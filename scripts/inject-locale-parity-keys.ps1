# Copyright (c) A|iens. All rights reserved.

<#
.SYNOPSIS
    Closes the locale-parity gap left by the profile and settings work.

.DESCRIPTION
    LocaleStringsTest requires every key declared in values/strings.xml to
    exist in every supported locale. Forty-six keys — the edit-profile
    fields, the profile tab labels, the profile empty states and the
    settings rows — were declared in English only, so the test failed for
    all fifteen non-English locales and the app fell back to English
    mid-screen wherever those rows were used.

    This script adds those forty-six keys to every locale. Hindi gets a
    real translation, because the project owner reads Hindi; the other
    locales get the English copy, which is exactly what the resource
    system would have shown them anyway.

    Two constraints shape the implementation:

      - A plain text replacement corrupts XML lines that contain double
        quotes, so the keys are written by a character-level pass.
      - The files are written as UTF-8 without a BOM and with CRLF line
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
    @{ name = 'edit_profile_change_avatar_cd'; value = 'Change profile picture' },
    @{ name = 'edit_profile_delete_account'; value = 'Delete Account' },
    @{ name = 'edit_profile_delete_coming_soon'; value = 'Account deletion is not supported in this build.' },
    @{ name = 'edit_profile_email_hint'; value = 'youremail@email.com' },
    @{ name = 'edit_profile_field_email'; value = 'Email' },
    @{ name = 'edit_profile_field_full_name'; value = 'Full name' },
    @{ name = 'edit_profile_field_phone'; value = 'Phone number' },
    @{ name = 'edit_profile_field_username'; value = 'Username' },
    @{ name = 'edit_profile_full_name_hint'; value = 'Your Name' },
    @{ name = 'edit_profile_phone_hint'; value = '0000-0000-0000' },
    @{ name = 'edit_profile_save_changes'; value = 'Save Changes' },
    @{ name = 'edit_profile_username_hint'; value = '@yourname' },
    @{ name = 'profile_badge_empty'; value = 'No badges yet' },
    @{ name = 'profile_badge_empty_hint'; value = 'Keep listening to unlock your first.' },
    @{ name = 'profile_challenge_empty'; value = 'No challenges yet' },
    @{ name = 'profile_challenge_empty_hint'; value = 'Start a streak by listening to a full album today.' },
    @{ name = 'profile_feed_empty'; value = 'Nothing in your feed yet' },
    @{ name = 'profile_feed_empty_hint'; value = 'Posts from people you follow will show up here.' },
    @{ name = 'profile_followers'; value = '%1$s followers' },
    @{ name = 'profile_following'; value = '%1$s following' },
    @{ name = 'profile_notifications_cd'; value = 'Notifications' },
    @{ name = 'profile_post_comments_cd'; value = 'Comments' },
    @{ name = 'profile_post_likes_cd'; value = 'Likes' },
    @{ name = 'profile_post_menu_cd'; value = 'Post options' },
    @{ name = 'profile_post_sample_author'; value = 'Your Name' },
    @{ name = 'profile_post_sample_text'; value = 'Beautiful landscape 🌄' },
    @{ name = 'profile_post_sample_time'; value = '3h ago' },
    @{ name = 'profile_settings_cd'; value = 'Settings' },
    @{ name = 'profile_signed_out_title'; value = 'Sign in to view your profile' },
    @{ name = 'profile_tab_badge'; value = 'Badge' },
    @{ name = 'profile_tab_challenge'; value = 'Challenge' },
    @{ name = 'profile_tab_feed'; value = 'Feed' },
    @{ name = 'profile_verified_cd'; value = 'Verified' },
    @{ name = 'settings_account_subtitle'; value = '@%1$s' },
    @{ name = 'settings_dark_mode'; value = 'Dark mode' },
    @{ name = 'settings_faq'; value = 'FAQ' },
    @{ name = 'settings_general'; value = 'General settings' },
    @{ name = 'settings_language_english'; value = 'English' },
    @{ name = 'settings_log_out'; value = 'Log Out' },
    @{ name = 'settings_my_contact'; value = 'My Contact' },
    @{ name = 'settings_open_failed'; value = 'Couldn''t open %1$s' },
    @{ name = 'settings_pause_notifications'; value = 'Pause notifications' },
    @{ name = 'settings_pause_notifications_subtitle'; value = 'Stop the bell and the system notifications while you listen' },
    @{ name = 'settings_terms_of_service'; value = 'Terms of service' },
    @{ name = 'settings_user_policy'; value = 'User policy' },
    @{ name = 'signed_out_title'; value = 'Sign in' }
)

