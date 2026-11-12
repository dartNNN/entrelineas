package local.bilingual.reader.ui
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*


/**
 * Quick panel over the page: what the page shows (scheme, font, hints, translation), seen on the page at once.
 * The full screen holds the same and everything else, by tabs.
 */
@Composable
internal fun QuickSettingsPanel(s: Settings, shell: ShellSettings, systemDark: Boolean, colors: SchemeColors, book: Book?, chapter: Chapter?, layoutMs: Long,
                               onChange: (Settings) -> Unit, onShell: (ShellSettings) -> Unit, onAll: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Что показывать", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onAll, Modifier.testTag("allSettings")) { Text("Все настройки") }
        }
        SchemePicker(shell.scheme, systemDark) { onShell(shell.copy(scheme = it)) }
        Text("Размер шрифта ${s.fontSize.toInt()}", style = MaterialTheme.typography.labelLarge)
        Slider(s.fontSize, { onChange(s.copy(fontSize = it.toInt().toFloat())) }, valueRange = 14f..26f, steps = 11,
            modifier = Modifier.testTag("fontSize"))
        HintSettings(s, book, colors, onChange)
        TranslationSettings(s, chapter, colors, onChange)
        Section("Экран")
        Toggle("Полноэкранный режим", shell.fullscreen, note = "Без системных строк; часы, батарея и страница — в своей строке") {
            onShell(shell.copy(fullscreen = it))
        }
        Text("Разбивка главы на страницы: $layoutMs мс", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(vertical = 8.dp).testTag("layoutMs"))
    }
}
