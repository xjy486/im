# 即通即时通信系统技术方案

> 状态：架构设计已确认，尚未开始业务代码实现  
> 确认日期：2026-08-17  
> 适用范围：Android、macOS、Spring Boot 单体服务  
> 领域词汇：[CONTEXT.md](../../CONTEXT.md)  
> 架构决策：[docs/adr/](../adr/)

## 1. 结论

方案技术上可行，但已经从最初的 C2C 演示原型扩展为一个分阶段交付的完整即时通信系统，包含：

- 账号登录、联系人申请和拉黑；
- C2C 与群聊文本、图片消息；
- Android FCM 后台通知；
- 一台 MOBILE 与一台 PC 同时在线；
- Android 与 macOS 加密本地历史及中英文搜索；
- 消息撤回、群管理移除和可靠同步；
- 公开群搜索、群号、邀请链接、二维码和审批；
- 私人 AI 总结、回复草稿、待办及关键信息提取。

在 2 核 2GB 单机上可以运行和展示，但不提供高可用，也不以海量连接为目标。必须按里程碑实现和验收，不能把所有功能一次性堆到客户端中。

## 2. 首版范围

### 2.1 支持

- 用户可以公开注册，服务端为其分配账号；已注册用户使用账号和密码登录；
- 用户、群各有不可变 11 位公开号码；
- 联系人申请、删除和单向拉黑；
- C2C 与最多 100 人群聊；
- 文本与图片消息；
- 用户头像和群头像；
- 服务端权威历史、断线同步和本地恢复；
- Android 离线发送队列；
- Android 与 macOS 本地加密、历史浏览及中英文搜索；
- MOBILE、PC 各最多一台活动设备；
- 群主、管理员、普通成员权限；
- 私人 AI 助手。

### 2.2 明确不支持

- 语音、视频、普通文件、动态表情；
- 消息编辑、表情回应、引用回复；
- 正在输入、在线状态、群逐成员已读；
- 端到端加密；
- 两台手机同时在线；
- Web、Windows、Linux 客户端；
- 短信或邮件自助找回密码；
- AI 自动发消息、外部任务执行、Agent 工具调用。

## 3. 质量与容量目标

首版运行基线为一台 2 核 2GB 云主机：

| 指标 | 目标 |
|---|---:|
| 并发 WSS | 100 |
| 持续消息吞吐 | 20 条/秒 |
| 短时峰值 | 50 条/秒 |
| C2C 持久化 ACK | p95 ≤ 500ms |
| 100 人群消息 ACK | p95 ≤ 1.5s |
| 同步分页 | 最多 200 个事件 |
| 本地 10 万文本搜索 | p95 ≤ 200ms |
| AI 外部调用超时 | 60 秒 |

FCM 不承诺严格到达时间；权威交付保证来自服务端持久化与后续同步。

## 4. 总体架构

    Android App                          macOS App
    ├─ Compose UI                       ├─ Compose Desktop
    ├─ Room + SQLCipher                 ├─ H2 AES
    ├─ WorkManager                      ├─ JDBC Adapter
    ├─ OkHttp WSS                       └─ OkHttp WSS
    └─ FCM
             \                           /
              \ HTTPS / WSS             /
                    Caddy
                      │
                Spring Boot
    ┌─────────────────┼──────────────────┐
    │ Auth / Device    │ Contact / Group │
    │ Message / Sync   │ Media / Avatar  │
    │ AI / Audit       │ Admin           │
    └──────────┬───────┴───────┬─────────┘
               │               │
          PostgreSQL       Private MinIO
               │
         Outbox Dispatcher ── FCM
               │
            AI Worker ───── OpenAI-compatible API

后端为模块化单体：统一编译、统一部署、单 PostgreSQL 事务边界，不引入 Kafka、微服务、服务发现或 Kubernetes。

## 5. 技术选型

### Android

- Kotlin、Jetpack Compose；
- ViewModel、Repository、Room；
- SQLCipher、Android Keystore；
- OkHttp WebSocket、Retrofit；
- Coil 自定义加密媒体加载；
- WorkManager 只负责调度，Room `pending_commands` 才是离线队列。

### macOS

- Kotlin、Compose Desktop；
- H2 AES 嵌入式数据库；
- macOS Keychain；
- JDBC 持久化适配器；
- 与 Android 共享协议、领域模型和搜索分词逻辑；
- 首版只允许在线发送，但支持离线查看和搜索。

### Server

- Java、Spring Boot、Spring Security；
- Spring JDBC `JdbcClient`；
- Flyway；
- PostgreSQL；
- Spring WebSocket；
- Firebase Admin SDK；
- Spring AI 2.0 作为薄模型适配层；
- MinIO 私有对象存储。

## 6. 身份、账号和公开号码

### 6.1 内部身份

用户、群、消息、媒体等内部主键使用 UUIDv7。内部 UUID 是外键、鉴权和协议实体引用的唯一依据。

### 6.2 账号与群号

账号和群号共用全局 `public_identifiers` 命名空间：

