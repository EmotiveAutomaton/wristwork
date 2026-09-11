[CmdletBinding()]
param([string]$Serial)

$ErrorActionPreference = 'Continue'
$root = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$failures = 0
function Report([string]$Name, [bool]$Ok, [string]$Detail) {
    if (-not $Ok) { $script:failures++ }
    $mark = if ($Ok) { 'PASS' } else { 'FAIL' }
    Write-Output ("{0,-4} {1}: {2}" -f $mark, $Name, $Detail)
}

Report 'JDK' ($null -ne (Get-Command java -ErrorAction SilentlyContinue)) 'java is on PATH'
Report 'Gradle wrapper' (Test-Path -LiteralPath (Join-Path $root 'gradlew.bat')) 'wrapper is present'
$configPath = Join-Path $root 'config.properties'
Report 'Configuration' (Test-Path -LiteralPath $configPath) 'ignored config.properties is present'

$config = @{}
if (Test-Path -LiteralPath $configPath) {
    Get-Content -LiteralPath $configPath | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $config[$matches[1].Trim()] = $matches[2].Trim() }
    }
    $required = @('NTFY_BASE_URL', 'NTFY_TOKEN', 'TOPIC_TAGS', 'TOPIC_FLAGS',
        'TOPIC_HEALTH', 'TOPIC_PROMPTS')
    $usable = $required | Where-Object {
        -not $config[$_] -or $config[$_] -match 'replace-with|example\.invalid'
    }
    Report 'Private values' ($usable.Count -eq 0) 'endpoint, token, and four topics are configured'

    if ($usable.Count -eq 0) {
        $headers = @{ Authorization = "Bearer $($config.NTFY_TOKEN)" }
        try {
            $health = Invoke-WebRequest -Uri "$($config.NTFY_BASE_URL.TrimEnd('/'))/v1/health" `
                -Headers $headers -Method Get -TimeoutSec 15
            Report 'ntfy health' ($health.StatusCode -eq 200) "HTTP $($health.StatusCode)"
        } catch {
            Report 'ntfy health' $false $_.Exception.Message
        }
        try {
            $topic = [Uri]::EscapeDataString($config.TOPIC_TAGS)
            $check = Invoke-WebRequest `
                -Uri "$($config.NTFY_BASE_URL.TrimEnd('/'))/$topic/json?poll=1&since=10s" `
                -Headers $headers -Method Get -TimeoutSec 15
            Report 'Topic access' ($check.StatusCode -eq 200) 'read-only authenticated poll succeeded'
        } catch {
            Report 'Topic access' $false $_.Exception.Message
        }
    }
}

$adb = Get-Command adb -ErrorAction SilentlyContinue
Report 'adb' ($null -ne $adb) 'adb is on PATH'
if ($adb -and $Serial) {
    $state = & adb -s $Serial get-state 2>$null
    Report 'Watch' ($LASTEXITCODE -eq 0 -and $state -eq 'device') 'selected serial is connected'
    $package = & adb -s $Serial shell cmd package list packages com.emotiveautomaton.wristworkemotion 2>$null
    Report 'Installed app' ($package -match 'com\.emotiveautomaton\.wristworkemotion') `
        'wristwork-emotion package is present'
}

$archiveDir = Join-Path $root 'backend/data'
if (Test-Path -LiteralPath $archiveDir) {
    $archiveFiles = @(Get-ChildItem -LiteralPath $archiveDir -Filter '*.jsonl' -File -ErrorAction SilentlyContinue)
    Write-Output ("INFO Archive: {0} topic file(s) exist; freshness and contents were not inferred" -f $archiveFiles.Count)
}

exit $failures
