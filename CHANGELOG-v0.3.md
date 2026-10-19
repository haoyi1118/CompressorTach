# CompressorTach V0.3.0 — 更新说明

1. **清洁安装脚本**：`install-clean.bat` / `install-clean.ps1`，在 Windows 使用 ADB 检测已安装的 `com.compwave.tach`；如发现旧版，用户确认后先卸载，再安装新 APK。卸载会删除旧版 App 的私有数据。没有连接电脑或没有用户确认的情况下，普通 Android APK 无法自行静默卸载旧版本。
2. **单屏优先 UI**：压缩估算转速卡片、振动频谱卡片和间距；开始测量/停止测量、导出 CSV 按钮紧接频谱下方；较小屏幕或辅助字体放大时仍可滚动。
3. **测量设置入口**：屏幕右上角齿轮，弹窗设置最低/最高转速和 X/Y/Z/自动轴，点击保存后持久化；保存时如果正在测量，停止当前测量，下次开始时生效。
4. **版本**：`versionCode=3`，`versionName=0.3.0`，包名 `com.compwave.tach` 不变。核心转速算法沿用 v0.2 的最大谱峰优先与倍频候选逻辑。
5. **Actions**：构建成功后的 Artifact 包含 APK 和清洁安装脚本。

## 推荐安装方式（Windows）

1. GitHub Actions 成功后下载 `CompressorTach-debug-apk.zip` 并解压。
2. 安装 [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools)（提供 `adb`）；手机开启开发者选项、USB 调试，并授权当前电脑。
3. 双击解压目录里的 `install-clean.bat`。脚本会自动寻找同目录的 `app-debug.apk`；如未找到，请用 PowerShell 执行：
   ```powershell
   .\install-clean.ps1 -Apk "D:\\Download\\app-debug.apk"
   ```
4. 检测到旧版本时，根据提示输入 `UNINSTALL` 确认清空应用数据并卸载，脚本随后自动安装新版；如果没有旧版则直接安装。

> **限制**：若只是把 `app-debug.apk` 发送到手机里直接点安装，不会自动卸载旧版。这是 Android 安全模型的限制。签名一致、versionCode 更高时，Android 通常可直接覆盖升级并保留数据；GitHub 每次构建默认生成的 debug key 可能不同，跨次安装会出现签名不匹配，因此提供清洁安装脚本。

## 验证边界

Java 测速算法可通过 `tests/AnalyzerSelfTest.java` 在普通 JDK 上测试。由于本环境没有 Android SDK，最终 Android UI 的编译、不同屏幕单屏适配和 APK 安装流程需在 Android Studio / GitHub Actions 和实际手机上确认。
