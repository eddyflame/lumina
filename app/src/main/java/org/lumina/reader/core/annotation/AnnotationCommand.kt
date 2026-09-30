package org.lumina.reader.core.annotation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 内存注释暂存仓库接口
 */
interface AnnotationStore {
    fun addAnnotation(annotation: PdfAnnotation)
    fun removeAnnotation(annotationId: String)
    fun getAnnotationsForPage(pageIndex: Int): List<PdfAnnotation>
}

/**
 * 可撤销/重做的注释操作命令契约
 */
interface AnnotationCommand {
    fun execute()
    fun undo()
}

/**
 * 添加注释命令
 */
class AddAnnotationCommand(
    private val store: AnnotationStore,
    private val annotation: PdfAnnotation
) : AnnotationCommand {
    override fun execute() {
        store.addAnnotation(annotation)
    }

    override fun undo() {
        store.removeAnnotation(annotation.id)
    }
}

/**
 * 删除注释命令
 */
class DeleteAnnotationCommand(
    private val store: AnnotationStore,
    private val annotation: PdfAnnotation
) : AnnotationCommand {
    override fun execute() {
        store.removeAnnotation(annotation.id)
    }

    override fun undo() {
        store.addAnnotation(annotation)
    }
}

/**
 * 撤销/重做命令栈管理器
 */
class UndoRedoManager {

    private val undoStack = ArrayDeque<AnnotationCommand>()
    private val redoStack = ArrayDeque<AnnotationCommand>()

    private val _canUndoFlow = MutableStateFlow(false)
    val canUndoFlow: StateFlow<Boolean> = _canUndoFlow.asStateFlow()

    private val _canRedoFlow = MutableStateFlow(false)
    val canRedoFlow: StateFlow<Boolean> = _canRedoFlow.asStateFlow()

    fun execute(command: AnnotationCommand) {
        command.execute()
        undoStack.addLast(command)
        redoStack.clear()
        updateFlows()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        val cmd = undoStack.removeLast()
        cmd.undo()
        redoStack.addLast(cmd)
        updateFlows()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val cmd = redoStack.removeLast()
        cmd.execute()
        undoStack.addLast(cmd)
        updateFlows()
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        updateFlows()
    }

    private fun updateFlows() {
        _canUndoFlow.value = undoStack.isNotEmpty()
        _canRedoFlow.value = redoStack.isNotEmpty()
    }
}
