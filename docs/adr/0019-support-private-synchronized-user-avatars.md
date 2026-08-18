---
status: accepted
---

# 支持受控可见并跨端同步的用户头像

用户可以上传、裁剪、替换和移除头像；无头像时客户端显示用户名首字符生成的默认头像。头像只允许本人和与其存在 C2C 会话的已认证用户读取，`users` 保存 `avatar_media_id` 与 `avatar_version`；变更后向所有会话对端生成 `USER_PROFILE_UPDATED` 同步事件，Android 和 PC 按版本失效本地加密缓存。
