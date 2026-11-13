package com.che.ls05

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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

/** Kapsül tonu. OK yalnızca "bağlı" kapsülünde kullanılır. */
enum class Tone { OK, WARN, NEUTRAL }

/** Durum satırının altı hali (şartname bölüm 4). Hiçbiri "başarısız" değildir. */
enum class StatusKind { IDLE, WAITING, SENDING, CONFIRMED, NO_ACK, UNKNOWN }

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

/** Dokunulabilir satır: başlık, alt metin, sağda değer ve ok. */
class NavRow(val view: LinearLayout, val title: TextView, val subtitle: TextView, val value: TextView)

class Banner(val view: LinearLayout, val text: TextView)

class TabItem(val view: LinearLayout, val icon: ImageView?, val label: TextView)

class StatusLine(val view: LinearLayout, val icon: ImageView, val spinner: ProgressBar, val text: TextView)

class Segmented(val view: LinearLayout, private val paint: (Int) -> Unit) {
    fun select(index: Int) = paint(index)
}

class Collapsible(
    val view: LinearLayout,
    val header: LinearLayout,
    val icon: ImageView?,
    val title: TextView,
    val subtitle: TextView,
    val content: LinearLayout,
    private val chevron: ImageView?,
) {
    var open: Boolean = false
        private set

    /** Kullanıcı başlığa dokununca çağrılır. */
    var onToggle: ((Boolean) -> Unit)? = null

    init {
        header.setOnClickListener {
            setOpen(!open)
            onToggle?.invoke(open)
        }
    }

    fun setOpen(value: Boolean) {
        open = value
        content.visibility = if (value) View.VISIBLE else View.GONE
        chevron?.rotation = if (value) 90f else 0f
        // TalkBack durumu söylesin: API 30+ durum açıklaması (başlık ve alt metin okunmaya devam eder).
        val state = if (value) "Açık" else "Kapalı"
        if (Build.VERSION.SDK_INT >= 30) {
            header.stateDescription = state
        } else {
            header.contentDescription = "${title.text}, $state"
        }
    }
}

/**
 * Bütün ekranların kurulduğu ortak bileşenler (şartname bölüm 5). Renkler yalnızca kaynaklardan gelir.
 * Geometri: büyük kart 24 dp, kart 20 dp, düğme 16 dp köşe; kapsül tam yuvarlak; dokunma alanı en az 48 dp.
 * Nane yeşili yalnızca: seçili sekme, açık anahtar, ilerleme, "Saat onayladı", bağlı kapsülü, birincil düğme.
 */
class Ui(val c: Context) {

    companion object {
        const val MATCH = -1 // ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = -2 // ViewGroup.LayoutParams.WRAP_CONTENT
        private const val FULL = 999 // tam yuvarlak köşe (yüksekliğin yarısına kırpılır)
    }

    private val density: Float = c.resources.displayMetrics.density
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun color(id: Int): Int = c.getColor(id)
    private fun spPx(sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, c.resources.displayMetrics)

    // ---------- Zeminler ----------

