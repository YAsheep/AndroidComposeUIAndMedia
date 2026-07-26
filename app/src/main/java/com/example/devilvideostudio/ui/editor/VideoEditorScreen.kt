package com.example.devilvideostudio.ui.editor

import android.app.Activity
import android.view.SurfaceView
import android.widget.Toast
import android.content.res.Configuration
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private val Glass = Color(0xC51B1D24)
private val Accent = Color(0xFFB8FF38)

@Composable
fun VideoEditorScreen(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val configuration = LocalConfiguration.current
  val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
  @Suppress("DEPRECATION")
  val displayRotation =
    (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
  val controller = remember { VideoEditorController(context.applicationContext) }
  // The same SurfaceView remains attached when the device rotates. Only its window
  // viewport is laid out again; the player, surface and playback position are retained.
  val videoSurface = remember { SurfaceView(context).also(controller::attach) }
  var hasVideo by remember { mutableStateOf(false) }
  var emojiEnabled by remember { mutableStateOf(true) }
  var isPlaying by remember { mutableStateOf(false) }
  var exportProgress by remember { mutableIntStateOf(-1) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    uri?.let {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          it,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      }
      controller.open(it)
      hasVideo = true
      isPlaying = true
    }
  }

  DisposableEffect(controller) {
    onDispose { controller.release() }
  }

  LaunchedEffect(displayRotation) {
    controller.setPreviewRotation(
      when (displayRotation) {
        Surface.ROTATION_90 -> 90f
        Surface.ROTATION_180 -> 180f
        Surface.ROTATION_270 -> -90f
        else -> 0f
      },
    )
  }

  Box(modifier.background(Color.Black)) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { videoSurface },
      onRelease = controller::detach,
    )

    if (!hasVideo) {
      Column(
        Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("LOCAL VIDEO LAB", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text("选择本地 MP4，解码画面将渲染到 SurfaceView", color = Color.LightGray)
        ActionButton("＋ 选择视频") { picker.launch("video/mp4") }
      }
    }

    EditorChrome(
      modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
      isLandscape = isLandscape,
      emojiEnabled = emojiEnabled,
      isPlaying = isPlaying,
      exportProgress = exportProgress,
      onOpen = { picker.launch("video/mp4") },
      onExport = {
        exportProgress = 0
        controller.export(
          onProgress = { exportProgress = it },
          onComplete = {
            exportProgress = -1
            Toast.makeText(context, "已保存到 Movies/DevilStudio", Toast.LENGTH_LONG).show()
          },
          onError = {
            exportProgress = -1
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
          },
        )
      },
      onSeekBack = { controller.seekBy(-5_000) },
      onSeekForward = { controller.seekBy(5_000) },
      onEmojiChange = {
        emojiEnabled = it
        controller.setEmojiEnabled(it)
      },
      onPlayPause = {
        controller.togglePlayback()
        isPlaying = !isPlaying
      },
    )
  }
}

@Composable
private fun EditorChrome(
  modifier: Modifier,
  isLandscape: Boolean,
  emojiEnabled: Boolean,
  isPlaying: Boolean,
  exportProgress: Int,
  onOpen: () -> Unit,
  onExport: () -> Unit,
  onSeekBack: () -> Unit,
  onSeekForward: () -> Unit,
  onEmojiChange: (Boolean) -> Unit,
  onPlayPause: () -> Unit,
) {
  Box(modifier) {
    val topAlignment = if (isLandscape) Alignment.TopEnd else Alignment.TopCenter
    val bottomAlignment = if (isLandscape) Alignment.BottomStart else Alignment.BottomCenter
    val horizontalEdge = if (isLandscape) 18.dp else 12.dp

    TopMenu(
      modifier = Modifier
        .align(topAlignment)
        .padding(
          top = if (isLandscape) 8.dp else 10.dp,
          end = if (isLandscape) horizontalEdge else 0.dp,
        ),
      onOpen = onOpen,
      exportProgress = exportProgress,
      onExport = onExport,
    )

    SideMenu(
      Modifier.align(Alignment.CenterStart).padding(start = horizontalEdge),
      title = "TOOLS",
      items = listOf("↶" to onSeekBack, "1×" to {}, "↷" to onSeekForward),
      compact = isLandscape,
    )
    SideMenu(
      Modifier.align(Alignment.CenterEnd).padding(end = horizontalEdge),
      title = "LAYER",
      items = listOf("T" to {}, "◐" to {}, "⌗" to {}),
      compact = isLandscape,
    )

    BottomMenu(
      modifier = Modifier
        .align(bottomAlignment)
        .navigationBarsPadding()
        .padding(start = if (isLandscape) horizontalEdge else 0.dp),
      emojiEnabled = emojiEnabled,
      isPlaying = isPlaying,
      onEmojiChange = onEmojiChange,
      onPlayPause = onPlayPause,
    )
  }
}

@Composable
private fun TopMenu(
  modifier: Modifier,
  onOpen: () -> Unit,
  exportProgress: Int,
  onExport: () -> Unit,
) {
  Surface(modifier.padding(top = 10.dp), color = Glass, shape = RoundedCornerShape(24.dp)) {
    Row(
      Modifier.padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      ActionButton("打开", onOpen)
      Text("DEVIL STUDIO", color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
      if (exportProgress >= 0) {
        CircularProgressIndicator(progress = { exportProgress / 100f }, color = Accent)
        Text("$exportProgress%", color = Color.White)
      } else {
        ActionButton("导出视频", onExport)
      }
    }
  }
}

@Composable
private fun SideMenu(
  modifier: Modifier,
  title: String,
  items: List<Pair<String, () -> Unit>>,
  compact: Boolean,
) {
  Surface(modifier, color = Glass, shape = RoundedCornerShape(24.dp)) {
    Column(
      Modifier.padding(
        horizontal = if (compact) 6.dp else 8.dp,
        vertical = if (compact) 8.dp else 12.dp,
      ),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 9.dp),
    ) {
      Text(title, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
      items.forEach { (label, action) ->
        Button(
          onClick = action,
          shape = CircleShape,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333640)),
          contentPadding = ButtonDefaults.ContentPadding,
        ) { Text(label, color = Color.White) }
      }
    }
  }
}

@Composable
private fun BottomMenu(
  modifier: Modifier,
  emojiEnabled: Boolean,
  isPlaying: Boolean,
  onEmojiChange: (Boolean) -> Unit,
  onPlayPause: () -> Unit,
) {
  Surface(modifier.padding(bottom = 12.dp), color = Glass, shape = RoundedCornerShape(28.dp)) {
    Row(
      Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("😈", style = MaterialTheme.typography.headlineSmall)
      Text("写入画面", color = Color.White)
      Switch(checked = emojiEnabled, onCheckedChange = onEmojiChange)
      ActionButton(if (isPlaying) "Ⅱ 暂停" else "▶ 播放", onPlayPause)
    }
  }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
  ) { Text(label) }
}
