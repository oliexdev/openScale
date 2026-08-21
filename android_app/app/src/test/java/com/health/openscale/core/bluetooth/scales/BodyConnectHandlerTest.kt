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

import com.google.common.truth.Truth.assertThat
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.WeightUnit
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.UUID

/**
 * Tests for [BodyConnectHandler].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BodyConnectHandlerTest {

    private val CHR_UPLD = uuid16(0x8A82)
    private val CHR_WEIGHT = uuid16(0x8A24)
    private val CHR_BODY = uuid16(0x8A22)
    private val CMD_PASSWORD: Byte = 0xA0.toByte()
    private val CMD_CHALLENGE: Byte = 0xA1.toByte()
    private val CMD_SLOT_STATUS: Byte = 0x83.toByte()
    private val OP_WEIGHT: Byte = 0x1F
    private val OP_BODY: Byte = 0x7f
    private val KEY_PASSWORD = "bodyconnect/password"
    private val PASSWORD: Int = 0x1234_5678
    private val CHALLENGE: Long = 0xDEAD_BEEF
    private val RESOLVED_STRING_SLOT = "res:${R.string.bluetooth_scale_weightgurus_slot}"
    private val RESOLVED_STRING_SLOT_MATCH = "res:${R.string.bluetooth_scale_weightgurus_slot_match}"

    private val dispatcher = StandardTestDispatcher()

    private typealias SlotSelectionData = Pair<Array<CharSequence>, IntArray>

    private val user = ScaleUser(
        id = 1,
        userName = "Test User",
        birthday = Date(769238400000L),
        bodyHeight = 175f,
        gender = GenderType.MALE,
        initialWeight = 80f,
        goalWeight = 70f,
        scaleUnit = WeightUnit.KG,
        activityLevel = ActivityLevel.MODERATE,
    )

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        val strings = mutableMapOf<String, String>()
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

    private class FixedDataProvider(
        private val user: ScaleUser,
        private val previous: ScaleMeasurement? = null,
    ) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? =
            previous?.takeIf { userId == user.id }
    }

    private fun setupHandler(
        settings: InMemorySettings = InMemorySettings(),
    ): Setup {
        val handler = BodyConnectHandler()
        val transport = CapturingTransport()
        val callbacks = CapturingCallbacks()
        handler.attach(transport, callbacks, settings, FixedDataProvider(user), CoroutineScope(dispatcher))
        return Setup(handler, transport, callbacks, settings, user)
    }

    private fun setupHandlerConnected(
        settings: InMemorySettings = InMemorySettings(),
    ): Setup {
        val setup = setupHandler(settings)
        setup.handler.handleConnected(user)
        return setup
    }

    // --- Device matching --------------------------------------------------------

    @Test
    fun recognizeBodyConnectDeviceWithPrefix1() {
        val handler = BodyConnectHandler()
        val support = handler.supportFor(device("1BODY CONNECT Smart Scale"))
        assertThat(support).isNotNull()
        assertThat(support?.displayName).isEqualTo("1BODY CONNECT")
        assertThat(support?.implemented).containsExactly(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
        )
    }

    @Test
    fun recognizeBodyConnectDeviceWithPrefix0() {
        val handler = BodyConnectHandler()
        val support = handler.supportFor(device("0BODY CONNECT Scale"))
        assertThat(support).isNotNull()
        assertThat(support?.displayName).isEqualTo("1BODY CONNECT")
    }

    @Test
    fun recognizeXLineDeviceWithPrefix0() {
        val handler = BodyConnectHandler()
        val support = handler.supportFor(device("0X-LINE Scale"))
        assertThat(support).isNotNull()
        assertThat(support?.displayName).isEqualTo("1X-LINE")
    }

    @Test
    fun recognizeXLineDeviceWithPrefix1() {
        val handler = BodyConnectHandler()
        val support = handler.supportFor(device("1X-LINE Scale"))
        assertThat(support).isNotNull()
        assertThat(support?.displayName).isEqualTo("1X-LINE")
    }

    @Test
    fun rejectsOtherDevices() {
        val handler = BodyConnectHandler()
        assertThat(handler.supportFor(device("SomeOther Scale"))).isNull()
    }

    // --- Authentication ---------------------------------------------------------

    @Test
    fun handlesPasswordResponseAndSendsBroadcastID() {
        val settings = InMemorySettings()
        val setup = setupHandlerConnected(settings)

        setup.handler.handleNotification(CHR_UPLD, buildPasswordFrame())

        assertThat(settings.getInt(KEY_PASSWORD).toLong() and 0xFFFFFFFF).isEqualTo(PASSWORD)
        assertThat(setup.transport.writes).hasSize(1)
        assertThat(setup.transport.writes[0].payload[0]).isEqualTo(0x21.toByte())
    }

    @Test
    fun passwordFrameTooShortIsIgnored() {
        val settings = InMemorySettings()
        val setup = setupHandlerConnected(settings)

        val shortPassword = byteArrayOf(CMD_PASSWORD, 0x01.toByte(), 0x02.toByte())

        setup.handler.handleNotification(CHR_UPLD, shortPassword)

        assertThat(settings.getInt(KEY_PASSWORD)).isEqualTo(-1)
    }

    @Test
    fun handlesChallengeResponseWhenNoPassword() {
        val settings = InMemorySettings()
        val setup = setupHandlerConnected(settings)

        setup.handler.handleNotification(CHR_UPLD, buildChallengeFrame())

        assertThat(setup.transport.writes).isEmpty()
    }

    @Test
    fun handlesChallengeResponseWhenPaired() {
        val settings = InMemorySettings().apply {
            putInt(KEY_PASSWORD, PASSWORD)
        }
        val setup = setupHandlerConnected(settings)

        setup.handler.handleNotification(CHR_UPLD, buildChallengeFrame())

        // Handler sends 3 writes: challenge response, profile, and time
        assertThat(setup.transport.writes).hasSize(3)
        assertThat(setup.transport.writes[0].payload[0]).isEqualTo(0x20.toByte()) // CMD_CHALLENGE_RESPONSE
        assertThat(setup.transport.writes[1].payload[0]).isEqualTo(0x51.toByte()) // Profile
        assertThat(setup.transport.writes[2].payload[0]).isEqualTo(0x02.toByte()) // CMD_TIME

        val expectedResponse = CHALLENGE xor PASSWORD.toLong()
        assertThat(setup.transport.writes[0].payload[1].toInt() and 0xFF).isEqualTo(expectedResponse and 0xFF)
        assertThat(setup.transport.writes[0].payload[2].toInt() and 0xFF).isEqualTo(expectedResponse shr 8 and 0xFF)
        assertThat(setup.transport.writes[0].payload[3].toInt() and 0xFF).isEqualTo(expectedResponse shr 16 and 0xFF)
        assertThat(setup.transport.writes[0].payload[4].toInt() and 0xFF).isEqualTo(expectedResponse shr 24 and 0xFF)
    }

    @Test
    fun handlesProfileEchoAndSendsEnableDisconnect() {
        val setup = setupHandlerConnected(InMemorySettings())

        setup.handler.handleNotification(CHR_UPLD, byteArrayOf(0xC0.toByte()))

        assertThat(setup.transport.writes).hasSize(1)
        assertThat(setup.transport.writes[0].payload[0]).isEqualTo(0x22.toByte())
    }

    // --- Slot selection ---------------------------------------------------------

    @Test
    fun handlesSlotStatusAndRequestsSlotFromUser() {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        setup.handler.handleConnected(user)

        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(1, "John"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(2, "Eve"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(3, ""))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(4, ""))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(5, ""))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(6, ""))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(7, ""))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(8, ""))

        assertThat(setup.transport.writes).isEmpty()
        assertThat(setup.callbacks.requestedUserInteractions).hasSize(1)
        assertThat(setup.callbacks.requestedUserInteractions[0].first).isEqualTo(UserInteractionType.CHOOSE_USER)
        val requestedUserInteractionData = setup.callbacks.requestedUserInteractions[0].second as SlotSelectionData
        assertThat(requestedUserInteractionData.first).isEqualTo(Array<CharSequence>(8) { RESOLVED_STRING_SLOT })   // no suggestion
        assertThat(requestedUserInteractionData.second).isEqualTo(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    }

    @Test
    fun handlesSlotStatusAndRequestsSlotFromUserWithMatch() {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        setup.handler.handleConnected(user)

        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(1, "John"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(2, "Eve"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(3, "Test User"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(4, ""))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(5, ""))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(6, ""))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(7, ""))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(8, ""))

        assertThat(setup.transport.writes).isEmpty()
        assertThat(setup.callbacks.requestedUserInteractions).hasSize(1)
        assertThat(setup.callbacks.requestedUserInteractions[0].first).isEqualTo(UserInteractionType.CHOOSE_USER)
        val requestedUserInteractionData = setup.callbacks.requestedUserInteractions[0].second as SlotSelectionData
        assertThat(requestedUserInteractionData.first).isEqualTo(Array<CharSequence>(8) { RESOLVED_STRING_SLOT }.also { it[2] = RESOLVED_STRING_SLOT_MATCH })   // slot 3 suggested
        assertThat(requestedUserInteractionData.second).isEqualTo(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    }

    @Test
    fun handlesSlotStatusAndRequestsSlotFromUserNoEmptySlotSuggestsFirst() {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        setup.handler.handleConnected(user)

        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(1, "John"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(2, "Eve"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(3, "Max"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(4, "Mike"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(5, "Angela"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(6, "Sylvia"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(7, "Alex"))
        setup.handler.handleNotification(CHR_UPLD, buildSlotFrame(8, "John 2"))

        assertThat(setup.transport.writes).isEmpty()
        assertThat(setup.callbacks.requestedUserInteractions).hasSize(1)
        assertThat(setup.callbacks.requestedUserInteractions[0].first).isEqualTo(UserInteractionType.CHOOSE_USER)
        val requestedUserInteractionData = setup.callbacks.requestedUserInteractions[0].second as SlotSelectionData
        assertThat(requestedUserInteractionData.first).isEqualTo(Array<CharSequence>(8) { RESOLVED_STRING_SLOT }.also { it[0] = RESOLVED_STRING_SLOT_MATCH })   // slot 1 suggested
        assertThat(requestedUserInteractionData.second).isEqualTo(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    }

    @Test
    fun handlesSlotChoiceAndSendsProfileTimeAck() = runTest {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        setup.handler.handleConnected(user)

        setup.handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, 0, 3)

        assertThat(setup.transport.writes).hasSize(4)
        assertThat(setup.transport.writes[0].payload[0]).isEqualTo(0x03.toByte())
        assertThat(setup.transport.writes[1].payload[0]).isEqualTo(0x51.toByte())
        assertThat(setup.transport.writes[2].payload[0]).isEqualTo(0x02.toByte())
        assertThat(setup.transport.writes[3].payload[0]).isEqualTo(0x22.toByte())

        // Add user
        assertThat(setup.transport.writes[0].payload[1].toInt()).isEqualTo(3)
        assertThat(setup.transport.writes[0].payload.copyOfRange(2, 18)).isEqualTo("Test User       ".toByteArray())

        // Profile
        assertThat(setup.transport.writes[1].payload[2].toInt()).isEqualTo(3)   // slot
        assertThat(setup.transport.writes[1].payload[3]).isEqualTo(0x01.toByte())   // male
        assertThat(setup.transport.writes[1].payload[4].toInt() and 0xFF).isEqualTo(32)   // age
        assertThat(setup.transport.writes[1].payload[5].toInt() and 0xFF).isEqualTo(175)   // height
        assertThat(setup.transport.writes[1].payload[7]).isEqualTo(0x00)   // activity
        assertThat((setup.transport.writes[1].payload[9].toInt() and 0xFF) or (setup.transport.writes[1].payload[10].toInt() and 0xFF shl 8)).isEqualTo(8000)   // initial weight
    }

    @Test
    fun handlesSlotChoiceInvalidResult() = runTest {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        setup.handler.handleConnected(user)

        setup.handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, 0, 17)

        assertThat(setup.transport.writes).isEmpty()
    }

    @Test
    fun handlesTruncatedUserName() = runTest {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        user.userName = "This is a very long user name that exceeds the maximum allowed length for a slot name"
        setup.handler.handleConnected(user)

        setup.handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, 0, 3)

        assertThat(setup.transport.writes).hasSize(4)
        assertThat(setup.transport.writes[0].payload.copyOfRange(2, 18)).isEqualTo("This is a very l".toByteArray())
    }

    @Test
    fun handlesUserNameWithSpecialCharacters() = runTest {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        user.userName = "User ûýþØ"
        setup.handler.handleConnected(user)

        setup.handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, 0, 3)

        assertThat(setup.transport.writes).hasSize(4)
        assertThat(setup.transport.writes[0].payload.copyOfRange(2, 18)).isEqualTo("User ----       ".toByteArray())
    }

    @Test
    fun handlesSlotChoiceFemaleUser() = runTest {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        user.gender = GenderType.FEMALE
        setup.handler.handleConnected(user)

        setup.handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, 0, 3)

        assertThat(setup.transport.writes).hasSize(4)
        assertThat(setup.transport.writes[1].payload[3]).isEqualTo(0x02.toByte())
    }

    @Test
    fun handlesSlotChoiceHeavyActivity() = runTest {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        user.gender = GenderType.MALE
        user.activityLevel = ActivityLevel.HEAVY
        setup.handler.handleConnected(user)

        setup.handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, 0, 3)

        assertThat(setup.transport.writes).hasSize(4)
        assertThat(setup.transport.writes[1].payload[3]).isEqualTo(0x03.toByte())   // active male
        assertThat(setup.transport.writes[1].payload[7]).isEqualTo(0x01)    // heavey activity
    }

    @Test
    fun handlesSlotChoiceFemaleUserExtremeActivity() = runTest {
        val settings = InMemorySettings()
        val setup = setupHandler(settings)
        user.gender = GenderType.FEMALE
        user.activityLevel = ActivityLevel.EXTREME
        setup.handler.handleConnected(user)

        setup.handler.onUserInteractionFeedback(UserInteractionType.CHOOSE_USER, 0, 3)

        assertThat(setup.transport.writes).hasSize(4)
        assertThat(setup.transport.writes[1].payload[3]).isEqualTo(0x04.toByte())   // active female
        assertThat(setup.transport.writes[1].payload[7]).isEqualTo(0x02)    // extreme activity
    }

    // --- Weight and body composition frame parsing -----------------------------------------

    @Test
    fun rejectsBodyFrameWithoutMatchingWeight() {
        val settings = InMemorySettings().apply {
            putInt(KEY_PASSWORD, PASSWORD)
        }
        val setup = setupHandlerConnected(settings)

        val bodyFrame = buildBodyFrame(524159516L, 176, 616, 427, 360)

        setup.handler.handleNotification(CHR_BODY, bodyFrame)

        assertThat(setup.callbacks.published).isEmpty()
    }

    @Test
    fun parsesValidBodyCompositionFrameWithMatchingWeight() {
        val setup = setupHandlerConnected(InMemorySettings())

        val weightFrame = buildWeightFrame(524159516L, 8020)
        val bodyFrame = buildBodyFrame(524159516L, 176, 616, 427, 36)

        setup.handler.handleNotification(CHR_WEIGHT, weightFrame)
        setup.handler.handleNotification(CHR_BODY, bodyFrame)

        assertThat(setup.callbacks.published).hasSize(1)
        val measurement = setup.callbacks.published.single()
        assertThat(measurement.weight).isWithin(1e-5f).of(80.2f)
        assertThat(measurement.fat).isWithin(1e-5f).of(17.6f)
        assertThat(measurement.water).isWithin(1e-5f).of(61.6f)
        assertThat(measurement.muscle).isWithin(1e-5f).of(42.7f)
        assertThat(measurement.bone).isWithin(1e-5f).of(3.6f)
    }

    @Test
    fun parsesValidBodyCompositionFrameWithAlternativeTimestampReference() {
        val setup = setupHandlerConnected(InMemorySettings())

        val weightFrame = buildWeightFrame(524159516L, 8020)
        val bodyFrame = buildBodyFrame(208540316L, 176, 616, 427, 36)

        setup.handler.handleNotification(CHR_WEIGHT, weightFrame)
        setup.handler.handleNotification(CHR_BODY, bodyFrame)

        assertThat(setup.callbacks.published).hasSize(1)
    }

    @Test
    fun rejectsBodyCompositionFrameWithDifferentTimestamp() {
        val setup = setupHandlerConnected(InMemorySettings())

        val weightFrame = buildWeightFrame(524159516L, 8020)
        val bodyFrame = buildBodyFrame(123456L, 176, 616, 427, 360)

        setup.handler.handleNotification(CHR_WEIGHT, weightFrame)
        setup.handler.handleNotification(CHR_BODY, bodyFrame)

        assertThat(setup.callbacks.published).isEmpty()
    }

    @Test
    fun rejectsBodyFrameWithZeroWeight() {
        val settings = InMemorySettings().apply {
            putInt(KEY_PASSWORD, PASSWORD)
        }
        val setup = setupHandlerConnected(settings)

        val weightFrame = buildWeightFrame(524159516L, 0)
        val bodyFrame = buildBodyFrame(524159516L, 176, 616, 427, 360)

        setup.handler.handleNotification(CHR_WEIGHT, weightFrame)
        setup.handler.handleNotification(CHR_BODY, bodyFrame)

        assertThat(setup.callbacks.published).isEmpty()
    }

    @Test
    fun rejectsTruncatedBodyFrame() {
        val setup = setupHandlerConnected(InMemorySettings())

        val truncated = byteArrayOf(OP_BODY, 0x00.toByte(), 0x00.toByte(), 0x00.toByte())

        setup.handler.handleNotification(CHR_BODY, truncated)

        assertThat(setup.callbacks.published).isEmpty()
    }

    // --- Timestamp conversion ---------------------------------------------------

    @Test
    fun convertsJavaTimeToDeviceTimeAndBackCorrectly() {
        val handler = BodyConnectHandler()
        val javaMs = System.currentTimeMillis()
        val deviceTime = handler.javaTimeToDevice(javaMs)
        val convertedBack = handler.deviceTimeToJava(deviceTime)

        assertThat(convertedBack).isWithin(1000L).of(javaMs)
    }

    // --- Edge cases -------------------------------------------------------------

    @Test
    fun handlesEmptyUploadData() {
        val setup = setupHandlerConnected(InMemorySettings())

        setup.handler.handleNotification(CHR_UPLD, ByteArray(0))

        assertThat(setup.transport.writes).isEmpty()
    }

    @Test
    fun handlesEmptyWeightData() {
        val setup = setupHandlerConnected(InMemorySettings())

        setup.handler.handleNotification(CHR_WEIGHT, ByteArray(0))

        assertThat(setup.callbacks.published).isEmpty()
    }

    @Test
    fun handlesEmptyBodyData() {
        val setup = setupHandlerConnected(InMemorySettings())

        setup.handler.handleNotification(CHR_BODY, ByteArray(0))

        assertThat(setup.callbacks.published).isEmpty()
    }

    @Test
    fun handlesUnknownCharacteristic() {
        val setup = setupHandlerConnected(InMemorySettings())

        val unknownUuid = uuid16(0x9999)
        setup.handler.handleNotification(unknownUuid, byteArrayOf(0x00.toByte()))
    }

    // --- Helper classes ---------------------------------------------------------

    private data class Setup(
        val handler: BodyConnectHandler,
        val transport: CapturingTransport,
        val callbacks: CapturingCallbacks,
        val settings: InMemorySettings,
        val user: ScaleUser,
    )

    private class CapturingTransport : ScaleDeviceHandler.Transport {
        val writes = mutableListOf<Write>()

        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(service: UUID, characteristic: UUID, payload: ByteArray, withResponse: Boolean) {
            writes += Write(service, characteristic, payload.copyOf(), withResponse)
        }
        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun getPeripheral() = null
        override fun hasCharacteristic(service: UUID, characteristic: UUID) = true
    }

    private data class Write(
        val service: UUID,
        val characteristic: UUID,
        val payload: ByteArray,
        val withResponse: Boolean,
    )

    private class CapturingCallbacks : ScaleDeviceHandler.Callbacks {
        val published = mutableListOf<ScaleMeasurement>()
        val requestedUserInteractions = mutableListOf<Pair<UserInteractionType, SlotSelectionData?>>()

        override fun onPublish(measurement: ScaleMeasurement) {
            published += measurement.copy()
        }
        override fun onUserInteractionRequired(interactionType: UserInteractionType, data: Any?) {
            @Suppress("UNCHECKED_CAST")
            requestedUserInteractions += Pair(interactionType, data as? SlotSelectionData)
        }
        override fun resolveString(resId: Int, vararg args: Any) = "res:$resId"
    }

    private fun device(name: String, vararg services: Int) = ScannedDeviceInfo(
        name = name,
        address = "00:11:22:33:44:55",
        rssi = -50,
        serviceUuids = services.map { uuid16(it) },
        manufacturerData = null,
    )

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun buildSlotFrame(slot: Int, name: String): ByteArray {
        val nameArray = ByteArray(16) { 0x20.toByte() }
        name.toByteArray().copyInto(nameArray)
        return byteArrayOf(CMD_SLOT_STATUS, slot.toByte()) + nameArray
    }

    private fun buildPasswordFrame(): ByteArray {
        return byteArrayOf(
            CMD_PASSWORD,
            PASSWORD.toByte(), // byte 0 (LSB)
            (PASSWORD ushr 8 and 0xFF).toByte(), // byte 1
            (PASSWORD ushr 16 and 0xFF).toByte(), // byte 2
            (PASSWORD ushr 24 and 0xFF).toByte(), // byte 3 (MSB)
        )
    }

    private fun buildChallengeFrame(): ByteArray {
        // Challenge in little-endian format (as sent by device)
        return byteArrayOf(
            CMD_CHALLENGE, // CMD_CHALLENGE
            CHALLENGE.toByte(), // byte 0 (LSB)
            (CHALLENGE ushr 8 and 0xFF).toByte(), // byte 1
            (CHALLENGE ushr 16 and 0xFF).toByte(), // byte 2
            (CHALLENGE ushr 24 and 0xFF).toByte(), // byte 3 (MSB)
        )
    }

    private fun buildWeightFrame(timestamp: Long, weight: Int): ByteArray {
        return byteArrayOf(
            OP_WEIGHT,
            (weight and 0xFF).toByte(),
            (weight and 0xFF00 shr 8).toByte(),
            0x00.toByte(), 0xFE.toByte(),
            (timestamp and 0xFF).toByte(),
            (timestamp and 0xFF00 shr 8).toByte(),
            (timestamp and 0xFF0000 shr 16).toByte(),
            (timestamp and 0xFF000000 shr 24).toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0xB2.toByte(), 0x13.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0x01.toByte(), 0x19.toByte(), 0x00.toByte(),
        )
    }

    private fun buildBodyFrame(timestamp: Long, fat: Int, water: Int, muscle: Int, bone: Int): ByteArray {
        return byteArrayOf(
            OP_BODY,
            (timestamp and 0xFF).toByte(),
            (timestamp and 0xFF00 shr 8).toByte(),
            (timestamp and 0xFF0000 shr 16).toByte(),
            (timestamp and 0xFF000000 shr 24).toByte(),
            0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
            (fat and 0xFF).toByte(),
            (fat and 0xFF00 shr 8 or 0xF0).toByte(),
            (water and 0xFF).toByte(),
            (water and 0xFF00 shr 8 or 0xF0).toByte(),
            0x00.toByte(), 0xF0.toByte(),
            (muscle and 0xFF).toByte(),
            (muscle and 0xFF00 shr 8 or 0xF0).toByte(),
            (bone and 0xFF).toByte(),
            (bone and 0xFF00 shr 8 or 0xF0).toByte(),
            0x00.toByte(), 0x00.toByte(),
        )
    }
}
