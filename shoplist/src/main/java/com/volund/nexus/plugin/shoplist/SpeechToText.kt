package com.volund.nexus.plugin.shoplist

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Phone-side speech-to-text, transposed from the maintainer's Rokid Relay
 * reference (the OpenAI buffered path, mirrored by beyondlevi/rokid-inbox-nexus):
 * PCM16-mono -> WAV -> OpenAI `/v1/audio/transcriptions` (multipart) -> text.
 *
 * On Nexus the audio comes from the glasses microphone over the hub as raw
 * 16 kHz mono PCM ([ShopListPluginService] buffers it); this only turns a
 * captured buffer into text. Runs on a background thread (blocking OkHttp call).
 */
class SpeechToText(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** ISO/BCP-47 code to force; blank = auto-detect. */
    private val language: String = "",
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** @param pcm16leMono signed 16-bit little-endian mono PCM at [sampleRate]. Blocking. */
    fun transcribe(pcm16leMono: ByteArray, sampleRate: Int): String {
        require(apiKey.isNotBlank()) { "OpenAI key not configured" }
        require(pcm16leMono.size >= MIN_AUDIO_BYTES) { "Audio too short" }
        val wav = Pcm16Wav.encode(pcm16leMono, sampleRate)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model.ifBlank { DEFAULT_MODEL })
            .addFormDataPart("response_format", "json")
            .apply { if (language.isNotBlank()) addFormDataPart("language", language) }
            .addFormDataPart(
                "prompt",
                "Transcribe a short shopping-list item dictated on Rokid glasses. Keep the spoken language.",
            )
            .addFormDataPart("file", "item.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(TRANSCRIPTIONS_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                throw RuntimeException("OpenAI STT ${res.code}: ${text.take(200).ifBlank { res.message }}")
            }
            val json = runCatching { JSONObject(text) }.getOrElse { throw RuntimeException("Bad STT response") }
            json.optJSONObject("error")?.let {
                throw RuntimeException(it.optString("message").ifBlank { "OpenAI STT failed" })
            }
            return json.optString("text").trim()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini-transcribe"

        /** Buffered OpenAI transcription models (label -> id), as offered by Relay. */
        val MODELS = linkedMapOf(
            "GPT-4o mini Transcribe" to "gpt-4o-mini-transcribe",
            "GPT-4o Transcribe" to "gpt-4o-transcribe",
            "Whisper" to "whisper-1",
        )

        private const val TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val MIN_AUDIO_BYTES = 3_200 // ~0.1 s at 16 kHz mono 16-bit

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** Minimal PCM16-mono WAV container (transposed from Relay's `Pcm16Wav`). */
object Pcm16Wav {
    private const val CHANNEL_COUNT = 1
    private const val BYTES_PER_SAMPLE = 2
    private const val BITS_PER_SAMPLE = 16

    fun encode(pcm16Mono: ByteArray, sampleRate: Int): ByteArray {
        val dataSize = pcm16Mono.size
        val byteRate = sampleRate * CHANNEL_COUNT * BYTES_PER_SAMPLE
        return ByteArrayOutputStream(44 + dataSize).apply {
            ascii("RIFF"); intLe(36 + dataSize); ascii("WAVE")
            ascii("fmt "); intLe(16); shortLe(1); shortLe(CHANNEL_COUNT)
            intLe(sampleRate); intLe(byteRate)
            shortLe(CHANNEL_COUNT * BYTES_PER_SAMPLE); shortLe(BITS_PER_SAMPLE)
            ascii("data"); intLe(dataSize); write(pcm16Mono)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.ascii(v: String) = write(v.toByteArray(Charsets.US_ASCII))
    private fun ByteArrayOutputStream.intLe(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff); write((v shr 16) and 0xff); write((v shr 24) and 0xff)
    }
    private fun ByteArrayOutputStream.shortLe(v: Int) { write(v and 0xff); write((v shr 8) and 0xff) }
}
