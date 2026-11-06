export type ServiceType = 'BACKEND' | 'FRONTEND' | 'FLUTTER_ANDROID'
export type BuildEngine = 'GITHUB' | 'LOCAL'

export type MobileEnvironmentName = 'dev' | 'test' | 'prod'
export interface MobileEnvironmentProfile {
  enabled: boolean
  applicationId: string
  expectedCertificateSha256: string
  signingProfile: string
  buildMode: 'DEBUG' | 'RELEASE'
  allowDebugSigning: boolean
  flavor?: string
  target?: string
  versionName?: string
  versionCode?: number
  apkPath?: string
  aabPath?: string
  dartDefines: Record<string, string>
  environmentVariables: Record<string, string>
}
export interface MobileSigningProfile { name: string; certificateSha256: string; debugCertificate: boolean; ready: boolean; message: string }
export interface MobileBuildConfig {
  versionPolicy?: 'AUTO' | 'MANUAL'
  environments?: Partial<Record<MobileEnvironmentName, MobileEnvironmentProfile>>
  outputFormat?: 'APK' | 'AAB'
  apkPath?: string
  flutterVersion: string
  applicationId: string
  expectedCertificateSha256: string
  flavor?: string
  target?: string
  versionName?: string
  versionCode?: number
  signingProfile?: string
  buildTimeoutSeconds: number
  aabPath: string
}

export interface Service {
  displayIcon?: string
  id?: number | string
  name: string
  code?: string
  serviceType?: ServiceType
  buildEngine?: BuildEngine
  mobileBuild?: MobileBuildConfig
  imageBuildMode?: 'STATIC_NGINX' | 'REPOSITORY_DOCKERFILE'
  description?: string
  createdBy?: string
  status?: string | boolean
  gitUrl?: string
  defaultBranch?: string
  gitAuthType?: string
  gitCredential?: string
  buildTool?: string
  nodeVersion?: string
  javaVersion?: string
  buildCommand?: string
  workDirectory?: string
  jarPath?: string
  buildTimeout?: number
  dockerfilePath?: string
  imageRepository?: string
  imageName?: string
  registryCredential?: string
  deployHost?: string
  targetUser?: string
  deployPort?: number
  containerPort?: number
  environment?: string
  healthCheckUrl?: string
  startupArgs?: string
  envVariables?: Array<{ key: string; value: string; secret?: boolean }>
  [key: string]: unknown
}

export interface DeploymentServer {
  id?: number | string
  name: string
  host: string
  connectionMode?: 'DIRECT' | 'SSH_CONFIG' | null
  sshConnectTimeoutSeconds?: number | null
  sshCommandTimeoutSeconds?: number | null
  businessUrl?: string | null
  port: number
  username: string
  authType: 'SSH_KEY' | 'PASSWORD'
  privateKeyPath?: string
  password?: string
  passwordConfigured?: boolean
  credentialHint?: string
  description?: string
  composeDirectory?: string
  composeFile?: string
  composeUseSudo?: boolean
  enabled: boolean
}

export interface ServiceEnvironment {
  id?: number | string
  serviceId?: number | string
  environmentName: 'dev' | 'test' | 'prod'
  serverIds: Array<number | string>
  hostPort: number
  containerPort: number
  containerName?: string
  envFilePath?: string
  dockerNetwork?: string
  restartPolicy?: 'NO' | 'UNLESS_STOPPED' | 'ALWAYS' | 'ON_FAILURE'
  deploymentMode: 'DOCKER_RUN' | 'DOCKER_COMPOSE'
  composeService?: string
  startCommand?: string
  healthCheckUrl?: string
  envJson?: string
  envVariables: Array<{ key: string; value: string; secret?: boolean }>
  enabled: boolean
}

export interface Artifact {
  id: number | string
  serviceId?: number | string
  buildTaskId?: number | string
  version: string
  branch?: string
  commitId?: string
  imageUrl?: string
  createdBy?: string
  createdAt?: string
  format?: 'APK' | 'AAB'
  buildEnvironmentName?: MobileEnvironmentName | null
  buildMode?: 'DEBUG' | 'RELEASE' | null
  applicationId?: string
  versionName?: string
  versionCode?: number
}

export interface ReleaseStep {
  id?: number | string
  name?: string
  stepName?: string
  code?: string
  status?: string
  message?: string
  log?: string
  logText?: string
  rawLog?: string
  startedAt?: string
  finishedAt?: string
}

export interface GitCommitPreview {
  branch: string
  commitId: string
  shortCommitId: string
  authorName: string
  authorEmail: string
  committedAt: string
  title: string
  message: string
  changedFiles: string[]
  changedFilesTruncated: boolean
}

export interface Release {
  id: number | string
  artifactId?: number | string
  createdBy?: string
  finishedAt?: string
  startedAt?: string
  serviceId?: number | string
  serviceName?: string
  serviceKey?: string
  serviceType?: ServiceType
  deliveryType?: 'MOBILE' | 'SERVER'
  mobileOutputFormat?: 'APK' | 'AAB' | null
  mobileBuildMode?: 'DEBUG' | 'RELEASE' | null
  mobileBuildEngine?: 'LOCAL' | 'GITHUB' | null
  frontendBuildEngine?: 'LOCAL' | 'GITHUB' | null
  backendBuildEngine?: 'LOCAL' | 'GITHUB' | null
  versionName?: string
  versionCode?: number
  preBuildTaskId?: number
  branch?: string
  commitId?: string
  environment?: string
  status?: string
  description?: string
  createdAt?: string
  updatedAt?: string
  logs?: string
  rawLog?: string
  steps?: ReleaseStep[]
  [key: string]: unknown
}
