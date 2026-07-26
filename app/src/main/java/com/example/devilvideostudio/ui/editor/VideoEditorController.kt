package com.example.devilvideostudio.ui.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream

@OptIn(UnstableApi::class)
class VideoEditorController(private val context: Context) {
  val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
    repeatMode = ExoPlayer.REPEAT_MODE_ALL
    // Keep the decoded picture centered and preserve its aspect ratio across rotation.
    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
  }

  private var inputUri: Uri? = null
  private var emojiEnabled = true
  private var previewRotationDegrees = 0f
  private var transformer: Transformer? = null

  fun attach(surfaceView: SurfaceView) = player.setVideoSurfaceView(surfaceView)

  fun detach(surfaceView: SurfaceView) = player.clearVideoSurfaceView(surfaceView)

  fun open(uri: Uri) {
    inputUri = uri
    player.setMediaItem(MediaItem.fromUri(uri))
    applyPreviewEffect()
    player.prepare()
    player.play()
  }

  fun setEmojiEnabled(enabled: Boolean) {
    emojiEnabled = enabled
    applyPreviewEffect()
  }

  fun setPreviewRotation(degrees: Float) {
    if (previewRotationDegrees == degrees) return
    previewRotationDegrees = degrees
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
    // Display-orientation compensation is preview-only and must not be encoded.
    val effects = Effects(emptyList<AudioProcessor>(), exportVideoEffects())
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
      .setVideoMimeType(MimeTypes.VIDEO_H264)
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
    player.setVideoEffects(previewVideoEffects())
  }

  private fun previewVideoEffects(): List<Effect> = buildList {
    if (emojiEnabled) add(createEmojiOverlay())
    if (previewRotationDegrees != 0f) {
      add(
        ScaleAndRotateTransformation.Builder()
          .setRotationDegrees(previewRotationDegrees)
          .build(),
      )
    }
  }

  private fun exportVideoEffects(): List<Effect> =
    if (emojiEnabled) listOf(createEmojiOverlay()) else emptyList()

  private fun createEmojiOverlay(): OverlayEffect {
    val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawText(
      "😈",
      20f,
      210f,
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 200f
        textAlign = Paint.Align.LEFT
      },
    )
    val settings = StaticOverlaySettings.Builder()
      .setScale(.34f, .34f)
      .setBackgroundFrameAnchor(.72f, -.64f)
      .setOverlayFrameAnchor(0f, 0f)
      .build()
    return OverlayEffect(listOf(BitmapOverlay.createStaticBitmapOverlay(bitmap, settings)))
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

  fun release() {
    transformer?.cancel()
    transformer = null
    player.release()
  }
}
