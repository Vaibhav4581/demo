package org.mesh.emergency

import android.app.Application
import mesh.android.di.meshAndroidModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class MeshApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(Level.ERROR)
            androidContext(this@MeshApplication)
            modules(meshAndroidModule)
        }

        mesh.android.service.HousekeepingWorker.enqueuePeriodicHousekeeping(this)
    }
}
