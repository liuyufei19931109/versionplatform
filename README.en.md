# Release Platform

**A self-hosted workspace for building applications, deploying services, and checking what shipped in each release.**

[简体中文](README.md) · English · [Español](README.es.md)　|　[Local setup (中文)](docs/quickstart.md) · [Connect a service (中文)](docs/first-service.md) · [User guide (中文)](docs/user-guide.md) · [Project checks](.github/workflows/checks.yml)

Before a deployment, see the selected commit, artifact, and target nodes. Afterwards, open the same record to see who started it, which nodes passed their health checks, and where a failed task stopped.

- **Trace a build.** Select a branch and commit, then follow the build steps, logs, and resulting artifact.
- **Promote a tested artifact.** Review each target's deployment history. Production deployments require staging verification and the operator's own one-time code. Select a previous artifact to redeploy it.
- **Review a release across modules.** Group frontend, backend, and Flutter Android modules in one release plan. Services report deployment and health-check results. Android delivery uses an acknowledgment from the distributor. The release summary records what shipped, what was deferred, and what still needs confirmation.

**Current version** `0.1.0-preview.1` · Source preview. The interface supports English, Spanish, and Simplified Chinese. Start in an isolated test environment.

## Quick start

Install JDK 21, Maven 3.9, and Node.js 22 or 24. Download or clone the repository, then open two terminals at its root. The local preview uses an H2 database and can start without access to your application servers.

In the first terminal, start the backend. The Bash example prompts for the initial administrator password without printing it. Use at least 12 characters and at most 72 UTF-8 bytes.

```bash
repo="$(pwd)"
mvn -f backend/pom.xml -DskipTests package && (
  set -e
  umask 077
  instance="$HOME/.local/share/release-platform-preview"
  mkdir -p "$instance"
  cd "$instance"
  read -r -s -p 'Initial admin password: ' RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  printf '\n'
  export RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  java -jar "$repo/backend/target/release-platform-0.1.0-preview.1.jar" \
    --server.address=127.0.0.1 --server.port=8080 --release.workspace=./workspaces
)
```

In the second terminal, start the frontend.

```bash
cd frontend
npm ci
VITE_API_PROXY=http://127.0.0.1:8080 npm run dev -- --host 127.0.0.1 --strictPort
```

Open `http://127.0.0.1:5173`, sign in as `admin` with the password you chose, and pair an authenticator app. On Windows, follow the [PowerShell instructions (中文)](docs/quickstart.md). You can change the interface language before signing in.

## Workspace preview

![Release dashboard with sample services, deployment records, and the execution queue](docs/assets/workspace-demo.png)

*All services, people, and records in this screenshot are fictional.*

Generic Dockerfile builds, registration of images from external CI, and first-time connection checks are on the [roadmap (中文)](docs/roadmap.md). See the [contribution guide](CONTRIBUTING.md) and [security policy](SECURITY.md) before opening a report.
