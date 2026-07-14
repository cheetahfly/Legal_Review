# Legal_Review 深度审核报告

> 审核日期：2026-07-14
> 方法：superpowers + gstack 框架，4 个独立 agent 并行审核（安全 / Android 生命周期 / 功能逻辑 / 代码质量），主审做交叉验证与去重。
> 范围：全部 18 个 Kotlin 源文件 + 测试 + 构建配置 + Manifest。
>
> 修复状态：CRITICAL 5/5 ✅、HIGH 10/11（保留 M12 证书锁定需外部 pin）、MEDIUM 16/21（跳过 M2 大重构、M12 证书锁定、L10 security-crypto 升级、M16 response_format 因 array 冲突暂缓，部分规则误报改为更精准替代）、LOW 12/14。

## 审核方法与可信度

- 4 个 agent 独立读同一批文件，从不同维度找问题。
- **交叉验证**：多个 agent 独立命中的发现可信度最高（标注 ✅✅）。
- 主审抽样验证了全部 CRITICAL 发现的代码（读 `RiskRuleEngine.kt`、`AnalysisOrchestrator.kt`、`ScreenCaptureManager.kt`），确认属实。
- 个别 agent 误报已校准（标注 ⚠️ 校准）。

---

## CRITICAL（5）

### C1. 整个分析流水线运行在主线程，长协议触发 ANR ✅✅
- **定位**：`LegalOverlayService.kt:67`（`Dispatchers.Main.immediate`）
- **问题**：OCR 像素复制（~10MB）、ML Kit `result.text` 提取、规则引擎 10 条正则全量扫描、无障碍节点递归 IPC 遍历，全在主线程。1080×2400 屏幕的长协议综合耗时易超 5 秒 ANR 阈值。仅网络调用在 `Dispatchers.IO`。
- **修复**：分析流水线移到 `Dispatchers.Default`，仅 UI 更新切回主线程。

### C2. 正则 `.` 不匹配换行，离线兜底系统性漏报 ✅
- **定位**：`RiskRuleEngine.kt:83-96`（`compilePattern` 无 `DOT_MATCHES_ALL`），受影响模式：行 139/146/153/160/167/174/181/188
- **问题**：OCR 与无障碍都会在视觉换行处插入 `\n`。`不承担.*责任` 在 `"本公司不承担\n任何责任"` 下不命中；纯字面量关键词被换行截断也失效。测试样本都是单行，掩盖了此 bug。
- **验证输入**：`"本公司不承担\n任何责任"` → `liability_limit_1` 不命中
- **修复**：匹配前 `fullText.replace("\n","")` 归一化，或所有正则加 `RegexOption.DOT_MATCHES_ALL`。

### C3. 屏幕文本（可能含 PII）未脱敏直发第三方 LLM ✅
- **定位**：`AnalysisOrchestrator.kt:51`（`$text` 原文拼入 prompt）、`LegalAccessibilityService.kt:40-53`（递归抓全窗口文本）、`accessibility_config.xml`（无 packageNames 白名单）
- **问题**：无障碍路径读取当前 App 全部节点文本（银行余额、验证码、聊天内容、表单 autofill），截图路径整屏 OCR，均原样发 DeepSeek/智谱/通义服务器。
- **修复**：入口正则脱敏（手机号/身份证/银行卡/邮箱）；`accessibility_config.xml` 加 `packageNames` 或只采长文本节点；主界面显式告知用户。

### C4. ScreenCaptureManager 用 `suspendCoroutine`，不可取消致资源泄漏 ✅✅
- **定位**：`ScreenCaptureManager.kt:67`
- **问题**：非 `suspendCancellableCoroutine`。Service 销毁时挂起的截屏协程无法取消，ImageReader/VirtualDisplay 不释放，回调不触发则永久挂起。
- **修复**：改 `suspendCancellableCoroutine` + `cont.invokeOnCancellation { 释放 reader/display }`。

