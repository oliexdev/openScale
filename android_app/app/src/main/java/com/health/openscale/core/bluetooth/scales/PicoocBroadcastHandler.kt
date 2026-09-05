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

import android.bluetooth.le.ScanResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.PicoocAnchorLearner
import com.health.openscale.core.bluetooth.libs.PicoocWhiteBodyComposition
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import kotlin.math.roundToInt

/**
 * Parser for the PICOOC Mini Lite broadcast advertisement.
 *
 * The layout was recovered from PICOOC 4.3.0's `BTBleForBroadcasDevice` and checked against
 * three captures from a physical Mini Lite. The full 16-byte manufacturer value is:
 *
 *   [0..5]   device MAC, in normal display order
 *   [6]      result state; 0x39 is the completed measurement accepted by the vendor app
 *   [7]      protocol byte (0x1F in the captures; the vendor parser does not inspect it)
 *   [8..9]   weight, unsigned big-endian, 0.05 kg units
 *   [10..11] impedance, unsigned big-endian, 0.1 ohm units; 0xFFFF means unavailable
 *   [12..13] secondary weight, unsigned big-endian with bit 15 masked, 0.1 lb units
 *   [14]     display-unit byte (passed through by the vendor app)
 *   [15]     one's-complement checksum of bytes [0..14]
 *
 * Android treats bytes [0..1] as the little-endian Bluetooth company id. Consequently [parse]
 * receives them separately as [manufacturerId], followed by the remaining 14-byte [payload].
 * The id is part of the embedded MAC, not a fixed PICOOC company id, and must never be hardcoded.
 */
internal object PicoocMiniLiteAdv {
    const val COMPLETE_STATE = 0x39
    private const val PAYLOAD_SIZE = 14
    private const val MFR_SIZE = 16

    data class Frame(
        val state: Int,
        val protocol: Int,
        val weightKg: Float,
        val impedanceOhm: Float?,
        val displayUnit: Int,
    ) {
        val complete: Boolean get() = state == COMPLETE_STATE
    }

    /** Parse one entry exactly as Android exposes it through ManufacturerSpecificData. */
    fun parse(manufacturerId: Int, payload: ByteArray?): Frame? {
        if (payload == null || payload.size != PAYLOAD_SIZE) return null

        val mfr = ByteArray(MFR_SIZE)
        mfr[0] = manufacturerId.toByte()
        mfr[1] = (manufacturerId ushr 8).toByte()
        payload.copyInto(mfr, destinationOffset = 2)

        // The vendor checksum is ~(sum(bytes 0..14) & 0xFF). Equivalently, including the
        // checksum byte makes the low byte of the total exactly 0xFF.
        val checksumTotal = mfr.sumOf { it.toInt() and 0xFF } and 0xFF
        if (checksumTotal != 0xFF) return null

        val state = mfr[6].toInt() and 0xFF
        val protocol = mfr[7].toInt() and 0xFF
        val weightRaw = u16be(mfr, 8)
        val impedanceRaw = u16be(mfr, 10)
        return Frame(
            state = state,
            protocol = protocol,
            weightKg = weightRaw / 20.0f,
            impedanceOhm = if (impedanceRaw == 0xFFFF) null else impedanceRaw / 10.0f,
            displayUnit = mfr[14].toInt() and 0xFF,
        )
    }

    private fun u16be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
}

/** Broadcast-only support for the PICOOC Mini Lite, advertised as `PICOOC-L`. */
class PicoocBroadcastHandler : ScaleDeviceHandler() {

