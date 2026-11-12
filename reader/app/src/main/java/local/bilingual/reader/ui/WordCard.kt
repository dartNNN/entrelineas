package local.bilingual.reader.ui
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*


@Composable
internal fun WordCard(tap: WordTap, known: Set<String>, settings: Settings, entries: Map<String, Entry>, onToggleKnown: (String) -> Unit) {
    val s = tap.sentence
    val i = tap.token
    val lex = s.lexical[i]
    val segment = s.segments.firstOrNull { i in it.first..it.last }
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()).testTag("wordCard")) {
        Text(s.tokens[i], style = MaterialTheme.typography.headlineSmall)
        if (lex != null) {
            Text(listOfNotNull(lex.lemma, lex.gram).joinToString(" · "), style = MaterialTheme.typography.labelLarge)
            Text(lex.gloss, style = MaterialTheme.typography.bodyLarge)
            lex.form?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text("появление №${lex.ordinal}", style = MaterialTheme.typography.labelSmall)
        }
        // The whole dictionary entry, every sense with the chosen one marked: right under the gloss when the sense was
        // chosen from the dictionary alone (it is the gloss's source), after everything else when a model chose it.
        val entry = lex?.let { entries[it.entryKey] }
        @Composable
        fun dictionaryEntry() {
            if (lex == null || entry == null) return
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Словарь: ${entry.headword}", style = MaterialTheme.typography.labelLarge)
            entry.senses.forEach { s ->
                val chosen = s.id == lex.sense
                Text("${s.id}. ${s.text}" + (s.gram?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (chosen) FontWeight.SemiBold else null, modifier = Modifier.padding(top = 2.dp).testTag(if (chosen) "chosenSense" else "sense"))
            }
        }
        val entryFirst = lex?.from == "dictionary"
        if (entryFirst) dictionaryEntry()
        segment?.let {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text((it.first..it.last).joinToString("") { k -> s.tokens[k] + (if (k < it.last) s.ws.getOrElse(k) { " " } else "") },
                style = MaterialTheme.typography.labelLarge)
            Text(it.literal, style = MaterialTheme.typography.bodyMedium)
            it.literary?.let { lit -> Text("= $lit", style = MaterialTheme.typography.bodyMedium) }
        }
        s.reference?.takeIf { settings.referenceInCard }?.let { ref ->
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val mark = when {
                ref.uncertain -> " · сопоставление сомнительное"
                ref.loose -> " · вольный"
                else -> ""
            }
            Text("Перевод переводчика$mark", style = MaterialTheme.typography.labelLarge)
            Text(ref.text ?: "Переводчик пропустил это предложение", style = MaterialTheme.typography.bodyMedium)
            if (settings.showFootnotes) ref.notes.forEach { Text("Сноска: $it", style = MaterialTheme.typography.bodySmall) }
        }
        if (settings.showAuthorNotes && s.authorNotes.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            s.authorNotes.forEach { Text("Сноска автора: $it", style = MaterialTheme.typography.bodySmall) }
        }
        val notes = s.notes.filter { i in it.first..it.last }
        if (notes.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 8.dp))
        notes.forEach { n ->
            val label = mapOf("grammar" to "Грамматика", "context" to "Контекст", "variants" to "Варианты", "expression" to "Выражение")[n.kind]
            Text("$label: ${n.text}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (!entryFirst) dictionaryEntry()
        if (lex != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onToggleKnown(lex.key) }, Modifier.testTag("toggleKnown")) {
                Text(if (lex.key in known) "Снова показывать подсказку" else "Знаю это значение")
            }
        }
    }
}
