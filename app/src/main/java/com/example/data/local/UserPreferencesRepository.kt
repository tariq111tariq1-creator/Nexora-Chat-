package com.example.data.local

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "offline_chat_prefs")

data class SupportedLanguage(
    val tag: String,
    val nativeName: String,
    val englishName: String,
    val isRtl: Boolean = false
)

val SUPPORTED_LANGUAGES = listOf(
    SupportedLanguage("ar", "العربية", "Arabic", isRtl = true),
    SupportedLanguage("en", "English", "English"),
    SupportedLanguage("fr", "Français", "French"),
    SupportedLanguage("es", "Español", "Spanish"),
    SupportedLanguage("de", "Deutsch", "German"),
    SupportedLanguage("tr", "Türkçe", "Turkish"),
    SupportedLanguage("zh-CN", "简体中文", "Chinese (Simplified)"),
    SupportedLanguage("hi", "हिन्दी", "Hindi"),
    SupportedLanguage("ru", "Русский", "Russian"),
    SupportedLanguage("pt", "Português", "Portuguese")
)

data class UserProfileSettings(
    val deviceId: String,
    val displayName: String,
    val avatarColorIndex: Int,
    val languageTag: String,
    val themePreference: ThemePreference,
    val encryptionEnabled: Boolean,
    val roomPin: String,
    val onboardingCompleted: Boolean,
    val preferredMode: ChannelMode = ChannelMode.ONLINE,
    val offlineOnlyBypass: Boolean = false,
    val cloudShareCode: String = ""
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val AVATAR_COLOR_INDEX = intPreferencesKey("avatar_color_index")
        val LANGUAGE_TAG = stringPreferencesKey("language_tag")
        val THEME_PREF = stringPreferencesKey("theme_preference")
        val ENCRYPTION_ENABLED = booleanPreferencesKey("encryption_enabled")
        val ROOM_PIN = stringPreferencesKey("room_pin")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val PREFERRED_MODE = stringPreferencesKey("preferred_mode")
        val OFFLINE_ONLY_BYPASS = booleanPreferencesKey("offline_only_bypass")
        val CLOUD_SHARE_CODE = stringPreferencesKey("cloud_share_code")
    }

    val settingsFlow: Flow<UserProfileSettings> = context.dataStore.data.map { prefs ->
        val defaultDeviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(24)
        val systemTag = Locale.getDefault().language.let { lang ->
            when (lang) {
                "zh" -> "zh-CN"
                in SUPPORTED_LANGUAGES.map { it.tag } -> lang
                else -> "en"
            }
        }
        val themeStr = prefs[Keys.THEME_PREF] ?: ThemePreference.DARK.name
        val themePref = runCatching { ThemePreference.valueOf(themeStr) }.getOrDefault(ThemePreference.DARK)
        val modeStr = prefs[Keys.PREFERRED_MODE] ?: ChannelMode.ONLINE.name
        val preferredMode = runCatching { ChannelMode.valueOf(modeStr) }.getOrDefault(ChannelMode.ONLINE)

        UserProfileSettings(
            deviceId = prefs[Keys.DEVICE_ID] ?: generateStableLocalId(),
            displayName = prefs[Keys.DISPLAY_NAME] ?: defaultDeviceName,
            avatarColorIndex = prefs[Keys.AVATAR_COLOR_INDEX] ?: 0,
            languageTag = prefs[Keys.LANGUAGE_TAG] ?: systemTag,
            themePreference = themePref,
            encryptionEnabled = prefs[Keys.ENCRYPTION_ENABLED] ?: true,
            roomPin = prefs[Keys.ROOM_PIN] ?: "",
            onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: false,
            preferredMode = preferredMode,
            offlineOnlyBypass = prefs[Keys.OFFLINE_ONLY_BYPASS] ?: false,
            cloudShareCode = prefs[Keys.CLOUD_SHARE_CODE] ?: ""
        )
    }

    suspend fun ensureDeviceIdInitialized() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_ID].isNullOrBlank()) {
                prefs[Keys.DEVICE_ID] = generateStableLocalId()
            }
        }
    }

    suspend fun completeOnboarding(displayName: String, languageTag: String) {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_ID].isNullOrBlank()) {
                prefs[Keys.DEVICE_ID] = generateStableLocalId()
            }
            prefs[Keys.DISPLAY_NAME] = displayName.trim().ifEmpty { Build.MODEL }
            prefs[Keys.LANGUAGE_TAG] = languageTag
            prefs[Keys.ONBOARDING_COMPLETED] = true
        }
    }

    suspend fun updatePreferredMode(mode: ChannelMode) {
        context.dataStore.edit { prefs ->
            prefs[Keys.PREFERRED_MODE] = mode.name
        }
    }

    suspend fun setOfflineOnlyBypass(bypass: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.OFFLINE_ONLY_BYPASS] = bypass
            if (bypass) {
                prefs[Keys.PREFERRED_MODE] = ChannelMode.OFFLINE.name
            } else {
                prefs[Keys.PREFERRED_MODE] = ChannelMode.ONLINE.name
            }
        }
    }

    suspend fun updateCloudShareCode(code: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CLOUD_SHARE_CODE] = code
        }
    }

    suspend fun updateDisplayName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DISPLAY_NAME] = name.trim().ifEmpty { Build.MODEL }
        }
    }

    suspend fun updateAvatarColorIndex(index: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.AVATAR_COLOR_INDEX] = index
        }
    }

    suspend fun updateLanguageTag(tag: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LANGUAGE_TAG] = tag
        }
    }

    suspend fun updateThemePreference(theme: ThemePreference) {
        context.dataStore.edit { prefs ->
            prefs[Keys.THEME_PREF] = theme.name
        }
    }

    suspend fun updateSecuritySettings(encryptionEnabled: Boolean, roomPin: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ENCRYPTION_ENABLED] = encryptionEnabled
            prefs[Keys.ROOM_PIN] = roomPin.trim()
        }
    }

    private fun generateStableLocalId(): String {
        return "OC-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
    }
}
