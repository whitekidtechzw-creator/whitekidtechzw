package zw.whitekid.vpn.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.ui.MainActivity

/** Quick Settings tile: tap to connect or disconnect. It follows the connection live (Connecting, Connected, server name). */
class WkTile : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        refresh()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch { VpnState.status.collect { refresh() } }
        sc.launch { VpnState.step.collect { refresh() } }
    }

    override fun onStopListening() { scope?.cancel(); scope = null }

    private fun refresh() {
        val t = qsTile ?: return
        val st = VpnState.status.value
        t.state = if (st == Status.CONNECTED || st == Status.CONNECTING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Whitekid VPN"
        if (Build.VERSION.SDK_INT >= 29) {
            t.subtitle = when (st) {
                Status.CONNECTED -> VpnState.message.value.ifBlank { "Connected" }
                Status.CONNECTING -> VpnState.step.value.ifBlank { "Connecting…" }
                Status.ERROR -> "Failed"
                Status.IDLE -> "Off"
            }
        }
        t.updateTile()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }

    override fun onClick() {
        val st = VpnState.status.value
        if (st == Status.CONNECTED || st == Status.CONNECTING) {
            VpnController.disconnect(applicationContext); refresh(); return
        }
        val list = Store.profiles()
        val p = list.firstOrNull { it.id == Store.selectedId } ?: list.firstOrNull()
        if (p == null || VpnService.prepare(this) != null) { openApp(); return }
        VpnController.connect(applicationContext, p)
        refresh()
    }
}
