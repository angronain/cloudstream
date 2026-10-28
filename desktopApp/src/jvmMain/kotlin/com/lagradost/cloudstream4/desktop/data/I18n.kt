package com.lagradost.cloudstream4.desktop.data

import java.util.Locale

/** The app's languages, "" follows the system */
object I18n {
    val languages = listOf("" to "System", "en" to "English", "fr" to "Français", "zh" to "中文（简体）")

    val current: String get() = AppData.settings.value.language.ifEmpty { Locale.getDefault().language }
}

/**
 * [text] in the app's language, English if it has no translation. {0}, {1}... are replaced by [args].
 * The French and Chinese texts come from the Android app where it has the same text.
 */
fun tr(text: String, vararg args: Any?): String {
    val translated = Translations.byLanguage[I18n.current]?.get(text) ?: text
    return args.foldIndexed(translated) { i, result, arg -> result.replace("{$i}", arg.toString()) }
}
