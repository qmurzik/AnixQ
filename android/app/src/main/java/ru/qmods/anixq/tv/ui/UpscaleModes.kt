package ru.qmods.anixq.tv.ui

/**
 * Every upscaler the TV offers. [chain] runs in order: "lanczos" / "rcas" are the built-in GPU filters,
 * other names are Anime4K shader files in assets/anime4k. Ids are stored in settings — keep them stable.
 * [heavy] modes are listed only on boxes with ≥ 3 GB RAM (they can still auto-disable on frame drops).
 */
data class UpscaleMode(val id: Int, val name: String, val hint: String, val chain: List<String>, val heavy: Boolean = false)

val UPSCALE_MODES = listOf(
    UpscaleMode(0, "Выключен", "как есть", emptyList()),
    UpscaleMode(3, "Anime4K S", "нейросеть ×2 — быстро и чисто, лучший выбор", listOf("Upscale_CNN_x2_S")),
    UpscaleMode(5, "Anime4K M", "нейросеть побольше — точнее", listOf("Upscale_CNN_x2_M"), heavy = true),
    UpscaleMode(6, "Anime4K L", "самая точная сеть, нужна мощная приставка", listOf("Upscale_CNN_x2_L"), heavy = true),
    UpscaleMode(9, "Шумоподавление S", "для сжатых стримов: убирает шум и блоки, ×2", listOf("Upscale_Denoise_CNN_x2_S")),
    UpscaleMode(10, "Шумоподавление M", "то же, точнее", listOf("Upscale_Denoise_CNN_x2_M"), heavy = true),
    UpscaleMode(4, "Восстановление", "убирает размытие и артефакты, потом ×2 — яркие линии", listOf("Restore_CNN_S", "Upscale_CNN_x2_S")),
    UpscaleMode(13, "Восстановление HQ", "то же на больших сетях", listOf("Restore_CNN_M", "Upscale_CNN_x2_M"), heavy = true),
    UpscaleMode(11, "Мягкое восстановление", "аккуратнее, без пересвета линий", listOf("Restore_CNN_Soft_S", "Upscale_CNN_x2_S")),
    UpscaleMode(12, "Мягкое восстановление HQ", "то же на больших сетях", listOf("Restore_CNN_Soft_M", "Upscale_CNN_x2_M"), heavy = true),
    UpscaleMode(7, "GAN S", "генеративная сеть: дорисовывает детали, очень резко", listOf("Upscale_GAN_x2_S"), heavy = true),
    UpscaleMode(8, "GAN M", "ещё резче и детальнее", listOf("Upscale_GAN_x2_M"), heavy = true),
    UpscaleMode(14, "Чёткие линии", "×2 + тоньше и темнее контуры", listOf("Upscale_CNN_x2_S", "Thin_HQ", "Darken_HQ")),
    UpscaleMode(15, "Без размытия", "убирает мыло (DoG), потом ×2", listOf("Deblur_DoG", "Upscale_CNN_x2_S")),
    UpscaleMode(2, "Ланцош + резкость", "без нейросети: Lanczos до экрана + RCAS (лёгкий)", listOf("lanczos", "rcas")),
    UpscaleMode(1, "Резкость", "только RCAS — самый лёгкий", listOf("rcas")),
)

fun upscaleMode(id: Int) = UPSCALE_MODES.firstOrNull { it.id == id } ?: UPSCALE_MODES[0]
