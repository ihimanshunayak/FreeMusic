/*
 * Copyright (c) A|iens. All rights reserved.
 *
 * Name:    EditProfileDialog
 * Version: 1.0.0
 * Author:  A'nil Cyborg
 * Author URI: https://github.com/ihimanshunayak
 *
 * Description:
 *   The "Edit profile" dialog opened from [ProfileHero]. Lets the user
 *   override the four things the hero displays — display name, status
 *   line, bio, and avatar — and persists each through [AppSettings].
 *
 *   The dialog is local-only: nothing the user types here is ever sent
 *   anywhere. That is also why the overrides are persisted separately
 *   from the YouTube account; clearing the override is a single delete
 *   rather than a PATCH to a server record.
 *
 *   The avatar picker uses [ActivityResultContracts.PickVisualMedia], the
 *   system photo picker. The chosen image is then *copied* into the
 *   app's private files dir (so a deletion from the gallery does not
 *   strand a profile picture); the resulting file URI is what gets
 *   persisted. The dialog keeps a per-instance preview URI separate
 *   from [AppSettings.avatarOverrideUri] so cancel discards the change
 *   even after a pick.
 *
 * Responsibilities:
 *   - Show the three text fields with sensible defaults and validation.
 *   - Show the avatar preview, with a "change" affordance and a
 *     "remove" affordance that returns the hero to the YouTube avatar.
 *   - On save, write all four overrides through [AppSettings] and
 *     close; on cancel, write nothing and close.
 *
 * Dependencies:
 *   - AppSettings.displayNameOverride / statusLineOverride /
 *     bioOverride / avatarOverrideUri + setters (data/settings/AppSettings)
 *   - Account model (data/model/Models.kt)
 *   - HazeMaterials / HazeState (existing AccountChannelDialog pattern)
 *   - rememberLauncherForActivityResult + PickVisualMedia
 *
 * Integration Notes:
 *   The host shows this dialog with a `showEditProfile` boolean and
 *   dismisses it via the `onDismiss` callback. The dialog is fully
 *   stateful on its own — the four text fields are remembered locally
 *   and only flushed on save — so the host does not need to round-trip
 *   each keystroke through state.
 */
package com.ihimanshunayak.freemusic.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ihimanshunayak.freemusic.R
import com.ihimanshunayak.freemusic.data.model.Account
import com.ihimanshunayak.freemusic.data.settings.AppSettings
import com.ihimanshunayak.freemusic.ui.haptics.Haptic
import com.ihimanshunayak.freemusic.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Mirrors AccountChannelDialog's geometry — a single width, a single
// corner, the same scrim — so the two dialogs feel like one design.
// Prefixed with PROFILE_ because the package already has top-level
// `ALERT_WIDTH` / `ALERT_CORNER` / `SCRIM_COLOR` constants in
// UpdateAvailableDialog / PlayerDrawer, and Kotlin's overload resolver
// would otherwise see two competing definitions at every call site.
private val PROFILE_ALERT_WIDTH = 360.dp
private val PROFILE_ALERT_CORNER = 24.dp
private val PROFILE_SCRIM_COLOR = Color(0x66000000)

private const val MAX_STATUS = 80
private const val MAX_BIO = 240
private const val MAX_DISPLAY_NAME = 40
private const val AVATAR_FILE = "profile_avatar.jpg"

