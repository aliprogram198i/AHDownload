package com.ahdownload.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LocalMediaPlayerSmokeTest {

    @Test(timeout = 20_000)
    fun exoPlayerCanPrepareALocalAudioFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localAudio = File(context.cacheDir, "ahdownload-player-smoke.wav")
        writeSilentPcmWav(localAudio)

        val playerRef = AtomicReference<ExoPlayer>()
        val readyOrError = CountDownLatch(1)
        val playerError = AtomicReference<PlaybackException?>()
        val finalState = AtomicInteger(Player.STATE_IDLE)

        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val player = ExoPlayer.Builder(context).build()
                playerRef.set(player)
                player.addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            finalState.set(playbackState)
                            if (playbackState == Player.STATE_READY) {
                                readyOrError.countDown()
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            playerError.set(error)
                            readyOrError.countDown()
                        }
                    },
                )
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(localAudio)))
                player.prepare()
            }

            assertTrue(
                "ExoPlayer did not finish preparing a valid local WAV file",
                readyOrError.await(10, TimeUnit.SECONDS),
            )
            assertNull(
                "ExoPlayer rejected the local WAV fixture: " + playerError.get()?.errorCodeName,
                playerError.get(),
            )
            assertEquals(Player.STATE_READY, finalState.get())
            assertTrue(localAudio.isFile && localAudio.length() > 44L)
        } finally {
            playerRef.get()?.let { player ->
                InstrumentationRegistry.getInstrumentation().runOnMainSync { player.release() }
            }
            localAudio.delete()
        }
    }

    private fun writeSilentPcmWav(file: File) {
        val sampleRate = 8_000
        val channels = 1
        val bitsPerSample = 16
        val dataSize = sampleRate * channels * (bitsPerSample / 8) / 2

        FileOutputStream(file).buffered().use { output ->
            output.writeAscii("RIFF")
            output.writeIntLittleEndian(36 + dataSize)
            output.writeAscii("WAVE")
            output.writeAscii("fmt ")
            output.writeIntLittleEndian(16)
            output.writeShortLittleEndian(1) // PCM
            output.writeShortLittleEndian(channels)
            output.writeIntLittleEndian(sampleRate)
            output.writeIntLittleEndian(sampleRate * channels * bitsPerSample / 8)
            output.writeShortLittleEndian(channels * bitsPerSample / 8)
            output.writeShortLittleEndian(bitsPerSample)
            output.writeAscii("data")
            output.writeIntLittleEndian(dataSize)
            output.write(ByteArray(dataSize))
        }
    }

    private fun java.io.OutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun java.io.OutputStream.writeShortLittleEndian(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun java.io.OutputStream.writeIntLittleEndian(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
