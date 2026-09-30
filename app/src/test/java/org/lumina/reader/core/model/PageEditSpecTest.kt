package org.lumina.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PageEditSpecTest {

    @Test
    fun testNormalizedRotationCalculations() {
        assertEquals(0, PageEditSpec(0, 0).normalizedRotation)
        assertEquals(90, PageEditSpec(0, 90).normalizedRotation)
        assertEquals(180, PageEditSpec(0, 180).normalizedRotation)
        assertEquals(270, PageEditSpec(0, 270).normalizedRotation)
        assertEquals(0, PageEditSpec(0, 360).normalizedRotation)
        assertEquals(90, PageEditSpec(0, 450).normalizedRotation)
        assertEquals(270, PageEditSpec(0, -90).normalizedRotation)
        assertEquals(180, PageEditSpec(0, -180).normalizedRotation)
        assertEquals(270, PageEditSpec(0, -450).normalizedRotation)
    }

    @Test
    fun testPageReorderLogic() {
        val originalSpecs = (0 until 5).map { PageEditSpec(it, 0) }
        val mutableList = originalSpecs.toMutableList()

        // 将第 0 页移动到第 2 页
        val moved = mutableList.removeAt(0)
        mutableList.add(2, moved)

        assertEquals(5, mutableList.size)
        assertEquals(1, mutableList[0].originalPageIndex)
        assertEquals(2, mutableList[1].originalPageIndex)
        assertEquals(0, mutableList[2].originalPageIndex)
        assertEquals(3, mutableList[3].originalPageIndex)
        assertEquals(4, mutableList[4].originalPageIndex)
    }

    @Test
    fun testPageDeletionLogic() {
        val originalSpecs = (0 until 3).map { PageEditSpec(it, 0) }
        val mutableList = originalSpecs.toMutableList()

        // 删除第 1 页
        mutableList.removeAt(1)

        assertEquals(2, mutableList.size)
        assertEquals(0, mutableList[0].originalPageIndex)
        assertEquals(2, mutableList[1].originalPageIndex)
    }
}
