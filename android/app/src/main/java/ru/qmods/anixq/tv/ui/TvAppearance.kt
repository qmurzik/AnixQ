package ru.qmods.anixq.tv.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device preference: never exported to the library or applied to the phone. */
object TvAppearance {
    enum class Mode { CLASSIC, PANORAMA }
    enum class Motion { AUTO, FULL, OFF }
    private var preferences: SharedPreferences? = null
    private val _mode = MutableStateFlow(Mode.CLASSIC)
    val mode = _mode.asStateFlow()
    private val _motion = MutableStateFlow(Motion.AUTO)
    val motion = _motion.asStateFlow()
    fun init(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("tv.appearance", Context.MODE_PRIVATE)
        _mode.value = runCatching { Mode.valueOf(preferences?.getString("interface", null).orEmpty()) }.getOrDefault(Mode.CLASSIC)
        _motion.value = runCatching { Motion.valueOf(preferences?.getString("motionPolicy", null).orEmpty()) }.getOrDefault(Motion.AUTO)
    }
    fun select(mode: Mode) {
        preferences?.edit()?.putString("interface", mode.name)?.apply()
        Focus.barActive = false
        Focus.reset("settings")
        Focus.reset("panorama:settings")
        _mode.value = mode
    }
    fun chooseMotion(value: Motion) {
        preferences?.edit()?.putString("motionPolicy", value.name)?.apply()
        _motion.value = value
    }
    fun cycleMotion() = chooseMotion(when (_motion.value) {
        Motion.AUTO -> Motion.FULL
        Motion.FULL -> Motion.OFF
        Motion.OFF -> Motion.AUTO
    })
}
