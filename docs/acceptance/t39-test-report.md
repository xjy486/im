# T39 测试报告

## 结论

截至 **2026-08-25**，服务端契约、服务端构建、四账号标准 API 演示、
Android JVM 单元测试和 macOS 客户端测试均通过。真实 Android 设备上的
发布 APK clean-install smoke 已在 #39 的验收记录中通过；本报告不把客户端
人工观察冒充自动化结果。

## 验证结果

| 类别 | 命令 | 结果 |
|---|---|---|
| 环境 | `./scripts/dev-env.sh --check` | JDK 21、Maven Wrapper 3.9.11、Colima Docker 通过 |
| 功能/并发/服务端恢复 | `./scripts/dev-env.sh ./mvnw verify` | 168 tests，0 failures，0 errors，0 skipped |
| 四账号标准演示 | `./scripts/acceptance/standard-demo.sh` | 通过；覆盖 61 项 API/状态检查 |
| 服务端编译 | `./scripts/dev-env.sh ./mvnw -DskipTests compile` | 通过 |
| Android JVM 单元测试 | `ANDROID_HOME=... ./android-app/gradlew testDebugUnitTest` | 通过 |
| macOS 客户端测试 | `./desktop-app/gradlew test` | 通过 |
| 验收工具自测 | `./scripts/acceptance/self-test.sh` | 通过 |
| 发布工具自测 | `./scripts/release/self-test.sh` | 通过 |

## 覆盖说明

### 功能

`standard-demo.sh` 实际调用运行中的服务端，使用 Alice、Bob、Carol、Dave
四个临时账号验证：

- 完整账号搜索、联系人申请和接受、MOBILE/PC 同时在线、C2C 文本和跨设备历史；
- C2C 图片、私有媒体下载、用户头像和版本化头像缓存失效；
- 撤回墓碑；
- 公开群搜索、群号、二维码 deep link、入群申请、审批、管理员、管理移除和解散；
- 同类 MOBILE 替换、一次性 challenge、旧设备失信和新设备验证。

`MessageContractTest`、`MediaContractTest`、`AvatarContractTest`、
`GroupContractTest`、`AiSummaryContractTest` 和 `FcmFallbackContractTest`
在真实 PostgreSQL/MinIO Testcontainers 上补充内容权限、结构化 AI、FCM
无内容提示和恢复边界。

### 并发

服务端契约套件包含并发账号号生成、联系人/入群幂等、消息 clientMsgId
幂等、会话序号、100 人群成员上限与扇出、设备替换、AI 预算预留和 AI
队列边界测试。`mvn verify` 的 168 个测试全部通过。

### 恢复

- Flyway migration 与服务重启由 `MigrationRestartContractTest` 和
  `scripts/dev-smoke.sh` 覆盖；
- durable outbox、同步高水位和设备游标由同步/消息契约测试覆盖；
- PostgreSQL/MinIO 加密备份恢复演练由 `scripts/backup/restore-smoke.sh`
  覆盖，发布物和该演练已在 #39 记录中通过。

### 性能

- Desktop `LocalDatabaseSearchPerformanceTest` 验证 100,000 条本地消息搜索
  的 p95 目标；
- Android `AccountDatabaseSearchTest` 验证中文二元词和单汉字降级搜索；100,000
  条消息的 Android benchmark 仅在设备堆上限至少 256 MiB 的设备执行，受限
  的 API 35 AVD 不把进程内存不足误报成产品失败；
- 服务端并发与群扇出使用真实 PostgreSQL 运行，结果由契约断言而不是 mock
  数据库得出。

## 非自动化验收

Android FCM 后台通知、客户端加密数据库擦除、二维码扫码、桌面/手机 UI、
AI 私人结果跨端展示以及截图/录像仍需在真实客户端上按
`docs/acceptance/t39-standard-demo.md` 的人工步骤记录。测试报告不得在没有
这些人工证据时把对应项目标记为最终完成。
