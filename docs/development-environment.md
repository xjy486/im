# 开发环境与启动约定

本文是“即通”仓库的开发环境权威入口。后续开发、测试和本地启动均按这里执行，不依赖个人 shell 中偶然存在的 Java 或 Maven 配置。

## 1. 固定约定

| 工具 | 项目约定 | 说明 |
|---|---|---|
| 操作系统 | macOS arm64 | 当前主开发环境；CI 使用 Linux |
| JDK | 21 | `pom.xml`、Docker 构建和 CI 均使用 Java 21 |
| Maven | Wrapper 中的 3.9.11 | 只使用 `./mvnw`，不要求安装全局 `mvn` |
| Docker | macOS 本地开发使用 Colima；CI 使用 Linux Docker | `docker compose` 必须可用，Testcontainers 也依赖 Docker daemon |
| 其他命令 | Git、OpenSSL、curl | macOS 默认通常已提供；启动脚本会使用它们 |

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

`.env` 包含本地凭证，不得提交、复制到 issue、日志或聊天记录。修改 `JITONG_HTTP_PORT` 后重新执行 `./scripts/dev-up.sh` 即可使用新端口。

`.env.forward` 是另一套独立配置，仅用于本机 SSH 转发的云端依赖；不要把它改名覆盖 `.env`，也不要让 Docker Compose 读取它。

## 6. IDE 约定

IntelliJ IDEA 导入项目时：

1. Project SDK 选择 JDK 21；
2. Maven home 选择 Wrapper；
3. Maven importer 和 test runner 的 JDK 选择 Project SDK 21；
4. 不要把个人绝对路径、运行凭证或 `.env` 提交到仓库。

## 7. 常见问题

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

## 8. CI 对齐

GitHub Actions 使用 Temurin JDK 21、Maven Wrapper、Docker/Testcontainers，并额外验证生产 Caddy TLS 配置与 Compose 启动/重启 smoke。任何只在个人全局 Maven、Java 8 或未启动 Docker 时才能复现的流程都不属于项目支持的开发路径。