- 11 位纯数字；
- 前 10 位由密码学安全随机数生成，首位非零；
- 最后一位为 Luhn 校验位；
- 以 `CHAR(11)` 保存；
- 永久不可修改、不可自选；
- 账号注销或群解散后标记 `retired_at`，永不复用。

公开号码只能用于展示、登录和搜索，绝不能直接用于权限判断。

### 6.3 用户搜索

- 只支持完整账号精确搜索；
- 不支持昵称、账号前缀或模糊搜索；
- 用户可关闭 `searchable_by_account_no`；
- 返回账号、昵称、头像和联系人申请状态；
- 不返回邮箱、设备、在线状态或其他成员关系；
- 按用户与 IP 限速，未找到与不可见使用统一响应。

## 7. 登录与设备安全

### 7.1 密码

- 注册参数：`display_name + password`，服务端分配账号并建立首个设备会话；
- 登录参数：`account_no + password`；
- 公开注册按 IP 限速，注册成功直接返回首个设备的 Access/Refresh Token；
- 密码使用 Argon2id；
- 服务端只保存密码哈希；
- 修改密码需验证当前密码，撤销其他设备会话，当前设备轮换令牌；
- 管理员重置生成一次性临时密码，撤销所有设备和令牌，首次登录必须改密；
- 首版不做短信/邮件自助找回。

### 7.2 Token

- Access Token：高熵不透明随机值，有效期 15 分钟；
- Refresh Token：有效期 30 天，按 family 轮换；
- 数据库只保存 SHA-256 摘要；
- Refresh Token 重放会撤销整个 family 和对应设备；
- Token 不进入消息数据库；
- WSS 仅通过 `Authorization: Bearer` 握手，禁止 query token。

### 7.3 设备类别

设备类别只允许：

- `MOBILE`；
- `PC`。

同一用户最多同时有一个 ACTIVE MOBILE 和一个 ACTIVE PC：

    CREATE UNIQUE INDEX uq_active_device_per_class
    ON devices(user_id, device_class)
    WHERE trust_state = 'ACTIVE';

安装 ID 由 App 首次安装时随机生成，服务端只保存哈希，不使用 IMEI 等硬件标识。

### 7.4 同类设备替换

相同安装 ID 且未失信时复用设备记录；不同安装占用同类槽位时：

1. 登录接口返回一次性 replacement challenge；
2. 用户确认；
3. 服务端锁定用户；
4. 撤销旧设备、会话和 Refresh Token family；
5. 创建新设备和登录会话；
6. 提交事务；
7. 关闭旧 WSS；
8. 旧客户端下次联系服务端时删除令牌、数据库密钥和媒体缓存。

challenge 保存 `replaced_device_id`、`new_installation_id_hash`、`device_class`，五分钟过期且只能使用一次。

## 8. 联系人与 C2C 权限

### 8.1 联系人申请

申请状态：

- `PENDING`；
- `ACCEPTED`；
- `REJECTED`；
- `CANCELLED`；
- `EXPIRED`。

申请七天过期，可附带最多 100 字符验证信息。同一用户对只能存在一个待处理申请；双方交叉申请时自动接受。

### 8.2 C2C 创建

联系人申请被接受后，在一个幂等事务中：

1. 创建对等联系人关系；
2. 创建或复用唯一 C2C 会话；
3. 为双方写入同步事件；
4. 为活动设备写入 outbox。

每次发送 C2C 消息时，服务端重新检查联系人状态和拉黑关系。

### 8.3 删除和拉黑

删除联系人会终止双向关系并禁止新消息，但双方保留既有历史与媒体访问；会话进入只读归档。重新添加时复用原会话，但 AI 同意必须重新开启。

拉黑是单向安全操作：

- 结束联系人关系；
- 取消待处理申请；
- 禁止申请和消息；
- 关闭 C2C AI 并取消任务；
- 不向被拉黑者暴露原因；
- 解除拉黑不会自动恢复联系人。

关系结束后不再同步昵称、头像及在线资料变化。

## 9. 会话与群聊

### 9.1 会话类型

- `C2C`：唯一用户对；
- `GROUP`：最多 100 名活动成员。

`conversations` 保存统一序号和生命周期；`c2c_conversations` 保存规范化用户对；`groups` 保存群资料和治理设置。

### 9.2 群角色

| 角色 | 权限 |
|---|---|
| OWNER | 唯一；转让群主、设置管理员、管理成员、修改群资料、开关群 AI、解散群 |
| ADMIN | 邀请/移除普通成员、审批入群、修改群资料 |
| MEMBER | 发送消息、撤回自己一分钟内消息、主动退群 |

管理员不能管理群主或其他管理员。群主必须先转让身份才能退群。

### 9.3 可见性

| 类型 | 名称搜索 | 群号搜索 | 链接/二维码 | 管理员邀请 |
|---|---|---|---|---|
| PUBLIC | 是 | 是 | 是 | 是 |
| UNLISTED | 否 | 是 | 是 | 是 |
| PRIVATE | 否 | 否 | 否 | 是 |

