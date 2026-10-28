package app.palminspire.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（克制——只用在悬浮底栏、分离出的输入框、弹出框；底下的内容保持平面）。
 *
 * 做法：页面内容画进一个 GraphicsLayer（backdropSource），玻璃件把自己背后那一块重画成三层：
 *  ① 主体磨砂：模糊 + 提饱和，整块铺满；
 *  ② 边缘折射：圆角矩形 SDF 的边缘带里沿法线向内取样，背景在边缘被放大、弯折；这一层自带透明度——边缘不透明（清透），
 *     主体按 1 − frostMix 近乎透明——叠在①上面，于是边缘清透、主体磨砂（安卓 13+ AGSL）；
 *  ③ 受光：125° 光源的 2 像素受光边 + 迎光侧高光 + 背光侧压暗，再叠一层白色染色。
 * （先前用离屏 DstIn 遮罩磨砂，真机上整层被抹掉、主体透字，10-26 改成①②叠加。）
 * 剖面与参数来自一套移动端液态玻璃实现，浅色主题改白染色、加外沿细线。
 * 安卓 12 只有模糊没有折射；安卓 12 以下退成近乎不透明的白底。显示列表按引用合成，内容滚动时玻璃背后自动跟着变。
 *
 * 🔴 玻璃件绝不能放在它所取样的 backdropSource 里面：玻璃图层画内容层、内容层又含玻璃图层，显示列表成环，
 *    hwui 在 prepareTree 无限递归直接崩溃（真机实测）。页面内部的浮层要取样页面自己单独建的一层（见 SessionScreen）。
 */
@Stable
class Backdrop {
    var layer: GraphicsLayer? = null
    var origin by mutableStateOf(Offset.Zero)        // 内容层在根坐标里的位置
}

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember { Backdrop() }.also { it.layer = layer }
}

/** 内容容器：照常画，同时录进 backdrop 的图层供玻璃取用。 */
fun Modifier.backdropSource(b: Backdrop): Modifier = this
    .onGloballyPositioned { b.origin = it.positionInRoot() }
    .drawWithContent {
        val layer = b.layer ?: return@drawWithContent drawContent()
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }

/**
 * 材质档位（单位 dp）。band 边缘折射带宽、pow 折射衰减、refract 最大位移、frost 主体模糊半径、frostMix 主体磨砂浓度
 * （1 = 全磨砂，越小越清透；浮层主体一律 1——主体里哪怕 8% 的清晰字，边缘锐利也很扎眼，真机实测）、tint 染色、rim 受光边强度、spec 迎光高光、shade 背光压暗、sat 饱和度。
 */
@Immutable
data class GlassSpec(
    val band: Dp, val pow: Float, val refract: Dp, val frost: Dp, val frostMix: Float, val tint: Color,
    val rim: Float, val spec: Float, val shade: Float, val sat: Float = 1.45f, val ca: Float = 0f,
) {
    companion object {
        /** 悬浮底栏（LGM bar 档）。边缘清透再收一半（带宽 16→8、位移 13→8），色散加强。 */
        val Bar = GlassSpec(8.dp, 2.2f, 8.dp, 20.dp, 1.0f, Color.White.copy(alpha = 0.50f), 0.55f, 0.32f, 0.10f, ca = 0.18f)   // 色散 0.30→0.18：滚过深色字有彩边
        /** 输入胶囊、快捷钮（LGM pill 档，染色加重保可读）。 */
        val Pill = GlassSpec(8.dp, 2.2f, 7.dp, 16.dp, 1.0f, Color.White.copy(alpha = 0.66f), 0.55f, 0.30f, 0.10f)
        /** 弹出框、补全面板（LGM sheet 档）。 */
        val Sheet = GlassSpec(18.dp, 2.0f, 13.dp, 24.dp, 1.0f, Color.White.copy(alpha = 0.90f), 0.50f, 0.28f, 0.08f)   // 装内容的浮层：0.72→0.90，底下文字不再透上来
        /** 底栏选中气泡移动时抬起的透镜（参照 iOS 26：清透、放大、折射、色散；静止时回落为纯色磨砂，不用本档）。
         *  带宽取接近半高，整颗都在弯光 = 放大；frostMix 0 = 全清透。 */
        val Lens = GlassSpec(30.dp, 1.3f, 11.dp, 4.dp, 0.0f, Color.White.copy(alpha = 0.05f), 0.95f, 0.65f, 0.08f, 1.3f, ca = 0.14f)
    }
}

private val LIGHT = Offset(-0.5736f, -0.8192f)          // 125° 受光方向（屏幕坐标 y 向下），与 LGM 全站统一

