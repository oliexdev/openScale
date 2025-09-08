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
package com.health.openscale.core.bluetooth.modern

import android.os.Handler
import com.health.openscale.core.bluetooth.BluetoothEvent.UserInteractionType
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Unified vendor handler for Beurer/Sanitas models.
 * Uses the existing consent UI from the base handler.
 */
class StandardBeurerSanitasHandler : StandardWeightProfileHandler() {

    private enum class Model { BEURER_BF105, BEURER_BF950, BEURER_BF500, BEURER_BF600, SANITAS_SBF72 }

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
        Model.SANITAS_SBF72 -> Profile(
            service = uuid16(0xFFFF),
            chrUserList = uuid16(0x0001),
            chrActivity = uuid16(0x0004),
            chrTakeMeasurement = uuid16(0x0006),
            chrInitials = null,
            chrTargetWeight = null
        )
    }

    private fun nameFor(m: Model) = when (m) {
        Model.BEURER_BF105 -> "Beurer BF105/720"
        Model.BEURER_BF950 -> "Beurer BF950"
        Model.BEURER_BF500 -> "Beurer BF500"
        Model.BEURER_BF600 -> "Beurer BF600"
        Model.SANITAS_SBF72 -> "Sanitas SBF72"
    }

    fun driverName(): String = friendlyName ?: "Beurer/Sanitas"

    // Model detection; constructor stays empty.
    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name?.lowercase().orEmpty()
        val svcs = device.serviceUuids ?: emptySet()

        val model = when {
            "bf105" in name || "bf720" in name -> Model.BEURER_BF105
            "bf950" in name || "sbf77" in name || "sbf76" in name || "bf950" in name -> Model.BEURER_BF950
            "bf500" in name                    -> Model.BEURER_BF500
            "bf600" in name || "bf850" in name -> Model.BEURER_BF600
            "sbf72" in name || "bf915" in name || "sbf73" in name -> Model.SANITAS_SBF72
            svcs.contains(uuid16(0xFFF0))  -> Model.BEURER_BF600
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

        logD("Setting notifications on service=${p.service} for chrUserList=${p.chrUserList}")
        setNotifyOn(p.service, p.chrUserList)

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

    override fun onRequestMeasurement() {
        profile?.let {
            logD("Requesting measurement: writing 0x00 to chrTakeMeasurement=${it.chrTakeMeasurement}")
            writeTo(it.service, it.chrTakeMeasurement, byteArrayOf(0x00))
        } ?: logW("onRequestMeasurement called but profile is null")
    }

    // SBF72: show PIN on scale, then open the existing consent UI in-app.
    private fun triggerDisplayPinOnScale(scaleIndex: Int) {
        val p = profile ?: run {
            logW("Cannot trigger PIN display: profile is null")
            return
        }

        if (activeModel != Model.SANITAS_SBF72) {
            logD("triggerDisplayPinOnScale skipped: model=$activeModel is not SANITAS_SBF72")
            return
        }

        val pinIndex = (scaleIndex + 0x10) and 0xFF   // spec: slot N -> (0x10 + N)
        writeTo(p.service, p.chrUserList, byteArrayOf(pinIndex.toByte()))
        logD("SBF72: requested PIN display for scale slot $scaleIndex (PIN index=0x${pinIndex.toString(16)})")
    }

    override fun onUserInteractionFeedback(
        interactionType: UserInteractionType,
        appUserId: Int,
        feedbackData: Any,
        uiHandler: Handler
    ) {
        logD("onUserInteractionFeedback received: type=$interactionType appUserId=$appUserId feedbackData=$feedbackData")

        if (interactionType == UserInteractionType.CHOOSE_USER) {
            val idx = (feedbackData as? Int)
            val isSbf72 = (activeModel == Model.SANITAS_SBF72)

            logD("CHOOSE_USER feedback idx=$idx, model=$activeModel")

            if (idx != null && idx >= 0 && isSbf72) {
                val knownConsent = loadConsentForScaleIndex(idx)
                logD("Loaded existing consent for scaleIndex=$idx: $knownConsent")

                if (knownConsent == -1) {
                    logD("No consent yet for scaleIndex=$idx, preparing to trigger PIN display and open consent UI")
                    // Keep mapping consistent (like base handler does)
                    for (i in 0..255) {
                        if (i != idx && loadUserIdForScaleIndex(i) == appUserId) {
                            saveUserIdForScaleIndex(i, -1)
                            logD("Cleared previous mapping for appUserId=$appUserId at scaleIndex=$i")
                        }
                    }

                    saveUserIdForScaleIndex(idx, appUserId)
                    logD("Saved mapping appUserId=$appUserId to scaleIndex=$idx")

                    // 1) Ask the scale to display the PIN
                    triggerDisplayPinOnScale(idx)
                    // 2) Open existing consent UI in-app
                    requestUserInteraction(UserInteractionType.ENTER_CONSENT, intArrayOf(idx, 0))
                    logD("Triggered ENTER_CONSENT UI for scaleIndex=$idx")
                    return  // prevent base from triggering a second prompt
                }
            }
        }

        logD("Delegating onUserInteractionFeedback to base handler")
        super.onUserInteractionFeedback(interactionType, appUserId, feedbackData, uiHandler)
    }

    // ---- Vendor write helpers -------------------------------------------------
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
