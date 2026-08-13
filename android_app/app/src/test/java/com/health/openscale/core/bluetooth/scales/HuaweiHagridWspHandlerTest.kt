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
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.HuaweiHagridWspLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayDeque
import java.util.Date
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuaweiHagridWspHandlerTest {

    @Test
    fun `claims known Huawei Hagrid devices without claiming generic BIA`() {
        val scale2 = HuaweiHagridWspHandler().supportFor(device("HUAWEI Scale 2 Pro"))
        val scale3Pro = HuaweiHagridWspHandler().supportFor(device("Hagrid-B29"))
        val generic = HuaweiHagridWspHandler().supportFor(
            device("Generic BIA", services = intArrayOf(0x1805, 0x181B, 0x181C, 0x181D))
        )

        assertThat(scale2).isNotNull()
        assertThat(scale2!!.displayName).isEqualTo("HUAWEI Scale 2 Pro")
        assertThat(scale3Pro).isNotNull()
        assertThat(scale3Pro!!.displayName).isEqualTo("HUAWEI Scale 3 Pro")
        assertThat(scale2.implemented).doesNotContain(DeviceCapability.USER_SYNC)
        assertThat(scale2.implemented).doesNotContain(DeviceCapability.UNIT_CONFIG)
        assertThat(generic).isNull()
    }

    @Test
    fun `claims product id from manufacturer or service data`() {
        val manufacturerData = SparseArray<ByteArray>().apply {
            put(0x027D, "model=007B".encodeToByteArray())
        }
        val manufacturerSupport = HuaweiHagridWspHandler().supportFor(
            device(name = "Unknown Scale", manufacturerData = manufacturerData)
        )
        val serviceSupport = HuaweiHagridWspHandler().supportFor(
            device(
                name = "Unknown Scale",
                serviceData = mapOf(uuid16(0xFD00) to "product=007B".encodeToByteArray())
            )
        )

        assertThat(manufacturerSupport).isNotNull()
        assertThat(serviceSupport).isNotNull()
        assertThat(manufacturerSupport!!.displayName).isEqualTo("HUAWEI Scale 2 Pro")
        assertThat(serviceSupport!!.displayName).isEqualTo("HUAWEI Scale 2 Pro")
    }

    @Test
    fun `raw standard weight notification publishes standard measurement`() {
        val handler = HuaweiHagridWspHandler()
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = NoopTransport(),
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(ScaleUser(id = 7)),
            scope = CoroutineScope(EmptyCoroutineContext),
        )

        handler.handleNotification(
            characteristic = CHR_WEIGHT_MEASUREMENT,
            data = byteArrayOf(0x00, 0x8C.toByte(), 0x3C),
        )

        assertThat(callbacks.published).hasSize(1)
        assertThat(callbacks.published.single().userId).isEqualTo(7)
        assertThat(callbacks.published.single().weight).isWithin(0.0001f).of(77.5f)
    }

    @Test
    fun `does not send auth token when Hagrid secrets are not configured`() {
        val handler = HuaweiHagridWspHandler()
        val transport = CapturingTransport.allHagrid()
        handler.supportFor(device("HUAWEI Scale 2 Pro", address = HAGRID_ADDRESS))
        handler.attach(
            transport = transport,
            callbacks = CapturingCallbacks(),
            settings = InMemorySettings(),
            data = FixedDataProvider(ScaleUser(id = 7)),
            scope = CoroutineScope(EmptyCoroutineContext),
        )

        handler.handleConnected(ScaleUser(id = 7))
        sendWspNotification(handler, CHR_REQUEST_AUTH, ByteArray(16) { it.toByte() })

        assertThat(transport.writes.map { it.characteristic }).doesNotContain(CHR_AUTH_TOKEN)
        assertThat(transport.writes.map { it.characteristic }).contains(CHR_REALTIME_WEIGHT)
    }

    @Test
    fun `plain WSP realtime notification reports progress without publishing`() {
        val handler = HuaweiHagridWspHandler()
        val callbacks = CapturingCallbacks()
        handler.attach(
            transport = NoopTransport(),
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(ScaleUser(id = 7)),
            scope = CoroutineScope(EmptyCoroutineContext),
        )

        sendWspNotification(handler, CHR_REALTIME_WEIGHT, realtimePayload())

        assertThat(callbacks.published).isEmpty()
        assertThat(callbacks.infos.map { it.resId })
            .contains(R.string.bluetooth_scale_info_measuring_weight)
        assertThat(callbacks.infos.single().args.single() as Float)
            .isWithin(0.0001f).of(77.32f)
    }

    @Test
    fun `Hagrid history notification publishes non-terminal record and acknowledges next read`() {
        val handler = HuaweiHagridWspHandler()
        val callbacks = CapturingCallbacks()
        val transport = CapturingTransport(setOf(SVC_BODY_COMPOSITION to CHR_HISTORY_WEIGHT))
        handler.attach(
            transport = transport,
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(ScaleUser(id = 7)),
            scope = CoroutineScope(EmptyCoroutineContext),
        )

        sendWspNotification(handler, CHR_HISTORY_WEIGHT, historyPayload(completeFlag = 0x01))

        assertThat(callbacks.published).hasSize(1)
        val measurement = callbacks.published.single()
        assertThat(measurement.weight).isWithin(0.0001f).of(77.32f)
        assertThat(measurement.fat).isWithin(0.0001f).of(18.5f)
        assertThat(measurement.heartRate).isEqualTo(72)
        assertThat(measurement.impedanceLow).isWithin(0.0001).of(500.0)
        assertThat(measurement.impedance).isWithin(0.0001).of(600.0)
        assertThat(transport.reassembleWriteMessagesTo(CHR_HISTORY_WEIGHT).last())
            .isEqualTo(byteArrayOf(0x00))
    }

    @Test
    fun `Hagrid history duplicate is acknowledged without duplicate publish`() {
        val handler = HuaweiHagridWspHandler()
        val callbacks = CapturingCallbacks()
        val transport = CapturingTransport(setOf(SVC_BODY_COMPOSITION to CHR_HISTORY_WEIGHT))
        handler.attach(
            transport = transport,
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(ScaleUser(id = 7)),
            scope = CoroutineScope(EmptyCoroutineContext),
        )

        sendWspNotification(handler, CHR_HISTORY_WEIGHT, historyPayload(completeFlag = 0x01))
        sendWspNotification(handler, CHR_HISTORY_WEIGHT, historyPayload(completeFlag = 0x01))

        assertThat(callbacks.published).hasSize(1)
        assertThat(transport.reassembleWriteMessagesTo(CHR_HISTORY_WEIGHT)).hasSize(2)
    }

    @Test
    fun `Hagrid history terminal notification publishes without next-read ack`() {
        val handler = HuaweiHagridWspHandler()
        val callbacks = CapturingCallbacks()
        val transport = CapturingTransport(setOf(SVC_BODY_COMPOSITION to CHR_HISTORY_WEIGHT))
        handler.attach(
            transport = transport,
            callbacks = callbacks,
            settings = InMemorySettings(),
            data = FixedDataProvider(ScaleUser(id = 7)),
            scope = CoroutineScope(EmptyCoroutineContext),
        )

        sendWspNotification(handler, CHR_HISTORY_WEIGHT, historyPayload(completeFlag = 0x00))

        assertThat(callbacks.published).hasSize(1)
        assertThat(transport.reassembleWriteMessagesTo(CHR_HISTORY_WEIGHT)).isEmpty()
    }

    @Test
    fun `configured fake Hagrid secrets authenticate and start measurement reads after status ready`() {
        val randA = ByteArray(16) { it.toByte() }
        val randB = ByteArray(16) { (it + 16).toByte() }
        val workKey = ByteArray(16) { (it + 32).toByte() }
        val workKeyIv = ByteArray(16) { (it + 48).toByte() }
        val cak = ByteArray(16) { (it + 64).toByte() }
        val c1 = ByteArray(16) { (it + 80).toByte() }
        val c2 = ByteArray(16) { (it + 96).toByte() }
        val userInfoIv = ByteArray(16) { (it + 112).toByte() }
        val user = ScaleUser(
            id = 7,
            birthday = Date(946684800000L),
            bodyHeight = 171f,
            gender = GenderType.FEMALE,
            initialWeight = 83.2f,
        )
        val randomValues = ArrayDeque(listOf(randB, workKey, workKeyIv, userInfoIv))
        val handler = HuaweiHagridWspHandler(randomBytes = { size ->
            assertThat(size).isEqualTo(16)
            randomValues.removeFirst()
        })
        val settings = InMemorySettings().apply {
            putString(HuaweiHagridWspHandler.SETTINGS_KEY_CAK_HEX, cak.toHex())
            putString(HuaweiHagridWspHandler.SETTINGS_KEY_C1_HEX, c1.toHex())
            putString(HuaweiHagridWspHandler.SETTINGS_KEY_C2_HEX, c2.toHex())
        }
        val transport = CapturingTransport.allHagrid()
        handler.supportFor(device("HUAWEI Scale 2 Pro", address = HAGRID_ADDRESS))
        handler.attach(
            transport = transport,
            callbacks = CapturingCallbacks(),
            settings = settings,
            data = FixedDataProvider(user),
            scope = CoroutineScope(EmptyCoroutineContext),
        )

        handler.handleConnected(user)
        sendWspNotification(handler, CHR_REQUEST_AUTH, randA)

        val authTokenPayload = transport.reassembleWritesTo(CHR_AUTH_TOKEN)
        assertThat(authTokenPayload)
            .isEqualTo(HuaweiHagridWspLib.buildAuthTokenPayload(randA, randB, cak))

        sendWspNotification(
            handler,
            CHR_AUTH_TOKEN,
            HuaweiHagridWspLib.expectedAuthResponsePayload(randA, randB, cak)
        )

        val rootKey = HuaweiHagridWspLib.deriveHagridRootKey(
            c1,
            c2,
            HuaweiHagridWspLib.hagridC3FromBluetoothAddress(HAGRID_ADDRESS)
        )
        val workKeyPayload = transport.reassembleWritesTo(CHR_SEND_WORK_KEY)
        assertThat(workKeyPayload).hasLength(32)
        assertThat(HuaweiHagridWspLib.decryptPayload(workKeyPayload, rootKey)).isEqualTo(workKey)

        sendWspNotification(handler, CHR_SEND_WORK_KEY, byteArrayOf(0x00))

        assertThat(transport.writes.map { it.characteristic }).contains(CHR_GET_MANAGER_INFO)
        assertThat(transport.writes.map { it.characteristic }).doesNotContain(CHR_SET_USER_INFO)

        sendWspNotification(handler, CHR_GET_MANAGER_INFO, managerInfoPayload())

        val userInfoPayload = transport.reassembleWritesTo(CHR_SET_USER_INFO)
        assertThat(userInfoPayload).hasLength(85)
        assertThat(HuaweiHagridWspLib.decryptPayload(userInfoPayload, workKey))
            .isEqualTo(
                HuaweiHagridWspLib.buildUserInfoPayload(
                    HuaweiHagridWspLib.HagridUserInfo(
                        huid = HAGRID_MANAGER_HUID,
                        uid = "u:00000007",
                        gender = 1,
                        ageYears = user.age,
                        heightCm = 171,
                        weightKg = 83.2f,
                    )
                )
            )

        val userInfoAck = HuaweiHagridWspLib.encryptedPayloadWithIv(
            byteArrayOf(0x00),
            workKey,
            ByteArray(16) { (it + 128).toByte() }
        )
        sendWspNotification(handler, CHR_SET_USER_INFO, userInfoAck, encrypted = true)

        assertThat(transport.writes.map { it.characteristic })
            .containsAtLeast(
                CHR_CURRENT_TIME,
                CHR_SET_USER_INFO,
                CHR_PRODUCT_INFO,
                CHR_GET_MANAGER_INFO,
                CHR_SCALE_VERSION,
                CHR_GET_WEIGHT_UNIT,
                CHR_MEASUREMENT_STATUS_POLL,
            )
        assertThat(transport.writes.map { it.characteristic }).doesNotContain(CHR_HISTORY_WEIGHT)
        assertThat(transport.writes.map { it.characteristic }).doesNotContain(CHR_REALTIME_WEIGHT)

        sendWspNotification(handler, CHR_MEASUREMENT_STATUS_RESULT, byteArrayOf(0x00))

        assertThat(transport.writes.map { it.characteristic })
            .containsAtLeast(CHR_HISTORY_WEIGHT, CHR_REALTIME_WEIGHT)
    }

    private fun device(
        name: String,
        address: String = "00:11:22:33:44:55",
        services: IntArray = intArrayOf(),
        manufacturerData: SparseArray<ByteArray>? = null,
        serviceData: Map<UUID, ByteArray> = emptyMap()
    ) = ScannedDeviceInfo(
        name = name,
        address = address,
        rssi = -50,
        serviceUuids = services.map { uuid16(it) },
        manufacturerData = manufacturerData,
        serviceData = serviceData,
    )

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun sendWspNotification(
        handler: HuaweiHagridWspHandler,
        characteristic: UUID,
        payload: ByteArray,
        encrypted: Boolean = false
    ) {
        HuaweiHagridWspLib.buildWriteFrames(payload, encrypted).forEach { writeFrame ->
            val notifyFrame = writeFrame.copyOf()
            notifyFrame[0] = (
                if (encrypted) {
                    HuaweiHagridWspLib.FRAME_NOTIFY_ENCRYPTED
                } else {
                    HuaweiHagridWspLib.FRAME_NOTIFY_PLAIN
                }
            ).toByte()
            val crc = HuaweiHagridWspLib.crc16Modbus(notifyFrame.copyOfRange(0, notifyFrame.size - 2))
            notifyFrame[notifyFrame.size - 2] = (crc and 0xFF).toByte()
            notifyFrame[notifyFrame.size - 1] = ((crc ushr 8) and 0xFF).toByte()
            handler.handleNotification(characteristic, notifyFrame)
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }

    private fun managerInfoPayload(): ByteArray =
        HuaweiHagridWspLib.buildManagerInfoPayload(
            huid = HAGRID_MANAGER_HUID,
            deviceId = "c55f8f03-1111-4222-8333-aabbccddee49"
        )

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        val infos = mutableListOf<InfoRecord>()

        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement
        }

        override fun onInfo(resId: Int, vararg args: Any) {
            infos += InfoRecord(resId, args.toList())
        }

        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
    }

    private data class InfoRecord(
        val resId: Int,
        val args: List<Any>
    )

    private class NoopTransport : ScaleDeviceHandler.Transport {
        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) = Unit
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = false
    }

    private data class WriteRecord(
        val service: UUID,
        val characteristic: UUID,
        val payload: ByteArray,
        val withResponse: Boolean
    )

    private class CapturingTransport(
        private val available: Set<Pair<UUID, UUID>>
    ) : ScaleDeviceHandler.Transport {
        val writes = mutableListOf<WriteRecord>()
        val notifications = mutableListOf<Pair<UUID, UUID>>()

        override fun setNotifyOn(service: UUID, characteristic: UUID) {
            notifications += service to characteristic
        }

        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            writes += WriteRecord(service, characteristic, payload, withResponse)
        }

        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
            available.contains(service to characteristic)

        fun reassembleWritesTo(characteristic: UUID): ByteArray {
            val chunks = writes
                .filter { it.characteristic == characteristic }
                .sortedBy { it.payload[2].toInt() and 0x0F }
                .flatMap { record ->
                    val len = (record.payload[1].toInt() and 0xFF) - 3
                    record.payload.copyOfRange(3, 3 + len).toList()
                }
            return chunks.toByteArray()
        }

        fun reassembleWriteMessagesTo(characteristic: UUID): List<ByteArray> {
            val messages = mutableListOf<ByteArray>()
            val current = mutableListOf<WriteRecord>()
            var expectedFrames = 0

            writes.filter { it.characteristic == characteristic }.forEach { record ->
                val frame = record.payload
                val totalFrames = ((frame[2].toInt() ushr 4) and 0x0F) + 1
                if (current.isEmpty()) {
                    expectedFrames = totalFrames
                }
                current += record
                if (current.size == expectedFrames) {
                    val chunks = current
                        .sortedBy { it.payload[2].toInt() and 0x0F }
                        .flatMap {
                            val len = (it.payload[1].toInt() and 0xFF) - 3
                            it.payload.copyOfRange(3, 3 + len).toList()
                        }
                    messages += chunks.toByteArray()
                    current.clear()
                    expectedFrames = 0
                }
            }
            return messages
        }

        companion object {
            fun allHagrid(): CapturingTransport =
                CapturingTransport(
                    setOf(
                        SVC_USER_DATA to CHR_REQUEST_AUTH,
                        SVC_USER_DATA to CHR_AUTH_TOKEN,
                        SVC_USER_DATA to CHR_SEND_WORK_KEY,
                        SVC_USER_DATA to CHR_BIND_REQUEST,
                        SVC_USER_DATA to CHR_SET_USER_INFO,
                        SVC_USER_DATA to CHR_GET_MANAGER_INFO,
                        SVC_CURRENT_TIME to CHR_CURRENT_TIME,
                        SVC_CURRENT_TIME to CHR_PRODUCT_INFO,
                        SVC_CURRENT_TIME to CHR_SCALE_VERSION,
                        SVC_CURRENT_TIME to CHR_GET_WEIGHT_UNIT,
                        SVC_CURRENT_TIME to CHR_MEASUREMENT_STATUS_POLL,
                        SVC_CURRENT_TIME to CHR_MEASUREMENT_STATUS_RESULT,
                        SVC_BODY_COMPOSITION to CHR_REALTIME_WEIGHT,
                        SVC_BODY_COMPOSITION to CHR_HISTORY_WEIGHT,
                        SVC_WEIGHT_SCALE to CHR_WEIGHT_MEASUREMENT,
                    )
                )
        }
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val values = mutableMapOf<String, String>()

        override fun getInt(key: String, default: Int): Int = values[key]?.toIntOrNull() ?: default
        override fun putInt(key: String, value: Int) {
            values[key] = value.toString()
        }

        override fun getString(key: String, default: String?): String? = values[key] ?: default
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private class FixedDataProvider(
        private val user: ScaleUser,
        private val users: List<ScaleUser> = listOf(user)
    ) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = users
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? = null
    }

    companion object {
        private const val HAGRID_ADDRESS = "0C:95:41:6E:9E:50"
        private const val HAGRID_MANAGER_HUID = "420086000106881907"
        private val SVC_USER_DATA = uuid16Static(0x181C)
        private val SVC_CURRENT_TIME = uuid16Static(0x1805)
        private val SVC_BODY_COMPOSITION = uuid16Static(0x181B)
        private val SVC_WEIGHT_SCALE = uuid16Static(0x181D)
        private val CHR_REQUEST_AUTH = UUID.fromString("02b2a08e-f8b0-4047-b1fd-f4e0efeee679")
        private val CHR_AUTH_TOKEN = UUID.fromString("32330a04-15d9-421a-91c5-2a2d5c7525c9")
        private val CHR_SEND_WORK_KEY = UUID.fromString("a3d330f8-b84f-4f48-a78c-f8d1e33b597a")
        private val CHR_BIND_REQUEST = UUID.fromString("42596cbe-d291-4da3-8ca6-d1ae5d1c9174")
        private val CHR_SET_USER_INFO = UUID.fromString("8cc61d7d-66c0-4802-89c3-38c5a163592e")
        private val CHR_GET_MANAGER_INFO = UUID.fromString("4338c65e-ed8e-4085-bbea-a25e33ca6b54")
        private val CHR_CURRENT_TIME = uuid16Static(0x2A2B)
        private val CHR_PRODUCT_INFO = UUID.fromString("75143e79-f878-4a00-a628-edc40509de7e")
        private val CHR_SCALE_VERSION = UUID.fromString("1f5d3d5c-496d-4290-af03-c7a8d5419741")
        private val CHR_GET_WEIGHT_UNIT = UUID.fromString("7e6dbc73-42e7-45b9-a6ec-6aa2d7834695")
        private val CHR_MEASUREMENT_STATUS_POLL = UUID.fromString("bfc36f6e-4150-4a4b-9052-3d359e52962e")
        private val CHR_MEASUREMENT_STATUS_RESULT = UUID.fromString("ba216311-1787-472b-bef6-3eb29e62293e")
        private val CHR_WEIGHT_MEASUREMENT = uuid16Static(0x2A9D)
        private val CHR_REALTIME_WEIGHT = UUID.fromString("46797c17-d639-488d-9476-4789e8472878")
        private val CHR_HISTORY_WEIGHT = UUID.fromString("0212f42a-5f19-4bc1-ba52-d7ec7ccb71a4")

        private fun uuid16Static(short: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

        private fun realtimePayload(): ByteArray =
            byteArrayOf(
                0x34, 0x1E,
                0xB9.toByte(), 0x00,
                0xEA.toByte(), 0x07, 0x06, 0x16, 0x0F, 0x0E, 0x2A, 0x00,
                0xF4.toByte(), 0x01, 0xF5.toByte(), 0x01, 0xF6.toByte(), 0x01,
                0xF7.toByte(), 0x01, 0xF8.toByte(), 0x01, 0xF9.toByte(), 0x01,
                0x48, 0x00,
                0x58, 0x02, 0x59, 0x02, 0x5A, 0x02,
                0x5B, 0x02, 0x5C, 0x02, 0x5D, 0x02,
            )

        private fun historyPayload(completeFlag: Int): ByteArray =
            realtimePayload() + byteArrayOf(0x01, completeFlag.toByte())
    }
}
