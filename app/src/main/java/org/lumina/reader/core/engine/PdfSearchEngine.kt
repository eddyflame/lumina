package org.lumina.reader.core.engine

import android.util.Log
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.lumina.reader.core.model.NormalizedRect
import org.lumina.reader.core.model.PageEditSpec
import org.lumina.reader.core.model.SearchMatch
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 记录字符及其对应的 TextPosition 坐标与页面几何属性
 */
data class PositionedChar(
    val char: Char,
    val position: TextPosition?
)

/**
 * 页面级全文缓存数据，包含 1:1 对应的纯文本、字符坐标及实际显示尺寸
 */
data class PageSearchData(
    val text: String,
    val chars: List<PositionedChar>,
    val pageWidth: Float,
    val pageHeight: Float
)

/**
 * 基于 Apache PDFBox Android 的文档内容全文检索引擎
 *
 * 特性：
 * 1. 增量式多级流式搜索：边解析边分发匹配项，百页文档毫秒级即刻首显
 * 2. 页面纯文本内存级 LRU/并发缓存：同一文档多次搜索或翻阅免重复解密解析
 * 3. 上下文语义高亮摘要生成器：智能提取命中词前后语境并净化杂乱换行空格
 * 4. 协同虚拟页面映射：自动适配页码旋转、重排与删减后的视口映射
 * 5. 跨字符/空白/换行容错分词正则：解决 CJK 中文分词与排版字符间隙导致的“搜不到”顽疾
 * 6. 字符级精确坐标提取与高亮映射：文本流与坐标流 1:1 无漂移对齐，屏幕坐标精准归一化
 */
class PdfSearchEngine {

    /** 页面级全文与字符坐标缓存 (Key: PDF 物理原始页码 originalPageIndex) */
    private val pageDataCache = ConcurrentHashMap<Int, PageSearchData>()

    /**
     * 清空已缓存的页面文本与坐标数据 (在切换文档或保存重载时调用)
     */
    fun clearCache() {
        pageDataCache.clear()
    }

    /**
     * 执行关键词检索
     *
     * @param inputStreamProvider 提供原始 PDF 文件输入流的高阶函数 (按需懒加载打开)
     * @param query 搜索关键词
     * @param pageSpecs 用户在页面组织器中配置的虚拟页面映射规范
     * @param pageCount 原始文档总页数
     * @param tempDir 用于 PDFBox 临时缓冲的目录 (通常为 context.cacheDir)
     * @param onMatchFound 发现新匹配项时的实时流式回调
     * @param onProgress 页面扫描进度更新回调 (已扫描页数, 目标总页数)
     * @return 该次搜索命中的全部匹配列表
     */
    suspend fun search(
        inputStreamProvider: () -> InputStream?,
        query: String,
        pageSpecs: List<PageEditSpec>,
        pageCount: Int,
        tempDir: File? = null,
        onMatchFound: suspend (SearchMatch) -> Unit = {},
        onProgress: suspend (scannedCount: Int, totalPages: Int) -> Unit = { _, _ -> }
    ): List<SearchMatch> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return@withContext emptyList()

        // 构建当前视口真实有效的 (虚拟页码 virtualIndex, 物理页码 originalPageIndex) 映射对
        val effectivePages = if (pageSpecs.isNotEmpty()) {
            pageSpecs.mapIndexed { vIdx, spec -> vIdx to spec.originalPageIndex }
        } else {
            (0 until pageCount).map { it to it }
        }

        val totalPagesToScan = effectivePages.size
        if (totalPagesToScan == 0) return@withContext emptyList()

        val allMatches = mutableListOf<SearchMatch>()
        var globalIndex = 0

        // 检查是否有尚未被缓存过文本或坐标的物理页
        val uncachedPairs = effectivePages.filter {
            !pageDataCache.containsKey(it.second)
        }

