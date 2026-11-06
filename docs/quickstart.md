# 本地快速开始

这份指南适用于 `0.1.0-preview.1`。完成后，你可以用自己的管理员账号打开工作台，切换界面语言，查看服务、构建和版本管理页面。空实例中的服务与发版记录需要自己创建。

本地体验只需运行 Java 后端、Vite 前端和内置 H2 数据库。准备一台电脑、两个终端，以及能生成六位 TOTP 动态码的认证器。首次安装依赖需要访问 Maven 和 npm 仓库。

## 1. 准备源码和工具

从项目仓库下载源码 ZIP 并解压到空文件夹，或用仓库页面 **Code** 菜单中的地址执行 `git clone`。找到同时包含 `backend/pom.xml` 和 `frontend/package.json` 的目录。下文称它为**仓库根目录**，两个终端都从这里开始。

安装 **JDK 21、Maven 3.9.x、Node.js 22 或 24 和随附的 npm**。运行以下命令检查工具：

```text
java -version
mvn -version
node --version
npm --version
```

`mvn -version` 显示的 Java 版本也应是 21。Windows PowerShell 如拦截 `npm.ps1`，请使用 `npm.cmd --version` 和下文的 `npm.cmd` 命令。

本次体验使用个人目录下的独立文件夹保存实例数据。首次运行请选一个全新的目录，并使用全新的本地终端，避免继承其他部署的 `SPRING_*`、`RELEASE_*` 配置。后续启动同一实例时应复用同一目录。

## 2. 启动后端

在第一个终端编译后端，再从实例目录运行 JAR。默认管理员用户名为 `admin`。首次启动需要自行设置密码，要求**至少 12 个字符，UTF-8 编码最多 72 字节**。下面的命令在终端中隐藏密码输入。

### Windows PowerShell

在仓库根目录执行：

```powershell
$repo = (Get-Location).Path
mvn -f backend/pom.xml -DskipTests package
if ($LASTEXITCODE -ne 0) { throw '后端构建失败，请先检查 Maven 输出。' }

$jar = Join-Path $repo 'backend/target/release-platform-0.1.0-preview.1.jar'
$instance = Join-Path $env:LOCALAPPDATA 'release-platform-preview'
New-Item -ItemType Directory -Force -Path $instance | Out-Null
$initialPassword = Read-Host '为 admin 设置首次登录密码' -AsSecureString
Push-Location -LiteralPath $instance
try {
    $env:RELEASE_BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $initialPassword).Password
    java -jar $jar --server.address=127.0.0.1 --server.port=8080 --release.workspace=./workspaces
} finally {
    Remove-Item Env:\RELEASE_BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    $initialPassword.Dispose()
    Pop-Location
}
```

### macOS / Linux（Bash）

在仓库根目录打开 Bash；macOS 默认使用 zsh 时，先输入 `bash` 并按回车。执行：

```bash
repo="$(pwd)"
mvn -f backend/pom.xml -DskipTests package && (
  set -e
  umask 077
  instance="$HOME/.local/share/release-platform-preview"
  mkdir -p "$instance"
  cd "$instance"
  read -r -s -p '为 admin 设置首次登录密码：' RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  printf '\n'
  export RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  java -jar "$repo/backend/target/release-platform-0.1.0-preview.1.jar" \
    --server.address=127.0.0.1 --server.port=8080 --release.workspace=./workspaces
)
```

