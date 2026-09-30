package org.lumina.reader.core.export

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lumina.reader.core.annotation.PageCoordinateTransformer
import org.lumina.reader.core.annotation.PdfAnnotation
import org.lumina.reader.core.model.PageEditSpec
import java.io.InputStream
import java.io.OutputStream

/**
 * 工业级 PDF 回存与导出引擎 (基于纯 JVM Apache PDFBox Android)
 *
 * 核心功能：
 * 1. 物理写入 ISO 32000-1 标准 /Annots 字典 (Ink 自由笔画、荧光笔)
 * 2. 物理应用页面组织变更 (页面旋转 90/180/270 度、重排、删除)
 * 3. 零 JNI/NDK 崩溃风险，天然 16KB Page Size 安全
 */
object PdfDocumentExporter {

    private var isInitialized = false

    private var tempDir: java.io.File? = null

    /**
     * 初始化 PDFBox 资源加载器
     */
    fun init(context: Context) {
        if (!isInitialized) {
            try {
                if (!PDFBoxResourceLoader.isReady()) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }
            } catch (_: Exception) {
                // 在纯 JVM 单元测试环境中，若 Context 为 mock 或缺少 Android assets，允许降级
            }
            tempDir = context.applicationContext.cacheDir
            isInitialized = true
        }
    }

    /**
     * 将包含手绘注释与页面组织编辑的文档导出保存至指定的输出流
     *
     * @param inputStream 原始 PDF 输入流
     * @param outputStream 目标保存输出流
     * @param annotations 页面注释映射表 (originalPageIndex -> List<PdfAnnotation>)
     * @param pageSpecs 页面排序与旋转规范 (virtualIndex -> PageEditSpec)
     * @param customTempDir 可选临时工作目录 (用于 ScratchFile 磁盘缓存，防止 OOM)
     */
    suspend fun exportPdf(
        inputStream: InputStream,
        outputStream: OutputStream,
        annotations: Map<Int, List<PdfAnnotation>>,
        pageSpecs: List<PageEditSpec>,
        customTempDir: java.io.File? = null
    ) = withContext(Dispatchers.IO) {
        // 配置混合内存限制策略 (2MB 内存缓冲，超量部分流式落盘到缓存区，杜绝 Dalvik Heap OOM)
        val targetTempDir = customTempDir ?: tempDir
        val memSetting = if (targetTempDir != null && targetTempDir.exists()) {
            com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(2 * 1024 * 1024).setTempDir(targetTempDir)
        } else {
            com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(2 * 1024 * 1024)
        }

        val originalDoc = PDDocument.load(inputStream, memSetting)
        try {
            val originalPageCount = originalDoc.numberOfPages

            // 判定是否有页面编辑（排序、旋转、删除）
            val hasPageEdits = pageSpecs.isNotEmpty() && (
                pageSpecs.size != originalPageCount ||
                pageSpecs.mapIndexed { idx, spec -> idx == spec.originalPageIndex && spec.normalizedRotation == 0 }.any { !it }
            )

            if (!hasPageEdits) {
                // 没有页面顺序变动与旋转，直接在原始页面上写入注释并保存
                for (pageIndex in 0 until originalPageCount) {
                    val pageAnnots = annotations[pageIndex]
                    if (!pageAnnots.isNullOrEmpty()) {
                        val page = originalDoc.getPage(pageIndex)
                        applyAnnotationsToPage(originalDoc, page, pageAnnots)
                    }
                }
            } else {
                // 有页面排序/旋转/删减：在原文档内部重构页面结构树，杜绝双文档克隆导致的大内存激增与 OOM
                val originalPages = (0 until originalPageCount).map { originalDoc.getPage(it) }

                // 1. 原位更新有效页面的批注与旋转角度
                for (spec in pageSpecs) {
                    if (spec.originalPageIndex in 0 until originalPageCount) {
                        val page = originalPages[spec.originalPageIndex]
                        if (spec.normalizedRotation != 0) {
                            page.rotation = (page.rotation + spec.normalizedRotation) % 360
                        }
                        val pageAnnots = annotations[spec.originalPageIndex]
                        if (!pageAnnots.isNullOrEmpty()) {
                            applyAnnotationsToPage(originalDoc, page, pageAnnots)
                        }
                    }
                }

                // 2. 清空原页面树索引并按新顺序重新挂载
                while (originalDoc.numberOfPages > 0) {
                    originalDoc.removePage(0)
                }

                for (spec in pageSpecs) {
                    if (spec.originalPageIndex in 0 until originalPageCount) {
                        originalDoc.addPage(originalPages[spec.originalPageIndex])
                    }
                }
            }

            originalDoc.save(outputStream)
        } finally {
            originalDoc.close()
        }
    }

    /**
     * 将本地注释放入 PDF 页面的 /Annots 字典中
     */
    private fun applyAnnotationsToPage(
        doc: PDDocument,
        page: PDPage,
        annotations: List<PdfAnnotation>
    ) {
        val box = page.cropBox ?: page.mediaBox ?: PDRectangle(595f, 842f)
        val pageWidthPt = box.width
        val pageHeightPt = box.height
        val originX = box.lowerLeftX
        val originY = box.lowerLeftY

        val currentAnnots = (page.annotations ?: emptyList()).toMutableList()

        for (annotation in annotations) {
            when (annotation) {
                is PdfAnnotation.Ink -> {
                    if (annotation.strokes.isEmpty() || annotation.strokes.all { it.isEmpty() }) continue

                    val inkAnnot = PDAnnotationMarkup()
                    inkAnnot.cosObject.setName(COSName.SUBTYPE, PDAnnotationMarkup.SUB_TYPE_INK)

                    // 解析 ARGB 颜色
                    val colorInt = annotation.color
                    val r = ((colorInt shr 16) and 0xFF) / 255f
                    val g = ((colorInt shr 8) and 0xFF) / 255f
                    val b = (colorInt and 0xFF) / 255f
                    inkAnnot.color = PDColor(floatArrayOf(r, g, b), PDDeviceRGB.INSTANCE)

                    // 荧光笔透明度
                    if (annotation.isHighlighter) {
                        inkAnnot.constantOpacity = 0.45f
                    }

                    // 边框样式与笔触粗细
                    val borderStyle = PDBorderStyleDictionary()
                    borderStyle.width = annotation.strokeWidthDp
                    inkAnnot.borderStyle = borderStyle

                    // 将归一化笔画映射为 PDF 物理点坐标并求包围盒
                    var minPdfX = Float.MAX_VALUE
                    var minPdfY = Float.MAX_VALUE
                    var maxPdfX = Float.MIN_VALUE
                    var maxPdfY = Float.MIN_VALUE

                    val pathList = mutableListOf<FloatArray>()

                    for (stroke in annotation.strokes) {
                        if (stroke.size < 2) continue
                        val coords = FloatArray(stroke.size * 2)
                        for (i in stroke.indices) {
                            val normPt = stroke[i]
                            val (pdfLocalX, pdfLocalY) = PageCoordinateTransformer.normalizedToPdfPoint(
                                normPt,
                                pageWidthPt,
                                pageHeightPt
                            )
                            val pdfX = originX + pdfLocalX
                            val pdfY = originY + pdfLocalY
                            coords[i * 2] = pdfX
                            coords[i * 2 + 1] = pdfY

                            if (pdfX < minPdfX) minPdfX = pdfX
                            if (pdfX > maxPdfX) maxPdfX = pdfX
                            if (pdfY < minPdfY) minPdfY = pdfY
                            if (pdfY > maxPdfY) maxPdfY = pdfY
                        }
                        pathList.add(coords)
                    }

                    if (pathList.isNotEmpty()) {
                        inkAnnot.inkList = pathList.toTypedArray()
                        val padding = annotation.strokeWidthDp * 2f
                        inkAnnot.rectangle = PDRectangle(
                            (minPdfX - padding).coerceAtLeast(originX),
                            (minPdfY - padding).coerceAtLeast(originY),
                            (maxPdfX - minPdfX + 2 * padding).coerceAtLeast(10f),
                            (maxPdfY - minPdfY + 2 * padding).coerceAtLeast(10f)
                        )
                        inkAnnot.page = page
                        currentAnnots.add(inkAnnot)
                        try {
                            inkAnnot.constructAppearances(doc)
                        } catch (_: Throwable) {
                            // 降级：若外观流构造发生异常或出现内存压力，安全保持标准 /InkList 字典结构
                        }
                    }
                }
                else -> {
                    // 预留其他类型扩展
                }
            }
        }

        page.annotations = currentAnnots
    }
}
