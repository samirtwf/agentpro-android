/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.utils

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile
import org.linphone.core.tools.Log

/**
 * Converts a WAV recording to AAC inside an MP4/M4A container, using Android's built-in MediaCodec
 * encoder (no external library). WhatsApp rejects WAV but plays AAC/M4A inline. Runs synchronously
 * on the calling (background) thread.
 *
 * Handles the formats a PBX produces: 16-bit PCM and **G.711 a-law / μ-law** (8-bit, decoded to PCM
 * on the fly), including WAVE_FORMAT_EXTENSIBLE wrappers. Returns the produced .m4a File, or null on
 * any failure (caller falls back to sharing the original WAV, so it's never worse than before).
 */
object AudioConverter {
    private const val TAG = "[Audio Converter]"
    private const val OUT_MIME = "audio/mp4a-latm"
    private const val BIT_RATE = 128_000
    private const val TIMEOUT_US = 10_000L

    private const val FMT_PCM = 1
    private const val FMT_ALAW = 6
    private const val FMT_ULAW = 7
    private const val FMT_EXTENSIBLE = 0xFFFE

    private val alawTable = ShortArray(256) { decodeALaw(it).toShort() }
    private val ulawTable = ShortArray(256) { decodeULaw(it).toShort() }

    fun wavToM4a(wav: File): File? {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(wav, "r")
            val info = parseWav(raf) ?: run {
                Log.w("$TAG [${wav.name}] is not a supported WAV (PCM-16 / G.711), skipping conversion")
                return null
            }
            Log.i("$TAG [${wav.name}] format=${info.format} ${info.sampleRate}Hz ch=${info.channels}")

            val out = File(wav.parentFile, wav.nameWithoutExtension + ".m4a")
            val format = MediaFormat.createAudioFormat(OUT_MIME, info.sampleRate, info.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType(OUT_MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            raf.seek(info.dataOffset)
            var bytesRemaining = info.dataSize
            val isPcm = info.format == FMT_PCM
            val pcmPerByte = if (isPcm) 1 else 2 // G.711 expands one 8-bit byte into a 16-bit sample
            val raw = ByteArray(8192)
            val pcm = ByteArray(raw.size * 2)
            var totalFrames = 0L
            val bufInfo = MediaCodec.BufferInfo()
            var inputDone = false

            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val maxRaw = minOf(raw.size, inBuf.capacity() / pcmPerByte, bytesRemaining)
                        val read = if (maxRaw > 0) raf.read(raw, 0, maxRaw) else -1
                        if (read <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pcmLen: Int
                            if (isPcm) {
                                inBuf.put(raw, 0, read)
                                pcmLen = read
                            } else {
                                val table = if (info.format == FMT_ALAW) alawTable else ulawTable
                                for (i in 0 until read) {
                                    val s = table[raw[i].toInt() and 0xFF].toInt()
                                    pcm[i * 2] = (s and 0xFF).toByte()
                                    pcm[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                }
                                pcmLen = read * 2
                                inBuf.put(pcm, 0, pcmLen)
                            }
                            val ptsUs = totalFrames * 1_000_000L / info.sampleRate
                            codec.queueInputBuffer(inIndex, 0, pcmLen, ptsUs, 0)
                            totalFrames += pcmLen / (2 * info.channels)
                            bytesRemaining -= read
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        // The codec-config buffer is carried in the output format, not as data.
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufInfo.size = 0
                        }
                        if (bufInfo.size > 0 && muxerStarted) {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(bufInfo.offset)
                            outBuf.limit(bufInfo.offset + bufInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufInfo)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }

            Log.i("$TAG Converted [${wav.name}] -> [${out.name}] (${out.length()} bytes)")
            return out
        } catch (e: Exception) {
            Log.e("$TAG WAV->M4A conversion failed: $e")
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { raf?.close() } catch (_: Exception) {}
        }
    }

    private data class WavInfo(
        val format: Int,
        val sampleRate: Int,
        val channels: Int,
        val dataOffset: Long,
        val dataSize: Int
    )

    /** Minimal RIFF/WAVE parser. Supports PCM-16 and G.711 a-law/μ-law (incl. EXTENSIBLE). */
    private fun parseWav(raf: RandomAccessFile): WavInfo? {
        val riff = ByteArray(12)
        if (raf.read(riff) != 12) return null
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null

        var fmtCode = 0
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1L
        var dataSize = 0
        var haveFmt = false

        val head = ByteArray(8)
        while (raf.read(head) == 8) {
            val id = String(head, 0, 4, Charsets.US_ASCII)
            val size = le32(head, 4)
            if (size < 0) return null
            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size)
                    if (raf.read(fmt) != size) return null
                    fmtCode = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4)
                    bitsPerSample = le16(fmt, 14)
                    // EXTENSIBLE: the real format code is the first 2 bytes of the SubFormat GUID.
                    if (fmtCode == FMT_EXTENSIBLE && size >= 26) fmtCode = le16(fmt, 24)
                    haveFmt = true
                    if (size and 1 == 1) raf.skipBytes(1)
                }
                "data" -> {
                    dataOffset = raf.filePointer
                    dataSize = size
                    break
                }
                else -> raf.skipBytes(size + (size and 1))
            }
        }
        if (!haveFmt || dataOffset < 0 || sampleRate <= 0 || channels <= 0) return null
        val supported = when (fmtCode) {
            FMT_PCM -> bitsPerSample == 16
            FMT_ALAW, FMT_ULAW -> bitsPerSample == 8
            else -> false
        }
        if (!supported) return null
        return WavInfo(fmtCode, sampleRate, channels, dataOffset, dataSize)
    }

    // ---- G.711 decode (ITU-T) -----------------------------------------------------------------

    private fun decodeALaw(alaw0: Int): Int {
        val a = alaw0 xor 0x55
        var t = (a and 0x0F) shl 4
        val seg = (a and 0x70) shr 4
        t = when (seg) {
            0 -> t + 8
            1 -> t + 0x108
            else -> (t + 0x108) shl (seg - 1)
        }
        return if (a and 0x80 != 0) t else -t
    }

    private fun decodeULaw(ulaw0: Int): Int {
        val u = ulaw0.inv() and 0xFF
        var t = ((u and 0x0F) shl 3) + 0x84
        t = t shl ((u and 0x70) shr 4)
        return if (u and 0x80 != 0) 0x84 - t else t - 0x84
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
