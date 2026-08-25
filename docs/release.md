# 即通发布说明

当前发布版本通过 `scripts/release/build.sh` 生成。生成目录默认是
`release-dist/<version>/`，该目录包含以下交付物：

- `android/jitong-<version>.apk`
- `macos/jitong-<version>.dmg`
- `server/jitong-server-<version>.jar`
- 可选的 `server/jitong-im-server-<version>.tar` 容器镜像
- `compose.yaml`、`compose.production.yaml` 和 `infra/caddy/`
- `.env.example`、Firebase 配置模板、备份脚本和本说明
- `manifest.env` 与 `checksums.sha256`

`release-dist/` 只保存本机构建产物，不提交到 Git。发布包不包含 Firebase
凭证、AI API Key、数据库密码、MinIO 密码、管理员 API Key 或 Token。

## 构建

先检查固定开发环境：

```sh
./scripts/dev-env.sh --check
./scripts/release/self-test.sh
```

在 macOS 上构建完整发布包：

```sh
./scripts/release/build.sh --version 1.0.0
```

如果只需要客户端而不想构建 Docker 镜像，可以执行：

```sh
./scripts/release/build.sh \
  --version 1.0.0 \
  --skip-docker-image
```

Android release APK 未配置正式密钥时会使用随机临时签名，只适合干净设备安装验收。
正式 Android 发布必须使用不会轮换的产品签名密钥，通过构建脚本从本机环境传入：

```sh
export JITONG_ANDROID_KEYSTORE="$HOME/.config/jitong/android-release.keystore"
export JITONG_ANDROID_STORE_PASSWORD='read-from-your-local-secret-store'
export JITONG_ANDROID_KEY_ALIAS='jitong-release'
export JITONG_ANDROID_KEY_PASSWORD='read-from-your-local-secret-store'
./scripts/release/build.sh --version 1.0.0
```

未设置这些变量时，脚本会生成随机临时密钥；密钥不会复制进发布包。

真实 Firebase 配置只通过本机 Gradle 属性传入。可以从
`config/firebase.properties.example` 复制一份未跟踪的本地文件，再把其中的值
映射为 `firebaseApplicationId`、`firebaseApiKey`、`firebaseGcmSenderId`、
`firebaseProjectId` 和 `firebaseStorageBucket` 属性。

## 安装与验收

### Android

连接干净的测试设备或启动 Android Emulator 后，脚本会先卸载旧包、清除数据，
再安装并启动主 Activity：

```sh
./scripts/release/android-smoke.sh \
  release-dist/1.0.0/android/jitong-1.0.0.apk
```

脚本验证 `com.jitong.im.android` 进程在启动后仍然存活。登录所需服务端地址由
APK 构建时的 `jitongBaseUrl` 决定，Firebase 未配置时仍可使用 HTTP/WSS 和离线
队列能力。

### macOS

在目标 Mac 上执行：

```sh
./scripts/release/macos-smoke.sh \
  release-dist/1.0.0/macos/jitong-1.0.0.dmg
```

脚本会挂载 DMG、复制 `.app` 到临时 Applications 目录、启动应用并验证其捆绑的
JVM 进程存活。正式安装时可以将 `.app` 拖入 `/Applications`。运行时通过
`JITONG_SERVER_URL` 指向公网 HTTPS 地址：

```sh
JITONG_SERVER_URL=https://im.example.com \
  open -a /Applications/Jitong.app
```

### Docker Compose 与 Caddy

复制模板并生成本地凭证：

```sh
cp .env.example .env
./scripts/dev-up.sh
```

本机资源上限由 Compose 明确声明为总计不超过 2 CPU 和 2 GiB 内存，PostgreSQL
与 MinIO 没有宿主机端口暴露。发布前可以运行完整启动、四演示账号初始化和重启
验收：

```sh
./scripts/release/compose-smoke.sh
```

公网单节点部署使用生产覆盖：

```sh
JITONG_DOMAIN=im.example.com \
CADDY_EMAIL=ops@example.com \
./scripts/docker-runtime.sh docker compose \
  -f compose.yaml -f compose.production.yaml \
  --env-file .env up --detach --wait
```

