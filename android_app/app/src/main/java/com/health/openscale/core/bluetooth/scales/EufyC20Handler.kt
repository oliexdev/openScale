package com.health.openscale.core.bluetooth.scales

import android.bluetooth.le.ScanResult
import android.util.SparseArray
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.MiScaleLib
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.Locale

class EufyC20Handler : ScaleDeviceHandler() {

    private val MANUFACTURER_ID = 48228

    private var publishedOnce = false

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

    // Single-slot: current device address and its partial measurement.
    private var currentAddr: String? = null
    private var currentMeasurement: ScaleMeasurement? = null

    // Timeout to keep partials alive for a short while so multi-packet transmissions aren't lost
    private var currentLastSeenMs: Long = 0L
    private val PARTIAL_TIMEOUT_MS = 5_000L

    private fun extractManufacturerIds(m: Any?): Iterable<Int> = when (m) {
        is Map<*, *> -> m.keys.filterIsInstance<Int>()
        is SparseArray<*> -> (0 until m.size()).map { m.keyAt(it) }
        is List<*> -> (m as? List<Pair<Int, *>>)?.map { it.first } ?: emptyList()
        else -> emptyList()
    }

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val m = device.manufacturerData
        if (m != null && extractManufacturerIds(m).any { it == MANUFACTURER_ID }) return deviceSupport

        val name = device.name ?: return null
        val un = name.uppercase(Locale.US)
        if (un.startsWith("EUFY") && (un.contains("C20") || un.contains("T9130"))) {
            return deviceSupport
        }

        return null
    }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {

        if (publishedOnce) {
            publishedOnce = false
            return BroadcastAction.CONSUMED_STOP
        }

        val addr = result.device.address ?: return BroadcastAction.IGNORED

        val msdRaw = result.scanRecord?.manufacturerSpecificData ?: return BroadcastAction.IGNORED
        val msd = msdRaw as? SparseArray<ByteArray> ?: return BroadcastAction.IGNORED
        if (msd.size() == 0) return BroadcastAction.IGNORED
        val values = (0 until msd.size()).map { msd.valueAt(it) }

        // Expire stale partials
        if (currentAddr != null && System.currentTimeMillis() - currentLastSeenMs > PARTIAL_TIMEOUT_MS) {
            currentAddr = null
            currentMeasurement = null
        }

        val valid = values.firstOrNull {
            val flags = it.getOrNull(10)?.toInt() ?: return@firstOrNull false
            val hasWeight = (flags and 0x01) != 0
            val hasImp = (flags and 0x40) != 0
            val hasHr = (flags and 0x80) != 0
            val lenOkForImp = !hasImp || it.size >= 19
            val lenOkForHr = !hasHr || it.size >= 16
            val lenOkForWeight = !hasWeight || it.size >= 14
            lenOkForImp && lenOkForHr && lenOkForWeight
        }
        val payload = valid ?: values.lastOrNull() ?: return BroadcastAction.IGNORED
        if (payload.size < 14) return BroadcastAction.IGNORED

        try {
            val flags = payload[10].toInt() and 0xFF
            val hasWeight = (flags and 0x01) != 0
            val hasImpedance = (flags and 0x40) != 0
            val hasHeartRate = (flags and 0x80) != 0

            // Start a new partial for a new device address
            val isRelevant = hasWeight || hasImpedance || hasHeartRate || valid != null
            if (isRelevant) {
                if (currentAddr == null || currentAddr != addr) {
                    currentAddr = addr
                    currentMeasurement = ScaleMeasurement().apply {
                        dateTime = Date()
                        userId = user.id
                    }
                }
                // update last seen when we start/continue a relevant partial
                currentLastSeenMs = System.currentTimeMillis()
            }

            val stored = currentMeasurement ?: return BroadcastAction.IGNORED
            var updatedAny = false

            if (hasWeight && payload.size >= 14) {
                val rawWeight = ((payload[13].toInt() and 0xFF) shl 8) or (payload[12].toInt() and 0xFF)
                val weightKg = rawWeight / 100.0f
                if (weightKg != 0.0f) {
                    stored.weight = weightKg
                    stored.dateTime = Date()
                    updatedAny = true
                }
            }

            if (hasHeartRate && payload.size >= 16) {
                val hr = payload[15].toInt() and 0xFF
                if (hr != 0) {
                    stored.heartRate = hr
                    updatedAny = true
                }
            }

            if (hasImpedance && payload.size >= 19) {
                val rawImp = ((payload[18].toInt() and 0xFF) shl 8) or (payload[17].toInt() and 0xFF)
                val imp = rawImp / 10.0
                if (imp != 0.0) {
                    stored.impedance = imp
                    updatedAny = true
                }
            }

            // extend timeout when we actually updated something
            if (updatedAny) currentLastSeenMs = System.currentTimeMillis()

            fun computeBodyCompositionIfPossible(m: ScaleMeasurement) {
                val impDouble = m.impedance
                val w = m.weight
                if (impDouble > 0.0 && w > 0.0f) {
                    val imp = impDouble.toFloat()
                    val sex = if (user.gender == GenderType.MALE) 1 else 0
                    val lib = MiScaleLib(sex, user.age, user.bodyHeight)
                    val weightFloat = w
                    m.fat = lib.getBodyFat(weightFloat, imp)
                    m.water = lib.getWater(weightFloat, imp)
                    m.muscle = lib.getMuscle(weightFloat, imp)
                    m.bone = lib.getBoneMass(weightFloat, imp)
                    m.lbm = lib.getLBM(weightFloat, imp)
                    m.visceralFat = lib.getVisceralFat(weightFloat)
                }
            }

            if (!updatedAny) return BroadcastAction.CONSUMED_KEEP_SCANNING

            if (stored.hasWeight() && stored.impedance > 0.0 && stored.heartRate > 0) {
                computeBodyCompositionIfPossible(stored)
                logI("Eufy C20 final (combined): weight=${stored.weight} kg hr=${stored.heartRate} imp=${stored.impedance} fat=${stored.fat}")
                publish(stored)
                // clear for next measurement
                currentAddr = null
                currentMeasurement = null
                publishedOnce = true
                return BroadcastAction.CONSUMED_STOP
            }

            if (stored.hasWeight()) {
                logD("Eufy C20 live (partial): weight=${stored.weight} kg hr=${stored.heartRate} imp=${stored.impedance}")
                return BroadcastAction.CONSUMED_KEEP_SCANNING
            }
        } catch (t: Throwable) {
            logE("Parsing advertisement failed for Eufy C20", t)
            return BroadcastAction.IGNORED
        }

        return BroadcastAction.IGNORED
    }

    override fun onConnected(user: ScaleUser) {
        logI("Eufy C20 handler - onConnected (broadcast-only handler)")
    }
}
