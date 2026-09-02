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
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The USER_INFO record as it actually leaves the handler.
 *
 * [HuaweiAhCh100HandlerTest] calls the companion primitives directly. That
 * cannot catch a handler which stops calling them correctly: a test that
 * builds a frame with `explicitLen = 14` and then asserts the frame says 14
 * is green no matter what `sendUserInfo` does. These tests drive the real
 * state machine instead — attach, connect, wake, auth — and decode the bytes
 * the handler hands to the transport.
 *
 * Two properties are pinned here, both from issue #1449:
 *
 * 1. The record never carries weight 0. `initialWeight` is `0f` on a freshly
 *    created profile, and the in-session weight is unset until the first
 *    measurement, so the naive fallback sent a user who weighs nothing.
 * 2. The length byte declares 14 while 16 bytes are transmitted. openScale
 *    2.5.4 did this — `BluetoothHuaweiAH100.java:524` at tag `v2.5.4` passes
 *    the literal `14` for a payload it builds as 16 bytes, and
 *    `AHsendEncryptedCommand` writes that as `{0xDC, len + 0, cmd}`. The
 *    trailing `0x1C 0xE2` is transmitted but not counted. The 3.x port pulled
 *    the trailer into the payload and moved the length byte to 16 with it.
 *
 * Both handlers get the same treatment: the weight fallback is duplicated in
 * [HuaweiAhCh100Handler] and [HuaweiCH100SHandler], and two copies drift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HuaweiUserRecordWireTest {

    companion object {
        private const val TEST_MAC = "00:11:22:33:44:55"
        private const val TEST_USER_ID = 1

        /**
         * Mirrors the crypto constants in both handlers. Duplicated on purpose:
         * a test that imports the key from the code under test cannot notice
         * the code changing it.
         */
        private const val AES_KEY_HEX = "3DA2784AFB87B12A980FDE3456732156"
        private const val AES_IV_HEX = "4EF764322FDA7632123DEB8790FEA219"

        private const val FRAME_ENCRYPTED = 0xDC.toByte()
        private const val FRAME_NOTIFY_PLAIN = 0xBD.toByte()
        private const val CMD_USER_INFO = 9.toByte()
        private const val OP_WAKEUP = 0x00.toByte()
        private const val OP_AUTH_RESULT = 0x26.toByte()
    }

    // -- AH100 / CH100 -------------------------------------------------------

    @Test
    fun `AhCh100 - declares 14 while transmitting 16, as v2_5_4 and the vendor app do`() {
        val frame = driveAhCh100ToUserInfo(attachedAhCh100())

        assertThat(frame[0]).isEqualTo(FRAME_ENCRYPTED)
        assertThat(frame[1]).isEqualTo(0x0E.toByte())
        assertThat(frame[2]).isEqualTo(CMD_USER_INFO)
        assertThat(frame.size).isEqualTo(3 + 16)
    }

    @Test
    fun `AhCh100 - the two uncounted trailer bytes are still on the wire`() {
        // The point of the length byte fix is that the trailer is *excluded
        // from the count*, not dropped. Sending 14 bytes would be a different
        // record than the vendor app sends.
        val plain = decodeAhCh100Record(driveAhCh100ToUserInfo(attachedAhCh100()))

        assertThat(plain).hasLength(16)
        assertThat(plain[14]).isEqualTo(0x1C.toByte())
        assertThat(plain[15]).isEqualTo(0xE2.toByte())
    }

    @Test
    fun `AhCh100 - a fresh profile is never sent as weighing nothing`() {
        // The defect reported in #1449, at the point where it goes on the wire.
        val setup = attachedAhCh100(
            user = profile(initialWeight = 0f, bodyHeight = 180f),
            previous = null,
        )

        val weightTenthKg = weightFieldOf(decodeAhCh100Record(driveAhCh100ToUserInfo(setup)))

        assertThat(weightTenthKg).isNotEqualTo(0)
        // BMI 22 at 1.80 m — wrong, but a body. Replaced by the first reading.
        assertThat(weightTenthKg).isEqualTo(712)
    }

    @Test
    fun `AhCh100 - a stored measurement beats the BMI estimate`() {
        val setup = attachedAhCh100(
            user = profile(initialWeight = 0f, bodyHeight = 180f),
            previous = ScaleMeasurement(
                userId = TEST_USER_ID,
                dateTime = Date(0L),
            ).apply { this[MeasurementType.WEIGHT] = Kg(80.0f) },
        )

        val plain = decodeAhCh100Record(driveAhCh100ToUserInfo(setup))

        assertThat(weightFieldOf(plain)).isEqualTo(800)
        assertThat(plain[8].toInt() and 0xFF).isEqualTo(180)
    }

    @Test
    fun `AhCh100 - a profile without a height still gets a body`() {
        // bodyHeight defaults to -1f, so the BMI estimate has nothing to work
        // with. It still must not fall back to 0.
        val setup = attachedAhCh100(
            user = profile(initialWeight = 0f, bodyHeight = -1f),
            previous = null,
        )

        assertThat(weightFieldOf(decodeAhCh100Record(driveAhCh100ToUserInfo(setup)))).isEqualTo(700)
    }

    // -- CH100S --------------------------------------------------------------

    @Test
    fun `CH100S - a fresh profile is never sent as weighing nothing`() {
        val setup = attachedCh100s(
            user = profile(initialWeight = 0f, bodyHeight = 180f),
            previous = null,
        )

        val weightTenthKg = weightFieldOf(decodeCh100sRecord(driveCh100sToUserInfo(setup)))

        assertThat(weightTenthKg).isNotEqualTo(0)
        assertThat(weightTenthKg).isEqualTo(712)
    }

    @Test
    fun `CH100S - already declares its full payload, which carries no trailer`() {
        // This handler builds a 14-byte record without the 0x1C 0xE2 trailer,
        // so `payload.size` is already the number the vendor app declares. It
        // needs no explicitLen — pinned so nobody "fixes" it to match the
        // sibling handler and breaks it.
        val frame = driveCh100sToUserInfo(attachedCh100s())

        assertThat(frame[0]).isEqualTo(FRAME_ENCRYPTED)
        assertThat(frame[1]).isEqualTo(0x0E.toByte())
        assertThat(frame[2]).isEqualTo(CMD_USER_INFO)
        assertThat(frame.size).isEqualTo(3 + 14)
    }

    // -- Driving the handlers ------------------------------------------------

    private fun attachedAhCh100(
        user: ScaleUser = profile(),
        previous: ScaleMeasurement? = null,
    ): Setup<HuaweiAhCh100Handler> = attach(HuaweiAhCh100Handler(), "CH100", user, previous)

    private fun attachedCh100s(
        user: ScaleUser = profile(),
        previous: ScaleMeasurement? = null,
    ): Setup<HuaweiCH100SHandler> = attach(HuaweiCH100SHandler(), "CH100S", user, previous)

    private fun <H : ScaleDeviceHandler> attach(
        handler: H,
        advertName: String,
        user: ScaleUser,
        previous: ScaleMeasurement?,
    ): Setup<H> {
        // supportFor() is where both handlers latch the scale MAC they need for
        // the XOR obfuscation; skipping it makes every frame a no-op XOR.
        val support = handler.supportFor(
            ScannedDeviceInfo(
                name = advertName,
                address = TEST_MAC,
                rssi = -50,
                serviceUuids = emptyList(),
                manufacturerData = null,
            )
        )
        assertThat(support).isNotNull()

        val transport = CapturingTransport()
        handler.attach(
            transport = transport,
            callbacks = SilentCallbacks(),
            settings = InMemorySettings(),
            data = FixedDataProvider(user, previous),
            scope = CoroutineScope(EmptyCoroutineContext),
        )
        return Setup(handler, transport, user)
    }

    /** Run connect -> wake -> auth-ok and return the USER_INFO frame. */
    private fun driveAhCh100ToUserInfo(setup: Setup<HuaweiAhCh100Handler>): ByteArray =
        setup.drive()

    private fun driveCh100sToUserInfo(setup: Setup<HuaweiCH100SHandler>): ByteArray =
        setup.drive()

    private fun <H : ScaleDeviceHandler> Setup<H>.drive(): ByteArray {
        handler.handleConnected(user)
        handler.handleNotification(NOTIFY_CHARACTERISTIC, notification(OP_WAKEUP))
        handler.handleNotification(NOTIFY_CHARACTERISTIC, notification(OP_AUTH_RESULT, 0x01))
        return transport.writes.last { it.isNotEmpty() && it[0] == FRAME_ENCRYPTED }
    }

    /** Scale->host frame: `[0xBD, len, op, ...tail XOR'd with the MAC...]`. */
    private fun notification(op: Byte, vararg tail: Byte): ByteArray {
        val body = if (tail.isEmpty()) byteArrayOf(0x01) else tail
        return byteArrayOf(FRAME_NOTIFY_PLAIN, body.size.toByte(), op) + macXor(body)
    }

    // -- Decoding what was sent ----------------------------------------------

    /** `[0xDC, len, cmd] || macXor(AES(payload))` — the AH100/CH100 order. */
    private fun decodeAhCh100Record(frame: ByteArray): ByteArray {
        val magicKey = HuaweiAhCh100Handler.deriveMagicKey(
            HuaweiAhCh100Handler.buildAuthToken(TEST_USER_ID),
            HuaweiAhCh100Handler.macStringToBytes(TEST_MAC),
        )
        return aes(macXor(frame.copyOfRange(3, frame.size)), magicKey)
    }

    /** `[0xDC, len, cmd] || AES(macXor(payload))` — the CH100S order. */
    private fun decodeCh100sRecord(frame: ByteArray): ByteArray =
        macXor(aes(frame.copyOfRange(3, frame.size), hex(AES_KEY_HEX)))

    /** Weight is a little-endian tenth-kg field at offset 10 in both records. */
    private fun weightFieldOf(plain: ByteArray): Int =
        (plain[10].toInt() and 0xFF) or ((plain[11].toInt() and 0xFF) shl 8)

    private fun macXor(data: ByteArray): ByteArray =
        HuaweiAhCh100Handler.obfuscate(data, HuaweiAhCh100Handler.macStringToBytes(TEST_MAC))

    private fun aes(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(hex(AES_IV_HEX)),
        )
        return cipher.doFinal(data)
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // -- Fixtures and fakes --------------------------------------------------

    private fun profile(
        initialWeight: Float = 0f,
        bodyHeight: Float = 180f,
    ): ScaleUser = ScaleUser(
        id = TEST_USER_ID,
        birthday = Date(0L),
        bodyHeight = bodyHeight,
        initialWeight = initialWeight,
    )

    private data class Setup<H : ScaleDeviceHandler>(
        val handler: H,
        val transport: CapturingTransport,
        val user: ScaleUser,
    )

    private class CapturingTransport : ScaleDeviceHandler.Transport {
        val writes = mutableListOf<ByteArray>()

        override fun setNotifyOn(service: UUID, characteristic: UUID) = Unit
        override fun write(
            service: UUID,
            characteristic: UUID,
            payload: ByteArray,
            withResponse: Boolean,
        ) {
            writes += payload.copyOf()
        }

        override fun read(service: UUID, characteristic: UUID) = Unit
        override fun disconnect() = Unit
        override fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean = true
    }

    private class SilentCallbacks : ScaleDeviceHandler.Callbacks {
        override fun onPublish(measurement: ScaleMeasurement) = Unit
        override fun resolveString(resId: Int, vararg args: Any): String = "res:$resId"
    }

    private class InMemorySettings : ScaleDeviceHandler.DriverSettings {
        private val ints = mutableMapOf<String, Int>()
        private val strings = mutableMapOf<String, String>()

        override fun getInt(key: String, default: Int): Int = ints[key] ?: default
        override fun putInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun getString(key: String, default: String?): String? = strings[key] ?: default
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun remove(key: String) {
            ints.remove(key)
            strings.remove(key)
        }
    }

    private class FixedDataProvider(
        private val user: ScaleUser,
        private val previous: ScaleMeasurement?,
    ) : ScaleDeviceHandler.DataProvider {
        override fun currentUser(): ScaleUser = user
        override fun usersForDevice(): List<ScaleUser> = listOf(user)
        override fun lastMeasurementFor(userId: Int): ScaleMeasurement? =
            previous?.takeIf { userId == user.id }
    }
}

private val NOTIFY_CHARACTERISTIC: UUID =
    UUID.fromString("0000faa2-0000-1000-8000-00805f9b34fb")
