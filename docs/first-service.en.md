# Deploy your first service

[简体中文](first-service.md) · **English** · [Español](first-service.es.md)

Start with the [local quickstart](quickstart.en.md) if you haven’t started the platform yet. In this guide, you’ll deploy a small Node HTTP service to a dedicated Linux staging node. Once it’s running, you can trace the release from the commit you selected to the image version, target node, and health check result. Replace every `.invalid` hostname with one of your own test resources.

## 1. Prepare the build host, repositories, and test node

| Resource | What you need |
| --- | --- |
| Host running the platform backend | With **Local build**, the account running the backend needs a writable workspace, Git, an OpenSSH client, and access to a Docker engine that can pull the base image. If the backend runs in a container, verify the tools, permissions, and workspace from inside that container. |
| Git repository and image registry | Use a dedicated test repository and a separate image namespace. The build account needs read access to the Git repository and permission to push to the image repository. The target node needs permission to pull the image. Give the Docker-enabled build account access only to code you trust. |
| Dedicated test node | Install an SSH server, Docker, Compose V2, and `curl`. The deployment account needs access to the test project directory and permission to run Docker. Use a single staging node for this first deployment. |

**Create a remote `master` branch.** At the start of each build and on every retry, the platform checks that the selected commit includes the latest commit on the remote `master` branch. For a new test repository, commit the two files below directly to `master` and make that branch available on the remote. Choosing `main` as the **Default branch** on the form changes the preselection only. The same `master` check still applies.

Place `server.mjs` at the root of the test repository.

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

Add a `Dockerfile` in the same directory.

```dockerfile
FROM node:22-alpine
WORKDIR /app
COPY server.mjs .
USER node
EXPOSE 8080
CMD ["node", "server.mjs"]
```

On the test node, create a separate project directory such as `/srv/release-demo`. Save the following file there as `compose.yaml`, replacing the image address with your own. During deployment, the platform overrides the image reference with the selected artifact, pulls that image, and recreates `demo-web`. The node checks the container directly, so you can leave the port unpublished.

```yaml
services:
  demo-web:
    image: registry.example.invalid/lab/demo-web:bootstrap
```

## 2. Configure the registry and SSH

1. As an administrator, open **General settings → Image registry**. Enter the test registry hostname, such as `registry.example.invalid`, and its dedicated credentials. You’ll enter the full image repository path in the service settings later.
2. In the OpenSSH configuration for the account running the backend, set `StrictHostKeyChecking yes` for the test target. Verify the host public key fingerprint with the node administrator through a trusted channel. Then add the verified public key to that account’s `known_hosts` under the hostname and port used for the connection. For a non-default port, use `[hostname]:port`. If you access Git over SSH, configure trust for the Git host separately and use a read-only repository key.
3. Open **Servers → Add server**. Choose **Direct SSH** and **SSH Key**. Enter the test node’s address, deployment account, port, and path to the dedicated private key **on the backend host**. Set the Compose project directory to `/srv/release-demo` and the filename to `compose.yaml`. Use public key authentication for this example. The current password connection mode lacks strict host key verification.
4. Save and select **Test connection**. Both SSH and Compose should pass, and the service list should include `demo-web`. This check reads the Compose configuration. The build and deployment will verify push and pull permissions respectively.

## 3. Register the service and deploy

| Where to go | Settings for this example |
| --- | --- |
| **Services → New service** | Choose a name and use `demo-web` as the service identifier. Select **Frontend project** and **Local build**, enter your example Git repository URL, and set **Default branch** to `master`. |
| **Image settings** in the service details | Under **Frontend delivery method**, choose **Node app (repository Dockerfile)**. Set **Dockerfile path** to `Dockerfile` and **Full image repository address** to `registry.example.invalid/lab/demo-web`, substituting your registry. The repository root is the build context. The image itself supplies Node for the example application. |
| **Environments and clusters** in the service details | Choose **Staging environment**, enable **Allow deployments**, and select only the test node you just added. Choose Docker Compose as the deployment method. Set **Compose service name** to `demo-web`, **Health check port inside container** to `8080`, and **Health check URL** to `/healthz`. Use the platform-generated start command and save. |

Open **New build**, select the service and branch, then review and confirm the latest commit. Select **Build only** and queue the build. After it succeeds, open **New deployment** and select the artifact from that build, the staging environment, and the test node. Review your choices and submit.

Open the task details in **Deployment history**. Check three things against the same release: the build record shows the commit you confirmed, the deployment log names `demo-web` as the target, and the node passed its health check. In **Runtime overview**, run a check to compare the actual container and image with the most recent deployment record.

If a step fails, open that step’s log first. For a branch error, check the remote `master` and the selected commit. For a push failure, verify the full image repository path and permissions. For a failed health check, verify the container port, the `/healthz` endpoint, and that the application listens on `0.0.0.0`. After changing the source code, create a **new build**. Retrying the original task reuses its locked commit.

Continue with the [user guide](user-guide.en.md).
