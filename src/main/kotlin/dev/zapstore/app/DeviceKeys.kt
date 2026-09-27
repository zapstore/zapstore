package dev.zapstore.app

import android.content.Context
import dev.zapstore.iolite.Crypto
import dev.zapstore.iolite.Hex
import dev.zapstore.iolite.LocalSigner

object DeviceKeys {
    fun loadOrCreate(context: Context): LocalSigner {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        if (stored != null) return LocalSigner(Hex.decode(stored))
        val secret = Crypto.randomBytes(32)
        prefs.edit().putString(KEY, Hex.encode(secret)).apply()
        return LocalSigner(secret)
    }

    private const val PREFS = "device_keys"
    private const val KEY = "nsec"
}
