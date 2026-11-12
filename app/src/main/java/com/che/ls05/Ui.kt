package com.che.ls05

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Durum etiketi tonu. */
enum class Tone { OK, WARN, NEUTRAL }

/** Kodla değiştirilirken dinleyicisi geçici olarak kaldırılan anahtar. */
class Toggle(val sw: Switch, private val onChange: (Boolean) -> Unit) {
    private val listener = CompoundButton.OnCheckedChangeListener { _, value -> onChange(value) }

    init {
        sw.setOnCheckedChangeListener(listener)
    }

    fun set(checked: Boolean) {
        if (sw.isChecked == checked) return
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener(listener)
    }
}

class SwitchRow(val view: LinearLayout, val title: TextView, val subtitle: TextView, val toggle: Toggle)

class Banner(val view: LinearLayout, val text: TextView)

class TabItem(val view: LinearLayout, val icon: ImageView?, val label: TextView)

/**
 * Bütün ekranların kurulduğu ortak yardımcılar. Renkler kaynaklardan gelir (açık/koyu mod kendiliğinden).
 * Boşluk 8 dp tabanlı, kart köşesi 16 dp, dokunma alanı en az 48 dp.
 */
class Ui(val c: Context) {

    companion object {
        const val MATCH = -1 // ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = -2 // ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val density: Float = c.resources.displayMetrics.density
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun color(id: Int): Int = c.getColor(id)

    // ---------- Zeminler ----------

    fun round(fill: Int, radiusDp: Int = 16): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(dp(radiusDp).toFloat())
        d.setColor(fill)
        return d
    }

    private fun rippleColor(): ColorStateList =
        ColorStateList.valueOf((color(R.color.text) and 0x00FFFFFF) or 0x26000000)

    /** content null ve mask null ise sınırsız dalga. */
    fun ripple(content: Drawable?, mask: Drawable?): RippleDrawable = RippleDrawable(rippleColor(), content, mask)

    /** Saydam, dokununca köşeli dalga gösteren zemin. */
    fun pressable(radiusDp: Int = 8): Drawable = ripple(null, round(Color.WHITE, radiusDp))

    // ---------- Yerleşim ----------

