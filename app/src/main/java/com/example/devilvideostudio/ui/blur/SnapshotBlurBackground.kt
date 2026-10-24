package com.example.devilvideostudio.ui.blur

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap

/** RenderEffect blur (API 31+) runs on the GPU at draw time; older devices blur on the CPU. */
private val GpuBlurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Low-res snapshots of a source View, shared by every control that uses it as a background, so
 * the source is captured once per tick however many controls there are. Each new snapshot fades
 * in over the previous one so the background moves smoothly between captures.
 * Create it with [rememberSnapshotBlurSource].
 */
@Stable
class SnapshotBlurSource internal constructor() {
  internal var current by mutableStateOf<Snapshot?>(null)
  internal var previous by mutableStateOf<Snapshot?>(null)
  internal val fade = Animatable(1f)
  internal var blurRadiusPx by mutableFloatStateOf(0f)

  /** Controls currently drawing this source; capturing pauses while there are none. */
  internal var users by mutableIntStateOf(0)

  internal fun clear() {
    current = null
    previous = null
  }
}

/**
 * The whole source at low resolution (already blurred unless [GpuBlurSupported]), plus where the
 * source sat in the window when it was captured.
 */
internal class Snapshot(val image: ImageBitmap, val originInWindow: IntOffset, val size: IntSize)

/**
 * Captures [source] every [intervalMillis] at 1/[downscale] size. While [active] is false (for
 * example, video paused) it takes one snapshot and then stops until [active] turns true again.
 *
 * [source] may be a SurfaceView (copied with PixelCopy, so HDR video works too), a TextureView, or
 * any ordinary View; pass null when there is nothing to blur. The default [downscale] is 8 when
 * the GPU blurs, and 16 on older devices so the CPU blur has a quarter of the pixels to process.
 */
@Composable
fun rememberSnapshotBlurSource(
  source: View?,
  active: Boolean = true,
  blurRadius: Dp = 24.dp,
  intervalMillis: Long = 66,
  downscale: Int = if (GpuBlurSupported) 8 else 16,
): SnapshotBlurSource {
  val blurSource = remember { SnapshotBlurSource() }
  val buffers = remember { SnapshotBuffers() }
  val radiusPx = with(LocalDensity.current) { blurRadius.toPx() }
  SideEffect { blurSource.blurRadiusPx = radiusPx }

  LaunchedEffect(source, active, intervalMillis, downscale, radiusPx) {
    if (source == null) {
      blurSource.clear()
      return@LaunchedEffect
    }
    while (isActive) {
      val snapshot = if (blurSource.users > 0) {
        source.takeSnapshot(buffers, downscale, cpuBlurRadius = radiusPx / downscale)
      } else {
        null
      }
      if (snapshot != null) {
        blurSource.previous = blurSource.current
        blurSource.current = snapshot
        // Fade across one interval, so each fade ends as the next snapshot arrives.
        launch {
          blurSource.fade.snapTo(0f)
          blurSource.fade.animateTo(1f, tween(intervalMillis.toInt(), easing = LinearEasing))
        }
        if (!active) break
      }
      delay(intervalMillis)
    }
  }
  return blurSource
}

/**
 * Frosted-glass background: draws the part of [blurSource] that sits behind this control, clipped
 * to [shape], under [tint]. Shows just [tint] until the first snapshot arrives.
 */
@Composable
fun Modifier.snapshotBlurBackground(
  blurSource: SnapshotBlurSource,
  tint: Color,
  shape: Shape = RectangleShape,
): Modifier {
  DisposableEffect(blurSource) {
    blurSource.users++
    onDispose { blurSource.users-- }
  }
  val blurLayer = rememberGraphicsLayer()
  var originInWindow by remember { mutableStateOf(Offset.Zero) }
  return this
    .onGloballyPositioned { originInWindow = it.positionInWindow() }
    .clip(shape)
    .drawBehind {
      val current = blurSource.current
      if (current != null) {
        val previous = blurSource.previous
        val progress = if (previous == null) 1f else blurSource.fade.value
        val drawSnapshots: DrawScope.() -> Unit = {
          if (previous != null && progress < 1f) drawSnapshot(previous, originInWindow, 1f)
          drawSnapshot(current, originInWindow, progress)
        }
        if (GpuBlurSupported) {
          val radius = blurSource.blurRadiusPx
          blurLayer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
          blurLayer.record(block = drawSnapshots)
          drawLayer(blurLayer)
        } else {
          drawSnapshots()
        }
      }
      drawOutline(shape.createOutline(size, layoutDirection, this), tint)
    }
}

/** Draws the whole snapshot shifted under this control; the clip keeps only our slice. */
private fun DrawScope.drawSnapshot(snapshot: Snapshot, originInWindow: Offset, alpha: Float) {
  drawImage(
    snapshot.image,
    dstOffset = IntOffset(
      snapshot.originInWindow.x - originInWindow.x.roundToInt(),
      snapshot.originInWindow.y - originInWindow.y.roundToInt(),
    ),
    dstSize = snapshot.size,
    alpha = alpha,
    filterQuality = FilterQuality.Low,
  )
}

/**
 * Bitmaps and pixel arrays reused across ticks, so capturing allocates nothing per frame. Three
 * display bitmaps rotate: one is current, one is fading out, and the third takes the new frame.
 */
