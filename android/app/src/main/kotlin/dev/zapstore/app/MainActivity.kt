package dev.zapstore.app

import dev.zapstore.app.plugins.silent_install.SilentInstallPlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity: FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Register our silent installation plugin
        flutterEngine.plugins.add(SilentInstallPlugin())
    }
}