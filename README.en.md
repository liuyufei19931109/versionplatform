<h1 align="center">Release Platform</h1>

<p align="center"><strong>A self-hosted release workspace, from Git commit to production</strong><br>
Pin a commit when you build, review the artifact and targets before deployment, and see the outcome on each node.</p>

<p align="center">
  <a href="https://github.com/liuyufei19931109/versionplatform/actions/workflows/checks.yml"><img src="https://github.com/liuyufei19931109/versionplatform/actions/workflows/checks.yml/badge.svg?branch=main" alt="Build and test status"></a>
  &nbsp; <code>0.1.0-preview.1</code> · Source preview · <a href="LICENSE">MIT</a>
</p>

<p align="center"><a href="README.md">简体中文</a> · <strong>English</strong> · <a href="README.es.md">Español</a></p>

<p align="center"><strong><a href="docs/quickstart.en.md">Quick start</a></strong> &nbsp;·&nbsp; <a href="docs/first-service.en.md">Connect your first service</a> &nbsp;·&nbsp; <a href="docs/user-guide.en.md">User guide</a></p>

![Release dashboard with sample deployment records, target environments and operators](docs/assets/workspace-demo.en.png)

*Workspace preview · All services, people, and deployment records shown here are fictional.*

## What to check during a release

| Stage | What you can verify |
| --- | --- |
| Build | Select a branch and commit, follow the build steps and logs, and find the artifact produced by a successful build. |
| Promote to production | Review staging verification and the last deployment on each target node. The operator confirms the production deployment with their own one-time code. |
| Review the result | Check health results by node and inspect failed steps. To roll back, select an earlier artifact and deploy it again. |

A release plan can include frontend, backend, and Flutter Android modules. Services report deployment results by node. Android delivery is confirmed by the distributor. The release summary records what shipped, what was deferred, and what still needs confirmation.

## Try it locally

Install **JDK 21, Maven 3.9, and Node.js 22 or 24**. The local preview uses H2. You can sign in, pair an authenticator, and explore the workspace before connecting a separate test server.

1. From the repository root, build the backend and set the initial administrator password.
2. In a second terminal, install the dependencies in `frontend/` and start the frontend.
3. Open the local URL and sign in as `admin`. Use the selector at the top right of the sign-in page to choose English, Spanish, or Simplified Chinese.

[View setup commands for your operating system →](docs/quickstart.en.md)

## Documentation and contact

[Instance settings and logo (Chinese)](docs/source-baseline.md) · [Roadmap (Chinese)](docs/roadmap.md) · [Contributing (Chinese)](CONTRIBUTING.md) · [Security policy (Chinese)](SECURITY.md)

**Maintainer**　[liuyufei19931109](https://github.com/liuyufei19931109) · [liuyufei@midatia.cn](mailto:liuyufei@midatia.cn)

Email the maintainer to discuss the project or a collaboration. For reproducible bugs and feature requests, use [GitHub Issues](https://github.com/liuyufei19931109/versionplatform/issues).
