package com.whmdg.mczj.tools.ui.encryption

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.whmdg.mczj.tools.encryption.core.Argon2idKdf
import com.whmdg.mczj.tools.encryption.data.Argon2Params
import com.whmdg.mczj.tools.encryption.data.KdfType
import com.whmdg.mczj.tools.encryption.data.StorageLocation
import com.whmdg.mczj.tools.encryption.services.VaultService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BenchResult(val params: Argon2Params, val seconds: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultCreateScreen(
    vaultService: VaultService,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var vaultPath by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pwd1 by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }

    var encryptFilename by remember { mutableStateOf(true) }
    var encryptMetadata by remember { mutableStateOf(true) }
    var customEncryption by remember { mutableStateOf(false) }
    var algorithm by remember { mutableStateOf("AES-256-GCM") }

    var selectedPreset by remember { mutableStateOf("MEDIUM") }
    var lastStandardPreset by remember { mutableStateOf("MEDIUM") }

    var customTimeCostStr by remember { mutableStateOf("2") }
    var customMemoryCostMbStr by remember { mutableStateOf("64") }
    var customParallelismStr by remember { mutableStateOf("2") }

    var benchTimes by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var testingPresetKey by remember { mutableStateOf<String?>(null) }

    var busy by remember { mutableStateOf(false) }
    var showAlgoDialog by remember { mutableStateOf(false) }
    var vaultError by remember { mutableStateOf<Throwable?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    fun getAbsolutePathFromUri(context: Context, uri: android.net.Uri): String {
        if (uri.scheme == "file") {
            return uri.path ?: ""
        }
        if (uri.scheme == "content") {
            val docId = try {
                val path = uri.path
                if (path != null && path.startsWith("/tree/")) {
                    path.substring(6)
                } else {
                    uri.lastPathSegment
                }
            } catch (e: Exception) {
                null
            }
            if (docId != null) {
                val parts = docId.split(":")
                if (parts.size >= 2) {
                    val type = parts[0]
                    val relativePath = parts[1]
                    if ("primary".equals(type, ignoreCase = true)) {
                        val baseDir = android.os.Environment.getExternalStorageDirectory().absolutePath
                        return File(baseDir, relativePath).absolutePath
                    } else {
                        val baseDir = "/storage/$type"
                        if (File(baseDir).exists()) {
                            return File(baseDir, relativePath).absolutePath
                        }
                    }
                }
            }
        }
        return uri.path ?: uri.toString()
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            vaultPath = getAbsolutePathFromUri(context, it)
        }
    }

    fun getCustomParams(): Argon2Params? {
        val t = customTimeCostStr.toIntOrNull()
        val m = customMemoryCostMbStr.toIntOrNull()
        val p = customParallelismStr.toIntOrNull()
        
        if (t == null || t !in 1..100) {
            Toast.makeText(context, "迭代次数范围应在 1 至 100 之间", Toast.LENGTH_SHORT).show()
            return null
        }
        if (m == null || m !in 8..1024) {
            Toast.makeText(context, "内存占用范围应在 8MB 至 1024MB 之间", Toast.LENGTH_SHORT).show()
            return null
        }
        if (p == null || p !in 1..32) {
            Toast.makeText(context, "并行度范围应在 1 至 32 之间", Toast.LENGTH_SHORT).show()
            return null
        }
        return Argon2Params(t, m * 1024, p)
    }

    fun runSingleBenchmark(key: String, params: Argon2Params) {
        testingPresetKey = key
        coroutineScope.launch(Dispatchers.Default) {
            val testSalt = ByteArray(16)
            val sw = System.currentTimeMillis()
            var seconds = Double.POSITIVE_INFINITY
            try {
                Argon2idKdf.derive(
                    password = "bench",
                    salt = testSalt,
                    timeCost = params.timeCost,
                    memoryCostKb = params.memoryCostKb,
                    parallelism = params.parallelism
                )
                seconds = (System.currentTimeMillis() - sw) / 1000.0
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                benchTimes = benchTimes + (key to seconds)
                testingPresetKey = null
            }
        }
    }

    fun isExternalPath(path: String): Boolean {
        return path.startsWith("/storage/") || path.startsWith(Environment.getExternalStorageDirectory().absolutePath)
    }

    fun checkOrRequestStoragePermission(): Boolean {
        if (!isExternalPath(vaultPath)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true
            showPermissionDialog = true
            return false
        }
        // Android 10 以下不需要特殊申请
        return true
    }

    fun onSubmit() {
        val trimmedName = name.trim()
        if (vaultPath.isEmpty()) {
            vaultError = Exception("请先选择保险箱存放目录")
            return
        }
        if (trimmedName.isEmpty() || pwd1.isEmpty() || pwd2.isEmpty()) {
            vaultError = Exception("请先填齐所有必需选项")
            return
        }
        if (pwd1 != pwd2) {
            vaultError = Exception("两次输入密码不一致")
            return
        }
        if (vaultService.isNameTaken(trimmedName)) {
            vaultError = Exception("保险箱名称已存在")
            return
        }

        if (!checkOrRequestStoragePermission()) return

        val finalParams = when (selectedPreset) {
            "LOW" -> Argon2Params.LOW
            "HIGH" -> Argon2Params.HIGH
            "CUSTOM" -> {
                val p = getCustomParams()
                if (p == null) return
                p
            }
            else -> Argon2Params.MEDIUM
        }

        busy = true
        coroutineScope.launch(Dispatchers.Default) {
            try {
                val isAbsolute = File(vaultPath).isAbsolute
                val location = if (isAbsolute) StorageLocation.EXTERNAL else StorageLocation.INTERNAL
                val relativePath = File(vaultPath, trimmedName).absolutePath

                vaultService.createVault(
                    name = trimmedName,
                    location = location,
                    relativePath = relativePath,
                    password = pwd1,
                    encryptFilename = encryptFilename,
                    encryptMetadata = encryptMetadata,
                    customEncryption = customEncryption,
                    kdfType = KdfType.ARGON2ID,
                    argonParams = finalParams,
                    algorithm = algorithm
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "保险箱创建成功", Toast.LENGTH_SHORT).show()
                    onBack()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    vaultError = e
                    busy = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建保险箱") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp)
        ) {
            // 1. 文件夹路径
            item {
                Text("保险箱存放位置", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = vaultPath,
                    onValueChange = { vaultPath = it },
                    label = { Text("目录路径") },
                    trailingIcon = {
                        IconButton(onClick = { folderLauncher.launch(null) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "选择文件夹")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 2. 名称
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("保险箱名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 3. 功能开关
            item {
                ListItem(
                    headlineContent = { Text("加密文件名") },
                    supportingContent = { Text("原始文件名将被 AES-GCM 加密为 hex/哈希") },
                    trailingContent = {
                        Switch(checked = encryptFilename, onCheckedChange = { encryptFilename = it })
                    }
                )
                ListItem(
                    headlineContent = { Text("加密算法") },
                    supportingContent = { Text(algorithm) },
                    trailingContent = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier.clickable { showAlgoDialog = true }
                )
                ListItem(
                    headlineContent = { Text("加密元数据") },
                    supportingContent = { Text("文件的创建/修改时间随密文一起加密") },
                    trailingContent = {
                        Switch(checked = encryptMetadata, onCheckedChange = { encryptMetadata = it })
                    }
                )
                ListItem(
                    headlineContent = { Text("自定义混淆") },
                    supportingContent = { Text("开启后插入伪随机钉子，更安全但也更慢") },
                    trailingContent = {
                        Switch(checked = customEncryption, onCheckedChange = {
                            customEncryption = it
                        })
                    }
                )
            }

            // 4. KDF 基准测试 (常驻显示)
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text("Argon2id 内存对抗参数", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("选择解密强度档位（点击右侧的“测试”按钮可进行单项解密耗时测试）：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(12.dp))
            }

            val presetsList = listOf(
                Triple("LOW", "低档", Argon2Params.LOW),
                Triple("MEDIUM", "中档 (推荐)", Argon2Params.MEDIUM),
                Triple("HIGH", "高档", Argon2Params.HIGH)
            )

            items(presetsList) { (key, label, params) ->
                val isSelected = selectedPreset == key
                val time = benchTimes[key]
                
                ListItem(
                    headlineContent = { Text(label, fontWeight = FontWeight.Bold) },
                    supportingContent = {
                        Text("迭代次数=${params.timeCost}, 内存占用=${params.memoryCostKb / 1024}MB, 并行度=${params.parallelism}")
                    },
                    leadingContent = {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                selectedPreset = key
                                lastStandardPreset = key
                            }
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (time != null) {
                                val timeText = if (time.isInfinite()) "失败(内存不足)" else "${"%.2f".format(time)} 秒"
                                Text(timeText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Text("待测定", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Button(
                                onClick = { runSingleBenchmark(key, params) },
                                enabled = testingPresetKey == null,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(if (testingPresetKey == key) "中..." else "测试", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    modifier = Modifier.clickable {
                        selectedPreset = key
                        lastStandardPreset = key
                    }
                )
            }

            // 自定义项
            item {
                val isSelected = selectedPreset == "CUSTOM"
                val time = benchTimes["CUSTOM"]
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text("自定义", fontWeight = FontWeight.Bold) },
                        supportingContent = {
                            if (!isSelected) {
                                Text("自定义迭代次数、内存及并行度参数")
                            }
                        },
                        leadingContent = {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    val baseParams = when (lastStandardPreset) {
                                        "LOW" -> Argon2Params.LOW
                                        "HIGH" -> Argon2Params.HIGH
                                        else -> Argon2Params.MEDIUM
                                    }
                                    customTimeCostStr = baseParams.timeCost.toString()
                                    customMemoryCostMbStr = (baseParams.memoryCostKb / 1024).toString()
                                    customParallelismStr = baseParams.parallelism.toString()
                                    selectedPreset = "CUSTOM"
                                }
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (time != null) {
                                    val timeText = if (time.isInfinite()) "失败(内存不足)" else "${"%.2f".format(time)} 秒"
                                    Text(timeText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                } else {
                                    Text("待测定", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Button(
                                    onClick = { 
                                        val customParams = getCustomParams()
                                        if (customParams != null) {
                                            runSingleBenchmark("CUSTOM", customParams)
                                        }
                                    },
                                    enabled = testingPresetKey == null,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(if (testingPresetKey == "CUSTOM") "中..." else "测试", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        modifier = Modifier.clickable {
                            val baseParams = when (lastStandardPreset) {
                                "LOW" -> Argon2Params.LOW
                                "HIGH" -> Argon2Params.HIGH
                                else -> Argon2Params.MEDIUM
                            }
                            customTimeCostStr = baseParams.timeCost.toString()
                            customMemoryCostMbStr = (baseParams.memoryCostKb / 1024).toString()
                            customParallelismStr = baseParams.parallelism.toString()
                            selectedPreset = "CUSTOM"
                        }
                    )

                    if (isSelected) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = customTimeCostStr,
                                onValueChange = { input ->
                                    val sanitized = input.filter { it.isDigit() }
                                    customTimeCostStr = sanitized
                                },
                                label = { Text("迭代次数") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = customMemoryCostMbStr,
                                onValueChange = { input ->
                                    val sanitized = input.filter { it.isDigit() }
                                    customMemoryCostMbStr = sanitized
                                },
                                label = { Text("内存(MB)") },
                                modifier = Modifier.weight(1.5f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            OutlinedTextField(
                                value = customParallelismStr,
                                onValueChange = { input ->
                                    val sanitized = input.filter { it.isDigit() }
                                    customParallelismStr = sanitized
                                },
                                label = { Text("并行度") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
            }

            // 5. 密码及确认
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                var pwd1Visible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = pwd1,
                    onValueChange = { pwd1 = it },
                    label = { Text("密码") },
                    visualTransformation = if (pwd1Visible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                var pwd2Visible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = pwd2,
                    onValueChange = { pwd2 = it },
                    label = { Text("再次输入密码") },
                    visualTransformation = if (pwd2Visible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 6. 确定创建
            item {
                Button(
                    onClick = { onSubmit() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("创建保险箱")
                }
            }
        }

        if (showAlgoDialog) {
            AlertDialog(
                onDismissRequest = { showAlgoDialog = false },
                title = { Text("选择加密算法") },
                text = {
                    Column {
                        listOf("AES-256-GCM", "AES-128-GCM", "ChaCha20-Poly1305").forEach { name ->
                            ListItem(
                                headlineContent = { Text(name) },
                                leadingContent = {
                                    RadioButton(selected = algorithm == name, onClick = {
                                        algorithm = name
                                        showAlgoDialog = false
                                    })
                                },
                                modifier = Modifier.clickable {
                                    algorithm = name
                                    showAlgoDialog = false
                                }
                            )
                        }
                    }
                },
                confirmButton = {}
            )
        }

        ErrorDialog(error = vaultError, onDismiss = { vaultError = null })

        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionDialog = false },
                title = { Text("需要存储权限") },
                text = {
                    Text("创建保险箱到外部存储需要「所有文件访问」权限。请在系统设置中开启。")
                },
                confirmButton = {
                    Button(onClick = {
                        showPermissionDialog = false
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                context.startActivity(intent)
                            } catch (ex: Exception) {
                                vaultError = Exception("无法跳转权限设置页面，请手动前往系统设置 -> 应用 -> 工具箱 -> 所有文件访问权限 开启")
                            }
                        }
                    }) {
                        Text("前往设置")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}
