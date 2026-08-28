# Rig stats feeder v4 -> topic `rig`, every 5 minutes flat. Integer percent, capped 99.
# Adds: vram %, GPU/CPU temperature (deg C), top TEN processes, and a temperature alert:
# crossing unsafe (gpu >= 90 C or cpu >= 95 C) posts a high-priority message to the agents topic
# once per excursion (re-armed when it cools); the watch icon logic reads temps off the payload.
# Payload: {"cpu":n,"ram":n,"gpu":n,"vram":n,"tg":n,"tc":n,"procs":[["name",c,g,r] x10]}
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot)
$cfg = @{}
Get-Content (Join-Path $root "config.properties") | ForEach-Object {
    if ($_ -match '^\s*([A-Z_]+)\s*=\s*([^#]*)') { $cfg[$Matches[1]] = $Matches[2].Trim() }
}
$url = "$($cfg['NTFY_BASE_URL'])/$($cfg['TOPIC_RIG'])"
# The bus requires a token once it is published to the internet; empty = LAN-only mode.
$hdr = @{}
if ($cfg['NTFY_TOKEN_SVC']) { $hdr['Authorization'] = "Bearer $($cfg['NTFY_TOKEN_SVC'])" }
$alertUrl = "$($cfg['NTFY_BASE_URL'])/$($cfg['TOPIC_AGENTS'])"
$cap = { param($v) [math]::Min(99, [math]::Max(0, [math]::Round($v))) }

