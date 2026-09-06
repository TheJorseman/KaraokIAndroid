param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")),
    [string]$Commit = "48f628a84833905ee4a0658ee6d4a5c915ce1997"
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
