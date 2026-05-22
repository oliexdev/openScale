/*
 * openScale
 * Copyright (C) 2026 Dany Mestas
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

import kotlin.math.abs

/**
 * Aggregates the two advertisement packets the S400 emits per weighing
 * session — Packet A (weight + heart rate + high-frequency impedance) and
 * Packet B (low-frequency impedance) — into a single finalized result.
 *
 * A timeout exit lets older firmware that only emits Packet A still produce a
 * measurement. A short dedup window prevents the same weighing from being
 * published twice when the scale re-broadcasts the final reading.
 */
class S400Aggregator(
    private val sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS,
    private val dedupWeightToleranceKg: Float = DEFAULT_DEDUP_WEIGHT_TOLERANCE_KG,
    private val dedupImpedanceToleranceOhm: Float = DEFAULT_DEDUP_IMPEDANCE_TOLERANCE_OHM,
) {
    companion object {
        const val DEFAULT_SESSION_TIMEOUT_MS = 10_000L
        const val DEFAULT_DEDUP_WEIGHT_TOLERANCE_KG = 0.05f
        const val DEFAULT_DEDUP_IMPEDANCE_TOLERANCE_OHM = 1.0f
    }

    /** Outcome of a single ingest call. */
    sealed class Outcome {
        /** Packet absorbed; keep listening for the next one. */
        data object Pending : Outcome()
        /** Both packets received (or timeout fired); ready to publish. */
        data class Finalized(
            val weightKg: Float,
            val impedanceHigh: Float,
            val impedanceLow: Float?,
            val heartRate: Int?,
            val timedOut: Boolean,
        ) : Outcome()
        /** Same finalization we just emitted; ignore to avoid double-publish. */
        data object Duplicate : Outcome()
    }

    private data class Session(
        var weightKg: Float? = null,
        var impedanceHigh: Float? = null,
        var impedanceLow: Float? = null,
        var heartRate: Int? = null,
        var firstSeenAt: Long = 0L,
    )
    private data class Recent(val weight: Float, val impedanceHigh: Float, val timeMs: Long)

    private val sessions = mutableMapOf<String, Session>()
    private val recent = mutableMapOf<String, Recent>()

    fun ingest(deviceMac: String, packet: S400Measurement, nowMs: Long): Outcome {
        val session = sessions.getOrPut(deviceMac) { Session(firstSeenAt = nowMs) }
        if (packet.weightKg > 0f) session.weightKg = packet.weightKg
        packet.impedanceHigh?.let { session.impedanceHigh = it }
        packet.impedanceLow?.let { session.impedanceLow = it }
        packet.heartRate?.let { session.heartRate = it }

        val weight = session.weightKg
        val impHigh = session.impedanceHigh
        val impLow = session.impedanceLow

        if (weight == null || impHigh == null) {
            return Outcome.Pending
        }

        val haveBoth = impLow != null
        val sessionAge = nowMs - session.firstSeenAt
        val timedOut = !haveBoth && sessionAge >= sessionTimeoutMs
        if (!haveBoth && !timedOut) {
            return Outcome.Pending
        }

        recent[deviceMac]?.let { previous ->
            val weightClose = abs(previous.weight - weight) < dedupWeightToleranceKg
            val impClose = abs(previous.impedanceHigh - impHigh) < dedupImpedanceToleranceOhm
            if (weightClose && impClose && (nowMs - previous.timeMs) < sessionTimeoutMs) {
                sessions.remove(deviceMac)
                return Outcome.Duplicate
            }
        }
        recent[deviceMac] = Recent(weight, impHigh, nowMs)
        sessions.remove(deviceMac)

        return Outcome.Finalized(
            weightKg = weight,
            impedanceHigh = impHigh,
            impedanceLow = impLow,
            heartRate = session.heartRate,
            timedOut = timedOut,
        )
    }

    fun reset() {
        sessions.clear()
        recent.clear()
    }
}
