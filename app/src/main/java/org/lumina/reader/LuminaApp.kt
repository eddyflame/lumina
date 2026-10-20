package org.lumina.reader

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.lumina.reader.core.export.PdfDocumentExporter

class LuminaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            PDFBoxResourceLoader.init(this)
        } catch (e: Exception) {
            Log.e("LuminaApp", "Failed to initialize PDFBoxResourceLoader", e)
        }
        PdfDocumentExporter.init(this)
    }
}
