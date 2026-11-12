package local.bilingual.reader.ui
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.bilingual.reader.actions.*
import local.bilingual.reader.reading.*


/**
 * The panel of an active selection, over the bottom of the page: what is selected and a close button, what to
 * select (unit, extension), what to take (layer), a preview, and the actions in a row of their own — the ones set
 * "shown at once" as compact buttons, the rest under "Ещё".
 */
@Composable
fun SelectionPanel(sel: TextSelection, content: SelectionContent, units: List<SelectionUnit>, actions: List<Pair<TextAction, ActionConfig>>, host: ActionHost,
                   onChange: (TextSelection) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val usable = actions.filter { (a, cfg) -> cfg.enabled && a.accepts(content) }
    val pinned = usable.filter { it.second.pinned }
    val rest = usable.filterNot { it.second.pinned }
    fun run(a: TextAction, cfg: ActionConfig) = a.run(host, fillTemplate(cfg.template, content, sel.layer), content, sel.layer)
    Surface(modifier.fillMaxWidth().testTag("selectionPanel"), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Выделено: ${sel.unit.title.lowercase()} · ${sel.layer.title.lowercase()}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClose, Modifier.testTag("closeSelection")) { Icon(Icons.Filled.Close, "Снять выделение") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                units.forEach { u ->
                    FilterChip(selected = sel.unit == u, onClick = { onChange(sel.copy(unit = u, more = 0)) }, label = { Text(u.title) },
                        modifier = Modifier.testTag("unit:${u.name}"))
                }
                if (sel.unit == SelectionUnit.SENTENCE || sel.unit == SelectionUnit.PARAGRAPH) {
                    AssistChip(onClick = { onChange(sel.copy(more = sel.more + 1)) }, label = { Text("ещё") },
                        leadingIcon = { Icon(Icons.Filled.Add, null) }, modifier = Modifier.testTag("extend"))
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                content.layers().forEach { l ->
                    FilterChip(selected = sel.layer == l, onClick = { onChange(sel.copy(layer = l)) }, label = { Text(l.title) },
                        modifier = Modifier.testTag("layer:${l.name}"))
                }
            }
            Text(content.layer(sel.layer), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp, end = 12.dp).testTag("selectionText"))
            HorizontalDivider(Modifier.padding(end = 12.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                pinned.forEach { (a, cfg) ->
                    AssistChip(onClick = { run(a, cfg) }, label = { Text(a.title(host, content)) }, modifier = Modifier.testTag("run:${a.id}"))
                }
                if (rest.isNotEmpty()) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(onClick = { open = true }, label = { Text("Ещё") },
                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) }, modifier = Modifier.testTag("moreActions"))
                        DropdownMenu(open, { open = false }) {
                            rest.forEach { (a, cfg) ->
                                DropdownMenuItem(text = { Text(a.title(host, content)) }, onClick = { open = false; run(a, cfg) },
                                    modifier = Modifier.testTag("run:${a.id}"))
                            }
                        }
                    }
                }
            }
        }
    }
}
