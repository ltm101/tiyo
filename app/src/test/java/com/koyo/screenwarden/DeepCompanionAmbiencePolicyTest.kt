package com.koyo.screenwarden

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepCompanionAmbiencePolicyTest {
    @Test
    fun nightIsDarkerThanMidday() {
        assertTrue(
            DeepCompanionAmbiencePolicy.forHour(1f).tintAlpha >
                DeepCompanionAmbiencePolicy.forHour(12f).tintAlpha
        )
    }

    @Test
    fun rainAndSnowUseDifferentWindowEffects() {
        val rain = DeepCompanionAmbiencePolicy.forWeather("小雨 24°C")
        val snow = DeepCompanionAmbiencePolicy.forWeather("Light snow -2°C")
        assertTrue(rain.rain)
        assertFalse(rain.snow)
        assertTrue(snow.snow)
        assertFalse(snow.rain)
    }
}
