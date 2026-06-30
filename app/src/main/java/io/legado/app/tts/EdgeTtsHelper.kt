package io.legado.app.tts

import io.legado.app.help.http.getProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Edge TTS 辅助类
 * 通过 Cloudflare Worker 代理调用微软 Edge TTS API
 *
 * Worker URL: https://tts.wos.ccwu.cc
 */
object EdgeTtsHelper {

    private const val WORKER_URL = "https://tts.wos.ccwu.cc"

    data class EdgeTtsResult(
        val success: Boolean,
        val audioData: ByteArray? = null,
        val error: String? = null
    )

    private val client by lazy {
        getProxyClient()
    }

    /**
     * 将文本转换为音频
     */
    suspend fun synthesize(
        text: String,
        voice: String = "zh-CN-XiaoxiaoNeural",
        rate: String = "+0%"
    ): EdgeTtsResult = withContext(Dispatchers.IO) {
        try {
            doSynthesize(text, voice)
        } catch (e: Exception) {
            EdgeTtsResult(success = false, error = e.message)
        }
    }

    private fun doSynthesize(
        text: String,
        voice: String
    ): EdgeTtsResult {
        val cleanText = removeIncompatibleCharacters(text)
        if (cleanText.isBlank()) {
            return EdgeTtsResult(success = false, error = "Text is empty")
        }

        val json = JSONObject().apply {
            put("text", cleanText)
            put("voice", voice)
        }

        val requestBody = json.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("$WORKER_URL/tts")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            return EdgeTtsResult(
                success = false,
                error = "HTTP ${response.code}: ${response.message}"
            )
        }

        val body = response.body ?: return EdgeTtsResult(
            success = false,
            error = "Response body is null"
        )

        val audioStream = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                audioStream.write(buffer, 0, bytesRead)
            }
        }

        val audioData = audioStream.toByteArray()
        return if (audioData.isNotEmpty()) {
            EdgeTtsResult(success = true, audioData = audioData)
        } else {
            EdgeTtsResult(success = false, error = "Received empty audio data")
        }
    }

    /**
     * 清理不兼容字符 (竖排表格符等)
     */
    private fun removeIncompatibleCharacters(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val code = c.code
            if ((code in 0..8) || (code in 11..12) || (code in 14..31)) {
                sb.append(' ')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 检查网络连接是否可用
     */
    fun isNetworkAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$WORKER_URL/health")
                .build()
            val response = client.newCall(request).execute()
            response.close()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