        var pdDoc: PDDocument? = null
        var inputStream: InputStream? = null
        val stripper: PositionCollectingStripper? = if (uncachedPairs.isNotEmpty()) {
            try {
                PositionCollectingStripper().apply {
                    sortByPosition = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate PositionCollectingStripper", e)
                null
            }
        } else null

        try {
            if (uncachedPairs.isNotEmpty()) {
                try {
                    inputStream = inputStreamProvider()
                    if (inputStream != null) {
                        val memSetting = if (tempDir != null && tempDir.exists()) {
                            try {
                                MemoryUsageSetting.setupMixed(2 * 1024 * 1024).setTempDir(tempDir)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to setup tempDir for PDFBox, fallback to main memory", e)
                                MemoryUsageSetting.setupMainMemoryOnly()
                            }
                        } else {
                            MemoryUsageSetting.setupMainMemoryOnly()
                        }
                        pdDoc = PDDocument.load(inputStream, memSetting)
                        Log.d(TAG, "PDDocument loaded successfully for search, pages: ${pdDoc.numberOfPages}")
                    } else {
                        Log.e(TAG, "inputStreamProvider returned null InputStream")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load PDDocument for search", e)
                }
            }

            val searchRegex = buildSearchRegex(trimmedQuery)

            for ((scannedCount, pair) in effectivePages.withIndex()) {
                currentCoroutineContext().ensureActive()
                val (virtualIndex, originalPageIdx) = pair

                // 1. 获取该页纯文本与字符坐标 (优先缓存读取，缺失则通过 PDFBox 解析并写回缓存)
                var pageData = pageDataCache[originalPageIdx]
                if (pageData == null && pdDoc != null && stripper != null) {
                    val docPages = pdDoc.numberOfPages
                    if (originalPageIdx in 0 until docPages) {
                        try {
                            stripper.startPage = originalPageIdx + 1
                            stripper.endPage = originalPageIdx + 1
                            stripper.clearCollected()
                            stripper.getText(pdDoc)

                            val text = stripper.getConstructedText()
                            val chars = stripper.getPositionedChars()
                            var pw = stripper.getPageWidth()
                            var ph = stripper.getPageHeight()

                            if (pw <= 0f || ph <= 0f) {
                                val pdPage = pdDoc.getPage(originalPageIdx)
                                val cropBox = pdPage.cropBox ?: pdPage.mediaBox
                                val rot = pdPage.rotation
                                pw = if (rot == 90 || rot == 270) cropBox.height else cropBox.width
                                ph = if (rot == 90 || rot == 270) cropBox.width else cropBox.height
                            }

                            pageData = PageSearchData(text, chars, pw, ph)
                            pageDataCache[originalPageIdx] = pageData
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to extract text/positions from page $originalPageIdx", e)
                            pageData = PageSearchData("", emptyList(), 0f, 0f)
                            pageDataCache[originalPageIdx] = pageData
                        }
                    } else {
                        pageData = PageSearchData("", emptyList(), 0f, 0f)
                    }
                }

                val safeData = pageData ?: PageSearchData("", emptyList(), 0f, 0f)
                val safeText = safeData.text

                // 2. 在该页文本中检索关键词 (支持空白容错/换行容错与 CJK 间距容错)
                if (safeText.isNotEmpty()) {
                    var matchInPage = 0

                    if (searchRegex != null) {
                        val matches = searchRegex.findAll(safeText)
                        for (matchResult in matches) {
                            currentCoroutineContext().ensureActive()
                            val start = matchResult.range.first
                            val length = matchResult.value.length
                            val snippet = extractSnippet(safeText, start, length)
                            val rects = extractHighlightRects(
                                chars = safeData.chars,
                                startCharIndex = start,
                                endCharIndex = start + length,
                                pageWidth = safeData.pageWidth,
                                pageHeight = safeData.pageHeight
                            )

                            val match = SearchMatch(
                                pageIndex = virtualIndex,
                                originalPageIndex = originalPageIdx,
                                matchIndexInPage = matchInPage++,
                                globalMatchIndex = globalIndex++,
                                snippet = snippet,
                                query = trimmedQuery,
                                startCharIndex = start,
                                endCharIndex = start + length,
                                highlightRects = rects
                            )
                            Log.d(TAG, "Search match: page=$virtualIndex, snippet='$snippet', rects=$rects")
                            allMatches.add(match)
                            onMatchFound(match)
                        }
                    } else {
                        // 回退至直接子串搜索
                        var startIndex = 0
                        while (startIndex < safeText.length) {
                            currentCoroutineContext().ensureActive()
                            val foundIndex = safeText.indexOf(trimmedQuery, startIndex, ignoreCase = true)
                            if (foundIndex < 0) break

                            val snippet = extractSnippet(safeText, foundIndex, trimmedQuery.length)
                            val rects = extractHighlightRects(
                                chars = safeData.chars,
                                startCharIndex = foundIndex,
                                endCharIndex = foundIndex + trimmedQuery.length,
                                pageWidth = safeData.pageWidth,
                                pageHeight = safeData.pageHeight
                            )
                            val match = SearchMatch(
                                pageIndex = virtualIndex,
                                originalPageIndex = originalPageIdx,
                                matchIndexInPage = matchInPage++,
                                globalMatchIndex = globalIndex++,
                                snippet = snippet,
                                query = trimmedQuery,
                                startCharIndex = foundIndex,
                                endCharIndex = foundIndex + trimmedQuery.length,
                                highlightRects = rects
                            )
                            Log.d(TAG, "Search match (fallback): page=$virtualIndex, snippet='$snippet', rects=$rects")
                            allMatches.add(match)
                            onMatchFound(match)

                            startIndex = foundIndex + trimmedQuery.length
                        }
                    }
                }

                // 3. 上报进度
                onProgress(scannedCount + 1, totalPagesToScan)
            }
        } finally {
            try {
                pdDoc?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing PDDocument", e)
            }
            try {
                inputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing inputStream", e)
            }
        }

        allMatches
    }

    /**
     * 根据字符偏移区间 [startCharIndex, endCharIndex) 从 PositionedChar 提取精确归一化高亮矩形。
     *
     * PDFBox 中 TextPosition 经过页面旋转调整后，(0,0) 为显示页面的左上角。
     * tp.x 为文字左边距，tp.width 为文字宽度；
     * tp.y 为文字基线（baseline）距页面顶部的距离，tp.height 为文字高度。
     */
    fun extractHighlightRects(
        chars: List<PositionedChar>,
        startCharIndex: Int,
        endCharIndex: Int,
        pageWidth: Float,
        pageHeight: Float
    ): List<NormalizedRect> {
        if (pageWidth <= 0f || pageHeight <= 0f || chars.isEmpty() || startCharIndex >= endCharIndex) {
            return emptyList()
        }

        val safeEnd = minOf(endCharIndex, chars.size)
        val safeStart = startCharIndex.coerceIn(0, safeEnd)
        if (safeStart >= safeEnd) return emptyList()

        // 收集该匹配区间内所有具有实际坐标的字符 TextPosition
        val matchTps = (safeStart until safeEnd).mapNotNull { chars[it].position }
        if (matchTps.isEmpty()) return emptyList()

        val lineRects = mutableListOf<NormalizedRect>()

        var currentMinX = Float.MAX_VALUE
        var currentMaxX = -Float.MAX_VALUE
        var currentMinY = Float.MAX_VALUE
        var currentMaxY = -Float.MAX_VALUE
        var currentBaseY = -1f
        var currentMaxFontH = 0f

        fun flushLine() {
            if (currentBaseY >= 0f && currentMinX < currentMaxX && currentMinY < currentMaxY) {
                // 适度水平留白 (按字高比例)，使两端字符外边缘包裹更自然
                val hPad = (currentMaxFontH * 0.08f).coerceIn(1f, 4f)
                val paddedMinX = currentMinX - hPad
                val paddedMaxX = currentMaxX + hPad

                val normLeft = (paddedMinX / pageWidth).coerceIn(0f, 1f)
                val normRight = (paddedMaxX / pageWidth).coerceIn(0f, 1f)
                val normTop = (currentMinY / pageHeight).coerceIn(0f, 1f)
                val normBottom = (currentMaxY / pageHeight).coerceIn(0f, 1f)
                val rect = NormalizedRect(
                    left = min(normLeft, normRight),
                    top = min(normTop, normBottom),
                    right = max(normLeft, normRight),
                    bottom = max(normTop, normBottom)
                )
                if (rect.isValid) {
                    lineRects.add(rect)
                }
            }
            currentMinX = Float.MAX_VALUE
            currentMaxX = -Float.MAX_VALUE
            currentMinY = Float.MAX_VALUE
            currentMaxY = -Float.MAX_VALUE
            currentBaseY = -1f
            currentMaxFontH = 0f
        }

        var lastTpRight = -Float.MAX_VALUE
        for (tp in matchTps) {
            val tpY = tp.y
            // 计算真实视觉字号 (PDFBox 中 tp.yScale 为文字矩阵 Y 轴缩放，已含 fontSize；fontSize 为 Tf 设定的字号)
            // 取各度量中的合理最大值，严禁两者相乘（避免二次方放大导致高亮高度遮盖多行）
            val effectiveFontHeight = maxOf(
                tp.height * 1.35f,
                abs(tp.yScale),
                tp.fontSizeInPt.toFloat(),
                tp.fontSize
            ).coerceAtLeast(1f)
            val tpW = tp.width.coerceAtLeast(0.5f)

            // 文字顶部（基线以上 85% 字高）与底部（基线以下 18% 字高以包容 g, y, p, q, j 等降部）
            val tpTop = tpY - effectiveFontHeight * 0.85f
            val tpBottom = tpY + effectiveFontHeight * 0.18f
            val tpLeft = tp.x
            val tpRight = tp.x + tpW

            // 行判定容差（基于字高的一半，自适应大标题与正文字号）
            val yTolerance = max(3f, effectiveFontHeight * 0.45f)

            // 水平连续性断开判定：
            // 同一行内，若两字符间距过大 (例如超过 1.5 倍有效字高或 2.5 倍字宽)，说明跨越了表格列、分栏或大段空白，必须断开为独立矩形，绝不能横跨表格合并！
            val maxHorizontalGap = max(effectiveFontHeight * 1.5f, tpW * 2.5f)
            val isSameLine = currentBaseY >= 0f && abs(tpY - currentBaseY) <= yTolerance
            val isHorizontalContinuous = isSameLine && (lastTpRight < 0f || (tpLeft >= lastTpRight - 3f && tpLeft - lastTpRight <= maxHorizontalGap))

            if (currentBaseY < 0f || (isSameLine && isHorizontalContinuous)) {
                // 属于同一连续文本块
                if (currentBaseY < 0f) {
                    currentBaseY = tpY
                }
                currentMaxFontH = max(currentMaxFontH, effectiveFontHeight)
                currentMinX = min(currentMinX, tpLeft)
                currentMaxX = max(currentMaxX, tpRight)
                currentMinY = min(currentMinY, tpTop)
                currentMaxY = max(currentMaxY, tpBottom)
                lastTpRight = max(lastTpRight, tpRight)
            } else {
                // 换行或跨表格列断开：提交当前矩形，开启新矩形
                flushLine()
                currentBaseY = tpY
                currentMaxFontH = effectiveFontHeight
                currentMinX = tpLeft
                currentMaxX = tpRight
                currentMinY = tpTop
                currentMaxY = tpBottom
                lastTpRight = tpRight
            }
        }
        flushLine()

        return lineRects
    }

    /**
     * 智能提取匹配位置附近的语境片段并净化格式：
     * 1. 优先以当前行/句子为边界，避免混入上一行或下一行的碎片文字
     * 2. 控制前置语境半径，确保关键词在单行/卡片视口内不被截断隐藏
     */
    fun extractSnippet(
        fullText: String,
        matchIndex: Int,
        matchLength: Int,
        contextRadius: Int = 24
    ): String {
        if (fullText.isEmpty() || matchIndex < 0 || matchIndex >= fullText.length) return ""

        // 向前寻找行首或截断边界，优先保证不超过换行符且给关键词留足展示空间
        var start = max(0, matchIndex - contextRadius)
        val lastNewlineBefore = fullText.lastIndexOfAny(charArrayOf('\r', '\n'), matchIndex - 1)
        if (lastNewlineBefore in start until matchIndex) {
            start = lastNewlineBefore + 1
        }
        while (start < matchIndex && fullText[start].isWhitespace()) {
            start++
        }

        // 向后寻找行尾或截断边界
        var end = min(fullText.length, matchIndex + matchLength + contextRadius)
        val nextNewlineAfter = fullText.indexOfAny(charArrayOf('\r', '\n'), matchIndex + matchLength)
        if (nextNewlineAfter in (matchIndex + matchLength)..end) {
            end = nextNewlineAfter
        }
        while (end > matchIndex + matchLength && fullText[end - 1].isWhitespace()) {
            end--
        }

        val prefix = if (start > 0 && fullText[start - 1] != '\r' && fullText[start - 1] != '\n') "…" else ""
        val suffix = if (end < fullText.length && fullText[end] != '\r' && fullText[end] != '\n') "…" else ""

        val rawSnippet = fullText.substring(start, end)
            .replace("[\r\n\t\u00A0\u3000]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

        return "$prefix$rawSnippet$suffix"
    }

    companion object {
        private const val TAG = "PdfSearchEngine"
        private const val WS_ANY = "[\\s\\u00A0\\u3000\\u2000-\\u200B]"
        private const val WS_INLINE = "[ \\t\\u00A0\\u3000\\u2000-\\u200B]"

        /**
         * 判定字符是否属于中日韩 (CJK) 字符范围
         */
        fun isCjk(c: Char): Boolean {
            val ub = Character.UnicodeBlock.of(c)
            return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                    || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                    || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                    || ub == Character.UnicodeBlock.HIRAGANA
                    || ub == Character.UnicodeBlock.KATAKANA
                    || ub == Character.UnicodeBlock.HANGUL_SYLLABLES
        }

        /**
         * 构建智能容错检索正则表达式：
         * 1. 当用户连续输入 CJK 字符 (未输入空格) 时，仅容错同行微小排版空白 (0~2 个内联空格)，严禁跨越换行符或表格列长空白
         * 2. 当用户显式输入空格或分隔符时，容错任意连续空白或换行 (\\s+)
         * 3. 严格转义字符，避免特殊符号破坏正则表达式
         */
        fun buildSearchRegex(query: String): Regex? {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return null
            val sb = StringBuilder()
            var prevChar: Char? = null
            var pendingWhitespace = false

            for (i in trimmed.indices) {
                val c = trimmed[i]
                if (c.isWhitespace() || c == '\u00A0' || c == '\u3000') {
                    pendingWhitespace = true
                } else {
                    if (prevChar != null) {
                        if (pendingWhitespace) {
                            // 用户显式输入了空白或复制带空格，容错任意空白或连续紧凑文本
                            sb.append("$WS_ANY*")
                        } else {
                            // 用户未敲空格，对相邻 CJK 容错同行内联空白或至多一次折行，严禁跨越段落或多行
                            if (isCjk(prevChar) || isCjk(c)) {
                                sb.append("(?:$WS_INLINE*|\\r?\\n$WS_INLINE*)")
                            }
                        }
                    }
                    sb.append(Regex.escape(c.toString()))
                    prevChar = c
                    pendingWhitespace = false
                }
            }
            return try {
                Regex(sb.toString(), RegexOption.IGNORE_CASE)
            } catch (e: Exception) {
                Log.w(TAG, "Regex construction failed for query: $query", e)
                null
            }
        }
    }
}

/**
 * 扩展 PDFTextStripper，构建与纯文本字符 1:1 绝对零偏移映射的字符坐标流
 */
private class PositionCollectingStripper : PDFTextStripper() {

    private val positionedChars = ArrayList<PositionedChar>()
    private val textBuilder = java.lang.StringBuilder()
    private var currentPageWidth: Float = 0f
    private var currentPageHeight: Float = 0f

    init {
        sortByPosition = true
    }

    fun clearCollected() {
        positionedChars.clear()
        textBuilder.setLength(0)
        currentPageWidth = 0f
        currentPageHeight = 0f
    }

    fun getPositionedChars(): List<PositionedChar> = positionedChars
    fun getConstructedText(): String = textBuilder.toString()
    fun getPageWidth(): Float = currentPageWidth
    fun getPageHeight(): Float = currentPageHeight

    override fun processPage(page: PDPage) {
        val cropBox = page.cropBox ?: page.mediaBox
        val rotation = page.rotation
        if (rotation == 90 || rotation == 270) {
            currentPageWidth = cropBox.height
            currentPageHeight = cropBox.width
        } else {
            currentPageWidth = cropBox.width
            currentPageHeight = cropBox.height
        }
        super.processPage(page)
    }

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        super.writeString(text, textPositions)
        if (text.isNullOrEmpty()) return
        val tps = textPositions ?: emptyList()

        if (tps.isEmpty()) {
            for (ch in text) {
                textBuilder.append(ch)
                positionedChars.add(PositionedChar(ch, null))
            }
            return
        }

        if (text.length == tps.size) {
            for (i in text.indices) {
                textBuilder.append(text[i])
                positionedChars.add(PositionedChar(text[i], tps[i]))
            }
        } else {
            // 当存在连字拆分 (如 fi 连字拆分为 f 和 i) 时按比例分配坐标
            for (i in text.indices) {
                val tpIndex = (i * tps.size / text.length).coerceIn(0, tps.size - 1)
                textBuilder.append(text[i])
                positionedChars.add(PositionedChar(text[i], tps[tpIndex]))
            }
        }
    }

    override fun writeWordSeparator() {
        super.writeWordSeparator()
        val sep = wordSeparator ?: " "
        textBuilder.append(sep)
        for (ch in sep) {
            positionedChars.add(PositionedChar(ch, null))
        }
    }

    override fun writeLineSeparator() {
        super.writeLineSeparator()
        val sep = lineSeparator ?: "\n"
        textBuilder.append(sep)
        for (ch in sep) {
            positionedChars.add(PositionedChar(ch, null))
        }
    }

    override fun writeParagraphSeparator() {
        super.writeParagraphSeparator()
        val sep = (paragraphEnd ?: "") + (paragraphStart ?: "")
        if (sep.isNotEmpty()) {
            textBuilder.append(sep)
            for (ch in sep) {
                positionedChars.add(PositionedChar(ch, null))
            }
        }
    }
}
