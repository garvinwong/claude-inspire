package app.palminspire.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.palminspire.R

/** 视觉：Claude 橙 #D97757 为主色；底色是干净的中性白 / 中性黑（不用纸黄底）；职能色各司其职。 */
object Brand {
    val Orange = Color(0xFFD97757)
    val OrangeDeep = Color(0xFFC0613F)
}

@Immutable
data class PalmColors(
    val bg: Color, val surface: Color, val surface2: Color, val line: Color,
    val ink: Color, val ink2: Color, val ink3: Color,
    val accent: Color, val accentSoft: Color, val onAccent: Color,
    val ok: Color, val okSoft: Color, val danger: Color, val dangerSoft: Color,
    val warn: Color, val warnSoft: Color, val info: Color, val infoSoft: Color,
    val userBubble: Color, val code: Color, val dark: Boolean,
)

val LightPalm = PalmColors(
    bg = Color(0xFFFAFAFA), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF2F2F1), line = Color(0xFFE6E5E3),
    ink = Color(0xFF1C1B1A), ink2 = Color(0xFF55524E), ink3 = Color(0xFF8C8883),
    accent = Brand.Orange, accentSoft = Color(0xFFFBEAE3), onAccent = Color.White,
    ok = Color(0xFF1F9D6B), okSoft = Color(0xFFE3F5EC), danger = Color(0xFFD64545), dangerSoft = Color(0xFFFBE7E7),
    warn = Color(0xFFC98A00), warnSoft = Color(0xFFFCF2DA), info = Color(0xFF2F6FE0), infoSoft = Color(0xFFE6EEFC),
    userBubble = Color(0xFFFBEAE3), code = Color(0xFFF4F3F1), dark = false,
)

val DarkPalm = PalmColors(
    bg = Color(0xFF121212), surface = Color(0xFF1B1B1B), surface2 = Color(0xFF242424), line = Color(0xFF303030),
    ink = Color(0xFFEDECEA), ink2 = Color(0xFFB5B1AC), ink3 = Color(0xFF85817C),
    accent = Brand.Orange, accentSoft = Color(0xFF3A2620), onAccent = Color.White,
    ok = Color(0xFF3CC08A), okSoft = Color(0xFF15332A), danger = Color(0xFFEF6666), dangerSoft = Color(0xFF3A1D1D),
    warn = Color(0xFFE5A92B), warnSoft = Color(0xFF382D15), info = Color(0xFF6B9BF2), infoSoft = Color(0xFF1C2840),
    userBubble = Color(0xFF3A2620), code = Color(0xFF202020), dark = true,
)

val LocalPalm = staticCompositionLocalOf { LightPalm }
/** 自带 DejaVu Sans Mono（含方块、框线字符）：部分厂商系统把系统 Monospace 换成了比例字体，终端画面会错位（真机实测）。 */
val Mono = FontFamily(Font(R.font.dejavu_sans_mono))

@Composable
fun PalmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalm else LightPalm
    val scheme = if (dark) darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.surface,
        onBackground = p.ink, onSurface = p.ink, surfaceVariant = p.surface2, outline = p.line, error = p.danger)
    else lightColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.bg, surface = p.surface,
        onBackground = p.ink, onSurface = p.ink, surfaceVariant = p.surface2, outline = p.line, error = p.danger)
    val t = Typography(
        titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    )
    CompositionLocalProvider(LocalPalm provides p) {
        MaterialTheme(colorScheme = scheme, typography = t, content = content)
    }
}
