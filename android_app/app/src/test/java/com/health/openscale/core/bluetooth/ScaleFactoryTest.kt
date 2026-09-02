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

import android.util.SparseArray
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.health.openscale.core.bluetooth.scales.DrTrustSSW532Handler
import com.health.openscale.core.bluetooth.scales.EtekcityESF551Handler
import com.health.openscale.core.bluetooth.scales.EtekcityFit8SHandler
import com.health.openscale.core.bluetooth.scales.EufyC20Handler
import com.health.openscale.core.bluetooth.scales.ExcelvanCF36xHandler
import com.health.openscale.core.bluetooth.scales.FitTrackDaraHandler
import com.health.openscale.core.bluetooth.scales.HumeDara2Handler
import com.health.openscale.core.bluetooth.scales.MGBHandler
import com.health.openscale.core.bluetooth.scales.OkOkHandler
import com.health.openscale.core.bluetooth.scales.QNHandlerBroadcast
import com.health.openscale.core.bluetooth.scales.ScaleupHandler
import com.health.openscale.core.bluetooth.scales.SinocareHandler
import com.health.openscale.core.bluetooth.scales.YunmaiXHandler
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
 * The devices themselves live in [ScaleCatalog.fixtures], shared with `ScaleCatalogTest`, which
 * renders the published scale table from the same list — a supported scale is written down once.
 * Advertisements are synthetic, built from the names/services documented in the handlers
 * themselves, not from personal captures.
 *
 * Robolectric is required because some handlers touch the Android framework while being
 * constructed (e.g. QNHandler creates a main-looper Handler).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScaleFactoryTest {

    /** Service 0xFFB0 — the LeFu-style service shared by a whole family of unrelated scales. */
    private val SERVICE_FFB0 = uuid16(0xFFB0)

    // Device construction and registry queries are shared with the catalog; see [ScaleCatalog].
    private fun claimants(device: ScannedDeviceInfo) = ScaleCatalog.claimants(device)

    private fun winner(device: ScannedDeviceInfo) = ScaleCatalog.winner(device)

    private fun device(name: String, vararg services: UUID) = ScaleCatalog.device(name, *services)

    private fun advertisement(
        name: String = "",
        services: List<UUID> = emptyList(),
        manufacturerData: List<Pair<Int, ByteArray>> = emptyList(),
    ) = ScaleCatalog.advertisement(name, services, manufacturerData)

    private fun uuid16(short: Int) = ScaleCatalog.uuid16(short)

    private fun assertClaimedBy(device: ScannedDeviceInfo, expected: Class<out ScaleDeviceHandler>) {
        val winner = winner(device)
        assertThat(winner).isNotNull()
        assertThat(winner!!.javaClass.simpleName).isEqualTo(expected.simpleName)
    }

    // --- Device claims ----------------------------------------------------------------------

    /**
     * The regression guard that matters: every device in [ScaleCatalog.fixtures] must still resolve
     * to the handler that owns it. A new (or reordered) handler that starts matching one of these
     * advertisements fails here.
     *
     * The fixtures are shared with `ScaleCatalogTest`, which renders the published scale table from
     * the very same list — so a scale is described in exactly one place.
     */
    @Test
    fun `catalog fixtures resolve to their own handler`() {
        for (fixture in ScaleCatalog.fixtures) {
            val winner = winner(fixture.device)

            assertWithMessage("device '${fixture.device.name.ifEmpty { "<nameless>" }}'")
                .that(winner?.javaClass?.simpleName)
                .isEqualTo(fixture.handler.simpleName)
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
            "HUAWEI Scale 3",
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
     * "Dara 2.0" (Hume Health, OEM "LeFu Scale", 0xFFF0/0xCF framing) and "FitTrack Dara" (an
     * unrelated FitTrack product, 0xFFB0/0xAC02 framing) are two different scales that happen
     * to share a model name. FitTrackDaraHandler only matches names starting with "FITTRACK",
     * so it never takes this device — but pin that explicitly since it's exactly the confusion
     * https://github.com/oliexdev/openScale/issues/1448 reported ("Not Supported" despite named
     * FitTrack Dara 2.0 support already existing). Also pin the sibling boundary against
     * ExcelvanCF36xHandler, the other 0xFFF0 handler: neither name collides with the other, but
     * both are on the same chip family in the same part of the registry.
     */
    @Test
    fun `Hume Dara 2_0 is not swallowed by the unrelated FitTrack Dara handler`() {
        assertClaimedBy(device("Dara 2.0"), HumeDara2Handler::class.java)
        assertClaimedBy(device("FITTRACK Dara", SERVICE_FFB0), FitTrackDaraHandler::class.java)
        assertClaimedBy(device("Electronic Scale"), ExcelvanCF36xHandler::class.java)

        assertThat(FitTrackDaraHandler().supportFor(device("Dara 2.0"))).isNull()
        assertThat(ExcelvanCF36xHandler().supportFor(device("Dara 2.0"))).isNull()
        assertThat(HumeDara2Handler().supportFor(device("Electronic Scale"))).isNull()
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

    // --- Broadcast fingerprints -------------------------------------------------------------

    /** Etekcity's company id — together with service 0xFFD0 the only fingerprint of the Fit 8S. */
    private val ETEKCITY_COMPANY_ID = 0x06D0

    /** Service 0xFFD0 — advertised by the Fit 8S alongside its manufacturer record. */
    private val SERVICE_FFD0 = uuid16(0xFFD0)

    /** A stable 75.500 kg / 500 Ω reading in the Fit 8S advertisement layout. */
    private fun fit8sPayload(): ByteArray = ByteArray(20).apply {
        this[0] = 0x01                              // header
        this[10] = 0xEC.toByte()                    // weight 75500 g, 3-byte little-endian
        this[11] = 0x26
        this[12] = 0x01
        this[13] = 0xF4.toByte()                    // impedance 500 Ω, 2-byte little-endian
        this[14] = 0x01
        this[15] = 0x01                             // stable
    }

    private fun fit8sAdvertisement() = advertisement(
        name = "",                                  // the scale advertises no name at all
        services = listOf(SERVICE_FFD0),
        manufacturerData = listOf(ETEKCITY_COMPANY_ID to fit8sPayload()),
    )

    /**
     * The Fit 8S is nameless, so it can only be recognised by company id plus service UUID. Both
     * halves must be required: matching on either one alone would make the handler claim
     * advertisements it cannot parse.
     */
    @Test
    fun `the nameless Etekcity Fit 8S is claimed by its own handler`() {
        assertClaimedBy(fit8sAdvertisement(), EtekcityFit8SHandler::class.java)

        val fit8s = EtekcityFit8SHandler()
        // Company id without the service…
        assertThat(
            fit8s.supportFor(
                advertisement(manufacturerData = listOf(ETEKCITY_COMPANY_ID to fit8sPayload()))
            )
        ).isNull()
        // …and the service without the company id.
        assertThat(
            fit8s.supportFor(
                advertisement(
                    services = listOf(SERVICE_FFD0),
                    manufacturerData = listOf(0xFF64 to fit8sPayload()),
                )
            )
        ).isNull()
        // A scan result that carried no manufacturer data at all.
        assertThat(fit8s.supportFor(device("", SERVICE_FFD0))).isNull()
    }

    /**
     * ScaleupHandler claims *any* manufacturer record whose company-id low byte is 0xD0 or 0xE0 —
     * and Etekcity's company id 0x06D0 ends in 0xD0, so both handlers answer for a Fit 8S. Only the
     * list position keeps the device on the driver that can decode it.
     */
    @Test
    fun `Etekcity Fit 8S is matched ahead of the low-byte Scaleup match`() {
        val claimants = claimants(fit8sAdvertisement()).map { it.javaClass.simpleName }
        assertThat(claimants).containsExactly("EtekcityFit8SHandler", "ScaleupHandler").inOrder()

        val order = ScaleFactory.createHandlers().map { it.javaClass.simpleName }
        assertThat(order.indexOf("EtekcityFit8SHandler")).isLessThan(order.indexOf("ScaleupHandler"))
    }

    /**
     * The other direction of the same overlap: a Scaleup advertisement carries no 0xFFD0 service
     * and a different company id, so the Fit 8S handler must keep its hands off it.
     */
    @Test
    fun `Scaleup broadcasts are not swallowed by the Etekcity Fit 8S handler`() {
        // key = (weight MSB shl 8) or flag → 75.50 kg (0x1D7E) while measuring (0xD0)
        val scaleup = advertisement(manufacturerData = listOf(0x1DD0 to ByteArray(9)))

        assertClaimedBy(scaleup, ScaleupHandler::class.java)
        assertThat(EtekcityFit8SHandler().supportFor(scaleup)).isNull()
    }

    /**
     * The connectable Etekcity ESF551 is the Fit 8S's closest neighbour — same vendor, so possibly
     * the same company id. It identifies itself by name and must keep winning: the Fit 8S handler
     * would otherwise downgrade it to broadcast-only.
     */
    @Test
    fun `the named Etekcity ESF551 wins over the broadcast Fit 8S handler`() {
        val esf551 = advertisement(
            name = "Etekcity Smart Fitness Scale",
            services = listOf(SERVICE_FFD0),
            manufacturerData = listOf(ETEKCITY_COMPANY_ID to fit8sPayload()),
        )

        assertClaimedBy(esf551, EtekcityESF551Handler::class.java)

        val order = ScaleFactory.createHandlers().map { it.javaClass.simpleName }
        assertThat(order.indexOf("EtekcityESF551Handler"))
            .isLessThan(order.indexOf("EtekcityFit8SHandler"))
    }

    /**
     * The registry holds several handlers that identify a scale from its manufacturer record alone.
     * Each must keep its own advertisement, and none of them may be answered by the Fit 8S handler.
     */
    @Test
    fun `manufacturer-data broadcasts stay with their own handler`() {
        val expectations: List<Pair<ScannedDeviceInfo, Class<out ScaleDeviceHandler>>> = listOf(
            // Sinocare — company id 0xFF64
            advertisement(manufacturerData = listOf(0xFF64 to ByteArray(12)))
                    to SinocareHandler::class.java,
            // QN broadcast variant — company id 0xFFFF with the AABB magic header
            advertisement(
                manufacturerData = listOf(
                    0xFFFF to byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x00, 0x00, 0x00, 0x00)
                )
            ) to QNHandlerBroadcast::class.java,
            // Eufy C20 — company id 0xBC64
            advertisement(manufacturerData = listOf(48228 to ByteArray(14)))
                    to EufyC20Handler::class.java,
            // OKOK V20 — company id 0x20CA behind one of the known names
            advertisement(name = "ADV", manufacturerData = listOf(0x20CA to ByteArray(16)))
                    to OkOkHandler::class.java,
            // Yunmai X — recognised by its advertised 16-bit service 0x1320
            advertisement(services = listOf(uuid16(0x1320))) to YunmaiXHandler::class.java,
        )

        for ((device, expected) in expectations) {
            assertClaimedBy(device, expected)
            assertThat(EtekcityFit8SHandler().supportFor(device)).isNull()
        }
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