### C5. `createVirtualDisplay` 返回 null 致永久挂起，悬浮按钮永久不可见 ✅
- **定位**：`ScreenCaptureManager.kt:92-97`（只 try/catch 异常，未处理 null）
- **问题**：返回 null 时 ImageReader 永不收帧，`cont.resume()` 永不触发，悬浮按钮（`LegalOverlayService.kt:144` 设 INVISIBLE）永久不可见，无恢复路径。
- **修复**：`if (virtualDisplay == null) cont.resume(Result.failure(IllegalStateException("VirtualDisplay null")))`。

---

## HIGH（11）

### H1. AnalysisResult JSON 通过 Intent extra 传递，TransactionTooLargeException ✅✅
- **定位**：`ResultSink.kt:12`、`LegalOverlayService.kt:259-278`
- **问题**：Binder 上限 ~1MB。长协议命中 20+ 条 finding，每条 excerpt+explanation+advice，JSON 轻松超限。`openResultScreen` 的 `startActivity` 抛异常且在 `launch{}` 内无 try/catch，结果页打不开。
- **修复**：改进程内单例缓存 + Intent 只传 ID；或写 cacheDir 文件传路径。至少外层加 try/catch 降级。

### H2. LLM 错误被静默吞掉，用户无法区分"无风险"与"调用失败" ✅✅✅
- **定位**：`AnalysisOrchestrator.kt:30`（`getOrElse { emptyList() }`）、`:37`（`llmUsed = llmFindings.isNotEmpty()`）、`:56`（解析失败也 emptyList）
- **问题**：网络故障、401、429、非 JSON 全部静默退化为"仅本地"。LLM 成功返回 `[]`（无风险）也显示"未用/失败"。用户不知 LLM 是否真工作过。
- **修复**：`AnalysisResult` 加 `llmError: String?`，透传失败原因给 UI。

### H3. `parseLlmFindings` 单条异常炸掉整批 LLM 结果 ✅
- **定位**：`AnalysisOrchestrator.kt:67-82`
- **问题**：`mapNotNull` 只过滤 null 不捕获异常。任一 item 非 JsonObject 或字段类型不符（如 `category` 是数组），异常传播到 `getOrElse`，整批丢失。
- **验证输入**：`[{"category":"AUTO_RENEW",...}, "garbage"]` → 第一条有效发现也丢失
- **修复**：每条 item 包 `runCatching { ... }.getOrNull()`。

### H4. 文本长度无上界 + 请求无 max_tokens，长协议 LLM 必败 ✅
- **定位**：`AnalysisOrchestrator.kt:29`（仅 `>= 30` 下界）、`OpenAiCompatibleLlmClient.kt:68-73`（无 `max_tokens`）
- **问题**：截屏 OCR 全文可达数万字，超模型上下文窗口触发 4xx，静默降级。无 max_tokens 则模型可输出超长 JSON 被截断为非法 JSON。
- **修复**：截断文本至 ~8k 字符再送 LLM；请求体加 `max_tokens: 2048`。

### H5. 每次点击悬浮按钮新建 OkHttpClient，线程池/连接池泄漏 ✅✅
- **定位**：`LegalOverlayService.kt:219-226`（`buildOrchestrator()` 每次新建）、`OpenAiCompatibleLlmClient` 默认构造器
- **问题**：每次分析新建 `SettingsRepository` + `LlmClient` + `OkHttpClient`（含连接池+线程池）。OkHttpClient 应单例。连续点击累积泄漏。
- **修复**：Service 持有 `lazy { LlmClientFactory.create(...) }`，配置变更才重建。

### H6. 重新授权泄漏旧 MediaProjection + VirtualDisplay ✅
- **定位**：`LegalOverlayService.kt:104-106`
- **问题**：`onStartCommand` 收到新 intent 时直接覆盖 `screenCaptureManager`，旧实例未 `stop()`。Android 14+ 每 token 仅允许一个活跃 MediaProjection。
- **修复**：创建新实例前 `screenCaptureManager?.stop()`。

