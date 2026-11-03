package com.interstellar.proxy.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPatrolGateTest {

    @Test
    fun `healthy sample does not trigger a round`() {
        assertEquals(
            SmartPatrolGate.Decision.Healthy,
            SmartPatrolGate.decide(
                hasCurrent = true,
                currentDead = false,
                delayMs = 480,
                goodMs = 500,
                previousBadPatrols = 0,
            ),
        )
    }

    @Test
    fun `first slow sample only asks for confirmation`() {
        assertEquals(
            SmartPatrolGate.Decision.Confirm,
            SmartPatrolGate.decide(
                hasCurrent = true,
                currentDead = false,
                delayMs = 650,
                goodMs = 500,
                previousBadPatrols = 0,
            ),
        )
    }

    @Test
    fun `second consecutive slow sample reselects`() {
        assertEquals(
            SmartPatrolGate.Decision.Reselect,
            SmartPatrolGate.decide(
                hasCurrent = true,
                currentDead = false,
                delayMs = 650,
                goodMs = 500,
                previousBadPatrols = 1,
            ),
        )
    }

    @Test
    fun `dead or absent current reselects immediately`() {
        assertEquals(
            SmartPatrolGate.Decision.Reselect,
            SmartPatrolGate.decide(
                hasCurrent = true,
                currentDead = true,
                delayMs = 0,
                goodMs = 500,
                previousBadPatrols = 0,
            ),
        )
        assertEquals(
            SmartPatrolGate.Decision.Reselect,
            SmartPatrolGate.decide(
                hasCurrent = false,
                currentDead = true,
                delayMs = 0,
                goodMs = 500,
                previousBadPatrols = 0,
            ),
        )
    }
}