    fun vertical(): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun horizontal(): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.HORIZONTAL
        l.setGravity(Gravity.CENTER_VERTICAL)
        return l
    }

    /** LinearLayout ebeveynine LinearLayout.LayoutParams ile ekler. */
    fun add(
        parent: LinearLayout,
        v: View,
        topDp: Int = 0,
        width: Int = MATCH,
        height: Int = WRAP,
        weight: Float = 0f,
        leftDp: Int = 0,
    ) {
        val lp = LinearLayout.LayoutParams(width, height, weight)
        lp.topMargin = dp(topDp)
        lp.leftMargin = dp(leftDp)
        parent.addView(v, lp)
    }

    fun scroll(child: View): ScrollView {
        val s = ScrollView(c)
        s.clipToPadding = false
        s.addView(child, FrameLayout.LayoutParams(MATCH, WRAP))
        return s
    }

    fun dialogBox(): LinearLayout {
        val l = vertical()
        l.setPadding(dp(24), dp(8), dp(24), 0)
        return l
    }

    fun divider(): View {
        val v = View(c)
        v.setBackgroundColor(color(R.color.divider))
        return v
    }

    // ---------- Yazı ----------

    fun text(s: CharSequence, sp: Float = 15f, colorId: Int = R.color.text, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color(colorId))
        if (bold) t.typeface = medium
        return t
    }

    fun caption(s: CharSequence): TextView = text(s, 13f, R.color.text2)

    /** Kart içi küçük başlık. */
    fun cardTitle(s: CharSequence): TextView = text(s, 14f, R.color.text2, bold = true)

    /** Büyük rakam (adım, alarm saati). */
    fun big(s: CharSequence, sp: Float = 40f): TextView {
        val t = text(s, sp, R.color.text, bold = true)
        t.letterSpacing = -0.01f
        t.includeFontPadding = false
        return t
    }

    /** Değer varsa büyük rakam, yoksa daha küçük soluk açıklama. */
    fun setBig(t: TextView, value: String?, empty: String, sp: Float = 40f) {
        if (value != null) {
            t.text = value
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            t.setTextColor(color(R.color.text))
        } else {
            t.text = empty
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            t.setTextColor(color(R.color.text2))
        }
    }

    fun mono(): TextView {
        val t = text("", 11f, R.color.text)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        return t
    }

    // ---------- 1. Kart ----------

    fun card(onClick: (() -> Unit)? = null): LinearLayout {
        val l = vertical()
        val shape = round(color(R.color.card), 16)
        l.setPadding(dp(16), dp(16), dp(16), dp(16))
        if (onClick != null) {
            l.background = ripple(shape, null)
            l.isClickable = true
            l.setOnClickListener { onClick() }
        } else {
            l.background = shape
        }
        return l
    }

    // ---------- 2. Bölüm başlığı ----------

    fun section(s: CharSequence): TextView {
        val t = text(s, 17f, R.color.text, bold = true)
        t.setPadding(dp(4), dp(24), dp(4), 0)
        return t
    }

    // ---------- 3. Satır ----------

    fun row(
        title: String,
        subtitle: String? = null,
        trailing: View? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val r = horizontal()
        r.minimumHeight = dp(48)
        r.setPadding(0, dp(4), 0, dp(4))
        val texts = vertical()
        add(texts, text(title, 15f))
        if (subtitle != null) add(texts, caption(subtitle))
        add(r, texts, width = 0, weight = 1f)
        if (trailing != null) add(r, trailing, width = WRAP, leftDp = 16)
        if (onClick != null) {
            r.background = pressable(8)
            r.isClickable = true
            r.setOnClickListener { onClick() }
        }
        return r
    }

    // ---------- 4. Anahtarlı satır ----------

    fun toggle(checked: Boolean, onChange: (Boolean) -> Unit): Toggle {
        val sw = Switch(c)
        sw.thumbTintList = ColorStateList(checkedStates, intArrayOf(color(R.color.accent), color(R.color.thumb_off)))
        sw.trackTintList = ColorStateList(checkedStates, intArrayOf(color(R.color.accent), color(R.color.track_off)))
        sw.minHeight = dp(48)
        sw.minimumHeight = dp(48)
        sw.isChecked = checked
        return Toggle(sw, onChange)
    }

    fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): SwitchRow {
        val r = horizontal()
        r.minimumHeight = dp(56)
        r.setPadding(0, dp(4), 0, dp(4))
        val texts = vertical()
        val t = text(title, 15f)
        val sub = caption(subtitle ?: "")
        sub.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        add(texts, t)
        add(texts, sub)
        add(r, texts, width = 0, weight = 1f)
        val tg = toggle(checked, onChange)
        add(r, tg.sw, width = WRAP, leftDp = 16)
        r.background = pressable(8)
        r.isClickable = true
        r.setOnClickListener { tg.sw.toggle() }
        return SwitchRow(r, t, sub, tg)
    }

    // ---------- 5. Durum etiketi (hap) ----------

    fun pill(label: String, tone: Tone): TextView {
        val t = TextView(c)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.typeface = medium
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        t.setGravity(Gravity.CENTER)
        t.setPadding(dp(12), dp(4), dp(12), dp(4))
        setPill(t, label, tone)
        return t
    }

    fun setPill(t: TextView, label: String, tone: Tone) {
        val (bgId, fgId) = when (tone) {
            Tone.OK -> R.color.ok_bg to R.color.accent
            Tone.WARN -> R.color.warn_bg to R.color.warn
            Tone.NEUTRAL -> R.color.field to R.color.text2
        }
        t.text = label
        t.setTextColor(color(fgId))
        t.background = round(color(bgId), 16)
    }

    // ---------- 6. Uyarı bandı ----------

    fun banner(message: String, action: String?, onAction: (() -> Unit)?): Banner {
        val v = horizontal()
        v.background = round(color(R.color.warn_bg), 16)
        v.minimumHeight = dp(56)
        v.setPadding(dp(16), dp(8), dp(8), dp(8))
        val t = text(message, 14f, R.color.warn)
        add(v, t, width = 0, weight = 1f)
        if (action != null && onAction != null) {
            val b = baseButton(action, R.color.warn)
            b.background = ripple(null, round(Color.WHITE, 24))
            b.setOnClickListener { onAction() }
            add(v, b, width = WRAP, leftDp = 8)
        }
        return Banner(v, t)
    }

    // ---------- 7-8. Düğmeler ----------

    private fun baseButton(label: String, textColorId: Int): Button {
        val b = Button(c)
        b.text = label
        b.isAllCaps = false
        b.stateListAnimator = null
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        b.setTextColor(color(textColorId))
        b.typeface = medium
        b.minHeight = dp(48)
        b.minimumHeight = dp(48)
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(16), dp(8), dp(16), dp(8))
        b.setGravity(Gravity.CENTER)
        return b
    }

    fun primary(label: String, onClick: () -> Unit): Button {
        val b = baseButton(label, R.color.on_accent)
        b.background = ripple(round(color(R.color.accent), 24), null)
        b.setOnClickListener { onClick() }
        return b
    }

    fun secondary(label: String, onClick: () -> Unit): Button {
        val b = baseButton(label, R.color.text)
        b.background = ripple(round(color(R.color.field), 24), null)
        b.setOnClickListener { onClick() }
        return b
    }

    fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.38f
    }

    // ---------- Diğer denetimler ----------

    fun field(hint: String): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setTextColor(color(R.color.text))
        e.setHintTextColor(color(R.color.text2))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        e.background = round(color(R.color.field), 12)
        e.setPadding(dp(16), dp(12), dp(16), dp(12))
        e.minHeight = dp(48)
        e.setSingleLine(true)
        return e
    }

    fun check(label: String, checked: Boolean): CheckBox {
        val b = CheckBox(c)
        b.text = label
        b.isChecked = checked
        b.setTextColor(color(R.color.text))
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        b.buttonTintList = ColorStateList(checkedStates, intArrayOf(color(R.color.accent), color(R.color.text2)))
        b.minHeight = dp(48)
        return b
    }

    /** Yuvarlak köşeli yatay ilerleme çubuğu (0..100). */
    fun progress(): ProgressBar {
        val p = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal)
        p.isIndeterminate = false
        val back: Drawable = round(color(R.color.field), 4)
        val front: Drawable = ClipDrawable(round(color(R.color.accent), 4), Gravity.START, ClipDrawable.HORIZONTAL)
        val layer = LayerDrawable(arrayOf(back, front))
        layer.setId(0, android.R.id.background)
        layer.setId(1, android.R.id.progress)
        p.progressDrawable = layer
        p.max = 100
        p.progress = 0
        return p
    }

    /** Vektör ikon; yüklenemezse null (yalnızca yazı görünür). */
    fun icon(resId: Int, colorId: Int): ImageView? {
        val d: Drawable? = try {
            c.getDrawable(resId)
        } catch (e: Exception) {
            null
        }
        if (d == null) return null
        val v = ImageView(c)
        v.setImageDrawable(d)
        v.imageTintList = ColorStateList.valueOf(color(colorId))
        return v
    }

    fun tabItem(iconRes: Int, label: String, onClick: () -> Unit): TabItem {
        val v = vertical()
        v.setGravity(Gravity.CENTER)
        v.minimumHeight = dp(64)
        v.setPadding(0, dp(8), 0, dp(8))
        v.background = ripple(null, null)
        v.isClickable = true
        v.setOnClickListener { onClick() }
        val ic = icon(iconRes, R.color.text2)
        if (ic != null) v.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
        val t = text(label, 12f, R.color.text2, bold = true)
        t.setGravity(Gravity.CENTER)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        add(v, t, topDp = 4, width = WRAP)
        return TabItem(v, ic, t)
    }
}

