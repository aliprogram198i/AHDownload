package com.ahdownload.domain.download

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

class LocalAtomicFileSink : AtomicFileSink {

    override suspend fun openTemporary(destinationPath: String, append: Boolean): OutputStream {
        val destination = Path.of(destinationPath).absolute()
        destination.parent?.createDirectories()

        val temporary = temporaryPath(destination)
        if (!append) {
            Files.deleteIfExists(temporary)
        } else if (!Files.exists(temporary)) {
            throw IllegalStateException("Temporary download file does not exist")
        }

        return if (append) {
            Files.newOutputStream(temporary, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        } else {
            Files.newOutputStream(temporary)
        }
    }

    override suspend fun temporarySize(destinationPath: String): Long {
        val temporary = temporaryPath(Path.of(destinationPath).absolute())
        return if (Files.exists(temporary)) Files.size(temporary) else 0L
    }

    override suspend fun commit(destinationPath: String) {
        val destination = Path.of(destinationPath).absolute()
        val temporary = temporaryPath(destination)
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    override suspend fun discard(destinationPath: String) {
        Files.deleteIfExists(temporaryPath(Path.of(destinationPath).absolute()))
    }

    private fun temporaryPath(destination: Path): Path =
        destination.resolveSibling(destination.fileName.toString() + ".part")
}
