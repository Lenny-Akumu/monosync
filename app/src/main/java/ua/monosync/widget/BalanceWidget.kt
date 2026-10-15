package ua.monosync.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ua.monosync.app
import ua.monosync.ui.MainActivity

class BalanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.app.repo
        val initial = getAppWidgetState(context, PreferencesGlanceStateDefinition, id).let {
            runCatching { WidgetLoader.load(repo, WidgetMode.of(it[KEY_MODE]), it[KEY_ACCOUNT]) }.getOrNull()
        }
        provideContent {
            val prefs = currentState<Preferences>()
            val mode = WidgetMode.of(prefs[KEY_MODE])
            val account = prefs[KEY_ACCOUNT]
            val tick = prefs[KEY_TICK] ?: 0L
            var data by remember { mutableStateOf(initial) }
            LaunchedEffect(mode, account, tick) {
                runCatching { WidgetLoader.load(repo, mode, account) }.onSuccess { data = it }
            }
            GlanceTheme {
                Content(data, mode, WidgetStyle.of(prefs[KEY_STYLE]))
            }
        }
    }

    private class Palette(val bg: ColorProvider?, val fg: ColorProvider, val muted: ColorProvider, val accent: ColorProvider, val track: ColorProvider, val warn: ColorProvider)

    @Composable
    private fun palette(style: WidgetStyle): Palette {
        val c = GlanceTheme.colors
        return when (style) {
            WidgetStyle.COLOR -> Palette(c.primaryContainer, c.onPrimaryContainer, c.onPrimaryContainer, c.primary, c.surface, c.error)
            WidgetStyle.SURFACE -> Palette(c.widgetBackground, c.onSurface, c.onSurfaceVariant, c.primary, c.secondaryContainer, c.error)
            WidgetStyle.CLEAR -> Palette(
                null, ColorProvider(Color.White), ColorProvider(Color(0xDDFFFFFF)),
                ColorProvider(Color(0xFFFFD500)), ColorProvider(Color(0x55FFFFFF)), ColorProvider(Color(0xFFFF8A80)),
            )
        }
    }

    @Composable
    private fun Content(data: WidgetData?, mode: WidgetMode, style: WidgetStyle) {
        val size = LocalSize.current
        val p = palette(style)
        val tiny = size.width < 100.dp || size.height < 64.dp
        val open = if (mode == WidgetMode.SUBSCRIPTIONS) actionStartActivity<MainActivity>(actionParametersOf(SUBS to "open"))
        else actionStartActivity<MainActivity>()
        var m = GlanceModifier.fillMaxSize()
        if (p.bg != null) m = m.background(p.bg).cornerRadius(if (tiny) 18.dp else 24.dp)
        Box(m.clickable(open).padding(if (tiny) 8.dp else 14.dp), contentAlignment = if (tiny) Alignment.Center else Alignment.CenterStart) {
            when {
                data == null -> Text("…", style = TextStyle(color = p.fg, fontSize = 18.sp))
                tiny -> Tiny(data, size, p)
                else -> Full(data, size, p)
            }
        }
    }

    @Composable
    private fun Tiny(d: WidgetData, size: DpSize, p: Palette) {
        val wide = size.width >= 100.dp
        Column(horizontalAlignment = if (wide) Alignment.Start else Alignment.CenterHorizontally) {
            if (size.height >= 56.dp) Line(d.label, p.muted, 10.sp, align = if (wide) TextAlign.Start else TextAlign.Center)
            Line(if (wide) d.headline else d.short, p.fg, if (wide) 18.sp else 15.sp, bold = true, align = if (wide) TextAlign.Start else TextAlign.Center)
            d.progress?.takeIf { size.height >= 72.dp }?.let { Progress(it, p, if (wide) GlanceModifier.fillMaxWidth() else GlanceModifier.width(size.width - 20.dp)) }
        }
    }

    @Composable
    private fun Full(d: WidgetData, size: DpSize, p: Palette) {
        val short = size.height < 110.dp
        val big = size.height >= 300.dp && size.width >= 240.dp
        val cols = if (size.width >= 380.dp && size.height >= 200.dp) 2 else 1
        val rowH = (if (big) 44 else 38) + (if (d.rows.any { it.progress != null }) 8 else 0)
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = if (short) Alignment.CenterVertically else Alignment.Top) {
            Line(d.label, p.muted, if (big) 14.sp else 12.sp)
            Spacer(GlanceModifier.height(2.dp))
            Line(d.headline, p.fg, if (big) 36.sp else if (short || size.width < 160.dp) 20.sp else 26.sp, bold = true)
            d.caption?.takeIf { size.height >= 84.dp }?.let {
                Spacer(GlanceModifier.height(2.dp))
                Line(it, p.muted, if (big) 14.sp else 12.sp)
            }
            d.progress?.takeIf { size.height >= 100.dp }?.let {
                Spacer(GlanceModifier.height(6.dp))
                Progress(it, p)
            }
            if (!short) {
                val used = (if (big) 140 else 110) + (if (d.progress != null) 10 else 0)
                val room = ((size.height.value - used) / rowH).toInt() * cols
                if (d.empty != null && d.rows.isEmpty()) {
                    Spacer(GlanceModifier.height(10.dp))
                    Line(d.empty, p.muted, 12.sp)
                } else if (room > 0 && size.width >= 140.dp) {
                    Spacer(GlanceModifier.height(if (big) 14.dp else 10.dp))
                    val showSub = size.width / cols >= 200.dp
                    Column(GlanceModifier.fillMaxWidth()) {
                        d.rows.take(room).chunked(cols).chunked(8).forEach { block ->
                            Column(GlanceModifier.fillMaxWidth()) {
                                block.forEach { line ->
                                    if (cols == 1) RowItem(line[0], p, showSub, big)
                                    else Row(GlanceModifier.fillMaxWidth()) {
                                        RowItem(line[0], p, showSub, big, GlanceModifier.defaultWeight())
                                        Spacer(GlanceModifier.width(16.dp))
                                        line.getOrNull(1)?.let { RowItem(it, p, showSub, big, GlanceModifier.defaultWeight()) }
                                            ?: Spacer(GlanceModifier.defaultWeight())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RowItem(r: WidgetRow, p: Palette, showSub: Boolean, big: Boolean, modifier: GlanceModifier = GlanceModifier.fillMaxWidth()) {
        val fs = if (big) 15.sp else 13.sp
        Column(modifier.padding(vertical = if (big) 5.dp else 3.dp)) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(GlanceModifier.defaultWeight()) {
                    Line(r.title, p.fg, fs)
                    r.sub?.takeIf { showSub && it.isNotBlank() }?.let { Line(it, p.muted, if (big) 12.sp else 10.sp) }
                }
                Spacer(GlanceModifier.width(8.dp))
                Line(r.value, if (r.positive) p.accent else p.fg, fs, bold = true)
            }
            r.progress?.let {
                Spacer(GlanceModifier.height(3.dp))
                Progress(it, p)
            }
        }
    }

    @Composable
    private fun Progress(v: Float, p: Palette, modifier: GlanceModifier = GlanceModifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = v.coerceIn(0f, 1f),
            modifier = modifier.height(4.dp),
            color = if (v > 1f) p.warn else p.accent,
            backgroundColor = p.track,
        )
    }

    @Composable
    private fun Line(text: String, color: ColorProvider, size: TextUnit, bold: Boolean = false, align: TextAlign = TextAlign.Start) {
        Text(
            text,
            maxLines = 1,
            style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium, textAlign = align),
        )
    }

    companion object {
        val KEY_MODE = stringPreferencesKey("mode")
        val KEY_ACCOUNT = stringPreferencesKey("account")
        val KEY_STYLE = stringPreferencesKey("style")
        val KEY_TICK = longPreferencesKey("tick")
        private val SUBS = ActionParameters.Key<String>("subscriptions")

        suspend fun refresh(context: Context) {
            runCatching {
                val ids = GlanceAppWidgetManager(context).getGlanceIds(BalanceWidget::class.java)
                val now = System.currentTimeMillis()
                ids.forEach { id ->
                    updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { it.toMutablePreferences().apply { this[KEY_TICK] = now } }
                    BalanceWidget().update(context, id)
                }
            }
        }
    }
}

class BalanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BalanceWidget()
}
