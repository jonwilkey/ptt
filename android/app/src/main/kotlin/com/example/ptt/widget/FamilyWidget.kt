@file:Suppress("MagicNumber") // the presence palette is colour literals

package com.example.ptt.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColorProvider
import com.example.ptt.MainActivity
import com.example.ptt.R
import com.example.ptt.crypto.EnvelopePayload
import com.example.ptt.talk.FamilyPerson
import com.example.ptt.talk.SendIntents
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private val WIDGET_CORNER_RADIUS = 16.dp

/** The provider's minimum resize size (3x1) and default size (4x2); see res/xml/family_widget_info.xml. */
internal val COMPACT_SIZE = DpSize(180.dp, 40.dp)

// Below the 250x110dp default so a launcher that reports a slightly small 4x2 still gets the normal layout.
internal val NORMAL_SIZE = DpSize(220.dp, 100.dp)

/** Narrower or shorter than the default 4x2: one-line rows with first names and smaller buttons. */
internal fun isCompact(size: DpSize) = size.width < NORMAL_SIZE.width || size.height < NORMAL_SIZE.height

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetStates(): WidgetStates
}

/**
 * The home-screen widget: one row per family member (name, presence dot, Page and Urgent), a badge when the setup
 * checklist has regressed. Page and Urgent start [com.example.ptt.talk.SendActivity], which unlocks the phone first
 * and then sends and opens the talk screen; nothing here starts a page on a locked phone.
 */
class FamilyWidget : GlanceAppWidget() {
    // Responsive, not Exact: two discrete layouts (compact 3x1, normal 4x2) give the launcher a bounded set of
    // RemoteViews to cache instead of one per pixel size, and the layout choice only needs "is it small".
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT_SIZE, NORMAL_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val states = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            .widgetStates()
        // Collected inside the composition: updateAll on a live session only recomposes, it does not call
        // provideGlance again, so a one-off read would draw stale state.
        val initial = states.current()
        provideContent {
            val state by states.states.collectAsState(initial)
            GlanceTheme { WidgetContent(state) }
        }
    }
}

class FamilyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FamilyWidget()
}

/** The Page and Urgent intents of one row; they differ in their data URI, so their PendingIntents are distinct. */
internal fun sendIntents(context: Context, person: FamilyPerson): Pair<Intent, Intent> = Pair(
    SendIntents.intent(context, person.userId, EnvelopePayload.LEVEL_NORMAL, person.name),
    SendIntents.intent(context, person.userId, EnvelopePayload.LEVEL_URGENT, person.name)
)

@Composable
internal fun WidgetContent(state: WidgetState) {
    val context = LocalContext.current
    val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
    val compact = isCompact(LocalSize.current)
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(WIDGET_CORNER_RADIUS).padding(if (compact) 4.dp else 8.dp)
    ) {
        when (state) {
            WidgetState.NeedsSetup -> Message(
                context.getString(R.string.widget_needs_setup),
                openApp,
                compact = compact
            )

            is WidgetState.Family -> FamilyBody(state, compact, openApp)
        }
    }
}

@Composable
private fun FamilyBody(state: WidgetState.Family, compact: Boolean, openApp: Action) {
    val context = LocalContext.current
    val warning = context.getString(R.string.widget_checklist_warning)
    if (state.people.isEmpty()) {
        if (state.checklistWarning) Message(warning, openApp, warning = true, compact = compact)
        // A compact widget has room for one line: the warning, if any, outranks "no one to page".
        if (!compact || !state.checklistWarning) {
            Message(context.getString(R.string.widget_nobody), openApp, compact = compact)
        }
    } else if (compact) {
        // At one row tall a header would push the only person out of view, so the badge becomes the first
        // (scrollable) list item instead.
        LazyColumn {
            if (state.checklistWarning) item { Message(warning, openApp, warning = true, compact = true) }
            val names = state.people.map { it.person.name }
            items(state.people, itemId = { it.person.userId.hashCode().toLong() }) {
                PersonRow(it, compactLabel(it.person.name, names), compact = true)
            }
        }
    } else {
        if (state.checklistWarning) Message(warning, openApp, warning = true)
        LazyColumn {
            items(state.people, itemId = {
                it.person.userId.hashCode().toLong()
            }) { PersonRow(it, it.person.name, compact = false) }
        }
    }
}

