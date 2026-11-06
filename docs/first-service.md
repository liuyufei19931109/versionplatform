# 接入第一个服务

本例把一个 Node HTTP 服务发布到独立的 Linux 预生产节点。完成后，你能从发版记录找到构建的 Commit、镜像版本、目标节点和健康检查结果。文中的 `.invalid` 域名均为占位符，请换成自己的测试资源。

## 1. 准备三处资源

| 位置 | 所需条件 |
| --- | --- |
| 运行平台后端的机器 | 选择「本机构建」后，后端运行账号需要可写的工作区、Git、OpenSSH 客户端和可用的 Docker 引擎。Docker 需要能拉取基础镜像。若平台在容器里运行，从该容器内确认工具、工作区和相应权限均可用。 |
| 源码与镜像仓库 | 使用专门的测试 Git 仓库和镜像命名空间。构建账号可以读取 Git、推送目标镜像，目标节点可以拉取该镜像。仅将受信代码接入有 Docker 权限的构建账号。 |
| 独立测试节点 | 准备 SSH 服务、Docker、Compose V2 和 `curl`。专用部署账号需要访问测试项目目录及执行 Docker 的权限。首次接入只绑定一个预生产节点。 |

**准备远程 `master` 分支。** 当前版本在构建开始和重试时检查所选 Commit 是否包含远程 `master` 的最新提交。对于新建示例仓，可直接在 `master` 上准备下面两个文件并提交。表单里的「默认分支」只影响选分支，填写 `main` 仍须满足这项检查。

在示例仓根目录放置 `server.mjs`：

```js
import { createServer } from 'node:http'

createServer((req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200)
    res.end('ok')
  } else {
    res.writeHead(200)
    res.end('release demo v1')
  }
}).listen(8080, '0.0.0.0')
```

同目录的 `Dockerfile`：

```dockerfile
FROM node:22-alpine
WORKDIR /app
COPY server.mjs .
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
```

在测试节点为此服务单独创建项目目录，例如 `/srv/release-demo`。将下面的 `compose.yaml` 放入该目录，替换镜像地址。「发版」会用选中制品的镜像覆盖下方声明，拉取镜像并重建 `demo-web`。本例由节点直接探测容器，无需向公网映射端口。

```yaml
services:
  demo-web:
    image: registry.example.invalid/lab/demo-web:bootstrap
```

## 2. 配置仓库和 SSH

1. 管理员在「基础配置 → 镜像仓库」填写测试仓库地址和专用登录凭据。这里填注册表主机，如 `registry.example.invalid`；稍后在服务上填写完整镜像路径。
2. 在后端运行账号的 OpenSSH 配置中为测试目标启用 `StrictHostKeyChecking yes`。请向节点管理员通过可信渠道核对主机公钥指纹，再将已核实的公钥按连接所用的主机名和端口写入该账号的 `known_hosts`。使用非默认端口时，以 `[主机名]:端口` 记录。源仓库若走 SSH，也需单独配置 Git 主机信任和只读仓库密钥。
3. 到「服务器管理 → 添加服务器」，选择「直接 SSH」与 SSH Key。填写隔离节点地址、部署账号、端口及**平台后端所在机器上**的专用私钥路径。填写 Compose 项目目录 `/srv/release-demo` 和文件名 `compose.yaml`。当前密码连接路径缺少严格的主机指纹校验，本例只使用公钥认证。
4. 保存后执行「测试连接」。SSH 和 Compose 两项均应成功，服务列表应包含 `demo-web`。这一步读取 Compose 配置；镜像推送和拉取能力分别在实际构建和部署时验证。

## 3. 登记服务并首次发版

| 入口 | 本例填写 |
| --- | --- |
| 「服务管理 → 新建服务」 | 名称自定，标识 `demo-web`，项目类型选「前端项目」，构建方式选「本机构建」，填写示例仓的 Git 地址，将默认分支设为 `master`。 |
| 服务详情的「镜像配置」 | 前端交付方式选「Node 应用（仓库 Dockerfile）」，Dockerfile 路径填 `Dockerfile`，完整镜像仓库地址填 `registry.example.invalid/lab/demo-web`。该模式使用仓库根目录作为构建上下文，示例应用的 Node 运行时由镜像提供。 |
| 服务详情的「环境与集群」 | 选「预生产环境」，允许发版，只勾选刚创建的测试节点。部署方式选 Docker Compose，Compose 服务名填 `demo-web`，容器内部健康检查端口填 `8080`，健康检查地址填 `/healthz`。使用平台生成的启动命令。保存配置。 |

进入「新建打包」，选服务与分支，核对并确认最新 Commit，选「仅打包」后加入队列。待任务完成，在「新建发版」选择本次成功的制品、预生产环境和测试节点，核对后提交。

在「发版记录」打开任务详情。三个结果应对应同一次发布：构建记录的 Commit 是你确认的版本，发版日志中的目标为 `demo-web`，该节点健康检查成功。在「运行总览」执行检查，查看实际容器、镜像与最近发布记录。

若任一环节失败，先打开失败步骤的日志。分支错误时核对远程 `master` 与所选 Commit；推送失败时核对完整镜像路径与测试仓库权限；健康检查失败时核对容器端口、`/healthz` 和应用是否监听 `0.0.0.0`。修复源码后创建**新构建**。原任务重试会沿用其锁定的 Commit。

下一步见[日常使用指南](user-guide.md)。