只有 Caddy 暴露 80/443，Spring Boot、PostgreSQL 和 MinIO 仍留在内部网络。

## 初始化四个演示账号

管理员 API Key 只从本机环境或命令行安全注入。脚本默认生成随机演示密码并将
账号和密码写入权限为 600 的未跟踪文件：

```sh
./scripts/release/init-demo.sh \
  --base-url http://127.0.0.1:8080 \
  --admin-api-key "$ADMIN_API_KEY" \
  --output demo-accounts.json
```

也可以明确指定只用于演示环境的共享密码：

```sh
./scripts/release/init-demo.sh \
  --admin-api-key "$ADMIN_API_KEY" \
  --password 'local-demo-password'
```

生成的四个用户显示名为 `Jitong Demo Alice`、`Bob`、`Carol` 和 `Dave`。不要把
`demo-accounts.json` 复制到 issue、日志或发布包。

服务端 API 标准演示可以在本地 Compose 栈启动后运行。它会创建临时四账号，
执行 T39 的联系人、C2C、媒体、头像、撤回、公开群治理和同类设备替换链路，
并输出不含凭证和消息正文的 Markdown 证据：

```sh
ADMIN_API_KEY="$(sed -n 's/^ADMIN_API_KEY=//p' .env)"
./scripts/acceptance/standard-demo.sh \
  --base-url "http://127.0.0.1:$(sed -n 's/^JITONG_HTTP_PORT=//p' .env)" \
  --admin-api-key "$ADMIN_API_KEY"
```

完整验收矩阵与客户端人工演示步骤见
`docs/acceptance/t39-standard-demo.md`。

## 升级

1. 先确认当前 `.env`、管理员 API Key 和备份密钥仍在安全位置。
2. 执行 PostgreSQL/MinIO 加密备份并保留备份证据。
3. 校验新包的 `checksums.sha256`。
4. 构建或加载新版本镜像，然后以同一个 Compose project 执行：

```sh
./scripts/backup/create.sh \
  --env-file .env \
  --key-file "$HOME/.config/jitong/backup.key"

./scripts/docker-runtime.sh docker load \
  --input release-dist/1.0.0/server/jitong-im-server-1.0.0.tar

JITONG_SERVER_IMAGE=jitong-im-server:1.0.0 \
  ./scripts/docker-runtime.sh docker compose \
  -f compose.yaml -f compose.production.yaml \
  --env-file .env up --detach --no-build --wait
```

Flyway migration 在服务启动时执行。升级后运行健康检查和
`./scripts/dev-smoke.sh`，确认迁移和服务重启都成功。

Android 使用稳定的产品签名执行覆盖安装；macOS 用新 DMG 替换旧 `.app`。两端
的服务端权威历史和加密本地副本不会因为普通升级而清除。

## 回滚

回滚前停止继续写入，保留失败版本日志和健康检查结果。应用回滚优先使用已经加载
的旧容器镜像，不要重新构建一个可能已经变化的源码版本：

```sh
JITONG_SERVER_IMAGE=jitong-im-server:<previous-version> \
  ./scripts/docker-runtime.sh docker compose \
  -f compose.yaml -f compose.production.yaml \
  --env-file .env up --detach --no-build --wait
```

如果新版本已经执行了不可逆数据库 migration，先不要强行回退应用。使用
`scripts/backup/verify.sh` 验证最近备份，必要时按
`docs/backup-restore.md` 的隔离恢复流程恢复 PostgreSQL、MinIO 和对应版本的
应用镜像。恢复后再运行 Compose smoke、健康检查和客户端登录验收。

Android 回滚只能安装仍使用同一产品签名且版本号更高或允许降级的维护包；普通用户
设备不应通过卸载来回滚，因为卸载会删除本地加密副本。macOS 回滚为关闭应用后把
旧 `.app` 放回 `/Applications`。

## 凭证与发布包检查

发布包的 `manifest.env` 明确记录 `credentials_included=false`，并且
`checksums.sha256` 不包含 `.env`、Firebase 配置、密钥或生成的演示账号文件。
提交前运行：

```sh
./scripts/release/self-test.sh
git status --short
```
