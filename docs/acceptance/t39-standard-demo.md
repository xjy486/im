# T39 标准演示与验收证据

这份文档是 Issue #40 的验收入口。它把四个演示账号的可重复服务端演示、
自动化契约测试、客户端测试、恢复演练和人工验收步骤连在一起，避免只凭
一次手工演示口头确认。

## 1. 四个演示账号

`scripts/release/init-demo.sh` 创建四个临时账号，顺序固定为：

1. Alice
2. Bob
3. Carol
4. Dave

账号文件包含密码，权限固定为 `600`，已被 Git 忽略。不要把它复制到 issue、
日志、发布包或测试报告。

## 2. 可重复服务端标准演示

先使用仓库规定的环境和 Compose wrapper：

```sh
./scripts/dev-env.sh --check
./scripts/dev-up.sh
```

从本机 `.env` 安全读取管理员 key，并运行标准演示：

```sh
ADMIN_API_KEY="$(sed -n 's/^ADMIN_API_KEY=//p' .env)"
./scripts/acceptance/standard-demo.sh \
  --base-url "http://127.0.0.1:$(sed -n 's/^JITONG_HTTP_PORT=//p' .env)" \
  --admin-api-key "$ADMIN_API_KEY"
```

脚本会在临时账号上验证：

- 健康检查、四账号登录，以及同一用户 `MOBILE` / `PC` 同时有效；
- 完整账号精确搜索、联系人申请和接受、C2C 文本、跨设备历史及 durable sync 补拉；
- 图片上传、C2C 图片、对端下载、图片撤回后的媒体失效、用户头像及版本化头像下载；
- 一分钟撤回和撤回墓碑；
- 公开群搜索、群号、二维码 deep link、入群申请、审批；
- 设置管理员、普通成员主动退群、由管理员管理移除普通成员、群解散和群号隐藏；
- 同类手机替换、一次性 challenge、旧设备失信和新设备验证。

脚本只在报告中记录检查项和 HTTP 状态，不记录密码、Token、推送 Token 或
消息正文。默认报告写到 `acceptance-evidence/`，该目录已被 Git 忽略。

## 3. 自动化证据矩阵

| #40 验收项 | 自动化入口 | 结果判定 |
|---|---|---|
| 账号搜索、联系人申请、MOBILE/PC 同时在线、C2C 文本 | `standard-demo.sh`；`ContactContractTest`；`MessageContractTest` | HTTP/API 契约和跨设备历史通过 |
| 离线 FCM、补拉 | `FcmFallbackContractTest`；`MessageContractTest` 的 durable sync 用例 | FCM 只携带无内容提示，权威消息从同步流补拉 |
| 图片、头像、撤回、本地搜索 | `standard-demo.sh`；`MediaContractTest`；`AvatarContractTest`；Android/PC local search tests | 媒体权限、版本失效、撤回索引清理和中文/英文搜索通过 |
| 公开群搜索、二维码申请、审批、角色、管理移除、解散 | `standard-demo.sh`；`GroupContractTest` | 公开入口、治理角色和解散后的访问撤销通过 |
| 私人总结、回复草稿、待办、多模态开关、跨端 AI 同步 | `AiSummaryContractTest`；`GroupAiPolicyContractTest`；Android/PC AI local sync tests | 私人所有权、结构化证据、图片能力开关和删除同步通过 |
| 同类手机替换、旧设备失信清除 | `standard-demo.sh`；`AuthDeviceContractTest`；Android/PC auth store tests | challenge 一次性、旧凭证失效和本地数据擦除通过 |

契约测试中的 AI provider 和 FCM sender 使用受控替身，不需要真实模型 key 或
Firebase key。真实 AI 调试必须遵循 `docs/development-environment.md`，只使用
临时账号和合成消息。

## 4. 功能、并发、恢复和性能测试

服务端完整验证：

```sh
./scripts/dev-env.sh ./mvnw verify
```

重点证据包括：

- 功能：`ContactContractTest`、`MessageContractTest`、`MediaContractTest`、
  `AvatarContractTest`、`GroupContractTest`、`AiSummaryContractTest`；
- 并发：`AuthConcurrencyContractTest`、`AuthDeviceContractTest`、群入群申请
  并发、消息幂等和 AI 预算并发测试；
- 恢复：`MigrationRestartContractTest`、outbox/sync recovery tests、
  `scripts/dev-smoke.sh`、`scripts/backup/restore-smoke.sh`；
- 性能：Android `AccountDatabaseSearchTest` 与 PC
  `LocalDatabaseSearchPerformanceTest` 验证 10 万条本地消息搜索 p95 目标；
  服务端并发和扇出由真实 PostgreSQL Testcontainers 契约测试覆盖。

客户端单独验证：

```sh
./android-app/gradlew testDebugUnitTest
./desktop-app/gradlew test
```

有 Android API 35 模拟器或测试设备时，继续执行发布包 clean-install smoke：

```sh
./scripts/release/android-smoke.sh release-dist/<version>/android/jitong-<version>.apk
```

macOS 安装包验收：

```sh
./scripts/release/macos-smoke.sh release-dist/<version>/macos/jitong-<version>.dmg
```

## 5. 人工演示记录

自动化脚本无法替代客户端 UI 的人工观察。验收人应使用同一份四账号文件，
在 Android 和 macOS 客户端按以下顺序记录截图或屏幕录像文件名：

1. Alice 在 PC 登录，Alice 在 MOBILE 登录；确认两个端都能看到同一联系人和消息。
2. Bob 离线，Alice 发送文本和图片；恢复 Bob 后确认 FCM 提示、同步补拉和图片可见。
3. Alice 撤回消息；两个端确认正文、媒体和本地搜索命中均消失，只保留墓碑。
4. 更新 Alice 头像；联系人端确认头像版本变化和旧版本缓存失效。
5. Carol 创建公开群，Dave 扫描二维码申请，Carol 审批，Bob 成为管理员。
6. Bob 管理移除 Dave，Carol 解散群；所有客户端确认群历史和本地数据访问被撤销。
7. Alice 新手机触发同类替换；旧手机下次请求失败并清除本地密钥、数据库和媒体缓存。
8. 双方开启 C2C AI；展示总结、三条可编辑回复草稿、待办、多模态开关和
   Alice 的 MOBILE/PC 私人 AI 同步。AI 输出不得自动发送到会话。

记录中不得出现密码、Access/Refresh Token、FCM token、AI key、数据库密码或
真实用户消息。

## 6. 父规格追溯

本验收入口对应：

- `#40`：四账号标准演示和验收证据；
- `#39`：发布物、Compose/Caddy、四账号初始化和客户端 clean-install；
- `#1`：`docs/design/im-system-technical-design.md` 第 25 节测试与标准演示；
- `docs/adr/0047-require-concurrency-client-and-demo-acceptance.md`；
- `docs/adr/0048-deliver-installers-infrastructure-contracts-and-test-evidence.md`。

如果任一人工步骤没有截图/录像或自动化结果没有命令输出，不得把对应验收项
标记为完成。
