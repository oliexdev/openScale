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

/**
 * Decides how long to wait before reconnecting to a scale whose link dropped on its own.
 *
 * The delay doubles from [baseDelayMs] up to [maxDelayMs] for as long as connections keep dropping
 * quickly, so a scale that accepts a connection and then hangs up straight away is not hammered.
 * A connection that stayed up for at least [stableAfterMs] counts as healthy and starts the
 * sequence over.
 */
internal class ReconnectPolicy(
    private val baseDelayMs: Long = 2_000,
    private val maxDelayMs: Long = 30_000,
    private val stableAfterMs: Long = 20_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var attempts = 0
    private var connectedAtMs: Long? = null

    /** Records that a connection has just been established. */
    fun onConnected() {
        connectedAtMs = clock()
    }

    /** Records an unexpected drop and returns the delay in milliseconds before the next attempt. */
    fun onDropped(): Long {
        val connectedAt = connectedAtMs
        connectedAtMs = null
        if (connectedAt != null && clock() - connectedAt >= stableAfterMs) attempts = 0

        val shift = attempts.coerceAtMost(MAX_SHIFT)
        attempts++
        return (baseDelayMs shl shift).coerceAtMost(maxDelayMs)
    }

    /** Forgets all history, e.g. when the user starts a connection by hand. */
    fun reset() {
        attempts = 0
        connectedAtMs = null
    }

    private companion object {
        const val MAX_SHIFT = 16
    }
}
