# 开发环境与启动约定

本文是“即通”仓库的开发环境权威入口。后续开发、测试和本地启动均按这里执行，不依赖个人 shell 中偶然存在的 Java 或 Maven 配置。

## 1. 固定约定

| 工具 | 项目约定 | 说明 |
|---|---|---|
| 操作系统 | macOS arm64 | 当前主开发环境；CI 使用 Linux |
| JDK | 21 | `pom.xml`、Docker 构建和 CI 均使用 Java 21 |
| Maven | Wrapper 中的 3.9.11 | 只使用 `./mvnw`，不要求安装全局 `mvn` |
| Docker | macOS 本地开发使用 Colima；CI 使用 Linux Docker | `docker compose` 必须可用，Testcontainers 也依赖 Docker daemon |
| 其他命令 | Git、OpenSSL、curl、Python 3、`uuidgen` | macOS 默认通常已提供；真实 AI 调试 runbook 会用 Python 3 解析 JSON、用 `uuidgen` 生成请求 ID |

仓库根目录的 `.java-version` 固定为 `21`。IDE、jenv 或其他版本管理器应读取或遵循该版本。

## 2. macOS 一次性安装

### 2.1 安装 JDK 21

推荐安装与 CI、Docker 镜像一致的 Eclipse Temurin 21：

```sh
brew install --cask temurin@21
```

项目脚本会通过 `/usr/libexec/java_home -v 21` 自动定位 JDK，因此系统默认 `java` 即使仍指向 Java 8，也不会影响通过项目脚本执行的命令。Maven Wrapper 首次运行时会自动下载项目固定的 Maven 版本。

### 2.2 Colima

macOS 本地开发和验证统一使用 Colima。Colima 未运行时，仓库脚本会自动执行
`colima start`，不再切换到 Docker Desktop。

首次安装：

```sh
brew install colima
```

验证：

```sh
colima status
docker context use colima
docker info
```

Testcontainers 在 macOS + Colima 下需要显式使用 Colima socket。由于 Ryuk 容器不能在当前 Colima socket 配置下挂载该 socket，运行 Maven/Testcontainers 测试时固定设置：

```sh
export DOCKER_HOST=unix://${HOME}/.colima/default/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

推荐始终通过仓库脚本执行：

```sh
./scripts/dev-env.sh --check
./scripts/dev-env.sh ./mvnw verify
```

`./scripts/docker-runtime.sh --check` 可以单独查看当前 Docker 运行时和
Testcontainers 配置。仓库脚本会在 Colima 未运行时自动执行 `colima start`。

`TESTCONTAINERS_RYUK_DISABLED=true` 会关闭 Testcontainers 的自动资源回收容器。测试进程被强制终止后，可检查并清理残留容器：

```sh
docker ps -a
docker container prune
```

不要在 macOS 上直接执行裸的 `./mvnw verify`、`docker compose` 或
`docker run` 来替代仓库脚本，因为这可能绕过 Colima socket 配置。

## 3. 每次开始开发

在仓库根目录执行环境检查：

```sh
./scripts/dev-env.sh --check
```

检查成功后有两种使用方式。

启动一个已经设置好 `JAVA_HOME` 和 `PATH` 的交互式开发 shell：

```sh
./scripts/dev-env.sh --shell
```

或者直接在正确环境中执行单条命令：

```sh
./scripts/dev-env.sh ./mvnw verify
```

### 使用 SSH 转发的云端 PostgreSQL / MinIO

当云服务器的 PostgreSQL 和 MinIO 已经通过 SSH 转发固定到本机以下端口时：

| 依赖 | 本机地址 |
|---|---|
| PostgreSQL | `127.0.0.1:5432` |
| MinIO API | `http://127.0.0.1:9000` |
| MinIO Console | `http://127.0.0.1:9001` |

不要把云端用户名、密码或转发配置写死进 `src/main/resources/application.yml`。该文件已经支持环境变量覆盖；使用独立的本地配置文件：

```sh
cp .env.forward.example .env.forward
# 编辑 .env.forward，填入本机转发对应的凭证
./scripts/dev-forward.sh
curl http://127.0.0.1:8080/api/v1/system/health
```

