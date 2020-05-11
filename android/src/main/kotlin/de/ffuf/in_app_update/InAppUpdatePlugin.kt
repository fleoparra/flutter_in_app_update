package de.ffuf.in_app_update

import android.app.Activity
import android.app.Activity.RESULT_OK
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.annotation.NonNull
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar

class InAppUpdatePlugin : FlutterPlugin, MethodCallHandler,
    PluginRegistry.ActivityResultListener, Application.ActivityLifecycleCallbacks {

    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val channel = MethodChannel(registrar.messenger(), "in_app_update")
            val instance = InAppUpdatePlugin()
            instance.registrar = registrar
            channel.setMethodCallHandler(instance)
        }

        private const val REQUEST_CODE_START_UPDATE = 1276
    }

    private lateinit var channel: MethodChannel
    private lateinit var registrar: Registrar

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(
            flutterPluginBinding.binaryMessenger,
            "in_app_update"
        )
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }


    private var updateResult: Result? = null
    private var appUpdateInfo: AppUpdateInfo? = null
    private var appUpdateManager: AppUpdateManager? = null

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "checkForUpdate" -> checkForUpdate(result)
            "performImmediateUpdate" -> performImmediateUpdate(result)
            "startFlexibleUpdate" -> startFlexibleUpdate(result)
            "completeFlexibleUpdate" -> completeFlexibleUpdate(result)
            else -> result.notImplemented()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_CODE_START_UPDATE) {
            if (resultCode != RESULT_OK) {
                updateResult?.error("Update failed", resultCode.toString(), null)
            } else {
                updateResult?.success(null)
            }
            updateResult = null
            return true
        }
        return false
    }

    override fun onActivityCreated(activity: Activity?, savedInstanceState: Bundle?) {}

    override fun onActivityPaused(activity: Activity?) {}

    override fun onActivityStarted(activity: Activity?) {}

    override fun onActivityDestroyed(activity: Activity?) {}

    override fun onActivitySaveInstanceState(activity: Activity?, outState: Bundle?) {}

    override fun onActivityStopped(activity: Activity?) {}

    override fun onActivityResumed(activity: Activity?) {
        appUpdateManager
            ?.appUpdateInfo
            ?.addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability()
                    == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                ) {
                    requireNotNull(registrar.activity()) {
                        updateResult?.error(
                            "in_app_update requires a foreground activity",
                            null,
                            null
                        )
                        Unit
                    }
                    appUpdateManager?.startUpdateFlowForResult(
                        appUpdateInfo,
                        AppUpdateType.IMMEDIATE,
                        registrar.activity(),
                        REQUEST_CODE_START_UPDATE
                    )
                }
            }
    }

    private fun performImmediateUpdate(result: Result) = checkAppState(result) {
        updateResult = result
        appUpdateManager?.startUpdateFlowForResult(
            appUpdateInfo,
            AppUpdateType.IMMEDIATE,
            registrar.activity(),
            REQUEST_CODE_START_UPDATE
        )
    }

    private fun checkAppState(result: Result, block: () -> Unit) {
        require(this::registrar.isInitialized) {
            result.error("in_app_update requires a foreground activity", null, null)
        }
        requireNotNull(appUpdateInfo) {
            result.error("Call checkForUpdate first!", null, null)
        }
        requireNotNull(registrar.activity()) {
            result.error("in_app_update requires a foreground activity", null, null)
        }
        requireNotNull(appUpdateManager) {
            result.error("Call checkForUpdate first!", null, null)
        }
        block()
    }

    private fun startFlexibleUpdate(result: Result) = checkAppState(result) {

        updateResult = result
        appUpdateManager?.startUpdateFlowForResult(
            appUpdateInfo,
            AppUpdateType.FLEXIBLE,
            registrar.activity(),
            REQUEST_CODE_START_UPDATE
        )
        appUpdateManager?.registerListener { state ->
            if (state.installStatus() == InstallStatus.DOWNLOADED) {
                updateResult?.success(null)
                updateResult = null
            } else if (state.installErrorCode() != null) {
                updateResult?.error(
                    "Error during installation",
                    state.installErrorCode().toString(),
                    null
                )
                updateResult = null
            }
        }
    }

    private fun completeFlexibleUpdate(result: Result) = checkAppState(result) {
        appUpdateManager?.completeUpdate()
    }

    private fun checkForUpdate(result: Result) {
        require(this::registrar.isInitialized) {
            result.error("in_app_update requires a foreground activity", null, null)
        }
        requireNotNull(registrar.activity()) {
            result.error("in_app_update requires a foreground activity", null, null)
        }

        registrar.addActivityResultListener(this)
        registrar.activity().application.registerActivityLifecycleCallbacks(this)

        appUpdateManager = AppUpdateManagerFactory.create(registrar.activity())

        // Returns an intent object that you use to check for an update.
        val appUpdateInfoTask = appUpdateManager!!.appUpdateInfo

        // Checks that the platform will allow the specified type of update.
        appUpdateInfoTask.addOnSuccessListener { info ->
            appUpdateInfo = info
            if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                result.success(
                    mapOf(
                        "updateAvailable" to true,
                        "immediateAllowed" to info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                        "flexibleAllowed" to info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
                        "availableVersionCode" to info.availableVersionCode()
                    )
                )
            } else {
                result.success(
                    mapOf(
                        "updateAvailable" to false,
                        "immediateAllowed" to false,
                        "flexibleAllowed" to false,
                        "availableVersionCode" to null
                    )
                )
            }
        }
        appUpdateInfoTask.addOnFailureListener {
            result.error(it.message, null, null)
        }
    }
}
