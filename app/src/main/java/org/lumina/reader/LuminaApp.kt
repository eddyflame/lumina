package org.lumina.reader

import android.app.Application
import org.lumina.reader.core.export.PdfDocumentExporter

class LuminaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PdfDocumentExporter.init(this)
    }
}
