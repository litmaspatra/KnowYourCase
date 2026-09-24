package com.bluespeaker.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.coroutineContext

class BluetoothAudioServer(private val context: Context) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6f61f3b2-0d74-4a2e-b327-7b690730ad31")
        const val SERVICE_NAME = "BlueSpeaker Audio"
        private const val HEADER_SIZE = 16
        private val MAGIC = byteArrayOf('B'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 'K'.code.toByte())
    }

    @SuppressLint("MissingPermission")
    suspend fun run(onState: (String) -> Unit) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter = manager.adapter ?: error("Bluetooth unavailable")
        if (!adapter.isEnabled) error("Turn Bluetooth on first")

        while (coroutineContext.isActive) {
            onState("Waiting for computer")
            val server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            try {
                val socket = server.accept()
                server.close()
                onState("Connected")
                socket.use { client ->
                    val input = BufferedInputStream(client.inputStream, 64 * 1024)
                    var track: AudioTrack? = null
                    var currentKey = ""

                    try {
                        val header = ByteArray(HEADER_SIZE)
                        while (coroutineContext.isActive) {
                            readFully(input, header)
                            if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) error("Invalid BlueSpeaker stream")

                            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                            bb.position(4)
                            val sampleRate = bb.int
                            val channels = bb.short.toInt()
                            val formatCode = bb.short.toInt()
                            val payloadLength = bb.int
                            if (sampleRate !in 8000..192000 || channels !in 1..2 || payloadLength !in 1..262144) {
                                error("Unsupported audio stream")
                            }

                            val key = "$sampleRate/$channels/$formatCode"
                            if (key != currentKey) {
                                track?.runCatching { stop() }
                                track?.release()
                                track = createTrack(sampleRate, channels, formatCode)
                                track.play()
                                currentKey = key
                            }

                            val payload = ByteArray(payloadLength)
                            readFully(input, payload)
                            val wrote = track.write(payload, 0, payload.size, AudioTrack.WRITE_BLOCKING)
                            if (wrote < 0) error("AudioTrack write failed: $wrote")
                        }
                    } finally {
                        track?.runCatching { stop() }
                        track?.release()
                    }
                }
            } catch (e: Exception) {
                if (!coroutineContext.isActive) throw e
                onState("Disconnected — waiting again")
            } finally {
                runCatching { server.close() }
            }
        }
    }

    private fun createTrack(sampleRate: Int, channels: Int, formatCode: Int): AudioTrack {
        val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val encoding = when (formatCode) {
            1 -> AudioFormat.ENCODING_PCM_FLOAT
            2 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            3 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
        val min = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding).coerceAtLeast(8192)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(min * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.setVolume(1f) }
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) throw java.io.EOFException("Computer disconnected")
            offset += n
        }
    }
}
