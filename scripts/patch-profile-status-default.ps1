# Patch profile_status_default apostrophe in every locale file.
# LocaleStringsTest + AAPT both require escaped apostrophe in Android strings.

$root = "C:\Users\HimanshuNayak\Documents\GITHUB\FreeMusic\app\src\main\res"
$locales = @("de","es","fr","he","hi","id","it","ja","pl","pt","ru","th","tr","vi","zh")

$pattern = 'name="profile_status_default">What's playing lately</string>'
$replacement = 'name="profile_status_default">What\'s playing lately</string>'

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

foreach ($tag in $locales) {
    $file = Join-Path $root ("values-" + $tag + "\strings.xml")
    if (-not (Test-Path $file)) { continue }
    $raw = [System.IO.File]::ReadAllText($file, $utf8NoBom)
    if ($raw.Contains($pattern)) {
        $new = $raw.Replace($pattern, $replacement)
        [System.IO.File]::WriteAllText($file, $new, $utf8NoBom)
        Write-Host ("$tag : patched")
    } else {
        Write-Host ("$tag : no match")
    }
}