private class SnapshotBuffers {
  var width = 0
    private set
  var height = 0
    private set
  lateinit var scratch: Bitmap
    private set
  lateinit var pixels: IntArray
    private set
  lateinit var tmp: IntArray
    private set
  private lateinit var display: Array<Bitmap>
  private var next = 0

  fun ensure(width: Int, height: Int) {
    if (width == this.width && height == this.height) return
    this.width = width
    this.height = height
    scratch = createBitmap(width, height)
    pixels = IntArray(width * height)
    tmp = IntArray(width * height)
    display = Array(3) { createBitmap(width, height) }
  }

  fun nextDisplay(): Bitmap = display[next].also { next = (next + 1) % display.size }
}

private suspend fun View.takeSnapshot(
  buffers: SnapshotBuffers,
  downscale: Int,
  cpuBlurRadius: Float,
): Snapshot? {
  if (width <= 0 || height <= 0) return null
  val w = (width / downscale).coerceAtLeast(1)
  val h = (height / downscale).coerceAtLeast(1)
  buffers.ensure(w, h)
  val origin = locationInWindow()
  val sourceSize = IntSize(width, height)
  val captured = captureScaled(buffers.scratch) ?: return null
  val display = withContext(Dispatchers.Default) {
    captured.getPixels(buffers.pixels, 0, w, 0, 0, w, h)
    if (!GpuBlurSupported) gaussianBlur(buffers.pixels, buffers.tmp, w, h, cpuBlurRadius)
    // setPixels also tells the renderer the bitmap changed, so its cached texture is refreshed.
    buffers.nextDisplay().apply { setPixels(buffers.pixels, 0, w, 0, 0, w, h) }
  }
  return Snapshot(display.asImageBitmap(), origin, sourceSize)
}

private fun View.locationInWindow(): IntOffset {
  val location = IntArray(2)
  getLocationInWindow(location)
  return IntOffset(location[0], location[1])
}

/** Copies this whole view, scaled to [target]'s size. Returns the copy, or null on failure. */
private suspend fun View.captureScaled(target: Bitmap): Bitmap? = when (this) {
  // A SurfaceView's pixels live outside the view hierarchy; only PixelCopy can read them. Before
  // API 34 PixelCopy waits for the copy on the calling thread, so keep it off the main thread.
  is SurfaceView -> if (!holder.surface.isValid) null else withContext(Dispatchers.Default) {
    suspendCancellableCoroutine { cont ->
      PixelCopy.request(
        this@captureScaled,
        target,
        { cont.resume(if (it == PixelCopy.SUCCESS) target else null) },
        Handler(Looper.getMainLooper()),
      )
    }
  }
  // Let the GPU scale the frame straight into the small bitmap instead of copying it full size.
  is TextureView -> if (isAvailable) getBitmap(target) else null
  else -> drawScaled(target.width, target.height)
}

/**
 * Draws an ordinary view at [width] x [height]. Recording into a Picture and rendering that with
 * the hardware renderer handles hardware bitmaps (common from image loaders), which a plain
 * software Canvas rejects with an exception.
 */
private fun View.drawScaled(width: Int, height: Int): Bitmap? {
  val scaleX = width.toFloat() / this.width
  val scaleY = height.toFloat() / this.height
  return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    val picture = Picture()
    picture.beginRecording(width, height).apply {
      scale(scaleX, scaleY)
      this@drawScaled.draw(this)
    }
    picture.endRecording()
    Bitmap.createBitmap(picture, width, height, Bitmap.Config.ARGB_8888)
  } else {
    // API 26-27 cannot render a Picture on the GPU; skip frames that contain hardware bitmaps.
    runCatching {
        createBitmap(width, height).also {
        val canvas = Canvas(it)
        canvas.scale(scaleX, scaleY)
        draw(canvas)
      }
    }.getOrNull()
  }
}

/**
 * Separable Gaussian blur of [pixels] in place (edge pixels clamped), using [tmp] as scratch.
 * [radius] is in these pixels.
 */
private fun gaussianBlur(pixels: IntArray, tmp: IntArray, w: Int, h: Int, radius: Float) {
  val r = radius.roundToInt().coerceIn(1, 32)
  val sigma = r / 2f
  val kernel = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
  val sum = kernel.sum()
  for (i in kernel.indices) kernel[i] /= sum
  blurPass(pixels, tmp, w, h, kernel, r, horizontal = true)
  blurPass(tmp, pixels, w, h, kernel, r, horizontal = false)
}

private fun blurPass(
  input: IntArray,
  output: IntArray,
  w: Int,
  h: Int,
  kernel: FloatArray,
  r: Int,
  horizontal: Boolean,
) {
  for (y in 0 until h) {
    for (x in 0 until w) {
      var a = 0f
      var red = 0f
      var g = 0f
      var b = 0f
      for (k in -r..r) {
        val sx = if (horizontal) (x + k).coerceIn(0, w - 1) else x
        val sy = if (horizontal) y else (y + k).coerceIn(0, h - 1)
        val pixel = input[sy * w + sx]
        val weight = kernel[k + r]
        a += (pixel ushr 24) * weight
        red += (pixel shr 16 and 0xFF) * weight
        g += (pixel shr 8 and 0xFF) * weight
        b += (pixel and 0xFF) * weight
      }
      output[y * w + x] = (a.roundToInt() shl 24) or (red.roundToInt() shl 16) or
        (g.roundToInt() shl 8) or b.roundToInt()
    }
  }
}
