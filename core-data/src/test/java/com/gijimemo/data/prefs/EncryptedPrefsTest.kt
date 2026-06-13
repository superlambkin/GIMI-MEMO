package com.gijimemo.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EncryptedPrefsTest {
    private lateinit var prefs: SecurePrefs

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = FakeSecurePrefs(context)
    }

    @Test
    fun `putApiKey and getApiKey round-trip`() {
        prefs.putApiKey("apikey_test", "secret123")
        assertThat(prefs.getApiKey("apikey_test")).isEqualTo("secret123")
    }

    @Test
    fun `getApiKey returns null for missing key`() {
        assertThat(prefs.getApiKey("apikey_missing")).isNull()
    }

    @Test
    fun `removeApiKey deletes the key`() {
        prefs.putApiKey("apikey_test", "x")
        prefs.removeApiKey("apikey_test")
        assertThat(prefs.getApiKey("apikey_test")).isNull()
    }
}

/**
 * Fake implementation using regular SharedPreferences for testing.
 * EncryptedSharedPreferences requires Android Keystore which isn't
 * available in Robolectric unit tests.
 */
private class FakeSecurePrefs(context: Context) : SecurePrefs {
    private val prefs = context.getSharedPreferences("test_prefs", Context.MODE_PRIVATE)

    override fun putApiKey(ref: String, key: String) {
        prefs.edit().putString(ref, key).apply()
    }

    override fun getApiKey(ref: String): String? = prefs.getString(ref, null)

    override fun removeApiKey(ref: String) {
        prefs.edit().remove(ref).apply()
    }
}