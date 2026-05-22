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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class S400AggregatorTest {

    private val mac = "8C:D0:B2:F6:BE:EF"

    private fun packetA(weight: Float = 69.9f, hi: Float = 543.2f, hr: Int? = 92) =
        S400Measurement(weight, impedanceHigh = hi, impedanceLow = null, heartRate = hr)

    private fun packetB(lo: Float = 497.6f) =
        S400Measurement(0f, impedanceHigh = null, impedanceLow = lo, heartRate = null)

    @Test
    fun pendingUntilBothPacketsArrive_thenFinalizes() {
        val agg = S400Aggregator()

        val first = agg.ingest(mac, packetA(), nowMs = 1_000)
        assertThat(first).isInstanceOf(S400Aggregator.Outcome.Pending::class.java)

        val second = agg.ingest(mac, packetB(), nowMs = 1_200) as S400Aggregator.Outcome.Finalized
        assertThat(second.weightKg).isWithin(0.01f).of(69.9f)
        assertThat(second.impedanceHigh).isWithin(0.01f).of(543.2f)
        assertThat(second.impedanceLow!!).isWithin(0.01f).of(497.6f)
        assertThat(second.heartRate).isEqualTo(92)
        assertThat(second.timedOut).isFalse()
    }

    @Test
    fun packetOrderIndependent_packetBFirst() {
        val agg = S400Aggregator()

        val first = agg.ingest(mac, packetB(), nowMs = 1_000)
        assertThat(first).isInstanceOf(S400Aggregator.Outcome.Pending::class.java)

        val second = agg.ingest(mac, packetA(), nowMs = 1_100) as S400Aggregator.Outcome.Finalized
        assertThat(second.impedanceLow!!).isWithin(0.01f).of(497.6f)
        assertThat(second.impedanceHigh).isWithin(0.01f).of(543.2f)
        assertThat(second.timedOut).isFalse()
    }

    @Test
    fun finalizesAfterTimeout_whenOnlyPacketAArrives() {
        val agg = S400Aggregator()

        val first = agg.ingest(mac, packetA(), nowMs = 1_000)
        assertThat(first).isInstanceOf(S400Aggregator.Outcome.Pending::class.java)

        // Same packet replayed long after first-seen → timeout exit.
        val late = agg.ingest(
            mac,
            packetA(),
            nowMs = 1_000 + S400Aggregator.DEFAULT_SESSION_TIMEOUT_MS
        ) as S400Aggregator.Outcome.Finalized

        assertThat(late.impedanceHigh).isWithin(0.01f).of(543.2f)
        assertThat(late.impedanceLow).isNull()
        assertThat(late.timedOut).isTrue()
    }

    @Test
    fun dedupsRepeatedFinalizationWithinWindow() {
        val agg = S400Aggregator()

        agg.ingest(mac, packetA(), nowMs = 1_000)
        val finalized = agg.ingest(mac, packetB(), nowMs = 1_100)
        assertThat(finalized).isInstanceOf(S400Aggregator.Outcome.Finalized::class.java)

        // Same weighing rebroadcast a second later — should be ignored.
        agg.ingest(mac, packetA(), nowMs = 2_000)
        val duplicate = agg.ingest(mac, packetB(), nowMs = 2_100)
        assertThat(duplicate).isInstanceOf(S400Aggregator.Outcome.Duplicate::class.java)
    }

    @Test
    fun allowsSecondMeasurementAfterDedupWindow() {
        val agg = S400Aggregator()

        agg.ingest(mac, packetA(weight = 69.9f, hi = 543.2f), nowMs = 1_000)
        agg.ingest(mac, packetB(lo = 497.6f), nowMs = 1_100)

        // Different weighing well past the dedup window.
        val laterStart = 1_100L + S400Aggregator.DEFAULT_SESSION_TIMEOUT_MS + 1
        agg.ingest(mac, packetA(weight = 70.1f, hi = 540.0f), nowMs = laterStart)
        val laterFinalized = agg.ingest(mac, packetB(lo = 495.0f), nowMs = laterStart + 100)
            as S400Aggregator.Outcome.Finalized

        assertThat(laterFinalized.weightKg).isWithin(0.01f).of(70.1f)
    }

    @Test
    fun sessionsIsolatedByMac() {
        val agg = S400Aggregator()
        val otherMac = "AA:BB:CC:DD:EE:FF"

        agg.ingest(mac, packetA(), nowMs = 1_000)
        // PacketB from a different device must not finalize the first MAC's session.
        val crossed = agg.ingest(otherMac, packetB(), nowMs = 1_100)
        assertThat(crossed).isInstanceOf(S400Aggregator.Outcome.Pending::class.java)

        val done = agg.ingest(mac, packetB(), nowMs = 1_200)
        assertThat(done).isInstanceOf(S400Aggregator.Outcome.Finalized::class.java)
    }
}
