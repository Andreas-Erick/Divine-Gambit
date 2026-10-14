package com.andreaserick.multiversechess.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the AI opponent and puzzle designer come from. */
enum class AiProvider { OFF, GEMINI, SERVER, ON_DEVICE }

/**
 * The player's AI choice: which [provider], and how to reach it. API keys are kept encrypted with a
 * key in the Android Keystore that never leaves the phone.
 */
data class AiSettings(
    val provider: AiProvider = AiProvider.OFF,
    val geminiKey: String = "",
    val geminiModel: String = "",
    val serverUrl: String = "",
    val serverModel: String = "",
    val serverKey: String = "",
    /** The on-device model's file name in [AiStore.modelsDir]. */
    val deviceModel: String = "",
) {
    /** Whether the chosen provider has everything it needs. */
    val isReady: Boolean get() = when (provider) {
        AiProvider.OFF -> false
        AiProvider.GEMINI -> geminiKey.isNotBlank() && geminiModel.isNotBlank()
        AiProvider.SERVER -> serverUrl.isNotBlank() && serverModel.isNotBlank()
        AiProvider.ON_DEVICE -> deviceModel.isNotBlank()
    }
}

/** Keeps [AiSettings] on the phone, and the on-device models' files. */
class AiStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)

    /** Downloaded models; left out of backups (see backup_rules.xml), as they're large and can be downloaded again. */
    val modelsDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    fun load(): AiSettings = AiSettings(
        provider = prefs.getString("provider", null)?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() } ?: AiProvider.OFF,
        geminiKey = secret("gemini_key"),
        geminiModel = prefs.getString("gemini_model", "").orEmpty(),
        serverUrl = prefs.getString("server_url", "").orEmpty(),
        serverModel = prefs.getString("server_model", "").orEmpty(),
        serverKey = secret("server_key"),
        deviceModel = prefs.getString("device_model", "").orEmpty(),
    )

    fun save(settings: AiSettings) {
        prefs.edit {
            putString("provider", settings.provider.name)
            putString("gemini_key", seal(settings.geminiKey))
            putString("gemini_model", settings.geminiModel)
            putString("server_url", settings.serverUrl)
            putString("server_model", settings.serverModel)
            putString("server_key", seal(settings.serverKey))
            putString("device_model", settings.deviceModel)
        }
    }

    /** The model [settings] describe, or null if they're incomplete. */
    fun model(settings: AiSettings): LanguageModel? {
        if (!settings.isReady) return null
        return when (settings.provider) {
            AiProvider.OFF -> null
            AiProvider.GEMINI -> GeminiModel(settings.geminiKey, settings.geminiModel)
            AiProvider.SERVER -> ServerModel(settings.serverUrl, settings.serverModel, settings.serverKey.ifBlank { null })
            AiProvider.ON_DEVICE -> OnDeviceModel(
                File(modelsDir, settings.deviceModel),
                DeviceModels.byFile(settings.deviceModel)?.name ?: settings.deviceModel.substringBeforeLast('.'),
                context.cacheDir,
            )
        }
    }

    private fun secret(name: String): String = prefs.getString(name, null)?.let(::open).orEmpty()

    /** [text] encrypted with the Keystore key, as Base64 (IV first); empty stays empty. */
    private fun seal(text: String): String {
        if (text.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    /** The text [sealed] by [seal], or empty if it can't be opened (e.g. restored onto another phone). */
    private fun open(sealed: String): String = runCatching {
        if (sealed.isEmpty()) return ""
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        }
        String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH))
    }.getOrDefault("")

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "ai_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
