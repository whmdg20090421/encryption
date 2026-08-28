# 7-Zip-JBinding-4Android API 指南

## 库信息

- **依赖**: `com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03`
- **包名**: `net.sf.sevenzipjbinding`
- **许可证**: LGPL v2.1

---

## 核心类

### SevenZip (主类)

```kotlin
import net.sf.sevenzipjbinding.SevenZip

// 打开压缩包（自动检测格式）
val inArchive = SevenZip.openInArchive(null, inStream)

// 打开压缩包（指定格式）
val inArchive = SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, inStream)

// 打开带密码的压缩包
val inArchive = SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, inStream, "password")

// 创建 7z 压缩包
val outArchive = SevenZip.openOutArchive7Zip()

// 创建 ZIP 压缩包
val outArchive = SevenZip.openOutArchiveZip()

// 创建通用格式压缩包
val outArchive = SevenZip.openOutArchive(ArchiveFormat.SEVEN_ZIP)
```

### ArchiveFormat (枚举)

```kotlin
import net.sf.sevenzipjbinding.ArchiveFormat

// 支持压缩的格式
ArchiveFormat.SEVEN_ZIP  // 7z
ArchiveFormat.ZIP        // ZIP
ArchiveFormat.TAR        // TAR
ArchiveFormat.GZIP       // TAR.GZ
ArchiveFormat.BZIP2      // TAR.BZ2

// 只支持解压的格式
ArchiveFormat.RAR        // RAR
ArchiveFormat.RAR5       // RAR5
ArchiveFormat.ISO        // ISO
ArchiveFormat.CAB        // CAB
// ... 更多格式
```

### PropID (属性ID)

```kotlin
import net.sf.sevenzipjbinding.PropID

PropID.PATH          // 文件路径
PropID.IS_FOLDER     // 是否是目录
PropID.SIZE          // 原始大小
PropID.PACKED_SIZE   // 压缩后大小
PropID.ATTRIBUTES    // 文件属性
PropID.CREATION_TIME // 创建时间
PropID.LAST_ACCESS_TIME // 最后访问时间
PropID.LAST_WRITE_TIME // 最后修改时间
```

---

## 解压操作

### 基本解压流程

```kotlin
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.RandomAccessFile

// 1. 打开压缩包
val raf = RandomAccessFile(archiveFile, "r")
val inStream = RandomAccessFileInStream(raf)
val inArchive = SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, inStream)

// 2. 获取文件数量
val itemCount = inArchive.getNumberOfItems()

// 3. 遍历并解压
for (i in 0 until itemCount) {
    val path = inArchive.getStringProperty(i, PropID.PATH)
    val isDir = inArchive.getStringProperty(i, PropID.IS_FOLDER) == "true"

    if (isDir) {
        File(outputDir, path).mkdirs()
        continue
    }

    val outputFile = File(outputDir, path)
    outputFile.parentFile?.mkdirs()

    val outRaf = RandomAccessFile(outputFile, "rw")
    val outStream = RandomAccessFileOutStream(outRaf)

    val extractCallback = object : IArchiveExtractCallback {
        override fun setTotal(total: Long) {}
        override fun setCompleted(complete: Long) {}
        override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
            if (index == i) {
                return object : ISequentialOutStream {
                    override fun write(data: ByteArray?): Int {
                        if (data != null) outStream.write(data)
                        return data?.size ?: 0
                    }
                }
            }
            return null
        }
        override fun prepareOperation(mode: ExtractAskMode) {}
        override fun setOperationResult(result: ExtractOperationResult) {}
    }

    inArchive.extract(null, false, extractCallback)
    outStream.close()
    outRaf.close()
}

// 4. 关闭
inArchive.close()
inStream.close()
raf.close()
```

### 带密码解压

```kotlin
val inArchive = SevenZip.openInArchive(
    ArchiveFormat.SEVEN_ZIP,
    inStream,
    "password"  // 直接传密码字符串
)
```

### 解压单个文件

```kotlin
// 使用 extractSlow 方法
val outStream = SequentialOutStream()
for (i in 0 until itemCount) {
    val path = inArchive.getStringProperty(i, PropID.PATH)
    if (path == targetFileName) {
        val result = inArchive.extractSlow(i, outStream)
        break
    }
}
```

---

## 压缩操作

### 基本压缩流程 (7z)

```kotlin
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileOutStream
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile

// 1. 创建输出压缩包
val raf = RandomAccessFile(outputFile, "rw")
val outStream = RandomAccessFileOutStream(raf)
val outArchive = SevenZip.openOutArchive7Zip()

// 2. 设置压缩级别
outArchive.setLevel(5)  // 0-9

// 3. 创建回调
val callback = object : IOutCreateCallback<IOutItem7z> {
    private val fileList = mutableListOf<Pair<String, ByteArray>>()

    override fun setTotal(total: Long) {}
    override fun setCompleted(complete: Long) {}
    override fun setOperationResult(operationResultOk: Boolean) {}

    override fun getItemInformation(
        index: Int,
        outItemFactory: OutItemFactory<IOutItem7z>
    ): IOutItem7z {
        val item = outItemFactory.createOutItem()

        // 首次调用时收集文件
        if (fileList.isEmpty()) {
            collectFiles(sourceDir, "")
        }

        if (index < fileList.size) {
            val (path, data) = fileList[index]
            item.setPropertyPath(path)
            item.setDataSize(data.size.toLong())
        }

        return item
    }

    override fun getStream(index: Int): ISequentialInStream? {
        if (index < fileList.size) {
            val (_, data) = fileList[index]
            return ByteArrayInputStream(data)
        }
        return null
    }

    private fun collectFiles(file: File, basePath: String) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { collectFiles(it, "$basePath${file.name}/") }
        } else {
            fileList.add(Pair("$basePath${file.name}", file.readBytes()))
        }
    }
}

// 4. 创建压缩包
outArchive.createArchive(outStream, fileList.size, callback)

// 5. 关闭
outArchive.close()
outStream.close()
raf.close()
```

