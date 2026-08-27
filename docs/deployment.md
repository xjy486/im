# 即通单节点生产部署

本文面向第一次部署服务端的开发者和运维人员，覆盖一台云主机上的 Docker Compose 部署。当前版本由 Caddy、Spring Boot、PostgreSQL 和 MinIO 组成，只有 Caddy 接收公网流量。

这套方案适合当前 V1 的单节点目标。它不提供多节点高可用，也不负责云主机、DNS、备份存储和密钥保管服务的采购与配置。

## 1. 部署拓扑

```text
Internet
   │ TCP 80 / 443
   ▼
Caddy
   │ Docker 内部网络
   ├── Spring Boot :8080
   ├── PostgreSQL :5432
   └── MinIO API :9000
```

PostgreSQL 和 MinIO 只使用 Docker 内部网络与持久卷。MinIO Console 的 `9001` 端口不对外发布。

默认资源目标为 2 个 CPU 和 2 GiB 内存。建议为主机配置 1 至 2 GiB swap 作为故障缓冲，并单独关注数据库、媒体对象和备份目录的磁盘增长。

## 2. 上线前条件

部署主机需要满足以下条件。

- 已安装 Docker Engine 和 Docker Compose plugin，`docker compose version` 可以正常执行。
- 公网 DNS 的 A 或 AAAA 记录已经指向主机。
- 云安全组和主机防火墙允许 TCP 80、443 入站。5432、8080、9000、9001 不应开放公网访问。
- 主机至少有 2 个 CPU、2 GiB 内存和足够的持久磁盘。
- 已准备应用发布包，或者已将仓库和 Docker 构建所需文件放到主机。
- 已准备独立的备份密钥保管位置。备份密钥不能放在仓库、Docker volume 或备份目录中。

首次申请 HTTPS 证书时，Caddy 需要从公网访问 80 或 443。DNS 尚未生效、端口被其他服务占用或云安全组未放行时，证书申请会失败。

## 3. 准备部署目录

可以直接使用仓库目录，也可以使用 `scripts/release/build.sh` 生成的发布包。发布包包含 Compose 文件、Caddy 配置、环境变量模板、服务端镜像 tar 和备份脚本。

在发布包目录中请确认下列文件都存在。

```text
compose.yaml
compose.production.yaml
Dockerfile
infra/caddy/Caddyfile.production
.env.example
server/jitong-im-server-<version>.tar
server/image-name.txt
```

校验发布包时执行下面的命令。

```sh
sha256sum -c checksums.sha256
```

macOS 如果使用仓库脚本，应先检查 Docker 运行时。

```sh
./scripts/docker-runtime.sh --check
```

Linux 主机可以直接使用 Docker Engine，仍建议通过仓库的 `docker-runtime.sh` 执行 Docker 命令，以便命令和文档保持一致。

## 4. 创建生产环境文件

生产环境必须使用独立的 `.env`。该文件权限应为 `600`，并且不进入 Git、工单、日志或聊天记录。

复制模板后必须替换所有占位凭证。`dev-up.sh` 只会在 `.env` 不存在时生成本地凭证，直接复制模板不会触发重新生成。

```sh
cp .env.example .env
chmod 600 .env
```

至少需要设置以下变量。

| 变量 | 生产要求 |
|---|---|
| `POSTGRES_PASSWORD` | 使用随机长密码 |
| `MINIO_ROOT_USER` | 使用专用的 MinIO 管理用户 |
| `MINIO_ROOT_PASSWORD` | 使用随机长密码 |
| `MINIO_BUCKET` | 确认媒体 bucket 名称 |
| `ADMIN_API_KEY` | 使用随机长 key，只交给管理员脚本使用 |
| `JITONG_DOMAIN` | 公网域名，例如 `im.example.com` |
| `CADDY_EMAIL` | 接收证书通知的邮箱 |
| `JITONG_SERVER_IMAGE` | 发布包中的镜像名，例如 `jitong-im-server:1.0.0` |

可以用下面的命令生成随机值，然后通过安全的本地编辑器写入 `.env`。

```sh
openssl rand -hex 32
openssl rand -hex 32
openssl rand -hex 48
```

FCM 和 AI 默认关闭。启用它们时，只把生产凭证写入未跟踪的 `.env`，并在发布前确认 provider、额度、超时和图片输入策略已经经过验证。

## 5. 首次部署

如果使用发布包中的镜像 tar，先加载镜像。

```sh
./scripts/docker-runtime.sh docker load \
  --input server/jitong-im-server-1.0.0.tar
```

将 `server/image-name.txt` 中的镜像名写入 `.env` 的 `JITONG_SERVER_IMAGE`。源码目录部署可以保留 `jitong-im-server:local`，由 Compose 在本机完成构建。

先校验生产 Compose 和 Caddy 配置。

