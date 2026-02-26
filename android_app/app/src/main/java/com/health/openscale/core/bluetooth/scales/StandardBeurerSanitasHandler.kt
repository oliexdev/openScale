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

import androidx.datastore.preferences.core.PreferencesSerializer.writeTo
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.scales.SanitasSbf72Handler.Companion.CHR_SBF72_USER_LIST
import com.health.openscale.core.bluetooth.scales.SanitasSbf72Handler.Companion.SVC_SBF72_CUSTOM
import com.health.openscale.core.data.ActivityLevel
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import com.welie.blessed.BluetoothBytesParser
import java.nio.ByteBuffer
import java.util.GregorianCalendar
import java.util.UUID

/**
 * Unified vendor handler for Beurer models.
 * Uses the existing consent UI from the base handler.
 */
class StandardBeurerSanitasHandler : StandardWeightProfileHandler() {

    private enum class Model { BEURER_BF105, BEURER_BF950, BEURER_BF500, BEURER_BF600 }
    private val scaleUserList = mutableListOf<ScaleUser>()

    private data class Profile(
        val service: UUID,
        val chrUserList: UUID,
        val chrActivity: UUID?,          // null => not supported
        val chrTakeMeasurement: UUID,
        val chrInitials: UUID?,
        val chrTargetWeight: UUID?
    )

    private var activeModel: Model? = null
    private var friendlyName: String? = null
    private var profile: Profile? = null

    private fun pFor(m: Model) = when (m) {
        Model.BEURER_BF105 -> Profile(
            service = uuid16(0xFFFF),
            chrUserList = uuid16(0x0001),
            chrActivity = uuid16(0x0004),
            chrTakeMeasurement = uuid16(0x0006),
            chrInitials = uuid16(0x0002),
            chrTargetWeight = uuid16(0x0003)
        )
        Model.BEURER_BF950 -> Profile(
            service = uuid16(0xFFFF),
            chrUserList = uuid16(0x0001),
            chrActivity = uuid16(0x0004),
            chrTakeMeasurement = uuid16(0x0006),
            chrInitials = uuid16(0x0002),
            chrTargetWeight = null
        )
        Model.BEURER_BF500 -> Profile(
            service = uuid16(0xFFFF),
            chrUserList = uuid16(0xFFF1),
            chrActivity = uuid16(0xFFF2),
            chrTakeMeasurement = uuid16(0xFFF4),
            chrInitials = null,
            chrTargetWeight = null
        )
        Model.BEURER_BF600 -> Profile(
            service = uuid16(0xFFF0),
            chrUserList = uuid16(0xFFF2),
            chrActivity = uuid16(0xFFF3),
            chrTakeMeasurement = uuid16(0xFFF4),
            chrInitials = uuid16(0xFFF6), // BF850 initials
            chrTargetWeight = null
        )
    }

    private fun nameFor(m: Model) = when (m) {
        Model.BEURER_BF105 -> "Beurer BF105/720"
        Model.BEURER_BF950 -> "Beurer BF950"
        Model.BEURER_BF500 -> "Beurer BF500"
        Model.BEURER_BF600 -> "Beurer BF600"
    }

    fun driverName(): String = friendlyName ?: "Beurer"

    // Model detection; constructor stays empty.
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.lowercase().orEmpty()

        val model = when {
            "bf105" in name || "bf720" in name -> Model.BEURER_BF105
            "bf950" in name || "sbf77" in name || "sbf76" in name -> Model.BEURER_BF950
            "bf500" in name                    -> Model.BEURER_BF500
            "bf600" in name || "bf850" in name -> Model.BEURER_BF600
            else -> return null
        }

        activeModel = model
        profile = pFor(model)
        friendlyName = nameFor(model)

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.BATTERY_LEVEL,
            DeviceCapability.LIVE_WEIGHT_STREAM
        )
        return DeviceSupport(
            displayName = driverName(),
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // ---- Vendor-specific extras on connect -----------------------------------
    override fun onConnected(user: ScaleUser) {
        super.onConnected(user) // standard UDS/WSS/BCS
        logD("Scale connected: userId=${user.id}, name=${user.userName}")

        val p = profile
        if (p == null) {
            logW("No profile available after connection for userId=${user.id}")
            return
        }

        val scaleIndex = findKnownScaleIndexForAppUser(user.id) ?: -1
        if (loadConsentForScaleIndex(scaleIndex) == -1) {
            profile?.chrUserList?.let { chr ->
                profile?.service?.let { svc ->
                    logD("Setting custom user list notifications on service=${svc} for chrUserList=${chr}")

                    setNotifyOn(svc, chr)
                    writeTo(svc, chr, byteArrayOf(0x00.toByte()))
                }
            }
        }

        p.chrActivity?.let {
            logD("Writing activity level for userId=${user.id} to chrActivity=${it}")
            writeActivityLevel(user)
        }

        p.chrInitials?.let {
            logD("Writing initials for userId=${user.id} to chrInitials=${it}")
            writeInitials(user)
        }

        p.chrTargetWeight?.let {
            logD("Writing target weight for userId=${user.id} to chrTargetWeight=${it}")
            writeTargetWeight(user)
        }
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        val p = profile
        if (p == null) {
            logW("No profile available after connection for userId=${user.id}")
            return
        }

        when (characteristic) {
            p.chrUserList       -> {
                handleUserList(data, user)
            }
            else ->
                super.onNotification(characteristic, data, user)
        }
    }

    override fun onRequestMeasurement() {
        profile?.let {
            logD("Requesting measurement: writing 0x00 to chrTakeMeasurement=${it.chrTakeMeasurement}")
            writeTo(it.service, it.chrTakeMeasurement, byteArrayOf(0x00))
        } ?: logW("onRequestMeasurement called but profile is null")
    }

    // ---- Vendor write helpers -------------------------------------------------

    private fun handleUserList(data: ByteArray, user : ScaleUser) {
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
                logD("User-list received")
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
                    if (initials.get(0).code == 0xff && initials.get(1).code == 0xff && initials.get(
                            2
                        ).code == 0xff
                    ) {
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

    private fun writeActivityLevel(user: ScaleUser) {
        val lvl = (user.activityLevel.toInt() + 1).coerceIn(1, 5)
        profile?.chrActivity?.let { chr ->
            profile?.service?.let { svc ->
                writeTo(svc, chr, byteArrayOf(lvl.toByte()))
            }
        }
    }

    private fun writeInitials(user: ScaleUser) {
        val raw = user.userName?.uppercase()?.replace(Regex("[^A-Z0-9]"), "").orEmpty()
        val initials = raw.take(3)
        if (initials.isNotEmpty()) {
            profile?.chrInitials?.let { chr ->
                profile?.service?.let { svc ->
                    writeTo(svc, chr, initials.encodeToByteArray())
                }
            }
        }
    }

    private fun writeTargetWeight(user: ScaleUser) {
        val goal = user.goalWeight.toInt()
        val bb = ByteBuffer.allocate(2).apply {
            put(((goal ushr 8) and 0xFF).toByte())
            put((goal and 0xFF).toByte())
        }
        profile?.chrTargetWeight?.let { chr ->
            profile?.service?.let { svc ->
                writeTo(svc, chr, bb.array())
            }
        }
    }
}