    fun round(fill: Int, radiusDp: Int = 20): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.RECTANGLE)
        d.setCornerRadius(dp(radiusDp).toFloat())
        d.setColor(fill)
        return d
    }

    private fun rippleColor(): ColorStateList =
        ColorStateList.valueOf((color(R.color.text) and 0x00FFFFFF) or 0x1F000000)

    /** Dalga yalnızca tıklanabilir öğelere verilir. content null ise yalnızca maske içinde görünür. */
    fun ripple(content: Drawable?, mask: Drawable?): RippleDrawable = RippleDrawable(rippleColor(), content, mask)

    /** Saydam, dokununca köşeli dalga gösteren zemin. */
    fun pressable(radiusDp: Int = 12): Drawable = ripple(null, round(Color.WHITE, radiusDp))

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

    /** Boş yer tutucu (ağırlıkla esner). */
    fun space(): View = View(c)

    fun scroll(child: View): ScrollView {
        val s = ScrollView(c)
        s.clipToPadding = false
        s.isVerticalScrollBarEnabled = false
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
        v.setBackgroundColor(color(R.color.border))
        return v
    }

    // ---------- Yazı ----------

    fun text(s: CharSequence, sp: Float = 15f, colorId: Int = R.color.text, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color(colorId))
        t.typeface = if (bold) medium else regular
        return t
    }

    /** Açıklama: 13 sp, ikincil renk. */
    fun caption(s: CharSequence): TextView = text(s, 13f, R.color.text2)

    /** Kart başlığı: 14 sp, ikincil renk. */
    fun cardTitle(s: CharSequence): TextView = text(s, 14f, R.color.text2, bold = true)

    /** Büyük değer: 32 sp (alarm saati 28 sp). */
    fun big(s: CharSequence, sp: Float = 32f): TextView {
        val t = text(s, sp, R.color.text, bold = true)
        t.letterSpacing = -0.01f
        t.includeFontPadding = false
        return t
    }

    /** Değer varsa büyük rakam, yoksa daha küçük soluk açıklama. */
    fun setBig(t: TextView, value: CharSequence?, empty: String, sp: Float = 32f) {
        if (value != null) {
            t.text = value
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            t.setTextColor(color(R.color.text))
        } else {
            t.text = empty
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            t.setTextColor(color(R.color.text2))
        }
    }

    /** Sırayla değer, birim, değer, birim… Birimler küçük ve ikincil renkte ("8 sa 21 dk", "93 bpm"). */
    fun valueSpan(vararg parts: String): CharSequence {
        val b = SpannableStringBuilder()
        parts.forEachIndexed { i, p ->
            val start = b.length
            b.append(p)
            if (i % 2 == 1) {
                b.setSpan(RelativeSizeSpan(0.5f), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                b.setSpan(ForegroundColorSpan(color(R.color.text2)), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return b
    }

    fun mono(): TextView {
        val t = text("", 11f, R.color.text2)
        t.typeface = Typeface.MONOSPACE
        t.setTextIsSelectable(true)
        return t
    }

    // ---------- İkon ----------

    private fun drawable(resId: Int): Drawable? = try {
        c.getDrawable(resId)
    } catch (e: Exception) {
        null
    }

    /** Vektör ikon; yüklenemezse null (yalnızca yazı görünür). */
    fun icon(resId: Int, colorId: Int): ImageView? {
        val d = drawable(resId) ?: return null
        val v = ImageView(c)
        v.setImageDrawable(d)
        v.imageTintList = ColorStateList.valueOf(color(colorId))
        return v
    }

    fun setIcon(v: ImageView?, resId: Int, colorId: Int) {
        if (v == null) return
        v.setImageDrawable(drawable(resId))
        v.imageTintList = ColorStateList.valueOf(color(colorId))
    }

    private fun addLeading(row: LinearLayout, v: View, sizeDp: Int, gapDp: Int) {
        val lp = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        lp.rightMargin = dp(gapDp)
        row.addView(v, lp)
    }

    /** Küçük ikon + kart başlığı ("Nabız", "Uyku", "Tema"). İkon rengi nane olamaz. */
    fun iconTitle(resId: Int, colorId: Int, title: String): LinearLayout {
        val r = horizontal()
        icon(resId, colorId)?.let { addLeading(r, it, 20, 8) }
        add(r, cardTitle(title), width = 0, weight = 1f)
        return r
    }

    // ---------- Kart ----------

    /** big: özet kartı (24 dp köşe). Diğerleri 20 dp. */
    fun card(onClick: (() -> Unit)? = null, big: Boolean = false, padDp: Int = 16, padVDp: Int = padDp): LinearLayout {
        val l = vertical()
        val shape = round(color(R.color.card), if (big) 24 else 20)
        l.setPadding(dp(padDp), dp(padVDp), dp(padDp), dp(padVDp))
        if (onClick != null) {
            l.background = ripple(shape, null)
            l.isClickable = true
            l.isFocusable = true
            l.setOnClickListener { onClick() }
        } else {
            l.background = shape
        }
        return l
    }

    // ---------- Bölüm başlığı ----------

    fun section(s: CharSequence): TextView {
        val t = text(s, 15f, R.color.text, bold = true)
        t.setPadding(dp(4), 0, dp(4), 0)
        return t
    }

    // ---------- Satır ----------

    fun row(
        title: String,
        subtitle: String? = null,
        trailing: View? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val r = horizontal()
        r.minimumHeight = dp(48)
        r.setPadding(0, dp(6), 0, dp(6))
        val texts = vertical()
        add(texts, text(title, 15f))
        if (subtitle != null) add(texts, caption(subtitle), topDp = 2)
        add(r, texts, width = 0, weight = 1f)
        if (trailing != null) add(r, trailing, width = WRAP, leftDp = 12)
        if (onClick != null) {
            r.background = pressable(12)
            r.isClickable = true
            r.isFocusable = true
            r.setOnClickListener { onClick() }
        }
        return r
    }

    /** Dokunulabilir satır: isteğe bağlı ikon, başlık, alt metin, sağda değer ve ok. */
    fun navRow(iconRes: Int?, title: String, subtitle: String?, value: String?, onClick: () -> Unit): NavRow {
        val r = horizontal()
        r.minimumHeight = dp(56)
        r.setPadding(0, dp(6), 0, dp(6))
        if (iconRes != null) icon(iconRes, R.color.text2)?.let { addLeading(r, it, 24, 16) }
        val texts = vertical()
        val t = text(title, 15f)
        val sub = caption(subtitle ?: "")
        sub.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        add(texts, t)
        add(texts, sub, topDp = 2)
        add(r, texts, width = 0, weight = 1f)
        val v = text(value ?: "", 15f, R.color.text2)
        v.visibility = if (value == null) View.GONE else View.VISIBLE
        add(r, v, width = WRAP, leftDp = 12)
        icon(R.drawable.ic_chevron, R.color.muted)?.let {
            val lp = LinearLayout.LayoutParams(dp(20), dp(20))
            lp.leftMargin = dp(4)
            r.addView(it, lp)
        }
        r.background = pressable(12)
        r.isClickable = true
        r.isFocusable = true
        r.setOnClickListener { onClick() }
        return NavRow(r, t, sub, v)
    }

    // ---------- Anahtar ve anahtarlı satır ----------

    /**
     * Framework Switch, kendi çizimimizle: yol 52×32 dp, top 24 dp, kenardan 4 dp.
     * Ölçü hesabı Switch'in kuralına göre: genişlik = 2 × top yuvası (20) + yol dolgusu (6 + 6) = 52.
     * Top çizimi yuvadan iki yana 2 dp taşar (24 dp). Açık: nane yol + açık top; kapalı: gri yol + gri top.
     */
    fun toggle(checked: Boolean, onChange: (Boolean) -> Unit): Toggle {
        val sw = Switch(c)

        val knob = GradientDrawable()
        knob.setShape(GradientDrawable.OVAL)
        knob.setSize(dp(24), dp(24))
        knob.setColor(ColorStateList(checkedStates, intArrayOf(color(R.color.on_accent), color(R.color.switch_off_thumb))))
        val thumb = LayerDrawable(arrayOf<Drawable>(knob))
        thumb.setLayerInset(0, 0, dp(4), 0, dp(4))
        thumb.setPadding(dp(2), 0, dp(2), 0)

        val path = GradientDrawable()
        path.setShape(GradientDrawable.RECTANGLE)
        path.setCornerRadius(dp(16).toFloat())
        path.setSize(dp(52), dp(32))
        path.setColor(ColorStateList(checkedStates, intArrayOf(color(R.color.accent), color(R.color.switch_off_track))))
        val track = LayerDrawable(arrayOf<Drawable>(path))
        track.setPadding(dp(6), 0, dp(6), 0)

        sw.thumbTintList = null
        sw.trackTintList = null
        sw.thumbDrawable = thumb
        sw.trackDrawable = track
        sw.switchMinWidth = dp(52)
        sw.showText = false
        sw.background = null
        sw.setPadding(0, 0, 0, 0)
        sw.setGravity(Gravity.CENTER_VERTICAL)
        sw.minHeight = dp(48)
        sw.minimumHeight = dp(48)
        sw.isChecked = checked
        return Toggle(sw, onChange)
    }

    /**
     * Anahtarlı satır. iconRes: 24 dp tek renk ikon. appIcon true ise 40 dp uygulama ikonu yuvası ayrılır
     * (lead null olsa da hizalama bozulmaz).
     */
    fun switchRow(
        title: String,
        subtitle: String?,
        checked: Boolean,
        iconRes: Int? = null,
        appIcon: Boolean = false,
        lead: Drawable? = null,
        onChange: (Boolean) -> Unit,
    ): SwitchRow {
        val r = horizontal()
        r.minimumHeight = dp(56)
        r.setPadding(0, dp(4), 0, dp(4))
        if (iconRes != null) {
            icon(iconRes, R.color.text2)?.let { addLeading(r, it, 24, 16) }
        } else if (appIcon) {
            val iv = ImageView(c)
            if (lead != null) iv.setImageDrawable(lead)
            addLeading(r, iv, 40, 12)
        }
        val texts = vertical()
        val t = text(title, 15f)
        val sub = caption(subtitle ?: "")
        sub.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        add(texts, t)
        add(texts, sub, topDp = 2)
        add(r, texts, width = 0, weight = 1f)
        val tg = toggle(checked, onChange)
        add(r, tg.sw, width = WRAP, leftDp = 12)
        r.background = pressable(12)
        r.isClickable = true
        r.setOnClickListener { tg.sw.toggle() }
        return SwitchRow(r, t, sub, tg)
    }

    // ---------- Durum satırı ----------

    fun statusLine(): StatusLine {
        val v = LinearLayout(c)
        v.orientation = LinearLayout.HORIZONTAL
        val ic = ImageView(c)
        val sp = ProgressBar(c, null, android.R.attr.progressBarStyleSmall)
        sp.isIndeterminate = true
        sp.indeterminateTintList = ColorStateList.valueOf(color(R.color.text2))
        val t = text("", 13f, R.color.text2)
        for (lead in arrayOf<View>(ic, sp)) {
            val lp = LinearLayout.LayoutParams(dp(16), dp(16))
            lp.rightMargin = dp(6)
            lp.topMargin = dp(1)
            v.addView(lead, lp)
        }
        add(v, t, width = 0, weight = 1f)
        val line = StatusLine(v, ic, sp, t)
        setStatus(line, StatusKind.IDLE, "")
        return line
    }

    /** Durum yalnızca renkle anlatılmaz: metin her zaman vardır, ikon yardımcıdır. */
    fun setStatus(line: StatusLine, kind: StatusKind, message: String) {
        val colorId = when (kind) {
            StatusKind.IDLE -> R.color.muted
            StatusKind.SENDING -> R.color.text2
            StatusKind.CONFIRMED -> R.color.accent
            StatusKind.WAITING, StatusKind.NO_ACK, StatusKind.UNKNOWN -> R.color.warning
        }
        val iconRes = when (kind) {
            StatusKind.WAITING -> R.drawable.ic_clock
            StatusKind.CONFIRMED -> R.drawable.ic_check
            StatusKind.NO_ACK -> R.drawable.ic_alert
            StatusKind.UNKNOWN -> R.drawable.ic_question
            StatusKind.IDLE, StatusKind.SENDING -> 0
        }
        line.text.text = message
        line.text.setTextColor(color(colorId))
        line.spinner.visibility = if (kind == StatusKind.SENDING) View.VISIBLE else View.GONE
        val d = if (iconRes != 0) drawable(iconRes) else null
        if (d != null) {
            line.icon.setImageDrawable(d)
            line.icon.imageTintList = ColorStateList.valueOf(color(colorId))
            line.icon.visibility = View.VISIBLE
        } else {
            line.icon.visibility = View.GONE
        }
    }

    // ---------- Kapsül ----------

    fun pill(label: String, tone: Tone): TextView {
        val t = TextView(c)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.typeface = medium
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        t.setGravity(Gravity.CENTER)
        t.setPadding(dp(12), dp(5), dp(12), dp(5))
        setPill(t, label, tone)
        return t
    }

    fun setPill(t: TextView, label: String, tone: Tone) {
        val (bgId, fgId) = when (tone) {
            Tone.OK -> R.color.accent_bg to R.color.accent
            Tone.WARN -> R.color.warning_bg to R.color.warning
            Tone.NEUTRAL -> R.color.raised to R.color.text2
        }
        t.text = label
        t.setTextColor(color(fgId))
        t.background = round(color(bgId), FULL)
    }

    // ---------- Uyarı bandı ----------

    fun banner(message: String, action: String?, onAction: (() -> Unit)?): Banner {
        val v = horizontal()
        v.background = round(color(R.color.warning_bg), 16)
        v.minimumHeight = dp(56)
        v.setPadding(dp(16), dp(8), dp(8), dp(8))
        val t = text(message, 14f, R.color.warning)
        add(v, t, width = 0, weight = 1f)
        if (action != null && onAction != null) {
            val b = baseButton(action, R.color.warning)
            b.background = pressable(16)
            b.setOnClickListener { onAction() }
            add(v, b, width = WRAP, leftDp = 8)
        }
        return Banner(v, t)
    }

    // ---------- Düğmeler ----------

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

    private fun button(label: String, bgId: Int, fgId: Int, onClick: () -> Unit): Button {
        val b = baseButton(label, fgId)
        b.background = ripple(round(color(bgId), 16), null)
        b.setOnClickListener { onClick() }
        return b
    }

    /** Birincil (tonal): nane zeminli, nane yazılı. Ekran başına en çok bir tane. */
    fun tonal(label: String, onClick: () -> Unit): Button = button(label, R.color.accent_bg, R.color.accent, onClick)

    /** Dolu nane: yalnızca kritik onayda. */
    fun filled(label: String, onClick: () -> Unit): Button = button(label, R.color.accent, R.color.on_accent, onClick)

    fun secondary(label: String, onClick: () -> Unit): Button = button(label, R.color.raised, R.color.text, onClick)

    /** Tehlikeli (silme): yalnızca yazı rengi değişir. */
    fun danger(label: String, onClick: () -> Unit): Button = button(label, R.color.raised, R.color.danger, onClick)

    /** Zeminsiz metin düğmesi. */
    fun textButton(label: String, onClick: () -> Unit): Button {
        val b = baseButton(label, R.color.text2)
        b.background = pressable(16)
        b.setOnClickListener { onClick() }
        return b
    }

    /** 48 dp dokunma alanlı ikon düğmesi. */
    fun iconButton(resId: Int, description: String, onClick: () -> Unit): ImageView {
        val v = ImageView(c)
        v.setImageDrawable(drawable(resId))
        v.imageTintList = ColorStateList.valueOf(color(R.color.text2))
        v.scaleType = ImageView.ScaleType.CENTER
        v.contentDescription = description
        v.background = ripple(null, round(Color.WHITE, 24))
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener { onClick() }
        return v
    }

    fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.38f
    }

    // ---------- Segment kontrol ----------

    /** Yükseltilmiş dış yüzey; seçili parça kart renginde. Seçim TalkBack'e "seçili" olarak bildirilir. */
    fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): Segmented {
        val box = horizontal()
        box.background = round(color(R.color.raised), 16)
        box.setPadding(dp(4), dp(4), dp(4), dp(4))
        val items = ArrayList<TextView>()
        val paint: (Int) -> Unit = { sel ->
            items.forEachIndexed { k, t ->
                val on = k == sel
                t.isSelected = on
                t.setTextColor(color(if (on) R.color.accent else R.color.text2))
                t.typeface = if (on) medium else regular
                t.background = if (on) ripple(round(color(R.color.accent_bg), 12), null) else pressable(12)
            }
        }
        labels.forEachIndexed { i, s ->
            val t = text(s, 14f, R.color.text2)
            t.setGravity(Gravity.CENTER)
            t.minHeight = dp(44)
            t.setPadding(dp(8), dp(4), dp(8), dp(4))
            t.isClickable = true
            t.isFocusable = true
            t.setOnClickListener {
                paint(i)
                onSelect(i)
            }
            items += t
            add(box, t, width = 0, weight = 1f)
        }
        paint(selected)
        return Segmented(box, paint)
    }

    // ---------- Katlanır bölüm ----------

    /** Başlığa dokununca açılır/kapanır; ok döner. inCard false ise zeminsizdir (kart içinde grup). */
    fun collapsible(title: String, subtitle: String?, iconRes: Int?, open: Boolean, inCard: Boolean = true): Collapsible {
        val outer = vertical()
        if (inCard) {
            outer.background = round(color(R.color.card), 20)
            outer.setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        val header = horizontal()
        header.minimumHeight = dp(56)
        header.setPadding(0, dp(6), 0, dp(6))
        header.background = pressable(12)
        header.isClickable = true
        header.isFocusable = true
        val ic = if (iconRes != null) icon(iconRes, R.color.text2) else null
        if (ic != null) addLeading(header, ic, 24, 16)
        val texts = vertical()
        val t = text(title, 15f)
        val sub = caption(subtitle ?: "")
        sub.visibility = if (subtitle == null) View.GONE else View.VISIBLE
        add(texts, t)
        add(texts, sub, topDp = 2)
        add(header, texts, width = 0, weight = 1f)
        val chevron = icon(R.drawable.ic_chevron, R.color.muted)
        if (chevron != null) {
            val lp = LinearLayout.LayoutParams(dp(20), dp(20))
            lp.leftMargin = dp(12)
            header.addView(chevron, lp)
        }
        add(outer, header)
        val content = vertical()
        if (inCard) content.setPadding(0, dp(4), 0, dp(12))
        add(outer, content)
        val col = Collapsible(outer, header, ic, t, sub, content, chevron)
        col.setOpen(open)
        return col
    }

    // ---------- Alt menü ----------

    /** Tek yüzey, 24 dp köşe. Sekmeler ağırlıkla eşit bölünür. */
    fun tabBar(): LinearLayout {
        val bar = horizontal()
        bar.background = round(color(R.color.card), 24)
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        return bar
    }

    fun tabItem(iconRes: Int, label: String, onClick: () -> Unit): TabItem {
        val v = vertical()
        v.setGravity(Gravity.CENTER)
        v.minimumHeight = dp(56)
        v.setPadding(dp(2), dp(6), dp(2), dp(6))
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener { onClick() }
        val ic = icon(iconRes, R.color.text2)
        if (ic != null) v.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
        val t = text(label, 12f, R.color.text2, bold = true)
        // Dört sekme her yazı boyutunda sığsın: 12 sp, ama en çok 14 dp.
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, minOf(spPx(12f), dp(14).toFloat()))
        t.setGravity(Gravity.CENTER)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        add(v, t, topDp = 2, width = WRAP)
        val item = TabItem(v, ic, t)
        setTab(item, false)
        return item
    }

    /** Seçili sekme: nane zeminli kapsül, nane ikon ve yazı. */
    fun setTab(item: TabItem, selected: Boolean) {
        val fg = color(if (selected) R.color.accent else R.color.text2)
        item.label.setTextColor(fg)
        item.icon?.imageTintList = ColorStateList.valueOf(fg)
        item.view.isSelected = selected
        item.view.background =
            if (selected) ripple(round(color(R.color.accent_bg), 18), null) else pressable(18)
    }

    // ---------- Diğer denetimler ----------

    fun field(hint: String): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setTextColor(color(R.color.text))
        e.setHintTextColor(color(R.color.muted))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        e.background = round(color(R.color.raised), 16)
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
        b.buttonTintList = ColorStateList(checkedStates, intArrayOf(color(R.color.accent), color(R.color.muted)))
        b.minHeight = dp(48)
        return b
    }

    /** Açıklama noktası (uyku evresi rengi). */
    fun dot(colorId: Int): View {
        val v = View(c)
        v.background = round(color(colorId), 3)
        return v
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
