package io.legado.app.tts

import io.legado.app.help.http.getProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Edge TTS 辅助类
 * 通过 WebSocket 连接微软 Bing Speech API 实现高质量 TTS
 *
 * 参考: https://github.com/rany2/edge-tts
 */
object EdgeTtsHelper {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR_VERSION = "143"
    private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
    private const val WIN_EPOCH = 11644473600L
    private const val S_TO_NS = 1e9

    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    private const val MAX_TEXT_BYTES = 4096

    data class EdgeTtsResult(
        val success: Boolean,
        val audioData: ByteArray? = null,
        val error: String? = null
    )

    private val client by lazy {
        getProxyClient()
    }

    /**
     * 生成 Sec-MS-GEC DRM token
     * 与 Python edge-tts drm.py 中的 generate_sec_ms_gec() 完全一致
     */
    private fun generateSecMsGec(): String {
        val nowSeconds = System.currentTimeMillis() / 1000.0
        var ticks = nowSeconds + WIN_EPOCH
        ticks -= ticks % 300
        ticks *= S_TO_NS / 100
        val strToHash = String.format(Locale.US, "%.0f%s", ticks, TRUSTED_CLIENT_TOKEN)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(strToHash.toByteArray(StandardCharsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * 生成随机 MUID (32位大写十六进制)
     */
    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    /**
     * 生成 JS 风格的日期字符串
     * 格式: "Mon Jan 01 2024 00:00:00 GMT+0000 (Coordinated Universal Time)"
     */
    private fun dateToString(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    /**
     * 构建带认证参数的 WebSocket URL
     */
    private fun buildWsUrl(): String {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val secMsGecToken = generateSecMsGec()
        return "$WSS_URL?" +
                "TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                "&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=$secMsGecToken" +
                "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"
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
            doSynthesize(text, voice, rate)
        } catch (e: Exception) {
            EdgeTtsResult(success = false, error = e.message)
        }
    }

    private suspend fun doSynthesize(
        text: String,
        voice: String,
        rate: String
    ): EdgeTtsResult = suspendCancellableCoroutine { cont ->
        val audioBuffer = ByteArrayOutputStream()
        var hasError = false
        var errorMsg: String? = null

        val muid = generateMuid()
        val url = buildWsUrl()

        // 清理不兼容字符 (竖排表格符等)
        val cleanText = removeIncompatibleCharacters(text)
        // XML 转义
        val escapedText = escapeXml(cleanText)

        val requestBuilder = Request.Builder()
            .url(url)
        requestBuilder.addHeader("Origin", ORIGIN)
        requestBuilder.addHeader(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                    " (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36" +
                    " Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
        )
        requestBuilder.addHeader("Pragma", "no-cache")
        requestBuilder.addHeader("Cache-Control", "no-cache")
        requestBuilder.addHeader("Cookie", "muid=$muid;")

        val webSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 发送语音配置
                webSocket.send(buildSpeechConfig())
                // 发送 SSML 文本 (分块发送)
                val chunks = splitTextByByteLength(escapedText, MAX_TEXT_BYTES)
                for (chunk in chunks) {
                    webSocket.send(buildSsmlMessage(voice, rate, chunk))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 二进制消息格式: 前2字节为 header 长度 (big-endian), 然后是 headers, 然后是音频数据
                val data = bytes.toByteArray()
                if (data.size < 2) return

                val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (headerLength > data.size) return

                // 跳过 header 长度 (2 bytes) + headers + \r\n\r\n (2 bytes)
                val audioStart = 2 + headerLength + 2
                if (audioStart < data.size) {
                    audioBuffer.write(data, audioStart, data.size - audioStart)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "done")
                    if (!cont.isActive) return
                    val audioData = audioBuffer.toByteArray()
                    if (audioData.isNotEmpty() && !hasError) {
                        cont.resume(EdgeTtsResult(success = true, audioData = audioData))
                    } else {
                        cont.resume(
                            EdgeTtsResult(
                                success = false,
                                error = errorMsg ?: "No audio data received"
                            )
                        )
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                hasError = true
                errorMsg = t.message ?: t.javaClass.simpleName
                if (cont.isActive) {
                    cont.resume(EdgeTtsResult(success = false, error = errorMsg))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!cont.isActive) return
                val audioData = audioBuffer.toByteArray()
                if (audioData.isNotEmpty()) {
                    cont.resume(EdgeTtsResult(success = true, audioData = audioData))
                } else {
                    cont.resume(
                        EdgeTtsResult(
                            success = false,
                            error = errorMsg ?: "Connection closed without audio"
                        )
                    )
                }
            }
        })

        cont.invokeOnCancellation {
            webSocket.cancel()
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
     * XML 转义
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * 按字节长度分割文本，确保不拆分 UTF-8 多字节字符
     */
    private fun splitTextByByteLength(text: String, maxBytes: Int): List<String> {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        if (textBytes.size <= maxBytes) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < textBytes.size) {
            var end = minOf(start + maxBytes, textBytes.size)
            // 确保不在 UTF-8 多字节字符中间截断
            while (end > start && (textBytes[end - 1].toInt() and 0xC0) == 0x80) {
                end--
            }
            if (end > start) {
                chunks.add(String(textBytes, start, end - start, StandardCharsets.UTF_8))
            }
            start = end
        }
        return chunks
    }

    private fun buildSpeechConfig(): String {
        return "X-Timestamp:${dateToString()}\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                """{"context":{"synthesis":{"audio":{"metadataoptions":""" +
                """"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
                """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
    }

    private fun buildSsmlMessage(
        voice: String,
        rate: String,
        escapedText: String
    ): String {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        return "X-RequestId:$requestId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${dateToString()}Z\r\n" +
                "Path:ssml\r\n\r\n" +
                "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                "<voice name='$voice'>" +
                "<prosody rate='$rate'>" +
                escapedText +
                "</prosody></voice></speak>"
    }

    /**
     * 检查网络连接是否可用
     */
    fun isNetworkAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("https://speech.platform.bing.com")
                .build()
            val response = client.newCall(request).execute()
            response.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
