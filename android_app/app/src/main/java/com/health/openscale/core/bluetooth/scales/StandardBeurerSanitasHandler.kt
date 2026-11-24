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

import com.health.openscale.R
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Percent
import com.health.openscale.core.data.MeasurementTypeIcon
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.BeurerBf1000Lib
import com.health.openscale.core.bluetooth.libs.BeurerBf105Lib
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

    // Segmental values buffered until the measurement they belong to is published.
    private val pendingSegmentals = LinkedHashMap<MeasurementType.Key<Percent>, Percent>()
    private var pendingVisceralFat = 0f

    // BF1000 private measurement characteristics on the Beurer FFFF service:
    // 0006 reports measurement-complete status, 0009 carries visceral/segmental
    // fat, and 000A carries segmental muscle.
    private val bf1000MeasurementStatus = uuid16(0x0006)
    private val bf1000SegmentalFatMeasurement = uuid16(0x0009)
    private val bf1000SegmentalMuscleMeasurement = uuid16(0x000A)
    private val bf1000CustomMeasurementChars = listOf(
        bf1000MeasurementStatus,
        bf1000SegmentalFatMeasurement,
        bf1000SegmentalMuscleMeasurement
    )

    // BF105 private characteristic on the Beurer FFFF service: 0008 carries one half-body frame
    // per recorded weigh-in, with the upper/lower body fat and muscle percentages.
    private val bf105HalfBodyMeasurement = uuid16(0x0008)

    // The two characteristics StandardWeightProfileHandler decodes, watched here to keep a
    // weigh-in open until the special values on the private characteristics have been read.
    private val chrWeightMeasurement = uuid16(0x2A9D)
    private val chrBodyCompositionMeasurement = uuid16(0x2A9C)

    // Some models report additional values of a weigh-in on private characteristics *after* the
    // standard packets of that weigh-in: the BF105 sends its half-body frame on FFFF/0008, the
    // BF1000 sends segmental fat on FFFF/0009 and segmental muscle on FFFF/000A. The base class
    // publishes as soon as a weight and a body-composition packet have been merged, which is
    // before any of that data has been read, so the record would be stored without it. The
    // body-composition packet is therefore kept in [heldComposition] until [awaitingSpecialData]
    // empties, and handing that packet back is what publishes the record - with its values
    // already in the buffer. A record whose data does not arrive is published without it, at the
    // next weigh-in or at the end of the connection.
    private var heldComposition: ByteArray? = null
    // The private characteristics of the weigh-in in flight that we still wait a packet from.
    private var awaitingSpecialData = emptySet<UUID>()

    /** Per model, the private characteristics carrying the special values of a weigh-in; none for the rest. */
    private fun specialDataCharacteristics(m: Model?): Set<UUID> = when (m) {
        Model.BEURER_BF105 -> setOf(bf105HalfBodyMeasurement)
        Model.BEURER_BF1000 -> setOf(bf1000SegmentalFatMeasurement, bf1000SegmentalMuscleMeasurement)
        else -> emptySet()
    }

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
        ) + if (model == Model.BEURER_BF500) {
            // Readings taken offline arrive as normal 2A9D/2A9C indications during consent.
            setOf(DeviceCapability.HISTORY_READ)
        } else {
            emptySet()
        }
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

        // A handler instance outlives a connection, so a new session must not start with
        // leftovers from the previous one: the buffers and the held packet belong to one record.
        if (specialDataCharacteristics(activeModel).isNotEmpty()) {
            pendingSegmentals.clear()
            pendingVisceralFat = 0f
            heldComposition = null
            awaitingSpecialData = emptySet()
        }

        when (activeModel) {
            Model.BEURER_BF1000 -> enableBf1000CustomMeasurements()
            Model.BEURER_BF105 -> {
                logD("Enabling BF105 half-body measurement characteristic")
                setNotifyOn(p.service, bf105HalfBodyMeasurement)
            }
            else -> Unit
        }
    }

    override fun onDisconnected() {
        // If the data that closes the held record never arrived, publish that record anyway:
        // weight and whole-body composition are still stored, only the values the scale did not
        // report are missing.
        releaseHeldComposition()
        super.onDisconnected()
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
            activeModel == Model.BEURER_BF105 && characteristic == bf105HalfBodyMeasurement -> {
                handleBf105HalfBodyData(data)
            }
            characteristic == chrBodyCompositionMeasurement &&
                specialDataCharacteristics(activeModel).isNotEmpty() -> {
                holdCompositionPacket(data)
                awaitingSpecialData = specialDataCharacteristics(activeModel)
            }
            characteristic == chrWeightMeasurement && heldComposition != null -> {
                // This is the next weigh-in, so the rest of the previous one's special data is
                // not coming any more; publish the previous one with the values it did report.
                // This assumes a scale reports a weigh-in's weight before the special values on
                // its private characteristics, which is how every capture of these models looks.
                releaseHeldComposition()
                super.onNotification(characteristic, data, user)
            }
            else ->
                super.onNotification(characteristic, data, user)
        }

        // This must come after the branches: they are what put a packet's values into the
        // buffers that publishing the weigh-in reads.
        noteSpecialDataRead(characteristic)
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
                    "BF1000 segmental fat decoded: visceral=${decoded.visceralFat} " +
                        "leftArm=${decoded.leftArm}% rightArm=${decoded.rightArm}% " +
                        "torso=${decoded.torso}% leftLeg=${decoded.leftLeg}% " +
                        "rightLeg=${decoded.rightLeg}%"
                )

                // Visceral fat has a predefined key of its own, so it rides the regular
                // field rather than a device-specific one.
                if (decoded.visceralFat > 0f) pendingVisceralFat = decoded.visceralFat

                bufferSegmental(SEGMENTAL_FAT_LEFT_ARM, decoded.leftArm)
                bufferSegmental(SEGMENTAL_FAT_RIGHT_ARM, decoded.rightArm)
                bufferSegmental(SEGMENTAL_FAT_TORSO, decoded.torso)
                bufferSegmental(SEGMENTAL_FAT_LEFT_LEG, decoded.leftLeg)
                bufferSegmental(SEGMENTAL_FAT_RIGHT_LEG, decoded.rightLeg)
            }
            characteristic == bf1000SegmentalMuscleMeasurement -> {
                val decoded = BeurerBf1000Lib.parseSegmentalMuscleMeasurement(data)
                if (decoded == null) {
                    logW("BF1000 segmental muscle packet could not be decoded")
                    return
                }

                logD(
                    "BF1000 segmental muscle decoded: leftArm=${decoded.leftArm}% " +
                        "rightArm=${decoded.rightArm}% torso=${decoded.torso}% " +
                        "leftLeg=${decoded.leftLeg}% rightLeg=${decoded.rightLeg}%"
                )

                bufferSegmental(SEGMENTAL_MUSCLE_LEFT_ARM, decoded.leftArm)
                bufferSegmental(SEGMENTAL_MUSCLE_RIGHT_ARM, decoded.rightArm)
                bufferSegmental(SEGMENTAL_MUSCLE_TORSO, decoded.torso)
                bufferSegmental(SEGMENTAL_MUSCLE_LEFT_LEG, decoded.leftLeg)
                bufferSegmental(SEGMENTAL_MUSCLE_RIGHT_LEG, decoded.rightLeg)
            }
        }

        if (characteristic == bf1000MeasurementStatus &&
            data.firstOrNull()?.toInt()?.and(0xFF) == 0x01) {
            logD("BF1000 measurement-complete status received")
        }
    }

    private fun isBf1000CustomMeasurementCharacteristic(characteristic: UUID): Boolean =
        characteristic in bf1000CustomMeasurementChars

    private fun bufferSegmental(key: MeasurementType.Key<Percent>, percent: Float) {
        if (percent > 0f && percent.isFinite()) pendingSegmentals[key] = Percent(percent)
    }

    // ---- BF105 half-body values ------------------------------------------------

    /**
     * The half-body frame belongs to the record it was measured with and arrives right after
     * that record's body-composition packet, so its values go to the buffer that the next
     * publish reads.
     */
    private fun handleBf105HalfBodyData(value: ByteArray) {
        logD("BF105 half-body chr=${bf105HalfBodyMeasurement.shortId()} len=${value.size} ${value.toHexPreview(32)}")

        val decoded = BeurerBf105Lib.parseHalfBodyMeasurement(value)
        if (decoded == null) {
            logW("BF105 half-body packet could not be decoded")
            return
        }

        bufferSegmental(SEGMENTAL_FAT_UPPER_BODY, decoded.upperBodyFat)
        bufferSegmental(SEGMENTAL_FAT_LOWER_BODY, decoded.lowerBodyFat)
        bufferSegmental(SEGMENTAL_MUSCLE_UPPER_BODY, decoded.upperBodyMuscle)
        bufferSegmental(SEGMENTAL_MUSCLE_LOWER_BODY, decoded.lowerBodyMuscle)
    }


    /**
     * The base class publishes as soon as a weight and a body-composition packet have been
     * merged, which for these scales happens before the special values of the same weigh-in
     * have arrived on its private characteristics. Taking the body-composition packet over here
     * keeps the weigh-in open; handing it back is what publishes it, with those values already
     * in the buffer the publish reads.
     */
    private fun holdCompositionPacket(value: ByteArray) {
        releaseHeldComposition()
        heldComposition = value
    }

    /**
     * Notes that one of the characteristics holding a weigh-in open has been read, and publishes
     * it once all of them have. Data belonging to no weigh-in in flight is left in the buffer
     * for the next publish, as it was before.
     */
    private fun noteSpecialDataRead(characteristic: UUID) {
        if (characteristic !in awaitingSpecialData) return
        awaitingSpecialData = awaitingSpecialData - characteristic
        if (awaitingSpecialData.isEmpty()) releaseHeldComposition()
    }

    /** Hands the held body-composition packet back, which publishes that weigh-in; no-op if none is held. */
    private fun releaseHeldComposition() {
        val value = heldComposition ?: return
        heldComposition = null
        awaitingSpecialData = emptySet()
        logD("Handing the held body-composition packet back for publication")
        super.onNotification(chrBodyCompositionMeasurement, value, currentAppUser())
    }

    /**
     * Attaches the values buffered from the private characteristics of the models that have
     * them to the measurement built from the standard weight/body-composition packets. Runs on
     * every publish path, including the disconnect flush, and clears the buffer so the next
     * weigh-in starts clean.
     */
    override fun transformBeforePublish(m: ScaleMeasurement): ScaleMeasurement {
        val transformed = super.transformBeforePublish(m)

        if (specialDataCharacteristics(activeModel).isEmpty()) return transformed

        if (activeModel == Model.BEURER_BF1000 && MeasurementType.VISCERAL_FAT !in transformed && pendingVisceralFat > 0f) {
            transformed[MeasurementType.VISCERAL_FAT] = pendingVisceralFat
        }
        pendingSegmentals.forEach { (key, percent) -> transformed[key] = percent }

        if (pendingSegmentals.isNotEmpty()) {
            logD("Attached ${pendingSegmentals.size} segmental values")
        }

        pendingSegmentals.clear()
        pendingVisceralFat = 0f

        return transformed
    }

    private companion object {
        // Semantic, vendor-neutral identities: a future scale reporting the same
        // quantities writes into the same columns, so the user's history survives a scale
        // change. The icon says *where* on the body, the colour says *what* is measured:
        // fat segments are shades of the predefined BODY_FAT red, muscle segments shades
        // of the MUSCLE green, darkening from the arms down to the legs. The anchor shades
        // themselves stay reserved for the whole-body totals.
        val SEGMENTAL_FAT_LEFT_ARM   = fat("left_arm",  R.string.measurement_type_segmental_fat_left_arm,   MeasurementTypeIcon.IC_BICEPS, 0xFFE57373)
        val SEGMENTAL_FAT_RIGHT_ARM  = fat("right_arm", R.string.measurement_type_segmental_fat_right_arm,  MeasurementTypeIcon.IC_BICEPS, 0xFFF44336)
        val SEGMENTAL_FAT_TORSO      = fat("torso",     R.string.measurement_type_segmental_fat_torso,      MeasurementTypeIcon.IC_CHEST,  0xFFE53935)
        val SEGMENTAL_FAT_LEFT_LEG   = fat("left_leg",  R.string.measurement_type_segmental_fat_left_leg,   MeasurementTypeIcon.IC_THIGH,  0xFFD32F2F)
        val SEGMENTAL_FAT_RIGHT_LEG  = fat("right_leg", R.string.measurement_type_segmental_fat_right_leg,  MeasurementTypeIcon.IC_THIGH,  0xFFC62828)

        val SEGMENTAL_MUSCLE_LEFT_ARM  = muscle("left_arm",  R.string.measurement_type_segmental_muscle_left_arm,  MeasurementTypeIcon.IC_BICEPS, 0xFF81C784)
        val SEGMENTAL_MUSCLE_RIGHT_ARM = muscle("right_arm", R.string.measurement_type_segmental_muscle_right_arm, MeasurementTypeIcon.IC_BICEPS, 0xFF4CAF50)
        val SEGMENTAL_MUSCLE_TORSO     = muscle("torso",     R.string.measurement_type_segmental_muscle_torso,     MeasurementTypeIcon.IC_CHEST,  0xFF43A047)
        val SEGMENTAL_MUSCLE_LEFT_LEG  = muscle("left_leg",  R.string.measurement_type_segmental_muscle_left_leg,  MeasurementTypeIcon.IC_THIGH,  0xFF388E3C)
        val SEGMENTAL_MUSCLE_RIGHT_LEG = muscle("right_leg", R.string.measurement_type_segmental_muscle_right_leg, MeasurementTypeIcon.IC_THIGH,  0xFF2E7D32)

        val SEGMENTAL_FAT_UPPER_BODY    = fat("upper_body",   R.string.measurement_type_segmental_fat_upper_body,    MeasurementTypeIcon.IC_CHEST, 0xFFEF9A9A)
        val SEGMENTAL_FAT_LOWER_BODY    = fat("lower_body",   R.string.measurement_type_segmental_fat_lower_body,    MeasurementTypeIcon.IC_THIGH, 0xFFB71C1C)

        val SEGMENTAL_MUSCLE_UPPER_BODY = muscle("upper_body", R.string.measurement_type_segmental_muscle_upper_body, MeasurementTypeIcon.IC_CHEST, 0xFFA5D6A7)
        val SEGMENTAL_MUSCLE_LOWER_BODY = muscle("lower_body", R.string.measurement_type_segmental_muscle_lower_body, MeasurementTypeIcon.IC_THIGH, 0xFF1B5E20)

        /** Both packets report tenths of a percent, so every segment is a PERCENT float. */
        private fun fat(part: String, nameResId: Int, icon: MeasurementTypeIcon, color: Long) =
            MeasurementType.devicePercent("segmental.fat.$part", nameResId, icon = icon, color = color.toInt())

        private fun muscle(part: String, nameResId: Int, icon: MeasurementTypeIcon, color: Long) =
            MeasurementType.devicePercent("segmental.muscle.$part", nameResId, icon = icon, color = color.toInt())
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
