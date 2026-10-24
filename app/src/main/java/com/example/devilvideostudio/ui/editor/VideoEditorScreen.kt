package com.example.devilvideostudio.ui.editor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.devilvideostudio.ui.blur.SnapshotBlurSource
import com.example.devilvideostudio.ui.blur.rememberSnapshotBlurSource
import com.example.devilvideostudio.ui.blur.snapshotBlurBackground

private val Glass = Color(0x8C16171C)
private val SoftGlass = Color(0x731D1E24)
private val Accent = Color(0xFFFF2442)
private val BiliPink = Color(0xFFFB7299)
private val HdrGold = Color(0xFFFFC94A)

/** Blurred snapshots of the video, captured once and shared by every bar in the chrome. */
private val LocalBlurSource = staticCompositionLocalOf<SnapshotBlurSource?> { null }

/** Frosted glass over the video, refreshed twice a second; just [tint] when there is no video. */
@Composable
private fun Modifier.frostedGlass(tint: Color, shape: Shape = RectangleShape): Modifier {
  val blurSource = LocalBlurSource.current ?: return background(tint, shape)
  return snapshotBlurBackground(blurSource, tint, shape)
}

@Composable
fun VideoEditorScreen(
  modifier: Modifier = Modifier,
  layoutIsLandscape: Boolean? = null,
  chromeRotationDegrees: Float = 0f,
) {
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }
  val configuration = LocalConfiguration.current
  val isLandscape =
    layoutIsLandscape ?: (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
  val controller = remember { VideoEditorController(context.applicationContext) }
  // Keep the same SurfaceView and controller while configChanges relays orientation to Compose.
  // SurfaceView (not TextureView) is required for HDR: it passes HDR frames to the display as-is.
  val videoSurface = remember { SurfaceView(context).also(controller::attach) }
  @Suppress("DEPRECATION")
  val displaySupportsHdr = remember(activity) { activity?.windowManager?.defaultDisplay?.isHdr == true }
  var hasVideo by remember { mutableStateOf(false) }
  var dynamicRange by remember { mutableStateOf<VideoDynamicRange?>(null) }
  var videoAspectRatio by remember { mutableFloatStateOf(0f) }
  var emojiEnabled by rememberSaveable { mutableStateOf(true) }
  var isPlaying by remember { mutableStateOf(false) }
  // Paused video shows a still frame, so one snapshot is enough until playback resumes.
  val blurSource = rememberSnapshotBlurSource(videoSurface.takeIf { hasVideo }, active = isPlaying)
  var exportProgress by remember { mutableIntStateOf(-1) }
  var landscapeLocked by rememberSaveable { mutableStateOf(false) }
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    uri?.let {
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          it,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
      }
      dynamicRange = null
      controller.open(it)
      hasVideo = true
      isPlaying = true
    }
  }

  DisposableEffect(controller) {
    controller.onDynamicRangeChanged = { dynamicRange = it }
    controller.onVideoAspectRatioChanged = { videoAspectRatio = it }
    controller.onPlaybackError = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    onDispose { controller.release() }
  }

  DisposableEffect(activity, landscapeLocked) {
    if (activity != null) {
      activity.requestedOrientation = if (landscapeLocked) {
        // The control is only exposed in landscape, so LOCKED pins the exact current side.
        ActivityInfo.SCREEN_ORIENTATION_LOCKED
      } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      }
    }
    onDispose { }
  }

  Box(modifier.background(Color.Black)) {
    AndroidView(
      // A SurfaceView stretches frames to its own size, so give it the video's shape. The window
      // itself rotates with the device, so the frames never need extra rotation.
      modifier = Modifier.align(Alignment.Center).then(
        if (videoAspectRatio > 0f) Modifier.aspectRatio(videoAspectRatio) else Modifier.fillMaxSize(),
      ),
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
        Text("选择本地 MP4，开始你的创作", color = Color.LightGray)
        ActionButton("＋ 选择视频", onClick = { picker.launch("video/mp4") })
      }
    }

    AnimatedContent(
      targetState = isLandscape,
      modifier = Modifier.fillMaxSize(),
      transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
      label = "editor-orientation",
    ) { landscape ->
      CompositionLocalProvider(LocalBlurSource provides blurSource) {
        EditorChrome(
          modifier = Modifier.fillMaxSize(),
          isLandscape = landscape,
          landscapeLocked = landscapeLocked,
          emojiEnabled = emojiEnabled,
          isPlaying = isPlaying,
          exportProgress = exportProgress,
          dynamicRange = dynamicRange,
          displaySupportsHdr = displaySupportsHdr,
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
          onLandscapeLockChange = { landscapeLocked = it },
        )
      }
    }
  }
}

