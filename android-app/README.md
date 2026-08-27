# 即通 Android 客户端

T07 的 Android 登录与设备替换最小可运行客户端。工程使用 Kotlin、Jetpack Compose、Room + SQLCipher、Android Keystore、Retrofit 和 OkHttp。

## 本地配置

- 默认服务端地址为 Android Emulator 可访问的 `http://10.0.2.2:8080/`，本地生成的群邀请链接也使用该地址。
- 真机或其他服务端地址通过 Gradle 属性覆盖：

```sh
./gradlew assembleDebug -PjitongBaseUrl=http://192.168.1.10:8080/
```

如果使用本机 SSH 端口转发，建议把远端 PostgreSQL/MinIO 转发到本机
`127.0.0.1:5432` 和 `127.0.0.1:9000`，并把 Spring Boot 暴露在
`0.0.0.0:8080`；Android Emulator 连接宿主机后端时仍使用
`http://10.0.2.2:8080/`。真机则使用宿主机局域网 IP。

当前联调使用的 AVD 是 `jitong_api35`。完整的复用、重建、启动和排查命令见
[Android Emulator 复用说明](../docs/android-emulator.md)。

- debug 构建允许明文 HTTP 连接，release 构建只允许 HTTPS。
- 生产环境构建需要通过 `-PjitongBaseUrl`、`-PjitongInviteHost` 和 `-PjitongInviteScheme=https` 指向公网服务端；本地 Debug 默认使用 `http://10.0.2.2:8080`。

`scripts/release/build.sh` 当前只提供 `--android-base-url` 参数。使用非默认公网域名时，
请直接执行 Gradle 并同时传入 API 地址和邀请链接参数：

```sh
./gradlew assembleRelease \
  -PjitongBaseUrl=https://im.example.com/ \
  -PjitongInviteHost=im.example.com \
  -PjitongInviteScheme=https
```

如果只修改 API base URL 而没有修改邀请链接 host，服务端生成的群邀请链接可能无法被 APK 接收。

如需启用 FCM，需要向 Gradle 传入 Firebase Android App 配置。配置值只作为
构建时资源进入 APK，不要把真实配置文件或服务端凭证提交到 Git：

```sh
./gradlew assembleDebug \
  -PfirebaseApplicationId=1:1234567890:android:abcdef \
  -PfirebaseApiKey=AIza... \
  -PfirebaseGcmSenderId=1234567890 \
  -PfirebaseProjectId=example-project \
  -PfirebaseStorageBucket=example-project.firebasestorage.app
```

FCM 未配置时客户端仍可构建和使用 WSS/离线队列，但不会注册推送 Token。
Token 首次注册或轮换时如果网络暂不可用，会由 WorkManager 在网络恢复后重试；
正常退出登录会取消该注册任务，重新登录后重新绑定当前 Token。

## 认证与本地数据约定

- 每次安装生成一个随机 `installationId`，只作为服务端设备绑定输入。
- 登录、确认替换和 Refresh Token 请求不经过带认证的 OkHttp 客户端，避免认证循环。
- Access Token 失效时最多执行一次 Refresh；明确收到服务端 401、设备失信或再次收到 401 时，客户端停止重试并删除当前账号的 SQLCipher 密钥、数据库、媒体缓存和令牌。网络异常或 5xx 不会误删本地数据，也不会被误判为设备失信。
- 正常退出只清理令牌，保留该账号的加密数据库、密钥包装结果和媒体目录。
- 每个账号使用 SHA-256 派生的文件名、独立随机 SQLCipher passphrase 和独立 Room 数据库实例。
- 账号密钥由 Android Keystore 中不可导出的 AES-GCM 密钥包装；Keystore key 不进入备份。

## 验证

Android 构建需要 JDK 17+、Android SDK 35 和 Gradle Wrapper。由于当前开发环境没有 Android SDK，本地只能完成源码审查；Docker 不可用时，根目录服务端 Testcontainers 契约测试会被自动跳过。

## Release APK

发布包脚本会使用 `assembleRelease`，并在没有提供正式签名密钥时生成一个只用于
干净设备安装 smoke 的临时密钥。临时签名不能用于覆盖真实用户安装，也不能作为
产品发布密钥。

正式构建通过以下 Gradle 属性提供稳定签名密钥，密码只从本机环境注入：

```sh
cd android-app
./gradlew assembleRelease \
  -PreleaseKeystore="$JITONG_ANDROID_KEYSTORE" \
  -PreleaseStorePassword="$JITONG_ANDROID_STORE_PASSWORD" \
  -PreleaseKeyAlias="$JITONG_ANDROID_KEY_ALIAS" \
  -PreleaseKeyPassword="$JITONG_ANDROID_KEY_PASSWORD"
```
