# Nightly pull of labels.jsonl from the NAS to this machine (the "rig").
# Registered in Windows Task Scheduler as "wristwork-labels-mirror" (see tools/rig/README.md).
# Host alias and remote path come from the gitignored config.properties (keys RAID_SSH_HOST,
# LABELS_PATH_RAID) — no addresses in this file. Synology sshd has no SFTP subsystem -> stream
# over plain ssh; cmd redirection keeps it byte-exact. Atomic: temp then move; never shrinks.
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
$tmp   = Join-Path $dest "labels.jsonl.tmp"
$final = Join-Path $dest "labels.jsonl"
cmd /c "ssh -o BatchMode=yes $sshHost ""cat $remote"" > ""$tmp"""
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tmp)) { throw "Mirror FAILED: ssh pull exited $LASTEXITCODE" }
$old = (Get-Item $final -ErrorAction SilentlyContinue).Length
if ($old -and (Get-Item $tmp).Length -lt $old) {
    Remove-Item $tmp
    throw "Mirror ABORTED: remote labels.jsonl smaller than local mirror ($old bytes)."
}
Move-Item -Force $tmp $final
Add-Content -Path (Join-Path $dest "mirror.log") -Value "$(Get-Date -Format o) mirrored $((Get-Item $final).Length) bytes"
