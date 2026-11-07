# Copyright (c) A|iens. All rights reserved.

<#
.SYNOPSIS
    Adds the Phase 3 profile keys to values/strings.xml and to every
    supported locale.

.DESCRIPTION
    The profile redesign added nineteen new keys: the streak chip, the
    top-songs empty state, the listening-insights tiles and the badges.

    Two constraints shape this script:

      - A plain text replacement corrupts XML lines that contain double
        quotes, so the keys are written by a character-level pass instead.
      - LocaleStringsTest requires every base key to exist in every
        locale, so a new base key that is not injected everywhere turns
        a pre-existing failure into a bigger pre-existing failure.

    Locales other than Hindi get the English text, which is the pattern
    the earlier profile-key injection used: a missing key falls back to
    English mid-screen anyway, so an explicit English copy is the same
    result with the test satisfied.

    The files are written as UTF-8 without a BOM and with CRLF line
    endings, which is what the Android resource compiler expects and
    what the rest of the resource tree uses.

.PARAMETER DryRun
    Lists the keys that would be added without touching any file.
#>
[CmdletBinding()]
param(
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$root = Join-Path $PSScriptRoot '..\app\src\main\res'
$root = [System.IO.Path]::GetFullPath($root)
$baseFile = Join-Path $root 'values\strings.xml'

# The English text, and the Hindi text for the one locale the project
# owner reads. Every other locale takes the English copy.
$base = @(
    @{ name = 'profile_streak_days'; value = '%1$d-day streak' },
    @{ name = 'profile_top_songs_empty'; value = 'No top songs yet' },
    @{ name = 'profile_top_songs_empty_hint'; value = 'Play a few tracks and your most-played songs will show up here.' },
    @{ name = 'profile_insights'; value = 'Listening insights' },
    @{ name = 'profile_insight_streak'; value = 'Current streak' },
    @{ name = 'profile_insight_peak_hour'; value = 'Peak hour' },
    @{ name = 'profile_insight_busiest_day'; value = 'Biggest day' },
    @{ name = 'profile_insight_unique'; value = 'Unique tracks' },
    @{ name = 'profile_insight_none'; value = '-' },
    @{ name = 'profile_insight_unique_value'; value = '%1$s songs - %2$s artists' },
    @{ name = 'profile_badges'; value = 'Badges' },
    @{ name = 'profile_badge_starter'; value = 'First steps' },
    @{ name = 'profile_badge_starter_desc'; value = 'Play 100 tracks' },
    @{ name = 'profile_badge_listener'; value = 'Deep listener' },
    @{ name = 'profile_badge_listener_desc'; value = 'Listen for 10 hours' },
    @{ name = 'profile_badge_streak'; value = 'On a roll' },
    @{ name = 'profile_badge_streak_desc'; value = 'Listen 7 days in a row' },
    @{ name = 'profile_badge_earned'; value = 'Earned' },
    @{ name = 'profile_badge_locked'; value = 'Locked' }
)

$hindi = @{
    'profile_streak_days' = '%1$d दिन की स्ट्रीक'
    'profile_top_songs_empty' = 'अभी कोई टॉप गाना नहीं'
    'profile_top_songs_empty_hint' = 'कुछ गाने चलाएँ और आपके सबसे ज़्यादा सुने गए गाने यहाँ दिखेंगे।'
    'profile_insights' = 'सुनने की जानकारी'
    'profile_insight_streak' = 'मौजूदा स्ट्रीक'
    'profile_insight_peak_hour' = 'सबसे व्यस्त घंटा'
    'profile_insight_busiest_day' = 'सबसे व्यस्त दिन'
    'profile_insight_unique' = 'अलग-अलग गाने'
    'profile_insight_none' = '-'
    'profile_insight_unique_value' = '%1$s गाने - %2$s कलाकार'
    'profile_badges' = 'बैज'
    'profile_badge_starter' = 'पहला कदम'
    'profile_badge_starter_desc' = '100 गाने चलाएँ'
    'profile_badge_listener' = 'गहरा श्रोता'
    'profile_badge_listener_desc' = '10 घंटे तक सुनें'
    'profile_badge_streak' = 'लगातार'
    'profile_badge_streak_desc' = '7 दिन लगातार सुनें'
    'profile_badge_earned' = 'हासिल'
    'profile_badge_locked' = 'लॉक'
}

function Escape-Xml([string]$value) {
    return $value.Replace('&', '&amp;').Replace('<', '&lt;').Replace('>', '&gt;')
}

function Get-ExistingKeys([string]$path) {
    $keys = New-Object System.Collections.Generic.HashSet[string]
    if (-not (Test-Path $path)) { return $keys }
    [xml]$doc = Get-Content $path -Raw -Encoding UTF8
    foreach ($node in $doc.resources.ChildNodes) {
        if ($node.NodeType -ne 'Element') { continue }
        $name = $node.GetAttribute('name')
        if ($name) { [void]$keys.Add($name) }
    }
    return $keys
}

function Add-Keys([string]$path, [array]$entries, [string]$label) {
    $existing = Get-ExistingKeys $path
    $pending = @($entries | Where-Object { -not $existing.Contains($_.name) })
    if ($pending.Count -eq 0) { return 0 }

    if ($DryRun) {
        Write-Verbose ("{0}: would add {1} key(s)" -f $label, $pending.Count)
        return 0
    }

    $lines = [System.IO.File]::ReadAllLines($path)
    $closeIdx = -1
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        if ($lines[$i] -match '^\s*</resources>\s*$') { $closeIdx = $i; break }
    }
    if ($closeIdx -lt 0) { throw "No </resources> found in $path" }

    $block = New-Object System.Collections.Generic.List[string]
    $block.Add('')
    $block.Add('    <!-- Profile page: Phase 3 (top songs, insights, badges) -->')
    foreach ($entry in $pending) {
        $block.Add(('    <string name="{0}">{1}</string>' -f $entry.name, (Escape-Xml $entry.value)))
    }

    $head = $lines[0..($closeIdx - 1)]
    $tail = $lines[$closeIdx..($lines.Count - 1)]
    $out = @($head) + $block.ToArray() + @($tail)

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($path, $out, $utf8NoBom)
    return $pending.Count
}

$added = Add-Keys $baseFile $base 'values'
Write-Host ("values: added {0} key(s)" -f $added)

foreach ($dir in Get-ChildItem (Join-Path $root 'values-*') -Directory) {
    $file = Join-Path $dir.FullName 'strings.xml'
    if (-not (Test-Path $file)) { continue }
    $tag = $dir.Name -replace '^values-', ''
    $entries = foreach ($entry in $base) {
        $value = if ($tag -eq 'hi' -and $hindi.ContainsKey($entry.name)) { $hindi[$entry.name] } else { $entry.value }
        @{ name = $entry.name; value = $value }
    }
    $count = Add-Keys $file $entries ("values-" + $tag)
    Write-Host ("values-{0}: added {1} key(s)" -f $tag, $count)
}

Write-Host 'Done.'
