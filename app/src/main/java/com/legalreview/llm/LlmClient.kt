package com.legalreview.llm

/**
 * 统一的大模型客户端接口。可插拔：未来新增非 OpenAI 兼容 provider 时实现此接口即可。
 */
interface LlmClient {
    /**
     * 用 [systemPrompt] 作为 system 指令、[userContent] 作为用户输入，调用聊天补全。
     * @return 模型输出的文本内容（通常是 JSON 串）；失败返回 Result.failure。
     */
    suspend fun chat(systemPrompt: String, userContent: String): Result<String>
}
