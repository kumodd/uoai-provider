package com.uoai.provider

import android.app.Application
import com.uoai.core.database.AppDatabase
import com.uoai.core.resources.ComputeResourceCoordinator
import com.uoai.plugins.PluginRegistry
import com.uoai.provider.di.AppScope
import com.uoai.providerapi.AsrTempFileStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ProviderApplication : Application() {
    @Inject lateinit var db: AppDatabase
    @Inject lateinit var plugins: PluginRegistry
    @Inject lateinit var asrTempStore: AsrTempFileStore
    @Inject lateinit var resourceCoordinator: ComputeResourceCoordinator
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // NFR-04: requests left QUEUED/RUNNING by a killed process can never complete; close them out
        // and sweep any orphaned temporary ASR upload files left behind by process death.
        scope.launch(Dispatchers.IO) {
            asrTempStore.sweepAll()
            plugins.ensureDefaultsSeeded()
            db.requestDao().failStale(System.currentTimeMillis())
            db.requestDao().prune(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        resourceCoordinator.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        resourceCoordinator.onLowMemory()
    }
}
