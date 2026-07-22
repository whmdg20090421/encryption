package com.whmdg.mczj.tools.ui.accounting

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.*
import kotlin.random.Random

/**
 * 基于小型 MLP 的备注预测器。
 * 输入特征：一级分类 + 二级分类 + 金额 + 时间 + 备注字符 bag-of-words
 * 输出：16 维 embedding，用于最近邻查找历史备注。
 * 支持 on-device 在线训练。
 */
object NotePredictor {

    // ─── 网络结构 ───
    private const val CAT1_SIZE = 22       // 一级分类 one-hot
    private const val CAT2_SIZE = 30       // 二级分类 one-hot（预留空间）
    private const val NUM_SIZE = 3         // amount + sin(hour) + cos(hour)
    private const val CHAR_HASH_SIZE = 64  // 备注字符 hash bag-of-words
    private const val INPUT_SIZE = CAT1_SIZE + CAT2_SIZE + NUM_SIZE + CHAR_HASH_SIZE  // 119
    private const val HIDDEN1 = 32
    private const val HIDDEN2 = 16
    private const val EMBEDDING_DIM = HIDDEN2

    // ─── 训练参数 ───
    private const val LEARNING_RATE = 0.001f
    private const val TRAIN_STEPS = 8
    private const val MIN_RECORDS = 10
    private const val HISTORY_REPLAY = 5
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

    // ─── 分类索引映射 ───
    private val cat1Index = mutableMapOf<String, Int>()
    private val cat2Index = mutableMapOf<String, Int>()
    private var nextCat1 = 0
    private var nextCat2 = 0

    private fun getCat1Idx(id: String): Int {
        return cat1Index.getOrPut(id) { (nextCat1++).coerceAtMost(CAT1_SIZE - 1) }
    }

    private fun getCat2Idx(id: String): Int {
        return cat2Index.getOrPut(id) { (nextCat2++).coerceAtMost(CAT2_SIZE - 1) }
    }

    // ─── 初始化 ───
    fun ensureInitialized(context: Context) {
        if (initialized) return
        initialized = true
        val dir = File(AccountingRepository.getAccountingDir(context), "ai_model")
        if (!dir.exists()) dir.mkdirs()
        val weightsFile = File(dir, "weights.json")
        val embeddingsFile = File(dir, "embeddings.json")
        if (weightsFile.exists() && embeddingsFile.exists()) {
            restoreState(weightsFile, embeddingsFile)
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
            val emb = forward(getFeatures(record.categoryId, record.subcategoryId ?: "", amt, extractHour(record.happenedAt), record.note))
            embeddings.add(NoteEmbedding(record.note, emb.toList(), record.categoryId, record.subcategoryId ?: "", amt, extractHour(record.happenedAt)))
        }
    }

