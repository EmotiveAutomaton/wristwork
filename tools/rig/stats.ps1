# Rig stats feeder v2: CPU/RAM/GPU percentages + top processes -> topic `rig`, every 5 minutes
# flat (no threshold gating — the tap-frame graphs want a continuous series; the bus caches 24 h,
# the graphs read the last 6 h). All values are integer percent of the machine's max, capped at 99.
# Payload: {"cpu":34,"gpu":99,"ram":44,"procs":[["ollama",42],["chrome",11],...]}  (top 5 by CPU)
# Reads NTFY_BASE_URL and TOPIC_RIG from the gitignored config.properties.
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot)
$cfg = @{}
Get-Content (Join-Path $root "config.properties") | ForEach-Object {
    if ($_ -match '^\s*([A-Z_]+)\s*=\s*([^#]*)') { $cfg[$Matches[1]] = $Matches[2].Trim() }
}
$url = "$($cfg['NTFY_BASE_URL'])/$($cfg['TOPIC_RIG'])"
$cap = { param($v) [math]::Min(99, [math]::Max(0, [math]::Round($v))) }

# --- totals ---
$cpu = & $cap ((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average)
$os = Get-CimInstance Win32_OperatingSystem
$ram = & $cap (100 * (1 - $os.FreePhysicalMemory / $os.TotalVisibleMemorySize))
$gpu = $null
try {
    $g = & nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>$null
    if ($g) { $gpu = & $cap ([int]($g | Select-Object -First 1)) }
} catch {}

# --- top processes by CPU (percent of whole machine) ---
$procs = @()
try {
    $cores = [Environment]::ProcessorCount
    $counter = Get-Counter '\Process(*)\% Processor Time' -ErrorAction SilentlyContinue
    $samples = $counter.CounterSamples |
        Where-Object { $_.Status -eq 0 -and $_.InstanceName -notin @('_total', 'idle') } |
        Sort-Object CookedValue -Descending | Select-Object -First 5
    $procs = @($samples | ForEach-Object {
        $pct = & $cap ($_.CookedValue / $cores)
        , @(($_.InstanceName -replace '#\d+$', '').Substring(0, [math]::Min(12, $_.InstanceName.Length)), $pct)
    })
} catch {}

$payload = [ordered]@{ cpu = $cpu; ram = $ram }
if ($null -ne $gpu) { $payload.gpu = $gpu }
if ($procs.Count -gt 0) { $payload.procs = $procs }
$json = ($payload | ConvertTo-Json -Compress -Depth 4)
Invoke-RestMethod -Method Post -Uri $url -Body $json -TimeoutSec 10 | Out-Null