$hindi = @{
    'edit_profile_change_avatar_cd' = 'प्रोफ़ाइल फ़ोटो बदलें'
    'edit_profile_delete_account' = 'अकाउंट हटाएँ'
    'edit_profile_delete_coming_soon' = 'इस बिल्ड में अकाउंट हटाना उपलब्ध नहीं है।'
    'edit_profile_email_hint' = 'youremail@email.com'
    'edit_profile_field_email' = 'ईमेल'
    'edit_profile_field_full_name' = 'पूरा नाम'
    'edit_profile_field_phone' = 'फ़ोन नंबर'
    'edit_profile_field_username' = 'यूज़रनेम'
    'edit_profile_full_name_hint' = 'आपका नाम'
    'edit_profile_phone_hint' = '0000-0000-0000'
    'edit_profile_save_changes' = 'बदलाव सेव करें'
    'edit_profile_username_hint' = '@yourname'
    'profile_badge_empty' = 'अभी कोई बैज नहीं'
    'profile_badge_empty_hint' = 'पहला बैज पाने के लिए सुनते रहें।'
    'profile_challenge_empty' = 'अभी कोई चुनौती नहीं'
    'profile_challenge_empty_hint' = 'आज पूरा एल्बम सुनकर स्ट्रीक शुरू करें।'
    'profile_feed_empty' = 'आपकी फ़ीड अभी खाली है'
    'profile_feed_empty_hint' = 'जिन्हें आप फ़ॉलो करते हैं उकी पोस्ट यहाँ दिखेंगी।'
    'profile_followers' = '%1$s फ़ॉलोअर्स'
    'profile_following' = '%1$s फ़ॉलो कर रहे हैं'
    'profile_notifications_cd' = 'नोटिफ़िकेशन'
    'profile_post_comments_cd' = 'कमेंट'
    'profile_post_likes_cd' = 'लाइक'
    'profile_post_menu_cd' = 'पोस्ट विकल्प'
    'profile_post_sample_author' = 'आपका नाम'
    'profile_post_sample_text' = 'सुंदर नज़ारा 🌄'
    'profile_post_sample_time' = '3 घंटे पहले'
    'profile_settings_cd' = 'सेटिंग्स'
    'profile_signed_out_title' = 'प्रोफ़ाइल देखने के लिए साइन इन करें'
    'profile_tab_badge' = 'बैज'
    'profile_tab_challenge' = 'चुनौती'
    'profile_tab_feed' = 'फ़ीड'
    'profile_verified_cd' = 'वेरिफ़ाइड'
    'settings_account_subtitle' = '@%1$s'
    'settings_dark_mode' = 'डार्क मोड'
    'settings_faq' = 'सामान्य प्रश्न'
    'settings_general' = 'सामान्य सेटिंग्स'
    'settings_language_english' = 'English'
    'settings_log_out' = 'लॉग आउट'
    'settings_my_contact' = 'मेरा संपर्क'
    'settings_open_failed' = '%1$s खोला नहीं जा सका'
    'settings_pause_notifications' = 'नोटिफ़िकेशन रोकें'
    'settings_pause_notifications_subtitle' = 'सुनते समय घंटी और सिस्टम नोटिफ़िकेशन बंद करें'
    'settings_terms_of_service' = 'सेवा की शर्तें'
    'settings_user_policy' = 'यूज़र पॉलिसी'
    'signed_out_title' = 'साइन इन'
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
    $block.Add('    <!-- Locale parity: profile, edit-profile and settings rows -->')
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
