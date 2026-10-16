# Publish FreeMusic v1.19 GitHub release.
# Pre-requisite: a GitHub Personal Access Token (classic) with `repo` scope.
# Set it before running this script:
#   $env:GITHUB_TOKEN = "ghp_..."
#   $env:GITHUB_USER  = "ihimanshunayak"   # if not already set in git config
#
# What this script does (no double-uploads):
#   1. POST /repos/ihimanshunayak/FreeMusic/releases (create the release)
#   2. For each of the 4 APKs, upload via the returned `upload_url`
#   3. Print the release HTML URL when done
#
# APKs are read from app/build/outputs/apk/prod/release/

$ErrorActionPreference = "Stop"

if (-not $env:GITHUB_TOKEN) {
    throw "GITHUB_TOKEN env var is not set. Set `$env:GITHUB_TOKEN = 'ghp_...' first."
}

$owner      = "ihimanshunayak"
$repo       = "FreeMusic"
$tag        = "v1.19"
$target     = "windows-desktop"
$title      = "FreeMusic v1.19 -- profile hero + edit profile dialog"
$apkDir     = Join-Path (Get-Location) "app\build\outputs\apk\prod\release"
$notesFile  = Join-Path (Get-Location) "scripts\release-notes-v1.19.md"

if (-not (Test-Path $notesFile)) {
    throw "Release notes file not found: $notesFile"
}

$notes = Get-Content -Raw -Path $notesFile

$headers = @{
    "Authorization" = "Bearer $env:GITHUB_TOKEN"
    "Accept"        = "application/vnd.github+json"
    "User-Agent"    = "freemusic-publisher"
    "X-GitHub-Api-Version" = "2022-11-28"
}

# 1. Create the release
$createBody = @{
    tag_name         = $tag
    target_commitish = $target
    name             = $title
    body             = $notes
    draft            = $false
    prerelease       = $false
    generate_release_notes = $false
} | ConvertTo-Json -Depth 5

Write-Host "Creating release $tag targeting $target ..."
$release = Invoke-RestMethod -Method Post `
    -Uri "https://api.github.com/repos/$owner/$repo/releases" `
    -Headers $headers `
    -ContentType "application/json" `
    -Body $createBody

Write-Host "Created release id=$($release.id) url=$($release.html_url)"

# 2. Upload assets
$apks = Get-ChildItem -Path $apkDir -Filter "*.apk" | Sort-Object Name
foreach ($apk in $apks) {
    $uploadUrlTemplate = $release.upload_url
    # upload_url looks like: https://uploads.github.com/repos/.../releases/{id}/assets{?name,label}
    $uploadUrl = ($uploadUrlTemplate -replace "\{.*$", "") + "?name=" + [System.Uri]::EscapeDataString($apk.Name)
    Write-Host "Uploading $($apk.Name) ($($apk.Length) bytes) ..."
    $assetHeaders = @{
        "Authorization" = $headers["Authorization"]
        "Accept"        = "application/vnd.github+json"
        "User-Agent"    = $headers["User-Agent"]
        "X-GitHub-Api-Version" = $headers["X-GitHub-Api-Version"]
        "Content-Type"  = "application/vnd.android.package-archive"
    }
    Invoke-RestMethod -Method Post `
        -Uri $uploadUrl `
        -Headers $assetHeaders `
        -InFile $apk.FullName `
        -ContentType "application/vnd.android.package-archive" | Out-Null
    Write-Host "  -> uploaded"
}

Write-Host ""
Write-Host "Done. Release: $($release.html_url)"