# --- totals ---
$cpu = & $cap ((Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average)
$os = Get-CimInstance Win32_OperatingSystem
$totalRamBytes = $os.TotalVisibleMemorySize * 1KB
$ram = & $cap (100 * (1 - $os.FreePhysicalMemory / $os.TotalVisibleMemorySize))
$gpu = $null; $vram = $null; $tg = $null
try {
    $g = & nvidia-smi --query-gpu=utilization.gpu,memory.used,memory.total,temperature.gpu --format=csv,noheader,nounits 2>$null
    if ($g) {
        $parts = ($g | Select-Object -First 1) -split '\s*,\s*'
        $gpu = & $cap ([double]$parts[0])
        if ([double]$parts[2] -gt 0) { $vram = & $cap (100 * [double]$parts[1] / [double]$parts[2]) }
        $tg = [math]::Round([double]$parts[3])
    }
} catch {}
# CPU temp via LibreHardwareMonitor's local web server (this board exposes no sensors to
# non-admin WMI). LHM runs elevated in the tray with the server on :8085; absent -> tc omitted.
$tc = $null
try {
    $lhm = Invoke-RestMethod -Uri "http://localhost:8085/data.json" -TimeoutSec 4
    $stack = New-Object System.Collections.Stack; $stack.Push($lhm)
    while ($stack.Count -gt 0 -and $null -eq $tc) {
        $n = $stack.Pop()
        if ($n.Text -match '^(Package|CPU Package|Core \(Tctl)' -and $n.Value -match '^([0-9.]+)') {
            $tc = [math]::Round([double]$Matches[1])
        }
        foreach ($ch in @($n.Children)) { if ($null -ne $ch) { $stack.Push($ch) } }
    }
} catch {}

# --- per-process GPU by pid: Windows GPU Engine counters (Task Manager's numbers; nvidia's own
# per-process view is blocked under WDDM). Bursty: two samples 1 s apart, keep each pid's max.
$gpuByPid = @{}
try {
    $gs = (Get-Counter '\GPU Engine(*)\Utilization Percentage' -SampleInterval 1 -MaxSamples 2 `
        -ErrorAction SilentlyContinue).CounterSamples
    foreach ($x in $gs) {
        if ($x.CookedValue -gt 0.5 -and $x.InstanceName -match 'pid_(\d+)_') {
            $procId = [int]$Matches[1]
            $prev = if ($gpuByPid.ContainsKey($procId)) { $gpuByPid[$procId] } else { 0 }
            $gpuByPid[$procId] = [math]::Min(99, [math]::Max($prev, [math]::Round($x.CookedValue)))
        }
    }
} catch {}

# --- top 10 processes, ranked by each one's busiest resource ---
$procs = @()
try {
    $cores = [Environment]::ProcessorCount
    $counter = Get-Counter '\Process(*)\% Processor Time', '\Process(*)\ID Process' `
        -SampleInterval 1 -MaxSamples 2 -ErrorAction SilentlyContinue | Select-Object -Last 1
    $cpuS = @{}; $pidS = @{}
    foreach ($smp in $counter.CounterSamples) {
        if ($smp.Status -ne 0 -or $smp.InstanceName -in @('_total', 'idle')) { continue }
        if ($smp.Path -like '*% processor time*') { $cpuS[$smp.InstanceName] = $smp.CookedValue }
        else { $pidS[$smp.InstanceName] = [int]$smp.CookedValue }
    }
    $ramByPid = @{}
    foreach ($gp in Get-Process -ErrorAction SilentlyContinue) { $ramByPid[$gp.Id] = $gp.WorkingSet64 }
    $scored = foreach ($e in $cpuS.GetEnumerator()) {
        $procId = $pidS[$e.Key]
        $c0 = & $cap ($e.Value / $cores)
        $g0 = if ($null -ne $procId -and $gpuByPid.ContainsKey($procId)) { $gpuByPid[$procId] } else { 0 }
        $r0 = if ($null -ne $procId -and $ramByPid.ContainsKey($procId)) { & $cap (100 * $ramByPid[$procId] / $totalRamBytes) } else { 0 }
        [pscustomobject]@{ Key = $e.Key; C = $c0; G = $g0; R = $r0; Score = [math]::Max($c0, [math]::Max($g0, $r0)) }
    }
    $top = $scored | Sort-Object Score -Descending | Select-Object -First 10
    $procs = @($top | ForEach-Object {
        $name = ($_.Key -replace '#\d+$', '')
        , @($name.Substring(0, [math]::Min(12, $name.Length)), $_.C, $_.G, $_.R)
    })
} catch {}

$payload = [ordered]@{ cpu = $cpu; ram = $ram }
if ($null -ne $gpu) { $payload.gpu = $gpu }
if ($null -ne $vram) { $payload.vram = $vram }
if ($null -ne $tg) { $payload.tg = $tg }
if ($null -ne $tc) { $payload.tc = $tc }
if ($procs.Count -gt 0) { $payload.procs = $procs }
$json = ($payload | ConvertTo-Json -Compress -Depth 4)
Invoke-RestMethod -Method Post -Uri $url -Body $json -Headers $hdr -TimeoutSec 10 | Out-Null

# --- temperature alert: once per excursion, re-armed after cooldown ---
# Ryzen 7000 parks at its 95 C TjMax by design (normal under load); only above it is anomalous.
$GPU_UNSAFE = 90; $CPU_UNSAFE = 97
$hot = ($null -ne $tg -and $tg -ge $GPU_UNSAFE) -or ($null -ne $tc -and $tc -ge $CPU_UNSAFE)
$flag = Join-Path (Join-Path $root "data") "rig-hot.flag"
if ($hot -and -not (Test-Path $flag)) {
    $msg = "RIG HOT:" + $(if ($null -ne $tg) { " gpu ${tg}C" }) + $(if ($null -ne $tc) { " cpu ${tc}C" })
    $alertHdr = $hdr.Clone(); $alertHdr['Title'] = "rig temperature"; $alertHdr['Priority'] = "high"
    Invoke-RestMethod -Method Post -Uri $alertUrl -Body $msg `
        -Headers $alertHdr -TimeoutSec 10 | Out-Null
    New-Item -ItemType File -Force $flag | Out-Null
} elseif (-not $hot -and (Test-Path $flag)) {
    Remove-Item $flag -Force
}
