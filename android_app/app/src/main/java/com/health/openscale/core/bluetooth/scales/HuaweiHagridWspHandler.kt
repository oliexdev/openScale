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

import android.util.SparseArray
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.HuaweiHagridSecretProvider
import com.health.openscale.core.bluetooth.libs.HuaweiHagridWspLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Huawei/Honor Hagrid WSP scale handler.
 *
 * Authentication uses only CAK/C1/C2 values explicitly configured in driver
 * settings. This handler intentionally does not contain Huawei Health key
 * extraction, Frida, root, APK, or native white-box logic.
 */
class HuaweiHagridWspHandler(
    private val secretProvider: HuaweiHagridSecretProvider =
        HuaweiHagridSecretProvider.DriverSettings(
            cakKey = SETTINGS_KEY_CAK_HEX,
            c1Key = SETTINGS_KEY_C1_HEX,
            c2Key = SETTINGS_KEY_C2_HEX
        ),
    private val randomBytes: (Int) -> ByteArray = { size ->
        ByteArray(size).also { SecureRandom().nextBytes(it) }
    }
) : ScaleDeviceHandler() {

    private val svcUserData = uuid16(0x181C)
    private val svcCurrentTime = uuid16(0x1805)
    private val svcBodyComposition = uuid16(0x181B)
    private val svcWeightScale = uuid16(0x181D)

    private val chrRequestAuth = UUID.fromString("02b2a08e-f8b0-4047-b1fd-f4e0efeee679")
    private val chrAuthToken = UUID.fromString("32330a04-15d9-421a-91c5-2a2d5c7525c9")
    private val chrSendWorkKey = UUID.fromString("a3d330f8-b84f-4f48-a78c-f8d1e33b597a")
    private val chrBindRequest = UUID.fromString("42596cbe-d291-4da3-8ca6-d1ae5d1c9174")
    private val chrSetUserInfo = UUID.fromString("8cc61d7d-66c0-4802-89c3-38c5a163592e")
    private val chrGetManagerInfo = UUID.fromString("4338c65e-ed8e-4085-bbea-a25e33ca6b54")
    private val chrCurrentTime = uuid16(0x2A2B)
    private val chrProductInfo = UUID.fromString("75143e79-f878-4a00-a628-edc40509de7e")
    private val chrScaleVersion = UUID.fromString("1f5d3d5c-496d-4290-af03-c7a8d5419741")
    private val chrGetWeightUnit = UUID.fromString("7e6dbc73-42e7-45b9-a6ec-6aa2d7834695")
    private val chrMeasurementStatusPoll = UUID.fromString("bfc36f6e-4150-4a4b-9052-3d359e52962e")
    private val chrMeasurementStatusResult = UUID.fromString("ba216311-1787-472b-bef6-3eb29e62293e")
    private val chrWeightMeasurement = uuid16(0x2A9D)
    private val chrBodyCompositionMeasurement = uuid16(0x2A9C)
    private val chrRealtimeWeight = UUID.fromString("46797c17-d639-488d-9476-4789e8472878")
    private val chrHistoryWeight = UUID.fromString("0212f42a-5f19-4bc1-ba52-d7ec7ccb71a4")

    private val accumulators = mutableMapOf<UUID, HuaweiHagridWspLib.FrameAccumulator>()
    private var pendingDeviceAddress: String? = null
    private var pendingProductProfile = HuaweiHagridWspLib.productProfileForMarker(null)
    private var pendingCapabilityBits: HuaweiHagridWspLib.HagridCapabilityBits? = null
    private var configuredSecrets: HuaweiHagridWspLib.HagridSecrets? = null
    private var pendingRandA: ByteArray? = null
    private var pendingRandB: ByteArray? = null
    private var session: HuaweiHagridWspLib.HagridSession? = null
    private var handshakeState = HandshakeState.IDLE
    private var currentUserInfoSync: ScaleUser? = null
    private var userInfoSyncGeneration = 0
    private var lastManagerInfo: HuaweiHagridWspLib.HagridManagerInfo? = null
    private var pendingProfileSyncAfterManagerInfo: ScaleUser? = null
    private var managerInfoReadGeneration = 0
    private var historyReadActive = false
    private var historyReadCount = 0
    private val publishedHistoryKeys = mutableSetOf<String>()
    private var measurementStatusPollingActive = false
    private var measurementStatusPollCount = 0
    private var measurementStatusPollJob: Job? = null
    private var measurementStatusReady = false
    private var postStatusMeasurementReadsStarted = false
    private var realtimeReadRequestCount = 0

    @Composable
    override fun DeviceConfigurationUi() {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Huawei Hagrid WSP secrets",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Configure CAK, C1, and C2 as 32 hex characters each. Values stay in per-driver app settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecretHexField("CAK", SETTINGS_KEY_CAK_HEX)
            SecretHexField("C1", SETTINGS_KEY_C1_HEX)
            SecretHexField("C2", SETTINGS_KEY_C2_HEX)
        }
    }

    @Composable
    private fun SecretHexField(label: String, key: String) {
        val persistedValue = settingsGetString(key) ?: ""
        var inputValue by remember(persistedValue) { mutableStateOf(persistedValue) }
        var lastSavedValue by remember(persistedValue) { mutableStateOf(persistedValue) }
        val isValid = inputValue.length == 32
        val showError = inputValue.isNotEmpty() && !isValid
        val isSaved = inputValue.isNotEmpty() && isValid && inputValue == lastSavedValue

        LaunchedEffect(inputValue) {
            when {
                inputValue.isEmpty() && lastSavedValue.isNotEmpty() -> {
                    settingsPutString(key, "")
                    lastSavedValue = ""
                }
                isValid && inputValue != lastSavedValue -> {
                    settingsPutString(key, inputValue)
                    lastSavedValue = inputValue
                }
            }
        }

        OutlinedTextField(
            value = inputValue,
            onValueChange = { newValue ->
                val filtered = newValue
                    .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    .uppercase(Locale.US)
                if (filtered.length <= 32) {
                    inputValue = filtered
                }
            },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            singleLine = true,
            isError = showError,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = when {
                            showError -> "Expected 32 hex characters"
                            isSaved -> "Saved"
                            else -> ""
                        },
                        color = if (showError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text("${inputValue.length}/32")
                }
            }
        )
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val marker = findProductMarker(device) ?: return null
        pendingDeviceAddress = device.address
        pendingProductProfile = HuaweiHagridWspLib.productProfileForMarker(marker)
        pendingCapabilityBits = findCapabilityBits(device)

        val capabilities = setOf(
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.TIME_SYNC,
            DeviceCapability.USER_SYNC,
            DeviceCapability.HISTORY_READ,
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.UNIT_CONFIG
        )

        return DeviceSupport(
            displayName = pendingProductProfile.displayName,
            capabilities = capabilities,
            implemented = setOf(
                DeviceCapability.TIME_SYNC,
                DeviceCapability.LIVE_WEIGHT_STREAM,
                DeviceCapability.BODY_COMPOSITION,
                DeviceCapability.HISTORY_READ,
            ),
            tuningProfile = TuningProfile.Conservative,
            linkMode = LinkMode.CONNECT_GATT
        )
    }

    override fun onConnected(user: ScaleUser) {
        accumulators.clear()
        pendingRandA = null
        pendingRandB = null
        session = null
        stopMeasurementStatusPolling()
        configuredSecrets = loadConfiguredSecrets()
        handshakeState = if (configuredSecrets == null) {
            HandshakeState.PROBE_ONLY
        } else {
            HandshakeState.WAITING_AUTH_CHALLENGE
        }
        currentUserInfoSync = null
        userInfoSyncGeneration++
        lastManagerInfo = null
        pendingProfileSyncAfterManagerInfo = null
        managerInfoReadGeneration++
        historyReadActive = false
        historyReadCount = 0
        publishedHistoryKeys.clear()
        postStatusMeasurementReadsStarted = false
        realtimeReadRequestCount = 0
        measurementStatusReady = false

        setNotifyIfPresent(svcUserData, chrRequestAuth)
        setNotifyIfPresent(svcUserData, chrAuthToken)
        setNotifyIfPresent(svcUserData, chrSendWorkKey)
        setNotifyIfPresent(svcUserData, chrBindRequest)
        setNotifyIfPresent(svcUserData, chrSetUserInfo)
        setNotifyIfPresent(svcUserData, chrGetManagerInfo)
        setNotifyIfPresent(svcCurrentTime, chrCurrentTime)
        setNotifyIfPresent(svcCurrentTime, chrProductInfo)
        setNotifyIfPresent(svcCurrentTime, chrScaleVersion)
        setNotifyIfPresent(svcCurrentTime, chrGetWeightUnit)
        setNotifyIfPresent(svcCurrentTime, chrMeasurementStatusPoll)
        setNotifyIfPresent(svcCurrentTime, chrMeasurementStatusResult)
        setNotifyIfPresent(svcBodyComposition, chrBodyCompositionMeasurement)
        setNotifyIfPresent(svcWeightScale, chrWeightMeasurement)
        setNotifyIfPresent(svcBodyComposition, chrRealtimeWeight)
        setNotifyIfPresent(svcBodyComposition, chrHistoryWeight)

        userInfo(R.string.bt_info_step_on_scale)
        logI(
            if (configuredSecrets == null) {
                "Huawei Hagrid WSP secrets missing; using probe-only mode"
            } else {
                "Huawei Hagrid WSP secrets configured; starting authentication"
            }
        )
        logDeviceProfile("scan")

        writeWspPlainIfPresent(svcUserData, chrRequestAuth, ByteArray(0))
        if (configuredSecrets == null) {
            writeWspPlainIfPresent(svcCurrentTime, chrCurrentTime, HuaweiHagridWspLib.currentTimePayload())
        }
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (HuaweiHagridWspLib.hasNotificationFramePrefix(data)) {
            val accumulator = accumulators.getOrPut(characteristic) {
                HuaweiHagridWspLib.FrameAccumulator()
            }
            val payload = accumulator.ingest(data) ?: return
            handleWspNotification(characteristic, payload, accumulator.lastCompletedEncrypted, user)
            return
        }

        handleRawNotification(characteristic, data, user)
    }

    override fun onDisconnected() {
        stopMeasurementStatusPolling()
        accumulators.clear()
        pendingRandA = null
        pendingRandB = null
        session = null
        configuredSecrets = null
        currentUserInfoSync = null
        userInfoSyncGeneration++
        lastManagerInfo = null
        pendingProfileSyncAfterManagerInfo = null
        managerInfoReadGeneration++
        historyReadActive = false
        historyReadCount = 0
        publishedHistoryKeys.clear()
        postStatusMeasurementReadsStarted = false
        realtimeReadRequestCount = 0
        measurementStatusReady = false
        handshakeState = HandshakeState.IDLE
    }

    private fun handleWspNotification(
        characteristic: UUID,
        rawPayload: ByteArray,
        encrypted: Boolean,
        user: ScaleUser
    ) {
        val payload = if (encrypted) {
            val activeSession = session
            if (activeSession == null) {
                logW("Encrypted Hagrid notification before work key: chr=${characteristicLabel(characteristic)} len=${rawPayload.size}")
                return
            }
            runCatching { HuaweiHagridWspLib.decryptPayload(rawPayload, activeSession.workKey) }
                .getOrElse { error ->
                    logW("Failed to decrypt Hagrid notification chr=${characteristicLabel(characteristic)}: ${error.message}")
                    return
                }
        } else {
            rawPayload
        }

        logD(
            "Huawei Hagrid WSP notification chr=${characteristicLabel(characteristic)} " +
                "encrypted=$encrypted rawLen=${rawPayload.size} plainLen=${payload.size}"
        )

        when (characteristic) {
            chrRealtimeWeight,
            chrBindRequest,
            chrBodyCompositionMeasurement,
            chrWeightMeasurement -> handleRealtimePayload(payload, user)

            chrRequestAuth -> handleAuthChallenge(payload)
            chrAuthToken -> handleAuthResponse(payload)
            chrSendWorkKey -> handleWorkKeyAck(payload, user)
            chrSetUserInfo -> handleUserInfoAck(payload)
            chrGetManagerInfo -> handleManagerInfoPayload(payload)
            chrProductInfo -> handleProductInfoPayload(payload)
            chrScaleVersion -> handleScaleVersionPayload(payload)
            chrGetWeightUnit -> handleWeightUnitPayload(payload)
            chrMeasurementStatusPoll,
            chrMeasurementStatusResult -> handleMeasurementStatusPayload(payload)
            chrHistoryWeight -> handleHistoryWeightPayload(payload, user)
            chrCurrentTime -> logD("Huawei Hagrid time-sync ack len=${payload.size}")
            else -> logD("Unhandled Hagrid WSP notification chr=${characteristicLabel(characteristic)} len=${payload.size}")
        }
    }

    private fun handleRawNotification(characteristic: UUID, payload: ByteArray, user: ScaleUser) {
        when (characteristic) {
            chrWeightMeasurement -> handleStandardWeightPayload(payload, user)
            chrBodyCompositionMeasurement -> handleStandardBodyCompositionPayload(payload, user)
            chrRealtimeWeight,
            chrBindRequest -> handleRealtimePayload(payload, user)
            chrSetUserInfo -> handleUserInfoAck(payload)
            chrGetManagerInfo -> handleManagerInfoPayload(payload)
            chrProductInfo -> handleProductInfoPayload(payload)
            chrScaleVersion -> handleScaleVersionPayload(payload)
            chrGetWeightUnit -> handleWeightUnitPayload(payload)
            chrMeasurementStatusPoll,
            chrMeasurementStatusResult -> handleMeasurementStatusPayload(payload)
            chrHistoryWeight -> handleHistoryWeightPayload(payload, user)
            else -> logD("Unhandled Hagrid raw notification chr=${characteristicLabel(characteristic)} len=${payload.size}")
        }
    }

    private fun handleAuthChallenge(payload: ByteArray) {
        if (payload.size != 16) {
            logW("Ignoring invalid Hagrid auth challenge length=${payload.size}")
            return
        }

        val secrets = configuredSecrets
        if (secrets == null) {
            logD("Hagrid auth challenge received without configured secrets; staying probe-only")
            writeWspPlainIfPresent(svcBodyComposition, chrRealtimeWeight, ByteArray(0))
            return
        }

        val randB = randomBytes(16)
        require(randB.size == 16) { "randomBytes(16) returned ${randB.size} bytes" }
        pendingRandA = payload.copyOf()
        pendingRandB = randB
        handshakeState = HandshakeState.AUTH_TOKEN_SENT

        val authPayload = HuaweiHagridWspLib.buildAuthTokenPayload(payload, randB, secrets.cak)
        logI("Sending Huawei Hagrid auth token (${authPayload.size} bytes)")
        writeWspPlainIfPresent(svcUserData, chrAuthToken, authPayload)
    }

    private fun handleAuthResponse(payload: ByteArray) {
        val secrets = configuredSecrets
        val randA = pendingRandA
        val randB = pendingRandB
        if (secrets == null || randA == null || randB == null) {
            logW("Hagrid auth response received without pending auth state")
            return
        }

        if (!HuaweiHagridWspLib.isValidAuthResponsePayload(payload, randA, randB, secrets.cak)) {
            handshakeState = HandshakeState.AUTH_FAILED
            logW("Huawei Hagrid auth response verification failed (${payload.size} bytes)")
            return
        }

        val address = getPeripheral()?.address ?: pendingDeviceAddress
        if (address.isNullOrBlank()) {
            handshakeState = HandshakeState.AUTH_FAILED
            logW("Cannot derive Huawei Hagrid root key without Bluetooth address")
            return
        }

        val rootKey = runCatching {
            HuaweiHagridWspLib.deriveHagridRootKey(
                secrets.c1,
                secrets.c2,
                HuaweiHagridWspLib.hagridC3FromBluetoothAddress(address)
            )
        }.getOrElse { error ->
            handshakeState = HandshakeState.AUTH_FAILED
            logW("Cannot derive Huawei Hagrid root key: ${error.message}")
            return
        }

        val workKey = randomBytes(16)
        val iv = randomBytes(16)
        require(workKey.size == 16) { "randomBytes(16) returned ${workKey.size} bytes for workKey" }
        require(iv.size == 16) { "randomBytes(16) returned ${iv.size} bytes for work-key IV" }
        session = HuaweiHagridWspLib.HagridSession(randA.copyOf(), randB.copyOf(), rootKey, workKey.copyOf())
        handshakeState = HandshakeState.WORK_KEY_SENT

        logI("Huawei Hagrid auth verified; sending encrypted work key")
        writeWspFramesIfPresent(
            svcUserData,
            chrSendWorkKey,
            HuaweiHagridWspLib.buildSendWorkKeyFrames(workKey, rootKey, iv)
        )
    }

    private fun handleWorkKeyAck(payload: ByteArray, user: ScaleUser) {
        val status = payload.firstOrNull()?.toInt()?.and(0xFF)
        if (status != 0x00) {
            handshakeState = HandshakeState.AUTH_FAILED
            logW("Huawei Hagrid work key rejected: status=${status ?: -1}")
            return
        }

        logI("Huawei Hagrid work key accepted; syncing time and selected profile")
        writeWspPlainIfPresent(svcCurrentTime, chrCurrentTime, HuaweiHagridWspLib.currentTimePayload())
        readManagerInfoBeforeProfileSync(user)
    }

    private fun readManagerInfoBeforeProfileSync(user: ScaleUser) {
        if (!hasCharacteristic(svcUserData, chrGetManagerInfo)) {
            beginUserProfileSync(user)
            return
        }

        pendingProfileSyncAfterManagerInfo = user
        val generation = ++managerInfoReadGeneration
        writeWspPlainIfPresent(svcUserData, chrGetManagerInfo, ByteArray(0))
        scope.launch {
            delay(MANAGER_INFO_TIMEOUT_MS)
            if (pendingProfileSyncAfterManagerInfo?.id == user.id && managerInfoReadGeneration == generation) {
                logW("Huawei Hagrid manager-info read timed out; using local HUID")
                pendingProfileSyncAfterManagerInfo = null
                beginUserProfileSync(user)
            }
        }
    }

    private fun beginUserProfileSync(selectedUser: ScaleUser) {
        if (!sendEncryptedUserInfo(selectedUser)) {
            handshakeState = HandshakeState.READY
            sendPostUserReads()
            return
        }

        currentUserInfoSync = selectedUser
        handshakeState = HandshakeState.USER_INFO_SENT
        val generation = ++userInfoSyncGeneration
        scope.launch {
            delay(USER_INFO_ACK_TIMEOUT_MS)
            if (
                handshakeState == HandshakeState.USER_INFO_SENT &&
                userInfoSyncGeneration == generation &&
                currentUserInfoSync?.id == selectedUser.id
            ) {
                logW("Huawei Hagrid set-user-info ack timed out; continuing with post-auth reads")
                currentUserInfoSync = null
                handshakeState = HandshakeState.READY
                sendPostUserReads()
            }
        }
    }

    private fun sendEncryptedUserInfo(user: ScaleUser): Boolean {
        val activeSession = session
        if (activeSession == null) {
            logW("Cannot send Huawei Hagrid user-info without an active work key")
            return false
        }
        if (!hasCharacteristic(svcUserData, chrSetUserInfo)) {
            logD("Characteristic missing, skip encrypted Huawei Hagrid user-info write")
            return false
        }

        val info = buildUserInfo(user)
        val plaintext = HuaweiHagridWspLib.buildUserInfoPayload(info)
        val iv = randomBytes(16)
        require(iv.size == 16) { "randomBytes(16) returned ${iv.size} bytes for user-info IV" }

        logD(
            "Sending encrypted Huawei Hagrid user-info: userId=${user.id} " +
                "payloadLen=${plaintext.size} huidPresent=${info.huid.isNotBlank()}"
        )
        HuaweiHagridWspLib.buildEncryptedWriteFrames(plaintext, activeSession.workKey, iv)
            .forEach { frame -> writeTo(svcUserData, chrSetUserInfo, frame, withResponse = true) }
        return true
    }

    private fun handleUserInfoAck(payload: ByteArray) {
        val status = payload.firstOrNull()?.toInt()?.and(0xFF)
        val accepted = status == 0x00
        if (accepted) {
            logI("Huawei Hagrid set-user-info accepted")
        } else {
            logW("Huawei Hagrid set-user-info returned status=${status ?: -1}")
        }

        if (handshakeState == HandshakeState.USER_INFO_SENT) {
            currentUserInfoSync = null
            handshakeState = HandshakeState.READY
            sendPostUserReads()
        }
    }

    private fun sendPostUserReads() {
        writeWspPlainIfPresent(svcCurrentTime, chrProductInfo, ByteArray(0))
        writeWspPlainIfPresent(svcUserData, chrGetManagerInfo, ByteArray(0))
        writeWspPlainIfPresent(svcCurrentTime, chrScaleVersion, ByteArray(0))
        writeWspPlainIfPresent(svcCurrentTime, chrGetWeightUnit, ByteArray(0))
        postStatusMeasurementReadsStarted = false
        realtimeReadRequestCount = 0
        startMeasurementStatusPolling()
    }

    private fun buildUserInfo(user: ScaleUser): HuaweiHagridWspLib.HagridUserInfo {
        val lastWeight = lastMeasurementFor(user.id)?.weight
        val configuredWeight = when {
            lastWeight != null && lastWeight.isFinite() && lastWeight > 0f -> lastWeight
            user.initialWeight.isFinite() && user.initialWeight > 0f -> user.initialWeight
            else -> 0f
        }
        val heightCm = if (user.bodyHeight.isFinite() && user.bodyHeight > 0f) {
            user.bodyHeight.roundToInt().coerceIn(0, 0xFFFF)
        } else {
            0
        }

        return HuaweiHagridWspLib.HagridUserInfo(
            huid = lastManagerInfo?.huid?.takeIf { it.isNotBlank() } ?: localSyntheticHuid(),
            uid = "u:%08X".format(Locale.US, user.id),
            gender = if (user.gender == GenderType.MALE) 0 else 1,
            ageYears = user.age.coerceIn(0, 255),
            heightCm = heightCm,
            weightKg = configuredWeight,
        )
    }

    private fun localSyntheticHuid(): String {
        val compactAddress = (getPeripheral()?.address ?: pendingDeviceAddress)
            ?.filter { it != ':' && it != '-' }
            ?.uppercase(Locale.US)
            ?.takeIf { it.length == 12 }
            ?: "000000000000"
        return "openscale:$compactAddress"
    }

    private fun handleRealtimePayload(payload: ByteArray, user: ScaleUser) {
        val parsed = HuaweiHagridWspLib.parseRealtimeMeasurement(payload)
        if (parsed == null || parsed.weightKg <= 0f) {
            logD("Ignoring unsupported Hagrid realtime payload len=${payload.size}")
            return
        }

        userInfo(R.string.bluetooth_scale_info_measuring_weight, parsed.weightKg)
        logD(
            "Hagrid realtime measurement parsed len=${parsed.rawLength} " +
                "lowCount=${parsed.lowFrequencyImpedance.size} highCount=${parsed.highFrequencyImpedance.size} saved=false"
        )
    }

    private fun handleHistoryWeightPayload(payload: ByteArray, user: ScaleUser) {
        val parsed = HuaweiHagridWspLib.parseHistoryMeasurement(payload)
        if (parsed == null || parsed.weightKg <= 0f) {
            historyReadActive = false
            logD("Hagrid history read finished or unsupported len=${payload.size}")
            return
        }

        historyReadActive = true
        historyReadCount += 1
        val key = parsed.historyDedupeKey
        val shouldPublish = key == null || publishedHistoryKeys.add(key)
        if (shouldPublish) {
            publishHagridMeasurement(parsed, user)
        } else {
            logD("Skipped duplicate Hagrid history measurement")
        }

        val complete = parsed.isHistoryComplete == true
        if (!complete && historyReadCount < MAX_HISTORY_RECORDS) {
            writeWspPlainIfPresent(svcBodyComposition, chrHistoryWeight, byteArrayOf(0x00))
        } else {
            historyReadActive = false
            logD(
                "Hagrid history read stopped: complete=$complete count=$historyReadCount " +
                    "saved=${publishedHistoryKeys.size}"
            )
        }
    }

    private fun publishHagridMeasurement(
        parsed: HuaweiHagridWspLib.HagridWeightMeasurement,
        user: ScaleUser
    ) {
        val low = parsed.representativeLowOhm ?: 0.0
        val high = parsed.representativeHighOhm ?: low
        val measurement = ScaleMeasurement(
            userId = user.id,
            dateTime = parsed.timestamp ?: Date(),
            weight = parsed.weightKg,
            fat = parsed.fatPercent.takeIf { it > 0f && it < 80f } ?: 0f,
            heartRate = parsed.heartRateBpm ?: 0,
            impedance = high,
            impedanceLow = low,
        )
        logI(
            "Publishing Huawei Hagrid measurement: lowCount=${parsed.lowFrequencyImpedance.size} " +
                "highCount=${parsed.highFrequencyImpedance.size}"
        )
        publish(measurement)
    }

    private fun handleStandardWeightPayload(payload: ByteArray, user: ScaleUser) {
        val parsed = HuaweiHagridWspLib.parseStandardWeightMeasurement(payload)
        val weight = parsed?.weightKg
        if (weight == null || weight <= 0f) {
            logD("Ignoring unsupported standard weight payload len=${payload.size}")
            return
        }

        publish(
            ScaleMeasurement(
                userId = user.id,
                dateTime = parsed.timestamp ?: Date(),
                weight = weight,
            )
        )
    }

    private fun handleStandardBodyCompositionPayload(payload: ByteArray, user: ScaleUser) {
        val parsed = HuaweiHagridWspLib.parseStandardBodyCompositionMeasurement(payload)
        if (parsed == null) {
            logD("Ignoring unsupported standard body-composition payload len=${payload.size}")
            return
        }
        if (parsed.weightKg == null && parsed.fatPercent == null && parsed.impedanceOhm == null) {
            return
        }

        publish(
            ScaleMeasurement(
                userId = user.id,
                dateTime = parsed.timestamp ?: Date(),
                weight = parsed.weightKg ?: 0f,
                fat = parsed.fatPercent?.takeIf { it > 0f && it < 80f } ?: 0f,
                muscle = parsed.musclePercent?.takeIf { it > 0f && it < 100f } ?: 0f,
                impedance = parsed.impedanceOhm ?: 0.0,
            )
        )
    }

    private fun handleManagerInfoPayload(payload: ByteArray) {
        val parsed = HuaweiHagridWspLib.parseManagerInfo(payload)
        if (parsed == null) {
            logD("Ignoring unsupported Hagrid manager-info payload len=${payload.size}")
            return
        }
        lastManagerInfo = parsed
        logD("Huawei Hagrid manager-info read len=${parsed.rawLength} huidPresent=${parsed.huid.isNotBlank()}")

        pendingProfileSyncAfterManagerInfo?.let { user ->
            pendingProfileSyncAfterManagerInfo = null
            beginUserProfileSync(user)
        }
    }

    private fun handleProductInfoPayload(payload: ByteArray) {
        val parsed = HuaweiHagridWspLib.parseProductInfo(payload)
        if (parsed == null) {
            logD("Ignoring unsupported Hagrid product-info payload len=${payload.size}")
            return
        }
        pendingProductProfile = HuaweiHagridWspLib.productProfileForMarker(parsed.smartProductId)
        logDeviceProfile("product-info")
    }

    private fun handleScaleVersionPayload(payload: ByteArray) {
        val parsed = HuaweiHagridWspLib.parseScaleVersion(payload)
        if (parsed == null) {
            logD("Ignoring unsupported Hagrid scale-version payload len=${payload.size}")
            return
        }
        logD("Huawei Hagrid scale version=${parsed.version} len=${parsed.rawLength}")
    }

    private fun handleWeightUnitPayload(payload: ByteArray) {
        val parsed = HuaweiHagridWspLib.parseWeightUnit(payload)
        if (parsed == null) {
            logD("Ignoring unsupported Hagrid weight-unit payload len=${payload.size}")
            return
        }
        logD("Huawei Hagrid weight unit=${parsed.label} raw=${parsed.raw}")
    }

    private fun handleMeasurementStatusPayload(payload: ByteArray) {
        val status = payload.firstOrNull()?.toInt()?.and(0xFF)
        logD("Huawei Hagrid measurement-status len=${payload.size} status=${status ?: -1}")
        if (status == 0x00) {
            measurementStatusReady = true
            sendMeasurementDataRequests()
        }
    }

    private fun startMeasurementStatusPolling() {
        if (!hasCharacteristic(svcCurrentTime, chrMeasurementStatusPoll)) {
            sendMeasurementDataRequests()
            return
        }

        measurementStatusPollingActive = true
        measurementStatusReady = false
        measurementStatusPollCount = 0
        sendMeasurementStatusPoll()

        measurementStatusPollJob?.cancel()
        measurementStatusPollJob = scope.launch {
            while (
                measurementStatusPollingActive &&
                !measurementStatusReady &&
                measurementStatusPollCount < MAX_STATUS_POLLS
            ) {
                delay(MEASUREMENT_STATUS_POLL_INTERVAL_MS)
                if (measurementStatusPollingActive && !measurementStatusReady) {
                    sendMeasurementStatusPoll()
                }
            }
            if (measurementStatusPollingActive && !measurementStatusReady) {
                logW("Huawei Hagrid measurement status timed out; requesting measurements directly")
                sendMeasurementDataRequests()
            }
        }
    }

    private fun stopMeasurementStatusPolling() {
        measurementStatusPollingActive = false
        measurementStatusPollJob?.cancel()
        measurementStatusPollJob = null
        measurementStatusPollCount = 0
    }

    private fun sendMeasurementStatusPoll() {
        measurementStatusPollCount += 1
        writeWspPlainIfPresent(
            svcCurrentTime,
            chrMeasurementStatusPoll,
            ByteArray(0),
            withResponse = false
        )
    }

    private fun sendMeasurementDataRequests() {
        stopMeasurementStatusPolling()
        if (postStatusMeasurementReadsStarted) {
            sendRealtimeMeasurementRequest()
            return
        }

        postStatusMeasurementReadsStarted = true
        if (hasCharacteristic(svcBodyComposition, chrHistoryWeight)) {
            historyReadActive = true
            historyReadCount = 0
            writeWspPlainIfPresent(svcBodyComposition, chrHistoryWeight, ByteArray(0))
        }
        sendRealtimeMeasurementRequest()
    }

    private fun sendRealtimeMeasurementRequest() {
        realtimeReadRequestCount += 1
        writeWspPlainIfPresent(svcBodyComposition, chrRealtimeWeight, ByteArray(0))
    }

    private fun setNotifyIfPresent(service: UUID, characteristic: UUID) {
        if (hasCharacteristic(service, characteristic)) {
            setNotifyOn(service, characteristic)
        }
    }

    private fun writeWspPlainIfPresent(
        service: UUID,
        characteristic: UUID,
        payload: ByteArray,
        withResponse: Boolean = true
    ) {
        if (!hasCharacteristic(service, characteristic)) return
        HuaweiHagridWspLib.buildWriteFrames(payload, encrypted = false)
            .forEach { frame -> writeTo(service, characteristic, frame, withResponse) }
    }

    private fun writeWspFramesIfPresent(
        service: UUID,
        characteristic: UUID,
        frames: List<ByteArray>
    ) {
        if (!hasCharacteristic(service, characteristic)) return
        frames.forEach { frame -> writeTo(service, characteristic, frame, withResponse = true) }
    }

    private fun loadConfiguredSecrets(): HuaweiHagridWspLib.HagridSecrets? =
        runCatching {
            secretProvider.load(
                getString = { key -> settingsGetString(key) },
                warn = { message -> logW(message) }
            )
        }
            .onFailure { logW("Invalid Huawei Hagrid secret settings: ${it.message}") }
            .getOrNull()

    private fun logDeviceProfile(source: String) {
        val highFrequency = pendingCapabilityBits?.supportsHighFrequencyImpedance
            ?: pendingProductProfile.expectedHighFrequencyImpedance
        logD(
            "Huawei Hagrid profile source=$source family=${pendingProductProfile.family} " +
                "display=${pendingProductProfile.displayName} highFrequency=$highFrequency"
        )
    }

    private fun findProductMarker(device: ScannedDeviceInfo): String? {
        extractProductMarker(device.name)?.let { return it }
        asciiFromManufacturerData(device.manufacturerData).forEach { text ->
            extractProductMarker(text)?.let { return it }
        }
        asciiFromServiceData(device.serviceData).forEach { text ->
            extractProductMarker(text)?.let { return it }
        }
        return null
    }

    private fun findCapabilityBits(device: ScannedDeviceInfo): HuaweiHagridWspLib.HagridCapabilityBits? {
        val texts = buildList {
            add(device.name)
            addAll(asciiFromManufacturerData(device.manufacturerData))
            addAll(asciiFromServiceData(device.serviceData))
        }

        for (text in texts) {
            val match = CAPABILITY_PATTERN.find(text) ?: continue
            HuaweiHagridWspLib.parseCapabilityBits(match.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun extractProductMarker(raw: String?): String? {
        val text = raw?.uppercase(Locale.US) ?: return null
        return PRODUCT_MARKERS.firstOrNull { marker -> text.contains(marker) }
    }

    private fun asciiFromManufacturerData(data: SparseArray<ByteArray>?): List<String> {
        if (data == null) return emptyList()
        return buildList {
            for (index in 0 until data.size()) {
                val bytes = data.valueAt(index) ?: continue
                add(bytes.toAsciiString())
            }
        }
    }

    private fun asciiFromServiceData(data: Map<UUID, ByteArray>): List<String> =
        data.values.map { it.toAsciiString() }

    private fun ByteArray.toAsciiString(): String =
        map { byte ->
            val value = byte.toInt() and 0xFF
            if (value in 0x20..0x7E) value.toChar() else ' '
        }.joinToString(separator = "")

    private fun characteristicLabel(characteristic: UUID): String =
        when (characteristic) {
            chrRequestAuth -> "request-auth"
            chrAuthToken -> "auth-token"
            chrSendWorkKey -> "send-work-key"
            chrBindRequest -> "bind-request"
            chrSetUserInfo -> "set-user-info"
            chrGetManagerInfo -> "get-manager-info"
            chrCurrentTime -> "current-time"
            chrProductInfo -> "product-info"
            chrScaleVersion -> "scale-version"
            chrGetWeightUnit -> "weight-unit"
            chrMeasurementStatusPoll -> "measurement-status-poll"
            chrMeasurementStatusResult -> "measurement-status-result"
            chrWeightMeasurement -> "weight-measurement"
            chrBodyCompositionMeasurement -> "body-composition-measurement"
            chrRealtimeWeight -> "realtime-weight"
            chrHistoryWeight -> "history-weight"
            else -> characteristic.toString()
        }

    private enum class HandshakeState {
        IDLE,
        PROBE_ONLY,
        WAITING_AUTH_CHALLENGE,
        AUTH_TOKEN_SENT,
        WORK_KEY_SENT,
        USER_INFO_SENT,
        READY,
        AUTH_FAILED
    }

    companion object {
        const val SETTINGS_KEY_CAK_HEX = "ble/huawei_hagrid/cak_hex"
        const val SETTINGS_KEY_C1_HEX = "ble/huawei_hagrid/c1_hex"
        const val SETTINGS_KEY_C2_HEX = "ble/huawei_hagrid/c2_hex"

        private const val MANAGER_INFO_TIMEOUT_MS = 2500L
        private const val USER_INFO_ACK_TIMEOUT_MS = 3000L
        private const val MEASUREMENT_STATUS_POLL_INTERVAL_MS = 3000L
        private const val MAX_STATUS_POLLS = 30
        private const val MAX_HISTORY_RECORDS = 64

        private val PRODUCT_MARKERS = listOf(
            "HUAWEI SCALE 2 PRO",
            "HAG-B19",
            "HAGRID-B19",
            "HUAWEI SCALE 3 PRO",
            "HAGRID-B29",
            "HAGRID2021-B19",
            "HUAWEI SCALE 3",
            "HEM-B19",
            "HERM-B19",
            "HONOR SCALE 2",
            "LUP-B19",
            "LUPIN-B19HN",
            "DOBBY-B19",
            "M00F",
            "M00D",
            "M0CJ",
            "N001",
            "007B",
        )

        private val CAPABILITY_PATTERN =
            Regex("""(?i)(?:capacity|capability|caps?)[:=]([0-9a-f]+)""")
    }
}