@Composable
private fun Message(text: String, action: Action, warning: Boolean = false, compact: Boolean = false) {
    Text(
        text,
        maxLines = if (compact) 2 else Int.MAX_VALUE,
        modifier = GlanceModifier.fillMaxWidth().padding(if (compact) 2.dp else 8.dp).clickable(action),
        style = TextStyle(
            color = if (warning) GlanceTheme.colors.error else GlanceTheme.colors.onSurface,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = if (warning) FontWeight.Bold else FontWeight.Normal
        )
    )
}

@Composable
private fun PersonRow(row: WidgetPerson, label: String, compact: Boolean) {
    val context = LocalContext.current
    val name = row.person.name
    val (page, urgent) = sendIntents(context, row.person)
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_presence_dot),
            contentDescription = presenceDescription(context, name, row.presence),
            modifier = GlanceModifier.size(12.dp),
            colorFilter = ColorFilter.tint(presenceColor(row.presence))
        )
        Spacer(GlanceModifier.width(if (compact) 4.dp else 8.dp))
        Box(GlanceModifier.defaultWeight()) {
            Text(
                label,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = if (compact) 14.sp else 16.sp)
            )
        }
        Button(
            context.getString(R.string.widget_page),
            actionStartActivity(page),
            GlanceModifier.buttonPadding(compact)
                .semantics { contentDescription = context.getString(R.string.widget_page_cd, name) },
            style = buttonStyle(compact)
        )
        Spacer(GlanceModifier.width(4.dp))
        Button(
            context.getString(R.string.widget_urgent),
            actionStartActivity(urgent),
            GlanceModifier.buttonPadding(compact)
                .semantics { contentDescription = context.getString(R.string.widget_urgent_cd, name) },
            style = buttonStyle(compact)
        )
    }
}

// Glance's Button adds 16dp x 8dp padding of its own unless the modifier carries a padding, which would leave a
// 180dp-wide, 40dp-tall widget no room for the name or any vertical margin; the explicit padding switches it off.
private fun GlanceModifier.buttonPadding(compact: Boolean) =
    if (compact) padding(horizontal = 8.dp, vertical = 4.dp) else this

private fun buttonStyle(compact: Boolean) = TextStyle(fontSize = if (compact) 12.sp else 14.sp)

internal fun presenceDescription(context: Context, name: String, presence: WidgetPresence): String = context.getString(
    when (presence) {
        WidgetPresence.OK -> R.string.widget_presence_ok
        WidgetPresence.WARNING -> R.string.widget_presence_warning
        WidgetPresence.OFFLINE -> R.string.widget_presence_offline
    },
    name
)

private val OK_DAY = Color(0xFF2E7D32)
private val OK_NIGHT = Color(0xFF66BB6A)
private val WARNING_DAY = Color(0xFFB26A00)
private val WARNING_NIGHT = Color(0xFFFFB74D)
private val OFFLINE_DAY = Color(0xFF757575)
private val OFFLINE_NIGHT = Color(0xFF9E9E9E)

private fun presenceColor(presence: WidgetPresence): GlanceColorProvider = when (presence) {
    WidgetPresence.OK -> ColorProvider(day = OK_DAY, night = OK_NIGHT)
    WidgetPresence.WARNING -> ColorProvider(day = WARNING_DAY, night = WARNING_NIGHT)
    WidgetPresence.OFFLINE -> ColorProvider(day = OFFLINE_DAY, night = OFFLINE_NIGHT)
}
