# 贡献指南

欢迎提交问题、翻译改进和代码。当前版本聚焦自托管团队的构建与发布流程。准备新增一类部署目标或改变发版行为时，建议先开 Issue，写明使用场景、现有流程和预期结果。功能范围见[项目说明](README.md)。

## 报告问题

附上版本号或提交哈希、操作系统、复现步骤、预期结果和实际结果。界面问题请写明浏览器和当前语言。使用虚构的服务名、地址和账号复现，粘贴与问题直接相关的日志片段。访问控制绕过、凭据泄露等漏洞请走[安全报告渠道](SECURITY.md)。

## 本地检查

以下命令分别从仓库根目录开始执行。首次安装依赖需要访问 npm 公共仓库或 Maven Central。运行这些检查不需要启动发版平台、连接部署节点或配置云服务凭据。

**前端**使用 Node.js 24.20.0 和 npm。`npm ci` 按仓库锁文件安装，`npm test` 执行全部前端测试，`npm run build` 检查 TypeScript 并生成静态文件。

```sh
cd frontend
npm ci
npm test
npm run build
```

**后端**使用 JDK 21、Maven 3.9 和 Python 3。下面的 Maven 命令编译所有 Java 测试源码，运行 CI 选定的八个测试类并打包 JAR。这组用例覆盖生产发布准入、模块权限、部署复核、构建方式、模拟的 GitHub 构建、平台 Logo 和接口错误。Python 用例用临时目录和模拟命令检查运行脚本，部分文件访问用例仅在 Linux 上执行。

```sh
cd backend
mvn --batch-mode --no-transfer-progress "-Dtest=ApiExceptionHandlerTest,BrandingLogoTest,DeploymentReviewTest,GitHubBackendBuildServiceTest,GitHubFrontendBuildServiceTest,MenuPermissionsTest,ProductionGateTest,ServiceBuildEngineSelectionTest" package
python3 -m unittest discover -s src/test/python -p 'test_*.py'
```

修改 CI 范围之外的 Java 代码时，另跑对应测试类，例如 `mvn -Dtest=VersionGitRepositoryTest test`。完整 Java 套件可用 `mvn test` 运行。部分移动构建测试使用 Android SDK 35，涉及真实镜像仓库、SSH 或回滚的变更请在自己的独立测试环境验证。持续集成的命令和运行环境见 [Checks 工作流](.github/workflows/checks.yml)。

## 提交 Pull Request

1. 从 `main` 建立工作分支，每个 PR 集中处理一个问题。
2. 行为修改请补上相关回归用例。修改界面文案时，同步维护简体中文、英文和西班牙语，保留参数占位符，以及用户输入或原始日志的内容。
3. 在 PR 中描述用户可见的变化、关联 Issue 和实际运行的检查。界面修改可附使用虚构数据的截图。需要部署方迁移配置时，请给出迁移步骤。
