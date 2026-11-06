# User guide

[简体中文](user-guide.md) · **English** · [Español](user-guide.es.md)

Start with a **service**. A **build task** produces an **artifact**, and each deployment of that artifact creates a **deployment record**. For your first deployment, connect a separate staging node by following [Connect your first service](first-service.en.md).

## Where to start

| Task | Where to go |
| --- | --- |
| Set up accounts and grant access | Administrators manage users and menu permissions in **General settings**. On first sign-in, each user opens **Sign-in security**, selects **Start enrollment**, scans the QR code and enters the six-digit code from their own authenticator. |
| Connect a repository, environments and nodes | In **Services**, configure the repository, build method and environments to enable. In **Servers**, configure SSH access and the remote Compose project. Grant permission to change build or startup commands only to people you trust to run commands on the build host or target node. |
| Build a new version | In **New build**, select the service and branch, then verify the commit. **Build only** saves the artifact for later. If you select a development or staging target, a successful build automatically queues the deployment. The task continues after you close the page. |
| Deploy a previous successful build | In **New deployment**, select **an artifact from a successful build of that service on the platform**, then select the environment and target nodes. Check the target list. The wizard will ask you to review it again if a deployment task already exists or you selected an older version. |
| Review a task or inspect a running service | **Build history** and **Deployment history** show the steps, logs, operator and result for each task. In **Runtime overview**, inspect the container and image on each node. Open **Service logs** to read application log files or the container’s standard output. |

## Choose a build method

- **Backend services.** In local build mode, the build host runs the configured application build command, then builds the image using the repository’s Dockerfile. Install the JDK and Maven versions required by that command, or the Gradle toolchain the project uses. Docker on the build host needs permission to build and push images.
- **Frontend services.** Choose between a static site build and a repository Dockerfile. For a static site, the host runs the project’s Node.js build command and packages the output in an Nginx image. With a repository Dockerfile, its build steps install the dependencies and build the application inside the image build environment.
- **Flutter Android.** Register the app under **App releases**. In the app’s build settings, configure the package name, signing, Flutter version and optional build parameters for each environment. Local builds require Flutter, the Android SDK, a JDK, signing materials and the verification tools. For external distribution, fetch the exact APK or AAB pinned for the delivery, distribute it, then confirm the result.
- This source preview includes the platform itself. To use GitHub cloud builds, provide a separate build repository, workflows and authorization. Mini programs are disabled by default. Generic Dockerfile services, registration of images from external CI and first-time setup checks are planned.

## Deploy to production or roll back

1. Before an artifact’s first production deployment, deploy **that same artifact** to staging and wait for the node health checks to pass. Production accepts artifacts that passed staging or were previously deployed successfully to production.
2. Start a production deployment from a successful staging entry in **Deployment history**, or select the artifact in **New deployment**. Verify the service, version, production environment and target nodes. Confirm with the authenticator code for **the account you are signed in with**. Retrying a failed production task requires a fresh verification.
3. To roll back, select the artifact from a previous successful deployment and check which production nodes should switch back. Keep the historical image tags and their contents in the registry. Overwriting a tag or deleting its image can make a rollback impossible. This version pulls images by reference. Deploying by immutable digest is planned.
4. A deployment record describes the outcome of a particular operation. To see which image a node is running **now**, check it again in **Runtime overview**. Cancelling a running task stops subsequent steps. Check the node separately for changes that already took effect.

## Common tasks

- **Recover from a failed build.** Find the first failed step in the task details. If the network or repository was temporarily unavailable, retry when access returns. If the source has changed, open **New build** and select and confirm the latest commit.
- **Check version branches.** In **Versions**, associate multiple services with their version branches. Version planning uses the remote `master` branch as its baseline, and the build verifies the baseline again. Automatic merging back to `master` after production deployment is disabled by default. If you enable it, grant Git write access separately and check the merge result.
- **Read logs and reload configuration.** **Runtime overview** reads node status on demand. **Update configuration and restart** reads the server’s current Compose configuration and environment settings, then recreates the selected service with its existing image. Expect a brief interruption. Verify the target container first. In production, you must also confirm with an authenticator code.
- **Back up runtime data.** Keep staging and production registry credentials, SSH keys, data directories and image namespaces separate. Back up the database, the credential encryption key and the authenticator encryption key together so they can be restored as a set. Store runtime data, signing files, backups and real logs outside the source repository.