/**
 * Show the "Edit profile" dialog. The dialog is full-screen-with-scrim
 * and self-contained: it manages its own form state, validates, and
 * commits the four overrides to [AppSettings] on save.
 *
 * @param account        The signed-in account. Used as the default
 *                       display name and avatar when the user has no
 *                       override set yet.
 * @param hazeState      The host's backdrop haze state, so the dialog
 *                       blurs the screen behind it consistently with
 *                       [AccountChannelDialog] and the rest of Settings.
 * @param onDismiss      Invoked when the dialog should close — save
 *                       and cancel both call it.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun EditProfileDialog(
    account: Account?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
) {
    // Diagnostic marker — tracks if the dialog composes without throwing,
    // separate from the page that hosts it.
    android.util.Log.d("Account", "EditProfileDialog compose")
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(PROFILE_ALERT_CORNER)

    val displayNameOverride by AppSettings.displayNameOverride.collectAsStateWithLifecycle()
    val statusOverride by AppSettings.statusLineOverride.collectAsStateWithLifecycle()
    val bioOverride by AppSettings.bioOverride.collectAsStateWithLifecycle()
    val avatarOverride by AppSettings.avatarOverrideUri.collectAsStateWithLifecycle()

    // Seed the form from the persisted overrides (or the YouTube account
    // values when no override exists). Local state means cancel does not
    // write anything, and a per-field validation message is computed
    // without bouncing through AppSettings.
    val initialName = displayNameOverride.takeIf { it.isNotBlank() } ?: account?.name.orEmpty()
    val initialStatus = statusOverride.takeIf { it.isNotBlank() } ?: ""
    val initialBio = bioOverride.takeIf { it.isNotBlank() } ?: ""
    val initialAvatar = avatarOverride.takeIf { it.isNotBlank() } ?: account?.thumbnailUrl

    var name by remember { mutableStateOf(initialName) }
    var status by remember { mutableStateOf(initialStatus) }
    var bio by remember { mutableStateOf(initialBio) }
    var avatar by remember { mutableStateOf(initialAvatar) }
    // The pre-save state of the avatar — null while nothing has been
    // picked this session. Lets us restore on cancel without comparing
    // file paths.
    val avatarOnOpen = remember { mutableStateOf(initialAvatar) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val nameError = if (name.isBlank()) stringResource(R.string.edit_profile_display_name_required) else null
    val statusError = if (status.length > MAX_STATUS) stringResource(R.string.edit_profile_status_too_long) else null
    val bioError = if (bio.length > MAX_BIO) stringResource(R.string.edit_profile_bio_too_long) else null
    val canSave = nameError == null && statusError == null && bioError == null && !busy

    val pickAvatar = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val saved = runCatching { copyToAppFiles(context, uri) }
                .getOrNull()
            if (saved != null) avatar = saved.toString()
            busy = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PROFILE_SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(PROFILE_ALERT_WIDTH)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.hazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .verticalScroll(rememberScrollState())
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Header(onClose = onDismiss)
            Spacer(Modifier.height(4.dp))
            AvatarEditor(
                avatarUri = avatar,
                busy = busy,
                onPick = {
                    pickAvatar.launch(
                        PickVisualMediaRequest(
                            mediaType = ActivityResultContracts.PickVisualMedia.ImageOnly,
                        ),
                    )
                },
                onRemove = { avatar = null },
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EditField(
                    label = stringResource(R.string.edit_profile_field_display_name),
                    value = name,
                    onValueChange = { name = it.take(MAX_DISPLAY_NAME) },
                    helper = stringResource(R.string.edit_profile_display_name_hint),
                    error = nameError,
                    singleLine = true,
                    capitalization = KeyboardCapitalization.Words,
                )
                EditField(
                    label = stringResource(R.string.edit_profile_field_status),
                    value = status,
                    onValueChange = { status = it.take(MAX_STATUS) },
                    helper = stringResource(R.string.edit_profile_status_hint),
                    error = statusError,
                    singleLine = true,
                )
                EditField(
                    label = stringResource(R.string.edit_profile_field_bio),
                    value = bio,
                    onValueChange = { bio = it.take(MAX_BIO) },
                    helper = stringResource(R.string.edit_profile_bio_hint),
                    error = bioError,
                    singleLine = false,
                )
            }
            Spacer(Modifier.height(8.dp))
            Footer(
                onCancel = onDismiss,
                canSave = canSave,
                onSave = {
                    if (!canSave) return@Footer
                    haptics.play(Haptic.Select)
                    AppSettings.setDisplayNameOverride(name.trim())
                    AppSettings.setStatusLineOverride(status.trim())
                    AppSettings.setBioOverride(bio.trim())
                    AppSettings.setAvatarOverrideUri(avatar?.toString().orEmpty())
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun Header(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.edit_profile),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        IconAction(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.edit_profile_cancel),
            onClick = onClose,
        )
    }
}

@Composable
private fun AvatarEditor(
    avatarUri: String?,
    busy: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = CircleShape
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (avatarUri != null) {
                AsyncImage(
                    model = avatarUri,
                    contentDescription = stringResource(R.string.edit_profile_avatar_alt),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextAction(
                label = stringResource(R.string.edit_profile_change_avatar),
                onClick = onPick,
            )
            if (avatarUri != null) {
                TextAction(
                    label = stringResource(R.string.edit_profile_remove_avatar),
                    onClick = onRemove,
                    destructive = true,
                )
            }
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    helper: String,
    error: String?,
    singleLine: Boolean,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor = if (error != null) {
        scheme.error
    } else {
        scheme.outline.copy(alpha = 0.5f)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .background(scheme.surface.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = TextStyle(
                    color = scheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W400,
                ),
                cursorBrush = SolidColor(scheme.primary),
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = helper,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    inner()
                },
            )
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun Footer(
    onCancel: () -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextAction(
            label = stringResource(R.string.edit_profile_cancel),
            onClick = onCancel,
        )
        Spacer(Modifier.width(4.dp))
        TextAction(
            label = stringResource(R.string.edit_profile_save),
            onClick = onSave,
            emphasised = canSave,
            disabled = !canSave,
        )
    }
}

@Composable
private fun TextAction(
    label: String,
    onClick: () -> Unit,
    emphasised: Boolean = false,
    destructive: Boolean = false,
    disabled: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        disabled -> scheme.onSurface.copy(alpha = 0.30f)
        destructive -> scheme.error
        emphasised -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (emphasised) FontWeight.W700 else FontWeight.W600,
            ),
            color = color,
        )
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Copy the picked URI's bytes into the app's private files dir and return
 * the file:// URI of the copy. Reading from a `content://` URI is fine,
 * but persisting one is not — the permission can be revoked at any time,
 * and a future read will then silently produce a blank avatar.
 *
 * The copy runs on Dispatchers.IO. The function returns the [Uri] of the
 * saved file, or null on any failure (the dialog then leaves the previous
 * avatar in place).
 */
