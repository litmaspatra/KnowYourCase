package com.bluespeaker.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class BluetoothAudioServer(private val context: Context) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6f61f3b2-0d74-4a2e-b327-7b690730ad31")
        const val SERVICE_NAME = "BlueSpeaker Audio"
    }

    @SuppressLint("MissingPermission")
    suspend fun run(onState: (String) -> Unit) = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter = manager.adapter ?: error("Bluetooth unavailable")
        val server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        onState("Waiting for computer")

        server.use {
            val socket = it.accept()
            onState("Connected")
            socket.use { client ->
                val min = AudioTrack.getMinBufferSize(
                    48000,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(48000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(min * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.setVolume(1f)
                track.play()
                client.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        track.write(buffer, 0, n, AudioTrack.WRITE_BLOCKING)
                    }
                }
                track.stop()
                track.release()
            }
        }
        onState("Disconnected")
    }
}
