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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.TanitaLib
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.UnitType
import com.health.openscale.core.service.ScannedDeviceInfo
import com.health.openscale.core.usecase.MeasurementTypeCrudUseCases
import com.health.openscale.testutil.RoomTestSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TanitaRD545HandlerTest {
    private val harnesses = mutableListOf<Harness>()
    private val profile = hex("6a3200016a330000027e220a546573746572000000006a3c80006a37006a38006a3e06d66a3b00604f00006a15000000016a1302")
    private val record = hex("016a3200016a330000026a38006a3e06d66a3b0060211d4c605600f5602200d5")
    private val gapName = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

    @After
    fun cleanup() = harnesses.forEach { it.scope.cancel() }

    @Test
    fun `shared Tanita name requires the vendor service`() {
        val handler = TanitaRD545Handler()
        fun device(services: List<UUID>) = ScannedDeviceInfo("TNT_PAIR", "00:00:00:00:00:00", -50, services, null)
        assertThat(handler.supportFor(device(emptyList()))).isNull()
        assertThat(handler.supportFor(device(listOf(TanitaRD545Handler.SERVICE)))).isNotNull()
    }

    @Test
    fun `checks the model before sending vendor commands`() {
        val h = harness()
        h.handler.handleNotification(gapName, "BC-401EU".toByteArray())
        assertThat(h.commands()).isEmpty()
        assertThat(h.disconnected).isTrue()
        assertThat(h.errors).contains(R.string.tanita_unsupported_model)
    }

    @Test
    fun `rejected identifier never silently registers or writes a profile`() {
        val h = harness()
        h.model()
        h.reply(0x8003, byteArrayOf(1))
        assertThat(h.commands().map(::opcode)).containsExactly(TanitaLib.VERIFY_UUID)
        assertThat(h.disconnected).isTrue()
        assertThat(h.errors).contains(R.string.tanita_setup_required)
    }

    @Test
    fun `normal weighing selects the linked slot and publishes confirmed metrics`() {
        val h = harness(links = "1:0,2:1")
        h.toProfiles()
        assertThat(h.commands().last()[4]).isEqualTo(0.toByte())
        h.reply(0x9000, profile)
        assertThat(opcode(h.commands().last())).isEqualTo(TanitaLib.EXCHANGE_USER_INFO)
        assertThat(h.commands().last()[4]).isEqualTo(0.toByte())
        h.reply(0x9002, profile)
        assertThat(opcode(h.commands().last())).isEqualTo(TanitaLib.MEASURE)
        h.reply(0xa010)
        h.reply(0xb000, byteArrayOf(1))
        h.reply(0xb010, record)
        val reading = h.published.single()
        assertThat(reading.userId).isEqualTo(1)
        assertThat(reading[MeasurementType.WEIGHT]?.value).isEqualTo(75f)
        assertThat(reading[MeasurementType.BODY_FAT]?.value).isEqualTo(21.3f)
        assertThat(reading[MeasurementType.BMI]).isEqualTo(24.5f)
        assertThat(reading.values).hasSize(3)
        assertThat(h.disconnected).isTrue()
    }

    @Test
    fun `a second app user uses their own slot instead of the last user slot`() {
        val h = harness(userId = 2, links = "1:0,2:1")
        h.toProfiles()
        assertThat(opcode(h.commands().last())).isEqualTo(TanitaLib.RETRIEVE_USER_INFO)
        assertThat(h.commands().last()[4]).isEqualTo(1.toByte())
        h.reply(0x9000, profile)
        assertThat(h.commands().last()[4]).isEqualTo(1.toByte())
    }

    @Test
    fun `unlinked users explicitly choose an unclaimed configured profile`() {
        val h = harness(links = "2:0")
        h.toProfiles()
        h.reply(0x9000, profile) // slot 1 belongs to app user 2
        h.reply(0x9000, profile) // slot 2 is available
        h.reply(0x9000, byteArrayOf(0), status = 1)
        h.reply(0x9000, byteArrayOf(0), status = 1)
        val choice = h.choices.single() as Pair<*, *>
        assertThat(choice.second as IntArray).isEqualTo(intArrayOf(1))
        assertThat(h.commands().map(::opcode)).doesNotContain(TanitaLib.EXCHANGE_USER_INFO)
        runBlocking { h.handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, 1, 1) }
        assertThat(h.settings.strings[TanitaRD545Handler.KEY_LINKS]).contains("1:1")
        assertThat(h.settings.strings[TanitaRD545Handler.KEY_LINKS]).contains("2:0")
        assertThat(opcode(h.commands().last())).isEqualTo(TanitaLib.EXCHANGE_USER_INFO)
    }

    @Test
    fun `explicit setup registers the openscale identifier after profile selection`() {
        val h = harness(links = "1:0", register = true)
        h.toProfiles(register = true)
        h.reply(0x9000, profile)
        h.reply(0x9002, profile)
        val registration = h.commands().last()
        assertThat(opcode(registration)).isEqualTo(TanitaLib.REGISTER_UUID)
        assertThat(registration.copyOfRange(4, registration.lastIndex).toString(Charsets.US_ASCII))
            .isEqualTo(TanitaRD545Handler.APPLICATION_ID.toString())
        h.reply(0x8002)
        assertThat(h.settings.ints[TanitaRD545Handler.KEY_REGISTER]).isEqualTo(0)
        assertThat(opcode(h.commands().last())).isEqualTo(TanitaLib.MEASURE)
    }

    @Test
    fun `changing the active app user aborts before results can be published`() {
        val h = harness(links = "1:0")
        h.toProfiles()
        h.activeUser = ScaleUser(id = 2)
        h.reply(0x9000, profile)
        assertThat(h.disconnected).isTrue()
        assertThat(h.published).isEmpty()
        assertThat(h.commands().map(::opcode)).doesNotContain(TanitaLib.EXCHANGE_USER_INFO)
        assertThat(h.errors).contains(R.string.tanita_user_changed)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `missing model response times out instead of leaving the connection active`() {
        val h = harness()
        h.dispatcher.scheduler.runCurrent()
        h.dispatcher.scheduler.advanceTimeBy(20_001)
        h.dispatcher.scheduler.runCurrent()
        assertThat(h.disconnected).isTrue()
        assertThat(h.errors).contains(R.string.tanita_timeout)
    }

    @Test
    fun `retrieves all history while reading consumes and renumbers records`() {
        val h = harness(links = "1:0")
        h.toProfiles()
        h.reply(0x9000, profile)
        h.reply(0x9002, profile)
        h.reply(0xa010)
        val history = MutableList(3) { record.copyOf() }
        h.reply(0xb000, byteArrayOf(history.size.toByte()))
        val requestedIndices = mutableListOf<Int>()
        while (!h.disconnected && requestedIndices.size < 4) {
            val command = h.commands().last()
            assertThat(opcode(command)).isEqualTo(TanitaLib.RETRIEVE_MEASUREMENT)
            val index = command[4].toInt() and 0xff
            requestedIndices.add(index)
            if (index !in 1..history.size) {
                h.reply(0xb010, record, status = 5)
            } else {
                val result = history.removeAt(index - 1)
                result[0] = index.toByte()
                h.reply(0xb010, result)
            }
        }
        assertThat(requestedIndices).containsExactly(3, 2, 1).inOrder()
        assertThat(history).isEmpty()
        assertThat(h.published).hasSize(3)
        assertThat(h.errors).isEmpty()
        assertThat(h.disconnected).isTrue()
    }

    @Test
    fun `a result is discarded if the active user changes immediately before it arrives`() {
        val h = harness(links = "1:0")
        h.toProfiles()
        h.reply(0x9000, profile)
        h.reply(0x9002, profile)
        h.reply(0xa010)
        h.reply(0xb000, byteArrayOf(1))
        h.activeUser = ScaleUser(id = 2)
        h.reply(0xb010, record)
        assertThat(h.published).isEmpty()
        assertThat(h.disconnected).isTrue()
    }

    @Test
    fun `failed measurement explains how to retry without importing old history`() {
        val h = harness(links = "1:0")
        h.toMeasuring()
        h.reply(0xa010, status = 1)
        assertThat(h.published).isEmpty()
        assertThat(h.commands().map(::opcode)).doesNotContain(TanitaLib.RETRIEVE_MEASUREMENT_COUNT)
        assertThat(h.errors).containsExactly(R.string.tanita_measurement_failed)
        assertThat(h.disconnected).isTrue()
    }

    @Test
    fun `valid weight without reported body fat never creates a fat value`() {
        val h = harness(links = "1:0")
        h.toMeasuring()
        h.reply(0xa010)
        h.reply(0xb000, byteArrayOf(1))
        val weightOnly = record.copyOf().also {
            it[it.lastIndex - 1] = 0xff.toByte()
            it[it.lastIndex] = 0xff.toByte()
        }
        h.reply(0xb010, weightOnly)
        val reading = h.published.single()
        assertThat(reading[MeasurementType.WEIGHT]?.value).isEqualTo(75f)
        assertThat(reading[MeasurementType.BODY_FAT]).isNull()
        assertThat(reading.values).hasSize(2) // weight plus the separately reported BMI
        assertThat(h.errors).isEmpty()
        assertThat(h.disconnected).isTrue()
    }

    @Test
    fun `publishes whole body segmental quality scores and signed impedance in their actual units`() {
        val h = harness(links = "1:0")
        h.toMeasuring()
        h.reply(0xa010)
        h.reply(0xb000, byteArrayOf(1))
        val extra = hex("6023151860290118602b022d6025004160270640602823605b46607004602482604200dc6045012c605c44604680604782614301906144ff38606e4b606f01")
        h.reply(0xb010, record + extra)
        val m = h.published.single()
        assertThat(m[MeasurementType.MUSCLE]?.value).isEqualTo(72f)
        assertThat(m[TanitaRD545Handler.TOTAL_MUSCLE]?.value).isEqualTo(54f)
        assertThat(m[MeasurementType.BONE]?.value).isEqualTo(2.8f)
        assertThat(m[MeasurementType.WATER]?.value).isEqualTo(55.7f)
        assertThat(m[MeasurementType.VISCERAL_FAT]).isEqualTo(6.5f)
        assertThat(m[MeasurementType.BMR]?.value).isEqualTo(1600f)
        assertThat(m[TanitaRD545Handler.METABOLIC_AGE]).isEqualTo(35)
        assertThat(m[TanitaRD545Handler.MUSCLE_QUALITY]).isEqualTo(70)
        assertThat(m[TanitaRD545Handler.PHYSIQUE_RATING]).isEqualTo(1)
        assertThat(m[MeasurementType.HEART_RATE]?.value).isEqualTo(75)
        assertThat(m[TanitaRD545Handler.PERCENT_METRICS.getValue(TanitaLib.Metric.LEFT_ARM_FAT)]?.value).isEqualTo(22f)
        assertThat(m[TanitaRD545Handler.KG_METRICS.getValue(TanitaLib.Metric.LEFT_ARM_MUSCLE_MASS)]?.value).isEqualTo(3f)
        assertThat(m[TanitaRD545Handler.INT_METRICS.getValue(TanitaLib.Metric.LEFT_ARM_FAT_SCORE)]).isEqualTo(0)
        assertThat(m[TanitaRD545Handler.INT_METRICS.getValue(TanitaLib.Metric.LEFT_ARM_MUSCLE_SCORE)]).isEqualTo(-2)
        assertThat(m[TanitaRD545Handler.OHM_METRICS.getValue(TanitaLib.Metric.LEFT_ARM_50KHZ_RESISTANCE)]?.value).isEqualTo(40f)
        assertThat(m[TanitaRD545Handler.OHM_METRICS.getValue(TanitaLib.Metric.LEFT_ARM_50KHZ_REACTANCE)]?.value).isEqualTo(-20f)
        assertThat(h.errors).isEmpty()
        assertThat(h.disconnected).isTrue()
    }

    @Test
    fun `raw muscle mass and percentage keep separate stored types in either discovery order`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val percentKey = QNHandler.TOTAL_MUSCLE
        val massKey = TanitaRD545Handler.TOTAL_MUSCLE
        for (keys in listOf(listOf(percentKey, massKey), listOf(massKey, percentKey))) {
            val db = RoomTestSupport.inMemory(context)
            try {
                val types = MeasurementTypeCrudUseCases(RoomTestSupport.repositoryFor(db), context)
                keys.forEach { assertThat(types.resolveOrCreate(it)).isNotNull() }

                val percent = types.resolveOrCreate(percentKey)!!
                val mass = types.resolveOrCreate(massKey)!!
                assertThat(mass.id).isNotEqualTo(percent.id)
                assertThat(percent.unit).isEqualTo(UnitType.PERCENT)
                assertThat(mass.unit).isEqualTo(UnitType.KG)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun `zero quality and assessment codes are retained but negative codes are rejected`() {
        val h = harness(links = "1:0")
        h.toMeasuring()
        h.reply(0xa010)
        h.reply(0xb000, byteArrayOf(1))
        h.reply(0xb010, record + hex("605b00607000602f0000606f00"))
        val m = h.published.single()
        val fatRating = TanitaRD545Handler.INT_METRICS.getValue(TanitaLib.Metric.FAT_RATING)
        assertThat(m[TanitaRD545Handler.MUSCLE_QUALITY]).isEqualTo(0)
        assertThat(m[fatRating]).isEqualTo(0)
        assertThat(m[TanitaRD545Handler.INT_METRICS.getValue(TanitaLib.Metric.BMR_RATING)]).isEqualTo(0)
        assertThat(m[TanitaRD545Handler.INT_METRICS.getValue(TanitaLib.Metric.HEART_RATE_STATE)]).isEqualTo(0)
        m[fatRating] = -1
        m[TanitaRD545Handler.MUSCLE_QUALITY] = -1
        assertThat(m[fatRating]).isEqualTo(0)
        assertThat(m[TanitaRD545Handler.MUSCLE_QUALITY]).isEqualTo(0)
        assertThat(h.errors).isEmpty()
        assertThat(h.disconnected).isTrue()
    }

    @Test
    fun `history without a valid timestamp is never saved as the current time`() {
        val missingTimestamp = hex("0160211d4c602200d5")
        val invalidTime = record.copyOf().also { it[7] = 0xff.toByte() }
        for (payload in listOf(missingTimestamp, invalidTime)) {
            val h = harness(links = "1:0")
            h.toMeasuring()
            h.reply(0xa010)
            h.reply(0xb000, byteArrayOf(1))
            h.reply(0xb010, payload)
            assertThat(h.published).isEmpty()
            assertThat(h.errors).containsExactly(R.string.tanita_invalid_response)
            assertThat(h.disconnected).isTrue()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `disconnect discards partial results cancels timers and allows a fresh session`() {
        val h = harness(links = "1:0")
        h.toMeasuring()
        h.reply(0xa010)
        h.reply(0xb000, byteArrayOf(1))
        val fragments = TanitaLib.fragment(response(0xb010, record, 0))
        h.handler.handleNotification(TanitaRD545Handler.NOTIFY, fragments.first())
        h.handler.handleDisconnected()
        fragments.drop(1).forEach { h.handler.handleNotification(TanitaRD545Handler.NOTIFY, it) }
        h.dispatcher.scheduler.advanceTimeBy(200_001)
        h.dispatcher.scheduler.runCurrent()
        assertThat(h.published).isEmpty()
        assertThat(h.errors).isEmpty()

        h.handler.handleConnected(h.activeUser)
        h.toMeasuring()
        h.reply(0xa010)
        h.reply(0xb000, byteArrayOf(1))
        h.reply(0xb010, record)
        assertThat(h.published).hasSize(1)
        assertThat(h.errors).isEmpty()
        assertThat(h.disconnected).isTrue()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `measurement timeout ends once and ignores a late completion`() {
        val h = harness(links = "1:0")
        h.toMeasuring()
        h.dispatcher.scheduler.runCurrent()
        h.dispatcher.scheduler.advanceTimeBy(179_999)
        h.dispatcher.scheduler.runCurrent()
        assertThat(h.disconnected).isFalse()
        h.dispatcher.scheduler.advanceTimeBy(2)
        h.dispatcher.scheduler.runCurrent()
        h.reply(0xa010)
        assertThat(h.errors).containsExactly(R.string.tanita_timeout)
        assertThat(h.published).isEmpty()
        assertThat(h.commands().map(::opcode)).doesNotContain(TanitaLib.RETRIEVE_MEASUREMENT_COUNT)
        assertThat(h.disconnected).isTrue()
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `corrupt result never publishes and eventually times out`() {
        val h = harness(links = "1:0")
        h.toMeasuring()
        h.reply(0xa010)
        h.reply(0xb000, byteArrayOf(1))
        val fragments = TanitaLib.fragment(response(0xb010, record, 0))
        fragments.last().let { it[it.lastIndex] = (it.last() + 1).toByte() }
        fragments.forEach { h.handler.handleNotification(TanitaRD545Handler.NOTIFY, it) }
        h.dispatcher.scheduler.runCurrent()
        h.dispatcher.scheduler.advanceTimeBy(20_001)
        h.dispatcher.scheduler.runCurrent()
        assertThat(h.published).isEmpty()
        assertThat(h.errors).containsExactly(R.string.tanita_timeout)
        assertThat(h.disconnected).isTrue()
    }

    @Test
    fun `history error keeps only records that were successfully validated`() {
        val h = harness(links = "1:0")
        h.toMeasuring()
        h.reply(0xa010)
        h.reply(0xb000, byteArrayOf(2))
        h.reply(0xb010, record.copyOf().also { it[0] = 2 })
        h.reply(0xb010, record, status = 5)
        assertThat(h.published).hasSize(1)
        assertThat(h.errors).containsExactly(R.string.tanita_operation_failed)
        assertThat(h.disconnected).isTrue()
    }

    private fun harness(userId: Int = 1, links: String = "", register: Boolean = false) = Harness(userId, links, register).also {
        harnesses.add(it)
        it.handler.handleConnected(it.activeUser)
    }

    private inner class Harness(userId: Int, links: String, register: Boolean) {
        val settings = Settings().apply {
            strings[TanitaRD545Handler.KEY_LINKS] = links
            ints[TanitaRD545Handler.KEY_REGISTER] = if (register) 1 else 0
        }
        val dispatcher = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var activeUser = ScaleUser(id = userId, userName = "Synthetic")
        var disconnected = false
        val published = mutableListOf<ScaleMeasurement>()
        val errors = mutableListOf<Int>()
        val choices = mutableListOf<Any?>()
        private val writes = mutableListOf<ByteArray>()
        val handler = TanitaRD545Handler().apply {
            attach(
                object : ScaleDeviceHandler.Transport {
                    override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
                    override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
                        assertThat(service).isEqualTo(TanitaRD545Handler.SERVICE)
                        assertThat(characteristic).isEqualTo(TanitaRD545Handler.WRITE)
                        assertThat(withResponse).isFalse()
                        writes.add(payload.copyOf())
                    }
                    override fun read(service: UUID, characteristic: UUID) = Unit
                    override fun disconnect() { disconnected = true }
                    override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
                },
                object : ScaleDeviceHandler.Callbacks {
                    override fun onPublish(measurement: ScaleMeasurement) { published.add(measurement) }
                    override fun onError(resId: Int, t: Throwable?, vararg args: Any) { errors.add(resId) }
                    override fun resolveString(resId: Int, vararg args: Any) = args.joinToString(" ")
                    override fun onUserInteractionRequired(interactionType: UserInteractionType, data: Any?) { choices.add(data) }
                },
                settings,
                object : ScaleDeviceHandler.DataProvider {
                    override fun currentUser() = activeUser
                    override fun usersForDevice() = listOf(activeUser)
                    override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
                },
                scope,
            )
        }

        fun model() = handler.handleNotification(gapName, "RD-545EU".toByteArray())

        fun toProfiles(register: Boolean = false) {
            model()
            if (!register) reply(0x8003)
            reply(0x8010)
            reply(0x8020)
        }

        fun toMeasuring() {
            toProfiles()
            reply(0x9000, profile)
            reply(0x9002, profile)
        }

        fun reply(command: Int, payload: ByteArray = byteArrayOf(0), status: Int = 0) {
            val wire = response(command, payload, status)
            TanitaLib.fragment(wire).forEach { handler.handleNotification(TanitaRD545Handler.NOTIFY, it) }
        }

        fun commands(): List<ByteArray> {
            val assembler = TanitaLib.Assembler()
            return writes.mapNotNull(assembler::add)
        }
    }

    private class Settings : ScaleDeviceHandler.DriverSettings {
        val ints = mutableMapOf<String, Int>()
        val strings = mutableMapOf<String, String>()
        override fun getInt(key: String, default: Int) = ints[key] ?: default
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, default: String?) = strings[key] ?: default
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { ints.remove(key); strings.remove(key) }
    }

    private fun opcode(message: ByteArray) = ((message[2].toInt() and 255) shl 8) or (message[3].toInt() and 255)

    private fun response(command: Int, payload: ByteArray, status: Int): ByteArray {
        val bytes = ByteArray(payload.size + 6)
        val length = bytes.size - 2
        bytes[0] = (length ushr 8).toByte(); bytes[1] = length.toByte()
        bytes[2] = (command ushr 8).toByte(); bytes[3] = command.toByte()
        bytes[4] = status.toByte()
        payload.copyInto(bytes, 5)
        bytes[bytes.lastIndex] = (255 - bytes.sumOf { it.toInt() and 255 }).toByte()
        return bytes
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
