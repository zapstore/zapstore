package dev.zapstore.app

import dev.zapstore.app.plugins.AppRestartPlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity: FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // Register the activity-only plugin. AndroidPackageManagerPlugin is
        // auto-registered so WorkManager background engines receive it too.
        flutterEngine.plugins.add(AppRestartPlugin())
    }
}