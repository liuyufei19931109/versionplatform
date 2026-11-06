# Local quickstart

[简体中文](quickstart.md) · **English** · [Español](quickstart.es.md)

This guide is for version `0.1.0-preview.1`. By the end, you can sign in to the dashboard with your own administrator account, switch languages, and explore the services, builds, and version management pages. A fresh instance starts with no services or deployment records.

To try the platform locally, run the Java backend and the Vite frontend with the built-in H2 database. You will need a computer, two terminals, and an authenticator that generates six-digit TOTP codes. The first dependency installation requires access to the Maven and npm registries.

## 1. Get the source and tools

Download the repository as a source ZIP and extract it into an empty folder, or run `git clone` with the URL from the repository’s **Code** menu. Find the directory containing both `backend/pom.xml` and `frontend/package.json`. This is the **repository root**. Start both terminals there.

Install **JDK 21, Maven 3.9.x, and Node.js 22 or 24 with its bundled npm**. Check the versions:

```text
java -version
mvn -version
node --version
npm --version
```

The Java version reported by `mvn -version` should also be 21. If Windows PowerShell blocks `npm.ps1`, use `npm.cmd --version` and the `npm.cmd` commands below.

For this walkthrough, use a separate folder in your user profile for instance data. On the first run, choose a new directory and open fresh local terminals so that you do not inherit `SPRING_*` or `RELEASE_*` settings from another deployment. Use the same directory each time you start this instance.

## 2. Start the backend

In the first terminal, build the backend, then run the JAR from the instance directory. The default administrator username is `admin`. On first startup, set a password with **at least 12 characters and no more than 72 bytes when encoded as UTF-8**. The commands below hide your password as you type.

### Windows PowerShell

From the repository root, run:

```powershell
$repo = (Get-Location).Path
mvn -f backend/pom.xml -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Backend build failed. Check the Maven output.' }

$jar = Join-Path $repo 'backend/target/release-platform-0.1.0-preview.1.jar'
$instance = Join-Path $env:LOCALAPPDATA 'release-platform-preview'
New-Item -ItemType Directory -Force -Path $instance | Out-Null
$initialPassword = Read-Host 'Set the initial password for admin' -AsSecureString
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

### macOS / Linux (Bash)

Open Bash in the repository root. If your macOS terminal uses zsh, enter `bash` and press Return first. Run:

```bash
repo="$(pwd)"
mvn -f backend/pom.xml -DskipTests package && (
  set -e
  umask 077
  instance="$HOME/.local/share/release-platform-preview"
  mkdir -p "$instance"
  cd "$instance"
  read -r -s -p 'Set the initial password for admin: ' RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  printf '\n'
  export RELEASE_BOOTSTRAP_ADMIN_PASSWORD
  java -jar "$repo/backend/target/release-platform-0.1.0-preview.1.jar" \
    --server.address=127.0.0.1 --server.port=8080 --release.workspace=./workspaces
)
```

Leave the first terminal running. Open the [backend health endpoint](http://127.0.0.1:8080/api/health) in your browser. Its response should include `"status":"UP"` and the version number. Use the frontend address in the next step to sign in.

## 3. Start the frontend

In the **second terminal**, run the commands for your platform from the repository root. Vite proxies the page’s `/api` requests to the local backend.

**Windows PowerShell**

```powershell
cd frontend
npm.cmd ci
if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed. Check the npm output.' }
$env:VITE_API_PROXY = 'http://127.0.0.1:8080'
npm.cmd run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

**macOS / Linux**

