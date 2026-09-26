package com.michele.teslawatch.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.michele.teslawatch.TeslaWatchApp

/**
 * Receives the Home Assistant token from the PC:
 *
 *   ./gradlew provisionToken
 *
 * which sends the token on adb's standard input and runs on the watch
 *   content call --uri content://com.michele.teslawatch.token --method set --arg <token>
 *
 * Only the adb shell (uid 2000) or root may call it: the caller is checked here, because Android does
 * not enforce provider permissions on call(). The manifest also requires android.permission.DUMP,
 * so ordinary apps cannot even reach the provider. Unlike a broadcast, a provider call leaves no copy
 * of its arguments in the system's broadcast history (which bug reports print).
 */
class TokenProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("only adb can change the token")
        // Providers start before Application.onCreate: use the store directly, not the application object
        val store = TokenStore.get(requireNotNull(context))
        val result = when (method) {
            METHOD_SET -> {
                val token = arg?.trim().orEmpty()
                if (!TokenStore.looksValid(token)) return Bundle().apply { putString(KEY_RESULT, "rejected") }
                store.set(token)
                "stored"
            }
            METHOD_CLEAR -> {
                store.clear()
                "cleared"
            }
            else -> return Bundle().apply { putString(KEY_RESULT, "unknown method") }
        }
        TeslaWatchApp.instanceOrNull?.repo?.onTokenChanged()
        return Bundle().apply { putString(KEY_RESULT, result) }
    }

    // Not a data provider: everything else is refused
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    private companion object {
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val METHOD_SET = "set"
        const val METHOD_CLEAR = "clear"
        const val KEY_RESULT = "result"
    }
}
