# Rig stats feeder: CPU/RAM/GPU/load JSON -> topic `rig`.
# Designed for a 5-minute Task Scheduler cadence; posts only on threshold change (any metric
# crossing a 10-point bucket) or when 15 minutes have passed since the last post, whichever first.
# Reads NTFY_BASE_URL and TOPIC_RIG from the gitignored config.properties. State in data/.
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot)
$cfg = @{}
Get-Content (Join-Path $root "config.properties") | ForEach-Object {
    if ($_ -match '^\s*([A-Z_]+)\s*=\s*([^#]*)') { $cfg[$Matches[1]] = $Matches[2].Trim() }
}
$url = "$($cfg['NTFY_BASE_URL'])/$($cfg['TOPIC_RIG'])"

# --- collect ---
$cpu = [math]::Round((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average)
$os = Get-CimInstance Win32_OperatingSystem
$ram = [math]::Round(100 * (1 - $os.FreePhysicalMemory / $os.TotalVisibleMemorySize))
$gpu = $null
try {
    $g = & nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>$null
    if ($g) { $gpu = [int]($g | Select-Object -First 1) }
} catch {}
$payload = @{ cpu = $cpu; ram = $ram }
if ($null -ne $gpu) { $payload.gpu = $gpu }
$json = ($payload | ConvertTo-Json -Compress)

# --- decide whether to post ---
$stateDir = Join-Path $root "data"
New-Item -ItemType Directory -Force $stateDir | Out-Null
$stateFile = Join-Path $stateDir "rig-stats-state.json"
$now = Get-Date
$post = $true
if (Test-Path $stateFile) {
    $prev = Get-Content $stateFile | ConvertFrom-Json
    $ageMin = ($now - [datetime]$prev.at).TotalMinutes
    $bucket = { param($v) if ($null -eq $v) { -1 } else { [math]::Floor($v / 10) } }
    $sameBuckets = (& $bucket $cpu) -eq (& $bucket $prev.cpu) -and
                   (& $bucket $ram) -eq (& $bucket $prev.ram) -and
                   (& $bucket $gpu) -eq (& $bucket $prev.gpu)
    if ($sameBuckets -and $ageMin -lt 15) { $post = $false }
}
if ($post) {
    Invoke-RestMethod -Method Post -Uri $url -Body $json -TimeoutSec 10 | Out-Null
    @{ at = $now.ToString("o"); cpu = $cpu; ram = $ram; gpu = $gpu } | ConvertTo-Json -Compress | Set-Content $stateFile
}