所有用户主动加入均需审批；管理员邀请可以直接入群。

### 9.4 邀请与审批

邀请令牌为 256 位随机值，数据库只保存 SHA-256 摘要，默认七天有效，支持最大使用次数和立即撤销。二维码只编码 HTTPS App Deep Link。

入群申请状态：

- `PENDING`；
- `APPROVED`；
- `REJECTED`；
- `CANCELLED`；
- `EXPIRED`。

普通移除后可以再次申请；群黑名单同时禁止搜索申请、链接申请和管理员邀请。

### 9.5 历史可见性

成员记录保存 `history_visible_after_seq`。入群时取当前 `conversation.last_seq`，只能读取更大序号的内容。重新入群会生成新的 `membership_version`，不能恢复旧历史。

退出、被移除或群解散后：

- 立即失去服务器访问；
- 客户端删除该群消息、搜索索引和媒体缓存；
- 群解散后服务端保留 30 天安全缓冲，再删除消息、媒体、头像和关联 AI 内容；
- 只保留不含内容的治理审计。

### 9.6 群系统事件

以下变化以不可搜索、不可撤回的 `SYSTEM` 消息进入统一 `conversationSeq`：

- GROUP_CREATED；
- MEMBER_JOINED；
- MEMBER_LEFT；
- MEMBER_REMOVED；
- ROLE_CHANGED；
- GROUP_PROFILE_UPDATED；
- AI_POLICY_CHANGED；
- GROUP_DISSOLVED。

## 10. 消息模型

### 10.1 标识与顺序

- `clientMsgId`：客户端 UUIDv4，用于幂等和重试；
- `messageId`：服务端 UUIDv7；
- `conversationSeq`：单会话严格递增；
- `syncSeq`：单用户同步事件流严格递增；
- `requestId`：客户端 UUIDv4，用于协议请求幂等和追踪。

`conversationSeq` 负责会话排序，`syncSeq` 负责多会话发现、跨端镜像、已读、撤回、群事件和 AI 私人事件。

### 10.2 类型与状态

服务端消息类型：

- `TEXT`；
- `IMAGE`；
- `SYSTEM`。

服务端消息状态：

- `ACTIVE`；
- `RECALLED`；
- `MODERATED`。

`SENDING`、`FAILED` 等只属于客户端本地发送状态，不写入服务端消息状态。

### 10.3 内容约束

- 文本最多 4000 个 Unicode code point；
- UTF-8 编码后最多 16 KiB；
- 单个 WSS 业务帧最多 64 KiB；
- 图片只能通过 HTTP 上传；
- 核心内容使用类型化字段：`text_content`、`media_id`；
- 扩展信息才允许进入 `metadata JSONB`。

## 11. 实时协议

### 11.1 JSON 信封

    {
      "version": 1,
      "operation": "message.send",
      "requestId": "uuid-v4",
      "body": {}
    }

同步事件额外包含 `syncSeq`。客户端不能提交可信 `senderId`；服务端从认证会话取得用户和设备身份，并对每条业务命令重新鉴权。

### 11.2 心跳与重连

- 每 30 秒发送 WebSocket Ping；
- 60 秒没有 Pong 才断开；
- 重连退避：1、2、4、8……最多 30 秒，并加入随机抖动；
- 连接成功后重置退避；
- 认证失败或设备撤销时停止无限重试；
- Access Token 到期前约 60 秒进行单航班刷新，成功后重建 WSS。

### 11.3 稳定错误码

- AUTH_INVALID；
- TOKEN_EXPIRED；
- DEVICE_REPLACEMENT_REQUIRED；
- NOT_CONTACT；
- NOT_MEMBER；
- FORBIDDEN_ROLE；
- IDEMPOTENCY_CONFLICT；
- RECALL_WINDOW_EXPIRED；
- SYNC_RESET_REQUIRED；
- MEDIA_EXPIRED；
- AI_BUSY；
- AI_BUDGET_EXCEEDED；
- CONTEXT_CHANGED；
- RATE_LIMITED。

REST 用 OpenAPI 描述，WSS 和 AI 输出用版本化 JSON Schema 描述。

## 12. 可靠消息事务

### 12.1 接受消息

消息 ACK 的唯一含义是“服务端事务已经成功持久化消息及必要同步记录”。

一个 PostgreSQL 事务完成：

1. 校验登录、联系人或群成员权限；
2. 处理 `UNIQUE(sender_id, client_msg_id)` 幂等；
3. 锁定会话；
4. 递增 `conversation.last_seq RETURNING`；
5. 写入类型化消息或绑定媒体；
6. 为相关用户分配 `syncSeq`；
7. 写入 `user_sync_events`；
8. 为目标活动设备写入 outbox；
9. 提交；
10. 提交后返回 ACK。

网络推送不得发生在数据库事务内。

### 12.2 Outbox

outbox 至少保存：

