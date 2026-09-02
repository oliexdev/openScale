package com.health.openscale.core.bluetooth.scales

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import androidx.core.util.isEmpty
import androidx.core.util.size
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.MiScaleLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale
import com.health.openscale.core.data.Bpm
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent

class EufyC20Handler : ScaleDeviceHandler() {
    private val MANUFACTURER_ID = 48228
    private val deviceSupport = DeviceSupport(
        displayName = "Eufy C20 (T9130)",
        capabilities = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION,
            DeviceCapability.HISTORY_READ
        ),
        implemented = setOf(
            DeviceCapability.LIVE_WEIGHT_STREAM,
            DeviceCapability.BODY_COMPOSITION
        ),
        tuningProfile = TuningProfile.Conservative,
        linkMode = LinkMode.BROADCAST_ONLY
    )

    // single simple global stored measurement to merge into
    private var stored: ScaleMeasurement? = null

    private fun extractManufacturerIds(m: Any?): Iterable<Int> = when (m) {
        is Map<*, *> -> m.keys.filterIsInstance<Int>()
        is SparseArray<*> -> (0 until m.size).map { m.keyAt(it) }
        is List<*> -> m.mapNotNull {
            if (it is Pair<*, *>) (it.first as? Int) else null
        }
        else -> emptyList()
    }


    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val m = device.manufacturerData
        if (m != null && extractManufacturerIds(m).any { it == MANUFACTURER_ID }) return deviceSupport
        val un = device.name.uppercase(Locale.US)
        if (un.startsWith("EUFY") && (un.contains("C20") || un.contains("T9130"))) {
            return deviceSupport
        }
        return null
    }


    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val msdRaw = result.scanRecord?.manufacturerSpecificData ?: return BroadcastAction.IGNORED
        val msd = msdRaw as? SparseArray<*> ?: return BroadcastAction.IGNORED
        if (msd.isEmpty()) return BroadcastAction.IGNORED
        val values = (0 until msd.size).mapNotNull { msd.valueAt(it) as? ByteArray }

        val payload = values.firstOrNull { it.size >= 14 } ?: return BroadcastAction.IGNORED

        try {
            val flags = payload[10].toInt() and 0xFF
            val hasWeight = (flags and 0x01) != 0
            val hasImpedance = (flags and 0x40) != 0
            val hasHeartRate = (flags and 0x80) != 0

            // build packet measurement m
            val m = ScaleMeasurement().apply {
                userId = user.id
                dateTime = Date()
            }

            if (hasWeight && payload.size >= 14) {
                val rawWeight = ((payload[13].toInt() and 0xFF) shl 8) or (payload[12].toInt() and 0xFF)
                val weightKg = rawWeight / 100.0f
                if (weightKg != 0.0f) m[MeasurementType.WEIGHT] = Kg(weightKg)
            }
            if (hasHeartRate && payload.size >= 16) {
                val hr = payload[15].toInt() and 0xFF
                if (hr != 0) m[MeasurementType.HEART_RATE] = Bpm(hr)
            }
            if (hasImpedance && payload.size >= 19) {
                val rawImp = ((payload[18].toInt() and 0xFF) shl 8) or (payload[17].toInt() and 0xFF)
                val imp = rawImp / 10.0
                if (imp != 0.0) m[MeasurementType.IMPEDANCE] = Ohm(imp.toFloat())
            }

            if (!m.hasWeight() && (m[MeasurementType.IMPEDANCE]?.value ?: 0f) <= 0.0 && (m[MeasurementType.HEART_RATE]?.value ?: 0) <= 0) {
                return BroadcastAction.IGNORED
            }

            // create stored if missing and get non-null local alias s
            val s = stored ?: ScaleMeasurement().apply {
                userId = user.id
                dateTime = Date()
            }.also { stored = it }

            // merge m into global stored (use local alias s, no !!)
            s.mergeWith(m)
            if (m.hasWeight() && ((s[MeasurementType.WEIGHT]?.value ?: 0f) != (m[MeasurementType.WEIGHT]?.value ?: 0f))) s[MeasurementType.WEIGHT] = Kg((m[MeasurementType.WEIGHT]?.value ?: 0f))
            
            // compute composition if possible
            if (s.hasWeight() && (s[MeasurementType.IMPEDANCE]?.value ?: 0f) > 0.0) {
                val sex = if (user.gender == GenderType.MALE) 1 else 0
                val lib = MiScaleLib(sex, user.age, user.bodyHeight)
                val wf = (s[MeasurementType.WEIGHT]?.value ?: 0f)
                s[MeasurementType.BODY_FAT] = Percent(lib.getBodyFat(wf, (s[MeasurementType.IMPEDANCE]?.value ?: 0f).toFloat()))
                s[MeasurementType.WATER] = Percent(lib.getWater(wf, (s[MeasurementType.IMPEDANCE]?.value ?: 0f).toFloat()))
                s[MeasurementType.MUSCLE] = Percent(lib.getMuscle(wf, (s[MeasurementType.IMPEDANCE]?.value ?: 0f).toFloat()))
                s[MeasurementType.BONE] = Kg(lib.getBoneMass(wf, (s[MeasurementType.IMPEDANCE]?.value ?: 0f).toFloat()))
                s[MeasurementType.LBM] = Kg(lib.getLBM(wf, (s[MeasurementType.IMPEDANCE]?.value ?: 0f).toFloat()))
                s[MeasurementType.VISCERAL_FAT] = lib.getVisceralFat(wf)
            }
            
            // if full measurement -> clear stored and stop
            return if (s.hasWeight() && (s[MeasurementType.IMPEDANCE]?.value ?: 0f) > 0.0 && (s[MeasurementType.HEART_RATE]?.value ?: 0) > 0) {
                s.dateTime = Date()
                publish(s)
                stored = null
                BroadcastAction.CONSUMED_STOP
            } else {
                publish(s)
                BroadcastAction.CONSUMED_KEEP_SCANNING
            }
        } catch (t: Throwable) {
            logE("Eufy C20 advertisement parse error: ${t.message}", t)
            return BroadcastAction.IGNORED
        }
    }

    override fun onConnected(user: ScaleUser) {
        logI("Eufy C20 handler - onConnected (broadcast-only handler)")
    }
}
