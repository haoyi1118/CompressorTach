# CompressorTach V0.3 — 冰箱全封闭压缩机手机振动转速仪

利用 Android 手机**内置三轴加速度传感器**和振动谐波分析，估算单缸往复式压缩机的曲轴转速（RPM）。

> **状态：开发原型，非计量级设备。** 本包是 Android Studio 源码工程，不是 APK；当前工作环境没有 Android SDK 和 Gradle，未完成 Android 实机编译/安装测试。核心 FFT/转速分析已通过 JVM 合成信号测试。应用不需要互联网连接、麦克风、外置转速探头。

## 功能

- 5 秒滑动振动采集窗、约 1 秒刷新一次测量结果
- 三轴加速度采集（`m/s²`），自动或指定 X/Y/Z 轴分析
- 依据 `SensorEvent.timestamp` 测量实际采样率、插值均匀重采样
- 去均值、Hann 窗、FFT、谱峰插值；**以测量范围所对应的 1×～3× 频带内最大有效谱峰为主峰**，不再采用谐波评分给峰值排名
- 优先将最大谱峰解释为 1×；若超出所设转速范围，则依次考虑 2×、3×，同时把其他在范围内的解释呈现为候选转速
- 估算转速卡片显示主峰频率、折算阶次、实际采样率、其他可能转速及置信度
- 页面顺序：**转速与实际采样率 → 实时频谱 → 开始/停止及 CSV 导出**；右上角齿轮进入测量设置弹窗
- 用户可在右上角 ⚙ 设置转速搜索范围（默认 **900–4800 rpm**）及分析轴；设置会持久化
- 导出最近缓存的原始三轴数据 CSV，兼容 Excel / Python / CompWave 的后续离线处理
- 完全本机运行，代码无第三方运行时依赖，也无需网络权限

## 安装与编译（Windows）

