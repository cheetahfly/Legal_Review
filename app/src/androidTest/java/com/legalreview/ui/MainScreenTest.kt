package com.legalreview.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.legalreview.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在真机/模拟器上跑：验证 MainApp 渲染、provider 切换、保存按钮等 UI 行为。
 * 不依赖网络或悬浮窗权限。
 */
@RunWith(AndroidJUnit4::class)
class MainScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainScreen_rendersCoreElements() {
        composeRule.onNodeWithText("协议把关").assertIsDisplayed()
        composeRule.onNodeWithText("悬浮窗权限：未开启").assertIsDisplayed()
        composeRule.onNodeWithText("无障碍快捷路径（可选）：未开启").assertIsDisplayed()
        composeRule.onNodeWithText("保存设置").assertIsDisplayed()
        // 没授权前，"授权并启动悬浮按钮" 应禁用
        composeRule.onNodeWithText("授权并启动悬浮按钮").assertIsNotEnabled()
    }

    @Test
    fun savingSettings_persistsAcrossRecreate() {
        // 改 API Key + 保存 → 杀进程重开 → 验证还在
        val newKey = "sk-instrumentation-${System.currentTimeMillis()}"
        composeRule.onNodeWithText("API Key").performClick()
        composeRule.onNodeWithText("API Key").performTextInput(newKey)
        composeRule.onNodeWithText("保存设置").performClick()

        // 触发 Activity 重建（旋转模拟）
        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText(newKey).assertIsDisplayed()
    }
}