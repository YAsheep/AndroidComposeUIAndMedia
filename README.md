# Devil Video Studio

一个使用 Jetpack Compose + Media3 的本地视频处理 Demo。

## 功能

- 从系统文件选择器打开本地 MP4
- ExoPlayer 解码并渲染到原生 `SurfaceView`
- Compose 实现覆盖于视频上方的上、下、左、右四区悬浮工具栏
- 实时开关 😈 视频帧叠加效果
- 使用 Media3 Transformer 将效果编码为 H.264/AAC MP4
- 通过 MediaStore 保存到 `Movies/DevilStudio`
- Activity 自适应横屏、竖屏及传感器旋转

## 稳定预览模式

默认启动入口为 `StableVideoActivity`。该 Activity 固定窗口坐标系，设备
旋转时保持 `SurfaceView`、视频输出尺寸和播放管线不变，只通过
`OrientationEventListener` 重新布局并旋转 Compose 工具栏。

旧的 `MainActivity` 仍保留用于对比：

```shell
adb shell am start -n com.example.devilvideostudio/.MainActivity
```

## 高斯模糊背景（毛玻璃工具栏）

悬浮在视频上方的工具栏使用“毛玻璃”背景：透过工具栏能看到被模糊后的视频画面。
实现位于 `app/src/main/java/com/example/devilvideostudio/ui/blur/SnapshotBlurBackground.kt`。

### 为什么不能直接用 `Modifier.blur`

视频渲染在 `SurfaceView` 上，它的像素在独立的 Surface 中由系统合成，不属于 View/Compose
的绘制树。Compose 的 `Modifier.blur` 只能模糊自身内容，拿不到下面的视频帧。因此这里采用
“定时截图 → 缩小 → 模糊 → 按位置贴回”的方案。

### 工作流程

1. **截图**：`rememberSnapshotBlurSource` 每 `intervalMillis`（默认 66ms，约 15fps）对视频
   View 截一次图：
   - `SurfaceView` 用 `PixelCopy` 读取（HDR 视频同样可用）；
   - `TextureView` 用 `getBitmap(target)` 直接让 GPU 缩放到小图；
   - 普通 View 录制到 `Picture` 后再生成 Bitmap，兼容硬件 Bitmap。
2. **降采样**：截图只取原尺寸的 1/8（GPU 模糊）或 1/16（CPU 模糊），大幅减少像素量，
   缩小本身也带来一部分模糊效果。
3. **模糊**：
   - Android 12（API 31）及以上：绘制时用 `RenderEffect`（Compose `BlurEffect`）在 GPU 上模糊；
   - 更老的设备：在后台线程用可分离高斯核（先横向、再纵向两遍）在 CPU 上模糊。
4. **贴回**：`Modifier.snapshotBlurBackground` 记录每个控件在窗口中的位置，把整张截图按
   “截图原点 − 控件原点”的偏移绘制，再用 `clip(shape)` 只保留控件背后那一块，最后叠加一层
   半透明 `tint`。
5. **平滑过渡**：每张新截图在一个截图间隔内从透明淡入，盖住上一张，避免画面一跳一跳。

### 性能优化

- **一次截图，多处共享**：所有工具栏通过 `CompositionLocal`（`LocalBlurSource`）共用同一个
  `SnapshotBlurSource`，不管有多少个控件，每个周期只截一次图。
- **按需截图**：没有控件在使用时（`users == 0`）暂停截图；视频暂停时只截一张静帧，恢复播放
  再继续。
- **零分配**：`SnapshotBuffers` 复用 Bitmap 和像素数组，三张显示 Bitmap 轮转（当前帧、
  淡出帧、新帧），每帧不产生新对象。
- **线程**：像素处理和 CPU 模糊放在 `Dispatchers.Default`；API 34 之前 `PixelCopy` 会阻塞
  调用线程，所以同样放在后台执行。

### 使用方式

```kotlin
// 1. 以视频 View 作为模糊源，暂停时停止连续截图
val blurSource = rememberSnapshotBlurSource(videoSurface, active = isPlaying)

// 2. 给任意控件加毛玻璃背景
Row(
  Modifier.snapshotBlurBackground(
    blurSource,
    tint = Color.Black.copy(alpha = 0.3f),
    shape = RoundedCornerShape(16.dp),
  )
) { /* ... */ }
```

可调参数：`blurRadius`（模糊半径，默认 24dp）、`intervalMillis`（截图间隔）、
`downscale`（降采样倍数）。

## 构建

```shell
./gradlew :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 使用

1. 点击“打开”或“选择视频”，选取一个 MP4。
2. 用底部开关控制 😈 是否写入视频帧。
3. 点击顶部“导出视频”，等待进度到 100%。
4. 在系统相册或 `Movies/DevilStudio` 中查看导出文件。

导出能力依赖设备提供可用的 H.264 编码器；不同设备的编码速度会有差异。
