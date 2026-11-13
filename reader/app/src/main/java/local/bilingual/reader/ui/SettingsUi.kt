package local.bilingual.reader.ui
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import local.bilingual.reader.actions.*
import local.bilingual.reader.format.*
import local.bilingual.reader.reading.*


/**
 * Settings screens. Every switch that changes the page shows a sample of what it adds, drawn in the page's own
 * styles and colours, so the owner sees which part of the text it controls; a switched-off sample is faded.
 */

/** Sample texts in the reader's styles for the current scheme. */
private class Samples(c: SchemeColors) {
    private val text = c.text.color()
    private val literal = SpanStyle(color = c.literal.color(), fontSize = 0.85.em, fontStyle = FontStyle.Italic)
    private val gloss = SpanStyle(color = c.gloss.color(), fontSize = 0.8.em)
    val style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp, color = text)

    val literalSample = buildAnnotatedString {
        append("En un lugar"); withStyle(literal) { append(" (В одном месте)") }; append(" de la Mancha")
    }
    val glossSample = buildAnnotatedString {
        append("Molinos"); withStyle(gloss) { append(" [мельницы]") }; append(" de viento"); withStyle(gloss) { append(" [ветер]") }
    }
    val notesSample = buildAnnotatedString {
        withStyle(SpanStyle(color = c.note.color(), fontSize = 0.8.em)) {
            append("◦ грамматика: de cuyo nombre — «чьё имя», относительное притяжательное cuyo\n※ контекст: Ламанча — край в Кастилии")
        }
    }
    val referenceSample = buildAnnotatedString {
        withStyle(SpanStyle(color = c.literal.color(), fontSize = 0.85.em)) { append("В одном селе Ламанчи, название которого не хочу вспоминать…") }
    }
    val footnoteSample = buildAnnotatedString { withStyle(SpanStyle(fontSize = 0.8.em)) { append("* Сноска переводчика к этому месту") } }
    val authorSample = buildAnnotatedString { withStyle(SpanStyle(fontSize = 0.8.em)) { append("* Сноска автора книги") } }
}

@Composable
private fun SampleText(text: AnnotatedString, samples: Samples, on: Boolean) {
    Text(text, style = samples.style, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).alpha(if (on) 1f else 0.35f))
}

/** A switch with what it controls: a short explanation and a sample of its part of the page. */
@Composable
fun Toggle(label: String, value: Boolean, note: String? = null, sample: (@Composable () -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(value, onChange, Modifier.testTag("toggle:$label"))
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        sample?.invoke()
    }
}

