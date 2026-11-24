package com.fishkingsin.manhuagui.downloads

import com.fishkingsin.manhuagui.core.LibraryService
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.manhuagui_ffi.AndroidExportFormat
import uniffi.manhuagui_ffi.ExportedChapterFile

class ArchiveExporter(
    private val service: LibraryService,
    private val downloadRoot: String,
    private val exportRoot: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun exportChapterToStream(
        comicId: Long,
        chapterId: Long,
        format: AndroidExportFormat,
        sink: suspend (File) -> Unit,
    ) = withContext(io) {
        withTemporaryExportDirectory { directory ->
            val files = service.exportChapters(
                comicId,
                listOf(chapterId),
                downloadRoot,
                directory.absolutePath,
                format,
            ).getOrThrow()
            files.forEach { exported -> sink(exported.asFile()) }
        }
    }

    suspend fun exportComicToDirectory(
        comicId: Long,
        chapterIds: List<Long>,
        format: AndroidExportFormat,
        write: suspend (fileName: String, sourceFile: File) -> Unit,
    ) = withContext(io) {
        withTemporaryExportDirectory { directory ->
            val files = service.exportChapters(
                comicId,
                chapterIds,
                downloadRoot,
                directory.absolutePath,
                format,
            ).getOrThrow()
            files.forEach { exported -> write(exported.fileName, exported.asFile()) }
        }
    }

    private suspend fun <T> withTemporaryExportDirectory(block: suspend (File) -> T): T {
        val root = File(exportRoot)
        check(root.isDirectory || root.mkdirs())
        val directory = Files.createTempDirectory(root.toPath(), "export-").toFile()
        return try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun ExportedChapterFile.asFile() = File(filePath)
}