`scripts/dev-forward.sh` 会读取 `.env.forward`、必要时构建最新 jar、启动本地 Spring Boot，并等待健康检查成功。它不启动 Docker。

`.env.forward` 已被 Git 忽略。SSH 转发断开、云端服务不可用或本机端口被其他程序占用时，启动会失败；这不会改变 Docker 本地栈的 `.env` 配置。

不要依赖当前终端的 `java` 或全局 `mvn`。所有 Maven 命令都从 `./mvnw` 进入。

Android Emulator 复用说明见 [Android Emulator 复用说明](android-emulator.md)，包括当前 `jitong_api35` AVD 的参数、启动命令、APK 安装和从零重建步骤。

## 4. 常用开发命令

### 编译与测试

```sh
# 快速编译
./scripts/dev-env.sh ./mvnw -DskipTests compile

# 单个契约测试
./scripts/dev-env.sh ./mvnw -Dtest=HealthContractTest test

# 提交前完整验证；需要 Docker
./scripts/dev-env.sh ./mvnw verify
```

契约测试通过 Testcontainers 启动真实 PostgreSQL 与 MinIO，不需要在宿主机单独安装这两个服务。

### 启动本地服务栈

```sh
./scripts/dev-up.sh
```

首次启动会生成权限受限且已被 Git 忽略的 `.env`，并构建、启动 Caddy、Spring Boot、PostgreSQL 和 MinIO。开发入口只绑定 loopback：

```sh
curl http://127.0.0.1:8080/api/v1/system/health
```

查看状态与日志。直接通过 runtime wrapper 执行 Docker 命令：

```sh
./scripts/docker-runtime.sh --check
./scripts/docker-runtime.sh docker compose --env-file .env ps
./scripts/docker-runtime.sh docker compose --env-file .env logs -f server
```

验证服务重启和 Flyway migration 保持完整：

```sh
./scripts/dev-smoke.sh
```

停止容器但保留 PostgreSQL、MinIO 和 Caddy 数据卷：

```sh
./scripts/docker-runtime.sh docker compose --env-file .env down
```

只有明确需要清空全部本地数据时才执行 `docker compose --env-file .env down --volumes`；该操作不可恢复本地数据库和对象存储内容。

## 5. 本地环境变量

`scripts/dev-up.sh` 自动创建 `.env`：

| 变量 | 用途 | 默认/生成方式 |
|---|---|---|
| `JITONG_HTTP_PORT` | Caddy 的 loopback HTTP 端口 | `8080` |
| `POSTGRES_PASSWORD` | PostgreSQL 本地密码 | OpenSSL 随机生成 |
| `MINIO_ROOT_USER` | MinIO 本地访问用户 | `jitong` |
| `MINIO_ROOT_PASSWORD` | MinIO 本地密码 | OpenSSL 随机生成 |
| `MINIO_BUCKET` | 私有媒体 bucket | `jitong-media` |
| `AI_PROVIDER_ENABLED` | 是否启用真实 AI 总结 worker | `false` |
| `AI_PROVIDER_BASE_URL` | OpenAI-compatible API 的 base URL | 空；真实调试时必填 |
| `AI_PROVIDER_API_KEY` | AI provider API key | 空；只写入本机 `.env` |
| `AI_PROVIDER_MODEL` | AI 总结使用的模型名 | `gpt-4o-mini` |
| `AI_PROVIDER_REQUEST_TIMEOUT` | AI Provider 连接和响应超时 | `30s` |
| `AI_PROVIDER_SUPPORTS_VISION` | Provider 当前模型是否支持图片输入 | `false` |
| `AI_IMAGE_INPUT_ENABLED` | 服务端是否允许把受控图片加入 AI 上下文 | `false` |
| `AI_WORKER_POLL_INTERVAL` | AI 异步任务轮询间隔，单位毫秒 | `250` |
| `AI_WORKER_LEASE_TIMEOUT` | RUNNING 任务失联后回收的租约；必须长于 Provider 超时 | `2m` |
| `AI_DAILY_TOKEN_LIMIT` | 每用户按上海自然日计算的 AI Token 上限 | `100000` |
| `AI_MAX_OUTPUT_TOKENS` | 单次 AI 总结允许的最大输出 Token | `1024` |
| `AI_RETENTION_INTERVAL` | AI 过期任务与私人内容清理间隔，单位毫秒 | `60000` |
| `AI_RETENTION_INITIAL_DELAY` | 服务启动后首次 AI 清理延迟，单位毫秒 | `60000` |

