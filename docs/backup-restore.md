# PostgreSQL 与 MinIO 备份恢复演练

本仓库的部署形态是单机 Docker Compose。备份流程把 PostgreSQL 写模型和
MinIO 私有对象分别导出，再用同一份本机密钥独立加密。备份目录不包含
PostgreSQL 密码、MinIO 凭证、管理员 API key 或加密密钥。

## 工具与安全边界

- 只通过 `scripts/docker-runtime.sh` 访问 Docker，macOS 使用 Colima。
- PostgreSQL 使用 `pg_dump --format=custom`，恢复使用 `pg_restore`。
- MinIO 使用临时的 `minio/mc` 容器镜像镜像同步对象，再打成 tar。
- 数据文件使用 OpenSSL AES-256-CBC、PBKDF2 和 600000 次迭代加密。
- `manifest.env` 只保存版本、数量、对象文件名、哈希和非敏感配置标识。
- `checksums.sha256` 在解密前验证密文和 manifest 未被替换；manifest 和两份
  密文还会使用 backup key-derived HMAC 校验，避免只重写 checksum 就能篡改
  恢复目标或数量证据。
- 密钥必须是仓库外 owner-readable-only 的文件。密钥丢失时，备份无法恢复。

OpenSSL `enc` 不提供 AEAD 标签，因此密文和 manifest 还会使用 backup key
派生的 HMAC 做完整性校验；SHA-256 清单负责快速发现传输损坏。任何备份
文件、manifest、HMAC 或 checksum 被修改，验证和恢复都会在解密前失败。

## 一次性创建密钥

```sh
./scripts/backup/init-key.sh --key-file "$HOME/.config/jitong/backup.key"
chmod 600 "$HOME/.config/jitong/backup.key"
```

将该文件放入独立的密钥保管位置。不要放在仓库、Docker volume、备份目录、
issue、日志或聊天记录中。

## 创建备份

先启动业务栈并准备本机 `.env`：

```sh
./scripts/dev-up.sh
./scripts/backup/create.sh \
  --env-file .env \
  --key-file "$HOME/.config/jitong/backup.key"
```

默认产物位于 `backups/<UTC 时间>-<随机后缀>/`：

```text
manifest.env
manifest.hmac
checksums.sha256
checksums.hmac
postgres.dump.enc
minio-objects.tar.enc
```

脚本只输出备份路径和证据路径，不输出命令行中的凭证。生成失败也会在
`backup-evidence/` 写入 `status=FAIL` 和稳定的失败原因。

## 验证备份

验证包含密文 checksum、manifest 安全字段、OpenSSL 解密和 MinIO tar 结构：

```sh
./scripts/backup/verify.sh \
  --backup-dir backups/<备份目录> \
  --key-file "$HOME/.config/jitong/backup.key"
```

验证成功会写入 `backup-evidence/verify-*.env`。这一步不连接生产数据库或
MinIO，因此可以在异机或离线恢复环境重复执行。

CI 还会运行不依赖 Docker 的 `scripts/backup/self-test.sh`，覆盖密钥权限、
加密解密、checksum、manifest/checksum HMAC 以及篡改失败证据。

## 恢复到隔离 Compose 项目

恢复必须使用目标环境文件。该文件应包含目标 PostgreSQL/MinIO 凭证和
`ADMIN_API_KEY`，但不应被提交：

```sh
./scripts/backup/restore.sh \
  --backup-dir backups/<备份目录> \
  --env-file .env \
  --key-file "$HOME/.config/jitong/backup.key" \
  --target-project jitong-im-restore-20260824 \
  --http-port 18080
```

恢复脚本会：

1. 在一个新的 Compose project 中启动空 PostgreSQL 和 MinIO；
2. 在恢复数据库前校验密文 checksum 并解密到临时目录；
3. 使用 `pg_restore` 恢复用户、消息、媒体引用和其他 Flyway 管理的表；
4. 创建目标 bucket 并把所有 MinIO 对象镜像回去；
5. 启动 Spring Boot 与 Caddy；
6. 验证健康检查和用户、消息、BOUND 媒体数量与 manifest 一致；
7. 写入包含 project、端口、清理状态和 PASS/FAIL 原因的证据。

默认保留恢复后的容器和 volume，便于人工抽查。确定不再需要时：

```sh
./scripts/docker-runtime.sh docker compose \
  --project-name jitong-im-restore-20260824 \
  --env-file .env \
  -f compose.yaml \
  down --volumes --remove-orphans
```

也可以在恢复命令上追加 `--cleanup`，让脚本在写出证据后自动清理。
`--replace` 只适用于明确指定的恢复 project，会先删除它的容器和 volume。

## 可重复的授权下载演练

以下命令在运行栈中创建三个临时用户、一条带图片的 C2C 消息，然后完整地
执行备份、离线验证、隔离恢复和权限检查：

```sh
./scripts/backup/restore-smoke.sh \
  --env-file .env \
  --key-file "$HOME/.config/jitong/backup.key"
```

演练的成功证据必须同时包含：

```text
authorized_uploader_download=PASS
authorized_peer_download=PASS
unauthorized_download=403
```

这不是把访问 token 写入备份。演练使用数据库中恢复的、仍在有效期内的
临时会话，仅用于在恢复后的服务端通过原有授权边界验证媒体下载。

## 灾难恢复检查清单

1. 取出备份目录和仓库版本。
2. 从独立密钥保管位置取出 backup key；不要把它复制进仓库。
3. 准备新的目标 `.env`，至少包含 PostgreSQL、MinIO 和 admin 凭证。
4. 运行 `verify.sh`，记录 PASS 证据。
5. 在新的 project 和新 HTTP 端口运行 `restore.sh`。
6. 检查恢复证据中的数量、健康检查和 project。
7. 随机抽取恢复后的图片，分别用已授权用户和未授权用户下载。
8. 记录 `restore-smoke.sh` 或人工抽查结果。
9. 业务确认后才切换流量；不要直接覆盖生产 volume。

## Git 安全

`.env`、`.env.*`、`secrets/`、`backups/` 和 `backup-evidence/` 都不应进入
Git。提交前执行：

```sh
git status --short
git grep -n -I -E \
  'POSTGRES_PASSWORD=|MINIO_ROOT_PASSWORD=|MINIO_SECRET_KEY=|ADMIN_API_KEY=' \
  -- ':!docs/backup-restore.md' ':!*.example' || true
```
