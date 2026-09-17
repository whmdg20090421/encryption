package com.whmdg.mczj.tools.util

import io.github.rosemoe.sora.text.UndoManager
import io.github.rosemoe.sora.widget.CodeEditor
import java.lang.reflect.Field

/**
 * 通过反射读取 Sora [UndoManager] 的撤销栈指针。
 *
 * Sora 0.24.5 未对外暴露 `stackPointer`，但编辑器保存按钮的脏状态判断需要它：
 * 指针等于"当前生效的操作数"，当指针回到保存时刻的位置时，内容即与磁盘一致。
 *
 * 该字段属于私有实现细节，Sora 升级后可能失效。失效时 [readStackPointer] 返回 null，
 * 调用方据此降级为内容比较。
 */
object UndoStackInspector {

    private const val TAG = "UndoStackInspector"

    @Volatile
    private var stackPointerField: Field? = null

    @Volatile
    private var broken = false

    /**
     * 读取编辑器的撤销栈指针。
     *
     * @return 当前栈指针；反射不可用时返回 null
     */
    fun readStackPointer(editor: CodeEditor?): Int? {
        val manager = editor?.text?.undoManager ?: return null
        return readStackPointer(manager)
    }

    private fun readStackPointer(manager: UndoManager): Int? {
        if (broken) return null
        val field = obtainField(manager.javaClass) ?: return null
        return try {
            field.getInt(manager)
        } catch (e: Exception) {
            broken = true
            DiagnosticLog.log(TAG, "读取 stackPointer 失败，降级为内容比较: ${e.message}")
            null
        }
    }

    private fun obtainField(clazz: Class<*>): Field? {
        stackPointerField?.let { return it }
        synchronized(this) {
            stackPointerField?.let { return it }
            return try {
                val field = clazz.getDeclaredField("stackPointer")
                field.isAccessible = true
                stackPointerField = field
                field
            } catch (e: NoSuchFieldException) {
                broken = true
                DiagnosticLog.log(TAG, "UndoManager 不存在 stackPointer 字段，降级为内容比较")
                null
            }
        }
    }
}
