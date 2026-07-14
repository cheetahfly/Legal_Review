# Legal_Review 真机 e2e 测试清单

> 适用版本：`app-debug.apk` (Phase 0/1/2 之后构建)
> 测试设备：Android 8.0+（API 26+），推荐 11+（无障碍更好用）

## 0. 准备

```bash
# 编译
cd F:\my_git\Legal_Review
gradlew assembleDebug

# 安装到设备（请先 adb devices 确认设备已连接）
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 启动（包名 com.legalreview）
adb shell am start -n com.legalreview/.MainActivity
```

清空旧数据（每次回归测试前做）：
```bash
adb shell pm clear com.legalreview
```

日志观察（捕获所有相关 TAG）：
```bash
adb logcat -c
adb logcat LegalOverlayService:V ScreenCaptureManager:V OcrRecognizer:V AnalysisOrchestrator:V *:S
```

---

## 1. 首次启动 / 主界面

| # | 动作 | 期望结果 |
|---|---|---|
| 1.1 | 启动 App | 进入主界面，标题"协议把关" |
| 1.2 | 顶部状态 | "悬浮窗权限：未开启"，"无障碍快捷路径（可选）：未开启" |
| 1.3 | 三个按钮 | "去开启悬浮窗权限"、"去开启无障碍服务"、"授权并启动悬浮按钮"（后者应**禁用**） |
| 1.4 | 云端模型下拉 | 默认显示 `DEEPSEEK · deepseek-chat` |
| 1.5 | 模型名 / API Key 输入框 | 模型名可改，API Key 留空 |

**回归点**：截屏保存主界面状态。

---

## 2. 权限授予

### 2.1 悬浮窗权限
- 点击"去开启悬浮窗权限" → 跳转到系统设置页 `ACTION_MANAGE_OVERLAY_PERMISSION`。
- 回到 App → 顶部状态应刷新为"已开启"（`onResume` 触发）。
- "授权并启动悬浮按钮"按钮应**变为可点**。

### 2.2 无障碍服务（可选）
- 点击"去开启无障碍服务" → 跳转到 `ACTION_ACCESSIBILITY_SETTINGS`。
- 找到"协议把关" → 开启。
- 回到 App → 状态显示"已开启"。

**回归点**：杀掉 App 再开，状态应正确保留。

---

## 3. 配置保存

| # | 动作 | 期望结果 |
|---|---|---|
| 3.1 | 下拉切换到 `ZHIPU` | 模型名应自动变为 `glm-4-flash`（来自 preset） |
| 3.2 | 输入假 API Key `sk-test-zhipu-12345` | 文本框同步显示 |
| 3.3 | 点击"保存设置" | 无明显反馈（设计如此），但 `SettingsRepository.saveConfig` 落盘 |
| 3.4 | 杀掉 App，重启 | 配置应保留，模型名/API Key 都对 |

**验证存储加密**（adb 抓 prefs 看不出明文）：
```bash
adb shell run-as com.legalreview cat /data/data/com.legalreview/shared_prefs/legal_review_secrets.xml
# 应是 Tink 加密的 base64，看不到 sk-test-zhipu-12345
```

---

## 4. 悬浮按钮 + 截屏分析（核心场景）

> 需要先完成 §2（至少悬浮窗权限）和 §3（API Key 已保存）。

| # | 动作 | 期望结果 |
|---|---|---|
| 4.1 | 点击"授权并启动悬浮按钮" | 弹出系统"屏幕共享"授权对话框 |
| 4.2 | 允许授权 | 后台启动 `LegalOverlayService`，悬浮按钮出现 |
| 4.3 | 退出 App 到桌面 | 悬浮按钮仍在桌面顶层 |
| 4.4 | 打开浏览器 → 搜"用户协议模板" → 进入一个含条款的页面 | 屏幕上有可见文字 |
| 4.5 | 点悬浮按钮 | 出现"分析中…"提示（toast 或 notification） |
| 4.6 | 等待 5-10 秒 | 弹出结果页（`ResultActivity`），列出若干风险条款 |
| 4.7 | 返回桌面 | 悬浮按钮仍在 |

**日志检查**：
```
LegalOverlayService: onClick
ScreenCaptureManager: MediaProjection started
OcrRecognizer: recognize started
AnalysisOrchestrator: local findings=N
AnalysisOrchestrator: llm call took=Xms
ResultSink: encoded findings=N
```

**判定**：
- 至少命中 1 条风险条款
- `source` 列至少出现 "local" 或 "llm"
- API Key 有效时（DeepSeek 真实 key），应同时出现 "local" 和 "llm" 两条来源

---

## 5. LLM 失败 / 离线回退

### 5.1 错误 API Key
| # | 动作 | 期望结果 |
|---|---|---|
| 5.1.1 | 把 API Key 改成 `sk-invalid` 保存 | — |
| 5.1.2 | 点悬浮按钮分析 | 仍能弹出结果，**至少有本地规则命中的条款** |
| 5.1.3 | 日志应见 `OpenAiCompatibleLlmClient: HTTP 401` 类似 | — |

### 5.2 飞行模式
| # | 动作 | 期望结果 |
|---|---|---|
| 5.2.1 | 开启飞行模式 | — |
| 5.2.2 | 点悬浮按钮 | 仍能弹出结果（仅本地规则），不崩溃、不卡死 |

---

## 6. 无障碍快捷路径

> 需要 §2.2 已开启。

| # | 动作 | 期望结果 |
|---|---|---|
| 6.1 | 在一个普通文本页面（非 FLAG_SECURE）点悬浮按钮 | 结果应比 4.6 **更快**（无截图/OCR） |
| 6.2 | 退出无障碍服务，再点悬浮按钮 | 自动回退到截屏路径，不报错 |

---

## 7. 边界 / 健壮性

| # | 动作 | 期望结果 |
|---|---|---|
| 7.1 | 在设置页把 API Key 留空 | 点悬浮按钮仍能用本地规则分析 |
| 7.2 | 分析一份纯空白页 | 结果页打开，findings 为空或仅 1 条"OTHER" |
| 7.3 | 旋转屏幕 / 横竖屏切换 | 结果页内容不丢 |
| 7.4 | 系统杀进程后重开 | 主界面正常进入；之前保存的配置还在 |

---

## 8. 回归

每改一个核心模块，跑一遍 **§1、§4.1-4.6** 即可覆盖 80% 路径。
改 `SettingsRepository` → 加跑 **§3**。
改 `OcrRecognizer` / `AnalysisOrchestrator` → 加跑 **§4-§5**。
改 Compose UI → 加跑 **§1、§6**。