保持第一个终端运行。在浏览器打开 [后端健康检查](http://127.0.0.1:8080/api/health)。返回内容应包含 `"status":"UP"` 和版本号。登录页面使用下一步的前端地址。

## 3. 启动前端

在**第二个终端**中从仓库根目录执行对应命令。Vite 会把页面的 `/api` 请求代理到本机后端。

**Windows PowerShell**

```powershell
cd frontend
npm.cmd ci
if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败，请检查 npm 输出。' }
$env:VITE_API_PROXY = 'http://127.0.0.1:8080'
npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

**macOS / Linux**

```bash
cd frontend
npm ci && VITE_API_PROXY=http://127.0.0.1:8080 \
  npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

打开 [本地工作台](http://127.0.0.1:5173)。若 [经前端代理的健康检查](http://127.0.0.1:5173/api/health) 也返回 `UP`，说明浏览器到后端的路径已接通。这里的两个服务都仅监听本机回环地址。

## 4. 登录并绑定认证器

1. 在登录页输入 `admin` 和第 2 步设置的密码。首次登录会进入「登录安全」。
2. 选择「开始绑定」。在 Google Authenticator 或兼容的 TOTP 应用中扫描二维码，也可以手动输入页面提供的密钥。二维码在 10 分钟内有效。
3. 输入认证器当前显示的六位数字，选择「验证并启用」。验证成功后进入工作台。下次登录需要账号密码和一个新的动态码。
4. 登录页和工作台右上角都能选择简体中文、Español 或 English。要验证记录是否为空，可以打开「服务管理」或「发版记录」。

同一个动态码只能使用一次。刚完成绑定或验证时，请等认证器显示下一个码再登录。校时请同时检查运行后端的电脑和手机。二维码和手动绑定密钥等同于第二因素凭据，请仅在自己的设备上保存。

## 5. 停止、再次启动和备份

先在前端终端按 `Ctrl+C`，再在后端终端按 `Ctrl+C`，待后端退出。文件型 H2 在正常关闭时写入备份；强制结束 Java 进程或突然断电可能来不及写出。

以上命令将以下内容放在实例目录中：

| 路径 | 用途 |
| --- | --- |
| `data/release-platform.mv.db` | H2 数据库，保存账号、服务配置和任务记录 |
| `data/credential-encryption.key`、`data/totp-encryption.key` | 解密已保存的凭据和 TOTP 密钥所需的本地密钥 |
| `data/backups/shutdown-*/` | 正常关闭时生成的 H2 快照目录 |
| `workspaces/` | 源码构建工作区，运行相关任务时使用 |

检查最新备份目录是否包含 `COMPLETE` 和 `database.zip`。平台在标记完成前还会复制当时存在的两份加密密钥。备份含账号数据和密钥，应和实例目录一起限制访问。移动实例时还要单独带走安装包等文件型制品；H2 压缩包仅包含数据库。

**再次启动**时沿用原来的 JAR、实例工作目录和参数。已有 `admin` 后可省略 `RELEASE_BOOTSTRAP_ADMIN_PASSWORD`；重新设置此变量不会重置现有密码。前端继续在 `frontend/` 下执行第 3 步的启动命令，锁文件和依赖没有变化时可省略 `npm ci`。

如需自定义路径，`RELEASE_DATA_DIR` 可调整 H2 数据目录，`RELEASE_SHUTDOWN_BACKUP_DIR` 可调整关停备份目录。**两个加密密钥的默认路径分别是当前工作目录下的 `./data/credential-encryption.key` 和 `./data/totp-encryption.key`**，单独更改 `RELEASE_DATA_DIR` 不会自动迁移它们。迁移已有实例应带上原密钥，并使用 `--release.credential-key-file=绝对路径`、`--release.totp-key-file=绝对路径` 指向正确文件。MySQL 是另一个可选配置；本指南的 H2 关停备份机制仅适用于文件型 H2。

## 常见启动问题

| 现象 | 检查方法 |
| --- | --- |
| 编译提示不支持 release 21 | 检查 `mvn -version` 中的 Java home，修正 `JAVA_HOME` 后重新打开终端。 |
| 后端启动后立即退出，提示首次管理员密码 | 在启动 Java 的同一终端设置满足长度要求的密码，再使用同一实例目录启动。 |
| 8080 或 5173 已占用 | 更改后端 `--server.port` 后，同步修改前端 `VITE_API_PROXY` 并重启 Vite；只改前端 `--port` 时，用新端口打开页面。 |
| 页面能打开，登录或 API 报连接失败 | 依次访问后端与前端代理的健康检查，确认后端已就绪，并核对代理的地址和端口。 |
| 重启后像是全新实例，或已绑定凭据无法解密 | 核对启动目录、数据目录和密钥配置，回到原实例目录。请保留已有数据库与密钥。 |
| 想做真实发版 | 准备受信的测试仓库、所选构建方式对应的工具和镜像仓库，再登记自己的 Linux 测试节点。当前构建及部分运行脚本依赖 Linux、Docker 和 SSH；在 Windows 或 macOS 打开工作台与执行 Linux 部署是两项独立操作。 |

完成本地体验后，再依照[接入第一个服务](first-service.md)配置自己的测试资源。
