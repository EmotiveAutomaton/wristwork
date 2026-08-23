# Nightly pull of labels.jsonl from the NAS to this machine (the "rig").
# Registered in Windows Task Scheduler as "wristwork-labels-mirror".
# Host alias and remote path come from the gitignored config.properties (RAID_SSH_HOST,
# LABELS_PATH_RAID) — no addresses in this file. Synology sshd has no SFTP subsystem, so this
# streams over plain ssh; cmd redirection keeps it byte-exact. `-n` detaches stdin — without it
# ssh hangs forever under Task Scheduler's console-less context (learned 2026-08-23).
# Atomic: temp then move. Never lets the mirror shrink (the archive is append-only).
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
$log   = Join-Path $dest "mirror.log"
$tmp   = Join-Path $dest "labels.jsonl.tmp"
$final = Join-Path $dest "labels.jsonl"
cmd /c "ssh -n -o BatchMode=yes -o ConnectTimeout=15 $sshHost ""cat $remote"" > ""$tmp"" 2> ""$tmp.err"""
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tmp)) {
    $err = (Get-Content "$tmp.err" -Raw -ErrorAction SilentlyContinue)
    Add-Content -Path $log -Value "$(Get-Date -Format o) FAILED exit=$LASTEXITCODE :: $err"
    throw "Mirror FAILED: ssh pull exited $LASTEXITCODE :: $err"
}
Remove-Item "$tmp.err" -ErrorAction SilentlyContinue
$old = (Get-Item $final -ErrorAction SilentlyContinue).Length
if ($old -and (Get-Item $tmp).Length -lt $old) {
    Remove-Item $tmp
    Add-Content -Path $log -Value "$(Get-Date -Format o) ABORTED shrink guard: remote smaller than local ($old bytes)"
    throw "Mirror ABORTED: remote labels.jsonl smaller than the local mirror."
}
Move-Item -Force $tmp $final
Add-Content -Path $log -Value "$(Get-Date -Format o) mirrored $((Get-Item $final).Length) bytes"
