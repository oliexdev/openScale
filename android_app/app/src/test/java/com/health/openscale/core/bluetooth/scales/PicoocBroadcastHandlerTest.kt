/*
 * openScale
 * Copyright (C) 2026 openScale contributors
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
package com.health.openscale.core.bluetooth.scales

import android.util.SparseArray
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.libs.PicoocWhiteBodyComposition
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parser regression tests using advertisements captured from a physical PICOOC Mini Lite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PicoocBroadcastHandlerTest {

    private val packetA = hex("02010609095049434F4F432D4C11FFD049004B4F1E391F065E13388704009C")
    private val packetB = hex("02010609095049434F4F432D4C11FFD049004B4F1E391F066013748706005C")
    private val packetC = hex("02010609095049434F4F432D4C11FFD049004B4F1E391F0676136087200040")

    private fun hex(value: String): ByteArray = value.removePrefix("0x").chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

    /** Extract the type-0xFF AD value and split it the same way Android's ScanRecord does. */
    private fun manufacturerEntry(advertisement: ByteArray): Pair<Int, ByteArray> {
        var offset = 0
        while (offset < advertisement.size) {
            val length = advertisement[offset].toInt() and 0xFF
            require(length > 0 && offset + length < advertisement.size)
            val type = advertisement[offset + 1].toInt() and 0xFF
            if (type == 0xFF) {
                val id = (advertisement[offset + 2].toInt() and 0xFF) or
                    ((advertisement[offset + 3].toInt() and 0xFF) shl 8)
                return id to advertisement.copyOfRange(offset + 4, offset + length + 1)
            }
            offset += length + 1
        }
        error("manufacturer data not found")
    }

    private fun parse(packet: ByteArray): PicoocMiniLiteAdv.Frame? {
        val (id, payload) = manufacturerEntry(packet)
        return PicoocMiniLiteAdv.parse(id, payload)
    }

    private fun device(name: String, packet: ByteArray? = packetC): ScannedDeviceInfo {
        val manufacturerData = packet?.let {
            val (id, payload) = manufacturerEntry(it)
            SparseArray<ByteArray>().apply { put(id, payload) }
        }
        return ScannedDeviceInfo(
            name = name,
            address = "D0:49:00:4B:4F:1E",
            rssi = -50,
            serviceUuids = emptyList(),
            manufacturerData = manufacturerData,
        )
    }

    @Test
    fun `parses all captured measurements`() {
        val a = parse(packetA)!!
        val b = parse(packetB)!!
        val c = parse(packetC)!!

        assertThat(a.weightKg).isWithin(1e-3f).of(81.5f)
        assertThat(a.impedanceOhm).isEqualTo(492.0f)
        assertThat(b.weightKg).isWithin(1e-3f).of(81.6f)
        assertThat(b.impedanceOhm).isEqualTo(498.0f)
        assertThat(c.weightKg).isWithin(1e-3f).of(82.7f)
        assertThat(c.impedanceOhm).isEqualTo(496.0f)
    }

    @Test
    fun `recognises the vendor apps completed-state marker`() {
        val frame = parse(packetC)!!

        assertThat(frame.state).isEqualTo(0x39)
        assertThat(frame.protocol).isEqualTo(0x1F)
        assertThat(frame.complete).isTrue()
        assertThat(frame.displayUnit).isEqualTo(0)
    }

    @Test
    fun `a checksummed non-final state remains in progress`() {
        val changed = packetC.copyOf()
        changed[21] = 0x38
        changed[30] = checksum(changed.copyOfRange(15, 30)).toByte()

        val frame = parse(changed)!!
        assertThat(frame.complete).isFalse()
    }

    @Test
    fun `rejects an incorrect checksum`() {
        val corrupted = packetC.copyOf().also { it[30] = (it[30] + 1).toByte() }
        assertThat(parse(corrupted)).isNull()
    }

    @Test
    fun `rejects a truncated manufacturer record`() {
        val (id, payload) = manufacturerEntry(packetC)
        assertThat(PicoocMiniLiteAdv.parse(id, payload.copyOf(payload.size - 1))).isNull()
    }

    @Test
    fun `rejects corrupted weight when the checksum is stale`() {
        val corrupted = packetC.copyOf().also { it[24] = 0x77 }
        assertThat(parse(corrupted)).isNull()
    }

    @Test
    fun `does not hardcode the captured manufacturer id or MAC`() {
        val changed = packetC.copyOf()
        changed[15] = 0x12
        changed[16] = 0x34
        changed[17] = 0x56
        changed[18] = 0x78
        changed[19] = 0x11
        changed[20] = 0x22
        changed[30] = checksum(changed.copyOfRange(15, 30)).toByte()

        assertThat(parse(changed)).isNotNull()
    }

    @Test
    fun `claims only the exact Mini Lite advertised name`() {
        val handler = PicoocBroadcastHandler()

        assertThat(handler.supportFor(device("PICOOC-L"))).isNotNull()
        assertThat(handler.supportFor(device("picooc-l"))).isNotNull()
        assertThat(handler.supportFor(device("PICOOC-L1"))).isNull()
        assertThat(handler.supportFor(device("Scale Up"))).isNull()
        assertThat(handler.supportFor(device("", packetC))).isNull()
    }

    @Test
    fun `saved device snapshot remains identifiable without manufacturer data`() {
        assertThat(PicoocBroadcastHandler().supportFor(device("PICOOC-L", packet = null))).isNotNull()
    }

    @Test
    fun `maps skeletal muscle to openScale muscle and keeps PICOOC total muscle separate`() {
        val result = PicoocWhiteBodyComposition.calculate(
            PicoocWhiteBodyComposition.Input(
                male = true,
                heightCm = 176f,
                age = 39,
                weightKg = 82.7f,
                correctedImpedanceOhm = 500,
                anchorWeightKg = 81,
                anchorBeta = 34,
            )
        )!!
        val measurement = ScaleMeasurement()

        PicoocBroadcastHandler().applyBodyCompositionMeasurements(measurement, result)

        assertThat(measurement[MeasurementType.MUSCLE]?.value).isWithin(0.05f).of(40.4f)
        assertThat(measurement[PicoocBroadcastHandler.TOTAL_MUSCLE]?.value).isWithin(0.05f).of(69.6f)
    }

    private fun checksum(bytes: ByteArray): Int =
        (bytes.sumOf { it.toInt() and 0xFF } and 0xFF).inv() and 0xFF
}
