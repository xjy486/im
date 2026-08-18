---
status: accepted
---

# 使用 Spring AI 作为薄 OpenAI 兼容适配层

服务端采用 Spring AI 2.0 封装 OpenAI-compatible Chat Completions，并以 `AiProvider` 能力声明描述视觉输入、原生 JSON Schema、流式输出和上下文上限；未来可增加官方 OpenAI Responses 适配器。会话授权、上下文选择、任务状态、缓存、预算和结果所有权均由自有领域服务实现，不使用框架 Chat Memory 作为消息来源，也不启用自主 Agent。