`.env` 包含本地凭证，不得提交、复制到 issue、日志或聊天记录。修改 `JITONG_HTTP_PORT` 后重新执行 `./scripts/dev-up.sh` 即可使用新端口。

`.env.forward` 是另一套独立配置，仅用于本机 SSH 转发的云端依赖；不要把它改名覆盖 `.env`，也不要让 Docker Compose 读取它。

### 5.1 启用真实 AI provider

真实 AI 调试只把凭证写入仓库根目录、已被 Git 忽略且权限为 `600` 的 `.env`。不要把 key 写入
`application.yml`、`compose.yaml`、`.env.forward`、测试代码或文档，也不要把它提交到 Git。

配置项示例（下面的 key 仅为占位符，不要照抄）：

```dotenv
AI_PROVIDER_ENABLED=true
AI_PROVIDER_BASE_URL=https://example.invalid/v1
AI_PROVIDER_API_KEY=replace-with-a-local-secret
AI_PROVIDER_MODEL=replace-with-provider-model
AI_PROVIDER_REQUEST_TIMEOUT=30s
AI_PROVIDER_SUPPORTS_VISION=true
AI_IMAGE_INPUT_ENABLED=true
AI_WORKER_POLL_INTERVAL=250
AI_WORKER_LEASE_TIMEOUT=2m
AI_DAILY_TOKEN_LIMIT=100000
AI_MAX_OUTPUT_TOKENS=1024
```

只有 `AI_PROVIDER_SUPPORTS_VISION` 与 `AI_IMAGE_INPUT_ENABLED` 同时为 `true` 时，服务端才会读取并发送
当前仍获授权的规范化消息图片；否则模型只会收到 `[图片]` 占位文本。每个任务最多发送四张图片，发送前会
重新编码并把最长边限制到 1024px。确认模型确实支持视觉输入后再声明该能力。

`compose.yaml` 会把这些变量传给 `server` 容器；Spring Boot 再通过
`src/main/resources/application.yml` 映射到 `jitong.ai.provider.*`。因此修改 `.env` 后必须重建或重启
`server`，已经运行的容器不会自动读取新配置：

```sh
./scripts/docker-runtime.sh docker compose --env-file .env up --build --detach --wait server
```

通常直接重新执行下面的命令即可：

```sh
./scripts/dev-up.sh
```

启动后先确认应用健康：

```sh
curl --fail --silent --show-error \
  "http://127.0.0.1:$(sed -n 's/^JITONG_HTTP_PORT=//p' .env)/api/v1/system/health"
```

## 6. 使用真实 AI 做 C2C 调试

真实调试会把选定的 C2C 消息发送到 `.env` 中配置的 provider。请只使用临时用户和合成测试内容，
不要使用真实用户的隐私消息。测试完成后删除任务和 artifact，并在 provider 侧轮换已经暴露或不再需要的 key。

### 6.1 启动与准备

在仓库根目录执行：

```sh
./scripts/dev-env.sh --check
./scripts/dev-up.sh
```

将 API 地址和本地管理员 key 放入当前 shell。下面的命令不会打印 AI key：

```sh
BASE_URL="http://127.0.0.1:$(sed -n 's/^JITONG_HTTP_PORT=//p' .env)"
ADMIN_API_KEY="$(sed -n 's/^ADMIN_API_KEY=//p' .env)"
PASSWORD='temporary-ai-debug-password'
```

### 6.2 创建两个临时用户并登录

使用管理员 API 创建两个临时用户，并只从响应中提取 `userId` 和 `accountNo`。不要把完整响应或
access token 写入日志：

