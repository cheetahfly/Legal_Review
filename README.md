# 协议把关 (Legal Review)

Android 原生 App（Kotlin + Jetpack Compose），仅个人自用。在其他 App / 网页遇到"同意协议/合同"弹窗时，点悬浮按钮即可截屏 → OCR → 本地规则引擎预筛 + 云端大模型精分析 → 提示需特别注意的风险条款。

## 功能流程

1. 主界面开启悬浮窗权限、填写云端模型 API Key（默认 DeepSeek，可切智谱 GLM / 通义千问）。
2. 点「授权并启动悬浮按钮」→ 系统弹 MediaProjection 授权框 → 同意后悬浮按钮出现。
3. 在其他 App 遇到协议弹窗时点悬浮按钮：隐藏按钮 → 截屏 → ML Kit 中文 OCR → 本地规则引擎 → 云端大模型 → 通知展示风险清单。

## 技术栈

- 截屏：MediaProjection API（前台服务 `foregroundServiceType="mediaProjection"`）
- OCR：ML Kit Text Recognition **bundled 中文包**（离线，不依赖 GMS）
- 本地规则引擎：关键词/正则匹配 10 类常见风险条款
- 云端大模型：OkHttp + kotlinx.serialization，OpenAI 兼容单实现覆盖 DeepSeek/智谱/通义，切 provider 只改 config
- Key 存储：EncryptedSharedPreferences
- UI：Jetpack Compose + Material3

## 模块结构

```
com.legalreview
├── MainActivity.kt              入口
├── overlay/
│   ├── LegalOverlayService.kt   前台服务：悬浮按钮 + 截图 + OCR + 分析调度
│   ├── ProjectionAuthActivity.kt MediaProjection 授权
│   └── ScreenCaptureManager.kt  MediaProjection 截图封装
├── ocr/OcrRecognizer.kt         ML Kit 中文 OCR
├── analysis/
│   ├── RiskRuleEngine.kt        本地规则引擎（10 类风险）
│   ├── AnalysisOrchestrator.kt  串联：文本→规则→LLM→合并
│   └── RiskFinding.kt           结果数据类
├── llm/
│   ├── LlmClient.kt             统一接口
│   ├── OpenAiCompatibleLlmClient.kt  OpenAI 兼容实现 + 工厂
│   ├── LlmConfig.kt             配置 + 三家 provider 预设
│   └── LegalAnalysisPrompt.kt   法务分析 system prompt
├── data/SettingsRepository.kt   EncryptedSharedPreferences 存配置
└── ui/
    ├── MainApp.kt               主界面（设置 + 权限引导）
    └── ResultScreen.kt          风险清单展示（供后续结果页接入）
```

## 构建与验证

### 环境要求

- JDK 17（本机已知路径：`C:\Users\user\.jdks\jdk-17\jdk-17.0.11+9`）
- Android SDK（compileSdk 34，build-tools；若本机无，需安装 Android Studio 或单独 SDK）
- Gradle 8.7（通过 wrapper）

### 首次配置

1. 设置 `JAVA_HOME` 指向 JDK 17。
2. 创建 `local.properties`，写入 Android SDK 路径，例如：
   ```
   sdk.dir=F\:\\Android\\Sdk
   ```
3. 生成 Gradle Wrapper（若本机有 gradle）：
   ```
   gradle wrapper --gradle-version 8.7
   ```
   或用 Android Studio 打开本项目，IDE 会自动补齐 wrapper 与 SDK。

### 构建

```bash
./gradlew assembleDebug
```

### 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

覆盖：
- `RiskRuleEngineTest` — 本地规则引擎对典型条款的命中
- `OpenAiCompatibleLlmClientTest` — 用 MockWebServer 验证请求体与响应解析

### 端到端验证（真机/模拟器）

1. `./gradlew installDebug` 安装。
2. 打开 App → 开启悬浮窗权限 → 填 DeepSeek API Key 并保存。
3. 点「授权并启动悬浮按钮」→ 允许屏幕共享 → 悬浮盾牌按钮出现。
4. 打开一个带协议弹窗的 App/网页 → 点悬浮按钮。
5. 在通知中查看审查结果；Logcat（tag: `LegalOverlayService`）可看截图尺寸、OCR 文本长度、分析过程。

## 关键注意事项

- **Android 14**：`foregroundServiceType="mediaProjection"` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限已声明；`startForeground` 在 `onStartCommand` 中调用。
- **ML Kit**：使用 bundled 版（`com.google.mlkit:text-recognition-chinese`），国内不依赖 GMS 下载，APK 体积约 +38MB。
- **FLAG_SECURE App**：截图会黑屏、无障碍也常读不到，属系统限制；后续可在 UI 提示用户手动复制协议文字。
- **三家 provider base_url**：DeepSeek `/v1`、智谱 `/api/paas/v4`、通义 `/compatible-mode/v1`，已在 `LlmProviderPresets` 固化。

## 待办（可选增强）

- 阶段7：接入 `AccessibilityService` 作为免 OCR 快捷路径（取屏幕节点文本，失败回退截图）。
- ResultScreen 接入：把通知展示升级为 App 内结果页（ResultScreen 已写好，待与悬浮按钮点击结果打通）。
- 悬浮按钮拖动：当前固定位置，后续加 `updateViewLayout` 拖动。
- Paddle-Lite OCR：若 ML Kit 中文识别率不达标，可替换为 PP-OCR。
