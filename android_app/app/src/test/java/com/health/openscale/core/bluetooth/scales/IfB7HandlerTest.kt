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

import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.advertisement
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.StandardImpedanceLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.UUID

/**
 * Tests for [IfB7Handler]: the advertisement fingerprint, and which frames of a weigh-in become a
 * published measurement.
 *
 * The sequences below follow a real capture of one weigh-in, with the readings invented: the payload
 * grammar is what the device sends (header `02 03 11`, the advertiser's own MAC, marker `01`,
 * big-endian weight × 100, big-endian impedance in ohms, flags, a sequence counter, and the checksum
 * `sum(bytes 9..15) and 0xFF`), and the jitter is real — the weight field of this scale moves by
 * ~1 kg between packets, so the sequences reproduce that instead of an idealised stable reading.
 * `C0:FF:EE:12:34:56` is [ScaleCatalog]'s fixture address, echoed in the frame's MAC field.
 *
 * Robolectric is needed for the [ScanResult]s: the manufacturer record is parsed by Android's own
 * `ScanRecord.parseFromBytes`, so the handler receives the record a real advertisement gives it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IfB7HandlerTest {

    /** Senssun's company id; the measurement record lives under it. */
    private val companyId = 0x0100

    /** The scale advertises from this synthetic address, and the frame echoes it. */
    private val address = "C0:FF:EE:12:34:56"

    /** Every frame starts with the constant header, the device's own MAC and the marker `01`. */
    private val head = "020311 C0FFEE123456 01"

    /** One manufacturer record: the head, plus weight, impedance, flags, sequence and checksum. */
    private fun record(dataAndCheck: String) = hex("$head $dataAndCheck")

    private val idle = record("0000 0000 01 01 03")

    /** Stepping on: the load cell ramps, the reading swings, and the BIA pass starts ~15 s later. */
    private fun weighIn(vararg frames: String) = frames.map { record(it) }

    private val settle = weighIn(
        "2BD4 0000 01 0F 10",   // 112.20 kg, first frames under load
        "2B8E 0000 01 10 CB",   // 111.50 kg
        "2B84 0000 01 11 C2",   // 111.40 kg
    )
    private val biaPhase = weighIn(
        "2B98 0000 A1 12 77",   // 111.60 kg, BIA phase begins, impedance not reported yet
        "2B98 0194 A1 13 0D",   // 111.60 kg, 404 Ω
        "2B98 0194 A1 1A 14",   // same pair, next sequence number: the BIA answer is one value
    )
    private val weightOnlyPhase = weighIn(
        "2BAC 0000 01 20 F9",   // 111.80 kg, jitter continues with no BIA phase
        "2BE8 0000 01 21 36",   // 112.40 kg
        "2BCA 0000 01 22 19",   // 112.10 kg
    )
    private val steppedOff = weighIn(
        "0000 0000 01 23 25",   // three no-load frames: the user has left the scale
        "0000 0000 01 24 26",
        "0000 0000 01 25 27",
    )

    private val user = ScaleUser(
        id = 1,
        gender = GenderType.MALE,
        bodyHeight = 180f,
        birthday = GregorianCalendar(1986, Calendar.JANUARY, 1).time,
    )

    // --- Device matching --------------------------------------------------------------------

    @Test
    fun `claims the IF_B7 advertisement as a broadcast-only body composition scale`() {
        val support = IfB7Handler().supportFor(scanned())!!

        assertThat(support.displayName).isEqualTo("Grifema GA2001 / Senssun IF_B7")
        assertThat(support.linkMode).isEqualTo(LinkMode.BROADCAST_ONLY)
        assertThat(support.implemented).contains(DeviceCapability.BODY_COMPOSITION)
    }

    @Test
    fun `leaves names it does not know alone`() {
        // The vendor SDK also lists an `IF_B7A` sibling. Its frames were never captured, so the
        // handler does not claim it: a row in the published catalog would be a device nobody tested.
        assertThat(IfB7Handler().supportFor(scanned(name = "IF_B7A"))).isNull()
        assertThat(IfB7Handler().supportFor(scanned(name = "IF_B7X"))).isNull()
        assertThat(IfB7Handler().supportFor(scanned(name = "SENSSUN FAT"))).isNull()
        assertThat(IfB7Handler().supportFor(scanned(name = ""))).isNull()
    }

    @Test
    fun `ignores a record that is not 17 bytes long`() {
        assertThat(IfB7Handler().supportFor(scanned(record = record("2134 0208 A1 30").copyOf(16))))
            .isNull()
        assertThat(IfB7Handler().supportFor(scanned(record = ByteArray(17)))).isNull()
    }

    @Test
    fun `ignores a record with no marker and a record with a changed header`() {
        val noMarker = record("2134 0208 A1 30 31").copyOf(17).also { it[9] = 0x00 }
        assertThat(IfB7Handler().supportFor(scanned(record = noMarker))).isNull()

        val badHeader = record("2134 0208 A1 30 31").copyOf(17).also { it[0] = 0x00 }
        assertThat(IfB7Handler().supportFor(scanned(record = badHeader))).isNull()
    }

    @Test
    fun `ignores a record whose checksum does not validate`() {
        val corrupted = record("2134 0208 A1 30 31").copyOf(17).also { it[16] = 0x32 }
        assertThat(IfB7Handler().supportFor(scanned(record = corrupted))).isNull()
    }

    @Test
    fun `ignores a record whose embedded MAC is not the advertiser's`() {
        val foreignMac = hex("020311 FFFFEE123456 01 2134 0208 A1 30 31")
        assertThat(IfB7Handler().supportFor(scanned(record = foreignMac))).isNull()
    }

    @Test
    fun `ignores a record under another company id`() {
        val device = advertisement(
            name = "IF_B7",
            manufacturerData = listOf(0x00FF to record("2134 0208 A1 30 31")),
        )
        assertThat(IfB7Handler().supportFor(device)).isNull()
    }

    @Test
    fun `ignores an advertisement with no manufacturer data`() {
        assertThat(IfB7Handler().supportFor(advertisement(name = "IF_B7"))).isNull()
    }

    // --- Publishing ------------------------------------------------------------------------

    /**
     * The scale streams ~6 packets a second and the weight field jitters by ~1 kg between them, so
     * the reading that gets stored is the median of the window, which is what the scale's own display
     * settles on (the capture: 112.20, 111.50, 111.40, 111.60, 111.60 → display 111.6).
     */
    @Test
    fun `publishes the median weight once the BIA impedance repeats`() {
        val setup = attached()

        for (frame in settle + biaPhase.take(2)) {
            assertThat(setup.handler.onAdvertisement(scanResult(frame), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        assertThat(setup.callbacks.published).isEmpty()

        // The impedance value has to be reported twice: the BIA pass produces one answer, and the
        // frames before it carry no impedance at all.
        assertThat(setup.handler.onAdvertisement(scanResult(biaPhase[2]), user))
            .isEqualTo(BroadcastAction.CONSUMED_STOP)

        assertThat(setup.callbacks.published).hasSize(1)
        val published = setup.callbacks.published.single()
        assertThat(published.userId).isEqualTo(user.id)
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(111.60f)
        assertThat(published[MeasurementType.IMPEDANCE]!!.value).isWithin(0.0001f).of(404f)

        // Composition is derived from the impedance, exactly as StandardImpedanceLib computes it for
        // this user; the scale's own body-fat numbers are not in the broadcast at all.
        val lib = StandardImpedanceLib(
            gender = user.gender,
            age = user.age,
            weightKg = 111.60,
            heightM = user.bodyHeight / 100.0,
            impedance = 404.0,
        )
        assertThat(published[MeasurementType.BODY_FAT]!!.value).isWithin(0.01f).of(lib.totalFatPercentage.toFloat())
        assertThat(published[MeasurementType.WATER]!!.value).isWithin(0.01f).of(lib.totalBodyWaterPercentage.toFloat())
        assertThat(published[MeasurementType.MUSCLE]!!.value).isWithin(0.01f).of(lib.skeletalMusclePercentage.toFloat())
        assertThat(published[MeasurementType.BONE]!!.value).isWithin(0.01f).of(lib.boneMassKg.toFloat())
        assertThat(published[MeasurementType.LBM]!!.value).isWithin(0.01f).of(lib.fatFreeMassKg.toFloat())
        assertThat(published[MeasurementType.BMR]!!.value).isWithin(0.1f).of(lib.basalMetabolicRate.toFloat())
    }

    /**
     * A weighing that ends before the scale runs its BIA pass — stepping off early, or standing on
     * the electrodes through footwear — must not be lost: the last frames are published as a plain
     * weight, so the median is stored and no composition is invented.
     */
    @Test
    fun `publishes a plain weight when the user steps off before the BIA phase`() {
        val setup = attached()

        for (frame in settle + weightOnlyPhase) {
            assertThat(setup.handler.onAdvertisement(scanResult(frame), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        assertThat(setup.handler.onAdvertisement(scanResult(steppedOff[0]), user))
            .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        assertThat(setup.handler.onAdvertisement(scanResult(steppedOff[1]), user))
            .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        assertThat(setup.handler.onAdvertisement(scanResult(steppedOff[2]), user))
            .isEqualTo(BroadcastAction.CONSUMED_STOP)

        assertThat(setup.callbacks.published).hasSize(1)
        val published = setup.callbacks.published.single()
        // the window keeps the last five readings: 111.50, 111.40, 111.80, 112.40, 112.10 → median
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(111.80f)
        assertThat(published[MeasurementType.IMPEDANCE]).isNull()
        assertThat(published[MeasurementType.BODY_FAT]).isNull()
    }

    /**
     * Standing on the electrodes through footwear breaks the BIA circuit: the scale still runs its
     * pass, and reports `0 Ω` as its answer. That is the same reading openScale uses for "BIA pass
     * failed" on the AiLink scales, so the settled weight is stored without composition.
     */
    @Test
    fun `publishes the settled weight when the BIA pass ends with no circuit`() {
        val setup = attached()

        // The weight field keeps jittering while the scale is looking for a circuit.
        val jitter = weighIn(
            "2B52 0000 01 64 E3",   // 110.90 kg
            "2B48 0000 01 6E E3",   // 110.80 kg
            "2B70 0000 01 78 15",   // 111.20 kg
            "2B3E 0000 01 82 ED",   // 110.70 kg
            "2B5C 0000 01 8C 15",   // 111.00 kg
        )
        val noCircuit = weighIn(
            "2B52 0000 A1 8D AC",   // 110.90 kg, BIA pass finished with 0 Ω
            "2B52 0000 A1 97 B6",   // the same answer twice: the pass produced one value
        )

        for (frame in jitter) {
            assertThat(setup.handler.onAdvertisement(scanResult(frame), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        assertThat(setup.callbacks.published).isEmpty()

        assertThat(setup.handler.onAdvertisement(scanResult(noCircuit[0]), user))
            .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        assertThat(setup.handler.onAdvertisement(scanResult(noCircuit[1]), user))
            .isEqualTo(BroadcastAction.CONSUMED_STOP)

        assertThat(setup.callbacks.published).hasSize(1)
        val published = setup.callbacks.published.single()
        // window 110.80, 111.20, 110.70, 111.00, 110.90, 110.90 → last five, median 110.90
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(110.90f)
        assertThat(published[MeasurementType.IMPEDANCE]).isNull()
        assertThat(setup.callbacks.published.single()[MeasurementType.BODY_FAT]).isNull()
    }

    /**
     * The BIA answer can arrive before the weight window has enough readings, so publishing waits
     * for the window to settle. The frames after the measurement are ignored, not stored twice.
     */
    @Test
    fun `a finished BIA pass publishes once the window has settled`() {
        val setup = attached()

        val earlyPair = weighIn(
            "2B52 0000 A1 C8 E7",   // 110.90 kg, 0 Ω, pass finished
            "2B52 0000 A1 D2 F1",   // twice: the answer is one value
        )
        assertThat(setup.handler.onAdvertisement(scanResult(earlyPair[0]), user))
            .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        assertThat(setup.handler.onAdvertisement(scanResult(earlyPair[1]), user))
            .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        assertThat(setup.callbacks.published).isEmpty()

        // Two more readings make the window settled, and the pass is already finished: the median
        // of the collected readings is published on this frame.
        assertThat(setup.handler.onAdvertisement(scanResult(record("2B48 0000 01 14 89")), user))
            .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        assertThat(setup.handler.onAdvertisement(scanResult(record("2B70 0000 01 15 B2")), user))
            .isEqualTo(BroadcastAction.CONSUMED_STOP)

        assertThat(setup.callbacks.published).hasSize(1)
        val published = setup.callbacks.published.single()
        // window 110.90, 110.90, 110.80, 111.20 → median 110.90
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(110.90f)
        assertThat(published[MeasurementType.IMPEDANCE]).isNull()

        assertThat(setup.handler.onAdvertisement(scanResult(earlyPair[1]), user))
            .isEqualTo(BroadcastAction.IGNORED)
    }

    /**
     * A single zero frame happens mid-weigh-in when someone shifts weight on the platform, so the
     * end of a weighing is a run of no-load frames, not one of them.
     */
    @Test
    fun `one zero frame does not end the weigh-in`() {
        val setup = attached()

        for (frame in settle + weightOnlyPhase + listOf(steppedOff[0])) {
            assertThat(setup.handler.onAdvertisement(scanResult(frame), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        assertThat(setup.callbacks.published).isEmpty()

        // The user is still on the scale: the window keeps collecting, and the BIA phase completes
        // the measurement afterwards.
        for (frame in listOf(steppedOff[0], biaPhase[1])) {
            setup.handler.onAdvertisement(scanResult(frame), user)
        }
        assertThat(setup.callbacks.published).isEmpty()
        assertThat(setup.handler.onAdvertisement(scanResult(biaPhase[2]), user))
            .isEqualTo(BroadcastAction.CONSUMED_STOP)

        assertThat(setup.callbacks.published.single()[MeasurementType.WEIGHT]!!.value)
            .isWithin(0.001f).of(111.80f)
    }

    @Test
    fun `the load ramp and its spikes are never published`() {
        val setup = attached()

        // 113.30, 105.00, 111.50, 112.00 kg: the window's spread is far above the settling span, so
        // the BIA pair completing does not publish a reading the scale never settled on.
        val ramp = weighIn(
            "2C42 0000 01 32 A2",
            "2904 0000 01 33 62",
            "2B8E 0000 01 34 EF",
            "2BC0 0000 01 35 22",
            "2BB6 0194 A1 36 4E",
            "2BB6 0194 A1 37 4F",
        )
        ramp.forEach {
            assertThat(setup.handler.onAdvertisement(scanResult(it), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }

        assertThat(setup.callbacks.published).isEmpty()
    }

    @Test
    fun `idle zero-load frames are never published`() {
        val setup = attached()

        repeat(20) {
            assertThat(setup.handler.onAdvertisement(scanResult(idle), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }

        assertThat(setup.callbacks.published).isEmpty()
    }

    /**
     * The scale keeps broadcasting the final reading while the user stands on it, so publishing has
     * to stop after the first measurement — otherwise one weigh-in stores the same record twice.
     */
    @Test
    fun `publishes once per session and ignores the frames after it`() {
        val setup = attached()

        for (frame in settle + biaPhase) {
            setup.handler.onAdvertisement(scanResult(frame), user)
        }
        assertThat(setup.callbacks.published).hasSize(1)

        repeat(6) {
            assertThat(setup.handler.onAdvertisement(scanResult(biaPhase[2]), user))
                .isEqualTo(BroadcastAction.IGNORED)
        }
        assertThat(setup.callbacks.published).hasSize(1)
    }

    /** Stepping off and weighing again is a new session: the window starts from empty. */
    @Test
    fun `a new session can publish again`() {
        val setup = attached()

        for (frame in settle + biaPhase) {
            setup.handler.onAdvertisement(scanResult(frame), user)
        }
        assertThat(setup.callbacks.published).hasSize(1)

        setup.handler.handleDisconnected()
        setup.handler.handleConnected(user)

        for (frame in settle + biaPhase) {
            setup.handler.onAdvertisement(scanResult(frame), user)
        }
        assertThat(setup.callbacks.published).hasSize(2)
        assertThat(setup.callbacks.published[0][MeasurementType.WEIGHT]!!.value)
            .isWithin(0.001f).of(setup.callbacks.published[1][MeasurementType.WEIGHT]!!.value)
    }

    /**
     * An impedance outside the BIA phase is not a value the scale measured: the flag byte says the
     * BIA pass is not running, so storing it would invent a reading.
     */
    @Test
    fun `an impedance outside the BIA phase is never stored`() {
        val setup = attached()

        val frames = weighIn(
            "2B8E 0194 01 3C 8C",   // 111.50 kg, 404 Ω reported without the BIA flag
            "2B8E 0194 01 3D 8D",
            "2B8E 0194 01 3E 8E",
            "2B8E 0194 01 3F 8F",
        )
        frames.forEach { setup.handler.onAdvertisement(scanResult(it), user) }
        assertThat(setup.callbacks.published).isEmpty()

        // Stepping off ends the weigh-in: the weight is stored, the impedance is not.
        steppedOff.forEach { setup.handler.onAdvertisement(scanResult(it), user) }
        val published = setup.callbacks.published.single()
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(111.50f)
        assertThat(published[MeasurementType.IMPEDANCE]).isNull()
        assertThat(published[MeasurementType.BODY_FAT]).isNull()
    }

    /**
     * Height and age are inputs of every body-composition equation, and [ScaleUser] defaults
     * `bodyHeight` to -1. Without them the derived percentages would be numbers out of a garbage
     * input, so only the weight is stored.
     */
    @Test
    fun `stores no composition for a user without a height`() {
        val setup = attached()
        val noHeight = ScaleUser(id = 1, gender = GenderType.MALE, bodyHeight = -1f)

        for (frame in settle + biaPhase) {
            setup.handler.onAdvertisement(scanResult(frame), noHeight)
        }

        val published = setup.callbacks.published.single()
        assertThat(published[MeasurementType.WEIGHT]!!.value).isWithin(0.001f).of(111.60f)
        assertThat(published[MeasurementType.IMPEDANCE]).isNull()
        assertThat(published[MeasurementType.BODY_FAT]).isNull()
    }

    /**
     * A decode that lands outside the scale's range (a 500 kg reading is the load cell mid-ramp) is
     * dropped, so it cannot pull the median of the real readings up.
     */
    @Test
    fun `an implausible reading is dropped from the window`() {
        val setup = attached()

        val frames = weighIn(
            "C350 0000 01 46 5B",   // 500.00 kg, outside the range of a consumer scale
            "2B8E 0000 01 47 02",   // 111.50 kg
            "2B8E 0000 01 48 03",
            "2B8E 0000 01 49 04",
            "2B8E 0194 A1 4A 3A",
            "2B8E 0194 A1 4B 3B",
        )
        for (frame in frames.take(5)) {
            assertThat(setup.handler.onAdvertisement(scanResult(frame), user))
                .isEqualTo(BroadcastAction.CONSUMED_KEEP_SCANNING)
        }
        assertThat(setup.callbacks.published).isEmpty()

        assertThat(setup.handler.onAdvertisement(scanResult(frames[5]), user))
            .isEqualTo(BroadcastAction.CONSUMED_STOP)
        assertThat(setup.callbacks.published.single()[MeasurementType.WEIGHT]!!.value)
            .isWithin(0.001f).of(111.50f)
    }

    @Test
    fun `a frame with a corrupted checksum is ignored`() {
        val setup = attached()
        val corrupted = biaPhase[1].copyOf(17).also { it[16] = 0x0E }

        repeat(5) {
            assertThat(setup.handler.onAdvertisement(scanResult(corrupted), user))
                .isEqualTo(BroadcastAction.IGNORED)
        }

        assertThat(setup.callbacks.published).isEmpty()
    }

    // --- Helpers --------------------------------------------------------------------------

    /** The advertisement the factory hands to `supportFor`: [ScaleCatalog]'s fixture address. */
    private fun scanned(record: ByteArray = biaPhase[1], name: String = "IF_B7"): ScannedDeviceInfo =
        advertisement(name = name, manufacturerData = listOf(companyId to record))

    /**
     * A real [ScanResult] carrying [payload] under [companyId]. The AD structure is written the way
     * an advertisement carries it (length, type 0xFF, company id little-endian, payload) and parsed
     * by Android's own hidden parser, so `manufacturerSpecificData` hands the handler exactly the
     * bytes the device sent after the company id.
     */
    @Suppress("DEPRECATION")
    private fun scanResult(payload: ByteArray, addr: String = address): ScanResult {
        val block = byteArrayOf(0x00, 0x01) + payload
        val adStructure = byteArrayOf((block.size + 1).toByte(), 0xFF.toByte()) + block
        val parseMethod = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
        parseMethod.isAccessible = true
        val record = parseMethod.invoke(null, adStructure) as ScanRecord
        return ScanResult(ShadowBluetoothDevice.newInstance(addr), record, -42, 0L)
    }

    private class Setup(val handler: IfB7Handler, val callbacks: CapturingCallbacks)

    private fun attached(): Setup {
        val handler = IfB7Handler()
        val callbacks = CapturingCallbacks()
        handler.attach(
            NoTransport(),
            callbacks,
            InMemorySettings(),
            FixedDataProvider(user),
            CoroutineScope(Dispatchers.Unconfined),
        )
        return Setup(handler, callbacks)
    }

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()

        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement.snapshot()
        }

        override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
    }

    private class NoTransport : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = false
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val strings = mutableMapOf<String, String>()
        private val ints = mutableMapOf<String, Int>()

        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun remove(key: String) {
            strings.remove(key)
            ints.remove(key)
        }
    }

    private class FixedDataProvider(private val user: ScaleUser) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }
}