```bash
cd frontend
npm ci && VITE_API_PROXY=http://127.0.0.1:8080 \
  npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

Open the [local dashboard](http://127.0.0.1:5173). If the [health check through the frontend proxy](http://127.0.0.1:5173/api/health) also returns `UP`, the browser can reach the backend through Vite. Both services listen only on the local loopback address.

## 4. Sign in and enroll your authenticator

1. Enter `admin` and the password from step 2 on the sign-in page. The first sign-in opens **Sign-in security**.
2. Choose **Start enrollment**. Scan the QR code in Google Authenticator or another TOTP-compatible app, or manually enter the key shown on the page. The QR code is valid for 10 minutes.
3. Enter the six digits currently shown in the authenticator and choose **Verify and enable**. Successful verification opens the dashboard. On your next sign-in, enter your username, password, and a fresh code.
4. Select 简体中文, Español, or English on the sign-in page or in the upper-right corner of the dashboard. Open **Services** or **Deployment history** to see the empty lists on a new instance.

Each verification code can be used only once. If you just enrolled or verified a code, wait for the next one before signing in again. Check that the clocks on both the backend computer and your phone are synchronized. The QR code and manual enrollment key are second-factor credentials. Keep them on your own devices.

## 5. Stop, restart, and back up the instance

Press `Ctrl+C` in the frontend terminal, then press `Ctrl+C` in the backend terminal and wait for the backend to exit. The file-based H2 database writes a backup during a clean shutdown. Forcibly terminating Java or losing power can prevent the backup from being written.

With the commands above, the following paths are relative to the instance directory:

| Path | Contents |
| --- | --- |
| `data/release-platform.mv.db` | H2 database containing user accounts, service settings, and task records |
| `data/credential-encryption.key`, `data/totp-encryption.key` | Local encryption keys needed to decrypt stored credentials and TOTP secrets |
| `data/backups/shutdown-*/` | H2 snapshot directory created during a clean shutdown |
| `workspaces/` | Workspace used by tasks that build from source |

Check that the newest backup directory contains both `COMPLETE` and `database.zip`. Before marking the backup complete, the platform copies each of the two encryption keys if it exists. Restrict access to the backups as well as the instance directory because they contain account data and keys. If you move the instance, copy file-based artifacts such as app packages separately. The H2 ZIP contains only the database.

**To restart**, use the same JAR, instance working directory, and arguments. Once the `admin` account exists, you can omit `RELEASE_BOOTSTRAP_ADMIN_PASSWORD`. Setting it again does not reset the existing password. For the frontend, run the startup command from step 3 in `frontend/`. You can skip `npm ci` if the lockfile and installed dependencies are unchanged.

To customize the paths, set `RELEASE_DATA_DIR` for H2 data and `RELEASE_SHUTDOWN_BACKUP_DIR` for shutdown backups. **The two encryption keys default to `./data/credential-encryption.key` and `./data/totp-encryption.key` relative to the current working directory.** Changing `RELEASE_DATA_DIR` alone does not move them. When migrating an existing instance, bring its original keys and point `--release.credential-key-file=/absolute/path` and `--release.totp-key-file=/absolute/path` to the appropriate files. MySQL is also available as an optional configuration. The shutdown backup described here applies only to file-based H2.

## Startup troubleshooting

| What you see | What to check |
| --- | --- |
| Compilation reports that release 21 is unsupported | Check the Java home reported by `mvn -version`. Correct `JAVA_HOME`, then reopen the terminal. |
| The backend exits immediately and asks for the initial administrator password | In the terminal that starts Java, set a password that meets the length requirements and start again from the same instance directory. |
| Port 8080 or 5173 is already in use | If you change the backend’s `--server.port`, update `VITE_API_PROXY` and restart Vite. If you change only the frontend’s `--port`, open the page on the new port. |
| The page opens, but sign-in or API requests report a connection error | Check the backend health endpoint and then the health endpoint through the frontend proxy. Confirm that the backend is ready and that the proxy points to the correct address and port. |
| After a restart the instance looks new, or existing credentials cannot be decrypted | Check the startup directory, data directory, and key paths. Return to the original instance directory and keep the original database and keys. |
| You want to deploy a real service | Prepare a trusted test repository, the tools for your chosen build method, and an image registry. Then register your own Linux test node. Current builds and some runtime scripts depend on Linux, Docker, and SSH. Opening the local dashboard on Windows or macOS is separate from executing a Linux deployment. |

After the local walkthrough, follow [Connect your first service](first-service.en.md) to configure your own test resources.
