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

import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.BeurerBf1000Lib
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

    private enum class Model { BEURER_BF105, BEURER_BF1000, BEURER_BF950, BEURER_BF500, BEURER_BF600 }
    private val scaleUserList = mutableListOf<ScaleUser>()
    private val bf1000AmbiguousScaleUserList = mutableListOf<ScaleUser>()

    private data class Profile(
        val service: UUID,
        val chrUserList: UUID,
        val chrActivity: UUID?,          // null => not supported
        val chrTakeMeasurement: UUID,
        val chrInitials: UUID?,
        val chrTargetWeight: UUID?
    )

    private data class ParsedBf1000ScaleUser(
        val scaleUser: ScaleUser,
        val authoritativeSlot: Boolean
    )

    private var activeModel: Model? = null
    private var friendlyName: String? = null
    private var profile: Profile? = null

    // BF1000 private measurement characteristics on the Beurer FFFF service:
    // 0006 reports measurement-complete status, 0009 carries visceral/segmental
    // fat, and 000A carries segmental muscle. Segmental values are decoded and
    // logged only until openScale has a generic/custom BLE measurement path.
    private val bf1000MeasurementStatus = uuid16(0x0006)
    private val bf1000SegmentalFatMeasurement = uuid16(0x0009)
    private val bf1000SegmentalMuscleMeasurement = uuid16(0x000A)
    private val bf1000CustomMeasurementChars = listOf(
        bf1000MeasurementStatus,
        bf1000SegmentalFatMeasurement,
        bf1000SegmentalMuscleMeasurement
    )

    private fun pFor(m: Model) = when (m) {
        Model.BEURER_BF105,
        Model.BEURER_BF1000 -> Profile(
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
        Model.BEURER_BF1000 -> "Beurer BF1000"
        Model.BEURER_BF950 -> "Beurer BF950"
        Model.BEURER_BF500 -> "Beurer BF500"
        Model.BEURER_BF600 -> "Beurer BF600"
    }

    fun driverName(): String = friendlyName ?: "Beurer"

    // Model detection; constructor stays empty.
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase()

        val model = when {
            "bf105" in name || "bf720" in name -> Model.BEURER_BF105
            "bf1000" in name                   -> Model.BEURER_BF1000
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

                    if (activeModel == Model.BEURER_BF1000) {
                        scaleUserList.clear()
                        bf1000AmbiguousScaleUserList.clear()
                    }
                    setNotifyOn(svc, chr)
                    writeTo(svc, chr, byteArrayOf(0x00.toByte()))
                }
            }
        }

        if (activeModel == Model.BEURER_BF1000) {
            enableBf1000CustomMeasurements()
        }
    }

    override fun writeUserDataToScale() {
        super.writeUserDataToScale() // standard UDS writes (DOB, gender, height, change increment)

        val user = currentAppUser()
        val p = profile ?: return

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

        when {
            characteristic == p.chrUserList -> {
                if (activeModel == Model.BEURER_BF1000) {
                    handleBf1000UserList(data, user)
                } else {
                    handleUserList(data, user)
                }
            }
            activeModel == Model.BEURER_BF1000 && isBf1000CustomMeasurementCharacteristic(characteristic) -> {
                handleBf1000CustomMeasurementData(characteristic, data)
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

    private fun enableBf1000CustomMeasurements() {
        val p = profile ?: return

        logD("Enabling BF1000 custom measurement characteristics")
        bf1000CustomMeasurementChars.forEach { chr ->
            setNotifyOn(p.service, chr)
        }
    }

    private fun handleBf1000CustomMeasurementData(characteristic: UUID, data: ByteArray) {
        logD("BF1000 custom chr=${characteristic.shortId()} len=${data.size} ${data.toHexPreview(64)}")

        when {
            characteristic == bf1000SegmentalFatMeasurement -> {
                val decoded = BeurerBf1000Lib.parseSegmentalFatMeasurement(data)
                if (decoded == null) {
                    logW("BF1000 segmental fat packet could not be decoded")
                    return
                }

                logD(
                    "BF1000 segmental fat decoded, not persisted: visceral=${decoded.visceralFat} " +
                        "leftArm=${decoded.leftArm}% rightArm=${decoded.rightArm}% " +
                        "torso=${decoded.torso}% leftLeg=${decoded.leftLeg}% " +
                        "rightLeg=${decoded.rightLeg}%"
                )
            }
            characteristic == bf1000SegmentalMuscleMeasurement -> {
                val decoded = BeurerBf1000Lib.parseSegmentalMuscleMeasurement(data)
                if (decoded == null) {
                    logW("BF1000 segmental muscle packet could not be decoded")
                    return
                }

                logD(
                    "BF1000 segmental muscle decoded, not persisted: leftArm=${decoded.leftArm}% " +
                        "rightArm=${decoded.rightArm}% torso=${decoded.torso}% " +
                        "leftLeg=${decoded.leftLeg}% rightLeg=${decoded.rightLeg}%"
                )
            }
        }

        if (characteristic == bf1000MeasurementStatus &&
            data.firstOrNull()?.toInt()?.and(0xFF) == 0x01) {
            logD("BF1000 measurement-complete status received")
        }
    }

    private fun isBf1000CustomMeasurementCharacteristic(characteristic: UUID): Boolean =
        characteristic in bf1000CustomMeasurementChars

    private fun handleUserList(data: ByteArray, user : ScaleUser) {
        val parser = BluetoothBytesParser(data)

        val userListStatus = parser.getUInt8().toInt()

        when (userListStatus) {
            2 -> {
                // Status=2 -> no user on scale; clear any stale mapping and offer registration
                logD("No users on scale, presenting create-only choice")
                val appId = user.id
                findKnownScaleIndexForAppUser(appId)?.let { idx ->
                    saveUserIdForScaleIndex(idx, -1)
                    saveConsentForScaleIndex(idx, -1)
                    logD("Cleared stale mapping for appUserId=$appId at scaleIndex=$idx")
                }
                presentCreateOnlyChoice()
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

    private fun handleBf1000UserList(data: ByteArray, user : ScaleUser) {
        if (data.isEmpty()) {
            logW("Empty user-list packet")
            return
        }

        if (data.size == 1) {
            when (u8(data, 0)) {
                2 -> {
                    if (scaleUserList.isEmpty() && bf1000AmbiguousScaleUserList.isEmpty()) {
                        // Status=2 -> no user on scale; clear any stale mapping and offer registration
                        logD("No users on scale, presenting create-only choice")
                        val appId = user.id
                        findKnownScaleIndexForAppUser(appId)?.let { idx ->
                            saveUserIdForScaleIndex(idx, -1)
                            saveConsentForScaleIndex(idx, -1)
                            logD("Cleared stale mapping for appUserId=$appId at scaleIndex=$idx")
                        }
                        presentCreateOnlyChoice()
                    } else {
                        logD("User-list received")
                        presentBf1000UserListIfConsentMissing(user)
                    }
                    return
                }

                1 -> {
                    // Status=1 -> user list complete
                    logD("User-list received")
                    presentBf1000UserListIfConsentMissing(user)
                    return
                }

                else -> {
                    logW("Unknown user-list status packet: ${data.toHexPreview(16)}")
                    return
                }
            }
        }

        parseBf1000ScaleUser(data)?.let { parsed ->
            if (parsed.authoritativeSlot) {
                upsertScaleUser(parsed.scaleUser)
            } else {
                bf1000AmbiguousScaleUserList += parsed.scaleUser
                logD("ScaleUser deferred: $parsed")
            }
        } ?: logW("User-list entry could not be decoded: ${data.toHexPreview(32)}")
    }

    private fun presentBf1000UserListIfConsentMissing(user: ScaleUser) {
        resolveBf1000AmbiguousScaleUsers()
        val scaleIndex = findKnownScaleIndexForAppUser(user.id) ?: -1
        if (loadConsentForScaleIndex(scaleIndex) == -1) {
            presentChooseFromUsers(scaleUserList.sortedBy { it.id })
        }
    }

    private fun resolveBf1000AmbiguousScaleUsers() {
        if (bf1000AmbiguousScaleUserList.isEmpty()) {
            return
        }

        bf1000AmbiguousScaleUserList.forEach { candidate ->
            if (scaleUserList.any { it.hasSameUserProfileAs(candidate) }) {
                logD("ScaleUser deferred duplicate ignored: $candidate")
                return@forEach
            }

            val targetIndex = listOf(candidate.id, candidate.id + 1)
                .filter { it in 1..255 }
                .distinct()
                .firstOrNull { slot -> scaleUserList.none { it.id == slot } }

            if (targetIndex != null) {
                upsertScaleUser(candidate.copy(id = targetIndex))
            } else {
                logW("ScaleUser deferred entry could not be assigned to a free slot: $candidate")
            }
        }

        bf1000AmbiguousScaleUserList.clear()
    }

    private fun parseBf1000ScaleUser(data: ByteArray): ParsedBf1000ScaleUser? {
        if (data.size < 12) {
            logW("User-list entry too short: len=${data.size} ${data.toHexPreview(32)}")
            return null
        }

        val authoritativeSlot = u8(data, 1) > 0
        val index = when {
            // Existing Beurer/Sanitas records use [kind, slot, initials...].
            authoritativeSlot -> u8(data, 1)
            // BF1000 captures also show [slot-like, 00, initials...] records.
            // They duplicate profile data, but their slot byte is not always
            // authoritative, so they are resolved after the list is complete.
            u8(data, 0) > 0 && u8(data, 1) == 0 -> u8(data, 0)
            else -> {
                logW("User-list entry has unknown slot layout: ${data.toHexPreview(32)}")
                return null
            }
        }

        val rawInitials = data.copyOfRange(2, 5)
        val initials = if (rawInitials.all { it == 0xFF.toByte() }) {
            "unknown"
        } else {
            String(rawInitials, Charsets.US_ASCII)
                .filter { it.isLetterOrDigit() }
                .take(3)
                .ifEmpty { "unknown" }
        }

        val year = u16le(data, 5)
        val month = u8(data, 7)
        val day = u8(data, 8)
        val height = u8(data, 9)
        val gender = u8(data, 10)
        val activityLevel = u8(data, 11)

        val calendar = GregorianCalendar(year, month - 1, day)
        val scaleUser = ScaleUser().apply {
            this.userName = initials
            this.birthday = calendar.time
            this.bodyHeight = height.toFloat()
            this.gender = if (gender == 0) GenderType.MALE else GenderType.FEMALE
            this.activityLevel = ActivityLevel.fromInt(activityLevel - 1)
            this.id = index
        }

        return ParsedBf1000ScaleUser(
            scaleUser = scaleUser,
            authoritativeSlot = authoritativeSlot
        )
    }

    private fun upsertScaleUser(scaleUser: ScaleUser) {
        val existingIndex = scaleUserList.indexOfFirst { it.id == scaleUser.id }
        if (existingIndex >= 0) {
            scaleUserList[existingIndex] = scaleUser
            logD("ScaleUser updated: $scaleUser")
        } else {
            scaleUserList.add(scaleUser)
            logD("ScaleUser added: $scaleUser")
        }
    }

    private fun ScaleUser.hasSameUserProfileAs(other: ScaleUser): Boolean =
        birthday == other.birthday &&
            bodyHeight == other.bodyHeight &&
            gender == other.gender &&
            activityLevel == other.activityLevel

    private fun writeActivityLevel(user: ScaleUser) {
        val lvl = (user.activityLevel.toInt() + 1).coerceIn(1, 5)
        profile?.chrActivity?.let { chr ->
            profile?.service?.let { svc ->
                writeTo(svc, chr, byteArrayOf(lvl.toByte()))
            }
        }
    }

    private fun writeInitials(user: ScaleUser) {
        val raw = user.userName.uppercase().replace(Regex("[^A-Z0-9]"), "")
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

    private fun UUID.shortId(): String =
        String.format("0x%04x", (mostSignificantBits shr 32) and 0xFFFF)

    private fun u8(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
