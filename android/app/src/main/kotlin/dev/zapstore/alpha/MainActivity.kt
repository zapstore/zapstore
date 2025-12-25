package dev.zapstore.alpha

import dev.zapstore.alpha.plugins.AndroidPackageManagerPlugin
import dev.zapstore.alpha.plugins.AppRestartPlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity: FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // Register plugins
        flutterEngine.plugins.add(AndroidPackageManagerPlugin())
        flutterEngine.plugins.add(AppRestartPlugin())
    }
}