/** Ekrandaki sayı ve zaman biçimleri. */
object Fmt {
    private val tr = Locale("tr", "TR")
    private val hmFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm")

    /** 8000 -> "8.000" */
    fun num(n: Int): String = String.format(tr, "%,d", n)

    fun hm(hour: Int, minute: Int): String = String.format(Locale.US, "%02d:%02d", hour, minute)

    fun local(ms: Long): LocalDateTime = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()

    /** "bugün 21:20", "dün 23:50" ya da "12.11 07:20". */
    fun day(at: LocalDateTime): String {
        val today = LocalDate.now()
        val d = at.toLocalDate()
        return when (d) {
            today -> "bugün ${at.format(hmFormat)}"
            today.minusDays(1) -> "dün ${at.format(hmFormat)}"
            else -> at.format(dayFormat)
        }
    }

    fun dayMs(ms: Long): String = day(local(ms))

    /** "az önce", "2 dk önce", "3 sa önce", "dün", "5 gün önce". */
    fun ago(ms: Long): String {
        val diff = System.currentTimeMillis() - ms
        if (diff < 60_000L) return "az önce"
        if (diff < 3_600_000L) return "${diff / 60_000L} dk önce"
        val then = local(ms).toLocalDate()
        val today = LocalDate.now()
        return when (then) {
            today -> "${diff / 3_600_000L} sa önce"
            today.minusDays(1) -> "dün"
            else -> "${ChronoUnit.DAYS.between(then, today)} gün önce"
        }
    }
}
