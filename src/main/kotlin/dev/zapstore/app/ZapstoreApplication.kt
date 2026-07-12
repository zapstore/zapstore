package dev.zapstore.app

import android.app.Application

class ZapstoreApplication : Application() {
    val catalogRepository: CatalogRepository by lazy {
        PurpleQuartzCatalogRepository(this)
    }
}
