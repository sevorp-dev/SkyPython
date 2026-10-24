package dev.skyloft.skypython.settings

/** Editor preferences shared by the editor (reads) and the Settings screen (writes). */
data class EditorSettings(
    val fontSizeSp: Int = 15,
    val wordWrap: Boolean = true,
    val lineNumbers: Boolean = true,
    val keepScreenOn: Boolean = false,
)
