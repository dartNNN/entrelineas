package local.bilingual.reader.actions
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.ichi2.anki.api.AddContentApi
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.bilingual.reader.reading.*


/** What an action may do besides talking to other apps. */
interface ActionHost {
    val context: Context
    /** Where actions run work off the main thread (other apps' providers, files). */
    val scope: CoroutineScope
    fun isKnown(key: String): Boolean
    fun toggleKnown(key: String)
    /** Asks the owner for a runtime permission (AnkiDroid's), then calls [then] with the answer. */
    fun requestPermission(permission: String, then: (Boolean) -> Unit)
    /** Shows the reader's own list of apps that take text and sends [text] to the one chosen. */
    fun chooseApp(text: String)
}

/**
 * One thing to do with a selection. A new action is one more implementation listed in [TextActions.available];
 * selection, panel and settings pick it up without changes.
 */
interface TextAction {
    val id: String
    fun title(host: ActionHost, content: SelectionContent): String
    /** Whether the action makes sense for this selection; the panel hides it otherwise. */
    fun accepts(content: SelectionContent): Boolean = true
    /** [text] is the action's template filled from the selection; [layer] is the layer chosen in the panel. */
    fun run(host: ActionHost, text: String, content: SelectionContent, layer: Layer)
}

private class CopyAction : TextAction {
    override val id = ACTION_COPY
    override fun title(host: ActionHost, content: SelectionContent) = "Копировать"
    override fun run(host: ActionHost, text: String, content: SelectionContent, layer: Layer) {
        val clipboard = host.context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(content.book, text))
        Toast.makeText(host.context, "Скопировано", Toast.LENGTH_SHORT).show()
    }
}

private class ShareAction : TextAction {
    override val id = ACTION_SHARE
    override fun title(host: ActionHost, content: SelectionContent) = "Поделиться"
    override fun run(host: ActionHost, text: String, content: SelectionContent, layer: Layer) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, content.book)
        host.context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Google Translate: the app through ACTION_PROCESS_TEXT when it is installed, the web page otherwise. */
private class TranslateAction : TextAction {
    override val id = ACTION_TRANSLATE
    override fun title(host: ActionHost, content: SelectionContent) = "Google Переводчик"
    override fun run(host: ActionHost, text: String, content: SelectionContent, layer: Layer) {
        val app = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(GOOGLE_TRANSLATE)
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            host.context.startActivity(app)
        } catch (_: ActivityNotFoundException) {
            val url = "https://translate.google.com/?sl=${content.language(layer)}&tl=${if (layer == Layer.ORIGINAL) content.support else content.studied}" +
                "&op=translate&text=" + Uri.encode(text)
            host.context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** The word card's "I know this sense", for a single word that has a gloss. */
private class KnownAction : TextAction {
    override val id = ACTION_KNOWN
    override fun title(host: ActionHost, content: SelectionContent) =
        if (host.isKnown(content.key)) "Снова показывать подсказку" else "Знаю это значение"
    override fun accepts(content: SelectionContent) = content.key.isNotEmpty()
    override fun run(host: ActionHost, text: String, content: SelectionContent, layer: Layer) = host.toggleKnown(content.key)
}

/**
 * A flashcard in AnkiDroid through its add-content API: the note goes straight into the deck "Entrelíneas" with the
 * reader's own note type (created on first use). Needs AnkiDroid installed and its permission, asked once.
 */
private class AnkiAction(override val id: String, private val title: String, private val note: (SelectionContent) -> AnkiNote,
                         private val accepts: (SelectionContent) -> Boolean) : TextAction {
    override fun title(host: ActionHost, content: SelectionContent) = title
    override fun accepts(content: SelectionContent) = accepts.invoke(content)
    override fun run(host: ActionHost, text: String, content: SelectionContent, layer: Layer) {
        val context = host.context
        if (AddContentApi.getAnkiDroidPackageName(context) == null) {
            toast(context, "AnkiDroid не установлен")
            return
        }
        if (ContextCompat.checkSelfPermission(context, AddContentApi.READ_WRITE_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            host.requestPermission(AddContentApi.READ_WRITE_PERMISSION) { granted ->
                if (granted) add(host, content) else toast(context, "Без разрешения AnkiDroid карточку не добавить")
            }
            return
        }
        add(host, content)
    }

    /** AnkiDroid's provider is another process: the calls run on the IO dispatcher, the result is shown on the main thread. */
    private fun add(host: ActionHost, content: SelectionContent) = host.scope.launch {
        val context = host.context
        toast(context, withContext(Dispatchers.IO) { addNote(context, content) })
    }

    private fun addNote(context: Context, content: SelectionContent): String {
        val n = note(content)
        val result = runCatching {
            val api = AddContentApi(context)
            val deck = api.deckList?.entries?.firstOrNull { it.value == AnkiNotes.DECK }?.key ?: api.addNewDeck(AnkiNotes.DECK)
            val model = api.modelList?.entries?.firstOrNull { it.value == n.model.name }?.key
                ?: api.addNewCustomModel(n.model.name, n.model.fields.toTypedArray(), arrayOf("Карточка"), arrayOf(n.model.front),
                    arrayOf(n.model.back), n.model.css, deck, 0)
            if (deck == null || model == null) return@runCatching "Не удалось создать колоду или тип записи в AnkiDroid"
            if (api.findDuplicateNotes(model, n.key)?.isNotEmpty() == true) return@runCatching "Уже есть в Anki"
            if (api.addNote(model, deck, n.fields.toTypedArray(), setOf(AnkiNotes.TAG)) != null) "Добавлено в Anki" else "AnkiDroid не принял карточку"
        }
        return result.getOrElse { "Ошибка AnkiDroid: ${it.message}" }
    }
}

/**
 * Speech through Android's text-to-speech (the engine chosen in the phone's settings, usually Google). It reads the
 * chosen layer in that layer's language from the book's manifest: the original in the studied language, translations
 * and notes in the support one. Offers the speech settings when the voice is not installed.
 */
private class SpeakAction : TextAction {
    override val id = ACTION_SPEAK
    override fun title(host: ActionHost, content: SelectionContent) = "Озвучить"
    override fun run(host: ActionHost, text: String, content: SelectionContent, layer: Layer) =
        Speech.say(host.context, content.layer(layer), Locale.forLanguageTag(content.language(layer)))
}

/** A short message over the screen, for what an action did. */
internal fun toast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

/** One action for every other app: the reader's own list of the apps that take text (see [AppTargets]). */
private class OpenInAppAction : TextAction {
    override val id = ACTION_OPEN_IN_APP
    override fun title(host: ActionHost, content: SelectionContent) = "Открыть в приложении…"
    override fun run(host: ActionHost, text: String, content: SelectionContent, layer: Layer) = host.chooseApp(text)
}

private const val GOOGLE_TRANSLATE = "com.google.android.apps.translate"

object TextActions {
    /** The built-in actions; other apps are reached through "Открыть в приложении…". */
    fun available(): List<TextAction> {
        val ankiWord = AnkiAction(ACTION_ANKI_WORD, "Anki: слово", AnkiNotes::word) { it.key.isNotEmpty() }
        val ankiSentence = AnkiAction(ACTION_ANKI_SENTENCE, "Anki: предложение", AnkiNotes::sentence) { it.original.isNotBlank() }
        return listOf(KnownAction(), CopyAction(), ankiWord, ankiSentence, SpeakAction(), TranslateAction(), OpenInAppAction(), ShareAction())
    }
}
