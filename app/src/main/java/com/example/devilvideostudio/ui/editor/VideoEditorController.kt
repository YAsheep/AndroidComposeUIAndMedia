package com.example.devilvideostudio.ui.editor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream

/** Dynamic range of the loaded video, read from the selected video track's format. */
enum class VideoDynamicRange(val label: String, val isHdr: Boolean) {
  SDR("SDR", false),
  HDR10("HDR10", true),
  HLG("HLG", true),
  DOLBY_VISION("杜比视界", true),
}

@OptIn(UnstableApi::class)
class VideoEditorController(private val context: Context) {
  val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
    repeatMode = ExoPlayer.REPEAT_MODE_ALL
    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
  }

  private var inputUri: Uri? = null
  private var emojiEnabled = true
  private var transformer: Transformer? = null

  var dynamicRange: VideoDynamicRange? = null
    private set
  var onDynamicRangeChanged: ((VideoDynamicRange) -> Unit)? = null
  var onPlaybackError: ((String) -> Unit)? = null
  /** Display width / height of the video, so the UI can size the SurfaceView to match. */
  var onVideoAspectRatioChanged: ((Float) -> Unit)? = null

  init {
    player.addListener(object : Player.Listener {
      override fun onTracksChanged(tracks: Tracks) {
        val format = tracks.selectedVideoFormat() ?: return
        val range = format.dynamicRange()
        if (range == dynamicRange) return
        dynamicRange = range
        onDynamicRangeChanged?.invoke(range)
      }

      override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width == 0 || videoSize.height == 0) return
        onVideoAspectRatioChanged?.invoke(
          videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height,
        )
      }

      override fun onPlayerError(error: PlaybackException) {
        onPlaybackError?.invoke(error.message ?: "视频播放失败")
      }
    })
  }

  // SurfaceView hands frames straight to the display compositor, which keeps HDR output intact.
  fun attach(surfaceView: SurfaceView) = player.setVideoSurfaceView(surfaceView)

  fun detach(surfaceView: SurfaceView) = player.clearVideoSurfaceView(surfaceView)

  fun open(uri: Uri) {
    inputUri = uri
    dynamicRange = null
    player.setMediaItem(MediaItem.fromUri(uri))
    applyPreviewEffect()
    player.prepare()
    player.play()
  }

  fun setEmojiEnabled(enabled: Boolean) {
    emojiEnabled = enabled
    applyPreviewEffect()
  }

  fun togglePlayback() {
    if (player.isPlaying) player.pause() else player.play()
  }

  fun seekBy(deltaMs: Long) {
    player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))
  }

  fun export(
    onProgress: (Int) -> Unit,
    onComplete: (Uri) -> Unit,
    onError: (String) -> Unit,
  ) {
    val source = inputUri ?: return onError("请先选择一个 MP4 文件")
    if (transformer != null) return
    val tempFile = File(context.cacheDir, "devil_export_${System.currentTimeMillis()}.mp4")
    val effects = Effects(emptyList<AudioProcessor>(), videoEffects())
    val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(source))
      .setEffects(effects)
      .build()
    val listener = object : Transformer.Listener {
      override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
        transformer = null
        runCatching { publishToMovies(tempFile) }
          .onSuccess(onComplete)
          .onFailure { onError(it.message ?: "保存失败") }
        tempFile.delete()
      }

      override fun onError(
        composition: androidx.media3.transformer.Composition,
        exportResult: ExportResult,
        exportException: ExportException,
      ) {
        transformer = null
        tempFile.delete()
        onError(exportException.message ?: "视频导出失败")
      }
    }
    transformer = Transformer.Builder(context)
      // H.264 cannot carry 10-bit HDR, so keep HDR sources as HEVC.
      .setVideoMimeType(if (dynamicRange?.isHdr == true) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .addListener(listener)
      .build()
      .also {
        it.start(editedItem, tempFile.absolutePath)
        pollProgress(it, onProgress)
      }
  }

  private fun pollProgress(activeTransformer: Transformer, onProgress: (Int) -> Unit) {
    val holder = androidx.media3.transformer.ProgressHolder()
    val state = activeTransformer.getProgress(holder)
    if (state == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
    if (state != Transformer.PROGRESS_STATE_NOT_STARTED) {
      android.os.Handler(context.mainLooper).postDelayed({
        if (transformer === activeTransformer) pollProgress(activeTransformer, onProgress)
      }, 300)
    }
  }

  private fun applyPreviewEffect() {
    player.setVideoEffects(videoEffects())
  }

  private fun videoEffects(): List<Effect> =
    if (emojiEnabled) listOf(createEmojiOverlay()) else emptyList()

  private fun createEmojiOverlay(): OverlayEffect {
    // A TextOverlay, not a plain BitmapOverlay: on HDR input Media3 treats bitmap overlays as
    // Ultra HDR images and rejects ones without a gainmap, while text overlays work for SDR and HDR.
    val emoji = SpannableString("😈").apply {
      setSpan(AbsoluteSizeSpan(200), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val settings = StaticOverlaySettings.Builder()
      .setScale(.34f, .34f)
      .setBackgroundFrameAnchor(.72f, -.64f)
      .setOverlayFrameAnchor(0f, 0f)
      .build()
    return OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(emoji, settings)))
  }

  private fun publishToMovies(file: File): Uri {
    val values = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, "DevilStudio_${System.currentTimeMillis()}.mp4")
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DevilStudio")
      put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
    resolver.openOutputStream(uri).use { output ->
      checkNotNull(output)
      FileInputStream(file).use { it.copyTo(output) }
    }
    values.clear()
    values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
  }

  private fun Tracks.selectedVideoFormat(): Format? {
    val group = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return null
    return (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat)
  }

  private fun Format.dynamicRange(): VideoDynamicRange = when {
    sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> VideoDynamicRange.DOLBY_VISION
    colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> VideoDynamicRange.HDR10
    colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> VideoDynamicRange.HLG
    else -> VideoDynamicRange.SDR
  }

  fun release() {
    transformer?.cancel()
    transformer = null
    player.release()
  }
}
