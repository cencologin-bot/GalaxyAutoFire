package com.galaxy.autofire

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.galaxyStore: DataStore<Preferences> by preferencesDataStore(name = "galaxy_settings_v2")

/** Cuatro intervalos en ms. Solo se guardan; no disparan ninguna acción. */
data class Timings(val values: List<Int> = DEFAULTS) {
    fun sanitized() = Timings(List(COUNT) { values.getOrElse(it) { DEFAULTS[it] }.coerceIn(0, MAX_MS) })
    fun withValue(index: Int, value: Int) =
        Timings(values.toMutableList().also { it[index] = value }).sanitized()

    companion object {
        const val COUNT = 4
        const val MAX_MS = 1000
        val DEFAULTS = listOf(30, 60, 90, 120)
    }
}

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.galaxyStore

    private object K {
        val LEFT = floatPreferencesKey("left")
        val TOP = floatPreferencesKey("top")
        val RIGHT = floatPreferencesKey("right")
        val BOTTOM = floatPreferencesKey("bottom")
        val SAT = floatPreferencesKey("min_saturation")
        val VAL = floatPreferencesKey("min_value")
        val COVERAGE = floatPreferencesKey("min_coverage")
        val DELAYS = List(Timings.COUNT) { intPreferencesKey("delay${it + 1}") }
    }

    private val data: Flow<Preferences> = store.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }

    val config: Flow<DetectionConfig> = data.map { p ->
        val d = DetectionConfig()
        DetectionConfig(
            left = p[K.LEFT] ?: d.left,
            top = p[K.TOP] ?: d.top,
            right = p[K.RIGHT] ?: d.right,
            bottom = p[K.BOTTOM] ?: d.bottom,
            minSaturation = p[K.SAT] ?: d.minSaturation,
            minValue = p[K.VAL] ?: d.minValue,
            minCoverage = p[K.COVERAGE] ?: d.minCoverage,
        ).sanitized()
    }.distinctUntilChanged()

    val timings: Flow<Timings> = data.map { p ->
        Timings(List(Timings.COUNT) { p[K.DELAYS[it]] ?: Timings.DEFAULTS[it] }).sanitized()
    }.distinctUntilChanged()

    suspend fun saveConfig(config: DetectionConfig) {
        val c = config.sanitized()
        store.edit {
            it[K.LEFT] = c.left
            it[K.TOP] = c.top
            it[K.RIGHT] = c.right
            it[K.BOTTOM] = c.bottom
            it[K.SAT] = c.minSaturation
            it[K.VAL] = c.minValue
            it[K.COVERAGE] = c.minCoverage
        }
    }

    suspend fun saveTimings(timings: Timings) {
        val t = timings.sanitized()
        store.edit { prefs -> t.values.forEachIndexed { i, v -> prefs[K.DELAYS[i]] = v } }
    }
}