/** 玻璃表面。只给浮层用（克制）。 */
fun Modifier.glass(shape: Shape, spec: GlassSpec = GlassSpec.Bar): Modifier = composed {
    val b = LocalBackdrop.current
    val refractLayer = rememberGraphicsLayer()
    val frostLayer = rememberGraphicsLayer()
    val fx = remember { GlassFx() }
    var pos by remember { mutableStateOf(Offset.Zero) }
    val api = Build.VERSION.SDK_INT
    this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .drawWithContent {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val radius = when (outline) {
                is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
                else -> 0f
            }
            val src = b?.layer
            val isz = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            val shift = if (b != null) pos - b.origin else Offset.Zero
            when {
                src != null && api >= 33 && fx.ensure(size, radius, spec, this) -> {
                    refractLayer.renderEffect = fx.refract
                    refractLayer.record(isz) { translate(-shift.x, -shift.y) { drawLayer(src) } }
                    frostLayer.renderEffect = fx.frost
                    frostLayer.record(isz) { translate(-shift.x, -shift.y) { drawLayer(src) } }
                    clipPath(path) {
                        drawLayer(frostLayer)                                // ① 整块磨砂
                        drawLayer(refractLayer)                              // ② 边缘清透折射（自带透明度）
                    }
                    drawOutline(outline, spec.tint)
                    clipPath(path) {
                        drawRect(fx.light!!, blendMode = BlendMode.Screen)  // 受光边 + 高光（滤色加亮）
                        drawRect(fx.shadeBrush!!)                           // 背光侧压暗
                    }
                }
                src != null && api >= 31 -> {                            // 安卓 12：只有模糊
                    fx.ensureBlur(spec, this)
                    frostLayer.renderEffect = fx.frost
                    frostLayer.record(isz) { translate(-shift.x, -shift.y) { drawLayer(src) } }
                    clipPath(path) { drawLayer(frostLayer) }
                    drawOutline(outline, spec.tint)
                    clipPath(path) {
                        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.5f), 0.45f to Color.Transparent),
                            size = size.copy(height = size.height * 0.6f))
                    }
                }
                else -> drawOutline(outline, Color.White.copy(alpha = 0.94f))
            }
            // 外沿细线：浅色页面上靠它和投影把浮层与底下内容分开
            drawOutline(outline, Color(0x14000000), style = Stroke(width = 1.dp.toPx()))
            drawContent()
        }
}

private fun Path.addOutline(o: Outline) = when (o) {
    is Outline.Rectangle -> addRect(o.rect)
    is Outline.Rounded -> addRoundRect(o.roundRect)
    is Outline.Generic -> addPath(o.path)
}

/** 每个玻璃件一份：着色器只编译一次，尺寸或档位变了才重设参数、重建效果。 */
private class GlassFx {
    private var key: Any? = null
    private var blurKey: Any? = null
    var refract: RenderEffect? = null
    var frost: RenderEffect? = null
    var light: ShaderBrush? = null
    var shadeBrush: ShaderBrush? = null
    private var refractShader: Any? = null
    private var lightShader: Any? = null
    private var shadeShader: Any? = null

    @RequiresApi(31)
    fun ensureBlur(spec: GlassSpec, d: Density) {
        val k = spec to d.density
        if (blurKey == k) return
        blurKey = k
        val px = with(d) { spec.frost.toPx() }
        val sat = ColorMatrix().apply { setSaturation(spec.sat) }
        frost = AndroidRenderEffect.createColorFilterEffect(ColorMatrixColorFilter(sat),
            AndroidRenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP)).asComposeRenderEffect()
    }

    private var broken = false

    /** 准备折射三件套；着色器编译失败（系统着色器实现差异等）返回 false，调用方退回纯磨砂，绝不闪退。 */
    @RequiresApi(33)
    fun ensure(size: Size, radius: Float, spec: GlassSpec, d: Density): Boolean {
        if (broken) return false
        return try { build(size, radius, spec, d); true } catch (e: IllegalArgumentException) {
            android.util.Log.w("palm", "玻璃着色器编译失败，退回磨砂：${e.message}")
            broken = true
            false
        }
    }

    @RequiresApi(33)
    private fun build(size: Size, radius: Float, spec: GlassSpec, d: Density) {
        val k = listOf(size, radius, spec, d.density)
        if (key == k) return
        key = k
        ensureBlur(spec, d)
        val band = with(d) { spec.band.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val r = radius.coerceAtMost(minOf(size.width, size.height) / 2f)
        val rs = (refractShader as? RuntimeShader) ?: RuntimeShader(REFRACT_SKSL).also { refractShader = it }
        rs.setFloatUniform("size", size.width, size.height)
        rs.setFloatUniform("radius", r)
        rs.setFloatUniform("band", band)
        rs.setFloatUniform("pw", spec.pow)
        rs.setFloatUniform("bend", with(d) { spec.refract.toPx() })
        rs.setFloatUniform("sat", spec.sat)
        rs.setFloatUniform("frostMix", spec.frostMix)
        rs.setFloatUniform("ca", spec.ca)
        refract = AndroidRenderEffect.createRuntimeShaderEffect(rs, "content").asComposeRenderEffect()
        fun lit(old: Any?, mode: Float): RuntimeShader {
            val s = (old as? RuntimeShader) ?: RuntimeShader(LIGHT_SKSL)
            s.setFloatUniform("size", size.width, size.height)
            s.setFloatUniform("radius", r)
            s.setFloatUniform("band", band)
            s.setFloatUniform("rimW", with(d) { 1.6.dp.toPx() })
            s.setFloatUniform("rimK", spec.rim)
            s.setFloatUniform("specK", spec.spec)
            s.setFloatUniform("shadeK", spec.shade)
            s.setFloatUniform("light", LIGHT.x, LIGHT.y)
            s.setFloatUniform("mode", mode)
            return s
        }
        val ls = lit(lightShader, 0f).also { lightShader = it }
        val ss = lit(shadeShader, 1f).also { shadeShader = it }
        light = ShaderBrush(ls)
        shadeBrush = ShaderBrush(ss)
    }
}

