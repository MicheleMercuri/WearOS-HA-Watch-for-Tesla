package com.michele.teslawatch

import android.app.Application
import androidx.wear.tiles.TileService
import com.michele.teslawatch.config.TokenStore
import com.michele.teslawatch.data.TeslaRepository
import com.michele.teslawatch.tile.QuickActionsTileService

class TeslaWatchApp : Application() {
    lateinit var tokens: TokenStore
        private set
    lateinit var repo: TeslaRepository
        private set

    override fun onCreate() {
        super.onCreate()
        tokens = TokenStore.get(this)
        repo = TeslaRepository(this, tokens)
        repo.onActionResult = {
            runCatching { TileService.getUpdater(this).requestUpdate(QuickActionsTileService::class.java) }
        }
        // Published last, so instanceOrNull never exposes a half-built application
        instance = this
    }

    companion object {
        lateinit var instance: TeslaWatchApp
            private set

        /** Null only before Application.onCreate (a content provider can be called that early). */
        val instanceOrNull: TeslaWatchApp? get() = if (::instance.isInitialized) instance else null
    }
}
