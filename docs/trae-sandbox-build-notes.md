# TRAE 沙箱构建环境参考（仅限 TRAE 沙箱使用）

> ⚠️ **声明：本文档内容仅限于 TRAE 沙箱环境参考，不适用于本地开发或其他 CI 环境。**
>
> 记录 TRAE 沙箱中重建 Android 构建环境并构建 carrier-ims-for-pixel (Pixel 1 / Android 10) APK 的踩坑经验，方便后续沙箱重置后快速恢复。

## 沙箱环境特征

- OS：Ubuntu 24.04 LTS（容器，非 VM）
- 架构：x86_64
- 内存：约 5.8 GiB
- Swap：0（容器无 `CAP_SYS_ADMIN`，无法 `swapon`）
- 出网：必须走 HTTP 代理 `127.0.0.1:18080`，**外部 HTTPS 直连全失败**
- Java：mise 管理，可用版本 8/11/17/25（路径 `/root/.local/share/mise/installs/java/`）
- 源码位置：`/workspace`（git 仓库持久，环境重置后源码与 git 历史保留）

## 出网与代理

**核心规则：所有外部 HTTPS 请求必须走代理，直连不可达。**

- 环境变量已设置（shell 自动生效）：
  ```
  https_proxy=http://127.0.0.1:18080
  http_proxy=http://127.0.0.1:18080
  ```
- curl / wget 会自动读取环境变量代理，测试可达性返回 200 不代表直连可用。
- Java / Gradle / Maven **不读环境变量**，必须通过 `-Dhttps.proxyHost=...` 系统属性显式传入。
- 不可达的站点（直连）：maven.aliyun.com、dl.google.com、repo.maven.apache.org、repo1.maven.org 全部 timeout。

### Gradle 代理配置（gradle.properties）

```properties
org.gradle.jvmargs=-Xmx2048M -Dfile.encoding=UTF-8 \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=18080 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=18080 \
  -Dhttp.nonProxyHosts=localhost|127.0.0.1
```

### Robolectric 单测代理（app/build.gradle.kts）

Robolectric 的 `MavenArtifactFetcher` 在 TestWorker JVM 里下载 android-all jar，也需要代理：

```kotlin
tasks.withType<Test>().configureEach {
    systemProperty("http.proxyHost", "127.0.0.1")
    systemProperty("http.proxyPort", "18080")
    systemProperty("https.proxyHost", "127.0.0.1")
    systemProperty("https.proxyPort", "18080")
    systemProperty("http.nonProxyHosts", "localhost|127.0.0.1")
    systemProperty("robolectric.mavenRepository.url", "https://maven.aliyun.com/repository/central")
}
```

### SDK 下载禁用（gradle.properties）

AGP 启动时会 fetch SDK repository manifest，通过代理时偶发 SSL 握手失败。已安装所需 SDK 后可禁掉：

```properties
android.builder.sdkDownload=false
```

## Swap 限制

**无法启用 swap。** 沙箱是容器：

- 无 `CAP_SYS_ADMIN`（capability mask `a80425fb` 不含 bit 21）
- 无 `/dev/zram*`
- 无 `/dev/loop-control`
- `swapon /swapfile` → `Operation not permitted`

**替代方案**：降低各 JVM 进程的 -Xmx 上限，把总内存压在物理内存内。

## 内存配置（5.8 GiB 物理内存）

| 进程 | -Xmx | 说明 |
|------|------|------|
| Gradle daemon | 2048M | 主构建进程 |
| Kotlin daemon | 1024M | 编译期额外进程 |
| 合计 | 3072M | 留 2-3 GiB 给系统 + AAPT2 + D8 |

`gradle.properties` 对应：

```properties
kotlin.daemon.jvmargs=-Xmx1024M
org.gradle.jvmargs=-Xmx2048M ...
org.gradle.parallel=false
org.gradle.workers.max=2
```

**踩坑记录**：
- `-Xmx2560M` + `-Xmx1536M` = 4096M → Kotlin 编译完到 Java 编译阶段 Gradle daemon 被 OOM Kill
- 降到 2048M + 1024M = 3072M → 稳定通过

