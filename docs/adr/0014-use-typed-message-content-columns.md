---
status: accepted
---

# 使用类型化消息内容字段

消息表以 `text_content` 和 `media_id` 表示文本与图片内容，并使用 CHECK 约束保证消息类型与字段组合有效，只把非核心扩展信息放入 `metadata JSONB`。这使撤回时的内容清理、媒体引用管理和数据库约束保持明确，而不把整个消息正文隐藏在自由 JSONB 中。
