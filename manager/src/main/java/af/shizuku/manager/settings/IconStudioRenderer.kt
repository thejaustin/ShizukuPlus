package af.shizuku.manager.settings

import af.shizuku.manager.R
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Model + software renderer for the Custom Icon Studio. Everything here is plain Android
 * graphics so it can run on a background dispatcher; the Compose sheet only holds state.
 */
internal object IconStudioRenderer {
    const val SIZE = 512

    enum class Glyph(
        val key: String,
        val resId: Int,
        /** Adaptive-icon foregrounds already carry their own safe-zone padding. */
        val isAdaptiveForeground: Boolean = false,
    ) {
        PLUS("modern", R.drawable.ic_launcher_foreground, true),
        CLASSIC("classic", R.drawable.ic_launcher_classic_foreground, true),
        TERMINAL("terminal", R.drawable.ic_terminal_24),
        ADB("adb", R.drawable.ic_adb_24),
        CODE("code", R.drawable.ic_code_24),
        SHIELD("shield", R.drawable.ic_shield_24),
        BOLT("bolt", R.drawable.ic_bolt_24),
        SPARKLE("sparkle", R.drawable.ic_sparkle_24),
        SCIENCE("science", R.drawable.ic_science_24),
        TUNE("settings", R.drawable.ic_settings_suggest_24),
        ;

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: PLUS
        }
    }

    enum class Source { PRESET, IMAGE, TEXT }

    enum class Shape(
        val key: String,
    ) {
        SQUIRCLE("squircle"),
        CIRCLE("circle"),
        ONEUI("oneui"),
        ROUNDED("rounded"),
        COOKIE("cookie"),
        TEARDROP("teardrop"),
        HEXAGON("hexagon"),
        SQUARE("square"),
        ;

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: SQUIRCLE
        }
    }

    enum class GlyphColor(
        val key: String,
    ) {
        AUTO("auto"),
        ORIGINAL("original"),
        WHITE("white"),
        BLACK("black"),
        ACCENT("accent"),
        ;

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
        }
    }

    data class State(
        val source: Source = Source.PRESET,
        val glyph: Glyph = Glyph.PLUS,
        val text: String = "S+",
        /** Photo fills the whole shape instead of sitting inside it like a glyph. */
        val imageFill: Boolean = true,
        val shape: Shape = Shape.SQUIRCLE,
        /** 0 means "follow the theme accent" so the default adapts to dynamic color. */
        val bgColor: Int = 0,
        val bgColor2: Int = 0,
        val gradient: Boolean = false,
        val gradientAngle: Float = 135f,
        val transparentBg: Boolean = false,
        val glyphColor: GlyphColor = GlyphColor.AUTO,
        /** Glyph size as a percent of the icon edge. */
        val glyphScale: Float = 62f,
        val rotation: Float = 0f,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
        val shadow: Boolean = false,
        val borderWidth: Float = 0f,
        val borderColor: Int = Color.WHITE,
    ) {
        fun toJson(): String =
            JSONObject()
                .put("source", source.name)
                .put("glyph", glyph.key)
                .put("text", text)
                .put("imageFill", imageFill)
                .put("shape", shape.key)
                .put("bgColor", bgColor)
                .put("bgColor2", bgColor2)
                .put("gradient", gradient)
                .put("gradientAngle", gradientAngle.toDouble())
                .put("transparentBg", transparentBg)
                .put("glyphColor", glyphColor.key)
                .put("glyphScale", glyphScale.toDouble())
                .put("rotation", rotation.toDouble())
                .put("offsetX", offsetX.toDouble())
                .put("offsetY", offsetY.toDouble())
                .put("shadow", shadow)
                .put("borderWidth", borderWidth.toDouble())
                .put("borderColor", borderColor)
                .toString()

        companion object {
            fun fromJson(json: String?): State {
                if (json.isNullOrBlank()) return State()
                return runCatching {
                    val o = JSONObject(json)
                    val d = State()
                    State(
                        source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(d.source),
                        glyph = Glyph.of(o.optString("glyph")),
                        text = o.optString("text", d.text),
                        imageFill = o.optBoolean("imageFill", d.imageFill),
                        shape = Shape.of(o.optString("shape")),
                        bgColor = o.optInt("bgColor", d.bgColor),
                        bgColor2 = o.optInt("bgColor2", d.bgColor2),
                        gradient = o.optBoolean("gradient", d.gradient),
                        gradientAngle = o.optDouble("gradientAngle", d.gradientAngle.toDouble()).toFloat(),
                        transparentBg = o.optBoolean("transparentBg", d.transparentBg),
                        glyphColor = GlyphColor.of(o.optString("glyphColor")),
                        glyphScale = o.optDouble("glyphScale", d.glyphScale.toDouble()).toFloat(),
                        rotation = o.optDouble("rotation", 0.0).toFloat(),
                        offsetX = o.optDouble("offsetX", 0.0).toFloat(),
                        offsetY = o.optDouble("offsetY", 0.0).toFloat(),
                        shadow = o.optBoolean("shadow", d.shadow),
                        borderWidth = o.optDouble("borderWidth", 0.0).toFloat(),
                        borderColor = o.optInt("borderColor", d.borderColor),
                    )
                }.getOrDefault(State())
            }
        }
    }

    private const val PREF_STATE = "icon_studio_state_v2"
    private const val SOURCE_IMAGE_FILE = "icon_studio_source.png"

    fun load(prefs: SharedPreferences): State = State.fromJson(prefs.getString(PREF_STATE, null))

    fun save(
        prefs: SharedPreferences,
        state: State,
    ) {
        prefs.edit().putString(PREF_STATE, state.toJson()).apply()
    }

    fun sourceImageFile(context: Context) = File(context.filesDir, SOURCE_IMAGE_FILE)

    /**
     * Copies a picked image into app storage, downsampled to at most [maxEdge] px, so it survives
     * restarts (GetContent URIs lose their grant) and never decodes a 50 MP photo at full size.
     */
    fun importImage(
        context: Context,
        uri: Uri,
        maxEdge: Int = 1024,
    ): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxEdge && bounds.outHeight / (sample * 2) >= maxEdge) sample *= 2
            val bitmap =
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: return@runCatching null
            sourceImageFile(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap
        }.getOrNull()

    fun loadImportedImage(context: Context): Bitmap? =
        sourceImageFile(context).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    fun glyphBitmap(
        context: Context,
        glyph: Glyph,
        size: Int = SIZE,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        if (glyph.resId == 0) return bitmap
        val drawable = ContextCompat.getDrawable(context, glyph.resId)?.mutate() ?: return bitmap
        if (glyph.isAdaptiveForeground) {
            // Crop the 108dp adaptive canvas to its 72dp visible area so presets size like the
            // material glyphs instead of rendering tiny.
            val full = (size * 108f / 72f).toInt()
            val off = (full - size) / 2
            drawable.setBounds(-off, -off, full - off, full - off)
        } else {
            drawable.setBounds(0, 0, size, size)
        }
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** Resolved colors for a render: theme-dependent defaults are filled in by the caller. */
    data class Palette(
        val accent: Int,
        val accentContainer: Int,
        val onAccentContainer: Int,
    )

    fun effectiveBg(
        state: State,
        palette: Palette,
    ) = if (state.bgColor == 0) palette.accentContainer else state.bgColor

    fun effectiveBg2(
        state: State,
        palette: Palette,
    ) = if (state.bgColor2 == 0) palette.accent else state.bgColor2

    fun render(
        state: State,
        palette: Palette,
        preset: Bitmap?,
        image: Bitmap?,
        size: Int = SIZE,
    ): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val shapePath = shapePath(state.shape, rect)
        val bg = effectiveBg(state, palette)
        val bg2 = effectiveBg2(state, palette)

        // 1. Background
        if (!state.transparentBg) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            if (state.gradient) {
                val rad = Math.toRadians(state.gradientAngle.toDouble())
                val cx = size / 2f
                val r = size / 2f
                paint.shader =
                    LinearGradient(
                        cx - (cos(rad) * r).toFloat(),
                        cx - (sin(rad) * r).toFloat(),
                        cx + (cos(rad) * r).toFloat(),
                        cx + (sin(rad) * r).toFloat(),
                        bg,
                        bg2,
                        Shader.TileMode.CLAMP,
                    )
            } else {
                paint.color = bg
            }
            canvas.drawPath(shapePath, paint)
        }

        // 2. Content layer, always clipped to the shape so nothing spills past the corners.
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cc = Canvas(content)
        val fillPhoto = state.source == Source.IMAGE && state.imageFill
        val scale = if (fillPhoto) 1f + (state.glyphScale - 62f).coerceAtLeast(0f) / 100f else state.glyphScale / 100f
        val edge = size * scale
        val left = (size - edge) / 2f + size * state.offsetX / 100f
        val top = (size - edge) / 2f + size * state.offsetY / 100f
        val dest = RectF(left, top, left + edge, top + edge)
        cc.save()
        cc.rotate(state.rotation, size / 2f, size / 2f)

        val glyphTint =
            when (state.glyphColor) {
                GlyphColor.ORIGINAL -> null
                GlyphColor.WHITE -> Color.WHITE
                GlyphColor.BLACK -> 0xFF1B1B1F.toInt()
                GlyphColor.ACCENT -> palette.accent
                GlyphColor.AUTO ->
                    when {
                        state.source == Source.IMAGE -> null
                        state.transparentBg -> palette.accent
                        else -> autoContrast(bg, if (state.gradient) bg2 else bg)
                    }
            }

        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        when (state.source) {
            Source.TEXT -> drawText(lc, state.text.ifBlank { "S+" }, dest, glyphTint ?: Color.WHITE)
            Source.IMAGE -> image?.let { drawCropped(lc, it, dest) }
            Source.PRESET -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                if (glyphTint != null) p.colorFilter = PorterDuffColorFilter(glyphTint, PorterDuff.Mode.SRC_IN)
                preset?.let { lc.drawBitmap(it, null, dest, p) }
            }
        }
        if (state.source == Source.IMAGE && glyphTint != null) {
            lc.drawColor(glyphTint, PorterDuff.Mode.SRC_IN)
        }

        if (state.shadow && !fillPhoto) {
            val alpha = layer.extractAlpha()
            val sp =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(90, 0, 0, 0)
                    maskFilter = BlurMaskFilter(size * 0.03f, BlurMaskFilter.Blur.NORMAL)
                }
            cc.drawBitmap(alpha, 0f, size * 0.02f, sp)
            alpha.recycle()
        }
        cc.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
        cc.restore()

        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        cc.drawPath(shapePath, mask)
        canvas.drawBitmap(content, 0f, 0f, null)
        content.recycle()

        // 3. Border
        if (state.borderWidth > 0f) {
            val stroke =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = state.borderWidth * size / 100f
                    color = state.borderColor
                }
            val inset = stroke.strokeWidth / 2f
            canvas.drawPath(shapePath(state.shape, RectF(inset, inset, size - inset, size - inset)), stroke)
        }
        return out
    }

    private fun autoContrast(
        a: Int,
        b: Int,
    ): Int {
        val lum = (ColorUtils.calculateLuminance(a) + ColorUtils.calculateLuminance(b)) / 2
        return if (lum > 0.45) 0xFF1B1B1F.toInt() else Color.WHITE
    }

    private fun drawCropped(
        canvas: Canvas,
        src: Bitmap,
        dest: RectF,
    ) {
        // Center-crop: photos keep their aspect ratio instead of being squashed into a square.
        val s = maxOf(dest.width() / src.width, dest.height() / src.height)
        val m =
            Matrix().apply {
                postScale(s, s)
                postTranslate(
                    dest.centerX() - src.width * s / 2f,
                    dest.centerY() - src.height * s / 2f,
                )
            }
        canvas.save()
        canvas.clipRect(dest)
        canvas.drawBitmap(src, m, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        dest: RectF,
        color: Int,
    ) {
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = dest.height()
            }
        // Fit the widest of width/height into the glyph box.
        val bounds = android.graphics.Rect()
        p.getTextBounds(text, 0, text.length, bounds)
        val fit = minOf(dest.width() * 0.92f / bounds.width().coerceAtLeast(1), dest.height() * 0.7f / bounds.height().coerceAtLeast(1))
        p.textSize *= fit
        p.getTextBounds(text, 0, text.length, bounds)
        canvas.drawText(text, dest.centerX(), dest.centerY() - bounds.exactCenterY(), p)
    }

    fun shapePath(
        shape: Shape,
        r: RectF,
    ): Path {
        val path = Path()
        val w = r.width()
        when (shape) {
            Shape.CIRCLE -> path.addOval(r, Path.Direction.CW)
            Shape.SQUIRCLE -> superellipse(path, r, 4.0)
            Shape.ONEUI -> superellipse(path, r, 5.2)
            Shape.ROUNDED -> path.addRoundRect(r, w * 0.22f, w * 0.22f, Path.Direction.CW)
            Shape.SQUARE -> path.addRoundRect(r, w * 0.06f, w * 0.06f, Path.Direction.CW)
            Shape.TEARDROP -> {
                val big = w / 2f
                val small = w * 0.12f
                path.addRoundRect(r, floatArrayOf(big, big, big, big, small, small, big, big), Path.Direction.CW)
            }
            Shape.COOKIE -> {
                // Scalloped "cookie" from the M3 Expressive shape set.
                val cx = r.centerX()
                val cy = r.centerY()
                val base = w / 2f / 1.07f
                val steps = 360
                for (i in 0..steps) {
                    val t = 2 * Math.PI * i / steps
                    val rad = base * (1 + 0.07 * cos(9 * t))
                    val x = (cx + rad * cos(t)).toFloat()
                    val y = (cy + rad * sin(t)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            Shape.HEXAGON -> {
                val cx = r.centerX()
                val cy = r.centerY()
                val rad = w / 2f
                val pts =
                    (0 until 6).map { i ->
                        val a = Math.toRadians((60 * i - 90).toDouble())
                        (cx + rad * cos(a)).toFloat() to (cy + rad * sin(a)).toFloat()
                    }
                // Rounded corners: quad curves through each vertex.
                val k = 0.18f
                for (i in pts.indices) {
                    val (px, py) = pts[(i + pts.size - 1) % pts.size]
                    val (x, y) = pts[i]
                    val (nx, ny) = pts[(i + 1) % pts.size]
                    val ax = x + (px - x) * k
                    val ay = y + (py - y) * k
                    val bx = x + (nx - x) * k
                    val by = y + (ny - y) * k
                    if (i == 0) path.moveTo(ax, ay) else path.lineTo(ax, ay)
                    path.quadTo(x, y, bx, by)
                }
                path.close()
            }
        }
        return path
    }

    private fun superellipse(
        path: Path,
        r: RectF,
        n: Double,
    ) {
        val a = r.width() / 2.0
        val b = r.height() / 2.0
        val cx = r.centerX()
        val cy = r.centerY()
        val steps = 240
        for (i in 0..steps) {
            val t = 2 * Math.PI * i / steps
            val c = cos(t)
            val s = sin(t)
            val x = cx + a * sign(c) * abs(c).pow(2.0 / n)
            val y = cy + b * sign(s) * abs(s).pow(2.0 / n)
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
    }
}
