# Android Emulator 复用说明

本文记录 T07 联调使用的 Android Emulator。AVD 本体和 Android SDK 不在 Git 仓库中；仓库只保存可复用的创建参数和启动命令。

## 1. 当前 AVD

| 项目 | 值 |
|---|---|
| AVD 名称 | `jitong_api35` |
| 显示名称 | `Jitong API 35` |
| Android API | 35 |
| 系统镜像 | `google_apis / arm64-v8a` |
| 设备模板 | Pixel |
| CPU 架构 | ARM64 |
| 屏幕 | 1080 × 2400，420 dpi |
| 内存 | 2048 MB |
| Play Store | 未启用 |
| ADB 设备 | `emulator-5554` |

当前机器上的 AVD 目录：

```text
~/.android/avd/jitong_api35.avd
~/.android/avd/jitong_api35.ini
```

SDK 目录使用 macOS 上 Android Studio 的标准位置：

```text
~/Library/Android/sdk
```

早期版本的本文把 SDK 装在 `/tmp/jitong-android-sdk`。`/tmp` 会被系统定期清理，那份 SDK 已经不存在，不要再使用该路径。如果本机还没有 SDK，按照本文第 4 节安装到上面的标准位置。

## 2. 检查是否已经存在

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

emulator -list-avds
adb devices -l
```

如果输出包含：

```text
jitong_api35
```

并且 `adb devices -l` 中出现：

```text
emulator-5554    device
```

就可以直接复用，不需要重新创建。

## 3. 启动和停止

启动当前 AVD：

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

emulator \
  -avd jitong_api35 \
  -no-snapshot \
  -no-boot-anim \
  -gpu swiftshader_indirect
```

建议在单独的终端窗口运行启动命令。另开一个终端等待系统启动完成：

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"

until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 1
done

adb devices -l
```

停止模拟器：

```sh
adb emu kill
```

如果本机有多个模拟器，给 ADB 命令加设备参数：

```sh
adb -s emulator-5554 shell getprop ro.build.version.sdk
```

## 4. 从零安装 SDK 和重建 AVD

以下命令适用于当前 macOS arm64 环境。Android Command-line Tools 的下载地址可能随 Google 发布版本变化；如果当前下载地址失效，到 Android Studio 官方下载页获取最新 macOS command-line tools zip，并替换下载 URL。

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"

curl -fL \
  -o /tmp/commandlinetools.zip \
  https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip

unzip -q /tmp/commandlinetools.zip -d "$ANDROID_SDK_ROOT/cmdline-tools/latest"

SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager"

yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses

"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" \
  "emulator" \
  "platforms;android-35" \
  "system-images;android-35;google_apis;arm64-v8a" \
  "build-tools;35.0.0"
```

创建 AVD：

```sh
echo no | "$AVDMANAGER" create avd \
  --force \
  --name jitong_api35 \
  --package "system-images;android-35;google_apis;arm64-v8a" \
  --device pixel
```

创建完成后检查：

```sh
emulator -list-avds
cat "$HOME/.android/avd/jitong_api35.avd/config.ini"
```

如需尽量匹配当前联调设备，可确认 `config.ini` 中包含以下关键值：

```ini
abi.type=arm64-v8a
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.device.name=pixel
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.ramSize=2048
image.sysdir.1=system-images/android-35/google_apis/arm64-v8a/
tag.id=google_apis
```

## 5. 安装和启动即通 Android

在仓库根目录执行：

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"

cd android-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.jitong.im.android.debug
adb shell monkey -p com.jitong.im.android.debug 1
```

Debug 构建默认连接：

```text
http://10.0.2.2:8080/
```

`10.0.2.2` 是 Android Emulator 访问宿主机 `127.0.0.1` 的特殊地址。配合当前云端转发联调环境：

```text
宿主机 PostgreSQL：127.0.0.1:5432
宿主机 MinIO API：127.0.0.1:9000
宿主机 Spring Boot：0.0.0.0:8080
模拟器访问后端：http://10.0.2.2:8080/
```

启动后端：

```sh
./scripts/dev-forward.sh
```

检查模拟器到宿主机后端的网络：

```sh
printf 'GET /api/v1/system/health HTTP/1.0\r\nHost: 10.0.2.2\r\nConnection: close\r\n\r\n' \
  | adb shell toybox nc 10.0.2.2 8080
```

响应中应包含：

```json
{"version":1,"status":"UP"}
```

## 6. 常见问题

### `emulator: command not found`

重新设置 SDK 路径：

```sh
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export PATH="$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$PATH"
```

### `No such AVD name`

检查 AVD 名称：

```sh
emulator -list-avds
```

如果没有 `jitong_api35`，重新执行本文第 4 节的创建命令。

### `adb devices` 显示 `offline`

```sh
adb kill-server
adb start-server
adb devices -l
```

仍然异常时，停止旧模拟器后重新启动：

```sh
adb emu kill
emulator -avd jitong_api35 -no-snapshot -no-boot-anim -gpu swiftshader_indirect
```

### 模拟器访问不到后端

先确认宿主机后端正在监听 `8080`：

```sh
lsof -nP -iTCP:8080 -sTCP:LISTEN
curl http://127.0.0.1:8080/api/v1/system/health
```

再确认模拟器可以访问宿主机：

```sh
printf 'GET /api/v1/system/health HTTP/1.0\r\nHost: 10.0.2.2\r\nConnection: close\r\n\r\n' \
  | adb shell toybox nc 10.0.2.2 8080
```

不要在 Android Emulator 中使用 `127.0.0.1:8080`，因为它指向模拟器自身。

## 7. Git 记录边界

以下内容不会进入 Git：

- `~/.android/avd/jitong_api35.avd/`
- `~/.android/avd/jitong_api35.ini`
- `~/Library/Android/sdk/`
- 模拟器运行数据和快照

Git 中只记录本文和 Android 工程。这样可以避免提交数 GB 的系统镜像、用户数据、快照或机器相关路径，同时保留完整的重建参数。
