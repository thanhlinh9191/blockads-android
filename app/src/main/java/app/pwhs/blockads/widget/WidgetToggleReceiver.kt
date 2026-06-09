package app.pwhs.blockads.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.pwhs.blockads.MainActivity
import app.pwhs.blockads.data.datastore.AppPreferences
import app.pwhs.blockads.service.AdBlockVpnService
import app.pwhs.blockads.service.RootProxyService
import app.pwhs.blockads.utils.VpnUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Internal (unexported) receiver that handles VPN toggle from the widget.
 * Kept separate from the exported AppWidgetProvider to prevent external apps
 * from toggling the VPN via broadcast.
 */
class WidgetToggleReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TOGGLE_VPN = "app.pwhs.blockads.widget.TOGGLE_VPN"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE_VPN) {
            toggleVpn(context)
            AdBlockWidgetProvider.sendUpdateBroadcast(context)
        }
    }

    private fun toggleVpn(context: Context) {
        val isRootProxyRunning = RootProxyService.isRunning
        val isVpnRunning = AdBlockVpnService.isRunning

        if (isRootProxyRunning) {
            RootProxyService.stop(context)
        } else if (isVpnRunning) {
            val stopIntent = Intent(context, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
            context.startService(stopIntent)
        } else {
            val appPrefs = AppPreferences(context)
            val routingMode = runBlocking { appPrefs.routingMode.first() }
            val isRootMode = routingMode == AppPreferences.ROUTING_MODE_ROOT

            if (!isRootMode && VpnUtils.isOtherVpnActive(context)) {
                Timber.w("Another VPN is active, dropping widget connection request")
                val appIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_SHOW_VPN_CONFLICT_DIALOG, true)
                }
                context.startActivity(appIntent)
                return
            }

            if (isRootMode) {
                RootProxyService.start(context)
            } else {
                val startIntent = Intent(context, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_START
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(startIntent)
                    } else {
                        context.startService(startIntent)
                    }
                } catch (e: Exception) {
                    Timber.w("Cannot start VPN from widget, opening app: $e")
                    val appIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(appIntent)
                }
            }
        }
    }
}
