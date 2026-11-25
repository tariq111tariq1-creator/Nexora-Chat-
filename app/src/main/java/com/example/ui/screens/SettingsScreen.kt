package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ChannelMode
import com.example.data.local.SUPPORTED_LANGUAGES
import com.example.data.local.UserProfileSettings
import com.example.permissions.PermissionHelper
import com.example.permissions.PermissionStatusSnapshot
import com.example.ui.components.FriendQrShareDialog
import com.example.ui.components.PeerAvatar
import com.example.ui.components.PermissionsChecklistCard
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ThemePreference

@Composable
fun SettingsScreen(
    settings: UserProfileSettings,
    permissionSnapshot: PermissionStatusSnapshot,
    isSignedIn: Boolean,
    signedInEmail: String?,
    myShareCode: String,
    onGoogleSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onSelectPreferredMode: (ChannelMode) -> Unit,
    onUpdateName: (String) -> Unit,
    onUpdateAvatarColor: (Int) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectTheme: (ThemePreference) -> Unit,
    onUpdateSecurity: (encryptionEnabled: Boolean, roomPin: String) -> Unit,
    onPermissionsChanged: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var nameInput by remember(settings.displayName) { mutableStateOf(settings.displayName) }
    var roomPinInput by remember(settings.roomPin) { mutableStateOf(settings.roomPin) }
    var encryptionEnabled by remember(settings.encryptionEnabled) { mutableStateOf(settings.encryptionEnabled) }
    var showMyQrDialog by remember { mutableStateOf(false) }

    val multiPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onPermissionsChanged()
    }

    if (showMyQrDialog && myShareCode.isNotBlank()) {
        FriendQrShareDialog(
            shareCode = myShareCode,
            displayName = settings.displayName,
            onCopyCode = {
                clipboard.setText(AnnotatedString(myShareCode))
                Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showMyQrDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Cloud Account, Share Code & Default Mode Section
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Public, contentDescription = null, tint = ElectricCyan)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.section_cloud_account),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (isSignedIn) {
                        if (!signedInEmail.isNullOrBlank()) {
                            Text(
                                text = signedInEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (myShareCode.isNotBlank()) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = ElectricCyan.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = stringResource(R.string.my_share_code_title),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = myShareCode,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = ElectricCyan
                                        )
                                    }
                                    Row {
                                        IconButton(onClick = {
                                            clipboard.setText(AnnotatedString(myShareCode))
                                            Toast.makeText(context, context.getString(R.string.message_copied), Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.copy_message))
                                        }
                                        IconButton(onClick = { showMyQrDialog = true }) {
                                            Icon(Icons.Rounded.QrCode2, contentDescription = stringResource(R.string.my_qr_short))
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.default_mode_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = settings.preferredMode == ChannelMode.ONLINE,
                                onClick = { onSelectPreferredMode(ChannelMode.ONLINE) },
                                shape = SegmentedButtonDefaults.itemShape(0, 2)
                            ) {
                                Text(stringResource(R.string.mode_badge_online), maxLines = 1)
                            }
                            SegmentedButton(
                                selected = settings.preferredMode == ChannelMode.OFFLINE,
                                onClick = { onSelectPreferredMode(ChannelMode.OFFLINE) },
                                shape = SegmentedButtonDefaults.itemShape(1, 2)
                            ) {
                                Text(stringResource(R.string.mode_badge_offline), maxLines = 1)
                            }
                        }

                        OutlinedButton(
                            onClick = onSignOutClick,
                            modifier = Modifier.fillMaxWidth().testTag("sign_out_button")
                        ) {
                            Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sign_out))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.cloud_sign_in_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onGoogleSignInClick,
                            modifier = Modifier.fillMaxWidth().testTag("settings_google_sign_in_button")
                        ) {
                            Icon(Icons.Rounded.AccountCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sign_in_with_google))
                        }
                    }
                }
            }
        }

        // Profile & Identity Section
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.section_profile),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.clickable {
                                onUpdateAvatarColor((settings.avatarColorIndex + 1) % 6)
                            }
                        ) {
                            PeerAvatar(
                                name = nameInput.ifBlank { settings.displayName },
                                colorIndex = settings.avatarColorIndex,
                                isOnline = true,
                                size = 56.dp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text(stringResource(R.string.profile_name_label)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.device_id_label, settings.deviceId),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(
                            onClick = { onUpdateName(nameInput) },
                            modifier = Modifier.testTag("save_profile_button")
                        ) {
                            Text(stringResource(R.string.save_changes))
                        }
                    }
                }
            }
        }

        // 10-Language Selector Section
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.section_language),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    SUPPORTED_LANGUAGES.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { lang ->
                                val selected = settings.languageTag == lang.tag
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_lang_${lang.tag}")
                                        .clickable { onSelectLanguage(lang.tag) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text(
                                                text = lang.nativeName,
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                            Text(
                                                text = lang.englishName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (selected) {
                                            Icon(
                                                Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Theme Section
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.section_appearance),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = settings.themePreference == ThemePreference.DARK,
                            onClick = { onSelectTheme(ThemePreference.DARK) },
                            shape = SegmentedButtonDefaults.itemShape(0, 3)
                        ) {
                            Text(stringResource(R.string.theme_dark), maxLines = 1)
                        }
                        SegmentedButton(
                            selected = settings.themePreference == ThemePreference.LIGHT,
                            onClick = { onSelectTheme(ThemePreference.LIGHT) },
                            shape = SegmentedButtonDefaults.itemShape(1, 3)
                        ) {
                            Text(stringResource(R.string.theme_light), maxLines = 1)
                        }
                        SegmentedButton(
                            selected = settings.themePreference == ThemePreference.SYSTEM,
                            onClick = { onSelectTheme(ThemePreference.SYSTEM) },
                            shape = SegmentedButtonDefaults.itemShape(2, 3)
                        ) {
                            Text(stringResource(R.string.theme_system), maxLines = 1)
                        }
                    }
                }
            }
        }

        // Privacy & Local Encryption
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.section_security),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.encryption_enabled),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.encryption_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = encryptionEnabled,
                            onCheckedChange = {
                                encryptionEnabled = it
                                onUpdateSecurity(it, roomPinInput)
                            }
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = roomPinInput,
                            onValueChange = { roomPinInput = it.take(8) },
                            label = { Text(stringResource(R.string.room_pin_setting)) },
                            placeholder = { Text(stringResource(R.string.pin_input_hint)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = { onUpdateSecurity(encryptionEnabled, roomPinInput) }
                        ) {
                            Text(stringResource(R.string.save_changes))
                        }
                    }
                }
            }
        }

        // Permissions Readiness Center
        item {
            PermissionsChecklistCard(
                snapshot = permissionSnapshot,
                onRequestPermissions = { perms -> multiPermLauncher.launch(perms) },
                onOpenAppSettings = { PermissionHelper.openAppSettings(context) },
                onOpenLocationSettings = { PermissionHelper.openLocationSettings(context) },
                onOpenWifiSettings = { PermissionHelper.openWifiSettings(context) }
            )
        }
    }
}