    companion object {
        private const val ADVERTISED_NAME = "PICOOC-L"
        private const val WEIGHT_MIN_KG = 0.5f
        private const val WEIGHT_MAX_KG = 300.0f

        private const val KEY_ANCHOR_WEIGHT = "anchorWeight"
        private const val KEY_ANCHOR_BETA = "anchorBeta"
        private const val KEY_LEARNER_STATE = "learnerState"
        private const val KEY_PROFILE_FINGERPRINT = "profileFingerprint"
        private const val KEY_MEASUREMENT_ANCHOR = "measurementAnchor"
        private const val KEY_PREVIOUS_RAW_R = "previousRawR"
        private const val KEY_PREVIOUS_CORRECTED_R = "previousCorrectedR"
        private const val KEY_PREVIOUS_WEIGHT_GRAMS = "previousWeightGrams"
        private const val KEY_PREVIOUS_TIMESTAMP = "previousTimestamp"
        private const val KEY_UI_LAST_USER_ID = "ui/lastUserId"
        private const val KEY_UI_LAST_USER_NAME = "ui/lastUserName"
        private const val KEY_UI_PROGRESS = "ui/progress"
        private const val KEY_UI_BETA = "ui/beta"
        private const val KEY_UI_ANCHOR_WEIGHT = "ui/anchorWeight"
        private const val KEY_UI_FIXED = "ui/fixed"

        val TOTAL_MUSCLE = MeasurementType.devicePercent(
            "picooc.total_muscle",
            R.string.measurement_type_picooc_total_muscle,
        )
        val METABOLIC_AGE = MeasurementType.deviceInt(
            "picooc.metabolic_age",
            R.string.measurement_type_picooc_metabolic_age,
        )
    }