### H7. 截屏无超时，悬浮按钮可能永久隐藏 ✅
- **定位**：`ScreenCaptureManager.kt:81-89`
- **问题**：ImageReader 仅在 `acquireLatestImage()` 非 null 时 resume。若永不产生帧，continuation 永不恢复，按钮永久 INVISIBLE，无反馈。
- **修复**：`withTimeout(5_000)` 或 `Handler.postDelayed` 超时返回 `Result.failure`。

### H8. ImageReader 回调 + 10MB 像素复制在主线程 ✅
- **定位**：`ScreenCaptureManager.kt:36`（`Handler(Looper.getMainLooper())`）、`:103-114`（`imageToBitmap`）
- **问题**：回调在主线程分发，`copyPixelsFromBuffer` 同步复制 ~10MB，明显丢帧卡顿。
- **修复**：用专用 `HandlerThread("CaptureThread")`，`stop()` 时销毁。

### H9. 合并逻辑：LLM 低严重度覆盖本地高严重度同类别 ✅
- **定位**：`AnalysisOrchestrator.kt:85-90`
- **问题**：按 category 去重，LLM 优先。本地 LIABILITY_LIMIT(HIGH) 被 LLM 的 LOW 覆盖，用户低估风险。
- **验证输入**：本地 HIGH + LLM 返回同类别 LOW → 仅剩 LOW
- **修复**：同类别重叠取 `max(severityRank)`。

### H10. ProGuard 规则不足，release 构建序列化/枚举崩溃 ✅✅
- **定位**：`app/proguard-rules.pro`（仅 keep ML Kit）
- **问题**：release `isMinifyEnabled=true` 但未 keep kotlinx.serialization 生成的 serializer（`RiskFinding`/`AnalysisResult` 运行时崩溃），未 keepnames `RiskCategory`/`Severity` 枚举常量（`valueOf("AUTO_RENEW")` 失败、prompt 里类别名与代码不一致）。
- **修复**：补 `-keepnames enum com.legalreview.analysis.RiskCategory { *; }` 等；或 `RiskFinding` 改用枚举后由 kotlinx.serialization 自动 keep；加官方 kotlinx.serialization ProGuard 规则。

### H11. 缺 networkSecurityConfig，API 26-27 默认允许明文 HTTP ✅
- **定位**：`AndroidManifest.xml:11-17`、`build.gradle.kts:13`（minSdk=26）、`LlmConfig.kt:11`（baseUrl 无 HTTPS 校验）
- **问题**：Android 9+ 默认禁明文，但 minSdk=26 意味 API 26-27 设备允许明文 HTTP。若 baseUrl 被改为 HTTP，API Key 明文发送。
- **修复**：加 `res/xml/network_security_config.xml`（`cleartextTrafficPermitted="false"`）；`LlmConfig` init 校验 `startsWith("https://")`。

---

## MEDIUM（17）