    // ─── 特征编码 ───
    private fun getFeatures(cat1: String, cat2: String, amount: Float, hour: Int, note: String): FloatArray {
        val f = FloatArray(INPUT_SIZE)
        val i1 = getCat1Idx(cat1)
        if (i1 < CAT1_SIZE) f[i1] = 1f
        val i2 = getCat2Idx(cat2)
        if (i2 < CAT2_SIZE) f[CAT1_SIZE + i2] = 1f
        val base = CAT1_SIZE + CAT2_SIZE
        f[base] = (amount / 10000f).coerceIn(0f, 1f)
        f[base + 1] = sin(hour * PI.toFloat() / 12f)
        f[base + 2] = cos(hour * PI.toFloat() / 12f)
        val charBase = base + NUM_SIZE
        for (ch in note) {
            val idx = (ch.code.hashCode() and 0x7FFFFFFF) % CHAR_HASH_SIZE
            f[charBase + idx] += 1f
        }
        // 备注特征加权
        for (i in charBase until charBase + CHAR_HASH_SIZE) {
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
    private fun forward(input: FloatArray): FloatArray {
        // Layer 1: ReLU
        val h1 = FloatArray(HIDDEN1)
        for (i in 0 until HIDDEN1) {
            var sum = b1[i]
            for (j in 0 until INPUT_SIZE) sum += w1[i][j] * input[j]
            h1[i] = max(0f, sum)
        }
        // Layer 2: linear (embedding)
        val out = FloatArray(HIDDEN2)
        for (i in 0 until HIDDEN2) {
            var sum = b2[i]
            for (j in 0 until HIDDEN1) sum += w2[i][j] * h1[j]
            out[i] = sum
        }
        return out
    }

    // ─── 训练（对比学习：拉近同备注，推远不同备注）───
    private fun trainSingle(cat1: String, cat2: String, amount: Float, hour: Int, note: String) {
        val features = getFeatures(cat1, cat2, amount, hour, note)
        for (step in 0 until TRAIN_STEPS) {
            // 前向
            val h1Raw = FloatArray(HIDDEN1)
            for (i in 0 until HIDDEN1) {
                var sum = b1[i]
                for (j in 0 until INPUT_SIZE) sum += w1[i][j] * features[j]
                h1Raw[i] = sum
            }
            val h1 = FloatArray(HIDDEN1) { max(0f, h1Raw[it]) }
            val out = FloatArray(HIDDEN2)
            for (i in 0 until HIDDEN2) {
                var sum = b2[i]
                for (j in 0 until HIDDEN1) sum += w2[i][j] * h1[j]
                out[i] = sum
            }

            // 找正样本（同备注）和负样本（不同备注）
            val sameNote = embeddings.filter { it.note == note }
            val diffNote = embeddings.filter { it.note != note }
            if (sameNote.isEmpty() && diffNote.isEmpty()) break

            // 计算梯度（简化版对比损失）
            val gradOut = FloatArray(HIDDEN2)
            // 拉近同备注
            for (pos in sameNote) {
                for (d in 0 until HIDDEN2) gradOut[d] += (out[d] - pos.embedding[d]) * 0.1f
            }
            // 推远不同备注
            for (neg in diffNote.take(3)) {
                for (d in 0 until HIDDEN2) gradOut[d] -= (out[d] - neg.embedding[d]) * 0.05f
            }
            // 如果没有历史，用 MSE 逼近自身
            if (sameNote.isEmpty() && diffNote.isEmpty()) {
                for (d in 0 until HIDDEN2) gradOut[d] = out[d] * 0.01f
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
                    // Adam update
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

            // Layer 1 梯度（ReLU 导数）
            for (i in 0 until HIDDEN1) {
                val g = if (h1Raw[i] > 0) dh1[i] else 0f
                for (j in 0 until INPUT_SIZE) {
                    mW1[i][j] = beta1 * mW1[i][j] + (1 - beta1) * g * features[j]
                    vW1[i][j] = beta2 * vW1[i][j] + (1 - beta2) * (g * features[j]).pow(2)
                    val mHat = mW1[i][j] / (1 - beta1.pow(adamStep))
                    val vHat = vW1[i][j] / (1 - beta2.pow(adamStep))
                    w1[i][j] -= LEARNING_RATE * mHat / (sqrt(vHat) + eps)
                }
                mB1[i] = beta1 * mB1[i] + (1 - beta1) * g
                vB1[i] = beta2 * vB1[i] + (1 - beta2) * g.pow(2)
                val mHat = mB1[i] / (1 - beta1.pow(adamStep))
                val vHat = vB1[i] / (1 - beta2.pow(adamStep))
                b1[i] -= LEARNING_RATE * mHat / (sqrt(vHat) + eps)
            }
        }
    }

    // ─── 预测 ───
    data class Prediction(
        val note: String,
        val score: Int,
        val isAiGenerated: Boolean
    )

    /**
     * 当 embedding 数量不足 MIN_RECORDS 时，直接从 DB 最近记录中按文本匹配度返回最近 10 条备注。
     */
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

    /** embedding 数量是否达到 AI 预测门槛 */
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
            cat1Count = 0,  // one-hot 编码，无独立分类计数
            cat2Count = 0,  // one-hot 编码，无独立分类计数
            hitRate = rate,
            adamSteps = adamStep,
            totalCount = totalCount,
            hitCount = hitCount
        )
    }

    fun predict(cat1: String, cat2: String, amount: Float, hour: Int, partialNote: String): List<Prediction> {
        if (!initialized || embeddings.size < MIN_RECORDS || partialNote.isEmpty()) return emptyList()
        val features = getFeatures(cat1, cat2, amount, hour, partialNote)
        val queryEmb = forward(features)
        // 加权：备注相似度 × NOTE_WEIGHT + 其余特征相似度
        val scored = embeddings.map { ne ->
            val cosSim = cosineSimilarity(queryEmb, ne.embedding.toFloatArray())
            val noteTextSim = textSimilarity(partialNote, ne.note)
            // 综合分数：embedding 相似度 60% + 文本前缀匹配 40%
            val combined = (cosSim * 0.6f + noteTextSim * 0.4f)
            Prediction(ne.note, (combined * 100).toInt().coerceIn(0, 100), ne.isAiGenerated)
        }
        // 去重，取最高分
        val unique = scored.groupBy { it.note }
            .map { (note, preds) -> preds.maxByOrNull { it.score }!! }
            .filter { it.score >= (SIMILARITY_THRESHOLD * 100).toInt() }
            .sortedByDescending { it.score }
            .take(10)

        // 命中率追踪
        totalCount++
        return unique
    }

    /** 记录用户实际选择的备注，更新命中率 */
    fun recordHit(note: String) {
        hitCount++
    }

    /** 是否应该创建 AI 备注 */
    fun shouldCreateAiNote(): Boolean {
        if (totalCount < 20) return false
        return (hitCount.toFloat() / totalCount.coerceAtLeast(1)) < HIT_RATE_THRESHOLD
    }

    /** 从历史备注中提取关键词组合生成 AI 备注 */
    fun createAiNote(cat1: String, cat2: String, amount: Float, hour: Int, partialNote: String): String? {
        if (!shouldCreateAiNote()) return null
        // 找最相似的几条记录，提取共同关键词
        val features = getFeatures(cat1, cat2, amount, hour, partialNote)
        val queryEmb = forward(features)
        val nearest = embeddings
            .sortedByDescending { cosineSimilarity(queryEmb, it.embedding.toFloatArray()) }
            .take(5)
        if (nearest.isEmpty()) return null
        // 从最近的备注中提取 2-gram 频率最高的片段
        val ngrams = mutableMapOf<String, Int>()
        for (ne in nearest) {
            val note = ne.note
            for (i in 0 until note.length - 1) {
                val gram = note.substring(i, i + 2)
                ngrams[gram] = (ngrams[gram] ?: 0) + 1
            }
        }
        val topGrams = ngrams.entries.sortedByDescending { it.value }.take(3).map { it.key }
        if (topGrams.isEmpty()) return null
        // 组合成新备注
        return "✦" + topGrams.joinToString("")
    }

    // ─── 训练入口 ───
    fun train(context: Context, record: AccountingRecord) {
        if (record.note.isEmpty()) return
        ensureInitialized(context)
        val amt = record.amount.toFloatOrNull() ?: 0f
        trainSingle(record.categoryId, record.subcategoryId ?: "", amt, extractHour(record.happenedAt), record.note)
        val emb = forward(getFeatures(record.categoryId, record.subcategoryId ?: "", amt, extractHour(record.happenedAt), record.note))
        embeddings.add(NoteEmbedding(record.note, emb.toList(), record.categoryId, record.subcategoryId ?: "", amt, extractHour(record.happenedAt), false))
        // 后台保存
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
        // 简单字符重叠率
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
        val cat1Index: Map<String, Int>,
        val cat2Index: Map<String, Int>,
        val nextCat1: Int,
        val nextCat2: Int,
        val adamStep: Int,
        val hitCount: Int,
        val totalCount: Int
    )

    @Serializable
    private data class EmbeddingState(
        val embeddings: List<NoteEmbedding>
    )

    private fun saveState(dir: File) {
        try {
            val state = ModelState(
                w1 = w1.map { it.toList() },
                b1 = b1.toList(),
                w2 = w2.map { it.toList() },
                b2 = b2.toList(),
                cat1Index = cat1Index.toMap(),
                cat2Index = cat2Index.toMap(),
                nextCat1 = nextCat1,
                nextCat2 = nextCat2,
                adamStep = adamStep,
                hitCount = hitCount,
                totalCount = totalCount
            )
            File(dir, "weights.json").writeText(Json.encodeToString(state))
            File(dir, "embeddings.json").writeText(Json.encodeToString(EmbeddingState(embeddings.toList())))
        } catch (_: Exception) {}
    }

    private fun restoreState(weightsFile: File, embeddingsFile: File) {
        try {
            val state = Json.decodeFromString<ModelState>(weightsFile.readText())
            w1 = state.w1.map { it.toFloatArray() }.toTypedArray()
            b1 = state.b1.toFloatArray()
            w2 = state.w2.map { it.toFloatArray() }.toTypedArray()
            b2 = state.b2.toFloatArray()
            cat1Index.clear(); cat1Index.putAll(state.cat1Index)
            cat2Index.clear(); cat2Index.putAll(state.cat2Index)
            nextCat1 = state.nextCat1
            nextCat2 = state.nextCat2
            adamStep = state.adamStep
            hitCount = state.hitCount
            totalCount = state.totalCount
            initAdam()
            // 恢复 Adam 状态（简化：重置动量，不影响预测质量）
            val embState = Json.decodeFromString<EmbeddingState>(embeddingsFile.readText())
            embeddings.clear()
            embeddings.addAll(embState.embeddings)
        } catch (_: Exception) {
            initWeights()
        }
    }
}
