package zw.whitekid.vpn.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import zw.whitekid.vpn.data.Store

val ACCENTS = listOf(
    "Signal orange" to Color(0xFFFF6B35), "Amber" to Color(0xFFF2B134), "Emerald" to Color(0xFF3DD68C),
    "Ice" to Color(0xFF6CC4FF), "Rose" to Color(0xFFFF6F91), "Lime" to Color(0xFFB7E34D),
    "Neon violet" to Color(0xFFA78BFA), "Electric cyan" to Color(0xFF22E5FF), "Hot pink" to Color(0xFFFF3EA5)
)

private var darkMode by mutableStateOf(true)
/** v1.10: pure-black background in dark mode */
var amoledOn by mutableStateOf(false)
var accentBase by mutableStateOf(ACCENTS[0].second)

/** Picks dark/light from the user's choice, or from the phone when set to "follow the phone". */
fun applyTheme(systemDark: Boolean) {
    darkMode = when (Store.themeMode) { 1 -> false; 2 -> true; else -> systemDark }
    amoledOn = Store.amoled
}
fun isDarkNow() = darkMode

// Carbon and bone: warm near-black, off-white text, one signal accent. Green only ever means "connected".
val WkBg: Color get() = if (darkMode) (if (amoledOn) Color(0xFF000000) else Color(0xFF0A0C12)) else Color(0xFFF3F1EC)
val WkCard: Color get() = if (darkMode) (if (amoledOn) Color(0xFF0B0C0D) else Color(0xFF141720)) else Color(0xFFFFFFFF)
val WkLine: Color get() = if (darkMode) (if (amoledOn) Color(0xFF24262A) else Color(0xFF282C37)) else Color(0xFFD9D5CB)
val WkText: Color get() = if (darkMode) Color(0xFFECE8DF) else Color(0xFF16150F)
val WkMuted: Color get() = if (darkMode) Color(0xFF8E8C85) else Color(0xFF6A675E)
val WkRed: Color get() = if (darkMode) Color(0xFFFF5A4F) else Color(0xFFC62D22)
/** warning / "working on it" amber (name kept from the old theme) */
val WkViolet: Color get() = if (darkMode) Color(0xFFF2B134) else Color(0xFFA96F00)
/** connected */
val WkOk: Color get() = if (darkMode) Color(0xFF3DD68C) else Color(0xFF14894F)
/** The accent, darkened a little in light mode so it stays readable on paper. */
val WkMint: Color get() = if (darkMode) accentBase else lerp(accentBase, Color.Black, 0.30f)

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

@Composable
fun WkTheme(content: @Composable () -> Unit) {
    val sys = isSystemInDarkTheme()
    val view = LocalView.current
    applyTheme(sys)
    val dark = darkMode
    val barColor = WkBg.toArgb()
    SideEffect {
        val w = view.context.findActivity()?.window ?: return@SideEffect
        w.statusBarColor = barColor; w.navigationBarColor = barColor
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(
        shapes = Shapes(small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(6.dp), large = RoundedCornerShape(8.dp)),
        colorScheme = if (dark) darkColorScheme(
            primary = WkMint, onPrimary = WkBg, secondary = WkViolet, background = WkBg, surface = WkCard,
            onBackground = WkText, onSurface = WkText, surfaceVariant = WkCard, onSurfaceVariant = WkMuted, error = WkRed
        ) else lightColorScheme(
            primary = WkMint, onPrimary = Color.White, secondary = WkViolet, background = WkBg, surface = WkCard,
            onBackground = WkText, onSurface = WkText, surfaceVariant = WkCard, onSurfaceVariant = WkMuted, error = WkRed
        ),
        content = content
    )
}
