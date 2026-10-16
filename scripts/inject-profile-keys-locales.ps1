# One-shot localization fallback injector.
# Purpose: add new English string keys to every supported locale's
#          values-<tag>/strings.xml after the account_scrobbling anchor.
# LocaleStringsTest enforces that every key in values/strings.xml exists
# in every locale; we satisfy that with English fallbacks.
# Workflow: FreeMusic v1.19

$ErrorActionPreference = "Stop"

$root = "C:\Users\HimanshuNayak\Documents\GITHUB\FreeMusic\app\src\main\res"
$locales = @("de","es","fr","he","hi","id","it","ja","pl","pt","ru","th","tr","vi","zh")

$injected = @(
    '',
    '    <!-- Profile hero + edit profile dialog -->',
    '    <string name="profile_title">Profile</string>',
    '    <string name="profile_signed_in_as">Signed in as %1$s</string>',
    "    <string name=`"profile_status_default`">What`'s playing lately</string>",
    '    <string name="profile_bio_default">No bio yet</string>',
    '    <string name="edit_profile">Edit profile</string>',
    '    <string name="edit_profile_save">Save</string>',
    '    <string name="edit_profile_cancel">Cancel</string>',
    '    <string name="edit_profile_field_display_name">Display name</string>',
    '    <string name="edit_profile_field_status">Status</string>',
    '    <string name="edit_profile_field_bio">Bio</string>',
    '    <string name="edit_profile_avatar_alt">Profile picture</string>',
    '    <string name="edit_profile_change_avatar">Change picture</string>',
    '    <string name="edit_profile_remove_avatar">Remove</string>',
    '    <string name="edit_profile_display_name_hint">Visible to your connections</string>',
    '    <string name="edit_profile_status_hint">A short line about your listening mood</string>',
    '    <string name="edit_profile_bio_hint">A few lines about your music taste</string>',
    '    <string name="edit_profile_display_name_required">Display name cannot be empty</string>',
    '    <string name="edit_profile_status_too_long">Status is too long</string>',
    '    <string name="edit_profile_bio_too_long">Bio is too long</string>',
    '    <string name="stat_hours_listened">Hours listened</string>',
    '    <string name="stat_tracks_played">Tracks played</string>',
    '    <string name="stat_playlists">Playlists</string>',
    '    <string name="stat_sources">Sources</string>'
)

foreach ($tag in $locales) {
    $file = Join-Path $root "values-$tag\strings.xml"
    if (-not (Test-Path $file)) {
        Write-Warning "Missing: $file"
        continue
    }

    $lines = Get-Content $file
    $markerIdx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match 'name="account_scrobbling"') {
            $markerIdx = $i
            break
        }
    }
    if ($markerIdx -lt 0) {
        Write-Warning "$tag : no account_scrobbling anchor; skipping"
        continue
    }

    $hasProfileTitle = ($lines | Where-Object { $_ -match 'name="profile_title"' }) | Select-Object -First 1
    if ($hasProfileTitle) {
        Write-Host "$tag : already has profile_title; skipping"
        continue
    }

    $head = $lines[0..$markerIdx]
    $tail = $lines[($markerIdx + 1)..($lines.Count - 1)]
    $new  = @($head) + $injected + @($tail)

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($file, $new, $utf8NoBom)
    Write-Host "$tag : injected $((@($injected).Count)) lines after account_scrobbling"
}

Write-Host "Done."