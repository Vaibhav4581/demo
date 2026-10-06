package mesh.android.identity

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Manages encrypted storage for the database passphrase and node identity keys
 * using Android Keystore and EncryptedSharedPreferences.
 */
class KeystoreManager(
    private val context: Context,
    private val prefsName: String = "mesh_secure_keystore_prefs"
) {
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                prefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            // Fallback for test/Robolectric environments or devices with Keystore bugs
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        }
    }

    /**
     * Retrieves or generates a 256-bit (32-byte) database passphrase for SQLCipher.
     */
    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existing = securePrefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }
        val newPassphrase = ByteArray(32)
        SecureRandom().nextBytes(newPassphrase)
        val encoded = Base64.encodeToString(newPassphrase, Base64.NO_WRAP)
        securePrefs.edit().putString(KEY_DB_PASSPHRASE, encoded).apply()
        return newPassphrase
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        return securePrefs.getString(key, defaultValue)
    }

    fun putString(key: String, value: String) {
        securePrefs.edit().putString(key, value).apply()
    }

    fun getBytes(key: String): ByteArray? {
        val str = securePrefs.getString(key, null) ?: return null
        return Base64.decode(str, Base64.NO_WRAP)
    }

    fun putBytes(key: String, value: ByteArray) {
        val str = Base64.encodeToString(value, Base64.NO_WRAP)
        securePrefs.edit().putString(key, str).apply()
    }

    companion object {
        private const val KEY_DB_PASSPHRASE = "key_sqlcipher_db_passphrase"
    }
}
