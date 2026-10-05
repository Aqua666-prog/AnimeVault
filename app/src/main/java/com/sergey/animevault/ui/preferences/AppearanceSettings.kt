package com.sergey.animevault.ui.preferences

enum class VaultThemeMode(val title: String, val description: String) {
    VAULT("Vault Lemon Purple", "Тёмный фон, фиолетовая навигация и лимонные действия"),
    MIDNIGHT("Midnight", "Более холодная синяя палитра и глубокие поверхности"),
    OLED("AMOLED Lemon Purple", "Чёрный фон и более тёмные карточки"),
    DYNAMIC("Dynamic", "Цвета Android из системной динамической палитры"),
}

enum class VaultAccentMode(val title: String) {
    VIOLET("Фиолетовый"),
    BLUE("Синий"),
    RED("Красный"),
    SYSTEM("Системный"),
}

enum class VaultMotionMode(val title: String, val durationScale: Float) {
    FULL("Полные", 1f),
    REDUCED("Уменьшенные", 0.45f),
    MINIMAL("Минимальные", 0.12f),
    OFF("Отключены", 0f),
}

data class AppearanceSettings(
    val theme: VaultThemeMode = VaultThemeMode.VAULT,
    val accent: VaultAccentMode = VaultAccentMode.VIOLET,
    val blurEnabled: Boolean = false,
    val motion: VaultMotionMode = VaultMotionMode.FULL,
    val dynamicArtwork: Boolean = false,
    val reducedMotion: Boolean = false,
) {
    val effectiveMotion: VaultMotionMode
        get() = when {
            motion == VaultMotionMode.OFF -> VaultMotionMode.OFF
            motion == VaultMotionMode.MINIMAL -> VaultMotionMode.REDUCED
            reducedMotion -> VaultMotionMode.REDUCED
            else -> motion
        }
}

enum class DefaultVideoScale(val title: String) {
    FIT("Вписать"),
    FILL("Заполнить"),
    ZOOM("Увеличить"),
}

enum class DefaultNextEpisode(val title: String) {
    OFF("Не переходить"),
    COUNTDOWN("С отсчётом"),
    IMMEDIATE("Сразу"),
}

enum class DefaultEqualizer(val title: String) {
    OFF("Выкл."),
    FLAT("Ровный"),
    DIALOGUE("Речь"),
    BASS("Бас"),
    BRIGHT("Ясность"),
    NIGHT("Ночной"),
}

data class PlaybackDefaults(
    val speed: Float = 1f,
    val videoScale: DefaultVideoScale = DefaultVideoScale.FIT,
    val nextEpisode: DefaultNextEpisode = DefaultNextEpisode.COUNTDOWN,
    val equalizer: DefaultEqualizer = DefaultEqualizer.OFF,
    val subtitlesEnabled: Boolean = true,
)
