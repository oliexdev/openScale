/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReconnectPolicyTest {

    private var now = 1_000_000L
    private val policy = ReconnectPolicy(
        baseDelayMs = 2_000,
        maxDelayMs = 30_000,
        stableAfterMs = 20_000,
        clock = { now },
    )

    /** Connects, stays up for [upMs] and drops; returns the delay chosen for the next attempt. */
    private fun dropAfter(upMs: Long): Long {
        policy.onConnected()
        now += upMs
        return policy.onDropped()
    }

    @Test
    fun `quick drops back off exponentially up to the cap`() {
        val delays = List(7) { dropAfter(3_000) }
        assertThat(delays).containsExactly(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L).inOrder()
    }

    @Test
    fun `a connection that stayed up starts the sequence over`() {
        repeat(3) { dropAfter(3_000) }
        assertThat(dropAfter(25_000)).isEqualTo(2_000L)
        assertThat(dropAfter(3_000)).isEqualTo(4_000L)
    }

    @Test
    fun `reset forgets earlier drops`() {
        repeat(3) { dropAfter(3_000) }
        policy.reset()
        assertThat(dropAfter(3_000)).isEqualTo(2_000L)
    }

    @Test
    fun `a drop with no recorded connection still backs off`() {
        assertThat(policy.onDropped()).isEqualTo(2_000L)
        assertThat(policy.onDropped()).isEqualTo(4_000L)
    }
}
