package local.bilingual.reader.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * An app that can take the selected text. PROCESS: it handles selected text itself (ACTION_PROCESS_TEXT, the apps of
 * Android's own selection menu: dictionaries, translators, search, maps). SEND: it accepts shared text (ACTION_SEND,
 * the share sheet: messengers, notes, mail, other readers). Far more apps implement SEND; both are offered.
 */
data class AppTarget(val packageName: String, val activity: String, val label: String, val process: Boolean) {
    val id: String get() = "$packageName/$activity"
    val component: ComponentName get() = ComponentName(packageName, activity)
}

object AppTargets {
    /** Apps that take text, PROCESS ones first; an app found both ways is kept once, as PROCESS. */
    fun list(context: Context): List<AppTarget> {
        val pm = context.packageManager
        fun query(intent: Intent, process: Boolean) = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppTarget(it.activityInfo.packageName, it.activityInfo.name, it.loadLabel(pm).toString(), process) }
        val process = query(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), true)
        val send = query(Intent(Intent.ACTION_SEND).setType("text/plain"), false)
            .filter { s -> process.none { it.packageName == s.packageName } }
        return order(process + send, emptyList())
    }

    /** Recently used first (most recent first), then by kind and name. */
    fun order(targets: List<AppTarget>, recent: List<String>): List<AppTarget> =
        targets.sortedWith(compareBy<AppTarget>({ recent.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } },
            { !it.process }, { it.label.lowercase() }))

    fun send(context: Context, target: AppTarget, text: String) {
        val intent = if (target.process) Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        else Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
        runCatching { context.startActivity(intent.setType("text/plain").setComponent(target.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast(context, "Не удалось открыть «${target.label}»") }
    }
}
