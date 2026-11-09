# Copyright (c) A|iens. All rights reserved.

<#
.SYNOPSIS
    Adds the manual "Check for updates" keys to values/strings.xml and to
    every supported locale.

.DESCRIPTION
    The updater gained a Settings row a user can tap, so five new keys came
    with it: the row's title, its subtitle (which carries the installed
    version), the in-flight label, and the two outcomes that have no dialog
    of their own — "up to date" and "couldn't check".

    Two constraints shape this script:

      - LocaleStringsTest requires every base key to exist in every
        locale, so a new base key that is not injected everywhere turns
        a passing test into a failing one.
      - The files are written as UTF-8 without a BOM and with CRLF line
        endings, which is what the Android resource compiler expects and
        what the rest of the resource tree uses.

    Hindi gets a real translation, because the project owner reads Hindi;
    the other locales take the English copy — the same result the resource
    system would have produced by falling back, with the test satisfied.

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
#
# The typographic apostrophe (U+2019) is kept as-is in the text — it is what
# the rest of the resource tree uses and it needs no XML escaping. PowerShell
# itself treats it as a string delimiter, so these values are double-quoted.
$base = @(
    @{ name = 'update_check_title'; value = 'Check for updates' },
    @{ name = 'update_check_subtitle'; value = 'Installed version %1$s' },
    @{ name = 'update_check_checking'; value = "Checking for updates…" },
    @{ name = 'update_check_up_to_date'; value = "You’re on the latest version." },
    @{ name = 'update_check_failed'; value = "Couldn’t check for updates. Try again later." }
)

$hindi = @{
    'update_check_title' = 'अपडेट देखें'
    'update_check_subtitle' = 'इंस्टॉल किया गया वर्ज़न %1$s'
    'update_check_checking' = 'अपडेट देख रहे हैं…'
    'update_check_up_to_date' = 'आप नवीनतम वर्ज़न पर हैं।'
    'update_check_failed' = 'अपडेट नहीं देख सके। बाद में कोशिश करें।'
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
        Write-Host ("{0}: would add {1} key(s)" -f $label, $pending.Count)
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
    $block.Add('    <!-- Updater: manual "Check for updates" row -->')
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
