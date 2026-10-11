/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PicoocAnchorLearnerTest {

    @Test
    fun `promotes a nearby weight cluster only on its fourth measurement`() {
        var state = PicoocAnchorLearner.State()
        val samples = listOf(81.5f to 492, 81.6f to 498, 82.7f to 496, 82.6f to 494)

        samples.forEachIndexed { index, (weight, rawR) ->
            val decision = PicoocAnchorLearner.decide(state, weight, rawR)
            assertThat(decision.beta).isEqualTo(if (index == 0) 0 else 34)
            val update = PicoocAnchorLearner.accept(state, decision, weight, rawR, 34)
            assertThat(update.progress).isEqualTo(index + 1)
            if (index < 3) assertThat(update.fixedBeta).isNull()
            else assertThat(update.fixedBeta).isEqualTo(34)
            state = update.state
        }
    }

    @Test
    fun `second record uses the vendor four-kilo and sixty-ohm exception`() {
        val first = PicoocAnchorLearner.accept(
            PicoocAnchorLearner.State(),
            PicoocAnchorLearner.Decision(0, null),
            80f,
            500,
            30,
        ).state

        assertThat(PicoocAnchorLearner.decide(first, 83.5f, 560).beta).isEqualTo(30)
        assertThat(PicoocAnchorLearner.decide(first, 83.5f, 561).beta).isEqualTo(0)
        assertThat(PicoocAnchorLearner.decide(first, 84.1f, 500).beta).isEqualTo(0)
    }

    @Test
    fun `serialized cluster state round trips and malformed state resets safely`() {
        var state = PicoocAnchorLearner.State()
        state = PicoocAnchorLearner.accept(
            state,
            PicoocAnchorLearner.decide(state, 81.5f, 492),
            81.5f,
            492,
            34,
        ).state
        state = PicoocAnchorLearner.accept(
            state,
            PicoocAnchorLearner.decide(state, 81.6f, 498),
            81.6f,
            498,
            34,
        ).state

        assertThat(PicoocAnchorLearner.decode(PicoocAnchorLearner.encode(state))).isEqualTo(state)
        assertThat(PicoocAnchorLearner.decode("broken")).isEqualTo(PicoocAnchorLearner.State())
    }
}