| # | 问题 | 定位 |
|---|---|---|
| M1 | `RiskFinding` 用 String 存 category/severity/source，丢失类型安全，三处重复 `when` | `RiskFinding.kt:9-18` |
| M2 | `LegalOverlayService` 职责过载（悬浮窗+通知+截屏+OCR+分析+结果），核心逻辑不可测 | `LegalOverlayService.kt` 全文 |
| M3 | UI 层直接做 IO，无 ViewModel，旋转丢未保存配置 | `MainApp.kt:45,145,150` |
| M4 | `errorResult` 的 `categoryLabel="提示"` 与 `category="OTHER"`（label 是"其他风险"）不一致 | `LegalOverlayService.kt:201-217` |
| M5 | LLM severity 未归一化大小写（`"High"` vs `"HIGH"`），序列化字段不一致 ⚠️校准：排序/显示因 uppercase 正常，仅字段不一致 | `AnalysisOrchestrator.kt:71-75` |
| M6 | markdown 代码块剥离大小写敏感 + 不处理前后附加文本（` ```JSON` 不剥离） | `AnalysisOrchestrator.kt:60` |
| M7 | `high_penalty_1` 不解析百分比值，1% 也报"违约金过高" | `RiskRuleEngine.kt:188` |
| M8 | 本地命中未按类别去重，"自动续费"出现 5 次产生 5 条重复 finding + 浪费 LLM token | `AnalysisOrchestrator.kt:26-27` |
| M9 | `allowBackup="true"` 允许应用数据被备份提取 | `AndroidManifest.xml:12` |
| M10 | API Key 输入框未做密码掩码（无 `PasswordVisualTransformation`） | `MainApp.kt:135-141` |
| M11 | LLM 提示注入：协议原文原样拼入 prompt，无分隔/转义 | `AnalysisOrchestrator.kt:51` |
| M12 | 无证书锁定（CertificatePinning），企业/恶意 CA 可 MITM 截获 API Key | `OpenAiCompatibleLlmClient.kt:79-82` |
| M13 | `OcrRecognizer` 用 `suspendCancellableCoroutine` 但未 `invokeOnCancellation`，ML Kit Task 取消后仍运行 ✅✅ | `OcrRecognizer.kt:22-32` |
| M14 | `ProjectionAuthActivity` 旋转重建会再次弹出 MediaProjection 授权框 | `ProjectionAuthActivity.kt:20-36` |
| M15 | `imageToBitmap` 中间填充 Bitmap（~10MB）未 recycle | `ScreenCaptureManager.kt:110-113` |
| M16 | 请求未设 `response_format: json_object`，依赖脆弱的 markdown 剥离 | `OpenAiCompatibleLlmClient.kt:68-73` |
| M17 | `OpenAiCompatibleLlmClient` 对外部 JSON 用 `!!`，`content` 为数组（多模态）时崩溃，错误信息无业务含义 | `OpenAiCompatibleLlmClient.kt:49-52` |

### 规则引擎误报/漏报（MEDIUM）

| 规则 | 问题 | 验证输入 |
|---|---|---|
| `irrevocable_auth_1` `永久.*授权` 过宽 | "永久使用授权"误报为不可撤销 | `"用户可获得永久使用授权"` |
| `unilateral_terminate_1` `无需承担任何责任.*终止` 过窄+顺序颠倒 | "有权随时终止，且不承担任何责任"不命中 | 见左 |
| `high_penalty_1` `赔偿.*倍.*费用` 要求"费用" | "赔偿服务费3倍"不命中 | 见左 |
| `jurisdiction_1` `regex:仲裁` 过宽 | "诉讼与仲裁的选择"误报 | 见左 |

---

## LOW（14）

| # | 问题 | 定位 |
|---|---|---|
| L1 | `matchedKeyword` 展示正则字符串（如`赔偿.*上限`）进 LLM context ⚠️校准：未进 RiskFinding/UI，仅影响 LLM 上下文质量 | `RiskRuleEngine.kt:101-119` |
| L2 | excerpt 边界用 label 长度而非实际命中长度，贪婪模式下截取错位 | `RiskRuleEngine.kt:109` |
| L3 | 空结果时 ResultScreen 无"未发现风险"占位，大片空白 | `ResultScreen.kt:41-48` |
| L4 | LazyColumn `items` 无稳定 key，全量重组 | `ResultScreen.kt:45` |
| L5 | `llmUsed` 语义误导：LLM 成功返回 `[]` 也显示"未用/失败" | `AnalysisOrchestrator.kt:37` |
| L6 | API Key 以明文 String 驻留内存多处，无法主动清零 | `LlmConfig.kt:12` 等 |
| L7 | LLM 异常 message 含响应体前 500 字，接 Crashlytics 可能泄露 | `OpenAiCompatibleLlmClient.kt:42` |
| L8 | `getParcelableExtra` 已废弃重载（Android 13+ 类型不安全） | `LegalOverlayService.kt:101-102` |
| L9 | `byProvider` 未知 provider 静默回退 DeepSeek，用户无感知 | `LlmConfig.kt:31` |
| L10 | security-crypto 1.1.0-alpha06 为 alpha 版，keystore key 失效后 prefs 不可读 | `libs.versions.toml:12` |
| L11 | 全量硬编码中文字符串，strings.xml 仅 2 条 | 多处 |
| L12 | `enableJetifier=true` 但无旧 support 库，无用转换拖慢构建 | `gradle.properties:3` |
| L13 | `buildConfig=true` 但无 BuildConfig 字段使用；`appcompat` 仅 1 处用 | `build.gradle.kts:35,85` |
| L14 | 死代码/死资源：`ids.xml`、`colors.xml` 的 `app_primary_dark`/`app_background`、`kotlinx-coroutines-test`、`material-icons-extended`（仅用 1 图标） | 多处 |

---

## 已确认 OK 的项（无问题）

- **组件导出**：除 MainActivity（launcher 必需）外全部 `exported=false`；LegalAccessibilityService 需系统 BIND 权限 ✅
- **权限最小化**：6 个权限均与功能直接对应，无多余 ✅
- **foregroundServiceType**：`mediaProjection` 声明正确，`startForeground` 在 `getMediaProjection` 之前调用 ✅
- **PendingIntent**：使用 `FLAG_IMMUTABLE` ✅
- **截图不落盘**：Bitmap 仅内存传 OCR，未写文件；OCR 全本地 ✅
- **日志安全**：全部 Log 只记长度/尺寸/布尔，无 OCR 文本/像素/API Key ✅
- **OkHttp 无 HTTPLoggingInterceptor**：不会把 Bearer token 打 logcat ✅
- **JSON 解析防崩**：`RiskCategory.valueOf` 包了 runCatching，恶意 LLM 返回无法触发崩溃/代码执行 ✅
- **Provider 预设**：DeepSeek/Zhipu/Qwen 三家 baseUrl + model 经验证真实正确 ✅
- **依赖版本**：OkHttp 4.12.0、kotlinx-serialization-json 1.6.3 无已知 CVE ✅

---

## 修复优先级建议

### 立即处理（影响核心功能/数据安全）
1. **C1** 主线程 ANR → 分析流水线移到 `Dispatchers.Default`
2. **C2** 正则换行漏报 → 文本归一化或 `DOT_MATCHES_ALL`
3. **C3** PII 脱敏 + 用户告知
4. **C4+C5+H7** ScreenCaptureManager 三连：可取消 + null 检查 + 超时
5. **H1** Intent 大数据崩溃 → 单例缓存

### 本周内
6. **H2** LLM 错误透传（加 `llmError` 字段）
7. **H3** parseLlmFindings 单条 runCatching
8. **H4** 文本截断 + max_tokens
9. **H5** OkHttpClient 单例化
10. **H9** 合并取 max severity
11. **H10** ProGuard 规则
12. **H11** networkSecurityConfig

### 下个迭代
13. **M1** RiskFinding 改枚举（消除 M4/M5/L1/L2 一连串问题）
14. **M2** 抽 CaptureCoordinator/ResultPresenter，让 runAnalysis 可测
15. **M3** 引入 ViewModel
16. **M7-M8** 规则引擎去重 + 百分比解析
17. **M10** API Key 掩码
18. **M16** response_format json_object

### opportunistic
19. 其余 MEDIUM / LOW 按需处理

---

## 测试盲区

当前 31 个单元测试 + 6 个 instrumentation 测试未覆盖：
- 多行 OCR 文本（C2 未捕获）
- LLM 返回混合类型 JSON 数组（H3 未捕获）
- 大写 ` ```JSON` 代码块（M6 未捕获）
- LLM 与本地同类别不同 severity 合并（H9 未捕获）
- 百分比数值边界（M7 未捕获）
- `LegalOverlayService` / `ScreenCaptureManager` / `OcrRecognizer` 完全无测试（需重构才能注入）

**最大可测性瓶颈**：`LegalOverlayService` 把截屏/OCR/分析/结果全锁死在 Service 里，依赖硬编码（H5/M2）。抽出 `CaptureCoordinator` + `AnalysisRunner` 后，错误分支才能单测。
