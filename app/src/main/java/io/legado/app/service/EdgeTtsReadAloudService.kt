package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.IntentAction
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.tts.EdgeTtsHelper
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

/**
 * Edge TTS 在线朗读服务
 * 优先使用微软 Edge TTS（音质好），失败时降级到系统 TTS
 */
@SuppressLint("UnsafeOptInUsageError")
class EdgeTtsReadAloudService : BaseReadAloudService(), Player.Listener {

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "edgeTts" + File.separator
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: io.legado.app.help.coroutine.Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 3

    companion object {
        /** Edge TTS 中文语音列表 */
        val CHINESE_VOICES = listOf(
            "zh-CN-XiaoxiaoNeural" to "晓晓（女，温柔自然）",
            "zh-CN-YunxiNeural" to "云希（男，沉稳大气）",
            "zh-CN-YunyangNeural" to "云扬（男，新闻播报）",
            "zh-CN-XiaoyiNeural" to "晓艺（女，活泼可爱）",
            "zh-CN-YunjianNeural" to "云健（男，体育解说）"
        )
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            downloadAndPlayAudios()
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                try {
                    downloadAudiosInternal()
                } catch (e: Exception) {
                    when (e) {
                        is CancellationException -> Unit
                        else -> {
                            AppLog.put("Edge TTS 朗读失败，降级到系统 TTS\n${e.localizedMessage}", e, true)
                            fallbackToSystemTts()
                        }
                    }
                }
            }
        }.onError {
            AppLog.put("Edge TTS 朗读下载出错\n${it.localizedMessage}", it, true)
            fallbackToSystemTts()
        }
    }

    private suspend fun downloadAudiosInternal() {
        contentList.forEachIndexed { index, content ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (index < nowSpeak) return@forEachIndexed
            var text = content
            if (paragraphStartPos > 0 && index == nowSpeak) {
                text = text.substring(paragraphStartPos)
            }
            val fileName = md5SpeakFileName(text)
            val speakText = text.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                runCatching {
                    val audioData = getEdgeTtsAudio(speakText)
                    if (audioData != null) {
                        createSpeakFile(fileName, audioData)
                    } else {
                        createSilentSound(fileName)
                    }
                }.onFailure {
                    when (it) {
                        is CancellationException -> throw it
                        else -> {
                            consecutiveFailures++
                            if (consecutiveFailures >= maxConsecutiveFailures) {
                                throw NoStackTraceException("Edge TTS 连续失败 $consecutiveFailures 次")
                            }
                            AppLog.put("Edge TTS 单次失败，继续尝试\n${it.localizedMessage}")
                            createSilentSound(fileName)
                        }
                    }
                }
            }
            val file = getSpeakFileAsMd5(fileName)
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            launch(Main) {
                exoPlayer.addMediaItem(mediaItem)
            }
        }
    }

    private suspend fun getEdgeTtsAudio(text: String): ByteArray? {
        val voice = AppConfig.edgeTtsVoice
        val rate = getEdgeTtsRate()
        val result = EdgeTtsHelper.synthesize(text, voice, rate)
        if (result.success && result.audioData != null) {
            consecutiveFailures = 0
            return result.audioData
        } else {
            throw NoStackTraceException(result.error ?: "Edge TTS 返回空数据")
        }
    }

    private fun getEdgeTtsRate(): String {
        val rate = (AppConfig.speechRatePlay - 5) * 10
        return if (rate >= 0) "+${rate}%" else "${rate}%"
    }

    private fun fallbackToSystemTts() {
        AppLog.put("Edge TTS 不可用，降级到系统 TTS")
        toastOnUi("Edge TTS 不可用，已切换到系统朗读")
        playStop()
        val intent = Intent(this, TTSReadAloudService::class.java)
        intent.action = IntentAction.play
        intent.putExtra("play", true)
        intent.putExtra("pageIndex", pageIndex)
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            AppLog.put("降级到系统 TTS 失败\n${e.localizedMessage}", e, true)
        }
        stopSelf()
    }

    private fun md5SpeakFileName(
        content: String,
        textChapter: io.legado.app.ui.book.read.page.entities.TextChapter? = this.textChapter
    ): String {
        return MD5Utils.md5Encode16(textChapter?.title ?: "") + "_" +
                MD5Utils.md5Encode16("edge-tts-|-$speechRate-|-$content")
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, audioData: ByteArray) {
        FileOutputStream(FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")).use { out ->
            out.write(audioData)
        }
    }

    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        downloadAndPlayAudios()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_READY -> {
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }
            Player.STATE_ENDED -> {
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("Edge TTS 朗读错误\n${contentList[nowSpeak]}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("Edge TTS 连续5次错误，切换到系统朗读")
            fallbackToSystemTts()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<EdgeTtsReadAloudService>(actionStr)
    }

}