- event_type；
- entity_id；
- target_device_id；
- status；
- attempt_count；
- next_attempt_at；
- completed_at。

outbox 与同步事件不复制消息正文，只保存实体引用。Dispatcher 在事务提交后读取实体当前状态，尝试 WSS；不存在前台 WSS 或写入失败时，对 MOBILE 发送通用 FCM。

### 12.3 群扇出

100 人以内群消息仍在同一事务中为所有活动成员写同步事件、为其活动设备写 outbox。该方案牺牲大群扩展性，换取演示规模内清晰的一致性。

### 12.4 固定锁顺序

- 设备替换：user → devices → auth_sessions → refresh_tokens；
- 消息：conversation → message/media → user_sync_counters（按 userId 排序）；
- 头像：user → media → peer user_sync_counters（按 userId 排序）。

任何事务不得反向加锁。

## 13. 同步协议

### 13.1 数据结构

- `user_sync_counters(user_id, last_seq)`：给用户事件分配序号；
- `user_sync_events(user_id, sync_seq, event_type, entity_id, created_at)`；
- `device_sync_states(device_id, last_acked_seq, updated_at)`。

`conversation_read_states` 按用户维护 C2C 的 `readSeq`，查询返回双方进度。群聊的 `readSeq` 保存在当前 `conversation_members` 成员生命周期记录中，重新入群时归零；群聊查询与 `CONVERSATION_READ` 同步只返回/投递当前用户，供其设备恢复未读边界，不暴露群成员已读列表。

手机推进游标不会替 PC 确认消费。

### 13.2 高水位屏障

1. WSS 认证后返回当前高水位 B；
2. 客户端进入 SYNCING，同时缓冲实时事件；
3. 分页请求 `/sync?after=L&until=B`；
4. 每页在本地数据库事务中应用；
5. 同步到 B 后，按 `syncSeq` 应用缓冲中大于 B 的事件；
6. 忽略重复或小于等于已应用游标的事件；
7. 发送 `sync.ack`，进入 LIVE。

同步事件保留 30 天。游标过旧返回 `SYNC_RESET_REQUIRED`，客户端通过分页会话和权威消息历史全量恢复。

## 14. 文本消息流程

### 14.1 Android

1. 生成 `clientMsgId`；
2. Room 插入本地消息，状态 `SENDING`；
3. 在线时立即发 WSS；离线时写入 `pending_commands`；
4. 服务端事务持久化并 ACK；
5. 客户端以服务端 ID 和序号 upsert；
6. 状态更新为 `SENT`；
7. ACK 超时使用相同 `clientMsgId` 重试；
8. 正常退出时未接受命令转为 `FAILED`，未来登录不得自动发送。

### 14.2 PC

PC 只能在线发送：先写本地临时状态，收到 ACK 后固化。断网时发送按钮不可用，不建立持久离线发送队列。

## 15. 图片与头像

### 15.1 消息图片

1. 客户端选择图片并预压缩；
2. 写入本地 AES-GCM 加密待上传区；
3. 生成 `uploadId` 和 SHA-256；
4. HTTP multipart 上传；
5. 服务端校验真实格式、大小和尺寸；
6. 重新解码、移除 EXIF/GPS；
7. 生成规范化原图和 320px 缩略图；
8. 写入 `TEMP` 媒体；
9. 客户端发送 IMAGE 消息引用 `mediaId`；
10. 消息事务将媒体变为 `BOUND`；
11. 24 小时未绑定的 TEMP 媒体自动过期。

图片最长边 2048px，规范化后不超过 5MB。不做跨用户内容哈希去重。

### 15.2 下载

客户端通过后端媒体接口下载，后端每次校验联系人历史或当前群成员权限，再代理 MinIO 内容。媒体撤回、管理移除或过期后立即返回 `410 Gone`。

### 15.3 头像

用户和群头像使用独立 `AVATAR` 用途，不复用聊天图片。客户端提交裁剪区域，服务端生成：

- 512×512 WebP；
- 96×96 WebP。

用户头像只对本人和存在 C2C 会话的用户可见。头像变更递增 `avatar_version` 并产生资料更新同步事件；旧头像进入 `EXPIRED`。

## 16. 撤回与群管理移除

### 16.1 发送者撤回

- 仅发送者；
- 服务端接受后 60 秒内；
- 以服务端时间为准；
- 已读不影响资格；
- 重复撤回幂等。

撤回保留 `messageId`、`conversationSeq`、发送者和 `recalled_at`，清除文本、搜索内容和媒体引用，状态变为 `RECALLED`。

### 16.2 管理移除

群主或管理员可以随时移除群消息。状态变为 `MODERATED`，保留操作人、原因和时间，清除实际内容。客户端删除正文、搜索索引和媒体缓存。

## 17. 本地数据库

### 17.1 Android

每个账号、每台设备独立 Room + SQLCipher 数据库。

核心表：

