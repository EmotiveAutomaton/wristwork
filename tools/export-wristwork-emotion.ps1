[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
$sourceRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$destinationRoot = [IO.Path]::GetFullPath($Destination)
$comparison = if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    [StringComparison]::OrdinalIgnoreCase
} else {
    [StringComparison]::Ordinal
}

if ($destinationRoot.Equals($sourceRoot, $comparison) -or
    $sourceRoot.StartsWith($destinationRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) +
        [IO.Path]::DirectorySeparatorChar, $comparison)) {
    throw "Destination must not be the wristwork repository or one of its parents: $destinationRoot"
}
if (Test-Path -LiteralPath $destinationRoot) {
    $existing = Get-Item -LiteralPath $destinationRoot -Force
    if ($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Refusing to replace a reparse-point destination: $destinationRoot"
    }
    Remove-Item -LiteralPath $destinationRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null

function Copy-RelativeFile([string]$RelativePath) {
    $source = Join-Path $sourceRoot $RelativePath
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Required export source is missing: $RelativePath"
    }
    $target = Join-Path $destinationRoot $RelativePath
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Force
}

# This list is the privacy boundary. Adding a file to wristwork does not put it in the
# reusable repository; it must be reviewed and added here deliberately.
$sharedFiles = @(
    '.gitattributes',
    'build.gradle.kts',
    'gradle.properties',
    'gradlew',
    'gradlew.bat',
    'gradle/libs.versions.toml',
    'gradle/wrapper/gradle-wrapper.jar',
    'gradle/wrapper/gradle-wrapper.properties',
    'app/proguard-rules.pro',
    'app/src/main/java/com/emotiveautomaton/wristwork/complication/HealthComplicationService.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/complication/StateComplicationService.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/data/CurrentState.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/data/SpokenNote.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/data/StateNames.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/data/TagDao.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/data/TagDb.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/data/TagEvent.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/flags/FlagListenerService.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/health/PassiveDataService.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/health/SensorInventory.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/net/NtfyClient.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/ui/TagActivity.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/ui/Theme.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/work/CueWorker.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/work/DrainWorker.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/work/PromptDismissReceiver.kt',
    'app/src/main/java/com/emotiveautomaton/wristwork/work/PromptWorker.kt',
    'app/src/main/res/drawable/ic_health.xml',
    'app/src/main/res/drawable/ic_state.xml',
    'app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
    'app/src/test/java/com/emotiveautomaton/wristwork/data/CurrentStateTest.kt',
    'tools/hooks/secret-grep.sh'
)
$sharedFiles | ForEach-Object { Copy-RelativeFile $_ }

$templateRoot = Join-Path $sourceRoot 'distribution/wristwork-emotion/template'
if (-not (Test-Path -LiteralPath $templateRoot -PathType Container)) {
    throw "Export template is missing: $templateRoot"
}
Get-ChildItem -LiteralPath $templateRoot -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($templateRoot.Length).TrimStart(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $target = Join-Path $destinationRoot $relative
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
    Copy-Item -LiteralPath $_.FullName -Destination $target -Force
}

Write-Output "Exported wristwork-emotion to $destinationRoot"
