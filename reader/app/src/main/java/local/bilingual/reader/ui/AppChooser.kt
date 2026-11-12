package local.bilingual.reader.ui
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import local.bilingual.reader.actions.*


/**
 * The reader's own list for "Открыть в приложении…": recently used apps first, then the apps that work with selected
 * text, then the apps that take shared text. A tap sends the text and closes the list.
 */
@Composable
fun AppChooser(targets: List<AppTarget>, onChoose: (AppTarget) -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
        Text("Открыть в приложении", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (targets.isEmpty()) {
            Text("Нет приложений, которые принимают текст", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            return@Column
        }
        LazyColumn(Modifier.heightIn(max = 480.dp).testTag("appChooser")) {
            items(targets, key = { it.id }) { t ->
                val icon = remember(t.id) {
                    runCatching { pm.getActivityIcon(t.component).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                }
                Row(Modifier.fillMaxWidth().clickable { onChoose(t) }.padding(horizontal = 24.dp, vertical = 10.dp).testTag("app:${t.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) Image(icon, null, Modifier.size(36.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(t.label, style = MaterialTheme.typography.bodyLarge)
                        Text(if (t.process) "работает с выделенным текстом" else "принимает текст", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
