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
package com.health.openscale.core.bluetooth

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.scales.DrTrustSSW532Handler
import com.health.openscale.core.bluetooth.scales.FitTrackDaraHandler
import com.health.openscale.core.bluetooth.scales.MGBHandler
import com.health.openscale.core.bluetooth.scales.RelaxmedicHandler
import com.health.openscale.core.bluetooth.scales.RobiS9Handler
import com.health.openscale.core.bluetooth.scales.SanitasSbf72Handler
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.bluetooth.scales.TaylorBIAHandler
import com.health.openscale.core.bluetooth.scales.YunmaiHandler
import com.health.openscale.core.service.ScannedDeviceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Registry-level tests for [ScaleFactory]'s handler list.
 *
 * The per-handler tests in `bluetooth/scales/` each check one `supportFor` in isolation and
 * therefore cannot see *across* handlers: a new driver whose matcher is a little too greedy
 * silently steals a device from an existing one while every single handler test stays green.
 * These tests close that gap by probing the whole ordered registry — the same list and the
 * same first-match-wins rule [ScaleFactory.createCommunicator] uses.
 *
 * Fixtures are synthetic advertisements built from the advertised names/services documented in
 * the handlers themselves, not from personal captures.
 *
 * Robolectric is required because some handlers touch the Android framework while being
 * constructed (e.g. QNHandler creates a main-looper Handler).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScaleFactoryTest {

    /** Service 0xFFB0 — the LeFu-style service shared by a whole family of unrelated scales. */
    private val SERVICE_FFB0 = uuid16(0xFFB0)

    /**
     * A fresh registry per probe: several handlers stash state in `supportFor` (e.g.
     * StandardBeurerSanitasHandler remembers the matched model), so a shared instance would let
     * one fixture bleed into the next.
     */
    private fun claimants(device: ScannedDeviceInfo): List<ScaleDeviceHandler> =
        ScaleFactory.createHandlers().filter { it.supportFor(device) != null }

    /** The handler [ScaleFactory.createCommunicator] would pick for [device], or null. */
    private fun winner(device: ScannedDeviceInfo): ScaleDeviceHandler? =
        ScaleFactory.createHandlers().firstOrNull { it.supportFor(device) != null }

    private fun device(name: String, vararg services: UUID) = ScannedDeviceInfo(
        name = name,
        address = "C0:FF:EE:12:34:56",
        rssi = -50,
        serviceUuids = services.toList(),
        manufacturerData = null,
    )

    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    private fun assertClaimedBy(device: ScannedDeviceInfo, expected: Class<out ScaleDeviceHandler>) {
        val winner = winner(device)
        assertThat(winner).isNotNull()
        assertThat(winner!!.javaClass.simpleName).isEqualTo(expected.simpleName)
    }

    // --- Device claims ----------------------------------------------------------------------

    /**
     * The regression guard that matters: for a spread of known advertised names, the registry must
     * still resolve to the handler that owns that device. A new (or reordered) handler that starts
     * matching one of these names fails here.
     */
    @Test
    fun `known advertised names resolve to their own handler`() {
        val expectations: List<Pair<String, String>> = listOf(
            // name (as advertised)               expected handler class
            "openScale" to "CustomOpenScaleHandler",
            "MIBFS" to "MiScaleHandler",
            "MI_SCALE" to "MiScaleHandler",
            "XMTZC14HM" to "MiScaleS400Handler",
            "MIJIA SCALE S800" to "XiaomiS800Handler",
            "BLEsmart_0001000C0080E1A2B3C4" to "OmronWlcHandler",
            "Keep_S3" to "KeepS3Handler",
            "AE BS-06" to "ActiveEraBF06Handler",
            "Shape200" to "SoehnleHandler",
            "YUNMAI-ISSE-1234" to "YunmaiHandler",
            "YUNMAI-SIGNAL-1234" to "YunmaiHandler",
            "01257B1234" to "TrisaBodyAnalyzeHandler",
            "Etekcity Smart Fitness Scale" to "EtekcityESF551Handler",
            "Vitafit VT701" to "VitafitVT701Handler",
            "SENSSUN FAT" to "SenssunHandler",
            "Weight Scale" to "SinocareHandler",
            "Electronic Scale" to "ExcelvanCF36xHandler",
            "ES-26BB-B" to "RenphoES26BBHandler",
            "Health Scale" to "OneByoneHandler",
            "1byone scale" to "OneByoneNewHandler",
            "CH100S" to "HuaweiCH100SHandler",
            "Hoffen BS-8107" to "HoffenBbs8107Handler",
            "RUNSTAR-R5" to "RunstarR5Handler",
            "runstar-r6" to "RunstarR6Handler",
            "Beurer BF450" to "BeurerBF450Handler",
            "Beurer BF105" to "StandardBeurerSanitasHandler",
            "BEURER BF700" to "BeurerSanitasHandler",
            "000fatscale01" to "InlifeHandler",
            "10376BAA" to "WeightGurusA3Handler",
            "Mengii" to "DigooDGSO38HHandler",
            "yunchen" to "HesleyHandler",
            "vscale" to "ExingtechY1Handler",
            "IHEALTH HS3" to "IHealthHS3Handler",
            "CULT Smart Scale Pro" to "CultSmartScaleProHandler",
            "AAA002" to "AAAxHandler",
            "eufy T9148" to "EufyP2Handler",
            "EUFY C20" to "EufyC20Handler",
            "debug" to "DebugGattHandler",
        )

        for ((name, expected) in expectations) {
            val winner = winner(device(name))
            assertThat(winner?.javaClass?.simpleName).isEqualTo(expected)
        }
    }

    /**
     * The Yunmai driver is registered twice with different constructor arguments, so the class
     * name alone does not prove the right variant answered — check the reported product instead.
     */
    @Test
    fun `the two Yunmai registry entries stay distinguishable`() {
        val classic = winner(device("YUNMAI-ISSE-1234"))
        val mini = winner(device("YUNMAI-SIGNAL-1234"))

        assertThat(classic).isInstanceOf(YunmaiHandler::class.java)
        assertThat(mini).isInstanceOf(YunmaiHandler::class.java)
        assertThat(classic!!.supportFor(device("YUNMAI-ISSE-1234"))!!.displayName)
            .isNotEqualTo(mini!!.supportFor(device("YUNMAI-SIGNAL-1234"))!!.displayName)
    }

    /**
     * Devices identified by an exact, vendor-specific name must be claimed by exactly one handler.
     * A second claimant means two drivers overlap and the winner is decided by list position —
     * which is exactly the kind of accident that only shows up on a user's device.
     */
    @Test
    fun `exact-name devices are claimed by a single handler`() {
        val unambiguous = listOf(
            "openScale",
            "Keep_S3",
            "AE BS-06",
            "BLEsmart_0001000C0080E1A2B3C4",
            "Etekcity Smart Fitness Scale",
            "SENSSUN FAT",
            "ES-26BB-B",
            "CH100S",
            "Hoffen BS-8107",
            "RUNSTAR-R5",
            "000fatscale01",
            "Mengii",
            "yunchen",
            "vscale",
            "10376BAA",
        )

        for (name in unambiguous) {
            val claimants = claimants(device(name)).map { it.javaClass.simpleName }
            assertThat(claimants).hasSize(1)
        }
    }

    // --- Ordering ---------------------------------------------------------------------------

    /**
     * MGBHandler claims *any* device advertising service 0xFFB0, so every sibling on that service
     * only wins by standing earlier in the list. This pins the documented order; moving MGBHandler
     * up (or a sibling down) breaks these devices silently at runtime.
     */
    @Test
    fun `0xFFB0 siblings are matched ahead of the generic MGB handler`() {
        assertClaimedBy(device("FITTRACK Dara", SERVICE_FFB0), FitTrackDaraHandler::class.java)
        assertClaimedBy(device("BIA SCALE", SERVICE_FFB0), TaylorBIAHandler::class.java)
        assertClaimedBy(device("relaxmedic", SERVICE_FFB0), RelaxmedicHandler::class.java)
        assertClaimedBy(device("robi", SERVICE_FFB0), RobiS9Handler::class.java)
        assertClaimedBy(device("SSW532", SERVICE_FFB0), DrTrustSSW532Handler::class.java)

        // The generic member of the family keeps the devices nobody else claims.
        assertClaimedBy(device("swan", SERVICE_FFB0), MGBHandler::class.java)

        val order = ScaleFactory.createHandlers().map { it.javaClass.simpleName }
        val mgb = order.indexOf("MGBHandler")
        val siblings = listOf(
            "TaylorBIAHandler",
            "FitTrackDaraHandler",
            "RelaxmedicHandler",
            "RobiS9Handler",
            "DrTrustSSW532Handler",
        )
        for (sibling in siblings) {
            assertThat(order.indexOf(sibling)).isLessThan(mgb)
        }
    }

    /**
     * The Dr. Trust driver only claims a device when the name *and* the 0xFFB0 service match, so it
     * can never take a device away from MGBHandler — but it sat behind MGB in the registry, which
     * meant MGB (service-only match) swallowed every SSW-532. Pins both halves of that fix.
     */
    @Test
    fun `Dr Trust SSW532 is not swallowed by the service-only MGB match`() {
        assertClaimedBy(device("SSW532", SERVICE_FFB0), DrTrustSSW532Handler::class.java)
        assertClaimedBy(device("SSW-532 FG2211", SERVICE_FFB0), DrTrustSSW532Handler::class.java)

        // Without the service the Dr. Trust handler must stay out of the way…
        assertThat(claimants(device("SSW532")).map { it.javaClass.simpleName }).isEmpty()
        // …and without the name it must not touch MGB's own devices.
        assertClaimedBy(device("icomon", SERVICE_FFB0), MGBHandler::class.java)
        assertClaimedBy(device("yg", SERVICE_FFB0), MGBHandler::class.java)
    }

    /**
     * SanitasSbf72Handler and BeurerSanitasHandler both answer to `sbf7x` names; only the list
     * position keeps the SBF72/73 devices on the driver written for them.
     */
    @Test
    fun `Sanitas SBF72 is matched ahead of the legacy Beurer-Sanitas handler`() {
        assertClaimedBy(device("SANITAS SBF72"), SanitasSbf72Handler::class.java)

        val order = ScaleFactory.createHandlers().map { it.javaClass.simpleName }
        assertThat(order.indexOf("SanitasSbf72Handler")).isLessThan(order.indexOf("BeurerSanitasHandler"))
    }

    // --- Non-scales -------------------------------------------------------------------------

    /**
     * Nothing in the registry may claim an unrelated BLE device: a false positive shows the device
     * as a supported scale in the scan list and then fails on connect.
     */
    @Test
    fun `no handler claims unrelated bluetooth devices`() {
        val foreign = listOf(
            "",
            "MI Band 5",
            "Galaxy Watch4",
            "AirPods Pro",
            "Polar H10",
            "TV Remote",
            "Tile",
        )

        for (name in foreign) {
            val claimants = claimants(device(name)).map { it.javaClass.simpleName }
            assertThat(claimants).isEmpty()
        }
    }

    // --- Registry hygiene -------------------------------------------------------------------

    /**
     * Guards against a handler being pasted into the list twice — harmless at runtime, but it
     * hides the real winner and makes the order impossible to reason about. YunmaiHandler is the
     * one intentional double entry (classic + mini).
     */
    @Test
    fun `registry contains no accidental duplicate handlers`() {
        val duplicates = ScaleFactory.createHandlers()
            .groupingBy { it.javaClass.simpleName }
            .eachCount()
            .filterValues { it > 1 }

        assertThat(duplicates).containsExactly("YunmaiHandler", 2)
    }
}
