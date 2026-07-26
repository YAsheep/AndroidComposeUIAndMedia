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
