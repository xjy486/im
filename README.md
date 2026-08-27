# 即通服务端

当前仓库包含 T01 的可运行服务端与契约测试 seam：Spring Boot、Flyway、PostgreSQL、私有 MinIO、Caddy、统一错误响应和最小运行指标。

首次参与开发请先阅读 [开发环境与启动约定](docs/development-environment.md)，并运行：

```sh
./scripts/dev-env.sh --check
```

生产部署入口是 [单节点生产部署](docs/deployment.md)。发布构建、升级和回滚见
[发布说明](docs/release.md)，备份与恢复见
[PostgreSQL 与 MinIO 备份恢复演练](docs/backup-restore.md)。

## 本地启动

需要 Colima、OpenSSL 和 curl。macOS 本地脚本会在 Colima 未运行时自动启动它，
并使用 Colima Docker socket。首次启动会生成仅本机使用、已被 Git 忽略的 `.env`：

```sh
./scripts/dev-up.sh
```

开发栈只通过 Caddy 绑定 loopback 地址 `http://127.0.0.1:8080`。PostgreSQL 和 MinIO 没有宿主机端口映射。健康检查：

```sh
curl http://127.0.0.1:8080/api/v1/system/health
```

验证空库迁移后的服务重启：

```sh
./scripts/dev-smoke.sh
```

本地 Caddy 使用 loopback HTTP，便于干净环境直接启动。公网部署使用自动 HTTPS 配置，
完整前置条件和运维步骤见 [单节点生产部署](docs/deployment.md)。

```sh
JITONG_DOMAIN=im.example.com \
CADDY_EMAIL=ops@example.com \
./scripts/docker-runtime.sh docker compose \
  -f compose.yaml -f compose.production.yaml --env-file .env up --build -d
```

生产覆盖文件仅公开 Caddy 的 80/443 端口；PostgreSQL、MinIO 和 Spring Boot 仍只在容器网络中可达。
如需直接执行 Compose 命令，先运行：

```sh
./scripts/docker-runtime.sh --check
```

如果 PostgreSQL 和 MinIO 使用 SSH 转发运行在本机 `5432`、`9000`、`9001`，请使用独立的云端联调配置，不要修改 Docker 的 `.env`：

```sh
cp .env.forward.example .env.forward
# 编辑 .env.forward 后：
./scripts/dev-forward.sh
```

云端联调后端健康检查仍使用 `http://127.0.0.1:8080/api/v1/system/health`。

## 测试

需要 JDK 21 和 Docker。macOS 本地验证统一使用 Colima；项目统一使用 Maven
Wrapper。契约测试通过 Testcontainers 启动真实 PostgreSQL 与 MinIO，并从随机
HTTP 端口验证公开行为：

```sh
./scripts/dev-env.sh ./mvnw test
```

协议基线位于 `contracts/`。公开错误响应固定为版本 1，并通过 `X-Request-Id` 关联安全日志；响应、日志、审计记录和指标不记录密码、Token、消息正文或媒体地址。安全事件通过 `SecurityAuditSink` 写入内容无关的 `audit_logs`。
