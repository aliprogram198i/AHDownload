package com.ahdownload.domain.download

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

class LocalAtomicFileSink : AtomicFileSink {

    override suspend fun openTemporary(destinationPath: String): OutputStream {
        val destination = Path.of(destinationPath).absolute()
        destination.parent?.createDirectories()

        val temporary = temporaryPath(destination)
        Files.deleteIfExists(temporary)
        return Files.newOutputStream(temporary)
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
