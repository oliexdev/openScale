/*
 * openScale
 * Copyright (C) 2026 nadelacruz
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

/**
 * Loads decoded HN-300T2 traces from test resources and returns the raw payloads.
 * Handles are the capture's own and are only used here to route lines.
 */
object Hn300t2Fixture {

    data class Trace(
        val handshakeWrite: ByteArray,
        val handshakeNotify: ByteArray,
        val wldWrites: List<ByteArray>,
        val wldNotifies: List<ByteArray>
    )

    private const val HANDSHAKE_HANDLE = "0x001b"
    private const val WLD_WRITE_HANDLE = "0x001e"
    private const val WLD_NOTIFY_HANDLE = "0x0020"

    private val LINE = Regex("""\b(WriteCmd|Notify) h=(0x[0-9a-fA-F]{4}) len=(\d+) val=([0-9a-fA-F]*)""")

    fun load(name: String): Trace {
        val stream = javaClass.classLoader.getResourceAsStream("omron_hn300t2/$name.txt")
            ?: error("Missing trace resource: omron_hn300t2/$name.txt")
        var hsWrite: ByteArray? = null
        var hsNotify: ByteArray? = null
        val writes = mutableListOf<ByteArray>()
        val notifies = mutableListOf<ByteArray>()

        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) continue
                val m = LINE.find(line) ?: continue
                val (kind, handle, len, value) = m.destructured
                val bytes = hex(value)
                check(bytes.size == len.toInt()) { "len=$len but ${bytes.size} bytes in: $line" }
                val isWrite = kind == "WriteCmd"
                when {
                    handle == HANDSHAKE_HANDLE && isWrite -> hsWrite = bytes
                    handle == HANDSHAKE_HANDLE -> hsNotify = bytes
                    handle == WLD_WRITE_HANDLE && isWrite -> writes += bytes
                    handle == WLD_NOTIFY_HANDLE && !isWrite -> notifies += bytes
                }
            }
        }
        return Trace(
            handshakeWrite = hsWrite ?: error("No handshake write in $name"),
            handshakeNotify = hsNotify ?: error("No handshake notify in $name"),
            wldWrites = writes,
            wldNotifies = notifies
        )
    }

    private fun hex(s: String): ByteArray = s.filterNot { it.isWhitespace() }
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
