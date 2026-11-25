package com.sergey.animevault.data.scanner

import android.database.MatrixCursor
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafCursorTest {
    @Test
    fun completeEmptyDirectoryIsValid() {
        cursor().use { assertEquals(emptyList<ScannedDocument>(), it.readCompleteDocuments {}) }
    }

    @Test
    fun loadingWithPartialRowsIsRejected() {
        cursor().use {
            it.addRow(arrayOf("episode", "Episode.mp4", "video/mp4"))
            it.extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            assertThrows(IOException::class.java) { it.readCompleteDocuments {} }
        }
    }

    @Test
    fun errorAndMalformedRowsAreRejected() {
        cursor().use {
            it.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "Unavailable") }
            assertThrows(IOException::class.java) { it.readCompleteDocuments {} }
        }
        cursor().use {
            it.addRow(arrayOf("episode", "Episode.mp4", null))
            assertThrows(IOException::class.java) { it.readCompleteDocuments {} }
        }
    }

    @Test
    fun cancelledEnumerationNeverReturnsRows() {
        cursor().use {
            it.addRow(arrayOf("episode", "Episode.mp4", "video/mp4"))
            assertThrows(CancellationException::class.java) {
                it.readCompleteDocuments { throw CancellationException() }
            }
        }
    }

    private fun cursor() = MatrixCursor(
        arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
    )
}
