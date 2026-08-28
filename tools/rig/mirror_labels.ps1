# Nightly pull of all append-only streams (labels, flags, health) from the NAS to this machine,
# plus the flag-listener canary (HEALTH_DESIGN.md). Registered in Task Scheduler as
# "wristwork-labels-mirror" via the windowless wrapper.
# Host alias and remote path come from the gitignored config.properties (RAID_SSH_HOST,
# LABELS_PATH_RAID). Synology sshd has no SFTP subsystem -> stream over plain ssh; `-n` detaches
# stdin (Task Scheduler's console-less context hangs ssh without it). Atomic: temp then move;
# a mirror never shrinks (append-only law).
$ErrorActionPreference = "Stop"
$root = Split-Path (Split-Path $PSScriptRoot)
$cfg = @{}
Get-Content (Join-Path $root "config.properties") | ForEach-Object {
    if ($_ -match '^\s*([A-Z_]+)\s*=\s*([^#]*)') { $cfg[$Matches[1]] = $Matches[2].Trim() }
}
$sshHost = $cfg["RAID_SSH_HOST"]; $remote = $cfg["LABELS_PATH_RAID"]
if (-not $sshHost -or -not $remote) { throw "config.properties missing RAID_SSH_HOST or LABELS_PATH_RAID" }
$dest = Join-Path $root "data"
New-Item -ItemType Directory -Force $dest | Out-Null
$log = Join-Path $dest "mirror.log"
$remoteDir = ($remote -replace '/[^/]+$', '')

$streams = @(($remote -split '/')[-1], "flags.jsonl", "health.jsonl")
foreach ($name in $streams) {
    $final = Join-Path $dest $name
    $tmp = "$final.tmp"
    cmd /c "ssh -n -o BatchMode=yes -o ConnectTimeout=15 $sshHost ""cat $remoteDir/$name 2>/dev/null || true"" > ""$tmp"" 2> ""$tmp.err"""
    if ($LASTEXITCODE -ne 0) {
        $err = (Get-Content "$tmp.err" -Raw -ErrorAction SilentlyContinue)
        Add-Content -Path $log -Value "$(Get-Date -Format o) FAILED $name exit=$LASTEXITCODE :: $err"
        continue
    }
    Remove-Item "$tmp.err" -ErrorAction SilentlyContinue
    $oldLen = (Get-Item $final -ErrorAction SilentlyContinue).Length
    if ($oldLen -and (Get-Item $tmp).Length -lt $oldLen) {
        Remove-Item $tmp
        Add-Content -Path $log -Value "$(Get-Date -Format o) ABORTED shrink guard: $name remote smaller than local ($oldLen bytes)"
        continue
    }
    Move-Item -Force $tmp $final
    Add-Content -Path $log -Value "$(Get-Date -Format o) mirrored $name $((Get-Item $final).Length) bytes"
}

# --- canary: N days of Fitbit-notification silence means the listener or its string-matching
# broke. Silence must never read as calm -> one high-priority alert, re-armed on recovery.
$CANARY_DAYS = 4
$flagsFile = Join-Path $dest "flags.jsonl"
if (Test-Path $flagsFile) {
    $cutoff = (Get-Date).AddDays(-$CANARY_DAYS)
    $recent = 0
    foreach ($line in Get-Content $flagsFile -ErrorAction SilentlyContinue) {
        try {
            $o = $line | ConvertFrom-Json
            $t = [DateTimeOffset]::FromUnixTimeSeconds([long]$o.time).LocalDateTime
            if ($t -gt $cutoff) { $recent++ }
        } catch {}
    }
    $alerted = Join-Path $dest "canary-alerted.flag"
    $streamAge = (Get-Date) - (Get-Item $flagsFile).CreationTime
    if ($recent -eq 0 -and $streamAge.TotalDays -ge $CANARY_DAYS) {
        if (-not (Test-Path $alerted)) {
            try {
                $hdr = @{ Title = "wristwork canary"; Priority = "high" }
                if ($cfg['NTFY_TOKEN_SVC']) { $hdr['Authorization'] = "Bearer $($cfg['NTFY_TOKEN_SVC'])" }
                Invoke-RestMethod -Method Post -Uri "$($cfg['NTFY_BASE_URL'])/$($cfg['TOPIC_AGENTS'])" `
                    -Body "CANARY: no Fitbit notifications captured in $CANARY_DAYS days - flag listener may be broken" `
                    -Headers $hdr -TimeoutSec 10 | Out-Null
                New-Item -ItemType File -Force $alerted | Out-Null
            } catch {}
        }
    } elseif (Test-Path $alerted) { Remove-Item $alerted -Force }
    Add-Content -Path $log -Value "$(Get-Date -Format o) canary: $recent fitbit notifications in last $CANARY_DAYS days"
}