1. 安装 [Android Studio](https://developer.android.com/studio)，首次安装配置 Android SDK Platform **35** 和 JDK **17 或更高**（推荐 IDE 内置 JDK）。
2. 解压项目到**英文路径**，如 `D:\Projects\CompressorTach`。
3. 在项目根目录 PowerShell 中运行一次：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\prepare-wrapper.ps1
   ```

   此步骤会从 Gradle 官方域名下载 `gradle-wrapper.jar` 并校验官方 SHA-256。需要联网。中国大陆网络如果访问 Gradle 官方站点失败，请使用可访问外网的环境下载，不要从不明网站获取 JAR。
4. Android Studio → **Open** → 选择 `CompressorTach` 文件夹 → 等待 Gradle Sync。首次需联网下载 Android Gradle Plugin 8.9.2、Gradle 8.11.1 和 SDK 组件。
5. USB 连接 Android 手机，启用**开发者选项 → USB 调试**，在 Android Studio 点绿色 Run；或者 `Build → Build Bundle(s) / APK(s) → Build APK(s)` 生成 `app/build/outputs/apk/debug/app-debug.apk` 再安装。
6. 也可将源码上传到**自己的 GitHub 仓库**：仓库 Actions 中有自动构建 APK 的工作流，Actions 构建成功后在 Artifacts 下载 `CompressorTach-debug-apk`，无需本地 Android SDK。上传前请确认本机环境和 GitHub 的依赖网络能够访问 Gradle。

### 仅测试算法（不需要 Android Studio）

装有 JDK 17+ 时，在 Windows 双击 `tests/run-self-test.bat`；它仅编译 JVM 纯 Java 核心，使用人工产生的多工况振动样本检测算法。**通过不等于实机测量精度已验证。**

## 测试操作（压缩机）

1. 保证压缩机**稳定转速运行**，不要在加减速过程中判定精度。
2. 将手机可靠固定在**静止、隔热且与压缩机有振动耦合的支架**上。不可直接放到高温压缩机壳体、裸露通电端子附近，也不可将手机固定到旋转件上。手机不得妨碍压缩机散热。采用柔软台面可能完全衰减转频，信号会变弱。
3. 测试前点击右上角 ⚙，设置**尽可能窄的真实转速范围**：若已知控制器转速大约 3000 rpm，可设 2700–3300 rpm；若可能是 4500 rpm，建议设 4000–4800 rpm。未知转速时可先用 900–4800 rpm 粗检，之后逐步缩小范围。
4. 点击**开始测量**，5–7 秒后读取 RPM、**实测最大谱峰和折算阶次**、实际采样率、备选转速和可信度。可更换 X/Y/Z 轴重复测量并比较结果。
5. 点击频谱下方的**停止测量**后点击**导出 CSV**，在手机的文档选择器中指定保存位置。

### 测量原理与关键限制

`RPM = 60 × f₁`，其中 `f₁` 为曲轴一阶旋转频率（Hz）。压缩机单缸往复激励包含 1×、2×、3× 等阶次，**最大谱峰不一定是 1×**。V0.2 按需求使用**最大有效谱峰优先**：在给定转速范围对应的一至三倍频段内找到最大幅值峰，把它优先作为一阶转速候选；如果其折算转速不在输入范围内，依次尝试解释为二阶或三阶。其它符合范围的阶次解释会明确显示出来。用户须结合已知转速、控制器频率或额外测量来甄别。

但**没有独立转速基准/先验转速范围时，单一振动频率可能不具有唯一解**。例如强烈的 75 Hz 谱峰既可能是 4500 rpm 的 1×，也可能是 2250 rpm 的 2×、1500 rpm 的 3×。如果仪器观测到最强 80 Hz，输入范围为 900–4800 rpm，则**首选会显示 4800 rpm，但 2400 rpm 和 1600 rpm 同样是合理的倍频解释**；该显示策略并不保证首选值更接近真实曲轴转速。理论上不存在仅靠这一谱峰判明真实转速的通用算法。

**采样率非常重要。** Android 12+ 限制普通传感器事件回调（除非具备相应权限），设备实际采样率由手机型号、系统和隐私设置决定。即使 app 申请了 200 Hz，实际也可能只有 50–100 Hz。200 Hz 的采样率对应 100 Hz 的 Nyquist 上限，可靠分析频段还须留裕量；高于 Nyquist 的机械振动分量可能混叠到低频导致错误判断。高转速（例如 4500 rpm = 75 Hz）在 200 Hz 采样率下只有一阶可能完整观测到，2× = 150 Hz 已超过 Nyquist。手机加速度计的内部低通滤波和机械带宽也因机型而异，不保证适用于高频、小幅结构振动；采样率高不等于有效带宽高。

**测量结果仅是估计值，必须用变频器速度反馈、专用振动/电磁转速计或其他独立方法校验。** 当前“可信度”只是谱线质量提示，不等同经过标定的准确率概率。

实际窗口时长约 5 秒，原始物理频率分辨率约 0.2 Hz（约 12 rpm）。零填充 FFT 仅帮助峰值插值，不会提升该物理分辨率。实测误差受传感器响应、混叠、非平稳性、振动耦合、干扰等影响，无法承诺 ±1 rpm。

## CSV 数据格式

```
# CompressorTach vibration capture; timestamps are monotonic, not wall-clock time
# units: t_s, ax_m_s2, ay_m_s2, az_m_s2
# estimated_rpm=...; confidence=...; fs_Hz=...; axis=...
t_s,ax_m_s2,ay_m_s2,az_m_s2
0.00000000,0.0000000,0.0000000,9.8100000
...
```

`ax, ay, az` 包含重力，离线分析时应先去均值/去趋势。`t_s` 是相对于本次采集首条数据的时间，使用 Android 单调时钟而非 UTC 时间。CSV 最长存储最近约 16000 条采样数据，能覆盖的时长取决于实际采样率（200 Hz 时约 80 秒）。

## 项目结构

```
app/src/main/java/com/compwave/tach/
  MainActivity.java      页面、交互、导出 CSV
  SensorRecorder.java    三轴传感器采样线程与缓存
  RpmAnalyzer.java       FFT 与候选转速算法（纯 Java）
  SpectrumView.java      频谱自绘
  VibrationSample.java   加速度样本模型
tests/AnalyzerSelfTest.java 合成信号算法测试
.github/workflows/build-apk.yml GitHub Actions 自动构建 APK
```

## 后续建议

- 支持“指定已知转速”标定，并对每款手机建立设备/工况适用性数据库
- 加入麦克风与加速度联合分析、转频连续跟踪、倍频冲突复核和转速历史趋势
- 引入抗混叠评估与对快速转速变化的短时追踪
- 多组真实压缩机测试验证（含转速基准），形成不同手机、不同安装位置的准确率统计

## 官方技术依据

- [Android Sensors Overview（高采样率限制）](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [SensorManager 官方 API](https://developer.android.com/reference/android/hardware/SensorManager)
- [Android Gradle Plugin 8.9 兼容性](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [Gradle Wrapper 安全校验](https://docs.gradle.org/current/userguide/gradle_wrapper.html)

## V0.2 更新记录（2026-10-19）

1. **转速算法**：主峰选择（V0.2）由谐波评分改为 FFT 最大有效峰值优先；显示主峰的 1×/2×/3× 全部范围内候选转速，图中竖线跟随真实主峰而非假定的一阶转频。最大峰值频率在转速范围之外但高次谐波可以解释时，首选按能落入范围的最小阶次折算，并提示较低可信度。
2. **采样率展示**：实际加速度传感器采样率从频谱卡片移到主转速卡片；在积累够 FFT 数据之前，若已有至少两条样本，也可先显示基于时间戳的初步实际采样率。
3. **布局**：转速卡片后立即显示实时频谱，后面是测量设置与操作按钮。
4. **合成信号测试**：加入“最强二阶高于范围”“最强二阶落在范围”“一阶最强”“高转速窄范围”“低采样率警告”等测试。测试通过不代表 Android APK 编译或压缩机实机测试通过。

### 从原版 GitHub 仓库升级

下载 V0.2 源码压缩包后，将解压得到的 `CompressorTach/` **全部内容**复制到已有仓库对应根目录（同名文件覆盖），保留仓库的 `.git/` 文件夹。然后运行：

```powershell
git add .
git commit -m "CompressorTach v0.2 peak-first RPM estimator and UI"
git push
```

如果没有 Git，则可在 GitHub 网页上传替换这些文件（重点是 `RpmAnalyzer.java`、`MainActivity.java`、`SpectrumView.java`、`app/build.gradle`），提交后到 Actions 页运行 `Build Android APK`。该源码包保留了 V0.1.1 已修订的 `build-apk.yml`。

---

## V0.3.0 UI 与安装更新（2026-10-19）

详细说明见 [CHANGELOG-v0.3.md](CHANGELOG-v0.3.md)。

- 当前版本号为 `0.3.0`。界面显示顺序变更为：紧凑标题（右上角测量设置）→ 估算转速（含实际采样率和潜在转速）→ 实时频谱 → 开始测量 / 导出 CSV 按钮；尽量单屏容纳，超小屏及系统放大字体时可滚动。
- 最低/最高转速和分析轴改用右上角 **⚙ 设置按钮**进入弹窗，点击保存持久化。
- **普通 Android APK 无法自动卸载旧版。** 如需“先卸载旧版再装新版”，在 Windows USB 连接手机，通过 `install-clean.bat` / `install-clean.ps1` 执行；首次操作需要明确确认数据被清除。APK 直接安装的默认行为仍由 Android 安装程序控制。新脚本会同时包含在成功的 GitHub Actions Artifact 中。
- 已知限制：测得最大峰值不必然等于机械转频，请继续参照上文的倍频候选说明。