@Composable
private fun EditorChrome(
  modifier: Modifier,
  isLandscape: Boolean,
  landscapeLocked: Boolean,
  emojiEnabled: Boolean,
  isPlaying: Boolean,
  exportProgress: Int,
  dynamicRange: VideoDynamicRange?,
  displaySupportsHdr: Boolean,
  onOpen: () -> Unit,
  onExport: () -> Unit,
  onSeekBack: () -> Unit,
  onSeekForward: () -> Unit,
  onEmojiChange: (Boolean) -> Unit,
  onPlayPause: () -> Unit,
  onLandscapeLockChange: (Boolean) -> Unit,
) {
  Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
    if (isLandscape) {
      LandscapeTopBar(
        Modifier.align(Alignment.TopCenter),
        exportProgress,
        dynamicRange,
        displaySupportsHdr,
        landscapeLocked,
        onOpen,
        onExport,
        onLandscapeLockChange,
      )
      SideMenu(
        Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
        title = "创作工具",
        items = listOf("T" to {}, "◐" to {}, "⌗" to {}),
        compact = true,
      )
      LandscapePlaybackBar(
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        isPlaying,
        onSeekBack,
        onPlayPause,
        onSeekForward,
      )
    } else {
      PortraitTopBar(
        Modifier.align(Alignment.TopCenter),
        exportProgress,
        dynamicRange,
        displaySupportsHdr,
        onOpen,
        onExport,
      )
      // Mirrored, unblurred copy for side-by-side comparison with the frosted menu on the right.
      SideMenu(
        Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
        title = "无模糊",
        items = listOf("T" to {}, "◐" to {}, "⌗" to {}),
        compact = false,
        frosted = false,
      )
      SideMenu(
        Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        title = "模糊",
        items = listOf("T" to {}, "◐" to {}, "⌗" to {}),
        compact = false,
      )
      PortraitBottomBar(
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        emojiEnabled,
        isPlaying,
        onEmojiChange,
        onPlayPause,
      )
    }
  }
}

@Composable
private fun PortraitTopBar(
  modifier: Modifier,
  exportProgress: Int,
  dynamicRange: VideoDynamicRange?,
  displaySupportsHdr: Boolean,
  onOpen: () -> Unit,
  onExport: () -> Unit,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .frostedGlass(Color(0x6B000000))
      .statusBarsPadding()
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    GhostButton("‹", onOpen)
    Column(Modifier.padding(start = 10.dp)) {
      Text("视频编辑", color = Color.White, fontWeight = FontWeight.SemiBold)
      Text("草稿已自动保存", color = Color(0xFFBABAC0), style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.width(8.dp))
    DynamicRangeBadge(dynamicRange, displaySupportsHdr)
    Spacer(Modifier.weight(1f))
    GhostButton("预览") { }
    Spacer(Modifier.width(8.dp))
    ExportButton(exportProgress, Accent, onExport)
  }
}

@Composable
private fun LandscapeTopBar(
  modifier: Modifier,
  exportProgress: Int,
  dynamicRange: VideoDynamicRange?,
  displaySupportsHdr: Boolean,
  landscapeLocked: Boolean,
  onOpen: () -> Unit,
  onExport: () -> Unit,
  onLandscapeLockChange: (Boolean) -> Unit,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .frostedGlass(Color(0x8008090C))
      .padding(horizontal = 16.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    GhostButton("‹ 返回", onOpen)
    Box(Modifier.height(18.dp).width(1.dp).background(Color(0xFF383940)))
    Text("DEVIL STUDIO", color = Color.White, fontWeight = FontWeight.Bold)
    Text("· 1080P", color = Color(0xFF92939B), style = MaterialTheme.typography.labelMedium)
    DynamicRangeBadge(dynamicRange, displaySupportsHdr)
    Spacer(Modifier.weight(1f))
    StatusPill("● 00:24 / 01:18")
    LockButton(landscapeLocked, onLandscapeLockChange)
    ExportButton(exportProgress, BiliPink, onExport)
  }
}

@Composable
private fun LockButton(locked: Boolean, onChange: (Boolean) -> Unit) {
  Button(
    onClick = { onChange(!locked) },
    colors = ButtonDefaults.buttonColors(
      containerColor = if (locked) Color(0x33FB7299) else Color(0xFF26272D),
      contentColor = if (locked) BiliPink else Color.White,
    ),
    shape = RoundedCornerShape(10.dp),
    contentPadding = ButtonDefaults.ContentPadding,
  ) {
    Text(if (locked) "▣ 已锁横屏" else "▢ 锁定横屏", style = MaterialTheme.typography.labelMedium)
  }
}