/** 圆角矩形 SDF 与外法线（与 LGM sdRR / gradRR 同一写法；坐标以中心为原点）。 */
private const val SDF_SKSL = """
float sdRR(float2 p, float2 hs, float r) {
    float2 q = abs(p) - (hs - float2(r));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float2 gradRR(float2 p, float2 hs, float r) {
    float2 q = abs(p) - (hs - float2(r));
    float2 g = (max(q.x, q.y) > 0.0) ? max(q, 0.0) : ((q.x > q.y) ? float2(1.0, 0.0) : float2(0.0, 1.0));
    return normalize(g + float2(1e-6)) * sign(p + float2(1e-6));
}
"""

/** ② 边缘折射：边缘带里沿“法线与径向的混合方向”向内取样（LGM scale 为负），背景在边缘被放大弯折；同时提饱和。
 *  输出透明度 = 1 − frostMix·(1 − w)：边缘不透明（清透折射），主体只剩一点，让底下的磨砂露出来。 */
private const val REFRACT_SKSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float band;
uniform float pw;
uniform float bend;
uniform float sat;
uniform float frostMix;
uniform float ca;
""" + SDF_SKSL + """
half4 main(float2 xy) {
    float2 hs = size * 0.5;
    float2 p = xy - hs;
    float t = max(-sdRR(p, hs, radius), 0.0);
    float w = pow(1.0 - smoothstep(0.0, band, t), pw);
    float2 n = gradRR(p, hs, radius);
    float2 dir = normalize(mix(n, p / max(length(p), 1e-4), 0.25) + float2(1e-5));
    float2 off = bend * dir * w;
    float2 lo = float2(0.5); float2 hi = size - float2(0.5);
    half4 c = content.eval(clamp(xy - off, lo, hi));
    // 色散：红、蓝两路按不同位移取样（越靠边位移越大，彩边只出现在折射带里）
    c.r = content.eval(clamp(xy - off * (1.0 - ca), lo, hi)).r;
    c.b = content.eval(clamp(xy - off * (1.0 + ca), lo, hi)).b;
    half l = dot(c.rgb, half3(0.299, 0.587, 0.114));
    c.rgb = max(mix(half3(l), c.rgb, half(sat)), half3(0.0));
    return c * half(1.0 - frostMix * (1.0 - w));
}
"""

/** ③ 受光：mode 0 = 受光边与迎光高光（预乘白，按滤色叠加），mode 1 = 背光侧压暗（黑色半透）。 */
private const val LIGHT_SKSL = """
uniform float2 size;
uniform float radius;
uniform float band;
uniform float rimW;
uniform float rimK;
uniform float specK;
uniform float shadeK;
uniform float2 light;
uniform float mode;
""" + SDF_SKSL + """
half4 main(float2 xy) {
    float2 hs = size * 0.5;
    float2 p = xy - hs;
    float t = max(-sdRR(p, hs, radius), 0.0);
    float edge = 1.0 - smoothstep(0.0, band, t);
    float lit = dot(gradRR(p, hs, radius), light);
    if (mode < 0.5) {
        float rim = pow(1.0 - smoothstep(0.0, rimW, t), 1.5);
        float a = rimK * rim * (0.5 + 0.5 * max(lit, 0.0)) + specK * pow(max(lit, 0.0), 10.0) * edge;
        half h = half(clamp(a, 0.0, 1.0));
        return half4(h, h, h, h);
    }
    half k = half(shadeK * pow(max(-lit, 0.0), 2.0) * edge);
    return half4(0.0, 0.0, 0.0, k);
}
"""
