package zw.whitekid.vpn

import android.app.Application
import zw.whitekid.vpn.data.AppLog
import zw.whitekid.vpn.data.Store
import zw.whitekid.vpn.ui.ACCENTS
import zw.whitekid.vpn.ui.accentBase

class WhitekidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        AppLog.init(this)
        accentBase = ACCENTS.getOrElse(Store.accent) { ACCENTS[0] }.second
    }
}