/** Shows whether the loaded video is HDR or SDR; nothing until the video track is known. */
@Composable
private fun DynamicRangeBadge(range: VideoDynamicRange?, displaySupportsHdr: Boolean) {
  range ?: return
  val label = when {
    !range.isHdr -> range.label
    displaySupportsHdr -> "${range.label} 已开启"
    else -> "${range.label} · 屏幕不支持"
  }
  Text(
    label,
    color = if (range.isHdr) Color(0xFF1A1300) else Color(0xFFD6D7DC),
    fontWeight = FontWeight.SemiBold,
    style = MaterialTheme.typography.labelSmall,
    modifier = Modifier
      .background(if (range.isHdr) HdrGold else Color(0xFF3A3B42), RoundedCornerShape(6.dp))
      .padding(horizontal = 7.dp, vertical = 3.dp),
  )
}

@Composable
private fun StatusPill(label: String) {
  Text(
    label,
    color = Color(0xFFD6D7DC),
    style = MaterialTheme.typography.labelMedium,
    modifier = Modifier.background(Color(0xFF23242A), RoundedCornerShape(8.dp)).padding(10.dp, 7.dp),
  )
}

@Composable
private fun ExportButton(progress: Int, color: Color, onExport: () -> Unit) {
  Button(
    onClick = onExport,
    enabled = progress < 0,
    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    shape = RoundedCornerShape(10.dp),
  ) {
    if (progress >= 0) {
      CircularProgressIndicator(
        progress = { progress / 100f },
        color = Color.White,
        strokeWidth = 2.dp,
        modifier = Modifier.size(16.dp),
      )
      Spacer(Modifier.width(7.dp))
      Text("$progress%", maxLines = 1)
    } else {
      Text("导出", maxLines = 1)
    }
  }
}

@Composable
private fun GhostButton(label: String, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
    contentPadding = ButtonDefaults.ContentPadding,
  ) { Text(label) }
}

@Composable
private fun SideMenu(
  modifier: Modifier,
  title: String,
  items: List<Pair<String, () -> Unit>>,
  compact: Boolean,
  frosted: Boolean = true,
) {
  val shape = RoundedCornerShape(18.dp)
  Surface(
    if (frosted) modifier.frostedGlass(SoftGlass, shape) else modifier.background(SoftGlass, shape),
    color = Color.Transparent,
    shape = shape,
  ) {
    Column(
      Modifier.padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
      Text(title, color = Color(0xFF999AA2), style = MaterialTheme.typography.labelSmall)
      items.forEach { (label, action) ->
        Button(
          onClick = action,
          shape = CircleShape,
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF303139)),
          contentPadding = ButtonDefaults.ContentPadding,
          modifier = Modifier.size(if (compact) 42.dp else 48.dp),
        ) { Text(label, color = Color.White) }
      }
    }
  }
}

@Composable
private fun LandscapePlaybackBar(
  modifier: Modifier,
  isPlaying: Boolean,
  onSeekBack: () -> Unit,
  onPlayPause: () -> Unit,
  onSeekForward: () -> Unit,
) {
  Row(
    modifier = modifier
      .padding(bottom = 10.dp)
      .frostedGlass(Glass, RoundedCornerShape(18.dp))
      .padding(horizontal = 10.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    GhostButton("↶ 5s", onSeekBack)
    ActionButton(if (isPlaying) "Ⅱ" else "▶", onPlayPause, BiliPink)
    GhostButton("5s ↷", onSeekForward)
    Box(Modifier.width(150.dp).height(3.dp).background(Color(0xFF55565E), CircleShape)) {
      Box(Modifier.fillMaxWidth(.32f).height(3.dp).background(BiliPink, CircleShape))
    }
    Text("00:24", color = Color.White, style = MaterialTheme.typography.labelSmall)
  }
}

@Composable
private fun PortraitBottomBar(
  modifier: Modifier,
  emojiEnabled: Boolean,
  isPlaying: Boolean,
  onEmojiChange: (Boolean) -> Unit,
  onPlayPause: () -> Unit,
) {
  Surface(
    modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).animateContentSize()
      .frostedGlass(Glass, RoundedCornerShape(22.dp)),
    color = Color.Transparent,
    shape = RoundedCornerShape(22.dp),
  ) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("00:24", color = Color.White, style = MaterialTheme.typography.labelSmall)
        Box(
          Modifier.weight(1f).padding(horizontal = 10.dp).height(3.dp)
            .background(Color(0xFF55565E), CircleShape),
        ) {
          Box(Modifier.fillMaxWidth(.32f).height(3.dp).background(Accent, CircleShape))
        }
        Text("01:18", color = Color(0xFFAAAAAF), style = MaterialTheme.typography.labelSmall)
      }
      Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Text("😈 贴纸入画", color = Color.White)
        Switch(checked = emojiEnabled, onCheckedChange = onEmojiChange)
        Spacer(Modifier.weight(1f))
        ActionButton(if (isPlaying) "Ⅱ 暂停" else "▶ 播放", onPlayPause, Accent)
      }
    }
  }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, color: Color = Accent) {
  Button(
    onClick = onClick,
    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
    shape = RoundedCornerShape(10.dp),
  ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
