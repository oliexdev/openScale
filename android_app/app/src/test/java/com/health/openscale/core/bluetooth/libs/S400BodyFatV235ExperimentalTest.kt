package com.health.openscale.core.bluetooth.libs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class S400BodyFatV235ExperimentalTest {
    @Test
    fun recordedWithinDomainResponses() {
        val input = checkNotNull(javaClass.getResourceAsStream("/s400/body-fat-v235.tsv"))
        var checked = 0
        input.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val t = line.split('\t')
                check(t.size == 10)
                val h = t[1].toInt()
                val w = t[2].toInt()
                val fat = S400BodyFatV235Experimental.estimatePct(
                    h, w, t[3].toInt(), t[4] == "1", t[5].toInt(), t[6].toInt(),
                )
                assertNotNull(t[0], fat)
                assertEquals(t[0], t[7].toFloat(), fat!!, 0.0001f)
                val derived = S400DerivedMetrics.compute(h / 10f, w / 10f, t[4] == "1",
                    S400DerivedMetrics.Components(bodyFatPct = fat))
                assertEquals("${t[0]} ffm", t[8].toFloat(), derived.ffmKg, 0.0001f)
                assertEquals("${t[0]} bmr", t[9].toFloat(), derived.bmrKcal, 0f)
                checked++
            }
        }
        assertEquals(275, checked)
    }

    @Test
    fun originalMaleReference() {
        val fat = S400BodyFatV235Experimental.estimatePct(1850, 870, 36, true, 5436, 4951)
        assertEquals(23.7f, fat!!, 0.0001f)
    }

    @Test
    fun rejectsUnsupportedAge() {
        assertNull(S400BodyFatV235Experimental.estimatePct(1850, 870, 18, true, 5436, 4951))
        assertNull(S400BodyFatV235Experimental.estimatePct(1850, 870, 69, true, 5436, 4951))
    }

    @Test
    fun rejectsUnsupportedAnthropometry() {
        assertNull(S400BodyFatV235Experimental.estimatePct(2200, 870, 36, true, 5436, 4951))
        assertNull(S400BodyFatV235Experimental.estimatePct(1850, 1500, 36, true, 5436, 4951))
    }

    @Test
    fun rejectsUnsupportedImpedance() {
        assertNull(S400BodyFatV235Experimental.estimatePct(1850, 870, 36, true, 543, 495))
        assertNull(S400BodyFatV235Experimental.estimatePct(1850, 870, 36, true, 5436, 0))
        assertNull(S400BodyFatV235Experimental.estimatePct(1850, 870, 36, true, 5436, 3000))
    }
}
