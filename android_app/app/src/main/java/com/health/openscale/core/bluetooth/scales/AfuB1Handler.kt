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

import android.bluetooth.BluetoothGattCharacteristic
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.AfuB1Lib
import com.health.openscale.core.bluetooth.libs.AfuB1Lib.ScaleFrame
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

class AfuB1Handler : ScaleDeviceHandler() {

    companion object {
        // Service advertised by the scale (and the GATT service carrying the vendor data channel).
        val SERVICE_UUID = UUID.fromString("0000fc50-0000-1000-8000-00805f9b34fb")

        // The frame write channel of the AFU app (service af000000-9f2d-4c8a-8d6f-6a7b45f90000).
        val WRITE_SERVICE = UUID.fromString("af000000-9f2d-4c8a-8d6f-6a7b45f90000")
        val WRITE_CHARACTERISTIC = UUID.fromString("af000002-9f2d-4c8a-8d6f-6a7b45f90000")

        // The app always talks to scale user slot 1.
        private const val SCALE_USER_SLOT = 1

        // Timing of the commit flow after the weight is marked final:
        // IDLE_DELAY_MS  — scale is considered idle (user stepped off) after this long
        //                  without a single frame; only then may the weigh be committed.
        // IDLE_POLL_MS   — how often the commit coroutine re-checks the idle condition.
        // COMMIT_TIMEOUT — give the scale this long to push the fresh record after the
        //                  re-sync + getWeighAgain, then fall back to the live weight.
        private const val IDLE_DELAY_MS = 2_000L
        private const val IDLE_POLL_MS = 250L
        private const val COMMIT_TIMEOUT_MS = 15_000L

        // Match tolerances of a "fresh committed" record — full rule on isFreshCommitted().
        private const val FRESH_RECORD_WINDOW_S = 600L
        private const val FRESH_WEIGHT_TOLERANCE_G = 1_000
    }

    // ---- User profile cached from the app ---------------------------------------

    private var age = 30
    private var sex = 1          // 1 = male, 0 = female (AFU convention)
    private var targetKg = 60.0f

    // ---- Session state ------------------------------------------------------------
    // Cross-thread state (GATT callback vs commit coroutine), hence @Volatile.

    @Volatile
    private var handshakeComplete = false

    @Volatile
    private var liveWeighInProgress = false

    @Volatile
    private var profileSent = false

    @Volatile
    private var historyDumpRequested = false

    @Volatile
    private var weightIsFinal = false

    @Volatile
    private var commitInProgress = false

    @Volatile
    private var committedRecordSeen = false

    @Volatile
    private var lastLiveWeightG: Int? = null

    @Volatile
    private var lastFrameAt = 0L

    @Volatile
    private var weighStartedAt = 0L
    private var commitJob: Job? = null

    // --- Device discovery --------------------------------------------------

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase()
        val hasService = device.serviceUuids.contains(SERVICE_UUID)
        val nameMatches = name.contains("AFU")
        if (!hasService || !nameMatches) return null