@Composable
fun Section(title: String, note: String? = null) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 2.dp))
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** What the page shows around the original: literal translations, glosses, notes, and the repeat without them. */
@Composable
fun HintSettings(s: Settings, book: Book?, colors: SchemeColors, onChange: (Settings) -> Unit) {
    val samples = remember(colors) { Samples(colors) }
    Section("Подсказки в тексте", "Всё, что стоит рядом с испанским текстом. Образец под переключателем показывает, как это выглядит.")
    Toggle("Дословный перевод сегментов", s.showLiteral, note = "Перевод каждого смыслового сегмента в скобках, курсивом",
        sample = { SampleText(samples.literalSample, samples, s.showLiteral) }) { onChange(s.copy(showLiteral = it)) }
    Toggle("Подсказки к словам", s.showLexical, note = "Значение слова в квадратных скобках сразу после него",
        sample = { SampleText(samples.glossSample, samples, s.showLexical) }) { onChange(s.copy(showLexical = it)) }
    Text("Подсказка к значению слова — первые ${s.threshold} раз", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    Text("Каждое значение подсказывается первые N раз за книгу, потом исчезает. Знакомое можно скрыть сразу: карточка слова → «Знаю это значение».",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(s.threshold.toFloat(), { onChange(s.copy(threshold = it.toInt())) }, valueRange = 1f..(book?.maxOccurrence ?: 20).toFloat(),
        steps = (book?.maxOccurrence ?: 20) - 2, enabled = s.showLexical, modifier = Modifier.testTag("threshold"))
    Toggle("Грамматика и контекст", s.showNotes, note = "Мелкие заметки под абзацем: ◦ грамматика, ※ реалии и контекст",
        sample = { SampleText(samples.notesSample, samples, s.showNotes) }) { onChange(s.copy(showNotes = it)) }

    Section("Повтор без подсказок", "Тот же текст ещё раз, чистым, чтобы перечитать без опоры.")
    if (!s.hasHints) {
        Text("Не показывается, пока выключены все подсказки: текст и так без них.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("repeatOff"))
    }
    listOf(Repeat.OFF to ("Нет" to ""), Repeat.PARAGRAPH to ("Каждый абзац под ним" to "как в книгах Франка"),
        Repeat.SCREEN to ("Весь экран следующим экраном" to "сначала экран с подсказками, свайп — он же без них"))
        .forEach { (mode, labels) ->
            Row(Modifier.fillMaxWidth().testTag("repeat:${mode.name}")
                .selectable(s.repeat == mode, enabled = s.hasHints) { onChange(s.copy(repeat = mode)) },
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.repeat == mode, onClick = null, enabled = s.hasHints)
                Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(labels.first, color = if (s.hasHints) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (labels.second.isNotEmpty()) Text(labels.second, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
}

/** The translator's text and both kinds of footnotes; shown only when the chapter being read has them. */
@Composable
fun TranslationSettings(s: Settings, chapter: Chapter?, colors: SchemeColors, onChange: (Settings) -> Unit) {
    val samples = remember(colors) { Samples(colors) }
    val sentences = chapter?.paragraphs?.asSequence()?.flatMap { it.sentences.asSequence() }
    val hasReference = sentences?.any { it.reference != null } == true
    val hasFootnotes = sentences?.any { it.reference?.notes?.isNotEmpty() == true } == true
    val hasAuthorNotes = sentences?.any { it.authorNotes.isNotEmpty() } == true
    if (!hasReference && !hasFootnotes && !hasAuthorNotes) return
    Section("Перевод переводчика и сноски", "Опубликованный русский перевод книги и сноски к ней.")
    if (hasReference) {
        Toggle("Перевод переводчика в карточке", s.referenceInCard, note = "В карточке слова — перевод всего предложения") {
            onChange(s.copy(referenceInCard = it))
        }
        Toggle("Перевод переводчика после абзаца", s.referenceInText, note = "Под каждым абзацем, мелко",
            sample = { SampleText(samples.referenceSample, samples, s.referenceInText) }) { onChange(s.copy(referenceInText = it)) }
    }
    if (hasFootnotes) Toggle("Сноски переводчика", s.showFootnotes, note = "Под переводом переводчика и в карточке",
        sample = { SampleText(samples.footnoteSample, samples, s.showFootnotes) }) { onChange(s.copy(showFootnotes = it)) }
    if (hasAuthorNotes) Toggle("Сноски автора", s.showAuthorNotes, note = "Сноски из испанского издания: под абзацем и в карточке",
        sample = { SampleText(samples.authorSample, samples, s.showAuthorNotes) }) { onChange(s.copy(showAuthorNotes = it)) }
}

@Composable
fun TypographySettings(s: Settings, onChange: (Settings) -> Unit) {
    Section("Шрифт")
    Text("Размер шрифта ${s.fontSize.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(s.fontSize, { onChange(s.copy(fontSize = it.toInt().toFloat())) }, valueRange = 14f..26f, steps = 11,
        modifier = Modifier.testTag("fontSize"))
    Text("Поля ${s.margin} dp", style = MaterialTheme.typography.labelLarge)
    Slider(s.margin.toFloat(), { onChange(s.copy(margin = (it / 4).toInt() * 4)) }, valueRange = 4f..48f, steps = 10,
        modifier = Modifier.testTag("margin"))
    Toggle("Выравнивание по ширине", s.justify, note = "Строки ровные с обеих сторон, как в книге") { onChange(s.copy(justify = it)) }
}

/** Colour scheme, full screen with its status line, and the top bar. */
@Composable
fun ViewSettings(shell: ShellSettings, systemDark: Boolean, onShell: (ShellSettings) -> Unit) {
    Section("Цветовая схема", "«Как в системе» — «Бумага» днём и «Сумерки», когда телефон в тёмной теме.")
    SchemePicker(shell.scheme, systemDark) { onShell(shell.copy(scheme = it)) }

    Section("Верхняя панель", "Меню и настройки. Появляется от касания места без слова, строки внизу экрана или свайпа вверх-вниз.")
    Text("Скрывать через ${shell.autoHideSeconds} с", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    Slider(shell.autoHideSeconds.toFloat(), { onShell(shell.copy(autoHideSeconds = it.toInt())) }, valueRange = 2f..10f,
        steps = 7, modifier = Modifier.testTag("autoHide"))

    Section("Полноэкранный режим", "Системные строки сверху и снизу скрыты; вместо них — своя строка состояния.")
    Toggle("Полноэкранный режим", shell.fullscreen) { onShell(shell.copy(fullscreen = it)) }
    val on = shell.fullscreen
    Text("Строка состояния", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp).alpha(if (on) 1f else 0.5f))
    listOf(true to "Сверху", false to "Снизу").forEach { (top, label) ->
        Row(Modifier.fillMaxWidth().selectable(shell.statusOnTop == top, enabled = on) { onShell(shell.copy(statusOnTop = top)) }
            .testTag("status:$label"), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = shell.statusOnTop == top, onClick = null, enabled = on)
            Text(label, modifier = Modifier.padding(start = 8.dp))
        }
    }
    Column(Modifier.alpha(if (on) 1f else 0.5f)) {
        Toggle("Часы", shell.clock) { onShell(shell.copy(clock = it)) }
        Toggle("Батарея", shell.battery) { onShell(shell.copy(battery = it)) }
        Toggle("Название главы", shell.chapterTitle) { onShell(shell.copy(chapterTitle = it)) }
    }

    Section("Прогресс", "Под страницей и в строке состояния полноэкранного режима. Страницы книги до конца не посчитаны — перед числом «~».")
    Text("Глава", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    Toggle("Страницы главы", shell.page, note = "12 / 40") { onShell(shell.copy(page = it)) }
    Toggle("Прогресс главы", shell.chapterProgress, note = "30%") { onShell(shell.copy(chapterProgress = it)) }
    Text("Книга", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    Toggle("Страницы книги", shell.bookPages, note = "123 / ~860") { onShell(shell.copy(bookPages = it)) }
    Toggle("Прогресс книги", shell.bookProgress, note = "14%") { onShell(shell.copy(bookProgress = it)) }
}

/** What an action receives: ready-made choices and an own variant; names map to [fillTemplate] placeholders. */
private val PRESETS = listOf(
    "{text}" to "Только выделенное",
    "{text}\\n{reference}" to "Выделенное и перевод переводчика",
    "{text}\\n{literal}" to "Выделенное и дословный перевод",
    "{original} — {gloss}\\n{sentence}" to "Слово, его значение и предложение (для карточек)",
)
private val FIELDS = listOf(
    "{text}" to "выделенное", "{original}" to "оригинал", "{literal}" to "дословно", "{reference}" to "перевод",
    "{lemma}" to "словарная форма", "{gloss}" to "значение", "{sentence}" to "предложение", "{chapter}" to "глава",
    "{book}" to "книга", "\\n" to "новая строка",
)
private val SAMPLE = SelectionContent(original = "molinos", literal = "мельниц", reference = "Тут увидели они тридцать или сорок ветряных мельниц.",
    lemma = "molino", gloss = "мельница", form = "", key = "", notes = "", sentence = "En esto, descubrieron treinta o cuarenta molinos de viento que hay en aquel campo.",
    chapter = "Capítulo VIII", book = "Don Quijote de la Mancha")

/** The actions of the selection panel: shown at once or under "Ещё", in order, with what each receives. */
@Composable
fun ActionsSettings(actions: List<Pair<TextAction, ActionConfig>>, host: ActionHost, onActions: (List<ActionConfig>) -> Unit) {
    Section("Действия с выделенным", "Долгое нажатие на слово выделяет его, внизу появляется панель с действиями. " +
        "Здесь — какие действия там есть, какие видны сразу, а какие спрятаны в «Ещё», и что каждое получает.")
    val configs = actions.map { it.second }
    val own = actions.indices.toList()
    @Composable
    fun card(idx: Int, group: List<Int>) {
        val (action, cfg) = actions[idx]
        val pos = group.indexOf(idx)
        ActionCard(action.title(host, SAMPLE), cfg, first = pos == 0, last = pos == group.lastIndex,
            onChange = { new -> onActions(configs.toMutableList().also { it[idx] = new }) },
            onMove = { delta -> group.getOrNull(pos + delta)?.let { other -> onActions(ActionConfigs.move(configs, idx, other - idx)) } })
    }
    own.forEach { card(it, own) }
}

/** What the reader's own fixed-content actions do, shown instead of the variants of the sent text. */
private val FIXED = mapOf(
    ACTION_KNOWN to "Скрывает подсказку к значению слова (или возвращает её). Работает, когда выделено одно слово.",
    ACTION_ANKI_WORD to "Карточка в AnkiDroid, колода «Entrelíneas»: лицевая сторона — слово и предложение с ним, " +
        "оборот — значение в этой книге, форма и перевод переводчика. Нужен установленный AnkiDroid; разрешение спросит один раз.",
    ACTION_ANKI_SENTENCE to "Карточка в AnkiDroid, колода «Entrelíneas»: лицевая сторона — выделенный испанский текст, " +
        "оборот — перевод переводчика и дословный перевод.",
    ACTION_SPEAK to "Читает вслух выбранный слой синтезатором речи телефона (Настройки → Синтез речи, обычно Google). " +
        "Язык берётся из книги: оригинал — языком книги, перевод, подсказки и заметки — языком перевода. " +
        "Без интернета работает, если голос скачан.",
)

@Composable
private fun ActionCard(title: String, cfg: ActionConfig, first: Boolean, last: Boolean,
                       onChange: (ActionConfig) -> Unit, onMove: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("action:${cfg.id}")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(cfg.enabled, { onChange(cfg.copy(enabled = it)) })
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 12.dp))
                IconButton({ onMove(-1) }, enabled = !first) { Icon(Icons.Filled.KeyboardArrowUp, "Выше") }
                IconButton({ onMove(1) }, enabled = !last) { Icon(Icons.Filled.KeyboardArrowDown, "Ниже") }
            }
            if (!cfg.enabled) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(cfg.pinned, { onChange(cfg.copy(pinned = true)) }, label = { Text("Видна сразу") })
                FilterChip(!cfg.pinned, { onChange(cfg.copy(pinned = false)) }, label = { Text("В «Ещё»") })
            }
            FIXED[cfg.id]?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (cfg.id == ACTION_OPEN_IN_APP) {
                Text("Показывает список приложений, которые принимают текст: сначала те, что умеют работать с выделенным " +
                    "(словари, переводчики, поиск), затем те, которым текст можно отправить. Недавние — сверху.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val preset = PRESETS.firstOrNull { it.first == cfg.template }?.second ?: "Свой вариант"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Передаётся: $preset", style = MaterialTheme.typography.bodyMedium)
                    Text("Например: " + fillTemplate(cfg.template, SAMPLE, Layer.ORIGINAL).replace("\n", " ⏎ "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                TextButton({ open = !open }, Modifier.testTag("what:${cfg.id}")) { Text(if (open) "Готово" else "Изменить") }
            }
            if (open) TemplateChooser(cfg.template) { onChange(cfg.copy(template = it)) }
        }
    }
}

/** Ready-made variants of the text an action receives, and an own variant built from named fields. */
@Composable
private fun TemplateChooser(template: String, onChange: (String) -> Unit) {
    val own = PRESETS.none { it.first == template }
    Column(Modifier.padding(top = 4.dp)) {
        PRESETS.forEach { (value, label) ->
            Row(Modifier.fillMaxWidth().selectable(template == value) { onChange(value) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(template == value, onClick = null)
                Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(label)
                    Text(fillTemplate(value, SAMPLE, Layer.ORIGINAL).replace("\n", " ⏎ "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().selectable(own) { if (!own) onChange(template + " ") }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(own, onClick = null)
            Text("Свой вариант", modifier = Modifier.padding(start = 8.dp))
        }
        if (own) {
            Text("Нажимайте поля, чтобы добавить их; текст между ними передаётся как есть.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FIELDS.forEach { (field, label) -> AssistChip({ onChange(template + field) }, label = { Text("+ $label") }) }
            }
            OutlinedTextField(template, onChange, modifier = Modifier.fillMaxWidth().testTag("template"), minLines = 2,
                shape = RoundedCornerShape(8.dp))
        }
    }
}

/** All settings, by tabs: the text and its hints, the look of the app, the selection actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullSettingsScreen(settings: Settings, shell: ShellSettings, systemDark: Boolean, colors: SchemeColors, book: Book?, chapter: Chapter?, layoutMs: Long,
                       actions: List<Pair<TextAction, ActionConfig>>, host: ActionHost,
                       onSettings: (Settings) -> Unit, onShell: (ShellSettings) -> Unit, onActions: (List<ActionConfig>) -> Unit,
                       onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize().testTag("fullSettings"), color = MaterialTheme.colorScheme.background) {
        Column {
            TopAppBar(title = { Text("Настройки") },
                navigationIcon = { IconButton(onBack, Modifier.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf("Текст" to "text", "Вид" to "view", "Действия" to "actions").forEachIndexed { i, (label, tag) ->
                    Tab(tab == i, { tab = i }, text = { Text(label) }, modifier = Modifier.testTag("tab:$tag"))
                }
            }
            Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> {
                        HintSettings(settings, book, colors, onSettings)
                        TranslationSettings(settings, chapter, colors, onSettings)
                        TypographySettings(settings, onSettings)
                        HorizontalDivider(Modifier.padding(top = 16.dp))
                        Text("Разбивка главы на страницы: $layoutMs мс", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                    1 -> ViewSettings(shell, systemDark, onShell)
                    else -> ActionsSettings(actions, host, onActions)
                }
                Spacer(Modifier.height(32.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}
