package zw.whitekid.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import zw.whitekid.vpn.data.Store

/** Connects after the phone restarts, if the user turned that on and already granted VPN permission. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !Store.connectOnBoot) return
        if (VpnService.prepare(ctx) != null) return
        if (!zw.whitekid.vpn.data.NetInfo.ruleAllows(ctx, Store.autoNet)) return
        val list = Store.profiles()
        val p = list.firstOrNull { it.id == Store.selectedId } ?: list.firstOrNull() ?: return
        VpnController.connect(ctx, p)
    }
}
