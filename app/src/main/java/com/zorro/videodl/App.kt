package com.zorro.videodl

import android.app.Application
import com.zorro.videodl.engine.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm the native payload so the first download does not pay for unpacking.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { YtDlpEngine.ensureInit(this@App) }
        }
    }
}
