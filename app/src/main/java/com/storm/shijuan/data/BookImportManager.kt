package com.storm.shijuan.data

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 当前导入任务状态
 */
data class ImportTask(
    val uri: Uri,
    val fileName: String,
    val type: ImportType,
    val progress: Float = 0f,
    val stage: String = "正在准备...",
    val isBackground: Boolean = false,
    val isRunning: Boolean = true,
)

/**
 * 书籍与漫画导入任务统一管理器：
 * 负责调度导入流程、维护 UI 状态流、支持前台弹窗与系统通知栏后台导入的双向切换。
 */
object BookImportManager {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var importJob: Job? = null

    var currentTask by mutableStateOf<ImportTask?>(null)
        private set

    fun isImporting(): Boolean = currentTask?.isRunning == true

    /**
     * 开启导入任务（默认先弹出前台弹窗展示进度条，用户可随时点击「后台导入」转入通知栏）
     */
    fun startImport(
        context: Context,
        uri: Uri,
        fileName: String,
        type: ImportType,
        store: Store,
        onSuccess: () -> Unit = {}
    ) {
        if (isImporting()) {
            Toast.makeText(context, "已有书籍正在导入中，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }

        val appContext = context.applicationContext
        currentTask = ImportTask(
            uri = uri,
            fileName = fileName,
            type = type,
            progress = 0f,
            stage = "正在准备导入...",
            isBackground = false,
            isRunning = true,
        )

        importJob = scope.launch {
            val result = if (type == ImportType.COMIC) {
                LocalImporter.importComic(appContext, uri, fileName, store) { p, stageText ->
                    updateProgress(appContext, p, stageText)
                }
            } else {
                LocalImporter.importNovel(appContext, uri, fileName, store) { p, stageText ->
                    updateProgress(appContext, p, stageText)
                }
            }

            withContext(Dispatchers.Main) {
                val wasBackground = currentTask?.isBackground == true
                val label = if (type == ImportType.COMIC) "漫画书架" else "小说书架"

                result.onSuccess {
                    if (wasBackground) {
                        BookImportService.notifyComplete(appContext, fileName, label)
                    }
                    Toast.makeText(appContext, "《$fileName》已成功导入并加入 $label！", Toast.LENGTH_SHORT).show()
                    currentTask = null
                    onSuccess()
                }.onFailure { err ->
                    val errorMsg = err.message ?: "未知错误"
                    if (wasBackground) {
                        BookImportService.notifyError(appContext, fileName, errorMsg)
                    }
                    Toast.makeText(appContext, "导入失败: $errorMsg", Toast.LENGTH_LONG).show()
                    currentTask = null
                }
            }
        }
    }

    private fun updateProgress(context: Context, progress: Float, stage: String) {
        val task = currentTask ?: return
        val clamped = progress.coerceIn(0f, 1f)
        currentTask = task.copy(progress = clamped, stage = stage)
        if (task.isBackground) {
            BookImportService.updateProgress(context, task.fileName, (clamped * 100).toInt(), stage)
        }
    }

    /**
     * 将当前导入任务转入后台运行：
     * 关闭前台交互弹窗，同时启动前台通知服务在系统通知栏实时显示进度条
     */
    fun moveToBackground(context: Context) {
        val task = currentTask ?: return
        if (task.isBackground) return
        currentTask = task.copy(isBackground = true)
        BookImportService.start(
            context = context.applicationContext,
            fileName = task.fileName,
            progress = (task.progress * 100).toInt(),
            stage = task.stage
        )
    }

    /**
     * 关闭弹窗状态
     */
    fun dismiss() {
        currentTask = null
    }
}