| 表 | 作用 |
|---|---|
| messages | 消息实体、服务端与客户端 ID、序号、内容和本地状态 |
| conversations | 会话摘要、最后序号、readSeq 和未读缓存 |
| message_search_fts | 英文词项与中文二元词组 |
| pending_commands | 离线命令权威队列 |
| media_cache | 加密媒体路径、状态和 LRU 信息 |
| sync_state | 当前 deviceId、lastSyncSeq 和全量同步时间 |
| ai_artifacts | 当前用户私人 AI 结果本地副本 |
| ai_action_items | 私人待办本地副本 |

关键索引：

- `UNIQUE(message_id)`；
- `UNIQUE(sender_id, client_msg_id)`；
- `UNIQUE(conversation_id, conversation_seq)`；
- `INDEX(conversation_id, conversation_seq DESC)`；
- `INDEX(send_state, next_attempt_at)`；
- `INDEX(media_state, last_accessed_at)`。

Android 搜索使用 Room FTS4。英文使用规范化词项，中文使用二元词组，命中后以原文校验；单汉字降级为加密库内 `LIKE`。

### 17.2 macOS

每个账号一个 H2 AES 文件数据库，密钥保存在 Keychain。逻辑实体与 Android 对齐，搜索表使用：

    message_search_terms(
      term,
      message_local_id
    )

并建立 `INDEX(term, message_local_id)`。PC 支持离线浏览和搜索，但不支持离线发送。
PC 还保存 `local_ai_jobs`、`local_ai_artifacts` 和 `local_ai_action_items`，并通过 owner
私有同步事件与 `GET /ai/jobs` 权威列表在增量同步和同步重置后对齐；过期任务和结果在读取时清理。

### 17.3 数据生命周期

- 正常退出：删除登录令牌，保留加密数据库、密钥包装和媒体缓存；
- 明确清除或设备失信：删除令牌、数据库密钥、数据库和缓存；
- 数据丢失：从服务端全量恢复；
- 撤回：数据库事务中先清正文与搜索索引，事务后删除加密文件。

## 18. 服务端数据模型

### 18.1 身份与认证

| 表 | 关键字段/约束 |
|---|---|
| public_identifiers | public_no PK、entity_type、entity_id、retired_at |
| users | id、account_no、display_name、password_hash、password_must_change、temporary_password_used、avatar_media_id、status |
| devices | user_id、device_class、installation_id_hash、trust_state、push_token_ciphertext |
| auth_sessions | device_id、access_token_hash UNIQUE、expires_at、status |
| refresh_tokens | session_id、family_id、token_hash UNIQUE、parent_id、state、expires_at |
| login_challenges | challenge_hash、replaced_device_id、新安装身份、purpose、expires_at、used_at |

`devices` 使用活动设备类别部分唯一索引。

### 18.2 社交与群

| 表 | 关键字段/约束 |
|---|---|
| contact_requests | requester、recipient、status、verification、expires_at |
| contacts | user_low_id、user_high_id、status；用户对唯一 |
| user_blocks | blocker_id、blocked_id；有方向 |
| conversations | id、type、status、last_seq |
| c2c_conversations | conversation_id、user_low_id、user_high_id；用户对唯一 |
| groups | conversation_id、group_no、name、description、visibility、owner_user_id、status |
| conversation_members | conversation_id、user_id、role、status、history_visible_after_seq、read_seq、membership_version |
| group_membership_audit | 角色与成员生命周期审计 |
| group_invites | token_hash、expires_at、max_uses、use_count、status |
| group_join_requests | group_id、user_id、status、created_at |
| group_bans | group_id、user_id、actor_id、reason |

公开群名称和简介使用 `pg_trgm` GIN 索引。待处理入群申请、联系人申请使用部分唯一索引防止重复。

### 18.3 消息、媒体与同步

| 表 | 关键字段/约束 |
|---|---|
| messages | message_id、conversation_id、conversation_seq、sender_id、client_msg_id、type、state、text_content、media_id、server_accepted_at、recalled_at |
| media | media_id、purpose、uploader_id、upload_id、state、object keys、尺寸、哈希、attached_entity_id |
| user_sync_counters | user_id、last_seq |
| user_sync_events | user_id、sync_seq、event_type、entity_id、created_at |
| device_sync_states | device_id、last_acked_seq、updated_at |
| outbox | event_type、entity_id、target_device_id、状态与重试字段 |

关键约束：

- `UNIQUE(sender_id, client_msg_id)`；
- `UNIQUE(conversation_id, conversation_seq)`；
- `UNIQUE(uploader_id, upload_id)`；
- 每个 MESSAGE_IMAGE media 只能绑定一条消息；
- `INDEX(messages(conversation_id, conversation_seq DESC))`；
- `INDEX(media(state, created_at))`；
- outbox 待处理部分索引。

### 18.4 AI 与运营

