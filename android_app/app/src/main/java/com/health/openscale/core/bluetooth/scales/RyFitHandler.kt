package com.health.openscale.core.bluetooth.scales

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.*
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RyFitHandler : ScaleDeviceHandler() {

    companion object {
        val SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private const val GUEST_SLOT = 9
    }

    private var heightCm = 170
    private var age = 30
    private var gender = "男"

    // 测量暂存
    private var pendingWeight: Float? = null
    private var pendingFat: Float? = null
    private var pendingWater: Float? = null

    // 状态标志
    private var isMeasuring = false
    private var autoC0Triggered = false

    // 超时任务
    private var d2TimeoutJob: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.uppercase()
        if (name.contains("CHRONOCLOUD") || name.contains("RYFIT") ||
            device.address.uppercase().startsWith("D0:39:72")
        ) {
            return DeviceSupport(
                displayName = "RyFit (云悦) Smart Scale",
                capabilities = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC
                ),
                implemented = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION,
                    DeviceCapability.TIME_SYNC
                ),
                linkMode = LinkMode.CONNECT_GATT
            )
        }
        return null
    }

    override fun onConnected(user: ScaleUser) {
        heightCm = user.bodyHeight.toInt().coerceIn(50, 250)
        age = user.getAge()
        gender = if (user.gender == GenderType.MALE) "男" else "女"

        setNotifyOn(SERVICE_UUID, CHAR_UUID)
        sendTimeSync()
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_UUID || data.isEmpty()) return
        logD("Received data: ${data.toHexPreview(16)}")

        when (data[0].toInt() and 0xFF) {
            0xD2 -> parseLiveWeight(data)
            0xD0, 0xD1 -> {
                if (data.size >= 14) {
                    val flags = data[2].toInt() and 0xFF
                    if (flags and 0x01 == 1) parsePacket1(data)
                    else parsePacket2(data, user)
                }
            }
            0xFB -> handleResponse(data)
        }
    }

    // ---------------------------------------------------------------
    // 命令发送
    // ---------------------------------------------------------------

    private fun sendTimeSync() {
        val ts = System.currentTimeMillis() / 1000L
        val cmd = byteArrayOf(
            0xFA.toByte(), 0xF8.toByte(),
            (ts and 0xFF).toByte(), ((ts shr 8) and 0xFF).toByte(),
            ((ts shr 16) and 0xFF).toByte(), ((ts shr 24) and 0xFF).toByte()
        )
        writeTo(SERVICE_UUID, CHAR_UUID, cmd)
        logI("Sent time sync")
    }

    private fun sendC0() {
        refreshUserFromApp()
        val heightCode = (2 * heightCm - 200).coerceIn(0, 255)
        val genderCode = if (gender == "男") 0x01 else 0x00
        val cmd = byteArrayOf(
            0xC0.toByte(), GUEST_SLOT.toByte(), heightCode.toByte(),
            age.toByte(), genderCode.toByte()
        )
        writeTo(SERVICE_UUID, CHAR_UUID, cmd)
        logI("Sent C0 to guest slot $GUEST_SLOT")
    }

    private fun sendAck(data: ByteArray) {
        if (data.size < 3) return
        val ack = byteArrayOf(0xFA.toByte(), data[0], data[1], data[2])
        writeTo(SERVICE_UUID, CHAR_UUID, ack, withResponse = true)
    }

    private fun refreshUserFromApp(): Boolean {
        val appUser = currentAppUser()
        heightCm = appUser.bodyHeight.toInt().coerceIn(50, 250)
        age = appUser.getAge()
        gender = if (appUser.gender == GenderType.MALE) "男" else "女"
        return true
    }

    // ---------------------------------------------------------------
    // 自动 C0 逻辑
    // ---------------------------------------------------------------

    private fun parseLiveWeight(data: ByteArray) {
        if (data.size < 4) return
        val raw = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val weight = raw / 10.0f
        logI("Live weight: $weight kg")

        // 重量归零：取消所有超时，重置状态
        if (weight <= 0.1f) {
            if (isMeasuring || autoC0Triggered) {
                logI("Weight dropped to 0 – aborting measurement and timeouts")
                cancelTimeout()
                resetMeasurementState()
            }
            return
        }

        if (isMeasuring) return

        // 空闲状态下首次检测到有效重量
        if (!autoC0Triggered && weight > 0.1f) {
            autoC0Triggered = true
            logI("Delay 1s then send C0 burst")

            // 延迟 1 秒后发送 3 次 C0
            scope.launch {
                delay(1.seconds)
                repeat(3) {
                    sendC0()
                    delay(500.milliseconds)
                }
            }

            // 15 秒超时：如果仍未进入测量，重发 C0
            d2TimeoutJob?.cancel()
            d2TimeoutJob = scope.launch {
                delay(15.seconds)
                if (!isMeasuring) {
                    retriggerC0()
                }
            }
        }
    }

    private fun retriggerC0() {
        logI("Retriggering C0 due to: D2 timeout (15 s) – no Packet1 received")
        autoC0Triggered = false
        cancelTimeout()
        scope.launch {
            repeat(3) {
                sendC0()
                delay(500.milliseconds)
            }
        }
    }

    private fun cancelTimeout() {
        d2TimeoutJob?.cancel()
        d2TimeoutJob = null
    }

    private fun resetMeasurementState() {
        isMeasuring = false
        pendingWeight = null; pendingFat = null; pendingWater = null
        autoC0Triggered = false
    }

    // ---------------------------------------------------------------
    // 测量数据解析
    // ---------------------------------------------------------------

    private fun parsePacket1(data: ByteArray) {
        if (data.size < 14) return
        isMeasuring = true
        pendingWeight = ((data[8].toInt() and 0xFF) shl 8 or (data[9].toInt() and 0xFF)) / 10.0f
        pendingFat    = ((data[10].toInt() and 0xFF) shl 8 or (data[11].toInt() and 0xFF)) / 10.0f
        pendingWater  = ((data[12].toInt() and 0xFF) shl 8 or (data[13].toInt() and 0xFF)) / 10.0f
        sendAck(data)
    }

    private fun parsePacket2(data: ByteArray, user: ScaleUser) {
        if (data.size < 14 || pendingWeight == null) {
            resetMeasurementState()
            return
        }

        val muscle = ((data[4].toInt() and 0xFF) shl 8 or (data[5].toInt() and 0xFF)) / 10.0f
        val bonePct = ((data[6].toInt() and 0xFF) shl 8 or (data[7].toInt() and 0xFF)) / 10.0f
        val bmr = ((data[8].toInt() and 0xFF) shl 8 or (data[9].toInt() and 0xFF)).toFloat()
        val visceralLevel = data[12].toInt() and 0xFF

        val visceralFat = when {
            visceralLevel < 10  -> visceralLevel.toFloat()
            visceralLevel >= 10 -> 13.0f
            else -> visceralLevel.toFloat()
        }

        sendAck(data)

        val weight = pendingWeight!!
        val boneKg = if (weight > 0) weight * bonePct / 100.0f else bonePct / 100.0f * 70.0f
        val lbm = weight * (1 - (pendingFat!! / 100.0f))

        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            this.weight = weight
            fat = pendingFat!!
            water = pendingWater!!
            this.muscle = muscle
            bone = boneKg
            this.bmr = bmr
            this.visceralFat = visceralFat
            this.lbm = lbm
        }
        publish(measurement)
        logI("Published measurement: weight=$weight, fat=$pendingFat, water=$pendingWater, muscle=$muscle")

        // 测量完成，取消超时，重置状态
        cancelTimeout()
        resetMeasurementState()
    }

    // ---------------------------------------------------------------
    // 响应处理
    // ---------------------------------------------------------------

    private fun handleResponse(data: ByteArray) {
        if (data.size < 2) return
        when (data[1].toInt() and 0xFF) {
            0xF8 -> {
                logI("Time sync OK, sending initial C0")
                sendC0()
            }
        }
    }

    // ---------------------------------------------------------------
    // 配置界面
    // ---------------------------------------------------------------

    @Composable
    override fun DeviceConfigurationUi() {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("RyFit 智能体质分析仪", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("固定使用游客槽位 P9，无需额外设置。\n站上秤后延迟 1 秒自动激活，超时自动重试。", style = MaterialTheme.typography.bodySmall)
        }
    }

    // ---------------------------------------------------------------
    // 辅助方法
    // ---------------------------------------------------------------

    private fun ScaleUser.getAge(): Int {
        val calNow = Calendar.getInstance()
        val calBirth = Calendar.getInstance().apply { time = this@getAge.birthday }
        var age = calNow.get(Calendar.YEAR) - calBirth.get(Calendar.YEAR)
        if (calNow.get(Calendar.DAY_OF_YEAR) < calBirth.get(Calendar.DAY_OF_YEAR)) age--
        return age.coerceIn(10, 100)
    }
}