```sh
ALICE_USER_INFO="$(curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/admin/users" \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -H 'Content-Type: application/json' \
  -d "{\"displayName\":\"AI Debug Alice\",\"password\":\"$PASSWORD\"}" \
  | python3 -c 'import json,sys; u=json.load(sys.stdin); print(u["userId"],u["accountNo"])')"
read -r ALICE_USER_ID ALICE_ACCOUNT <<EOF
$ALICE_USER_INFO
EOF

BOB_USER_INFO="$(curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/admin/users" \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY" \
  -H 'Content-Type: application/json' \
  -d "{\"displayName\":\"AI Debug Bob\",\"password\":\"$PASSWORD\"}" \
  | python3 -c 'import json,sys; u=json.load(sys.stdin); print(u["userId"],u["accountNo"])')"
read -r BOB_USER_ID BOB_ACCOUNT <<EOF
$BOB_USER_INFO
EOF
printf '%s\n' "Created temporary users: Alice=$ALICE_ACCOUNT Bob=$BOB_ACCOUNT"
```

分别使用返回的账号登录一次 `PC` 和 `MOBILE` 设备。将响应中的 `accessToken` 仅保存在当前
shell 的 `ALICE_TOKEN`、`BOB_TOKEN` 变量中：

```sh
ALICE_TOKEN="$(curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"accountNo\":\"$ALICE_ACCOUNT\",\"password\":\"$PASSWORD\",\"deviceClass\":\"PC\",\"installationId\":\"ai-debug-alice\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')"

BOB_TOKEN="$(curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"accountNo\":\"$BOB_ACCOUNT\",\"password\":\"$PASSWORD\",\"deviceClass\":\"MOBILE\",\"installationId\":\"ai-debug-bob\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')"
```

### 6.3 建立联系人、发送测试消息并开启双方 consent

Alice 发起联系人请求，Bob 接受。将响应中的 `requestId` 和 `conversationId` 保存为 shell
变量：

```sh
REQUEST_ID="$(curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/contact-requests" \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"accountNo\":\"$BOB_ACCOUNT\",\"verification\":\"ai-debug\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["requestId"])')"

CONVERSATION_ID="$(curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/contact-requests/$REQUEST_ID/accept" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["conversationId"])')"
```

发送两条合成文本消息，并从响应中分别提取 `conversationSeq`。新建会话通常从 sequence `1`
开始，但调试时应以实际响应为准：

```sh
FIRST_SEQ="$(curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/conversations/$CONVERSATION_ID/messages" \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"clientMsgId\":\"$(uuidgen)\",\"text\":\"AI debug: agree to ship the test flow.\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["conversationSeq"])')"

SECOND_SEQ="$(curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/conversations/$CONVERSATION_ID/messages" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"clientMsgId\":\"$(uuidgen)\",\"text\":\"AI debug: next step is to verify the summary result.\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["conversationSeq"])')"
printf '%s\n' "Created test message range: $FIRST_SEQ-$SECOND_SEQ"
```

双方都必须同意 AI 处理。第一次请求通常返回 `enabledForBoth: false`；第二次参与者开启 consent
后应返回 `enabledForBoth: true`，此时才能提交总结：

```sh
curl --fail --silent --show-error \
  -X PATCH "$BASE_URL/api/v1/conversations/$CONVERSATION_ID/ai/consent" \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}'

curl --fail --silent --show-error \
  -X PATCH "$BASE_URL/api/v1/conversations/$CONVERSATION_ID/ai/consent" \
  -H "Authorization: Bearer $BOB_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"enabled":true}'
```

### 6.4 提交并轮询 AI 总结任务

提交异步任务时，`requestId` 必须是新的 UUID；下面使用前两条消息实际返回的 sequence 范围：

```sh
JOB_JSON="$(curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/conversations/$CONVERSATION_ID/ai/summary" \
  -H "Authorization: Bearer $ALICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"$(uuidgen)\",\"afterSeq\":$((FIRST_SEQ - 1)),\"untilSeq\":$SECOND_SEQ}")"

JOB_ID="$(printf '%s' "$JOB_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["jobId"])')"
printf '%s\n' "Queued AI job: $JOB_ID"
```

轮询任务状态，直到 `SUCCEEDED` 或 `FAILED`：

```sh
while :; do
  JOB_JSON="$(curl --fail --silent --show-error \
    -H "Authorization: Bearer $ALICE_TOKEN" \
    "$BASE_URL/api/v1/ai/jobs/$JOB_ID")"
  STATUS="$(printf '%s' "$JOB_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')"
  printf '%s\n' "AI job status: $STATUS"
  case "$STATUS" in
    SUCCEEDED|FAILED) break ;;
  esac
  sleep 1
done
```

