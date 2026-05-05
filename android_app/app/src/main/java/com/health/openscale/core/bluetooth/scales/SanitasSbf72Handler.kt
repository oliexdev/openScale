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
package com.health.openscale.core.bluetooth.scales

import android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT16
import android.bluetooth.BluetoothGattCharacteristic.FORMAT_UINT8
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import com.welie.blessed.BluetoothBytesBuilder
import com.welie.blessed.BluetoothBytesParser
import java.util.GregorianCalendar
import java.util.UUID

/**
 * Handler specifically for Sanitas SBF72 and directly compatible Beurer/Sanitas models
 * (e.g., BF720, BF915, SBF73).
 * These models use a custom protocol for user selection and measurement triggering,
 * alongside standard Bluetooth services (WSS, BCS, UDS).
 */
class SanitasSbf72Handler : StandardWeightProfileHandler() {
    private val scaleUserList = mutableListOf<ScaleUser>()

    companion object {
        val SVC_SBF72_CUSTOM: UUID by lazy { UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb") }
        val CHR_SBF72_USER_LIST: UUID by lazy { UUID.fromString("00000001-0000-1000-8000-00805f9b34fb") }
        val CHR_SBF72_INITIALS : UUID by lazy { UUID.fromString("00000002-0000-1000-8000-00805f9b34fb") }
        val CHR_SBF72_ACTIVITY_LEVEL: UUID by lazy { UUID.fromString("00000004-0000-1000-8000-00805f9b34fb") }
        val CHR_SBF72_TAKE_MEASUREMENT: UUID by lazy { UUID.fromString("00000006-0000-1000-8000-00805f9b34fb") }
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.lowercase().orEmpty()

        val isMatchByName =
                name.contains("sbf72") ||
                name.contains("sbf73") ||
                name.contains("bf915")

        if (!isMatchByName) {
            return null
        }

        val identifiedDeviceName = when {
            name.contains("bf915") -> "Beurer BF915"
            name.contains("sbf73") -> "Sanitas SBF73"
            name.contains("sbf72") -> "Sanitas SBF72"
            else                    -> return null
        }

        logI("Device identified as ${identifiedDeviceName} by SanitasSbf72Handler.")

        val capabilities = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.BATTERY_LEVEL
        )

        return DeviceSupport(
            displayName = identifiedDeviceName,
            capabilities = capabilities,
            implemented = capabilities,
            linkMode = LinkMode.CONNECT_GATT
        )
    }


    override fun onConnected(user: ScaleUser) {
        super.onConnected(user)

        logD("Scale connected. Setting up SBF72 custom characteristics for user ${user.id} (${user.userName}).")

        val scaleIndex = findKnownScaleIndexForAppUser(user.id) ?: -1
        if (loadConsentForScaleIndex(scaleIndex) == -1) {
            // Notify on custom user list is already set by base or can be confirmed here
            setNotifyOn(SVC_SBF72_CUSTOM, CHR_SBF72_USER_LIST)

            // Request user list from SBF72's custom characteristic
            writeTo(SVC_SBF72_CUSTOM, CHR_SBF72_USER_LIST, byteArrayOf(0x00.toByte()))
        }

    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        when (characteristic) {
            CHR_SBF72_USER_LIST       -> {
                //the if condition is to catch the response to "display-pin-on-scale", because this response would produce an error in handleVendorSpecificUserList().
                if (data != null && data.size > 0 && data[0].toInt() != 17) {
                    handleSBF72UserList(data, user)
                }
            }
            else ->
                super.onNotification(characteristic, data, user)
        }
    }

    override fun requestScaleUserConsent(appUserId: Int, scaleIndex: Int) {
        //Requests the scale to display the pin for the user in it's display.
        //As parameter we need to send a pin-index to the custom user-list characteristic.
        //For user with index 1 the pin-index is 0x11, for user with index 2 it is 0x12 and so on.
        val scalePinIndex: Int = scaleIndex + 16
        val bleBuilder = BluetoothBytesBuilder()
        bleBuilder.addUInt8(scalePinIndex)
        writeTo(SVC_SBF72_CUSTOM,CHR_SBF72_USER_LIST,bleBuilder.build())

        super.requestScaleUserConsent(appUserId, scaleIndex)
    }

    override fun onRequestMeasurement() {
        val bleBuilder = BluetoothBytesBuilder()
        bleBuilder.addUInt8(0)
        writeTo(SVC_SBF72_CUSTOM, CHR_SBF72_TAKE_MEASUREMENT, bleBuilder.build());
    }

    override fun writeUserDataToScale() {
        val bleBuilder = BluetoothBytesBuilder()
        val activityLevel = currentAppUser().activityLevel.toInt() + 1
        logD(String.format("activityLevel: %d", activityLevel))

        bleBuilder.addUInt8(activityLevel)
        writeTo(SVC_SBF72_CUSTOM,CHR_SBF72_ACTIVITY_LEVEL,bleBuilder.build())

        val raw = currentAppUser().userName.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val initials = raw.take(3)
        if (initials.isNotEmpty()) {
            logD(String.format("initials: %s", initials))
            writeTo(SVC_SBF72_CUSTOM,CHR_SBF72_INITIALS,initials.encodeToByteArray())
        }

        super.writeUserDataToScale()
    }

    private fun handleSBF72UserList(data: ByteArray, user : ScaleUser) {
        val parser = BluetoothBytesParser(data)

        val userListStatus = parser.getUInt8().toInt()

        when (userListStatus) {
            2 -> {
                // Status=2 -> no user on scale
                logD("no user on scale")
                return
            }
            1 -> {
                // Status=1 -> user list complete
                logD( "User-list received")
                val scaleIndex = findKnownScaleIndexForAppUser(user.id) ?: -1
                if (loadConsentForScaleIndex(scaleIndex) == -1) {
                    presentChooseFromUsers(scaleUserList)
                }

                return
            }
            else -> {
                // Normal user data
                val index = parser.getUInt8().toInt()
                var initials = parser.getString()
                val end = if (3 > initials.length) initials.length else 3
                initials = initials.substring(0, end)
                if (initials.length == 3) {
                    if (initials.get(0).code == 0xff && initials.get(1).code == 0xff && initials.get(2).code == 0xff) {
                        initials = "unknown"
                    }
                }
                parser.offset = 5
                val year = parser.getUInt16().toInt()
                val month = parser.getUInt8().toInt()
                val day = parser.getUInt8().toInt()
                val height = parser.getUInt8().toInt()
                val gender = parser.getUInt8().toInt()
                val activityLevel = parser.getUInt8().toInt()

                val calendar = GregorianCalendar(year, month - 1, day)
                val scaleUser = ScaleUser().apply {
                    this.userName = initials
                    this.birthday = calendar.time
                    this.bodyHeight = height.toFloat()
                    this.gender = if (gender == 0) GenderType.MALE else GenderType.FEMALE
                    this.activityLevel = ActivityLevel.fromInt(activityLevel - 1)
                    this.id = index
                }
                scaleUserList.add(scaleUser)
                logD("ScaleUser added: $scaleUser")
            }
        }
    }
}

