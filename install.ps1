# Confluence RAG — installer for Windows
#
# Usage:
#   irm https://raw.githubusercontent.com/martinvidec/openaustria-confluence-rag/main/install.ps1 | iex
#
# Environment overrides:
#   $env:InstallPrefix   Override install dir (default: $env:LOCALAPPDATA\confluence-rag)
#   $env:Version         Install a specific release (e.g. v0.1.0), default: latest
#
# This script only installs the app itself. It does NOT install Java, Ollama
# or Qdrant — run `confluence-rag doctor` after install to verify prerequisites.

$ErrorActionPreference = 'Stop'

$Repo = 'martinvidec/openaustria-confluence-rag'
$InstallDir = if ($env:InstallPrefix) { $env:InstallPrefix } else { Join-Path $env:LOCALAPPDATA 'confluence-rag' }
$Version = if ($env:Version) { $env:Version } else { 'latest' }

function Die([string]$Msg) {
    Write-Host "error: $Msg" -ForegroundColor Red
    exit 1
}

function Write-Info([string]$Msg) { Write-Host ">> $Msg" -ForegroundColor Cyan }

Write-Info 'Confluence RAG installer (Windows)'

# ------------------------------- fetch release -----------------------------

$apiUrl = if ($Version -eq 'latest') {
    "https://api.github.com/repos/$Repo/releases/latest"
} else {
    "https://api.github.com/repos/$Repo/releases/tags/$Version"
}

Write-Info "Fetching release info from $apiUrl"
try {
    $release = Invoke-RestMethod -Uri $apiUrl -TimeoutSec 30 -UseBasicParsing
} catch {
    Die "cannot reach GitHub API (version '$Version' may not exist yet)"
}

$tag = $release.tag_name
$asset = $release.assets | Where-Object { $_.name -like '*-windows.zip' } | Select-Object -First 1
if (-not $asset) { Die "no -windows.zip asset found in release $tag" }

Write-Info "Installing confluence-rag $tag"

# ------------------------------- backup existing ---------------------------

$backup = $null
if (Test-Path $InstallDir) {
    $backup = "$InstallDir.bak.$([Guid]::NewGuid().ToString('N').Substring(0,8))"
    Write-Info "Existing install at $InstallDir — moving to $backup"
    Move-Item -Path $InstallDir -Destination $backup -Force
}

# ------------------------------- download + extract ------------------------

$tmp = Join-Path $env:TEMP "confluence-rag-install-$([Guid]::NewGuid())"
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

try {
    $zipPath = Join-Path $tmp 'confluence-rag.zip'
    Write-Info "Downloading $($asset.browser_download_url)"
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zipPath -UseBasicParsing

    Write-Info "Extracting to $InstallDir"
    Expand-Archive -Path $zipPath -DestinationPath $tmp -Force
    $inner = Get-ChildItem -Path $tmp -Directory | Where-Object { $_.Name -like 'confluence-rag-*' } | Select-Object -First 1
    if (-not $inner) { Die 'unexpected archive layout' }

    New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
    Copy-Item -Path (Join-Path $inner.FullName '*') -Destination $InstallDir -Recurse -Force

    # Ensure data + logs dirs exist
    @('data', 'logs') | ForEach-Object {
        $p = Join-Path $InstallDir $_
        if (-not (Test-Path $p)) { New-Item -ItemType Directory -Path $p -Force | Out-Null }
    }
} finally {
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
}

# ------------------------------- migrate config ----------------------------

if ($backup -and (Test-Path (Join-Path $backup 'config\config.env'))) {
    Write-Info "Preserving previous config from $backup"
    $cfgDir = Join-Path $InstallDir 'config'
    New-Item -ItemType Directory -Path $cfgDir -Force | Out-Null
    Copy-Item -Path (Join-Path $backup 'config\config.env') -Destination (Join-Path $cfgDir 'config.env') -Force
    $syncState = Join-Path $backup 'data\sync-state.json'
    if (Test-Path $syncState) {
        Copy-Item -Path $syncState -Destination (Join-Path $InstallDir 'data\sync-state.json') -Force
    }
}

# ------------------------------- add to PATH -------------------------------

$binDir = Join-Path $InstallDir 'bin'
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$parts = if ($userPath) { $userPath.Split(';') } else { @() }
if ($parts -notcontains $binDir) {
    $newPath = if ($userPath) { "$userPath;$binDir" } else { $binDir }
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
    Write-Info "Added $binDir to your User PATH"
    $pathReminder = $true
} else {
    $pathReminder = $false
}

# ------------------------------- done --------------------------------------

Write-Host ''
Write-Host " ✓  Confluence RAG $tag installed to $InstallDir" -ForegroundColor Green
Write-Host ''

if ($pathReminder) {
    Write-Host ' !  PATH was updated. Open a new PowerShell window to pick up the change.' -ForegroundColor Yellow
    Write-Host ''
}

Write-Host 'Next steps:'
Write-Host '  confluence-rag init        # configure Confluence URL, auth, spaces, pull models'
Write-Host '  confluence-rag doctor      # verify Java, Ollama, Qdrant'
Write-Host '  confluence-rag start       # run on http://localhost:8080'
Write-Host '  confluence-rag ingest      # trigger initial ingest'
Write-Host ''
Write-Host 'Prerequisites (install separately if missing):'
Write-Host '  Java 17+   https://adoptium.net/  or  winget install EclipseAdoptium.Temurin.17.JDK'
Write-Host '  Ollama     https://ollama.com/download'
Write-Host '  Qdrant     https://qdrant.tech/documentation/quick-start/'
Write-Host ''
Write-Host 'More:'
Write-Host '  confluence-rag help'
Write-Host "  https://github.com/$Repo"
