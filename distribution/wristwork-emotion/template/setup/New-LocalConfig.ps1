[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [uri]$BaseUrl,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Token,
    [ValidateRange(1, 240)]
    [int]$CueDelayMinutes = 30,
    [string]$NtfyBind = '0.0.0.0:8093',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$configPath = Join-Path $root 'config.properties'
$backendEnvPath = Join-Path $root 'backend/.env'
if (-not $Force -and ((Test-Path -LiteralPath $configPath) -or
    (Test-Path -LiteralPath $backendEnvPath))) {
    throw 'Private configuration already exists. Use -Force only when replacement is intended.'
}
if ($BaseUrl.Scheme -notin @('http', 'https')) {
    throw 'BaseUrl must use http or https.'
}

$bytes = [byte[]]::new(18)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$suffix = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '').Replace('/', '')
$topics = [ordered]@{
    TOPIC_TAGS = "emotion-labels-$suffix"
    TOPIC_FLAGS = "emotion-flags-$suffix"
    TOPIC_HEALTH = "emotion-health-$suffix"
    TOPIC_PROMPTS = "emotion-prompts-$suffix"
}
$base = $BaseUrl.AbsoluteUri.TrimEnd('/')
$configLines = @(
    "NTFY_BASE_URL=$base",
    "NTFY_TOKEN=$Token",
    "TOPIC_TAGS=$($topics.TOPIC_TAGS)",
    "TOPIC_FLAGS=$($topics.TOPIC_FLAGS)",
    "TOPIC_HEALTH=$($topics.TOPIC_HEALTH)",
    "TOPIC_PROMPTS=$($topics.TOPIC_PROMPTS)",
    "CUE_DELAY_MIN=$CueDelayMinutes"
)
$envLines = @(
    "NTFY_BIND=$NtfyBind",
    "NTFY_TOKEN=$Token",
    "TOPIC_TAGS=$($topics.TOPIC_TAGS)",
    "TOPIC_FLAGS=$($topics.TOPIC_FLAGS)",
    "TOPIC_HEALTH=$($topics.TOPIC_HEALTH)",
    "TOPIC_PROMPTS=$($topics.TOPIC_PROMPTS)"
)
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllLines($configPath, $configLines, $utf8)
New-Item -ItemType Directory -Path (Split-Path $backendEnvPath -Parent) -Force | Out-Null
[IO.File]::WriteAllLines($backendEnvPath, $envLines, $utf8)
New-Item -ItemType Directory -Path (Join-Path $root 'backend/data') -Force | Out-Null
Write-Output 'Created ignored app and backend configuration with new private topic names.'