```sh
./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml \
  config --quiet

JITONG_DOMAIN="$(sed -n 's/^JITONG_DOMAIN=//p' .env)" \
CADDY_EMAIL="$(sed -n 's/^CADDY_EMAIL=//p' .env)" \
./scripts/docker-runtime.sh docker run --rm \
  -e JITONG_DOMAIN \
  -e CADDY_EMAIL \
  -v "$PWD/infra/caddy:/etc/caddy:ro" \
  caddy:2.10.2-alpine \
  caddy validate --config /etc/caddy/Caddyfile.production
```

启动生产栈。

```sh
./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml \
  up --detach --wait
```

检查服务状态和公网健康接口。

```sh
./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml \
  ps

curl --fail --silent --show-error \
  https://im.example.com/api/v1/system/health
```

把示例域名替换为 `JITONG_DOMAIN` 的实际值。健康接口返回 `status` 为 `UP` 后，再进行客户端登录和媒体上传验收。

## 6. 日常运维

查看状态。

```sh
./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml ps
```

查看服务日志时只保留必要范围，不要把包含 Authorization header、环境变量或消息正文的完整日志复制到工单。

```sh
./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml \
  logs --tail=200 server caddy
```

修改 `.env` 后，已有容器不会自动读取新值。需要使用生产的两份 Compose 文件重新创建服务。

```sh
./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml \
  up --detach --wait server
```

只重启服务端。

```sh
./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml \
  restart server
```

生产环境不要执行 `scripts/dev-up.sh` 或 `scripts/dev-smoke.sh`。这两个入口使用基础 Compose 配置，可能把 Caddy 的生产 HTTPS 配置切回本地 loopback 配置。

## 7. 备份与恢复

先按 [PostgreSQL 与 MinIO 备份恢复演练](backup-restore.md) 创建独立备份密钥，再执行加密备份。

```sh
./scripts/backup/create.sh \
  --env-file .env \
  --key-file /secure/path/jitong-backup.key
```

备份完成后必须运行 `verify.sh`。备份目录和验证证据应复制到独立存储，不能只留在生产主机上。

恢复时使用新的 Compose project 和新的 HTTP 端口。恢复结果经过健康、数量和授权下载检查后，才允许切换公网流量。不要直接覆盖生产 volume。

仓库提供备份和恢复脚本，但没有内置的定时任务、异地存储上传或值班告警配置。生产环境需要由外部调度器和基础设施平台完成这些工作，并定期执行隔离恢复演练。

## 8. 升级

升级前完成以下检查。

1. 确认当前 `.env`、管理员 key 和备份 key 可以取用。
2. 创建并验证 PostgreSQL 和 MinIO 加密备份。
3. 校验新发布包的 `checksums.sha256`。
4. 加载新镜像，确认新镜像名已经写入 `JITONG_SERVER_IMAGE`。

以同一个 Compose project 执行升级。

```sh
./scripts/docker-runtime.sh docker load \
  --input server/jitong-im-server-1.0.0.tar

./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml \
  up --detach --no-build --wait
```

Flyway 会在服务启动时执行迁移。升级后使用公网健康接口确认服务可用，再执行一次生产配置下的 server 重启检查。

```sh
./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml \
  restart server

curl --fail --silent --show-error \
  https://im.example.com/api/v1/system/health
```

数据库迁移已经执行后，不要直接切换回旧应用镜像。先判断迁移是否可逆，必要时使用 [备份恢复文档](backup-restore.md) 在隔离 project 中恢复对应版本的数据和镜像。

## 9. 回滚

应用层回滚优先使用已经加载的旧镜像。

```sh
JITONG_SERVER_IMAGE=jitong-im-server:0.9.0 \
  ./scripts/docker-runtime.sh docker compose \
  --env-file .env \
  -f compose.yaml -f compose.production.yaml \
  up --detach --no-build --wait
```

如果新版本已执行不可逆迁移，先停止继续写入并保留日志、健康检查结果和备份证据。恢复流程必须使用新的 Compose project，完成数据数量和权限抽查后再切流量。

## 10. 客户端上线检查

Android release 构建必须使用稳定的产品签名。服务端域名、APK 的 API base URL 和群邀请链接域名需要保持一致。

当前 Android 构建支持通过 `jitongBaseUrl`、`jitongInviteHost` 和 `jitongInviteScheme` 配置这三个值。使用自定义域名时，按 [Android 客户端说明](../android-app/README.md) 直接执行 Gradle 构建并传入完整参数，再进行 clean-install 和群邀请链接验收。

macOS 客户端启动时通过 `JITONG_SERVER_URL` 指向公网 HTTPS 地址。两端都需要至少验证注册或登录、文本消息、图片上传下载、断线重连和客户端重启后的历史同步。

## 11. 部署边界

当前部署方案接受单机故障，适用规模和资源目标见 [系统技术设计](design/im-system-technical-design.md) 与 [单节点部署 ADR](adr/0018-deploy-a-single-node-docker-compose-stack.md)。

以下能力不属于当前文档范围。

- Kubernetes 或多节点调度
- PostgreSQL 和 MinIO 的高可用集群
- 云厂商托管数据库和对象存储的专用接入
- 完整的日志平台、指标平台和告警规则
- 自动化发布流水线和自动回滚
