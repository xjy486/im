---
status: accepted
---

# 在私有 MinIO 中保存规范化图片和头像

服务端通过 `MediaStorage` 接口把媒体保存到 Docker Compose 内部网络中的私有 MinIO。媒体用途分为 `MESSAGE_IMAGE` 和 `AVATAR`，生命周期统一为 `TEMP → BOUND → EXPIRED`：只有消息或头像事务成功后才能进入 `BOUND`；`EXPIRED` 立即禁止访问，物理文件由可重试异步任务删除，未绑定 TEMP 媒体在 24 小时后清理。为兜底处理数据库事务回滚后留下的对象，清理任务还会定期扫描受管控的 `message-images/`、`avatars/user/` 和 `avatars/group/` 前缀，仅删除数据库中不存在且超过可配置宽限期（默认 24 小时）的对象；扫描或删除失败会在后续周期重试。

客户端使用 UUIDv4 `uploadId` 保证上传幂等，SHA-256 只校验完整性，不做跨用户内容去重。服务端重新解码图片、移除 EXIF/GPS，消息图片限制最长边 2048px、最终不超过 5MB，并生成 320px 缩略图；头像根据裁剪区域生成 512×512 和 96×96 WebP。媒体通过后端鉴权代理访问，不使用预签名 URL；消息撤回或头像替换后立即拒绝旧媒体访问。
