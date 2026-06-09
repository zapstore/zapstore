package dev.zapstore.app.plugins.registration

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

/**
 * Registers the app-owned package manager plugin with every Flutter engine,
 * including headless WorkManager engines.
 *
 * Reflection keeps the reusable registration package independent of the app
 * module at compile time. The concrete plugin is bundled in the app module.
 */
class AndroidPackageManagerRegistrantPlugin : FlutterPlugin, ActivityAware {
    private var delegate: FlutterPlugin? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        val plugin =
                Class.forName("dev.zapstore.app.plugins.AndroidPackageManagerPlugin")
                        .getDeclaredConstructor()
                        .newInstance() as FlutterPlugin
        delegate = plugin
        plugin.onAttachedToEngine(binding)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        delegate?.onDetachedFromEngine(binding)
        delegate = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        (delegate as? ActivityAware)?.onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        (delegate as? ActivityAware)?.onDetachedFromActivityForConfigChanges()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        (delegate as? ActivityAware)?.onReattachedToActivityForConfigChanges(binding)
    }

    override fun onDetachedFromActivity() {
        (delegate as? ActivityAware)?.onDetachedFromActivity()
    }
}
