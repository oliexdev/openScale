package com.health.openscale.core.bluetooth.scales

import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date
import java.util.UUID

class EbelterBodyFatB2Handler : ScaleDeviceHandler() {

    // 16-bit UUIDs -> base UUID BLE
    private val SERVICE_FAA0 = uuid16(0xFAA0)
    private val CHAR_FAA1_WRITE = uuid16(0xFAA1)
    private val CHAR_FAA2_NOTIFY = uuid16(0xFAA2)

    // Handshake (comandos que ya probaste en nRF y dieron grasa)
    private val CMD1 = hex("ab0998fc121711180a2714")
    private val CMD2 = hex("ab0e9906151442148bbc0aa611bd12e9ea")

    // Evitar spamear handshake en cada frame
    private var lastHandshakeMs: Long = 0L

    // Anti-duplicados: muchas básculas mandan 2-3 tramas finales por una sola medición
    private var lastPublishMs: Long = 0L
    private var lastPublishedWeightRaw: Int = -1
    private var lastPublishedFatRaw: Int = -1

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = (device.name ?: "").uppercase()

        // En tus capturas sale como "Body Fat-B2"
        if (name.startsWith("BODY FAT-B2")) {
            return DeviceSupport(
                displayName = "Belter / Ebelter Body Fat-B2 (EF-916B4)",
                capabilities = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION
                ),
                implemented = setOf(
                    DeviceCapability.LIVE_WEIGHT_STREAM,
                    DeviceCapability.BODY_COMPOSITION
                ),
                linkMode = LinkMode.CONNECT_GATT
            )
        }
        return null
    }

    override fun onConnected(user: ScaleUser) {
        // 1) Activar notificaciones (FAA2)
        setNotifyOn(SERVICE_FAA0, CHAR_FAA2_NOTIFY)

        // 2) Handshake inicial (a veces basta)
        sendHandshakeIfNeeded(force = true)
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != CHAR_FAA2_NOTIFY) return
        if (data.size < 2) return

        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF

        // Mensajes cortos de estado (8D 01 ..) suelen ocurrir cuando te subes / parpadea
        // Aprovechamos para re-lanzar handshake cerca de la medición (mejora consistencia de grasa)
        if (b0 == 0x8D && b1 == 0x01) {
            sendHandshakeIfNeeded(force = false)
            return
        }

        // Mensaje de medición (8D 11 ..)
        if (b0 != 0x8D || b1 != 0x11) return
        if (data.size < 8) return

        val weightRaw = u16le(data, 4)   // bytes 4-5
        val fatRaw = u16le(data, 6)      // bytes 6-7

        val weightKg = weightRaw / 10.0f
        if (weightKg < 10.0f) return // descartar ruido

        val m = ScaleMeasurement()
        m.dateTime = Date()
        m.weight = weightKg

        // grasa solo si viene distinta de 0 (cuando no está activado, suele venir 0)
        if (fatRaw != 0) {
            m.fat = fatRaw / 10.0f
        }

        // -----------------------------
        // Anti-duplicados (clave)
        // -----------------------------
        val now = System.currentTimeMillis()

        // Si llega exactamente lo mismo, no publicar
        if (weightRaw == lastPublishedWeightRaw && fatRaw == lastPublishedFatRaw) return

        // Si llega otra trama muy seguida (<6s), casi siempre es duplicado del mismo pesaje
        if ((now - lastPublishMs) < 6000L) return

        lastPublishMs = now
        lastPublishedWeightRaw = weightRaw
        lastPublishedFatRaw = fatRaw

        publish(m)
    }

    private fun sendHandshakeIfNeeded(force: Boolean) {
        val now = System.currentTimeMillis()
        // No más de 1 handshake cada 2.5s (evita spam)
        if (!force && (now - lastHandshakeMs) < 2500L) return
        lastHandshakeMs = now

        writeTo(SERVICE_FAA0, CHAR_FAA1_WRITE, CMD1)
        writeTo(SERVICE_FAA0, CHAR_FAA1_WRITE, CMD2)
    }

    private fun u16le(b: ByteArray, offset: Int): Int {
        val lo = b[offset].toInt() and 0xFF
        val hi = b[offset + 1].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("-", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = clean.substring(idx, idx + 2).toInt(16).toByte()
        }
        return out
    }
}