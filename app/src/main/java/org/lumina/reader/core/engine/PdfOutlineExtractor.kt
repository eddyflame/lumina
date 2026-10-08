package org.lumina.reader.core.engine

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import org.lumina.reader.core.model.PdfOutlineItem
import java.io.InputStream

/**
 * 基于 Apache PDFBox Android 的真实 PDF 目录大纲 (TOC / Bookmarks) 提取器
 *
 * 递归解析 PDF 规范标准 /Outlines 书签树，提取多级章节名称、层级深度与对应目标页码。
 */
object PdfOutlineExtractor {

    /**
     * 从输入流中解析真实 PDF 书签大纲树
     */
    fun extractOutlines(inputStream: InputStream): List<PdfOutlineItem> {
        val memSetting = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(1024 * 1024)
        val doc = PDDocument.load(inputStream, memSetting)
        return try {
            extractOutlines(doc)
        } finally {
            doc.close()
        }
    }

    /**
     * 从已加载的 PDDocument 中遍历抽取树状大纲并转换为带缩进层级的数据模型
     */
    fun extractOutlines(doc: PDDocument): List<PdfOutlineItem> {
        val outline = doc.documentCatalog.documentOutline ?: return emptyList()
        val list = mutableListOf<PdfOutlineItem>()
        val pages = doc.pages

        fun traverse(item: PDOutlineItem, level: Int) {
            val title = item.title?.trim()
            val dest = item.destination
            val page = try {
                when (dest) {
                    is PDPageDestination -> dest.page
                    is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(dest)
                    else -> {
                        val action = item.action
                        if (action is PDActionGoTo) {
                            when (val actionDest = action.destination) {
                                is PDPageDestination -> actionDest.page
                                is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(actionDest)
                                else -> null
                            }
                        } else null
                    }
                }
            } catch (_: Exception) {
                null
            }

            val pageIndex = if (page != null) pages.indexOf(page) else {
                try {
                    when (dest) {
                        is PDPageDestination -> dest.retrievePageNumber()
                        else -> {
                            val action = item.action
                            if (action is PDActionGoTo && action.destination is PDPageDestination) {
                                (action.destination as PDPageDestination).retrievePageNumber()
                            } else -1
                        }
                    }
                } catch (_: Exception) {
                    -1
                }
            }

            if (!title.isNullOrBlank() && pageIndex in 0 until doc.numberOfPages) {
                list.add(PdfOutlineItem(title = title, pageIndex = pageIndex, level = level))
            }

            var child = item.firstChild
            while (child != null) {
                traverse(child, level + 1)
                child = child.nextSibling
            }
        }

        var current = outline.firstChild
        while (current != null) {
            traverse(current, 0)
            current = current.nextSibling
        }

        return list
    }
}