## 环境重建步骤

沙箱重置后，以下组件全部丢失，需手动重建：

### 1. Gradle 9.1.0

```bash
cd /tmp && curl -sSL -o gradle.zip https://mirrors.cloud.tencent.com/gradle/gradle-9.1.0-bin.zip
unzip -q gradle.zip -d /opt
/opt/gradle-9.1.0/bin/gradle --version
```

### 2. JDK 17

```bash
# 通过 mise 安装（如已安装跳过）
mise install java@17.0.2
# 路径：/root/.local/share/mise/installs/java/17.0.2
```

项目需 JDK 17（Kotlin 2.3.0 + AGP 8.13.2 + Robolectric ASM 兼容）。

### 3. Android SDK

```bash
mkdir -p /opt/android-sdk/cmdline-tools
cd /tmp
curl -sSL -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q cmdline-tools.zip -d /opt/android-sdk/cmdline-tools
mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest

# 接受许可 + 安装组件
yes | JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 \
  /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses

JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 \
  /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0"
```

> 为什么装两个 build-tools？AGP 8.13.2 默认要 35.0.0，compileSdk=36 需要 36.0.0 的某些资源。

### 4. debug.keystore

```bash
mkdir -p /root/.android
JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 \
  keytool -genkeypair -keystore /root/.android/debug.keystore \
  -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

### 5. local.properties

```properties
sdk.dir=/opt/android-sdk
```

## 构建命令

```bash
cd /workspace && \
JAVA_HOME=/root/.local/share/mise/installs/java/17.0.2 \
ANDROID_HOME=/opt/android-sdk \
/opt/gradle-9.1.0/bin/gradle :app:assembleDebug :app:testDebugUnitTest \
  --no-daemon \
  --tests "io.github.vvb2060.ims.privileged.SimResultBundleTest"
```

- `--no-daemon`：沙箱一次性构建，避免 daemon 常驻占内存
- `--tests`：只跑核心单测（3 个 Robolectric 用例），省时间
- 首次构建约 5-8 分钟（主要耗在下载依赖 + Kotlin 编译）
- 后续增量构建约 1-2 分钟

## 常见问题

### 1. `Failed to find Build Tools revision 35.0.0`

AGP 8.13.2 默认要 35.0.0，手动装：`sdkmanager "build-tools;35.0.0"`

### 2. `Gradle build daemon disappeared unexpectedly`

OOM Kill。降低 `org.gradle.jvmargs` 和 `kotlin.daemon.jvmargs`。

### 3. 卡在 `Still waiting for package manifests to be fetched remotely`

AGP 尝试 fetch SDK manifest，网络/代理问题。加 `android.builder.sdkDownload=false` 跳过。

### 4. Robolectric 单测 `ConnectException` / `MavenArtifactFetcher` 失败

TestWorker JVM 没走代理。在 `app/build.gradle.kts` 的 `tasks.withType<Test>()` 里加 `systemProperty("https.proxyHost/Port", ...)`。

### 5. 代理可用但 Gradle 仍下载慢

阿里云镜像在 `settings.gradle.kts` 的 `pluginManagement` 和 `dependencyResolutionManagement` 都配置了，优先走 maven.aliyun.com。确认代理到阿里云通畅。

### 6. `permission denied` / `Operation not permitted`

容器权限限制，不要尝试 `swapon`、`mount`、`modprobe` 等操作。

## 关键路径速查

| 组件 | 路径 |
|------|------|
| 项目根 | `/workspace` |
| JDK 17 | `/root/.local/share/mise/installs/java/17.0.2` |
| Gradle | `/opt/gradle-9.1.0` |
| Android SDK | `/opt/android-sdk` |
| debug keystore | `/root/.android/debug.keystore` |
| APK 输出 | `app/build/outputs/apk/debug/` |
| 单测报告 | `app/build/reports/tests/testDebugUnitTest/` |
| Maven 缓存 | `/root/.gradle/caches/modules-2/` |
