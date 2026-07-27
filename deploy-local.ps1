param(
    [string]$HostName = '129.204.9.242',
    [string]$ServerUser = 'root',
    [string]$KeyPath = "$HOME\.ssh\id_ed25519",
    [string]$RemoteAppDir = '/root/docker/inquisition/app',
    [string]$RemoteEnvFile = '/root/docker/inquisition/.env',
    [string]$ContainerName = 'inquisition',
    [string]$Image = 'dazecake/inquisition:v1.2.8',
    [int]$ChunkSizeMB = 4,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }

function Get-Java11Home {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        $version = (cmd /c """$env:JAVA_HOME\bin\java.exe"" -version 2>&1" | Out-String)
        if ($version -match 'version "11\.') { return $env:JAVA_HOME }
    }
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        $javaHomePath = Split-Path -Parent (Split-Path -Parent $java.Source)
        $version = (cmd /c """$javaHomePath\bin\java.exe"" -version 2>&1" | Out-String)
        if ($version -match 'version "11\.') { return $javaHomePath }
    }
    $candidate = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk-11*' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    throw 'JDK 11 not found.'
}

function Get-BootJar {
    $jar = Get-ChildItem (Join-Path $PSScriptRoot 'build\libs\Inquisition-*.jar') -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw 'build/libs/Inquisition-*.jar not found.' }
    return $jar
}

if (-not (Test-Path $KeyPath)) { throw "SSH key not found: $KeyPath" }

$sshArgs = @('-o','BatchMode=yes','-o','StrictHostKeyChecking=no','-i',$KeyPath)
$remoteUploadJar = "$RemoteAppDir/Inquisition-upload.jar"

Write-Step 'Check SSH'
& ssh @sshArgs "$ServerUser@$HostName" 'echo __SSH_OK__'
if ($LASTEXITCODE -ne 0) { throw 'SSH failed.' }

if (-not $SkipBuild) {
    Write-Step 'Build bootJar with JDK 11'
    $javaHome = Get-Java11Home
    $oldJavaHome = $env:JAVA_HOME
    $oldPath = $env:Path
    try {
        $env:JAVA_HOME = $javaHome
        $env:Path = "$javaHome\bin;$oldPath"
        & (Join-Path $PSScriptRoot 'gradlew.bat') --no-daemon bootJar
        if ($LASTEXITCODE -ne 0) { throw 'bootJar failed.' }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:Path = $oldPath
    }
}

$jar = Get-BootJar
$localHash = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower()

Write-Step 'Remove remote upload temp'
& ssh @sshArgs "$ServerUser@$HostName" "rm -f '$remoteUploadJar'"
if ($LASTEXITCODE -ne 0) { throw 'Remote upload cleanup failed.' }

Write-Step 'Upload jar via scp'
& scp @sshArgs $jar.FullName "$ServerUser@$HostName`:$remoteUploadJar"
if ($LASTEXITCODE -ne 0) { throw 'Jar upload failed.' }

Write-Step 'Verify and deploy'
$deployCommand = @'
set -e
cd '{0}'
ACTUAL_HASH=$(sha256sum Inquisition-upload.jar | cut -d' ' -f1)
if [ "$ACTUAL_HASH" != '{1}' ]; then
  echo "SHA256 mismatch: expected {1} got $ACTUAL_HASH" >&2
  exit 1
fi
unzip -tqq Inquisition-upload.jar >/dev/null
BACKUP_JAR="Inquisition.jar.bak.manual.$(date +%Y%m%d_%H%M%S)"
cp -f Inquisition.jar "$BACKUP_JAR"
mv -f Inquisition-upload.jar Inquisition.jar
ENV_FILE_OPTION=
if [ -f '{4}' ]; then
  ENV_FILE_OPTION="--env-file={4}"
fi
start_container() {{
  docker run -d --name {2} --restart always -p 2000:2000 $ENV_FILE_OPTION -v /root/docker/inquisition:/config -v {0}/Inquisition.jar:/Inquisition.jar {3} >/dev/null
}}
wait_healthy() {{
  for i in $(seq 1 30); do
    if curl -k -fsS https://127.0.0.1:2000/v3/api-docs >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
  done
  return 1
}}
docker stop {2} >/dev/null 2>&1 || true
docker rm {2} >/dev/null 2>&1 || true
if start_container && wait_healthy; then
  echo __DEPLOY_OK__
  exit 0
fi
docker logs --tail 80 {2} >&2 || true
echo 'New deployment failed; restoring previous JAR.' >&2
docker rm -f {2} >/dev/null 2>&1 || true
cp -f Inquisition.jar "Inquisition.jar.failed.$(date +%Y%m%d_%H%M%S)"
cp -f "$BACKUP_JAR" Inquisition.jar
if start_container && wait_healthy; then
  echo __ROLLBACK_OK__ >&2
  exit 1
fi
docker logs --tail 80 {2} >&2 || true
echo 'Automatic rollback failed.' >&2
exit 2
'@ -f $RemoteAppDir, $localHash, $ContainerName, $Image, $RemoteEnvFile
$deployCommand = $deployCommand -replace "`r`n", "`n"
& ssh @sshArgs "$ServerUser@$HostName" $deployCommand
if ($LASTEXITCODE -ne 0) { throw 'Remote deploy failed.' }

Write-Host ''
Write-Host "Deploy done: $($jar.Name)" -ForegroundColor Green
Write-Host "SHA256: $localHash" -ForegroundColor Green
