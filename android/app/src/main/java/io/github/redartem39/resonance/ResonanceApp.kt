package io.github.redartem39.resonance

import android.app.Application
import io.github.redartem39.resonance.data.Store
import io.github.redartem39.resonance.data.Yt

class ResonanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Yt.init(this)
    }
}
