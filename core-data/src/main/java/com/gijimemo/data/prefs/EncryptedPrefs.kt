package com.gijimemo.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface SecurePrefs {
    fun putApiKey(ref: String, key: String)
    fun getApiKey(ref: String): String?
    fun removeApiKey(ref: String)
}

@Singleton
class EncryptedPrefs @Inject constructor(
    @ApplicationContext context: Context
) : SecurePrefs {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "encrypted_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun putApiKey(ref: String, key: String) {
        prefs.edit().putString(ref, key).apply()
    }

    override fun getApiKey(ref: String): String? = prefs.getString(ref, null)

    override fun removeApiKey(ref: String) {
        prefs.edit().remove(ref).apply()
    }
}
