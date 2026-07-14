# Legal_Review 测试速查表

> 单页参考：环境配置 + 跑测试 + 看报告 + 常见错误速查。

## 环境

| 项 | 值 |
|---|---|
| JDK | 17（已安装在 `C:\Users\user\.jdks\jdk-17\jdk-17.0.11+9`） |
| Android SDK | `F:\Android\Sdk`（写在 `local.properties`） |
| Gradle | 8.7（由 `./gradlew` wrapper 自动下载） |
| 平台 | Windows 10 + Git Bash |

`JAVA_HOME` **不会**在 shell 之间持久——每次新 shell 起来都要设：

```bash
export JAVA_HOME="C:/Users/user/.jdks/jdk-17/jdk-17.0.11+9"
export PATH="$JAVA_HOME/bin:$PATH"
```

要长期生效加到 `~/.bashrc`：

```bash
echo 'export JAVA_HOME="C:/Users/user/.jdks/jdk-17/jdk-17.0.11+9"' >> ~/.bashrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.bashrc
```

---

## 单元测试（JVM，无设备）

```bash
cd F:/my_git/Legal_Review

# 跑全部
./gradlew :app:testDebugUnitTest

# 只跑某个包
./gradlew :app:testDebugUnitTest --tests "com.legalreview.analysis.*"
./gradlew :app:testDebugUnitTest --tests "com.legalreview.overlay.*"
./gradlew :app:testDebugUnitTest --tests "com.legalreview.llm.*"
./gradlew :app:testDebugUnitTest --tests "com.legalreview.data.*"

# 只跑单个测试类
./gradlew :app:testDebugUnitTest --tests "com.legalreview.analysis.RiskRuleEngineTest"

# 只跑某个测试方法
./gradlew :app:testDebugUnitTest --tests "com.legalreview.analysis.RiskRuleEngineTest.auto renew keyword hits"
```

### 看报告

| 类型 | 路径 |
|---|---|
| HTML（浏览器） | `app/build/reports/tests/testDebugUnitTest/index.html` |
| JUnit XML（CI） | `app/build/test-results/testDebugUnitTest/*.xml` |

Windows 下打开 HTML：
```bash
start app/build/reports/tests/testDebugUnitTest/index.html
```

### 当前测试矩阵（Phase 1 + 2 收尾）

| 套件 | 测试数 | 覆盖 |
|---|---|---|
| `RiskRuleEngineTest` | 8 | 10 类风险规则 + 边界 |
| `AnalysisOrchestratorTest` | 8 | LLM/本地合并 + Markdown 容错 + 排序 |
| `LegalAnalysisPromptTest` | 3 | system prompt 完备性 |
| `OpenAiCompatibleLlmClientTest` | 2 | HTTP 200 + HTTP 错误 |
| `SettingsRepositoryTest` | 4 | 默认/保存/provider 切换/覆盖 |
| `ResultSinkTest` | 6 | 编解码 + 容错 + 降级 |
| **合计** | **31** | |

---

## 完整构建（debug APK）

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 顺带打测试 APK（用于 adb 安装到设备）
./gradlew :app:assembleDebugAndroidTest
# 产物：app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

---

## 真机 / 模拟器测试（需 adb 设备）

```bash
# 1. 确认设备已连接
adb devices

# 2. 跑 instrumentation 套件
./gradlew :app:connectedDebugAndroidTest

# 3. 看报告
start app/build/reports/androidTests/connected/index.html

# 4. 手工 e2e 场景：见 docs/e2e-checklist.md
```

---

## 常见错误速查

| 症状 | 原因 | 修法 |
|---|---|---|
| `JAVA_HOME is not set` | 新 shell 没设环境变量 | 按本文开头设 `JAVA_HOME` |
| `SDK location not found` | `local.properties` 缺失或路径错 | 检查 `sdk.dir=F\:\\Android\\Sdk` |
| `AndroidKeyStore not found`（Robolectric） | EncryptedSharedPreferences 在沙箱里跑不了 | 已通过重构规避：测试用 `SettingsRepository(prefs)` 注入 |
| `Unresolved reference: DEFAULT_OPTIONS` | ML Kit 16.0.0 没这个常量 | 用 `ChineseTextRecognizerOptions.Builder().build()` |
| `onResume overrides nothing` | 缺 `LifecycleOwner` 参数 | 改为 `override fun onResume(owner: LifecycleOwner)` |
| `Could not download` gradle 发行包 | 网络中断 | 直接重跑 `./gradlew`（断点续传） |
| `testDebugUnitTest` 卡住 | 上次 daemon 文件被占用 | 关闭 IDE / 杀 `java.exe`，再重跑 |

---

## 写入新测试时的约定

- **单元测试**：放 `app/src/test/java/.../`，不需要 Android 框架的用纯 JUnit；需要 Context 的用 Robolectric。
- **真机测试**：放 `app/src/androidTest/java/.../`，用 `AndroidJUnit4` runner。
- **mock 策略**：项目用「手写 Fake 类」（`FakeLlmClient` 模式）；无 mockito/mockk 依赖。
- **依赖注入**：`SettingsRepository` 已暴露 `@VisibleForTesting constructor(SharedPreferences)`，新增需要 Android Context 的类时优先走同样模式。

---

## CI 集成提示（最小化）

```yaml
# GitHub Actions 片段
- name: Unit Tests
  run: ./gradlew :app:testDebugUnitTest

- name: Publish Test Results
  uses: mikepenz/action-junit-report@v4
  if: always()
  with:
    report_paths: app/build/test-results/testDebugUnitTest/*.xml
```

CI 上不用设 Windows 路径——runner 是 Linux，把 `JAVA_HOME` 换成：
```yaml
JAVA_HOME: /usr/lib/jvm/java-17-openjdk
sdk.dir: ${{ env.ANDROID_HOME }}
```