# 即通 Android 客户端

T07 的 Android 登录与设备替换最小可运行客户端。工程使用 Kotlin、Jetpack Compose、Room + SQLCipher、Android Keystore、Retrofit 和 OkHttp。

## 本地配置

- 默认服务端地址为 Android Emulator 可访问的 `http://10.0.2.2:8080/`。
- 真机或其他服务端地址通过 Gradle 属性覆盖：

```sh
./gradlew assembleDebug -PjitongBaseUrl=http://192.168.1.10:8080/
```

如果使用本机 SSH 端口转发，建议把远端 PostgreSQL/MinIO 转发到本机
`127.0.0.1:5432` 和 `127.0.0.1:9000`，并把 Spring Boot 暴露在
`0.0.0.0:8080`；Android Emulator 连接宿主机后端时仍使用
`http://10.0.2.2:8080/`。真机则使用宿主机局域网 IP。

- debug 构建允许明文 HTTP 连接，release 构建只允许 HTTPS。

## 认证与本地数据约定

- 每次安装生成一个随机 `installationId`，只作为服务端设备绑定输入。
- 登录、确认替换和 Refresh Token 请求不经过带认证的 OkHttp 客户端，避免认证循环。
- Access Token 失效时最多执行一次 Refresh；明确收到服务端 401、设备失信或再次收到 401 时，客户端停止重试并删除当前账号的 SQLCipher 密钥、数据库、媒体缓存和令牌。网络异常或 5xx 不会误删本地数据，也不会被误判为设备失信。
- 正常退出只清理令牌，保留该账号的加密数据库、密钥包装结果和媒体目录。
- 每个账号使用 SHA-256 派生的文件名、独立随机 SQLCipher passphrase 和独立 Room 数据库实例。
- 账号密钥由 Android Keystore 中不可导出的 AES-GCM 密钥包装；Keystore key 不进入备份。

## 验证

Android 构建需要 JDK 17+、Android SDK 35 和 Gradle Wrapper。由于当前开发环境没有 Android SDK，本地只能完成源码审查；Docker 不可用时，根目录服务端 Testcontainers 契约测试会被自动跳过。
