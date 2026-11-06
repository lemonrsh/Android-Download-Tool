package dev.fetchly

import android.content.Context
import java.security.SecureRandom

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("fetchly", Context.MODE_PRIVATE)

    /** Random secret the browser extension must send; generated once. */
    fun token(c: Context): String {
        val s = sp(c)
        s.getString("token", null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        s.edit().putString("token", t).apply()
        return t
    }

    fun segments(c: Context) = sp(c).getInt("segments", 4)
    fun setSegments(c: Context, n: Int) = sp(c).edit().putInt("segments", n).apply()

    fun integration(c: Context) = sp(c).getBoolean("integration", false)
    fun setIntegration(c: Context, v: Boolean) = sp(c).edit().putBoolean("integration", v).apply()
}
