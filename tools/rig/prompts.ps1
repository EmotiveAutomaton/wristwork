# The random-prompt allocator (detector design §1). Runs ONCE A DAY on the rig, early, and posts
# the day's random prompts to the bus AHEAD OF TIME with a deliver-at stamp. The watch's own
# poller fires them when their moment comes — so the evaluation backbone does not depend on this
# machine being awake at that minute, which is the accommodation that makes "run it on the rig"
# safe (owner decision, 2026-08-26).
#
# Registered in Task Scheduler as "wristwork-prompts" via the windowless wrapper.
#
# Config (config.properties, gitignored): NTFY_BASE_URL, TOPIC_PROMPTS, NTFY_TOKEN_SVC,
#   RANDOM_PER_DAY (default 1 — the ramp knob; the owner's call, revisit ~week 4),
#   WAKING_START / WAKING_END (default 09:00 / 22:00, local).
#
# Posted at ntfy priority `min` on purpose: the ntfy app must stay silent, because the ONE
# notification the owner should see is the one our own app raises, with the blinded copy.
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot)
$cfg = @{}
Get-Content (Join-Path $root "config.properties") | ForEach-Object {
    if ($_ -match '^\s*([A-Z_]+)\s*=\s*([^#]*)') { $cfg[$Matches[1]] = $Matches[2].Trim() }
}
$base = $cfg['NTFY_BASE_URL']; $topic = $cfg['TOPIC_PROMPTS']
if (-not $base -or -not $topic) { throw "config.properties missing NTFY_BASE_URL or TOPIC_PROMPTS" }
$perDay = 1; if ($cfg['RANDOM_PER_DAY']) { $perDay = [int]$cfg['RANDOM_PER_DAY'] }
$startH = "09:00"; if ($cfg['WAKING_START']) { $startH = $cfg['WAKING_START'] }
$endH   = "22:00"; if ($cfg['WAKING_END'])   { $endH   = $cfg['WAKING_END'] }

$dataDir = Join-Path $root "data"
New-Item -ItemType Directory -Force $dataDir | Out-Null
$log = Join-Path $dataDir "prompts.log"
$stamp = Join-Path $dataDir "prompts-last-day.txt"

$today = (Get-Date).ToString("yyyy-MM-dd")
if ((Test-Path $stamp) -and (Get-Content $stamp -Raw).Trim() -eq $today) {
    Add-Content -Path $log -Value "$(Get-Date -Format o) already allocated for $today"
    exit 0
}

$dayStart = [datetime]::ParseExact("$today $startH", "yyyy-MM-dd HH:mm", $null)
$dayEnd   = [datetime]::ParseExact("$today $endH",   "yyyy-MM-dd HH:mm", $null)
if ($dayEnd -le $dayStart) { throw "WAKING_END must be after WAKING_START" }
$windowMin = [int]($dayEnd - $dayStart).TotalMinutes

$hdr = @{ Priority = "min" }
if ($cfg['NTFY_TOKEN_SVC']) { $hdr['Authorization'] = "Bearer $($cfg['NTFY_TOKEN_SVC'])" }

$posted = 0
foreach ($i in 1..$perDay) {
    # Uniform within the waking window. No spacing rule at one a day; if the ramp knob goes up,
    # add a minimum separation here rather than letting two prompts land in the same ten minutes.
    $offset = Get-Random -Minimum 0 -Maximum $windowMin
    $at = $dayStart.AddMinutes($offset)
    if ($at -lt (Get-Date)) { $at = (Get-Date).AddMinutes(5) }   # script started late in the day
    # NOT -UFormat %s: on Windows PowerShell it reads a local DateTime as if it were UTC,
    # which put the first allocated prompt seven hours in the past (2026-08-26) — where the
    # watch's staleness rule correctly refused to ask it. DateTimeOffset carries the offset.
    $epoch = [int][System.DateTimeOffset]::new($at, [System.TimeZoneInfo]::Local.GetUtcOffset($at)).ToUnixTimeSeconds()
    # The epoch is part of the id so a re-allocation is a NEW prompt: the watch remembers
    # which ids it has fired, and a reused id would be silently swallowed as a duplicate.
    $id = "r-$today-$i-$epoch"
    $body = @{ prompt_id = $id; source = "random"; deliver_at = $epoch; ts = $epoch } | ConvertTo-Json -Compress
    try {
        Invoke-RestMethod -Method Post -Uri "$base/$topic" -Body $body -Headers $hdr -TimeoutSec 15 | Out-Null
        Add-Content -Path $log -Value "$(Get-Date -Format o) allocated $id for $($at.ToString('HH:mm'))"
        $posted++
    } catch {
        Add-Content -Path $log -Value "$(Get-Date -Format o) FAILED $id :: $($_.Exception.Message)"
    }
}
if ($posted -eq $perDay) { Set-Content -Path $stamp -Value $today }
