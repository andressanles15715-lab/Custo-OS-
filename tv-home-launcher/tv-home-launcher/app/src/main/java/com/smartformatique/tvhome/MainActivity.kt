package com.smartformatique.tvhome

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Une application affichable dans le launcher. */
data class AppItem(
    val label: String,
    val pkg: String,
    val component: ComponentName,
    val art: Drawable,
    val isBanner: Boolean,
    val color: Int,
    val lastUsed: Long
)

/**
 * Écran d'accueil Android TV inspiré du design tvOS :
 * fond qui prend la couleur de l'app sélectionnée, "top shelf" avec visuel
 * et titre, rangée d'apps aux coins arrondis avec effet de focus
 * (zoom, ombre, reflet qui balaie la carte) et horloge en haut à droite.
 */
class MainActivity : Activity() {

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val darkCard = 0xFF202024.toInt()

    private lateinit var bg: View
    private lateinit var shelf: LinearLayout
    private lateinit var shelfTitle: TextView
    private lateinit var shelfSub: TextView
    private lateinit var shelfArt: ImageView
    private lateinit var row1: LinearLayout
    private lateinit var row2: LinearLayout
    private lateinit var row2Label: TextView
    private lateinit var scroll1: HorizontalScrollView
    private lateinit var scroll2: HorizontalScrollView

