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

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
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
    private var pendingBf1000Measurement: ScaleMeasurement? = null

    private val bf1000WeightMeasurement = uuid16(0x2A9D)
    private val bf1000BodyCompositionMeasurement = uuid16(0x2A9C)
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
            activeModel == Model.BEURER_BF1000 && characteristic == bf1000WeightMeasurement -> {
                handleBf1000WeightMeasurement(data)
            }
            activeModel == Model.BEURER_BF1000 && characteristic == bf1000BodyCompositionMeasurement -> {
                handleBf1000BodyCompositionMeasurement(data)
            }
            characteristic == p.chrUserList -> {
                handleUserList(data, user)
            }
            activeModel == Model.BEURER_BF1000 && isBf1000CustomMeasurementCharacteristic(characteristic) -> {
                handleBf1000CustomMeasurementData(characteristic, data)
            }
            else ->
                super.onNotification(characteristic, data, user)
        }
    }

    override fun onDisconnected() {
        if (activeModel == Model.BEURER_BF1000) {
            publishPendingBf1000Measurement("disconnect")
        }

        super.onDisconnected()
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

    private fun handleBf1000WeightMeasurement(data: ByteArray) {
        logD("BF1000 standard weight len=${data.size} ${data.toHexPreview(64)}")
        val decoded = BeurerBf1000Lib.parseWeightMeasurement(data)
        if (decoded == null) {
            logW("BF1000 standard weight packet could not be decoded")
            return
        }

        val measurement = ScaleMeasurement().apply {
            weight = decoded.weightKg
            dateTime = decoded.dateTime
        }

        decoded.scaleUserIndex?.let { scaleUserIndex ->
            val appId = loadUserIdForScaleIndex(scaleUserIndex)
            if (appId != -1) measurement.userId = appId
            logD(
                "BF1000 weight: idx=$scaleUserIndex mappedAppId=$appId " +
                    "kg=${decoded.isKg} value=${measurement.weight}"
            )
        }
        decoded.bmi?.let {
            logD("BF1000 BMI=$it height(m)=${decoded.heightMeters}")
        }

        mergePendingBf1000Measurement(measurement, "standard weight")
    }

    private fun handleBf1000BodyCompositionMeasurement(data: ByteArray) {
        logD("BF1000 standard body composition len=${data.size} ${data.toHexPreview(64)}")
        val decoded = BeurerBf1000Lib.parseBodyCompositionMeasurement(
            data,
            fallbackWeightKg = pendingBf1000Measurement?.weight?.takeIf { it > 0f }
        )
        if (decoded == null) {
            logW("BF1000 standard body composition packet could not be decoded")
            return
        }

        val measurement = ScaleMeasurement().apply {
            fat = decoded.bodyFatPercent
            dateTime = decoded.dateTime
            decoded.bmrKcal?.let { bmr = it }
            decoded.musclePercent?.let { muscle = it }
            decoded.waterMassKg?.let { water = it }
            decoded.impedanceOhm?.let { impedance = it }
            decoded.weightKg?.let { weight = it }
            decoded.leanBodyMassKg?.let { lbm = it }
            decoded.boneMassKg?.let { bone = it }
        }

        decoded.scaleUserIndex?.let { scaleUserIndex ->
            val appId = loadUserIdForScaleIndex(scaleUserIndex)
            if (appId != -1) measurement.userId = appId
            logD("BF1000 body composition: idx=$scaleUserIndex mappedAppId=$appId fat=${measurement.fat}%")
        }
        decoded.bmrKcal?.let { logD("BF1000 BMR ~= $it kcal") }
        decoded.softLeanMassKg?.let { logD("BF1000 soft lean mass=$it kg") }
        decoded.impedanceOhm?.let { logD("BF1000 impedance=$it ohm") }
        decoded.boneMassKg?.let { logD("BF1000 bone mass=$it kg") }
        if (decoded.isMultiPacket) {
            logW("BF1000 body composition: multi-packet measurement not supported")
        }

        mergePendingBf1000Measurement(measurement, "standard body composition")
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
                val measurement = ScaleMeasurement().apply {
                    visceralFat = decoded.visceralFat
                    fatLeftArm = decoded.leftArm
                    fatRightArm = decoded.rightArm
                    fatTorso = decoded.torso
                    fatLeftLeg = decoded.leftLeg
                    fatRightLeg = decoded.rightLeg
                }

                logD(
                    "BF1000 segmental fat: visceral=${measurement.visceralFat} " +
                        "leftArm=${measurement.fatLeftArm}% rightArm=${measurement.fatRightArm}% " +
                        "torso=${measurement.fatTorso}% leftLeg=${measurement.fatLeftLeg}% " +
                        "rightLeg=${measurement.fatRightLeg}%"
                )
                mergePendingBf1000Measurement(measurement, "segmental fat")
            }
            characteristic == bf1000SegmentalMuscleMeasurement -> {
                val decoded = BeurerBf1000Lib.parseSegmentalMuscleMeasurement(data)
                if (decoded == null) {
                    logW("BF1000 segmental muscle packet could not be decoded")
                    return
                }
                val measurement = ScaleMeasurement().apply {
                    muscleLeftArm = decoded.leftArm
                    muscleRightArm = decoded.rightArm
                    muscleTorso = decoded.torso
                    muscleLeftLeg = decoded.leftLeg
                    muscleRightLeg = decoded.rightLeg
                }

                logD(
                    "BF1000 segmental muscle: leftArm=${measurement.muscleLeftArm}% " +
                        "rightArm=${measurement.muscleRightArm}% torso=${measurement.muscleTorso}% " +
                        "leftLeg=${measurement.muscleLeftLeg}% rightLeg=${measurement.muscleRightLeg}%"
                )
                mergePendingBf1000Measurement(measurement, "segmental muscle")
            }
        }

        if (characteristic == bf1000MeasurementStatus &&
            data.firstOrNull()?.toInt()?.and(0xFF) == 0x01) {
            logD("BF1000 measurement-complete status received")
            publishPendingBf1000Measurement("measurement complete")
        }
    }

    private fun isBf1000CustomMeasurementCharacteristic(characteristic: UUID): Boolean =
        characteristic in bf1000CustomMeasurementChars

    private fun mergePendingBf1000Measurement(measurement: ScaleMeasurement, source: String) {
        val current = pendingBf1000Measurement
        if (current == null) {
            pendingBf1000Measurement = measurement
        } else if (canMergeBf1000(current, measurement)) {
            current.mergeWith(measurement)
        } else {
            publishPendingBf1000Measurement("new BF1000 user packet before $source")
            pendingBf1000Measurement = measurement
        }

        pendingBf1000Measurement?.let {
            if (it.weight > 0f && it.fat > 0f && it.lbm <= 0f) {
                it.lbm = it.weight * (1f - it.fat / 100f)
            }
        }

        logD("BF1000 pending after $source: $pendingBf1000Measurement")
    }

    private fun canMergeBf1000(left: ScaleMeasurement, right: ScaleMeasurement): Boolean =
        left.userId == 0xFF ||
            right.userId == 0xFF ||
            left.userId == right.userId

    private fun publishPendingBf1000Measurement(reason: String) {
        val measurement = pendingBf1000Measurement ?: return
        if (!measurement.hasWeight()) {
            logW("Dropping BF1000 pending measurement on $reason because it has no weight: $measurement")
            pendingBf1000Measurement = null
            return
        }

        pendingBf1000Measurement = null
        logD("Publishing BF1000 measurement on $reason: $measurement")
        publish(transformBeforePublish(measurement))
    }

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
}
