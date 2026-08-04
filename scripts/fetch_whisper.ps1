param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")),
    [string]$Commit = "2ca53bb45e38748d07b310eeb36245a7157ac882"
)

$ErrorActionPreference = "Stop"
$target = Join-Path $Root "third_party\whisper.cpp"
$parent = Split-Path $target -Parent

if (-not (Test-Path $parent)) {
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
}

if (-not (Test-Path (Join-Path $target ".git"))) {
    git clone --no-checkout https://github.com/ggerganov/whisper.cpp.git $target
}

git -C $target fetch --depth 1 origin $Commit
git -C $target checkout --detach $Commit
Write-Output "whisper.cpp checked out at $Commit"
