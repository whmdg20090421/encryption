package com.whmdg.mczj.tools.ui.accounting

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.*
import kotlin.random.Random

/**
 * 基于小型 MLP 的备注预测器。
 * 输入特征：cat1 embedding(8) + cat2 embedding(8) + 数值(3) + bag-of-words(64) = 83 维
 * 输出：16 维 embedding，用于最近邻查找历史备注。
 * 分类特征使用动态 Embedding Table，类别增减不影响隐藏层权重。
 */
object NotePredictor {

    // ─── 网络结构 ───
    private const val CAT1_EMB_DIM = 8     // 一级分类 embedding 维度
    private const val CAT2_EMB_DIM = 8     // 二级分类 embedding 维度
    private const val NUM_SIZE = 3         // amount + sin(hour) + cos(hour)
    private const val CHAR_HASH_SIZE = 64  // 备注字符 hash bag-of-words
    private const val INPUT_SIZE = CAT1_EMB_DIM + CAT2_EMB_DIM + NUM_SIZE + CHAR_HASH_SIZE  // 83
    private const val HIDDEN1 = 32
    private const val HIDDEN2 = 16

    // ─── 训练参数 ───
    private const val LEARNING_RATE = 0.001f
    private const val TRAIN_STEPS = 2
    private const val MIN_RECORDS = 10
    private const val HIT_RATE_THRESHOLD = 0.30f

    // ─── 预测参数 ───
    private const val SIMILARITY_THRESHOLD = 0.70f
    private const val NOTE_WEIGHT = 2.0f

    // ─── 权重 ───
    private lateinit var w1: Array<FloatArray>  // [HIDDEN1][INPUT_SIZE]
    private lateinit var b1: FloatArray          // [HIDDEN1]
    private lateinit var w2: Array<FloatArray>  // [HIDDEN2][HIDDEN1]
    private lateinit var b2: FloatArray          // [HIDDEN2]

    // ─── Adam 优化器状态 ───
    private lateinit var mW1: Array<FloatArray>
    private lateinit var vW1: Array<FloatArray>
    private lateinit var mB1: FloatArray
    private lateinit var vB1: FloatArray
    private lateinit var mW2: Array<FloatArray>
    private lateinit var vW2: Array<FloatArray>
    private lateinit var mB2: FloatArray
    private lateinit var vB2: FloatArray
    private var adamStep = 0

    // ─── 分类 Embedding Table ───
    private val cat1Emb = mutableMapOf<String, FloatArray>()  // cat1 ID → embedding
    private val cat2Emb = mutableMapOf<String, FloatArray>()  // cat2 ID → embedding

    // ─── 历史 embedding ───
    @Serializable
    data class NoteEmbedding(
        val note: String,
        val embedding: List<Float>,
        val category1: String = "",
        val category2: String = "",
        val amount: Float = 0f,
        val hour: Int = 0,
        val isAiGenerated: Boolean = false
    )

    private val embeddings = mutableListOf<NoteEmbedding>()
    private var hitCount = 0
    private var totalCount = 0
    private var initialized = false

    // ─── Embedding 查表 ───
    private fun lookupEmb(id: String, table: MutableMap<String, FloatArray>, dim: Int): FloatArray {
        return table.getOrPut(id) { FloatArray(dim) { Random.nextFloat() * 0.1f - 0.05f } }
    }

    /** 重置模型（覆盖导入时调用，清除旧数据后从头训练） */
    fun reset() {
        initWeights()
        cat1Emb.clear()
        cat2Emb.clear()
        embeddings.clear()
        hitCount = 0
        totalCount = 0
        initialized = true
    }

    // ─── 初始化 ───
    fun ensureInitialized(context: Context) {
        if (initialized) return
        initialized = true
        val dir = File(AccountingRepository.getAccountingDir(context), "ai_model")
        if (!dir.exists()) dir.mkdirs()
        val weightsFile = File(dir, "weights.json")
        val embeddingsFile = File(dir, "embeddings.json")
        val embTableFile = File(dir, "embedding_table.json")
        if (weightsFile.exists() && embeddingsFile.exists()) {
            restoreState(weightsFile, embeddingsFile, embTableFile)
        } else {
            initWeights()
            initFromHistory(context)
            saveState(dir)
        }
    }

