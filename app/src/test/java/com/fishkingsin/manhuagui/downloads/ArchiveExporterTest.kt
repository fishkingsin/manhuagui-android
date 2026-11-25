package com.fishkingsin.manhuagui.downloads

import com.fishkingsin.manhuagui.core.LibraryService
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.manhuagui_ffi.AndroidExportFormat
import uniffi.manhuagui_ffi.ExportedChapterFile

class ArchiveExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun exportChapterToStreamCopiesArchiveBytesAndRemovesTemporaryFiles() = runTest {
        val cacheRoot = temporaryFolder.newFolder("cache")
        val library = DownloadsTestLibrary()
        val archive = byteArrayOf(1, 2, 3, 4)
        library.onExport = { comicId, chapterIds, downloadRoot, exportRoot, format ->
            assertEquals(14L, comicId)
            assertEquals(listOf(27L), chapterIds)
            assertEquals("/downloads", downloadRoot)
            assertEquals(AndroidExportFormat.CBZ, format)
            val file = File(exportRoot, "chapter-27.cbz")
            file.writeBytes(archive)
            listOf(ExportedChapterFile(27, file.name, file.absolutePath))
        }
        val io = StandardTestDispatcher(testScheduler)
        val exporter = ArchiveExporter(
            service = LibraryService(library, io),
            downloadRoot = "/downloads",
            exportRoot = cacheRoot.absolutePath,
            io = io,
        )
        val sink = ByteArrayOutputStream()

        exporter.exportChapterToStream(14, 27, AndroidExportFormat.CBZ) { file ->
            file.inputStream().use { it.copyTo(sink) }
        }

        assertArrayEquals(archive, sink.toByteArray())
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun exportChapterToStreamRemovesTemporaryFilesWhenSinkThrows() = runTest {
        val cacheRoot = temporaryFolder.newFolder("cache")
        val library = DownloadsTestLibrary()
        library.onExport = { _, _, _, exportRoot, _ ->
            val file = File(exportRoot, "chapter-28.pdf")
            file.writeBytes(byteArrayOf(5, 6, 7))
            listOf(ExportedChapterFile(28, file.name, file.absolutePath))
        }
        val io = StandardTestDispatcher(testScheduler)
        val exporter = ArchiveExporter(
            service = LibraryService(library, io),
            downloadRoot = "/downloads",
            exportRoot = cacheRoot.absolutePath,
            io = io,
        )

        val error = runCatching {
            exporter.exportChapterToStream(14, 28, AndroidExportFormat.PDF) {
                error("Sink failed")
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun exportComicToDirectoryWritesEveryChapterFileAndCleansTemporaryFiles() = runTest {
        val cacheRoot = temporaryFolder.newFolder("cache")
        val library = DownloadsTestLibrary()
        library.onExport = { comicId, chapterIds, downloadRoot, exportRoot, format ->
            assertEquals(15L, comicId)
            assertEquals(listOf(30L, 31L), chapterIds)
            assertEquals("/downloads", downloadRoot)
            assertEquals(AndroidExportFormat.PDF, format)
            chapterIds.map { chapterId ->
                val name = "chapter-$chapterId.pdf"
                val file = File(exportRoot, name)
                file.writeBytes(byteArrayOf(chapterId.toByte(), (chapterId + 1).toByte()))
                ExportedChapterFile(chapterId, name, file.absolutePath)
            }
        }
        val io = StandardTestDispatcher(testScheduler)
        val exporter = ArchiveExporter(
            service = LibraryService(library, io),
            downloadRoot = "/downloads",
            exportRoot = cacheRoot.absolutePath,
            io = io,
        )
        val written = linkedMapOf<String, ByteArray>()

        exporter.exportComicToDirectory(15, listOf(30, 31), AndroidExportFormat.PDF) { name, source ->
            written[name] = Files.readAllBytes(source.toPath())
        }

        assertEquals(setOf("chapter-30.pdf", "chapter-31.pdf"), written.keys)
        assertArrayEquals(byteArrayOf(30, 31), written.getValue("chapter-30.pdf"))
        assertArrayEquals(byteArrayOf(31, 32), written.getValue("chapter-31.pdf"))
        assertTrue(cacheRoot.listFiles().orEmpty().isEmpty())
    }
}
