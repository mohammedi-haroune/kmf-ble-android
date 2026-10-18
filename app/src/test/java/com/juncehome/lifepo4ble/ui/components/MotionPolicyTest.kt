package com.juncehome.lifepo4ble.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPolicyTest {
    @Test
    fun zeroScaleMeansAnimationsAreOff() {
        assertFalse(MotionPolicy.animationsEnabled(0f))
        assertTrue(MotionPolicy.reduceMotion(0f))
    }

    @Test
    fun positiveScalesKeepAnimationsOn() {
        listOf(0.5f, 1f, 1.5f, 10f).forEach { scale ->
            assertTrue("scale=$scale", MotionPolicy.animationsEnabled(scale))
            assertFalse("scale=$scale", MotionPolicy.reduceMotion(scale))
        }
    }

    @Test
    fun unreadableOrInvalidScaleDefaultsToAnimationsOn() {
        assertTrue(MotionPolicy.animationsEnabled(null))
        assertTrue(MotionPolicy.animationsEnabled(Float.NaN))
    }

    @Test
    fun negativeScaleIsTreatedAsOff() {
        assertFalse(MotionPolicy.animationsEnabled(-1f))
    }
}