        val caps = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC
        )
        return DeviceSupport(
            displayName = "AFU-BH-TZ-B1",
            capabilities = caps,
            implemented = caps,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    // --- Connection --------------------------------------------------------

    override fun onConnected(user: ScaleUser) {
        resetSession()

        age = user.age
        sex = if (user.gender == GenderType.MALE) 1 else 0
        targetKg = when {
            user.goalWeight > 0f -> user.goalWeight
            user.initialWeight > 0f -> user.initialWeight
            else -> 60.0f
        }

        subscribeToAllCharacteristics()
        lastFrameAt = System.currentTimeMillis()
        sendHandshake()
        // Drain the stored history right away; if the scale ignores an early 0x8e,
        // onStoredRecord re-sends it once the first record arrives.
        sendHistoryDumpRequest()
        logI("history-dump request (0x8e) sent right after handshake")
        userInfo(R.string.bt_info_step_on_scale)
    }

    override fun onDisconnected() {
        commitJob?.cancel()
        commitJob = null
    }

    /** Forget everything from a previous connection — the handler instance is shared. */
    private fun resetSession() {
        commitJob?.cancel()
        commitJob = null
        handshakeComplete = false
        liveWeighInProgress = false
        profileSent = false
        historyDumpRequested = false
        weightIsFinal = false
        commitInProgress = false
        committedRecordSeen = false
        lastLiveWeightG = null
        lastFrameAt = 0L
        weighStartedAt = 0L
    }

    /** Subscribe to every notify/indicate characteristic (the scale reports on one of them). */
    private fun subscribeToAllCharacteristics() {
        val peripheral = getPeripheral() ?: return
        for (service in peripheral.services) {
            for (char in service.characteristics ?: continue) {
                if ((char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                ) {
                    setNotifyOn(service.uuid, char.uuid)
                }
            }
        }
    }

    // --- Commands (phone -> scale) -----------------------------------------

    /** The service actually hosting the write characteristic, resolved from the GATT tree. */
    private fun writeService(): UUID {
        val peripheral = getPeripheral()
        if (peripheral != null) {
            for (service in peripheral.services) {
                if (service.characteristics?.any { it.uuid == WRITE_CHARACTERISTIC } == true) {
                    return service.uuid
                }
            }
            logW("write characteristic not found in GATT tree, using default service")
        }
        return WRITE_SERVICE
    }

    /** Handshake: time-sync + 2x sync-request. No profile — a profile 0x3f sent too
     *  early switches the scale into weighing mode and blocks the stored-history dump. */
    private fun sendHandshake() {
        sendTimeSync()
        sendSyncRequestPair()
        handshakeComplete = true
        logI("handshake sent (time-sync + 2x sync-request)")
    }

    private fun sendTimeSync() {
        writeTo(
            writeService(), WRITE_CHARACTERISTIC,
            AfuB1Lib.buildTimeSync(System.currentTimeMillis() / 1000L)
        )
    }

    private fun sendSyncRequestPair() {
        writeTo(
            writeService(), WRITE_CHARACTERISTIC,
            AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.FIRST)
        )
        writeTo(
            writeService(), WRITE_CHARACTERISTIC,
            AfuB1Lib.buildSyncRequest(AfuB1Lib.SyncRequestVariant.SECOND)
        )
    }

    /** The user profile (age/sex/target weight) with one of the profile variants. */
    private fun sendUserProfile(variant: AfuB1Lib.ProfileVariant) {
        writeTo(
            writeService(), WRITE_CHARACTERISTIC, AfuB1Lib.buildUserProfile(
                variant = variant,
                nowEpochSeconds = System.currentTimeMillis() / 1000L,
                user = SCALE_USER_SLOT,
                age = age,
                sex = sex,
                targetKg = targetKg
            )
        )
    }

    /** Tells the scale to dump its full history queue. */
    private fun sendHistoryDumpRequest() {
        writeTo(
            writeService(), WRITE_CHARACTERISTIC, AfuB1Lib.buildHistoryDumpRequest(
                nowEpochSeconds = System.currentTimeMillis() / 1000L,
                user = SCALE_USER_SLOT,
                age = age,
                sex = sex,
                targetKg = targetKg
            )
        )
    }

    /** Acknowledge a received frame; history records carry their sequence as correlation. */
    private fun sendAck(frame: ScaleFrame) {
        val correlation = (frame as? ScaleFrame.StoredRecord)?.sequence ?: 0
        writeTo(
            writeService(), WRITE_CHARACTERISTIC,
            AfuB1Lib.buildAck(frame.ackReplyByte, correlation)
        )
    }

    /** GetWeighAgain profiles (0x4b/0x5b) + chunk request — pulls the fresh committed weigh. */
    private fun sendGetWeighAgain() {
        sendUserProfile(AfuB1Lib.ProfileVariant.GET_WEIGH_AGAIN_1)
        sendUserProfile(AfuB1Lib.ProfileVariant.GET_WEIGH_AGAIN_2)
        writeTo(writeService(), WRITE_CHARACTERISTIC, AfuB1Lib.buildChunkRequest())
    }

    /** Finalize a weigh and arm the scale for the next one (mirrors the app's ending). */
    private fun sendFinalize() {
        sendUserProfile(AfuB1Lib.ProfileVariant.FINAL)
        sendTimeSync()
        sendSyncRequestPair()
    }

    // --- Incoming frames (scale -> phone) ----------------------------------

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        lastFrameAt = System.currentTimeMillis()
        val frame = AfuB1Lib.parse(data)
        if (frame == null) {
            logD("not an AFU frame (len=${data.size}) ${data.toHexPreview(20)}")
            return
        }
        when (frame) {
            is ScaleFrame.StoredRecord -> onStoredRecord(frame, user)
            is ScaleFrame.LiveWeight -> onLiveWeight(frame)
            is ScaleFrame.ImpedanceResult -> onImpedanceResult(frame)
            is ScaleFrame.BodyCompChunk -> onBodyCompChunk(frame)
            is ScaleFrame.Acknowledgement ->
                logD("RX ACK reply_cmd=0x${frame.replyCmd.toString(16)} op=0x${frame.replyOp.toString(16)}")

            is ScaleFrame.ControlFrame ->
                logD("RX control cmd=0x${frame.cmd.toString(16)}")
        }
    }

    /**
     * A stored record (0x54). On its own the scale repeats the same stored record
     * forever, so the first idle record triggers a history-dump request (0x8e)
     * that switches it to export mode; in export mode every record must be ACKed
     * before the next one arrives. After a weigh commit, the pushed record is the
     * fresh measurement.
     */
    private fun onStoredRecord(record: ScaleFrame.StoredRecord, user: ScaleUser) {
        logStoredRecord(record)
        sendAck(record)

        // Idle: answer the first record with a history-dump request to drain the FULL
        // queue. During a live weigh the scale ignores it, so gate it on no live frames.
        if (!liveWeighInProgress && !historyDumpRequested) {
            historyDumpRequested = true
            sendHistoryDumpRequest()
            logI("idle: history-dump request (0x8e) sent to drain the full queue")
        }

        publishMeasurement(
            user = user,
            weightKg = record.weightGrams / 1000.0f,
            impedanceOhms = record.resistanceOhms.toFloat(),
            timestamp = record.timestampEpochSeconds
        )

        if (isFreshCommitted(record) && !committedRecordSeen) {
            committedRecordSeen = true
            commitJob?.cancel()
            logI("committed weigh received — draining any remaining history")
            startPostWeighDrain()
        } else {
            logI("stored history imported (not the fresh weigh) — continuing session")
        }
    }

    /** Live weight streaming (0x51) while someone stands on the scale. */
    private fun onLiveWeight(weight: ScaleFrame.LiveWeight) {
        liveWeighInProgress = true
        if (weighStartedAt == 0L) weighStartedAt = System.currentTimeMillis()
        lastLiveWeightG = weight.weightGrams
        logI(
            "RX 0x51 live ${weight.weightGrams / 1000.0f}kg mode=${weight.mode} " +
                    "state=${if (weight.locked) 1 else 0}"
        )

        // Live weighing has started: now (and only now) send the user profile.
        if (!profileSent && handshakeComplete) {
            profileSent = true
            sendUserProfile(AfuB1Lib.ProfileVariant.INITIAL)
            logI("live: user profile (0x3f) sent")
        }

        // The scale signals the weight is final — ACK and start the commit flow.
        if (weight.locked && !weightIsFinal && handshakeComplete) {
            weightIsFinal = true
            sendAck(weight)
            logI("state=1 final, ACKed 0x51 — commit fires once the scale goes idle")
            startCommitFlow()
        }
    }

    /**
     * Single-impedance result (0x52). The scale only sends it after a second weigh-in
     * armed by profile 0x5b, which openScale does not do — so this is normally never
     * reached; every stored record already carries its resistance.
     */
    private fun onImpedanceResult(result: ScaleFrame.ImpedanceResult) {
        logI("RX 0x52 RESULT adc=${result.adcOhms} (impedance, valid=${result.valid})")
        sendAck(result)

        // After it the app sends the final profile + a re-sync to arm the next weigh.
        // If the fresh record was already received, the post-weigh drain is in charge
        // of ending the session. If not (abnormal ordering), arm the scale and let the
        // commit flow's timeout fall back to the stable live weight.
        if (handshakeComplete && commitInProgress && !committedRecordSeen) {
            logI("weigh complete — sending finalize to arm the next weigh")
            sendFinalize()
        }
    }

    /**
     * One 14-byte chunk of the scale's encrypted report (0x5f/0x14). Purpose unknown and
     * the payload is undecodable — body composition comes from weight + impedance, so the
     * data is discarded. The ACK still matters: un-ACKed frames get re-served by the
     * scale, so acknowledging keeps the flow moving towards the impedance frames.
     */
    private fun onBodyCompChunk(chunk: ScaleFrame.BodyCompChunk) {
        logI("RX 0x14 chunk ${chunk.chunkIndex} (encrypted report, discarded)")
        sendAck(chunk)
    }

    // --- Commit flow -------------------------------------------------------

    /**
     * After the weight is marked final the user must step off and the scale go idle. Only then can
     * the fresh committed record be pulled (re-sync + getWeighAgain). Waiting for the
     * scale to go quiet (rather than a fixed delay after the weight is marked final)
     * matters: the scale keeps
     * streaming 0x51 while someone stands on it, and a commit sent mid-weigh is ignored —
     * afterwards the scale replays its stored history instead of the fresh weight.
     */
    private fun startCommitFlow() {
        commitJob = scope.launch {
            while (!scaleIsIdle()) {
                if (committedRecordSeen) return@launch
                delay(IDLE_POLL_MS.milliseconds)
            }

            if (!commitInProgress) {
                commitInProgress = true
                logI("scale idle — re-sync + getWeighAgain to pull the fresh record")
                sendTimeSync()
                sendSyncRequestPair()
                sendGetWeighAgain()
            }

            // Phase 2: give the scale time to push the fresh record + impedance.
            val deadline = System.currentTimeMillis() + COMMIT_TIMEOUT_MS
            while (!committedRecordSeen && System.currentTimeMillis() < deadline) {
                delay(IDLE_POLL_MS.milliseconds)
            }

            val stableWeightG = lastLiveWeightG
            if (!committedRecordSeen && stableWeightG != null) {
                committedRecordSeen = true
                logW("commit timeout — publishing stable live weight")
                publishMeasurement(
                    user = currentAppUser(),
                    weightKg = stableWeightG / 1000.0f,
                    impedanceOhms = null,
                    timestamp = null
                )
                requestDisconnect()
            }
        }
    }

    /** True once the scale has been silent for [IDLE_DELAY_MS] — the user is off it. */
    private fun scaleIsIdle(): Boolean =
        System.currentTimeMillis() - lastFrameAt > IDLE_DELAY_MS

    /**
     * After the fresh weigh record: re-sync + 0x8e once more and wait until the queue
     * goes quiet, so any history the scale still holds is imported before disconnecting.
     * The scale only serves stored records while idle, and it re-serves the oldest
     * un-ACKed record until ACKed — draining to silence means everything is read.
     */
    private fun startPostWeighDrain() {
        commitJob = scope.launch {
            logI("post-weigh drain: re-sync + 0x8e to catch remaining history")
            sendTimeSync()
            sendSyncRequestPair()
            sendHistoryDumpRequest()
            val deadline = System.currentTimeMillis() + COMMIT_TIMEOUT_MS
            while (!scaleIsIdle() && System.currentTimeMillis() < deadline) {
                delay(IDLE_POLL_MS.milliseconds)
            }
            requestDisconnect()
        }
    }

    /**
     * A stored record is the fresh committed weigh only if it was recorded during this
     * weigh (timestamp near now, after the weigh started) and matches the final weight.
     * Everything else is old stored history and must not finish the session.
     */
    private fun isFreshCommitted(record: ScaleFrame.StoredRecord): Boolean {
        if (!commitInProgress || weighStartedAt == 0L) return false
        val nowSecs = System.currentTimeMillis() / 1000L
        val ts = record.timestampEpochSeconds
        if (ts < weighStartedAt / 1000L - 60L) return false
        if (abs(nowSecs - ts) > FRESH_RECORD_WINDOW_S) return false
        val live = lastLiveWeightG ?: return false
        return abs(record.weightGrams - live) <= FRESH_WEIGHT_TOLERANCE_G
    }

    // --- Measurement publishing --------------------------------------------

    private fun publishMeasurement(
        user: ScaleUser,
        weightKg: Float,
        impedanceOhms: Float?,
        timestamp: Long?
    ) {
        if (weightKg <= 0f) return
        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = timestamp?.let { plausibleDate(it) } ?: Date()
            this.weight = weightKg
            // Composition chain: resistance -> body-fat % -> report. Nothing derived is
            // published outside the reference implementation's validated input ranges.
            if (impedanceOhms != null && impedanceOhms > 0f) {
                val sexFlag = if (user.gender == GenderType.MALE) 1 else 0
                val bodyFat = AfuB1Lib.bodyFatPercent(
                    weightKg.toDouble(), user.bodyHeight.toDouble(), user.age,
                    sexFlag, impedanceOhms.toDouble(),
                )
                if (bodyFat != null) {
                    val comp = AfuB1Lib.bodyComposition(sexFlag, user.age, user.bodyHeight, weightKg, bodyFat)
                    if (comp != null) {
                        this.impedance = impedanceOhms.toDouble()
                        fat = bodyFat
                        water = comp.waterPercent.toFloat()
                        muscle = comp.skeletalMusclePercent.toFloat()
                        bone = comp.boneMassKg
                        visceralFat = comp.visceralFatIndex.toFloat()
                        protein = comp.proteinPercent.toFloat()
                        lbm = comp.fatFreeMassKg
                    }
                }
            }
        }
        logI(
            "publishing measurement: ${weightKg}kg" +
                    (impedanceOhms?.let { ", impedance=${it}Ohm" } ?: "")
        )
        publish(measurement)
    }

    /** History timestamps are Unix seconds; guard against a wildly wrong scale clock. */
    private fun plausibleDate(timestampEpochSeconds: Long): Date {
        val millis = timestampEpochSeconds * 1000L
        return if (millis in 946_684_800_000L..(System.currentTimeMillis() + 86_400_000L)) Date(millis)
        else Date()
    }

    private fun logStoredRecord(record: ScaleFrame.StoredRecord) {
        logI(
            "RX 0x54 stored seq=${record.sequence} type=${record.historyType} " +
                    iso(record.timestampEpochSeconds) + " ${record.weightGrams / 1000.0f}kg " +
                    "res=${record.resistanceOhms}Ohm" +
                    (if (record.locked) " FINAL" else "") +
                    (if (record.hasElectrode) " E" else "") +
                    (if (record.hasHeartRate) " HR" else "")
        )
    }

    // --- Helpers -----------------------------------------------------------

    private fun iso(epochSeconds: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000L }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d:%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)
        )
    }
}
