package com.sergey.animevault.ui.localai

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFileIntegrityTest {
    @get:Rule val folder = TemporaryFolder()
    private val spec = ModelFileSpec("config.json", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    @Test fun acceptsExactBytesAndRejectsSameSizeCorruption() = runBlocking {
        val file = folder.newFile().apply { writeText("abc") }
        assertTrue(verifyModelFile(file, spec))
        file.writeText("abd")
        assertFalse(verifyModelFile(file, spec))
    }
    @Test fun rejectsMissingTruncatedAndOversizedFiles() = runBlocking {
        assertFalse(verifyModelFile(File(folder.root, "missing"), spec))
        val file = folder.newFile().apply { writeText("ab") }
        assertFalse(verifyModelFile(file, spec))
        file.writeText("abcd")
        assertFalse(verifyModelFile(file, spec))
    }
}
