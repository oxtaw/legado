package io.legado.app.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Edge TTS 辅助类
 * 通过 WebSocket 连接微软 Bing Speech API 实现高质量 TTS
 */
object EdgeTtsHelper {

    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech.synthesize"

    private val EDGE_HEADERS = mapOf(
        "Origin" to "chrome-extension://jdiccldimpdaibmpdmdce",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0"
    )

    data class EdgeTtsResult(
        val success: Boolean,
        val audioData: ByteArray? = null,
        val error: String? = null
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
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

        val requestBuilder = Request.Builder()
            .url(WSS_URL)
        EDGE_HEADERS.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

        val webSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 发送语音配置
                val configMsg = buildSpeechConfig(requestId)
                webSocket.send(configMsg)

                // 发送 SSML 文本
                val ssmlMsg = buildSsmlMessage(requestId, text, voice, rate)
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // 二进制消息 = 音频数据
                val data = bytes.toByteArray()
                if (data.size > 2) {
                    // 跳过前 2 字节的头部标记
                    audioBuffer.write(data, 2, data.size - 2)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 文本消息，检查是否为 turn.end
                if (text.contains("turn.end")) {
                    webSocket.close(1000, "done")
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
                cont.resume(EdgeTtsResult(success = false, error = t.message))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (cont.isCancelled) return
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
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("speech.platform.bing.com", 443), 5000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