### ZIP 压缩

```kotlin
val outArchive = SevenZip.openOutArchiveZip()

val callback = object : IOutCreateCallback<IOutItemZip> {
    // 类似 7z 的实现
}

outArchive.createArchive(outStream, fileList.size, callback)
```

### 带密码压缩

```kotlin
// 7z 格式支持加密头部
val outArchive = SevenZip.openOutArchive7Zip() as IOutFeatureSetEncryptHeader
outArchive.setHeaderEncryption(true)

// 设置密码
val callback = object : IOutCreateCallback<IOutItem7z> {
    // ... 其他方法

    override fun cryptoGetTextPassword(): String? {
        return "password"
    }
}
```

---

## 密码检测

### 方法1：尝试用假密码打开

```kotlin
fun checkPasswordRequired(archivePath: String): Boolean? {
    return try {
        val raf = RandomAccessFile(File(archivePath), "r")
        val inStream = RandomAccessFileInStream(raf)

        // 尝试用假密码打开
        val inArchive = SevenZip.openInArchive(
            ArchiveFormat.SEVEN_ZIP,
            inStream,
            "dummy"  // 假密码
        )

        inArchive.close()
        inStream.close()
        raf.close()

        false  // 不需要密码
    } catch (e: SevenZipException) {
        val msg = e.message?.lowercase() ?: ""
        when {
            msg.contains("password") || msg.contains("encrypted") -> true  // 需要密码
            else -> null  // 文件损坏
        }
    }
}
```

### 方法2：使用 IArchiveOpenCallback

```kotlin
val callback = object : IArchiveOpenCallback {
    override fun setTotal(files: Long?, bytes: Long?) {}
    override fun setCompleted(files: Long?, bytes: Long?) {}
}

val inArchive = SevenZip.openInArchive(
    ArchiveFormat.SEVEN_ZIP,
    inStream,
    callback
)
```

---

## 目录浏览

### 获取压缩包内容列表

```kotlin
val inArchive = SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, inStream)
val itemCount = inArchive.getNumberOfItems()

val entries = mutableListOf<ArchiveEntry>()
for (i in 0 until itemCount) {
    val path = inArchive.getStringProperty(i, PropID.PATH)
    val isDir = inArchive.getStringProperty(i, PropID.IS_FOLDER) == "true"
    val size = inArchive.getStringProperty(i, PropID.SIZE)?.toLongOrNull() ?: 0
    val compressedSize = inArchive.getStringProperty(i, PropID.PACKED_SIZE)?.toLongOrNull() ?: 0

    entries.add(ArchiveEntry(path, isDir, size, compressedSize))
}
```

### 构建目录树

```kotlin
data class ArchiveNode(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val children: MutableList<ArchiveNode> = mutableListOf()
)

fun buildTree(entries: List<ArchiveEntry>): ArchiveNode {
    val root = ArchiveNode("", true)

    for (entry in entries) {
        val parts = entry.path.split('/').filter { it.isNotEmpty() }
        var current = root

        for (i in parts.indices) {
            val part = parts[i]
            val existing = current.children.find { it.name == part }

            if (existing != null) {
                current = existing
            } else {
                val node = ArchiveNode(
                    name = part,
                    isDirectory = entry.isDirectory || i < parts.size - 1
                )
                current.children.add(node)
                current = node
            }
        }
    }

    return root
}
```

---

## 高级功能

### 进度回调

```kotlin
val callback = object : IProgress {
    override fun setTotal(total: Long) {
        // 设置总进度
        println("Total: $total")
    }

    override fun setCompleted(complete: Long) {
        // 更新已完成进度
        println("Completed: $complete")
    }
}
```

### 多线程解压

```kotlin
// 设置解压线程数
inArchive.setThreadCount(4)

// 解压所有文件
val indices = IntArray(itemCount) { it }
inArchive.extract(indices, false, extractCallback)
```

### 属性查询

```kotlin
// 获取文件属性
val attributes = inArchive.getProperty(i, PropID.ATTRIBUTES) as Int

// 获取创建时间
val creationTime = inArchive.getProperty(i, PropID.CREATION_TIME)

// 获取最后修改时间
val lastWriteTime = inArchive.getProperty(i, PropID.LAST_WRITE_TIME)
```

---

## 常见问题

### 1. 初始化失败

```kotlin
if (!SevenZip.isInitializedSuccessfully()) {
    val exception = SevenZip.getLastInitializationException()
    // 处理初始化失败
}
```

### 2. 密码错误

```kotlin
try {
    val inArchive = SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, inStream, "wrong_password")
} catch (e: SevenZipException) {
    // 密码错误
}
```

### 3. 文件损坏

```kotlin
try {
    val inArchive = SevenZip.openInArchive(ArchiveFormat.SEVEN_ZIP, inStream)
} catch (e: SevenZipException) {
    val msg = e.message?.lowercase() ?: ""
    if (msg.contains("cannot open") || msg.contains("corrupt")) {
        // 文件损坏
    }
}
```

---

## 注意事项

1. **必须关闭资源**: 使用完 `IInArchive` 和 `IOutCreateArchive` 后必须调用 `close()`
2. **密码处理**: 密码通过 `ICryptoGetTextPassword` 接口或 `String` 参数传递
3. **格式检测**: 可以传 `null` 给 `openInArchive` 让库自动检测格式
4. **内存使用**: 大文件解压时注意内存使用，建议流式处理
5. **线程安全**: 库本身不是线程安全的，不要在多线程中共享同一个 Archive 实例