| 表 | 作用 |
|---|---|
| conversation_ai_settings | 会话 AI 开关、策略版本 |
| conversation_ai_consents | C2C 双方同意 |
| ai_jobs | 私人任务状态、上下文、模型、版本和错误 |
| ai_artifacts | 总结、回复建议、关键信息 |
| ai_action_items | 私人待办 |
| ai_cache_entries | 内容绑定缓存 |
| ai_daily_budgets | 每用户每日预算、预留与已用 |
| ai_usage_records | 不含消息内容的调用用量 |
| audit_logs | 安全审计，不含消息和 AI 内容 |
| abuse_reports | 用户、群和消息举报，保存目标引用、原因代码、状态和时间，不保存正文或媒体地址 |

## 19. 私人 AI 助手

### 19.1 架构

AI 业务由项目自己实现，Spring AI 2.0 只作为模型适配层：

    ConversationSummaryService
    SmartReplyService
    InformationExtractionService
    AiConsentService
    AiJobService
              │
          AiProvider
              │
    SpringAiOpenAiProvider

通用基线为 OpenAI-compatible Chat Completions。能力通过 `supportsVision`、`supportsNativeJsonSchema`、`maxInputTokens`、`supportsStreaming` 描述。首版不使用框架 Chat Memory，不启用 Agent 或工具。

### 19.2 权限

- AI 是请求者的私人助手，不是会话成员；
- C2C 必须双方开启；
- 群聊由群主统一控制；
- 群 AI 开启状态对所有成员持续可见；
- 转让群主自动关闭 AI、递增策略版本并取消任务；
- AI 结果必须绑定 `owner_user_id`；
- `requesting_device_id` 只用于审计，不能决定结果访问权。

### 19.3 上下文

- 总结默认按会话序号倒序读取最多 100 条未读消息，也可选择范围或全部未读；
- 智能回复读取会话末尾最多 20 条有效文本；
- 待办/关键信息最多读取选定 200 条；
- 排除 RECALLED 和 MODERATED；
- 图片由服务端开关和模型视觉能力共同控制；
- 每任务最多 4 张，最长边缩到 1024px；
- 不发送 EXIF、原始对象地址或失效媒体；
- 关闭视觉时以“[图片]”占位。

任务保存序号范围、`message_content_digest`、`membership_version` 和 `ai_policy_version`。调用模型前和保存结果前各校验一次，变化时以 `CONTEXT_CHANGED` 结束并丢弃结果。

### 19.4 任务状态

- QUEUED；
- RUNNING；
- SUCCEEDED；
- FAILED；
- CANCELLED；
- EXPIRED。

同一用户最多一个 RUNNING。智能回复繁忙时立即返回 AI_BUSY；总结和提取最多排队三个。429、5xx、超时最多自动重试一次，结构化结果最多修复一次。

首版不流式转发模型 Token，只同步：

- AI_JOB_QUEUED；
- AI_JOB_STARTED；
- AI_JOB_COMPLETED；
- AI_JOB_FAILED；
- AI_ARTIFACT_DELETED；
- AI_JOB_DELETED。

### 19.5 输出协议

SUMMARY：

    overview
    key_points[]
    decisions[]
    open_questions[]
    source_message_ids[]

SMART_REPLY：

    replies[{ text, tone }]  // 固定 3 条可编辑草稿

EXTRACTION：

    action_items[{
      title,
      details,
      assignee_user_id?,
      due_at?,
      priority,
      confidence,
      source_message_ids[]
    }]
    key_facts[{
      category,
      content,
      confidence,
      source_message_ids[]
    }]

服务端按 JSON Schema 校验。消息 ID 必须属于本次上下文。识别其他成员不会自动给对方创建任务。

### 19.6 预算与缓存

`ai_daily_budgets` 以 Asia/Shanghai 自然日计算：

- limit_tokens；
- reserved_tokens；
- used_tokens；
- version。

任务开始前原子预留最大预算，完成后按真实用量结算，失败或取消释放剩余预留。

缓存键至少包括：

- owner_user_id；
- 功能类型；
- 消息范围；
- provider/model；
- prompt_version；
- 图片开关；
- message_content_digest。

摘要覆盖有序消息 ID、当前状态、内容版本和可选媒体哈希，避免撤回后复用旧结果或跨用户越权命中。

### 19.7 保留与删除

- 回复建议默认 10 分钟；
- 总结默认 30 天；
- 待办保留至完成或用户删除；
- 用户删除时硬删除内容并同步所有设备；
- 账号注销自动取消任务并清理所有私人 AI 数据；
- 只保留无法关联用户的聚合用量。

## 20. FCM 与通知

通知类型：

- NEW_MESSAGE；
- CONTACT_REQUEST；
- GROUP_JOIN_REQUEST；
- GROUP_INVITE；
- AI_JOB_COMPLETED；
- SECURITY_ALERT；
- PROFILE_CHANGED（用户或群资料，包括头像，发生变化后提示客户端通过认证同步补拉）。

FCM 只携带事件类型和不敏感同步提示。存在可用前台 WSS 时只发 WSS；没有连接或写入失败时才发送 FCM。FCM Token 轮换通过认证接口更新，永久无效只清除推送 Token，不撤销设备。

## 21. 安全设计

### 21.1 明确边界

