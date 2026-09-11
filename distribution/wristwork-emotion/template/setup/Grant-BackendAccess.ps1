[CmdletBinding()]
param([string]$UserName = 'emotion')

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$envPath = Join-Path $root 'backend/.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    throw 'Run New-LocalConfig.ps1 first.'
}
$values = @{}
Get-Content -LiteralPath $envPath | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $values[$matches[1].Trim()] = $matches[2].Trim() }
}
Push-Location (Join-Path $root 'backend')
try {
    foreach ($key in @('TOPIC_TAGS', 'TOPIC_FLAGS', 'TOPIC_HEALTH', 'TOPIC_PROMPTS')) {
        $topic = $values[$key]
        if (-not $topic) { throw "Missing $key in backend/.env" }
        & docker compose exec ntfy ntfy access $UserName $topic rw
        if ($LASTEXITCODE -ne 0) { throw "Could not grant access to $key" }
    }
} finally {
    Pop-Location
}
Write-Output "Granted $UserName read-write access to the four private topics."
