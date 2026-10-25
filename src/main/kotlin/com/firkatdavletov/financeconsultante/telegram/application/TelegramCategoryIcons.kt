package com.firkatdavletov.financeconsultante.telegram.application

/** Legacy Telegram presentation choices; category persistence accepts any icon code. */
internal object TelegramCategoryIcons {
    val entries =
        listOf(
            "FOOD" to "🍔",
            "TRANSPORT" to "🚕",
            "HOME" to "🏠",
            "CLOTHES" to "👕",
            "HEALTH" to "💊",
            "ENTERTAINMENT" to "🎬",
            "SPORT" to "⚽",
            "EDUCATION" to "🎓",
            "TRAVEL" to "✈️",
            "PETS" to "🐾",
            "INCOME" to "💰",
            "OTHER" to "📦",
        )

    fun emoji(code: String): String = entries.firstOrNull { it.first == code }?.second ?: "📦"
}