    private var currentTop = 0xFF1B2A4A.toInt()
    private var bgAnimator: ValueAnimator? = null
    private var dirty = true

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            dirty = true
            loadApps()
        }
    }

    // ------------------------------------------------------------------
    // Cycle de vie
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        if (dirty) loadApps()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Appui sur HOME alors que le launcher est déjà ouvert : retour au début.
        scroll1.smoothScrollTo(0, 0)
        focusFirstCard()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Un écran d'accueil ne se ferme pas avec Retour.
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
        }
        bgAnimator?.cancel()
        io.shutdown()
    }

    // ------------------------------------------------------------------
    // Construction de l'interface
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        val root = FrameLayout(this)

        bg = View(this)
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))
        applyBg()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(32))
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        // --- Barre du haut : réglages, date, heure ---
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(64), dp(24), dp(64), 0)
            clipChildren = false
        }

        val gear = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            setOnClickListener { openSystemSettings() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.2f else 1f).scaleY(if (has) 1.2f else 1f)
                    .setDuration(160).start()
                v.background = if (has) circle(Color.argb(70, 255, 255, 255)) else null
            }
        }
        val gearIcon = ImageView(this).apply { setImageResource(R.drawable.ic_settings) }
        gear.addView(gearIcon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        top.addView(gear, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(24) })

        val date = TextClock(this).apply {
            format12Hour = "EEE d MMM"
            format24Hour = "EEE d MMM"
            setTextColor(Color.argb(179, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        top.addView(date, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(20) })

        val clock = TextClock(this).apply {
            format12Hour = "HH:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        top.addView(clock, LinearLayout.LayoutParams(WRAP, WRAP))
        content.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // --- "Top shelf" : titre + visuel de l'app sélectionnée ---
        shelf = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(72), dp(8), dp(72), dp(8))
            clipChildren = false
        }
        val shelfText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shelfTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            maxLines = 2
        }
        shelfSub = TextView(this).apply {
            setTextColor(Color.argb(179, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        shelfText.addView(shelfTitle, LinearLayout.LayoutParams(WRAP, WRAP))
        shelfText.addView(shelfSub, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
        shelf.addView(shelfText, LinearLayout.LayoutParams(0, WRAP, 1f))

        shelfArt = ImageView(this).apply {
            setClipToOutline(true)
            setOutlineProvider(roundedOutline(dp(18).toFloat()))
            setElevation(dp(8).toFloat())
            setBackgroundColor(darkCard)
        }
        shelf.addView(shelfArt, LinearLayout.LayoutParams(dp(440), dp(248)))
        content.addView(shelf, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Le visuel de la shelf s'adapte à la hauteur disponible (16:9).
        shelf.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            val avail = b - t - dp(16)
            val artH = minOf(avail, dp(248)).coerceAtLeast(dp(90))
            val artW = artH * 16 / 9
            val lp = shelfArt.layoutParams
            if (lp.height != artH || lp.width != artW) {
                lp.height = artH
                lp.width = artW
                shelfArt.layoutParams = lp
            }
        }

        // --- Rangée 1 : apps Android TV ---
        row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(72), dp(24), dp(72), dp(4))
        }
        scroll1 = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
        scroll1.addView(row1, FrameLayout.LayoutParams(WRAP, WRAP))
        content.addView(scroll1, LinearLayout.LayoutParams(MATCH, WRAP))

        // --- Rangée 2 : autres apps ---
        row2Label = TextView(this).apply {
            setText(R.string.other_apps)
            setTextColor(Color.argb(200, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(72), dp(16), 0, 0)
            visibility = View.GONE
        }
        content.addView(row2Label, LinearLayout.LayoutParams(WRAP, WRAP))

        row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(72), dp(16), dp(72), dp(4))
        }
        scroll2 = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        scroll2.addView(row2, FrameLayout.LayoutParams(WRAP, WRAP))
        content.addView(scroll2, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)
    }

    // ------------------------------------------------------------------
    // Chargement des applications
    // ------------------------------------------------------------------

    private fun loadApps() {
        dirty = false
        io.execute {
            val tvApps = buildItems(Intent.CATEGORY_LEANBACK_LAUNCHER, emptySet())
            val others = buildItems(Intent.CATEGORY_LAUNCHER, tvApps.map { it.pkg }.toSet())
            ui.post { if (!isDestroyed) showApps(tvApps, others) }
        }
    }

    private fun buildItems(category: String, exclude: Set<String>): List<AppItem> {
        val pm = packageManager
        val prefs = getSharedPreferences("home", MODE_PRIVATE)
        val seen = HashSet<String>(exclude)
        val out = ArrayList<AppItem>()
        val query = Intent(Intent.ACTION_MAIN).addCategory(category)
        val infos: List<ResolveInfo> = pm.queryIntentActivities(query, 0)

        for (ri in infos) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName
            if (pkg == packageName || !seen.add(pkg)) continue

            val label = ri.loadLabel(pm).toString()
            val banner: Drawable? = try {
                ai.loadBanner(pm) ?: ai.applicationInfo?.loadBanner(pm)
            } catch (e: Exception) {
                null
            }
            val art: Drawable = banner ?: ai.loadIcon(pm)
            out.add(
                AppItem(
                    label = label,
                    pkg = pkg,
                    component = ComponentName(pkg, ai.name),
                    art = art,
                    isBanner = banner != null,
                    color = dominantColor(art),
                    lastUsed = prefs.getLong("last_$pkg", 0L)
                )
            )
        }
        return out.sortedWith(
            compareByDescending<AppItem> { it.lastUsed }.thenBy { it.label.lowercase() }
        )
    }

    /** Couleur moyenne du visuel, ajustée pour servir de teinte de fond. */
    private fun dominantColor(d: Drawable): Int {
        val fallback = 0xFF2A3350.toInt()
        try {
            val copy = d.constantState?.newDrawable()?.mutate() ?: d
            val size = 24
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            copy.setBounds(0, 0, size, size)
            copy.draw(Canvas(bmp))
            val px = IntArray(size * size)
            bmp.getPixels(px, 0, size, 0, 0, size, size)
            bmp.recycle()

            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            for (p in px) {
                if (Color.alpha(p) > 128) {
                    r += Color.red(p)
                    g += Color.green(p)
                    b += Color.blue(p)
                    n++
                }
            }
            if (n == 0) return fallback

            val hsv = FloatArray(3)
            Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
            if (hsv[1] > 0.1f) hsv[1] = hsv[1].coerceIn(0.4f, 0.8f)
            hsv[2] = hsv[2].coerceIn(0.35f, 0.55f)
            return Color.HSVToColor(hsv)
        } catch (e: Exception) {
            return fallback
        }
    }

    private fun showApps(tvApps: List<AppItem>, others: List<AppItem>) {
        row1.removeAllViews()
        row2.removeAllViews()

        tvApps.forEach { row1.addView(makeCell(it, dp(260), dp(146), true)) }
        others.forEach { row2.addView(makeCell(it, dp(168), dp(94), false)) }

        val vis = if (others.isEmpty()) View.GONE else View.VISIBLE
        row2Label.visibility = vis
        scroll2.visibility = vis

        if (tvApps.isEmpty() && others.isEmpty()) {
            shelfTitle.setText(R.string.no_apps)
            shelfSub.text = ""
            shelfArt.setImageDrawable(null)
            return
        }
        scroll1.scrollTo(0, 0)
        focusFirstCard()
    }

    private fun focusFirstCard() {
        val first = (row1.getChildAt(0) ?: row2.getChildAt(0)) as? ViewGroup
        first?.getChildAt(0)?.requestFocus()
    }

    // ------------------------------------------------------------------
    // Cartes d'applications
    // ------------------------------------------------------------------

    private fun makeCell(item: AppItem, w: Int, h: Int, big: Boolean): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginEnd = dp(if (big) 28 else 22)
            }
        }

        val card = FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) setDefaultFocusHighlightEnabled(false)
            background = ColorDrawable(if (item.isBanner) darkCard else item.color)
            setClipToOutline(true)
            setOutlineProvider(roundedOutline(dp(if (big) 14 else 10).toFloat()))
            setElevation(dp(4).toFloat())
        }

        val img = ImageView(this).apply {
            setImageDrawable(item.art)
            if (item.isBanner) {
                scaleType = ImageView.ScaleType.CENTER_CROP
            } else {
                scaleType = ImageView.ScaleType.FIT_CENTER
                val p = (h * 0.16f).toInt()
                setPadding(p, p, p, p)
            }
        }
        card.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))

        // Reflet qui balaie la carte quand elle prend le focus.
        val shine = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF)
            )
            alpha = 0f
            rotation = 18f
        }
        card.addView(
            shine,
            FrameLayout.LayoutParams(w / 3, (h * 1.6f).toInt(), Gravity.CENTER_VERTICAL or Gravity.START)
        )

        val label = TextView(this).apply {
            text = item.label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = 0f
        }

        card.setOnFocusChangeListener { v, has ->
            if (has) {
                v.animate().scaleX(1.14f).scaleY(1.14f).translationZ(dp(24).toFloat())
                    .setDuration(200).start()
                label.animate().alpha(1f).setDuration(200).start()
                shine.animate().cancel()
                shine.translationX = -w / 3f
                shine.alpha = 1f
                shine.animate().translationX(w.toFloat()).setDuration(650)
                    .withEndAction { shine.alpha = 0f }.start()
                onFocused(item)
            } else {
                v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(160).start()
                label.animate().alpha(0f).setDuration(120).start()
                shine.animate().cancel()
                shine.alpha = 0f
            }
        }
        card.setOnClickListener { launch(item) }
        card.setOnLongClickListener {
            openAppInfo(item)
            true
        }

        cell.addView(card, LinearLayout.LayoutParams(w, h))
        cell.addView(label, LinearLayout.LayoutParams(w, WRAP).apply { topMargin = dp(22) })
        return cell
    }

    private fun onFocused(item: AppItem) {
        tintBackground(item.color)

        shelfTitle.text = item.label
        shelfSub.setText(R.string.hint_open)

        val art = item.art.constantState?.newDrawable()?.mutate() ?: item.art
        shelfArt.setImageDrawable(art)
        if (item.isBanner) {
            shelfArt.scaleType = ImageView.ScaleType.CENTER_CROP
            shelfArt.setPadding(0, 0, 0, 0)
            shelfArt.setBackgroundColor(darkCard)
        } else {
            shelfArt.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = (shelfArt.layoutParams.height * 0.16f).toInt()
            shelfArt.setPadding(p, p, p, p)
            shelfArt.setBackgroundColor(item.color)
        }

        shelf.animate().cancel()
        shelf.alpha = 0f
        shelf.animate().alpha(1f).setDuration(260).start()
    }

    // ------------------------------------------------------------------
    // Fond dégradé animé
    // ------------------------------------------------------------------

    private fun tintBackground(color: Int) {
        bgAnimator?.cancel()
        bgAnimator = ValueAnimator.ofObject(ArgbEvaluator(), currentTop, color).apply {
            duration = 450
            addUpdateListener {
                currentTop = it.animatedValue as Int
                applyBg()
            }
            start()
        }
    }

    private fun applyBg() {
        val bottom = ArgbEvaluator().evaluate(0.82f, currentTop, 0xFF050507.toInt()) as Int
        bg.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(currentTop, bottom)
        )
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private fun launch(item: AppItem) {
        try {
            getSharedPreferences("home", MODE_PRIVATE).edit()
                .putLong("last_${item.pkg}", System.currentTimeMillis()).apply()
            val i = Intent.makeMainActivity(item.component).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(i)
            dirty = true
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_launch, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo(item: AppItem) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${item.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_launch, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_launch, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Utilitaires de dessin
    // ------------------------------------------------------------------

    private fun circle(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundedOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
}
