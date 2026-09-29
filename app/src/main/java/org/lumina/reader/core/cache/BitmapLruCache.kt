package org.lumina.reader.core.cache

import android.graphics.Bitmap
import androidx.collection.LruCache

/**
 * 现代内存安全的高性能位图 LRU 缓存池
 *
 * 严格按照实际占用字节数管理，超出阈值自动淘汰，杜绝 OOM。
 */
class BitmapLruCache(
    maxMemoryBytes: Int = (Runtime.getRuntime().maxMemory() / 4).toInt().coerceAtLeast(32 * 1024 * 1024)
) {

    private val cache = object : LruCache<String, Bitmap>(maxMemoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    fun get(key: String): Bitmap? {
        val bitmap = cache.get(key)
        return if (bitmap != null && !bitmap.isRecycled) {
            bitmap
        } else {
            if (bitmap?.isRecycled == true) {
                cache.remove(key)
            }
            null
        }
    }

    fun put(key: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            cache.put(key, bitmap)
        }
    }

    fun remove(key: String) {
        cache.remove(key)
    }

    fun clear() {
        cache.evictAll()
    }
}
