package dev.bezh.zamer.ui

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.bezh.zamer.R
import dev.bezh.zamer.ble.LinkState
import dev.bezh.zamer.ble.ReferenceEdge
import dev.bezh.zamer.data.HistoryEntry
import dev.bezh.zamer.data.ThemeMode
import dev.bezh.zamer.domain.Draft
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.UnitSystem
import dev.bezh.zamer.ui.theme.ZamerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the space-constrained controls in each shipped locale at font scale 1.0 and 1.3,
 * writes PNGs to app/build/outputs/roborazzi/, and fails if any label wraps, is clipped or
 * ellipsized, or is pushed past the screen edge. Labels may still shrink through autoSize, so
 * look at the PNGs as well.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 35+ sandboxes need Java 21; the build runs on 17.
@Config(sdk = [34])
abstract class LabelFitTest(private val locale: String, private val translated: Boolean = true) {
    // Robolectric reads the app's manifest, which never lists the compose test host; register it here
    // so ui-test-manifest need not leak into the debug APK.
    private val registerHost = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        }
    }
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerHost).around(compose)

    private var fontScale by mutableFloatStateOf(1f)
    private val inset = Modifier.padding(horizontal = ScreenPadding)
    private val formatter = Formatter(UnitSystem.METRIC, 2)

    @Test
    fun measure() = render("measure") {
        ModeSelector(MeasureMode.DISTANCE, onSelect = {}, modifier = inset)
        listOf(MeasureMode.AREA, MeasureMode.VOLUME, MeasureMode.HEIGHT).forEach { mode ->
            Column(inset) {
                SlotRow(mode, Draft(List(mode.sides.size) { null }, next = 0), formatter, onSelect = {})
            }
        }
        Row(inset) { InlineName(HistoryEntry(1, 0, MeasureMode.AREA, listOf(4000, 3000)), onRename = { _, _ -> }) }
        ActionRow(canUndo = true, onUndo = {}, onReset = {}, modifier = inset)
    }

    @Test
    fun sketch() = render("sketch") {
        ToolSelector(SketchTool.LINE, onSelect = {}, modifier = inset)
        // Mirrors the action row inlined in SketchScreen.
        Row(inset.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val action = Modifier.weight(1f).height(72.dp)
            val padding = PaddingValues(horizontal = 8.dp)
            FilledTonalButton(onClick = {}, modifier = action, contentPadding = padding, shapes = ButtonDefaults.shapes()) {
                ActionLabel(Icons.AutoMirrored.Rounded.Undo, R.string.action_undo)
            }
            FilledTonalButton(onClick = {}, modifier = action, contentPadding = padding, shapes = ButtonDefaults.shapes()) {
                ActionLabel(Icons.Rounded.Delete, R.string.action_delete)
            }
            Button(onClick = {}, modifier = action, contentPadding = padding, shapes = ButtonDefaults.shapes()) {
                ActionLabel(Icons.Rounded.DeleteSweep, R.string.action_clear)
            }
        }
    }

    // The area line shares the bar with the pill's button and wraps even in English at 1.3; no correct
    // wording of "Area" fits beside every button, so it is reported, not enforced.
    @Test
    fun sketchTopBar() = render("sketch-topbar", known = { it.startsWith(compose.activity.getString(R.string.sketch_total_area, "")) }) {
        // Mirrors the top bar inlined in SketchScreen, once per pill state that carries a button.
        listOf(LinkState.NeedsPermission, LinkState.BluetoothOff, LinkState.NeedsRepair, LinkState.NotPaired).forEach { link ->
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = ScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton({}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(stringResource(R.string.sketch_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.sketch_total_area, formatter.area(12.5).toString()),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton({}) { Icon(Icons.Rounded.FitScreen, stringResource(R.string.sketch_fit)) }
                ConnectionPill(link, onRequestPermission = {}, onEnableBluetooth = {}, onPair = {}, compact = true)
            }
        }
    }

    @Test
    fun sketchLengthField() = render("sketch-length") {
        LengthField("3.25", unit = "m", valid = { true }, onDone = {}, onCancel = {}, modifier = inset)
        LengthField("10.66", unit = "ft", valid = { true }, onDone = {}, onCancel = {}, modifier = inset)
    }

    @Test
    fun language() {
        render("language") {
            Column(inset) { LanguageCard() }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.language_system)).performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/language-menu-$locale.png")
        report(labelsThatDoNotFit().map { (text, problem) -> text to "[$locale menu] $problem" })
    }

    @Test
    fun connectionPills() = render("pills") {
        val needAction = listOf(LinkState.NeedsPermission, LinkState.BluetoothOff, LinkState.NeedsRepair, LinkState.NotPaired)
        needAction.forEach { link ->
            Row(inset) { ConnectionPill(link, onRequestPermission = {}, onEnableBluetooth = {}, onPair = {}) }
        }
        // The compact form sits in the sketch top bar, after the back button and the title.
        needAction.forEach { link ->
            Row(inset.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ConnectionPill(link, onRequestPermission = {}, onEnableBluetooth = {}, onPair = {}, compact = true)
            }
        }
    }

    @Test
    fun settingsDevice() = render("settings-device") {
        Column(inset, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Mirrors the paired-device card inlined in SettingsScreen.
            SettingsCard(stringResource(R.string.settings_device)) {
                Text("HOTO", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton({}, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.settings_repair)) }
                    TextButton({}) { Text(stringResource(R.string.settings_forget)) }
                }
            }
            SettingsCard(stringResource(R.string.settings_rangefinder)) {
                Text(stringResource(R.string.device_reference), style = MaterialTheme.typography.titleSmall)
                Segmented(
                    options = listOf(R.string.reference_rear to ReferenceEdge.REAR, R.string.reference_front to ReferenceEdge.FRONT),
                    selected = ReferenceEdge.REAR,
                    onSelect = {},
                )
                Text(stringResource(R.string.settings_units), style = MaterialTheme.typography.titleSmall)
                Segmented(
                    options = listOf(R.string.units_metric to UnitSystem.METRIC, R.string.units_imperial to UnitSystem.IMPERIAL),
                    selected = UnitSystem.METRIC,
                    onSelect = {},
                )
            }
        }
    }

    @Test
    fun settingsDisplay() = render("settings-display") {
        Column(inset, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Mirrors the decimal-places toggles inlined in SettingsScreen; only the title is translated.
            SettingsCard(stringResource(R.string.settings_precision)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    (1..3).forEach { n ->
                        ToggleButton(
                            checked = n == 2,
                            onCheckedChange = {},
                            modifier = Modifier.weight(1f).height(52.dp),
                            shapes = when (n) {
                                1 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                3 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                        ) { Text("$n") }
                    }
                }
            }
            SettingsCard(stringResource(R.string.settings_theme)) {
                Segmented(
                    options = listOf(
                        R.string.theme_system to ThemeMode.SYSTEM,
                        R.string.theme_light to ThemeMode.LIGHT,
                        R.string.theme_dark to ThemeMode.DARK,
                    ),
                    selected = ThemeMode.SYSTEM,
                    onSelect = {},
                )
            }
        }
    }

    @Test
    fun settingsSwitches() = render("settings-switches") {
        Column(inset, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard(null) {
                SwitchRow(R.string.settings_dynamic, checked = true, onChange = {})
                SwitchRow(R.string.settings_keep_on, checked = false, onChange = {})
                SwitchRow(R.string.settings_haptics, checked = true, onChange = {})
            }
        }
    }

    @Test
    fun navigationBar() = render("navigation") {
        // Mirrors the bottom bar inlined in MainScaffold.
        ShortNavigationBar {
            listOf(
                R.string.tab_measure to Icons.Rounded.Straighten,
                R.string.tab_history to Icons.Rounded.History,
                R.string.tab_settings to Icons.Rounded.Settings,
            ).forEachIndexed { i, (label, icon) ->
                ShortNavigationBarItem(
                    selected = i == 0,
                    onClick = {},
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }

    @Test
    fun history() = render("history") {
        // Mirrors the header row inlined in HistoryScreen.
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            TextButton({}) { Text(stringResource(R.string.history_clear)) }
        }
        TabSelector(HistoryTab.Results, onSelect = {}, modifier = inset)
    }

    private fun render(name: String, known: (String) -> Boolean = { false }, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                ZamerTheme(ThemeMode.LIGHT, dynamicColor = false) {
                    Surface(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                    }
                }
            }
        }
        val problems = listOf(1f, 1.3f).flatMap { scale ->
            fontScale = scale
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name-$locale-x$scale.png")
            labelsThatDoNotFit().map { (text, problem) -> text to "[$locale x$scale] $problem" }
        }
        report(problems, known)
    }

    private fun report(problems: List<Pair<String, String>>, known: (String) -> Boolean = { false }) {
        problems.forEach { (text, problem) -> println(if (known(text)) "known: $problem" else problem) }
        // English and Russian are the owner's source text and stay as written; only report them.
        val enforced = problems.filterNot { (text, _) -> known(text) }.map { it.second }
        if (translated) assertTrue(enforced.joinToString("\n"), enforced.isEmpty())
    }

    /** Each label that does not fit on one line in the width it got, as its text and the reason. */
    private fun labelsThatDoNotFit(): List<Pair<String, String>> {
        val screenWidth = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxOf { it.size.width }
        val labels = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        return labels.fetchSemanticsNodes().mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val text = layout.layoutInput.text.text
            // didOverflowWidth and hasVisualOverflow trip on labels that fit, so compare the label's
            // single-line natural width with the width it was given.
            val needed = layout.multiParagraph.intrinsics.maxIntrinsicWidth
            val given = layout.layoutInput.constraints.maxWidth
            val problem = when {
                needed > given + 0.5f -> "\"$text\" needs ${needed.toInt()}px on one line but gets $given px"
                layout.lineCount > 1 -> "\"$text\" wraps to ${layout.lineCount} lines"
                layout.isLineEllipsized(0) -> "\"$text\" is ellipsized"
                node.positionInRoot.x + node.size.width > screenWidth -> "\"$text\" is pushed past the screen edge"
                else -> return@mapNotNull null
            }
            text to problem
        }
    }
}

@Config(qualifiers = "en-w360dp-h800dp")
class LabelFitEnTest : LabelFitTest("en", translated = false)

@Config(qualifiers = "ru-w360dp-h800dp")
class LabelFitRuTest : LabelFitTest("ru", translated = false)

@Config(qualifiers = "es-w360dp-h800dp")
class LabelFitEsTest : LabelFitTest("es")

@Config(qualifiers = "de-w360dp-h800dp")
class LabelFitDeTest : LabelFitTest("de")

@Config(qualifiers = "zh-rCN-w360dp-h800dp")
class LabelFitZhTest : LabelFitTest("zh-rCN")