    private fun initWeights() {
        val r1 = sqrt(2.0f / INPUT_SIZE)
        val r2 = sqrt(2.0f / HIDDEN1)
        w1 = Array(HIDDEN1) { FloatArray(INPUT_SIZE) { Random.nextFloat() * 2 * r1 - r1 } }
        b1 = FloatArray(HIDDEN1)
        w2 = Array(HIDDEN2) { FloatArray(HIDDEN1) { Random.nextFloat() * 2 * r2 - r2 } }
        b2 = FloatArray(HIDDEN2)
        initAdam()
    }

    private fun initAdam() {
        mW1 = Array(HIDDEN1) { FloatArray(INPUT_SIZE) }
        vW1 = Array(HIDDEN1) { FloatArray(INPUT_SIZE) }
        mB1 = FloatArray(HIDDEN1)
        vB1 = FloatArray(HIDDEN1)
        mW2 = Array(HIDDEN2) { FloatArray(HIDDEN1) }
        vW2 = Array(HIDDEN2) { FloatArray(HIDDEN1) }
        mB2 = FloatArray(HIDDEN2)
        vB2 = FloatArray(HIDDEN2)
        adamStep = 0
    }

    private fun initFromHistory(context: Context) {
        val records = AccountingRepository.getAllRecords(context)
        if (records.size < MIN_RECORDS) return
        for (record in records) {
            if (record.note.isEmpty()) continue
            val amt = record.amount.toFloatOrNull() ?: 0f
            trainSingle(record.categoryId, record.subcategoryId ?: "", amt, extractHour(record.happenedAt), record.note)
            val emb = forward(record.categoryId, record.subcategoryId ?: "", amt, extractHour(record.happenedAt), record.note)
            embeddings.add(NoteEmbedding(record.note, emb.toList(), record.categoryId, record.subcategoryId ?: "", amt, extractHour(record.happenedAt)))
        }
    }

    // ─── 特征编码（数值 + bag-of-words，不含分类）───
    private fun getNumericFeatures(amount: Float, hour: Int, note: String): FloatArray {
        val f = FloatArray(NUM_SIZE + CHAR_HASH_SIZE)
        f[0] = if (amount > 0f) (ln(amount + 1f) / ln(100001f)).coerceIn(0f, 1f) else 0f
        f[1] = sin(hour * PI.toFloat() / 12f)
        f[2] = cos(hour * PI.toFloat() / 12f)
        for (ch in note) {
            val idx = (ch.code.hashCode() and 0x7FFFFFFF) % CHAR_HASH_SIZE
            f[NUM_SIZE + idx] += 1f
        }
        for (i in NUM_SIZE until NUM_SIZE + CHAR_HASH_SIZE) {
            f[i] *= NOTE_WEIGHT
        }
        return f
    }

