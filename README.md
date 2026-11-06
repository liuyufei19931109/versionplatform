<h1 align="center">发版平台</h1>

<p align="center"><strong>从 Git 提交到生产节点的自托管发布工作台</strong><br>
构建时锁定提交，上线前核对制品和目标节点，上线后逐台查看部署结果。</p>

<p align="center">
  <a href="https://github.com/liuyufei19931109/versionplatform/actions/workflows/checks.yml"><img src="https://github.com/liuyufei19931109/versionplatform/actions/workflows/checks.yml/badge.svg?branch=main" alt="自动检查状态"></a>
  &nbsp; <code>0.1.0-preview.1</code> · 源码预览 · <a href="LICENSE">MIT</a>
</p>

<p align="center"><strong>简体中文</strong> · <a href="README.en.md">English</a> · <a href="README.es.md">Español</a></p>

<p align="center"><strong><a href="docs/quickstart.md">快速开始</a></strong> &nbsp;·&nbsp; <a href="docs/first-service.md">接入第一个服务</a> &nbsp;·&nbsp; <a href="docs/user-guide.md">使用手册</a></p>

![工作台中的最近发布记录、目标环境、执行人与节点状态](docs/assets/workspace-demo.png)

*工作台预览 · 服务、人员和发布记录均为演示数据。*

## 一次发布，三处核对

| 在什么时候 | 团队可以核对什么 |
| --- | --- |
| 构建时 | 选定分支和提交，查看构建步骤与日志，从成功记录找到对应制品。 |
| 上生产前 | 查看预生产验证结果，以及每个目标节点最近发布的版本。操作者使用自己的动态码确认生产发布。 |
| 发布完成后 | 查看各节点的健康检查结果和失败步骤。需要回退时，选择历史制品重新部署。 |

一个版本可以同时包含前端、后端和 Flutter Android 模块。服务部署以节点结果为据，Android 交付以分发方的回执为据。版本小结会列出已发布、延期和待确认的模块。

## 在本机试用

准备 **JDK 21、Maven 3.9 和 Node.js 22 或 24**。本地预览使用 H2，你可以先在自己的电脑上登录、绑定动态码、熟悉工作台，之后再接入独立测试节点。

1. 在仓库根目录打开终端，构建后端并设置首次管理员密码。
2. 另开终端，在 `frontend/` 安装依赖并启动前端。
3. 打开本地地址，用 `admin` 登录。登录页右上角可以切换简体中文、English 和 Español。

[按你的操作系统查看完整启动命令 →](docs/quickstart.md)

## 文档与联系

[实例配置与 Logo](docs/source-baseline.md) · [首版路线图](docs/roadmap.md) · [贡献指南](CONTRIBUTING.md) · [安全报告](SECURITY.md)

**维护者**　[liuyufei19931109](https://github.com/liuyufei19931109) · [liuyufei@midatia.cn](mailto:liuyufei@midatia.cn)

项目交流和合作可以直接发邮件。可复现的问题或功能建议，欢迎提交到 [GitHub Issues](https://github.com/liuyufei19931109/versionplatform/issues)。