    private val deviceSupport = DeviceSupport(
        displayName = "PICOOC Mini Lite",
        capabilities = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
        ),
        implemented = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
        ),
        linkMode = LinkMode.BROADCAST_ONLY,
    )

    /** The scale repeats its final advertisement; publish it at most once per scan session. */
    private var armed = true

    @Composable
    override fun DeviceConfigurationUi() {
        var lastUserId by remember { mutableIntStateOf(settingsGetInt(KEY_UI_LAST_USER_ID, -1)) }
        var lastUserName by remember { mutableStateOf(settingsGetString(KEY_UI_LAST_USER_NAME).orEmpty()) }
        var progress by remember { mutableIntStateOf(settingsGetInt(KEY_UI_PROGRESS, 0)) }
        var beta by remember { mutableIntStateOf(settingsGetInt(KEY_UI_BETA, 0)) }
        var anchorWeight by remember { mutableIntStateOf(settingsGetInt(KEY_UI_ANCHOR_WEIGHT, 0)) }
        var fixed by remember { mutableStateOf(settingsGetInt(KEY_UI_FIXED, 0) == 1) }
        var manualBeta by remember(beta) { mutableStateOf(beta.takeIf { it > 0 }?.toString().orEmpty()) }
        var manualAnchorWeight by remember(anchorWeight) {
            mutableStateOf(anchorWeight.takeIf { it > 0 }?.toString().orEmpty())
        }

        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.picooc_calibration_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                lastUserId < 0 || progress == 0 -> Text(stringResource(R.string.picooc_calibration_no_measurements))
                fixed -> Text(
                    stringResource(
                        R.string.picooc_calibration_fixed,
                        lastUserName,
                        beta,
                        anchorWeight,
                    )
                )
                else -> Text(
                    stringResource(
                        R.string.picooc_calibration_learning,
                        lastUserName,
                        progress,
                        beta,
                        anchorWeight,
                    )
                )
            }
            Button(
                onClick = {
                    resetCalibration(lastUserId)
                    progress = 0
                    beta = 0
                    anchorWeight = 0
                    fixed = false
                },
                enabled = lastUserId >= 0,
            ) {
                Text(
                    if (lastUserName.isBlank()) stringResource(R.string.picooc_calibration_reset_generic)
                    else stringResource(R.string.picooc_calibration_reset, lastUserName)
                )
            }
            Text(
                text = stringResource(R.string.picooc_calibration_manual_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = manualAnchorWeight,
                onValueChange = { manualAnchorWeight = it.filter(Char::isDigit).take(3) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.picooc_calibration_anchor_weight_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            OutlinedTextField(
                value = manualBeta,
                onValueChange = { manualBeta = it.filter(Char::isDigit).take(2) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.picooc_calibration_beta_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
            val parsedManualWeight = manualAnchorWeight.toIntOrNull()
            val parsedManualBeta = manualBeta.toIntOrNull()
            Button(
                onClick = {
                    val importedWeight = parsedManualWeight ?: return@Button
                    val importedBeta = parsedManualBeta ?: return@Button
                    settingsPutInt(userKey(KEY_ANCHOR_WEIGHT, lastUserId), importedWeight)
                    settingsPutInt(userKey(KEY_ANCHOR_BETA, lastUserId), importedBeta)
                    settingsPutString(userKey(KEY_LEARNER_STATE, lastUserId), "")
                    settingsPutInt(userKey(KEY_MEASUREMENT_ANCHOR, lastUserId), importedBeta * 10)
                    progress = PicoocAnchorLearner.REQUIRED_MEASUREMENTS
                    beta = importedBeta
                    anchorWeight = importedWeight
                    fixed = true
                    settingsPutInt(KEY_UI_PROGRESS, progress)
                    settingsPutInt(KEY_UI_BETA, beta)
                    settingsPutInt(KEY_UI_ANCHOR_WEIGHT, anchorWeight)
                    settingsPutInt(KEY_UI_FIXED, 1)
                },
                enabled = lastUserId >= 0 && parsedManualWeight in 1..300 && parsedManualBeta in 19..41,
            ) {
                Text(stringResource(R.string.picooc_calibration_apply_manual))
            }
        }
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? =
        deviceSupport.takeIf { device.name.trim().equals(ADVERTISED_NAME, ignoreCase = true) }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val manufacturerData = result.scanRecord?.manufacturerSpecificData
            ?: return BroadcastAction.IGNORED

        var frame: PicoocMiniLiteAdv.Frame? = null
        for (i in 0 until manufacturerData.size()) {
            frame = PicoocMiniLiteAdv.parse(
                manufacturerData.keyAt(i),
                manufacturerData.valueAt(i),
            )
            if (frame != null) break
        }
        val parsed = frame ?: return BroadcastAction.IGNORED

        if (!parsed.complete) {
            // Any valid non-final frame belongs to an active measurement and re-arms the guard.
            armed = true
            logD("measurement in progress (state=0x%02X)".format(parsed.state))
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        if (!armed) return BroadcastAction.CONSUMED_STOP
        if (parsed.weightKg !in WEIGHT_MIN_KG..WEIGHT_MAX_KG) {
            logW("completed frame has implausible weight ${parsed.weightKg} kg; ignoring")
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        val now = System.currentTimeMillis()
        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date(now)
            this[MeasurementType.WEIGHT] = Kg(parsed.weightKg)
            parsed.impedanceOhm
                ?.takeIf { it > 0.0f }
                ?.let { this[MeasurementType.IMPEDANCE] = Ohm(it) }
        }

        parsed.impedanceOhm
            ?.takeIf { it >= 50f }
            ?.takeUnless { user.useAssistedWeighing }
            ?.let { rawImpedance ->
                populateBodyComposition(measurement, user, parsed.weightKg, rawImpedance, now)
            }

        armed = false
        publish(measurement)
        logI(
            "measurement published: weight=${parsed.weightKg} kg, " +
                "impedance=${parsed.impedanceOhm ?: "unavailable"} ohm, " +
                "protocol=0x%02X unit=%d".format(parsed.protocol, parsed.displayUnit)
        )
        return BroadcastAction.CONSUMED_STOP
    }

    override fun onDisconnected() {
        armed = true
    }

    private fun populateBodyComposition(
        measurement: ScaleMeasurement,
        user: ScaleUser,
        weightKg: Float,
        rawImpedance: Float,
        now: Long,
    ) {
        val age = user.getAge(Date(now))
        if (age < 16 || user.bodyHeight <= 0f) {
            logW("body composition requires age >= 16 and a valid height")
            return
        }

        ensureCalibrationMatchesProfile(user)

        val rawR = rawImpedance.roundToInt()
        val previousRawR = settingsGetInt(userKey(KEY_PREVIOUS_RAW_R, user.id), -1).takeIf { it > 0 }
        val previousCorrectedR = settingsGetInt(userKey(KEY_PREVIOUS_CORRECTED_R, user.id), -1).takeIf { it > 0 }
        val previousWeight = settingsGetInt(userKey(KEY_PREVIOUS_WEIGHT_GRAMS, user.id), -1)
            .takeIf { it > 0 }
            ?.div(1000f)
        val previousTimestamp = settingsGetString(userKey(KEY_PREVIOUS_TIMESTAMP, user.id))?.toLongOrNull()
        val correctedR = PicoocWhiteBodyComposition.correctedImpedance(
            rawOhm = rawR,
            weightKg = weightKg,
            timestampMs = now,
            previousRawOhm = previousRawR,
            previousWeightKg = previousWeight,
            previousTimestampMs = previousTimestamp,
            previousCorrectedOhm = previousCorrectedR,
        )

        val anchorWeightKey = userKey(KEY_ANCHOR_WEIGHT, user.id)
        val anchorBetaKey = userKey(KEY_ANCHOR_BETA, user.id)
        val previousAnchorWeight = settingsGetInt(anchorWeightKey, 0).takeIf { it > 0 }
        val anchorWeight = PicoocWhiteBodyComposition.anchorWeight(weightKg, previousAnchorWeight)
        val fixedBeta = settingsGetInt(anchorBetaKey, 0).takeIf { it >= 19 }
        val learnerKey = userKey(KEY_LEARNER_STATE, user.id)
        val learnerState = PicoocAnchorLearner.decode(settingsGetString(learnerKey))
        val learnable = fixedBeta == null && correctedR >= 300
        val decision = if (learnable) {
            PicoocAnchorLearner.decide(learnerState, weightKg, rawR)
        } else {
            null
        }
        val betaForCalculation = fixedBeta ?: decision?.beta ?: 0
        val recentMeasurementAnchor = previousTimestamp
            ?.takeIf { now - it in 0L..1_800_000L }
            ?.let { settingsGetInt(userKey(KEY_MEASUREMENT_ANCHOR, user.id), 0) }
            ?: 0

        val result = PicoocWhiteBodyComposition.calculate(
            PicoocWhiteBodyComposition.Input(
                male = user.gender.isMale(),
                heightCm = user.bodyHeight,
                age = age,
                weightKg = weightKg,
                correctedImpedanceOhm = correctedR,
                anchorWeightKg = anchorWeight,
                anchorBeta = betaForCalculation,
                hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY),
                previousMeasurementAnchor = recentMeasurementAnchor,
            )
        ) ?: return

        val learnerUpdate = when {
            fixedBeta != null -> null
            decision != null -> PicoocAnchorLearner.accept(
                state = learnerState,
                decision = decision,
                weightKg = weightKg,
                rawOhm = rawR,
                calculatedBeta = result.anchorBeta,
            )
            else -> {
                settingsPutString(learnerKey, PicoocAnchorLearner.encode(PicoocAnchorLearner.skip(learnerState)))
                null
            }
        }
        learnerUpdate?.let { settingsPutString(learnerKey, PicoocAnchorLearner.encode(it.state)) }
        val promotedBeta = learnerUpdate?.fixedBeta
        if (promotedBeta != null) settingsPutInt(anchorBetaKey, promotedBeta)

        applyBodyCompositionMeasurements(measurement, result)

        settingsPutInt(anchorWeightKey, anchorWeight)
        settingsPutInt(userKey(KEY_MEASUREMENT_ANCHOR, user.id), result.measurementAnchor)
        settingsPutInt(userKey(KEY_PREVIOUS_RAW_R, user.id), rawR)
        settingsPutInt(userKey(KEY_PREVIOUS_CORRECTED_R, user.id), correctedR)
        settingsPutInt(userKey(KEY_PREVIOUS_WEIGHT_GRAMS, user.id), (weightKg * 1000f).roundToInt())
        settingsPutString(userKey(KEY_PREVIOUS_TIMESTAMP, user.id), now.toString())

        val progress = if (fixedBeta != null || promotedBeta != null) {
            PicoocAnchorLearner.REQUIRED_MEASUREMENTS
        } else {
            learnerUpdate?.progress ?: learnerState.progress
        }
        settingsPutInt(KEY_UI_LAST_USER_ID, user.id)
        settingsPutString(KEY_UI_LAST_USER_NAME, user.userName.ifBlank { user.id.toString() })
        settingsPutInt(KEY_UI_PROGRESS, progress)
        settingsPutInt(KEY_UI_BETA, result.anchorBeta)
        settingsPutInt(KEY_UI_ANCHOR_WEIGHT, anchorWeight)
        settingsPutInt(KEY_UI_FIXED, if (fixedBeta != null || promotedBeta != null) 1 else 0)

        logI(
            "PICOOC body composition: fat=${result.bodyFatPercent}, " +
                "totalMuscle=${result.totalMusclePercent}, skeletalMuscle=${result.skeletalMusclePercent}, " +
                "water=${result.waterPercent}, bone=${result.boneMassKg}, bmr=${result.basalMetabolicRateKcal}, " +
                "anchor=$anchorWeight/${result.anchorBeta}/${result.measurementAnchor} " +
                "(${if (fixedBeta != null || promotedBeta != null) "fixed" else "learning $progress/4"}), " +
                "R=$rawR->$correctedR"
        )
    }

    private fun ensureCalibrationMatchesProfile(user: ScaleUser) {
        val key = userKey(KEY_PROFILE_FINGERPRINT, user.id)
        val fingerprint = "white-v1:${user.gender}:${user.bodyHeight.toBits()}:${user.birthday.time}"
        if (settingsGetString(key) == fingerprint) return

        resetCalibration(user.id)
        settingsPutString(key, fingerprint)
        logI("PICOOC calibration reset because profile inputs changed for user ${user.id}")
    }

    private fun resetCalibration(userId: Int) {
        settingsPutInt(userKey(KEY_ANCHOR_WEIGHT, userId), 0)
        settingsPutInt(userKey(KEY_ANCHOR_BETA, userId), 0)
        settingsPutString(userKey(KEY_LEARNER_STATE, userId), "")
        settingsPutInt(userKey(KEY_MEASUREMENT_ANCHOR, userId), 0)
        settingsPutInt(userKey(KEY_PREVIOUS_RAW_R, userId), 0)
        settingsPutInt(userKey(KEY_PREVIOUS_CORRECTED_R, userId), 0)
        settingsPutInt(userKey(KEY_PREVIOUS_WEIGHT_GRAMS, userId), 0)
        settingsPutString(userKey(KEY_PREVIOUS_TIMESTAMP, userId), "")
        if (settingsGetInt(KEY_UI_LAST_USER_ID, -1) == userId) {
            settingsPutInt(KEY_UI_PROGRESS, 0)
            settingsPutInt(KEY_UI_BETA, 0)
            settingsPutInt(KEY_UI_ANCHOR_WEIGHT, 0)
            settingsPutInt(KEY_UI_FIXED, 0)
        }
    }

    /**
     * openScale's built-in MUSCLE reference ranges describe skeletal muscle. PICOOC's primary
     * "muscle" value is broader (lean mass minus bone) and commonly exceeds openScale's 60%
     * plausibility ceiling, so retain it as a separate vendor measurement instead.
     */
    internal fun applyBodyCompositionMeasurements(
        measurement: ScaleMeasurement,
        result: PicoocWhiteBodyComposition.Result,
    ) {
        measurement[MeasurementType.BODY_FAT] = Percent(result.bodyFatPercent)
        measurement[MeasurementType.MUSCLE] = Percent(result.skeletalMusclePercent)
        measurement[MeasurementType.WATER] = Percent(result.waterPercent)
        measurement[MeasurementType.BONE] = Kg(result.boneMassKg)
        measurement[MeasurementType.BMR] = Kcal(result.basalMetabolicRateKcal.toFloat())
        measurement[MeasurementType.PROTEIN] = Percent(result.proteinPercent)
        measurement[MeasurementType.BMI] = result.bmi
        measurement[MeasurementType.VISCERAL_FAT] = result.visceralFatLevel.toFloat()
        measurement[MeasurementType.LBM] = Kg(result.leanBodyMassKg)
        measurement[TOTAL_MUSCLE] = Percent(result.totalMusclePercent)
        measurement[METABOLIC_AGE] = result.metabolicAge
    }

    private fun userKey(base: String, userId: Int): String = "$base/$userId"
}
