package dev.zapstore.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class ZapstoreApplication : Application() {
    val catalogRepository: CatalogRepository by lazy {
        IoliteCatalogRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = catalogRepository.setRelayTrafficEnabled(true)
            override fun onStop(owner: LifecycleOwner) = catalogRepository.setRelayTrafficEnabled(false)
        })
    }
}
