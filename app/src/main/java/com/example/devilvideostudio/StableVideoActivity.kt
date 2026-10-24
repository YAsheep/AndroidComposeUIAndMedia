package com.example.devilvideostudio

import android.os.Bundle
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.devilvideostudio.theme.DevilVideoStudioTheme
import com.example.devilvideostudio.ui.editor.VideoEditorScreen
import kotlin.math.abs

/**
 * A video-workstation variant whose window never follows the orientation sensor.
 *
 * The SurfaceView and ExoPlayer pipeline therefore keep the same dimensions and
 * position. Only the Compose chrome responds to physical device orientation.
 */
class StableVideoActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      DevilVideoStudioTheme {
        val deviceRotation = rememberDeviceRotation()
        VideoEditorScreen(
          modifier = Modifier.fillMaxSize(),
          layoutIsLandscape = abs(deviceRotation) == 90,
          chromeRotationDegrees = -deviceRotation.toFloat(),
        )
      }
    }
  }
}

@Composable
private fun rememberDeviceRotation(): Int {
  val context = LocalContext.current
  var rotation by remember { mutableIntStateOf(0) }
  val listener = remember {
    object : OrientationEventListener(context) {
      override fun onOrientationChanged(orientation: Int) {
        if (orientation == ORIENTATION_UNKNOWN) return
        val nextRotation = when (orientation) {
          in 45..134 -> 90
          in 135..224 -> 180
          in 225..314 -> -90
          else -> 0
        }
        // Ignore sensor jitter near quadrant boundaries.
        if (nextRotation != rotation) rotation = nextRotation
      }
    }
  }

  DisposableEffect(listener) {
    if (listener.canDetectOrientation()) listener.enable()
    onDispose { listener.disable() }
  }
  return rotation
}
