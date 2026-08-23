# Rig stats feeder v3: totals + top-5 processes with per-process cpu/gpu/ram percent.
# Posts to topic `rig` every 5 minutes flat (graph continuity; bus caches 24 h, frame reads 6 h).
# All values integer percent of the machine max, capped at 99.
# Payload: {"cpu":32,"ram":42,"gpu":91,"procs":[["llama-server",4,91,12],...]}  ([name,c,g,r])
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
$totalRamBytes = $os.TotalVisibleMemorySize * 1KB
$ram = & $cap (100 * (1 - $os.FreePhysicalMemory / $os.TotalVisibleMemorySize))
$gpu = $null
try {
    $g = & nvidia-smi --query-gpu=utilization.gpu --format=csv,noheader,nounits 2>$null
    if ($g) { $gpu = & $cap ([int]($g | Select-Object -First 1)) }
} catch {}

# --- per-process GPU by pid: Windows GPU Engine counters (WDDM hides nvidia pmon's view;
# these are the numbers Task Manager shows). Sum across a pid's engines, cap 99.
$gpuByPid = @{}
try {
    # GPU load is bursty (inference requests): sample twice 1 s apart, keep each pid's max.
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

# --- top processes by CPU, with pid mapping for gpu/ram lookups ---
$procs = @()
try {
    $cores = [Environment]::ProcessorCount
    # Two samples 1 s apart: the first '% Processor Time' sample of a session reads ~0.
    $counter = Get-Counter '\Process(*)\% Processor Time', '\Process(*)\ID Process' `
        -SampleInterval 1 -MaxSamples 2 -ErrorAction SilentlyContinue | Select-Object -Last 1
    $cpuS = @{}; $pidS = @{}
    foreach ($smp in $counter.CounterSamples) {
        if ($smp.Status -ne 0 -or $smp.InstanceName -in @('_total', 'idle')) { continue }
        if ($smp.Path -like '*% processor time*') { $cpuS[$smp.InstanceName] = $smp.CookedValue }
        else { $pidS[$smp.InstanceName] = [int]$smp.CookedValue }
    }
    # Rank by the process's busiest resource (max of c/g/r), not CPU alone — a GPU-bound
    # model server with idle CPU must still surface.
    $ramByPid = @{}
    foreach ($gp in Get-Process -ErrorAction SilentlyContinue) { $ramByPid[$gp.Id] = $gp.WorkingSet64 }
    $scored = foreach ($e in $cpuS.GetEnumerator()) {
        $procId = $pidS[$e.Key]
        $c0 = & $cap ($e.Value / $cores)
        $g0 = if ($null -ne $procId -and $gpuByPid.ContainsKey($procId)) { $gpuByPid[$procId] } else { 0 }
        $r0 = if ($null -ne $procId -and $ramByPid.ContainsKey($procId)) { & $cap (100 * $ramByPid[$procId] / $totalRamBytes) } else { 0 }
        [pscustomobject]@{ Key = $e.Key; C = $c0; G = $g0; R = $r0; Score = [math]::Max($c0, [math]::Max($g0, $r0)) }
    }
    $top = $scored | Sort-Object Score -Descending | Select-Object -First 5
    $procs = @($top | ForEach-Object {
        $name = ($_.Key -replace '#\d+$', '')
        , @($name.Substring(0, [math]::Min(12, $name.Length)), $_.C, $_.G, $_.R)
    })
} catch {}

$payload = [ordered]@{ cpu = $cpu; ram = $ram }
if ($null -ne $gpu) { $payload.gpu = $gpu }
if ($procs.Count -gt 0) { $payload.procs = $procs }
$json = ($payload | ConvertTo-Json -Compress -Depth 4)
Invoke-RestMethod -Method Post -Uri $url -Body $json -TimeoutSec 10 | Out-Null