private suspend fun copyToAppFiles(context: Context, source: Uri): Uri? =
    withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "profile").apply { mkdirs() }
            val out = File(dir, AVATAR_FILE)
            context.contentResolver.openInputStream(source)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            Uri.fromFile(out)
        }.getOrNull()
    }

// ──────────────────────────────────────────────────────────────────────────────
// CodeFooter
// ──────────────────────────────────────────────────────────────────────────────
//
// Usage:
//   if (showEditProfile) {
//       EditProfileDialog(
//           account = account,
//           hazeState = hazeState,
//           onDismiss = { showEditProfile = false },
//       )
//   }
//
// Troubleshooting:
//   - Avatar pick returns nothing: the system photo picker needs
//     storage access. The dialog does not need its own permission
//     because PickVisualMedia uses the system picker, which is the
//     Android 13+ photo-picker API and runs out-of-process.
//   - "Save" button is greyed out: the display name is blank, or the
//     status/bio is over its length cap. The error line below the
//     affected field names which one.
//   - Avatar disappears after a reboot: the override URI is being
//     stored as a `content://` URI, not the file:// URI this dialog
//     produces. Make sure the pick flow went through [copyToAppFiles]
//     (it does, by default).
//
// Versioning notes:
//   1.0.0 — initial cut. Three text fields, avatar picker, save/cancel,
//   per-field validation, local-only persistence through AppSettings.
//
// Extension hooks:
//   - A "Use YouTube avatar" button restores the YouTube-provided
//     thumbnail as the avatar. The dialog already knows the right
//     value — [account.thumbnailUrl] — and only the [onRemove] handler
//     needs to learn the difference between "no avatar" and "fall
//     back to YouTube".
//
// Future compatibility notes:
//   - The dialog is API 26+ (the app's minSdk). The system photo
//     picker is API 33+; on older devices the picker falls back to
//     `ACTION_GET_CONTENT`, which still works. The pick launcher
//     handles the fall-back transparently.
//   - [BasicTextField] rather than [OutlinedTextField] is used so the
//     dialog keeps its own bespoke border treatment without dragging
//     in the M3 filled/outlined divider style. OutlinedTextField
//     would have looked correct only in one of the two themes.