    private fun extractHour(timestamp: Long): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }

    // ─── 前向传播 ───
    private fun forward(cat1Id: String, cat2Id: String, amount: Float, hour: Int, note: String): FloatArray {
        val c1 = lookupEmb(cat1Id, cat1Emb, CAT1_EMB_DIM)
        val c2 = lookupEmb(cat2Id, cat2Emb, CAT2_EMB_DIM)
        val num = getNumericFeatures(amount, hour, note)
        val input = FloatArray(INPUT_SIZE)
        c1.copyInto(input, 0)
        c2.copyInto(input, CAT1_EMB_DIM)
        num.copyInto(input, CAT1_EMB_DIM + CAT2_EMB_DIM)

        val h1 = FloatArray(HIDDEN1)
        for (i in 0 until HIDDEN1) {
            var sum = b1[i]
            for (j in 0 until INPUT_SIZE) sum += w1[i][j] * input[j]
            h1[i] = max(0f, sum)
        }
        val out = FloatArray(HIDDEN2)
        for (i in 0 until HIDDEN2) {
            var sum = b2[i]
            for (j in 0 until HIDDEN1) sum += w2[i][j] * h1[j]
            out[i] = sum
        }
        return out
    }

    // ─── 训练（对比学习：拉近同备注，推远不同备注）───
    private fun trainSingle(cat1Id: String, cat2Id: String, amount: Float, hour: Int, note: String) {
        for (step in 0 until TRAIN_STEPS) {
            // 前向传播（手动展开以保留中间值）
            val c1 = lookupEmb(cat1Id, cat1Emb, CAT1_EMB_DIM)
            val c2 = lookupEmb(cat2Id, cat2Emb, CAT2_EMB_DIM)
            val num = getNumericFeatures(amount, hour, note)
            val input = FloatArray(INPUT_SIZE)
            c1.copyInto(input, 0)
            c2.copyInto(input, CAT1_EMB_DIM)
            num.copyInto(input, CAT1_EMB_DIM + CAT2_EMB_DIM)

            val h1Raw = FloatArray(HIDDEN1)
            for (i in 0 until HIDDEN1) {
                var sum = b1[i]
                for (j in 0 until INPUT_SIZE) sum += w1[i][j] * input[j]
                h1Raw[i] = sum
            }
            val h1 = FloatArray(HIDDEN1) { max(0f, h1Raw[it]) }
            val out = FloatArray(HIDDEN2)
            for (i in 0 until HIDDEN2) {
                var sum = b2[i]
                for (j in 0 until HIDDEN1) sum += w2[i][j] * h1[j]
                out[i] = sum
            }

            // 找正样本和负样本
            val sameNote = embeddings.filter { it.note == note }
            val diffNote = embeddings.filter { it.note != note }
            if (sameNote.isEmpty() && diffNote.isEmpty()) break

            // 计算梯度（归一化样本数）
            val gradOut = FloatArray(HIDDEN2)
            val posCount = sameNote.size.coerceAtLeast(1)
            val negCount = diffNote.take(3).size.coerceAtLeast(1)
            for (pos in sameNote) {
                for (d in 0 until HIDDEN2) gradOut[d] += (out[d] - pos.embedding[d]) * 0.1f / posCount
            }
            for (neg in diffNote.take(3)) {
                for (d in 0 until HIDDEN2) gradOut[d] -= (out[d] - neg.embedding[d]) * 0.05f / negCount
            }

            // 反向传播
            adamStep++
            val beta1 = 0.9f
            val beta2 = 0.999f
            val eps = 1e-8f

            // Layer 2 梯度
            val dh1 = FloatArray(HIDDEN1)
            for (i in 0 until HIDDEN2) {
                val g = gradOut[i]
                for (j in 0 until HIDDEN1) {
                    dh1[j] += w2[i][j] * g
                    mW2[i][j] = beta1 * mW2[i][j] + (1 - beta1) * g * h1[j]
                    vW2[i][j] = beta2 * vW2[i][j] + (1 - beta2) * (g * h1[j]).pow(2)
                    val mHat = mW2[i][j] / (1 - beta1.pow(adamStep))
                    val vHat = vW2[i][j] / (1 - beta2.pow(adamStep))
                    w2[i][j] -= LEARNING_RATE * mHat / (sqrt(vHat) + eps)
                }
                mB2[i] = beta1 * mB2[i] + (1 - beta1) * g
                vB2[i] = beta2 * vB2[i] + (1 - beta2) * g.pow(2)
                val mHat = mB2[i] / (1 - beta1.pow(adamStep))
                val vHat = vB2[i] / (1 - beta2.pow(adamStep))
                b2[i] -= LEARNING_RATE * mHat / (sqrt(vHat) + eps)
            }

            // Layer 1 梯度 + embedding 更新
            val gradC1 = FloatArray(CAT1_EMB_DIM)
            val gradC2 = FloatArray(CAT2_EMB_DIM)
            for (i in 0 until HIDDEN1) {
                val g = if (h1Raw[i] > 0) dh1[i] else 0f
                for (j in 0 until INPUT_SIZE) {
                    mW1[i][j] = beta1 * mW1[i][j] + (1 - beta1) * g * input[j]
                    vW1[i][j] = beta2 * vW1[i][j] + (1 - beta2) * (g * input[j]).pow(2)
                    val mHat = mW1[i][j] / (1 - beta1.pow(adamStep))
                    val vHat = vW1[i][j] / (1 - beta2.pow(adamStep))
                    w1[i][j] -= LEARNING_RATE * mHat / (sqrt(vHat) + eps)
                }
                // 累积 embedding 梯度
                for (k in 0 until CAT1_EMB_DIM) gradC1[k] += g * w1[i][k]
                for (k in 0 until CAT2_EMB_DIM) gradC2[k] += g * w1[i][CAT1_EMB_DIM + k]
                mB1[i] = beta1 * mB1[i] + (1 - beta1) * g
                vB1[i] = beta2 * vB1[i] + (1 - beta2) * g.pow(2)
                val mHat = mB1[i] / (1 - beta1.pow(adamStep))
                val vHat = vB1[i] / (1 - beta2.pow(adamStep))
                b1[i] -= LEARNING_RATE * mHat / (sqrt(vHat) + eps)
            }
            // 更新当前样本的 embedding
            for (k in 0 until CAT1_EMB_DIM) c1[k] -= LEARNING_RATE * gradC1[k]
            for (k in 0 until CAT2_EMB_DIM) c2[k] -= LEARNING_RATE * gradC2[k]
        }
    }

    // ─── 预测 ───
    data class Prediction(
        val note: String,
        val score: Int,
        val isAiGenerated: Boolean
    )

    fun predictFromRecent(context: Context, partialNote: String): List<Prediction> {
        if (partialNote.isEmpty()) return emptyList()
        val records = AccountingRepository.getAllRecords(context)
        val recentNotes = records
            .filter { it.note.isNotEmpty() }
            .sortedByDescending { it.happenedAt }
            .distinctBy { it.note }
            .take(50)
        if (recentNotes.isEmpty()) return emptyList()
        val scored = recentNotes.map { r ->
            val sim = textSimilarity(partialNote, r.note)
            Prediction(r.note, (sim * 100).toInt().coerceIn(0, 100), false)
        }
        return scored
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
            .take(10)
    }

    fun hasEnoughData(): Boolean = initialized && embeddings.size >= MIN_RECORDS

    // ─── 状态查询（供 UI 展示）───
    data class ModelInfo(
        val initialized: Boolean,
        val sampleCount: Int,
        val cat1Count: Int,
        val cat2Count: Int,
        val hitRate: Float,
        val adamSteps: Int,
        val totalCount: Int,
        val hitCount: Int
    )

    fun getModelInfo(): ModelInfo {
        val rate = if (totalCount > 0) hitCount.toFloat() / totalCount else 0f
        return ModelInfo(
            initialized = initialized,
            sampleCount = embeddings.size,
            cat1Count = cat1Emb.size,
            cat2Count = cat2Emb.size,
            hitRate = rate,
            adamSteps = adamStep,
            totalCount = totalCount,
            hitCount = hitCount
        )
    }

    fun retrainAll(context: Context) {
        reset()
        initFromHistory(context)
        val dir = File(AccountingRepository.getAccountingDir(context), "ai_model")
        saveState(dir)
    }

    fun deleteModel(context: Context) {
        val dir = File(AccountingRepository.getAccountingDir(context), "ai_model")
        dir.listFiles()?.forEach { it.delete() }
        reset()
        initialized = false
    }

    fun predict(cat1: String, cat2: String, amount: Float, hour: Int, partialNote: String): List<Prediction> {
        if (!initialized || embeddings.size < MIN_RECORDS || partialNote.isEmpty()) return emptyList()
        val queryEmb = forward(cat1, cat2, amount, hour, partialNote)
        val scored = embeddings.map { ne ->
            val cosSim = cosineSimilarity(queryEmb, ne.embedding.toFloatArray())
            val noteTextSim = textSimilarity(partialNote, ne.note)
            val combined = (cosSim * 0.6f + noteTextSim * 0.4f)
            Prediction(ne.note, (combined * 100).toInt().coerceIn(0, 100), ne.isAiGenerated)
        }
        val unique = scored.groupBy { it.note }
            .map { (note, preds) -> preds.maxByOrNull { it.score }!! }
            .filter { it.score >= (SIMILARITY_THRESHOLD * 100).toInt() }
            .sortedByDescending { it.score }
            .take(10)
        totalCount++
        return unique
    }

    fun recordHit(note: String) {
        hitCount++
    }

    fun shouldCreateAiNote(): Boolean {
        if (totalCount < 20) return false
        return (hitCount.toFloat() / totalCount.coerceAtLeast(1)) < HIT_RATE_THRESHOLD
    }

    fun createAiNote(cat1: String, cat2: String, amount: Float, hour: Int, partialNote: String): String? {
        if (!shouldCreateAiNote()) return null
        val queryEmb = forward(cat1, cat2, amount, hour, partialNote)
        val nearest = embeddings
            .sortedByDescending { cosineSimilarity(queryEmb, it.embedding.toFloatArray()) }
            .firstOrNull() ?: return null
        return "✦" + nearest.note
    }

    // ─── 训练入口 ───
    fun train(context: Context, record: AccountingRecord) {
        if (record.note.isEmpty()) return
        ensureInitialized(context)
        val amt = record.amount.toFloatOrNull() ?: 0f
        val hour = extractHour(record.happenedAt)
        trainSingle(record.categoryId, record.subcategoryId ?: "", amt, hour, record.note)
        val emb = forward(record.categoryId, record.subcategoryId ?: "", amt, hour, record.note)
        embeddings.add(NoteEmbedding(record.note, emb.toList(), record.categoryId, record.subcategoryId ?: "", amt, hour, false))
        val dir = File(AccountingRepository.getAccountingDir(context), "ai_model")
        saveState(dir)
    }

    // ─── 辅助函数 ───
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }

    private fun textSimilarity(input: String, target: String): Float {
        if (input.isEmpty() || target.isEmpty()) return 0f
        if (target.startsWith(input)) return 0.95f
        if (target.contains(input)) return 0.80f
        val inputSet = input.toSet()
        val targetSet = target.toSet()
        val overlap = inputSet.intersect(targetSet).size.toFloat()
        return (overlap / inputSet.size.coerceAtLeast(1)).coerceIn(0f, 1f) * 0.7f
    }

    // ─── 持久化 ───
    @Serializable
    private data class ModelState(
        val w1: List<List<Float>>,
        val b1: List<Float>,
        val w2: List<List<Float>>,
        val b2: List<Float>,
        val adamStep: Int,
        val hitCount: Int,
        val totalCount: Int
    )

    @Serializable
    private data class EmbeddingState(
        val embeddings: List<NoteEmbedding>
    )

    @Serializable
    private data class EmbeddingTableState(
        val cat1Emb: Map<String, List<Float>>,
        val cat2Emb: Map<String, List<Float>>
    )

    private fun saveState(dir: File) {
        try {
            val state = ModelState(
                w1 = w1.map { it.toList() },
                b1 = b1.toList(),
                w2 = w2.map { it.toList() },
                b2 = b2.toList(),
                adamStep = adamStep,
                hitCount = hitCount,
                totalCount = totalCount
            )
            File(dir, "weights.json").writeText(Json.encodeToString(state))
            File(dir, "embeddings.json").writeText(Json.encodeToString(EmbeddingState(embeddings.toList())))
            val embTable = EmbeddingTableState(
                cat1Emb = cat1Emb.mapValues { it.value.toList() },
                cat2Emb = cat2Emb.mapValues { it.value.toList() }
            )
            File(dir, "embedding_table.json").writeText(Json.encodeToString(embTable))
        } catch (e: Exception) {
            Log.e("NotePredictor", "保存模型状态失败", e)
        }
    }

    private fun restoreState(weightsFile: File, embeddingsFile: File, embTableFile: File) {
        try {
            val state = Json.decodeFromString<ModelState>(weightsFile.readText())
            w1 = state.w1.map { it.toFloatArray() }.toTypedArray()
            b1 = state.b1.toFloatArray()
            w2 = state.w2.map { it.toFloatArray() }.toTypedArray()
            b2 = state.b2.toFloatArray()
            adamStep = state.adamStep
            hitCount = state.hitCount
            totalCount = state.totalCount
            initAdam()
            val embState = Json.decodeFromString<EmbeddingState>(embeddingsFile.readText())
            embeddings.clear()
            embeddings.addAll(embState.embeddings)
            if (embTableFile.exists()) {
                val tableState = Json.decodeFromString<EmbeddingTableState>(embTableFile.readText())
                cat1Emb.clear()
                cat1Emb.putAll(tableState.cat1Emb.mapValues { it.value.toFloatArray() })
                cat2Emb.clear()
                cat2Emb.putAll(tableState.cat2Emb.mapValues { it.value.toFloatArray() })
            }
        } catch (e: Exception) {
            Log.e("NotePredictor", "恢复模型状态失败，重新初始化", e)
            initWeights()
        }
    }
}