成功时检查结构化结果和证据范围：

```sh
printf '%s\n' "$JOB_JSON" | python3 -m json.tool
```

`result` 应包含 `overview`、`keyPoints`、`decisions`、`openQuestions` 和
`sourceMessageIds`。`sourceMessageIds` 必须全部属于本次授权的消息上下文；服务端会在任务完成前再次
校验 consent、policy version、消息范围和上下文摘要，校验失败时任务会进入 `FAILED`。

### 6.5 清理调试数据

任务完成后可删除任务及其 artifact：

```sh
curl --fail --silent --show-error \
  -X DELETE "$BASE_URL/api/v1/ai/jobs/$JOB_ID" \
  -H "Authorization: Bearer $ALICE_TOKEN"
```

临时用户可通过管理员接口 retire。保留本地 Docker 数据卷时，调试用户和消息仍会留在本机数据库；
使用创建用户时保存的 `ALICE_USER_ID` 和 `BOB_USER_ID` 执行 retire：

```sh
curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/admin/users/$ALICE_USER_ID/retire" \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY"

curl --fail --silent --show-error \
  -X POST "$BASE_URL/api/v1/admin/users/$BOB_USER_ID/retire" \
  -H "X-Admin-Api-Key: $ADMIN_API_KEY"
```

如果还需要清空本地数据库和对象存储，只有明确需要清空全部本地数据时才执行：

```sh
./scripts/docker-runtime.sh docker compose --env-file .env down --volumes
```

## 7. IDE 约定

IntelliJ IDEA 导入项目时：

1. Project SDK 选择 JDK 21；
2. Maven home 选择 Wrapper；
3. Maven importer 和 test runner 的 JDK 选择 Project SDK 21；
4. 不要把个人绝对路径、运行凭证或 `.env` 提交到仓库。

## 8. 常见问题

### `java -version` 显示 1.8

这是系统默认 Java，不代表项目脚本会使用它。先运行：

```sh
./scripts/dev-env.sh --check
```

如果脚本报告找不到 JDK 21，执行 `brew install --cask temurin@21`。

### `mvn: command not found`

这是预期状态。项目不使用全局 Maven，改用：

```sh
./scripts/dev-env.sh ./mvnw --version
./scripts/dev-env.sh ./mvnw verify
```

### Docker 已安装但测试仍无法连接

```sh
colima status
colima start
docker context use colima
export DOCKER_HOST=unix://${HOME}/.colima/default/docker.sock
export TESTCONTAINERS_RYUK_DISABLED=true
docker info
```

必须等 `docker info` 成功后再运行 Testcontainers 或 Compose 命令。

### 8080 端口被占用

修改 `.env` 中的 `JITONG_HTTP_PORT`，例如改为 `18080`，然后重新运行 `./scripts/dev-up.sh`。

云端转发开发栈使用：

```sh
SERVER_PORT=18080 ./scripts/dev-forward.sh
```

### AI 任务一直为 `FAILED`

先查看 server 日志中的错误码，但不要复制包含环境变量、Authorization header 或消息正文的完整日志：

```sh
./scripts/docker-runtime.sh docker compose --env-file .env logs --tail=200 server
```

重点检查：

1. `AI_PROVIDER_ENABLED=true`；
2. `AI_PROVIDER_BASE_URL` 以 provider 要求的 `/v1` 路径结尾；
3. `AI_PROVIDER_API_KEY` 和 `AI_PROVIDER_MODEL` 与 provider 控制台配置一致；
4. 两个参与者都已开启 consent；
5. `afterSeq` 和 `untilSeq` 覆盖实际存在且未撤回的消息；
6. provider 返回的是合法 JSON 对象，且 `sourceMessageIds` 没有引用上下文之外的消息。

如果修改了 `.env`，必须重新执行 `./scripts/dev-up.sh` 或显式重建 `server` 容器；仅刷新 API 请求不会更新
容器环境变量。

## 9. CI 对齐

GitHub Actions 使用 Temurin JDK 21、Maven Wrapper、Docker/Testcontainers，并额外验证生产 Caddy TLS 配置与 Compose 启动/重启 smoke。任何只在个人全局 Maven、Java 8 或未启动 Docker 时才能复现的流程都不属于项目支持的开发路径。
