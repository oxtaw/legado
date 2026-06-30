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
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Edge TTS 辅助类
 * 通过 WebSocket 连接微软 Bing Speech API 实现高质量 TTS
 *
 * 2025年12月 Microsoft 修改了 API，需要:
 * 1. 新的 WSS URL (readaloud/edge/v1)
 * 2. Sec-MS-GEC DRM token
 * 3. MUID cookie
 * 4. 更新的 audio 二进制协议解析
 */
object EdgeTtsHelper {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val SEC_MS_GEC_VERSION = "1-130.0.2849.68"
    private const val CHROMIUM_VERSION = "130.0.2849.68"
    private const val WIN_EPOCH = 11644473600L
    private const val S_TO_NS = 1e9.toLong()

    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

    private val BINARY_DELIM = "Path:audio\r\n".toByteArray()

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
     *
     * 算法:
     * 1. 获取当前 Unix 时间戳
     * 2. 加上 Windows 纪元偏移 (11644473600)
     * 3. 向下取整到最近的 300 秒 (5分钟)
     * 4. 转换为 100 纳秒间隔 (Windows 文件时间格式)
     * 5. 拼接 TRUSTED_CLIENT_TOKEN 后做 SHA-256
     * 6. 返回大写十六进制字符串
     */
    private fun generateSecMsGec(): String {
        val nowSeconds = System.currentTimeMillis() / 1000
        var ticks = nowSeconds + WIN_EPOCH
        ticks -= ticks % 300
        val ticks100ns = ticks * (S_TO_NS / 100)
        val strToHash = "${ticks100ns.toLong()}$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * 生成随机 MUID (32位大写十六进制)
     */
    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    /**
     * 构建带认证参数的 WebSocket URL
     */
    private fun buildWsUrl(): String {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val secMsGecToken = generateSecMsGec()
        return "$WSS_URL?" +
                "TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                "&Sec-MS-GEC=$secMsGecToken" +
                "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION" +
                "&ConnectionId=$connectionId"
    }

    /**
     * 将文本转换为音频
     * @param text 要朗读的文本
     * @param voice 语音名称，如 "zh-CN-XiaoxiaoNeural"
     * @param rate 语速，如 "+0%", "-10%", "+20%"
     * @return EdgeTtsResult 包含音频数据或错误信息
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
        val requestId = UUID.randomUUID().toString()
        val audioBuffer = ByteArrayOutputStream()
        var hasError = false
        var errorMsg: String? = null

        val muid = generateMuid()
        val url = buildWsUrl()

        val requestBuilder = Request.Builder()
            .url(url)
        requestBuilder.addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdmdce")
        requestBuilder.addHeader(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$CHROMIUM_VERSION Safari/537.36 Edg/$CHROMIUM_VERSION"
        )
        requestBuilder.addHeader("Cookie", "muid=$muid")

        val webSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 发送语音配置
                val configMsg = buildSpeechConfig(requestId)
                webSocket.send(configMsg)

                // 发送 SSML 文本
                val ssmlMsg = buildSsmlMessage(requestId, text, voice, rate)
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 新协议: 二进制消息中包含 Path:audio\r\n 分隔符
                val data = bytes.toByteArray()
                val delimIndex = indexOf(data, BINARY_DELIM)
                if (delimIndex >= 0) {
                    // 跳过分隔符本身，提取音频数据
                    val audioStart = delimIndex + BINARY_DELIM.size
                    if (audioStart < data.size) {
                        audioBuffer.write(data, audioStart, data.size - audioStart)
                    }
                } else if (data.size > 2) {
                    // 兼容旧协议: 跳过前 2 字节的头部标记
                    audioBuffer.write(data, 2, data.size - 2)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 文本消息，检查是否为 turn.end
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
                                error = errorMsg ?: "Empty audio response"
                            )
                        )
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                hasError = true
                errorMsg = t.message
                if (cont.isActive) {
                    cont.resume(EdgeTtsResult(success = false, error = t.message))
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
     * 在字节数组中查找子数组的索引
     */
    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun buildSpeechConfig(requestId: String): String {
        return "X-Timestamp:${java.time.OffsetDateTime.now()}\r\n" +
                "Content-Type:application/json; charset=utf-8\r\n" +
                "Path:speech.config\r\n\r\n" +
                """{
  "context": {
    "synthesis": {
      "audio": {
        "metadataOptions": {
          "sentenceBoundaryEnabled": "false",
          "wordBoundaryEnabled": "false"
        },
        "outputFormat": "audio-24khz-48kbitrate-mono-mp3"
      }
    }
  }
}"""
    }

    private fun buildSsmlMessage(
        requestId: String,
        text: String,
        voice: String,
        rate: String
    ): String {
        val escapedText = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        return "X-RequestId:$requestId\r\n" +
                "Content-Type:application/ssml+xml\r\n" +
                "X-Timestamp:${java.time.OffsetDateTime.now()}\r\n" +
                "Path:ssml\r\n\r\n" +
                """<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>
<voice name='$voice'><prosody rate='$rate'>$escapedText</prosody></voice>
</speak>"""
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
