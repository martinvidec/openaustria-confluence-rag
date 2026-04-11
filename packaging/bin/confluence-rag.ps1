# Confluence RAG — CLI wrapper (Windows / PowerShell)
#
# Subcommands: init, doctor, start, stop, status, logs, config,
# ingest, update, uninstall, version, help

$ErrorActionPreference = 'Stop'

# ------------------------------- paths -------------------------------------

$BinDir      = Split-Path -Parent $MyInvocation.MyCommand.Path
$InstallDir  = Split-Path -Parent $BinDir
$LibJar      = Join-Path $InstallDir 'lib\confluence-rag.jar'
$ConfigDir   = Join-Path $InstallDir 'config'
$ConfigFile  = Join-Path $ConfigDir 'config.env'
$DataDir     = Join-Path $InstallDir 'data'
$LogDir      = Join-Path $InstallDir 'logs'
$LogFile     = Join-Path $LogDir 'confluence-rag.log'
$ErrFile     = Join-Path $LogDir 'confluence-rag.err.log'
$PidFile     = Join-Path $InstallDir 'confluence-rag.pid'
$VersionFile = Join-Path $InstallDir 'VERSION'

$GitHubRepo       = 'martinvidec/openaustria-confluence-rag'
$AppPort          = 8080
$AppUrl           = "http://localhost:$AppPort"
$RequiredJavaMajor = 17

# ------------------------------- helpers -----------------------------------

function Write-Err([string]$Msg)  { Write-Host "error: $Msg" -ForegroundColor Red }
function Write-Warn([string]$Msg) { Write-Host "warn:  $Msg" -ForegroundColor Yellow }
function Write-Info([string]$Msg) { Write-Host ">> $Msg" -ForegroundColor Cyan }
function Write-Ok([string]$Msg)   { Write-Host " ✓  $Msg" -ForegroundColor Green }
function Write-Fail([string]$Msg) { Write-Host " ✗  $Msg" -ForegroundColor Red }

function Die([string]$Msg) {
    Write-Err $Msg
    exit 1
}

