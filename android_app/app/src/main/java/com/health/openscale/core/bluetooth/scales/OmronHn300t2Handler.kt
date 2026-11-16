/*
 * openScale
 * Copyright (C) 2026 nadelacruz
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

import androidx.annotation.VisibleForTesting
import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.Hn300t2Session
import com.health.openscale.core.bluetooth.libs.OmronHn300t2Lib
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
import com.welie.blessed.BondState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.UUID

/**
 * Read-only client for the Omron HN-300T2 body-composition scale (independent implementation).
 *
 * The scale is claimed by service 0xFE4A plus its advertised model id. A session handshake is
 * echoed on the unlock characteristic, then the stored records are read over the command channel.
 * Nothing is ever written to the scale beyond the handshake and read commands.
 */
class OmronHn300t2Handler internal constructor(
    bondedOverride: (() -> Boolean)? = null
) : ScaleDeviceHandler() {

    constructor() : this(null)

    /** Whether the link is bonded right now; tests inject this, production asks Blessed. */
    private val isBonded: () -> Boolean =
        bondedOverride ?: { getPeripheral()?.bondState == BondState.BONDED }

    companion object {
        val SVC_OMRON: UUID = uuid16Static(0xFE4A)
        val CHR_UNLOCK: UUID = UUID.fromString("b305b680-aee7-11e1-a730-0002a5d5c51b")
        val CHR_WRITE: UUID = UUID.fromString("db5b55e0-aee7-11e1-965e-0002a5d5c51b")
        val CHR_NOTIFY: UUID = UUID.fromString("49123040-aee8-11e1-a74d-0002a5d5c51b")

        const val ADVERTISED_ID = "00010412"

        private const val SEEN_KEY = "hn300t2_seen"
        private const val SEEN_MAX = 32

        /**
         * Give up if the scale stops answering; below the scale's observed 6.5-10 s idle drop so
         * the user gets a message before the link goes away. Re-armed on every handled notify.
         */
        const val RESPONSE_TIMEOUT_MS = 5_000L

        private const val LOCAL_NAME_PREFIX = "blesmart_"

        private fun uuid16Static(short: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))
    }

    private var session: Hn300t2Session? = null
    private var watchdog: Job? = null

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        if (SVC_OMRON !in device.serviceUuids) return null
        val name = device.name.trim().lowercase()
        if (!name.startsWith(LOCAL_NAME_PREFIX)) return null
        val id = name.removePrefix(LOCAL_NAME_PREFIX).take(ADVERTISED_ID.length)
        if (id != ADVERTISED_ID.lowercase()) return null

        // TODO(unconfirmed): declare BODY_COMPOSITION once a record field is confirmed
        // (docs/wld-protocol.md, "Open questions" item 1).
        val capabilities = setOf(DeviceCapability.HISTORY_READ)
        return DeviceSupport(
            displayName = "Omron HN-300T2",
            capabilities = capabilities,
            implemented = setOf(DeviceCapability.HISTORY_READ),
            // WLD responses are up to 24 bytes, so they need ATT MTU >= 27; Conservative requests
            // no MTU at all. Balanced asks for 185 and the scale grants what it supports (67 seen).
            tuningProfile = TuningProfile.Balanced
        )
    }

    override fun onConnected(user: ScaleUser) {
        if (listOf(CHR_UNLOCK, CHR_WRITE, CHR_NOTIFY).any { !hasCharacteristic(SVC_OMRON, it) }) {
            userError(R.string.bt_error_hn300t2_service_missing)
            requestDisconnect()
            return
        }

        // Subscribing to the unlock characteristic also prompts the scale to bond; an explicit
        // bond request is only added when the link is not bonded yet. createBond() returning true
        // only means it was queued: the bond itself is verified when the handshake echo arrives.
        setNotifyOn(SVC_OMRON, CHR_NOTIFY)
        setNotifyOn(SVC_OMRON, CHR_UNLOCK)
        getPeripheral()?.let {
            if (it.bondState != BondState.BONDED && !it.createBond()) {
                userError(R.string.bt_error_hn300t2_bond_required)
                requestDisconnect()
                return
            }
        }

        val token = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val s = Hn300t2Session(token)
        session = s
        armWatchdog()
        dispatch(s.start())
    }

    override fun onDisconnected() {
        // Callbacks are still attached here (the adapter detaches after this returns), so a
        // transfer that ends with the link gets a message instead of silence.
        val s = session
        if (s != null && !s.isTerminal) {
            logW("link dropped mid-session in phase ${s.phase}")
            userError(R.string.bt_error_hn300t2_transfer)
        }
        cancelWatchdog()
        session = null
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        val s = session ?: return
        if (characteristic != CHR_UNLOCK && characteristic != CHR_NOTIFY) return
        if (s.isTerminal) return
        val handshake = characteristic == CHR_UNLOCK
        if (handshake && !isBonded()) {
            // An unbonded link is unsupported: never send the session opener over it.
            abort(R.string.bt_error_hn300t2_bond_required, "link not bonded at handshake echo")
            return
        }
        val before = s.phase
        val actions = if (handshake) s.onHandshakeNotify(data) else s.onNotify(data)
        if (actions.isEmpty() && s.phase == before) {
            // Not a handled notify: the watchdog keeps running from the last good frame.
            logW("dropped frame (bad length/BCC): ${data.toHexPreview(8)}")
            return
        }
        if (!s.isTerminal) armWatchdog()
        dispatch(actions, handshake)
    }

    /** Ends the session on a handler-side failure: one message, then disconnect. */
    private fun abort(resId: Int, reason: String) {
        logW("session aborted: $reason")
        cancelWatchdog()
        session = null
        userError(resId)
        requestDisconnect()
    }

    private fun dispatch(actions: List<Hn300t2Session.Action>, fromHandshake: Boolean = false) {
        for (action in actions) {
            when (action) {
                is Hn300t2Session.Action.WriteHandshake ->
                    writeTo(SVC_OMRON, CHR_UNLOCK, action.bytes, withResponse = false)
                is Hn300t2Session.Action.WriteCommand ->
                    writeTo(SVC_OMRON, CHR_WRITE, action.bytes, withResponse = false)
                is Hn300t2Session.Action.Publish -> handleRecord(action.record, action.slot)
                is Hn300t2Session.Action.Warn -> logW(action.message)
                Hn300t2Session.Action.NoNewRecord ->
                    userInfo(R.string.bt_info_hn300t2_no_new_measurements)
                is Hn300t2Session.Action.Fail -> {
                    logW("session failed: ${action.reason}")
                    cancelWatchdog()
                    userError(
                        if (fromHandshake) R.string.bt_error_hn300t2_handshake
                        else R.string.bt_error_hn300t2_transfer
                    )
                    requestDisconnect()
                }
                Hn300t2Session.Action.Done -> {
                    cancelWatchdog()
                    requestDisconnect()
                }
            }
        }
    }

    @VisibleForTesting
    internal fun handleRecord(record: OmronHn300t2Lib.MeasurementRecord, slot: Int) {
        val epoch = record.timestamp.time / 1000
        val users = usersForDevice()
        val lasts = users.map { lastMeasurementFor(it.id)?.dateTime }
        val alreadyStored = users.isNotEmpty() && lasts.all { it != null && !record.timestamp.after(it) }
        when {
            epoch in seen() || alreadyStored -> {
                logI("record ts=$epoch already seen, dropped")
                userInfo(R.string.bt_info_hn300t2_no_new_measurements)
            }
            users.isEmpty() -> {
                logI("no openScale user for this device, record dropped")
                userInfo(R.string.bt_info_hn300t2_no_user)
            }
            // TODO: once the slot/user-index byte is confirmed, route by slot via a one-time slot -> openScale user mapping in DeviceConfigurationUi()
            else -> publishFor(currentAppUser().id, record)
        }
        finishSession()
    }

    private fun finishSession() {
        val actions = session?.finish().orEmpty()
        if (actions.isEmpty()) return
        armWatchdog()
        dispatch(actions)
    }

    private fun publishFor(userId: Int, record: OmronHn300t2Lib.MeasurementRecord) {
        publish(
            ScaleMeasurement(userId = userId, dateTime = record.timestamp).also {
                it[MeasurementType.WEIGHT] = Kg(record.weightKg)
            }
        )
        // Only a published record is remembered, so a record dropped earlier can still publish.
        remember(record.timestamp.time / 1000)
    }

    private fun seen(): List<Long> =
        settingsGetString(SEEN_KEY)?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    private fun remember(epoch: Long) {
        settingsPutString(SEEN_KEY, (seen() + epoch).takeLast(SEEN_MAX).joinToString(","))
    }

    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(RESPONSE_TIMEOUT_MS)
            watchdog = null
            abort(R.string.bt_error_hn300t2_timeout, "no notify for ${RESPONSE_TIMEOUT_MS}ms")
        }
    }

    private fun cancelWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }
}
