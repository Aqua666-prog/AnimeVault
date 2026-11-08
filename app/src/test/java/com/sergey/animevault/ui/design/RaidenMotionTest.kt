package com.sergey.animevault.ui.design

import org.junit.Assert.*
import org.junit.Test

class RaidenMotionTest {
    @Test fun revealStartsConcealedAndEndsTransparent() {
        assertEquals(0f, RaidenMotion.arrival(0f), .0001f)
        assertEquals(0f, RaidenMotion.title(0f), .0001f)
        assertEquals(1f, RaidenMotion.exit(0f), .0001f)
        assertEquals(1f, RaidenMotion.title(.7f), .0001f)
        assertEquals(0f, RaidenMotion.exit(1f), .0001f)
        assertTrue(RaidenMotion.splashMillis <= 1800)
    }
    @Test fun phasesAreBoundedAndMonotonicIncludingOutsideTimeline() {
        val phases = listOf<(Float) -> Float>(RaidenMotion::arrival, RaidenMotion::title, RaidenMotion::wave)
        phases.forEach { phase ->
            var last = 0f
            for (index in -10..110) {
                val current = phase(index / 100f)
                assertTrue(current in 0f..1f)
                assertTrue(current >= last)
                last = current
            }
        }
        assertEquals(0f, RaidenMotion.exit(2f), .0001f)
    }
}