首版使用 HTTPS/WSS，但不实现 E2EE。服务端可以读取文本与图片。未来若引入 E2EE，必须重新设计多设备密钥、群密钥、搜索、AI 和媒体访问，不能在现有方案上添加“简化加密”。

### 21.2 关键控制

- Argon2id 密码哈希；
- 不透明短期 Access Token 和轮换 Refresh Token；
- 每条 WSS 命令重新鉴权；
- 群成员和媒体访问由服务端判断；
- 文件真实类型、大小、尺寸和解码校验；
- 消息帧、文本、上下文和图片上限；
- 登录、搜索、入群、AI 按用户/IP限速；
- 数据库与媒体不向公网暴露；
- FCM、模型、数据库和 MinIO 凭证不进入 Git；
- 日志禁止记录密码、Token、正文、AI 输入输出和媒体地址。

### 21.3 失信与擦除

同类设备替换、远程移除、Refresh Token 重放和管理端撤销会使设备失信。服务端立即拒绝访问；合作客户端在下次收到信号或访问服务端时执行密码学擦除。已经被攻击者完全控制的设备无法保证远程删除，这是方案的真实安全边界。

## 22. 账号注销

用户必须先转让或解散其拥有的群。注销时：

1. 取消 QUEUED/RUNNING AI 任务；
2. 取消设备替换 challenge；
3. 撤销设备、会话和令牌；
4. 清除头像、联系人、申请、群成员关系和私人 AI 数据；
5. 退役账号；
6. 要求客户端删除本地密钥、数据库和缓存；
7. 将用户行置为 DELETED 占位。

其他参与者已经合法获得的历史消息继续存在，发送者显示“已注销用户”，不能反查个人资料或恢复账号。群解散后相关内容按 30 天规则删除。

## 23. 部署

    Internet
       │
    443/TCP
       │
     Caddy
       │ internal Docker network
       ├─ Spring Boot
       ├─ PostgreSQL
       └─ MinIO

只有 Caddy 暴露公网端口。Caddy 负责 HTTPS、WSS 和证书自动续期。PostgreSQL、MinIO 只存在于 Docker 内部网络。

2GB 机器的初始资源约束建议：

- Spring Boot JVM：`-Xms256m -Xmx640m`；
- Hikari 最大连接数：约 16，按压测调整；
- PostgreSQL `shared_buffers`：128MB 起步；
- 限制 AI Worker、outbox 和媒体任务并发；
- 配置 1–2GB swap 作为 OOM 缓冲，不把 swap 当正常内存；
- AI 推理全部使用外部服务。

PostgreSQL 与 MinIO 使用独立持久卷、分别备份、加密存储，并定期执行恢复演练。该拓扑接受单机故障。

## 24. 审计、监控与举报

审计事件：

- 登录、密码重置、Token 重放、设备替换；
- 联系人拉黑；
- 群审批、角色变化、群黑名单；
- 消息管理移除；
- 群 AI 开关；
- 账号注销；
- AI 模型、用量、状态和错误码。

运行指标：

- WSS 连接数；
- ACK 延迟；
- outbox 积压与重试；
- FCM 失败率；
- 同步重置次数；
- AI 队列、失败率和 Token 用量；
- PostgreSQL 与 JVM 内存。

`abuse_reports` 支持举报 USER、GROUP、MESSAGE。首版通过受保护的管理员接口或 CLI 查看举报、封禁用户和暂停公开群，不建设完整运营后台。

平台管理员处置使用独立的用户暂停状态和公开群暂停时间戳。暂停用户会撤销其活动凭证，暂停公开群会从群号/名称搜索和公开入群入口隐藏，但不删除已有群历史。举报接口只返回目标引用、原因代码和状态；普通日志与安全审计均不记录消息正文、密码、Token 或媒体地址。

## 25. 测试与验收

### 25.1 服务端

使用 Testcontainers 启动真实 PostgreSQL 与 MinIO，覆盖：

- 同一 clientMsgId 并发重试；
- 同会话并发序号；
- 手机替换和 Refresh Token 重放；
- 交叉联系人申请；
- 群成员上限、审批、角色和群主转让；
- 100 人群扇出；
- 撤回与媒体下载竞争；
- outbox 崩溃恢复；
- AI 预算并发预留；
- AI 上下文变化时丢弃结果。
- 举报目标访问边界、普通用户隔离、管理员处置、暂停公开群搜索以及脱敏审计。

### 25.2 客户端

- 数据库迁移、错误密钥；
- 正常退出保留、失信擦除；
- 中文二元词搜索；
- 同步屏障、乱序和重复事件；
- 撤回/管理移除清正文与索引；
- 图片加密缓存；
- Android 离线发送与退出后禁止自动补发；
- PC 重启增量同步；
- AI 私人结果跨设备同步和删除。

### 25.3 标准演示

预置四个无真实秘密账号，展示：