function Ensure-Dirs {
    @($DataDir, $LogDir, $ConfigDir) | ForEach-Object {
        if (-not (Test-Path $_)) { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
    }
}

function Require-Config {
    if (-not (Test-Path $ConfigFile)) {
        Die "config not found. Run 'confluence-rag init' first."
    }
}

function Load-Config {
    Require-Config
    Get-Content $ConfigFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { return }
        $parts = $line.Split('=', 2)
        if ($parts.Count -eq 2) {
            $name = $parts[0].Trim()
            $value = $parts[1].Trim().Trim('"').Trim("'")
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

function Http-GetCode([string]$Url) {
    try {
        $r = Invoke-WebRequest -Uri $Url -Method Get -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
        return $r.StatusCode
    } catch {
        return 0
    }
}

function Is-Running {
    if (-not (Test-Path $PidFile)) { return $false }
    $pidVal = (Get-Content $PidFile -Raw).Trim()
    if (-not $pidVal) { return $false }
    try {
        $p = Get-Process -Id ([int]$pidVal) -ErrorAction Stop
        return $null -ne $p
    } catch {
        return $false
    }
}

# ------------------------------- version -----------------------------------

function Cmd-Version {
    if (Test-Path $VersionFile) {
        Write-Output (Get-Content $VersionFile -Raw).Trim()
    } else {
        Write-Output 'unknown'
    }
}

# ------------------------------- help --------------------------------------

function Cmd-Help {
    $v = Cmd-Version
    @"
Confluence RAG — $v

Usage: confluence-rag <command> [options]

Setup:
  init          Interactive setup: writes config and optionally pulls models
  doctor        Verify Java, Ollama, Qdrant, required models

Running:
  start         Start the app in the background
  stop          Stop the running app (graceful, then forced after 30s)
  status        Is the app running?
  logs          Tail the log file (use --no-follow --tail N for static view)

Operation:
  ingest        Trigger a full re-ingest via /api/admin/ingest

Config:
  config        Print path to config.env
  config edit   Open config.env in your default editor (notepad)

Maintenance:
  version       Show installed version
  update        Download and install the latest release
  uninstall     Remove the installation (with confirmation)
  help          This message

Paths:
  install dir:  $InstallDir
  config file:  $ConfigFile
  log file:     $LogFile
"@ | Write-Output
}

# ------------------------------- init --------------------------------------

function Prompt-Default([string]$Prompt, [string]$Default) {
    if ($Default) {
        $r = Read-Host "$Prompt [$Default]"
    } else {
        $r = Read-Host $Prompt
    }
    if (-not $r) { $r = $Default }
    return $r
}

function Prompt-Secret([string]$Prompt) {
    $secure = Read-Host -Prompt $Prompt -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

function Cmd-Init([string[]]$Args) {
    $noPull = $false
    foreach ($a in $Args) {
        if ($a -eq '--no-pull') { $noPull = $true }
        else { Die "unknown flag for init: $a" }
    }

    Ensure-Dirs

    if (Test-Path $ConfigFile) {
        $a = Read-Host 'A configuration already exists. Overwrite? [y/N]'
        if ($a -notmatch '^[yY]') { Write-Info 'Aborted.'; return }
    }

    Write-Info 'Setting up Confluence RAG — press Enter to accept defaults'
    Write-Host ''

    $baseUrl = Prompt-Default 'Confluence Base URL' 'http://localhost:8090'
    $auth    = Prompt-Default 'Auth method (pat|basic)' 'pat'
    $pat = ''; $user = ''; $pass = ''
    switch ($auth) {
        'pat'   { $pat = Prompt-Secret 'Personal Access Token' }
        'basic' {
            $user = Prompt-Default 'Username' 'admin'
            $pass = Prompt-Secret 'Password'
        }
        default { Die "Unknown auth method: $auth (must be pat or basic)" }
    }
    $spaces      = Prompt-Default 'Spaces (comma-separated)' ''
    $chat        = Prompt-Default 'Chat model' 'gemma3:4b'
    $embed       = Prompt-Default 'Embedding model' 'bge-m3'
    $rerank      = Prompt-Default 'Reranker (llm|infinity|none)' 'llm'
    $rerankModel = ''
    if ($rerank -eq 'llm') {
        $rerankModel = Prompt-Default 'Reranker model' 'qwen3:0.6b'
    }

    $stateFile = Join-Path $DataDir 'sync-state.json'
    $cfg = @"
# Confluence RAG — generated by 'confluence-rag init' on $(Get-Date)
CONFLUENCE_BASE_URL=$baseUrl
CONFLUENCE_PAT=$pat
CONFLUENCE_USERNAME=$user
CONFLUENCE_PASSWORD=$pass
CONFLUENCE_SPACES=$spaces

OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_CHAT_MODEL=$chat
OLLAMA_EMBEDDING_MODEL=$embed

QDRANT_HOST=localhost
QDRANT_GRPC_PORT=6334
VECTOR_DIMENSION=1024

QUERY_RERANKER_TYPE=$rerank
QUERY_RERANKER_LLM_MODEL=$rerankModel
QUERY_RERANKER_LLM_URL=http://localhost:11434

QUERY_TOP_K=5
QUERY_SIMILARITY_THRESHOLD=0.45

SYNC_STATE_FILE=$stateFile
"@
    Set-Content -Path $ConfigFile -Value $cfg -NoNewline

    # Windows ACL: remove inherited Users access, keep owner only
    try {
        icacls.exe $ConfigFile /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
    } catch {
        Write-Warn 'could not tighten ACL on config.env'
    }

    Write-Host ''
    Write-Info "Configuration written to $ConfigFile"
    Write-Host ''

    if (-not $noPull) {
        $a = Read-Host 'Pull required Ollama models now? (bge-m3, gemma3:4b, qwen3:0.6b) [Y/n]'
        if ($a -notmatch '^[nN]') {
            $models = @($embed, $chat)
            if ($rerank -eq 'llm' -and $rerankModel) { $models += $rerankModel }
            $hasOllama = $null -ne (Get-Command ollama -ErrorAction SilentlyContinue)
            if (-not $hasOllama) {
                Write-Warn 'ollama not found in PATH, skipping pull — install from https://ollama.com'
            } else {
                foreach ($m in $models) {
                    Write-Info "ollama pull $m"
                    try { & ollama pull $m } catch { Write-Warn "pull failed for $m (continue)" }
                }
            }
        }
    }

    Write-Host ''
    Write-Info 'Setup complete. Next steps:'
    Write-Host '  confluence-rag doctor'
    Write-Host '  confluence-rag start'
    Write-Host '  confluence-rag ingest'
}

# ------------------------------- doctor ------------------------------------

function Check-Java {
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCmd) {
        Write-Fail 'Java not found'
        Write-Host '      > Install Java 17+:'
        Write-Host '        winget install EclipseAdoptium.Temurin.17.JDK'
        Write-Host '        Or: https://adoptium.net/'
        return $false
    }
    $verOut = (& java -version 2>&1) -join "`n"
    if ($verOut -match '"(?<v>[^"]+)"') {
        $v = $matches['v']
        $major = if ($v -like '1.*') { [int]($v.Split('.')[1]) } else { [int]($v.Split('.')[0]) }
        if ($major -lt $RequiredJavaMajor) {
            Write-Fail "Java $v found, need ${RequiredJavaMajor}+"
            return $false
        }
        Write-Ok "Java 17+ ($v)"
        return $true
    }
    Write-Warn 'could not parse java version'
    return $true
}

function Check-Config {
    if (Test-Path $ConfigFile) {
        Write-Ok "Config present ($ConfigFile)"
        return $true
    }
    Write-Fail 'Config missing — run: confluence-rag init'
    return $false
}

function Check-Ollama {
    Load-Config
    $url = if ($env:OLLAMA_BASE_URL) { $env:OLLAMA_BASE_URL } else { 'http://localhost:11434' }
    $code = Http-GetCode "$url/api/tags"
    if ($code -ne 200) {
        Write-Fail "Ollama not reachable at $url"
        Write-Host '      > Install: https://ollama.com/download'
        Write-Host '      > Start:   ollama serve'
        return $false
    }
    Write-Ok "Ollama reachable ($url)"

    try {
        $tags = Invoke-RestMethod -Uri "$url/api/tags" -TimeoutSec 5
        $available = $tags.models | ForEach-Object { $_.name }
    } catch {
        $available = @()
    }
    $ok = $true
    $embed = if ($env:OLLAMA_EMBEDDING_MODEL) { $env:OLLAMA_EMBEDDING_MODEL } else { 'bge-m3' }
    $chat  = if ($env:OLLAMA_CHAT_MODEL) { $env:OLLAMA_CHAT_MODEL } else { 'gemma3:4b' }
    foreach ($m in @($embed, $chat)) {
        if ($available | Where-Object { $_ -like "$m*" }) {
            Write-Host "      ✓  $m available" -ForegroundColor Green
        } else {
            Write-Host "      ✗  $m missing — run: ollama pull $m" -ForegroundColor Red
            $ok = $false
        }
    }
    if ($env:QUERY_RERANKER_TYPE -eq 'llm' -or -not $env:QUERY_RERANKER_TYPE) {
        $rm = if ($env:QUERY_RERANKER_LLM_MODEL) { $env:QUERY_RERANKER_LLM_MODEL } else { 'qwen3:0.6b' }
        if ($available | Where-Object { $_ -like "$rm*" }) {
            Write-Host "      ✓  $rm available (reranker)" -ForegroundColor Green
        } else {
            Write-Host "      ✗  $rm missing — run: ollama pull $rm" -ForegroundColor Red
            $ok = $false
        }
    }
    return $ok
}

function Check-Qdrant {
    Load-Config
    $host = if ($env:QDRANT_HOST) { $env:QDRANT_HOST } else { 'localhost' }
    $url = "http://${host}:6333"
    $code = Http-GetCode "$url/healthz"
    if ($code -ne 200) {
        Write-Fail "Qdrant not reachable at $url"
        Write-Host '      > Install: https://qdrant.tech/documentation/quick-start/'
        return $false
    }
    Write-Ok "Qdrant reachable ($url)"
    return $true
}

function Cmd-Doctor {
    $ok = $true
    if (-not (Check-Java)) { $ok = $false }
    $cfgOk = Check-Config
    if (-not $cfgOk) { $ok = $false }
    if ($cfgOk) {
        if (-not (Check-Ollama)) { $ok = $false }
        if (-not (Check-Qdrant)) { $ok = $false }
    }
    Write-Host ''
    if ($ok) { Write-Host ' ✓  All checks passed.' -ForegroundColor Green }
    else     { Write-Host ' ✗  Some checks failed — see above.' -ForegroundColor Red; exit 1 }
}

# ------------------------------- start / stop ------------------------------

function Cmd-Start {
    Load-Config
    Ensure-Dirs

    if (Is-Running) {
        $p = (Get-Content $PidFile -Raw).Trim()
        Write-Info "Already running (pid $p)"
        return
    }

    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        Die 'java not found — install Java 17+ first'
    }

    Write-Info 'Starting confluence-rag ...'
    $proc = Start-Process -FilePath 'java' `
        -ArgumentList '-jar', $LibJar `
        -WorkingDirectory $InstallDir `
        -RedirectStandardOutput $LogFile `
        -RedirectStandardError $ErrFile `
        -WindowStyle Hidden -PassThru
    $proc.Id | Out-File -FilePath $PidFile -Encoding ASCII

    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 2
        $code = Http-GetCode "$AppUrl/api/spaces"
        if ($code -eq 200) {
            Write-Info "Started. $AppUrl  (pid $($proc.Id))"
            return
        }
        if ($proc.HasExited) {
            Die "Process exited during startup. Check $LogFile / $ErrFile"
        }
    }
    Write-Warn "App started but /api/spaces did not respond within 60s — check $LogFile"
}

function Cmd-Stop {
    if (-not (Is-Running)) {
        Write-Info 'Not running.'
        if (Test-Path $PidFile) { Remove-Item $PidFile -Force }
        return
    }
    $pidVal = [int]((Get-Content $PidFile -Raw).Trim())
    Write-Info "Stopping pid $pidVal ..."
    try {
        $p = Get-Process -Id $pidVal -ErrorAction Stop
        $p.CloseMainWindow() | Out-Null
        if (-not $p.WaitForExit(30000)) {
            Write-Warn 'Graceful shutdown timeout, killing'
            Stop-Process -Id $pidVal -Force
        }
    } catch {
        Stop-Process -Id $pidVal -Force -ErrorAction SilentlyContinue
    }
    Remove-Item $PidFile -Force -ErrorAction SilentlyContinue
    Write-Info 'Stopped.'
}

function Cmd-Status {
    if (Is-Running) {
        $pidVal = (Get-Content $PidFile -Raw).Trim()
        $code = Http-GetCode "$AppUrl/api/spaces"
        if ($code -eq 200) {
            Write-Host " ✓  running — pid $pidVal, $AppUrl" -ForegroundColor Green
        } else {
            Write-Host " ?  process alive but HTTP not responding (pid $pidVal)" -ForegroundColor Yellow
        }
    } else {
        Write-Host ' ✗  not running' -ForegroundColor Red
        exit 1
    }
}

# ------------------------------- logs --------------------------------------

function Cmd-Logs([string[]]$Args) {
    $tailN = 50
    $follow = $true
    $i = 0
    while ($i -lt $Args.Count) {
        switch ($Args[$i]) {
            '--tail' { $tailN = [int]$Args[$i + 1]; $i += 2 }
            '--no-follow' { $follow = $false; $i += 1 }
            default { Die "unknown flag for logs: $($Args[$i])" }
        }
    }
    if (-not (Test-Path $LogFile)) { Die 'no log file yet — has the app been started?' }
    if ($follow) {
        Get-Content -Path $LogFile -Tail $tailN -Wait
    } else {
        Get-Content -Path $LogFile -Tail $tailN
    }
}

# ------------------------------- config ------------------------------------

function Cmd-Config([string[]]$Args) {
    $sub = if ($Args.Count -gt 0) { $Args[0] } else { '' }
    switch ($sub) {
        '' { Write-Output $ConfigFile }
        'edit' {
            Require-Config
            $editor = if ($env:EDITOR) { $env:EDITOR } else { 'notepad' }
            & $editor $ConfigFile
        }
        default { Die "unknown config sub-command: $sub" }
    }
}

# ------------------------------- ingest ------------------------------------

function Cmd-Ingest {
    if (-not (Is-Running)) {
        Die "app is not running — run 'confluence-rag start' first"
    }
    Write-Info "Triggering POST $AppUrl/api/admin/ingest"
    Invoke-RestMethod -Method Post -Uri "$AppUrl/api/admin/ingest"
}

# ------------------------------- update ------------------------------------

function Cmd-Update {
    Write-Info 'Fetching latest release info from GitHub ...'
    try {
        $rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$GitHubRepo/releases/latest" -TimeoutSec 30
    } catch {
        Die 'cannot reach GitHub API'
    }
    $latest = $rel.tag_name
    $current = Cmd-Version
    Write-Info "Current: $current  ->  latest: $latest"

    if ($current -eq $latest -or $current -eq $latest.TrimStart('v')) {
        Write-Info 'Already up to date.'
        return
    }

    $asset = $rel.assets | Where-Object { $_.name -like '*-windows.zip' } | Select-Object -First 1
    if (-not $asset) { Die "no windows archive found in release $latest" }

    $tmp = Join-Path $env:TEMP "confluence-rag-update-$([Guid]::NewGuid())"
    New-Item -ItemType Directory -Path $tmp | Out-Null
    $zipPath = Join-Path $tmp 'update.zip'
    Write-Info "Downloading $($asset.browser_download_url)"
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $zipPath -UseBasicParsing
    Expand-Archive -Path $zipPath -DestinationPath $tmp -Force

    $inner = Get-ChildItem -Path $tmp -Directory | Where-Object { $_.Name -like 'confluence-rag-*' } | Select-Object -First 1
    if (-not $inner) { Die 'unexpected archive layout' }

    $wasRunning = Is-Running
    if ($wasRunning) { Cmd-Stop }

    Write-Info 'Updating installation ...'
    Copy-Item -Path (Join-Path $inner.FullName 'lib\confluence-rag.jar') -Destination $LibJar -Force
    Copy-Item -Path (Join-Path $inner.FullName 'bin\confluence-rag.cmd') -Destination $BinDir -Force
    Copy-Item -Path (Join-Path $inner.FullName 'bin\confluence-rag.ps1') -Destination $BinDir -Force
    $newVersion = Join-Path $inner.FullName 'VERSION'
    if (Test-Path $newVersion) { Copy-Item -Path $newVersion -Destination $VersionFile -Force }
    Remove-Item -Recurse -Force $tmp

    Write-Info "Updated to $latest"
    if ($wasRunning) { Cmd-Start }
}

# ------------------------------- uninstall ---------------------------------

function Cmd-Uninstall([string[]]$Args) {
    $force = $Args -contains '--yes'

    if (-not $force) {
        $a = Read-Host "Remove $InstallDir and all data/logs? [y/N]"
        if ($a -notmatch '^[yY]') { Write-Info 'Aborted.'; return }
    }

    if (Is-Running) { Cmd-Stop }

    # Remove from User PATH
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath) {
        $parts = $userPath.Split(';') | Where-Object { $_ -and ($_ -ne $BinDir) }
        [Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User')
    }

    Remove-Item -Recurse -Force $InstallDir
    Write-Info 'Uninstalled.'
}

# ------------------------------- dispatch ----------------------------------

$cmd = if ($args.Count -gt 0) { $args[0] } else { 'help' }
$rest = if ($args.Count -gt 1) { $args[1..($args.Count - 1)] } else { @() }

switch ($cmd) {
    'init'      { Cmd-Init $rest }
    'doctor'    { Cmd-Doctor }
    'start'     { Cmd-Start }
    'stop'      { Cmd-Stop }
    'status'    { Cmd-Status }
    'logs'      { Cmd-Logs $rest }
    'config'    { Cmd-Config $rest }
    'ingest'    { Cmd-Ingest }
    'update'    { Cmd-Update }
    'uninstall' { Cmd-Uninstall $rest }
    'version'   { Cmd-Version }
    '-V'        { Cmd-Version }
    '--version' { Cmd-Version }
    'help'      { Cmd-Help }
    '-h'        { Cmd-Help }
    '--help'    { Cmd-Help }
    ''          { Cmd-Help }
    default     { Die "unknown command: $cmd  (try 'confluence-rag help')" }
}
