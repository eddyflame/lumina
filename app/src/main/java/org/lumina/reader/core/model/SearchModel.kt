package org.lumina.reader.core.model

/**
 * 搜索命中区域的归一化矩形坐标 (均在 [0, 1] 范围内)
 *
 * 坐标已转换为屏幕坐标系（左上角原点，y 轴向下）。
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isValid: Boolean get() = width > 0f && height > 0f
}

/**
 * PDF 文档内容搜索匹配项
 */
data class SearchMatch(
    /** 对应阅读器当前视图的虚拟页码 (0-indexed) */
    val pageIndex: Int,
    /** 对应 PDF 物理原始页码 (0-indexed) */
    val originalPageIndex: Int = pageIndex,
    /** 该匹配项在所属页面内的局部序号 (0-indexed) */
    val matchIndexInPage: Int = 0,
    /** 全局匹配项序号 (0-indexed) */
    val globalMatchIndex: Int = 0,
    /** 关键词周围的上下文预览摘要片段 */
    val snippet: String,
    /** 搜索的原始关键词 */
    val query: String,
    /** 在页面纯文本中的起始字符位置 */
    val startCharIndex: Int = 0,
    /** 在页面纯文本中的结束字符位置 */
    val endCharIndex: Int = 0,
    /**
     * 命中文本在页面上的归一化矩形区域列表 (支持跨行多矩形)
     *
     * 坐标已转换为屏幕坐标系（左上角原点，y 轴向下），值域 [0, 1]。
     * 当 PDFBox 无法提取精确位置时此列表为空，界面退化为无高亮模式。
     */
    val highlightRects: List<NormalizedRect> = emptyList()
)

/**
 * 文档全文检索 UI 状态模型
 */
data class SearchState(
    /** 搜索控制条是否处于激活开启状态 */
    val isOpen: Boolean = false,
    /** 用户当前输入的搜索关键词 */
    val query: String = "",
    /** 后台文本检索是否正在运行中 */
    val isSearching: Boolean = false,
    /** 检索到的全部匹配列表 */
    val matches: List<SearchMatch> = emptyList(),
    /** 当前高亮选中的全局匹配项索引 (0-indexed，-1 表示无选中) */
    val currentMatchIndex: Int = -1,
    /** 当前已扫描完成的页面总数 */
    val scannedPageCount: Int = 0,
    /** 需要检索的目标文档总页数 */
    val totalPageCount: Int = 0,
    /** 搜索结果全屏/底部卡片列表抽屉是否展开 */
    val isResultsListOpen: Boolean = false
) {
    /** 当前激活的匹配项对象 */
    val currentMatch: SearchMatch?
        get() = matches.getOrNull(currentMatchIndex)

    /** 匹配总数 */
    val totalMatches: Int
        get() = matches.size

    /** 适合界面友好显示的当前匹配项编号 (从 1 开始) */
    val displayMatchIndex: Int
        get() = if (currentMatchIndex in matches.indices) currentMatchIndex + 1 else 0
}
