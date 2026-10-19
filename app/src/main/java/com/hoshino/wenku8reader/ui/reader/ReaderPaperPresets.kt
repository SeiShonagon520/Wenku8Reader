package com.hoshino.wenku8reader.ui.reader

import androidx.compose.ui.graphics.Color
import com.hoshino.wenku8reader.R
import com.hoshino.wenku8reader.data.local.ReaderSettings

data class PaperPreset(
    val id: String,
    val nameRes: Int,
    val isDark: Boolean,
    val backgroundColor: Long,
    val textColor: Long,
    val previewBg: Color,
    val previewText: Color,
)

object ReaderPaperPresets {
    val presets = listOf(
        PaperPreset(
            id = "wenku_yellow",
            nameRes = R.string.reader_paper_default,
            isDark = false,
            backgroundColor = 0xFFF5EEDCL,
            textColor = 0xFF2D2319L,
            previewBg = Color(0xFFF5EEDC),
            previewText = Color(0xFF2D2319),
        ),
        PaperPreset(
            id = "parchment",
            nameRes = R.string.reader_paper_parchment,
            isDark = false,
            backgroundColor = 0xFFEFE5CDL,
            textColor = 0xFF3A2E20L,
            previewBg = Color(0xFFEFE5CD),
            previewText = Color(0xFF3A2E20),
        ),
        PaperPreset(
            id = "green",
            nameRes = R.string.reader_paper_green,
            isDark = false,
            backgroundColor = 0xFFE2ECE0L,
            textColor = 0xFF1F321EL,
            previewBg = Color(0xFFE2ECE0),
            previewText = Color(0xFF1F321E),
        ),
        PaperPreset(
            id = "white",
            nameRes = R.string.reader_paper_white,
            isDark = false,
            backgroundColor = 0xFFFFFFFFL,
            textColor = 0xFF111111L,
            previewBg = Color(0xFFFFFFFF),
            previewText = Color(0xFF111111),
        ),
        PaperPreset(
            id = "dark_dim",
            nameRes = R.string.reader_paper_dark,
            isDark = true,
            backgroundColor = 0xFF1E1F22L,
            textColor = 0xFFC8CBD0L,
            previewBg = Color(0xFF1E1F22),
            previewText = Color(0xFFC8CBD0),
        ),
        PaperPreset(
            id = "true_black",
            nameRes = R.string.reader_paper_true_black,
            isDark = true,
            backgroundColor = 0xFF000000L,
            textColor = 0xFFD0D0D0L,
            previewBg = Color(0xFF000000),
            previewText = Color(0xFFD0D0D0),
        ),
    )

    fun applyPreset(preset: PaperPreset, settings: ReaderSettings) {
        if (preset.isDark) {
            settings.setReaderBackgroundDark(preset.backgroundColor)
            settings.setReaderTextColorDark(preset.textColor)
            settings.setDarkMode("dark")
        } else {
            settings.setReaderBackgroundLight(preset.backgroundColor)
            settings.setReaderTextColorLight(preset.textColor)
            settings.setDarkMode("light")
        }
    }
}