1. 账号搜索、联系人申请与接受；
2. MOBILE 与 PC 同时在线；
3. C2C 文本、图片、离线通知、补拉和撤回；
4. 创建公开群、群号搜索、二维码申请和审批；
5. 设置管理员、管理移除、成员退出；
6. AI 总结最多 100 条未读、回复草稿、待办提取；
7. 多模态开关；
8. 同类手机替换和旧设备失信清除。

## 26. API 轮廓

### Auth / Device

- `POST /auth/login`
- `POST /auth/register`
- `POST /auth/device-replacement/confirm`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /auth/password/change`
- `DELETE /auth/account`
- `POST /admin/users/{id}/password-reset`
- `DELETE /devices/{id}`

### Users / Contacts

- `GET /users/search?accountNo=`
- `POST /contact-requests`
- `POST /contact-requests/{id}/accept`
- `POST /contact-requests/{id}/reject`
- `DELETE /contacts/{userId}`
- `POST /blocks/{userId}`
- `DELETE /blocks/{userId}`

### Conversations / Messages

- `GET /conversations`
- `GET /conversations/{id}/messages`
- `POST /conversations/{id}/read`
- `POST /messages/{id}/recall`
- `POST /messages/{id}/moderate`
- `GET /sync`

实时发送、ACK 和事件通过版本化 WSS 信封承载。

### Groups

- `POST /groups`
- `GET /groups/search`
- `POST /groups/{id}/join-requests`
- `POST /groups/{id}/join-requests/{requestId}/approve`
- `POST /groups/{id}/invites`
- `POST /groups/{id}/members`
- `DELETE /groups/{id}/members/{userId}`
- `PUT /groups/{id}/members/{userId}/role`
- `GET /groups/{id}/ai-policy`
- `PATCH /groups/{id}/ai-policy`
- `POST /groups/{id}/owner-transfer`
- `DELETE /groups/{id}`

### Media / Profile

- `POST /media/images`
- `GET /media/{id}`
- `PUT /users/me/avatar`
- `DELETE /users/me/avatar`
- `PUT /groups/{id}/avatar`

### AI

- `POST /conversations/{id}/ai/summary`
- `POST /conversations/{id}/ai/smart-replies`
- `POST /conversations/{id}/ai/extract`
- `GET /ai/jobs`
- `GET /ai/jobs/{id}`
- `GET /ai/artifacts`
- `DELETE /ai/artifacts/{id}`
- `PATCH /ai/action-items/{id}`
- `DELETE /ai/action-items/{id}`

## 27. 交付顺序

### 阶段 0：UI/交互设计

- 页面信息架构；
- 登录、联系人、会话、群治理、AI 的关键流程；
- 设计系统和组件规范；
- Android 与 macOS 可点击原型；
- API 与状态需求反馈到本技术方案。

### 阶段 1：服务端与契约

- Flyway schema；
- Auth、Device、Contact、Conversation、Message、Media、Group；
- WSS、sync、outbox；
- OpenAPI 与 JSON Schema；
- Testcontainers 集成测试；
- 管理员和初始化 CLI。

### 阶段 2：Android

- Compose UI；
- Room + SQLCipher；
- FCM、WSS、同步屏障；
- 本地搜索、离线队列、加密媒体。

### 阶段 3：macOS

- Compose Desktop；
- H2 AES、Keychain；
- 本地搜索；
- 多设备同步。

### 阶段 4：AI 与加固

- Spring AI 适配器；
- 私人 AI 任务、预算、缓存；
- 审计、举报、备份；
- 2 核 2GB 压测；
- 端到端演示和测试报告。

## 28. 主要风险

| 风险 | 控制措施 |
|---|---|
| 2GB 内存不足 | 严格容器/JVM/连接池限制，限制 Worker 并发，压测后调参 |
| 100 人同步扇出事务过重 | 群人数硬上限、20 msg/s 目标；超过目标时重新设计异步扇出 |
| FCM 延迟或丢失 | FCM 只提示，权威同步补齐 |
| PC H2 AES 打包复杂 | 在 UI 后、客户端开发前先做最小技术验证 |
| 中文搜索召回不足 | 二元词组 + 原文校验 + 单字 LIKE |
| AI 成本失控 | 原子预算预留、并发上限、上下文限制、内容缓存 |
| AI 使用陈旧或无权内容 | 双重权限校验、策略版本、成员版本、内容摘要 |
| 群公开搜索带来滥用 | 审批、黑名单、限速、举报与管理员暂停 |
| 将来引入 E2EE | 视为新架构，不在现有协议上增量拼接 |

## 29. 最终交付物

- Android APK；
- macOS Compose Desktop 安装包；
- Docker Compose 与 Caddy 配置；
- Flyway migrations；
- OpenAPI 和 WSS/AI JSON Schema；
- 管理员和初始化 CLI；
- Firebase、MinIO、服务端配置模板；
- 自动化测试和测试报告；
- 四个演示账号；
- 本技术方案、领域词汇表、ADR 和演示说明；
- 不包含任何真实 Firebase 凭证、API Key、数据库密码或 Token。
