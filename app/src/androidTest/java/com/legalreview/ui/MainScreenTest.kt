package com.legalreview.ui

import androidx.compose.ui.test.assertIsDisplayed
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
 * 在真机/模拟器上跑：验证 MainApp 渲染、权限按钮、配置重建持久化等 UI 行为。
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
    fun config_survivesRecreateWithoutSave() {
        // M3: rememberSaveable 保证未保存的输入在 Activity 重建后不丢。
        // 用 model 字段（非掩码）验证，API Key 已掩码不便断言明文。
        val newModel = "test-model-${System.currentTimeMillis()}"
        composeRule.onNodeWithText("模型名").performClick()
        composeRule.onNodeWithText("模型名").performTextInput(newModel)

        composeRule.activityRule.scenario.recreate()

        composeRule.onNodeWithText(newModel, substring = true).assertIsDisplayed()
    }
}
