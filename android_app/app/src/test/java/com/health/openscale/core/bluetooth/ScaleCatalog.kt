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
import com.health.openscale.core.bluetooth.scales.AAAxHandler
import com.health.openscale.core.bluetooth.scales.ActiveEraBF06Handler
import com.health.openscale.core.bluetooth.scales.AfuB1Handler
import com.health.openscale.core.bluetooth.scales.BeurerBF450Handler
import com.health.openscale.core.bluetooth.scales.BeurerSanitasHandler
import com.health.openscale.core.bluetooth.scales.BodyConnectHandler
import com.health.openscale.core.bluetooth.scales.CultSmartScaleProHandler
import com.health.openscale.core.bluetooth.scales.CustomOpenScaleHandler
import com.health.openscale.core.bluetooth.scales.DeviceCapability
import com.health.openscale.core.bluetooth.scales.DigooDGSO38HHandler
import com.health.openscale.core.bluetooth.scales.DrTrustSSW532Handler
import com.health.openscale.core.bluetooth.scales.EEBBLHandler
import com.health.openscale.core.bluetooth.scales.ESCS20MHandler
import com.health.openscale.core.bluetooth.scales.EbelterBodyFatB2Handler
import com.health.openscale.core.bluetooth.scales.EtekcityESF551Handler
import com.health.openscale.core.bluetooth.scales.EtekcityFit8SHandler
import com.health.openscale.core.bluetooth.scales.EufyC20Handler
import com.health.openscale.core.bluetooth.scales.EufyP2Handler
import com.health.openscale.core.bluetooth.scales.ExcelvanCF36xHandler
import com.health.openscale.core.bluetooth.scales.ExingtechY1Handler
import com.health.openscale.core.bluetooth.scales.FitTrackDaraHandler
import com.health.openscale.core.bluetooth.scales.HesleyHandler
import com.health.openscale.core.bluetooth.scales.HoffenBbs8107Handler
import com.health.openscale.core.bluetooth.scales.HuaweiAhCh100Handler
import com.health.openscale.core.bluetooth.scales.HuaweiCH100SHandler
import com.health.openscale.core.bluetooth.scales.HuaweiHagridWspHandler
import com.health.openscale.core.bluetooth.scales.IHealthHS3Handler
import com.health.openscale.core.bluetooth.scales.InlifeHandler
import com.health.openscale.core.bluetooth.scales.KeepS3Handler
import com.health.openscale.core.bluetooth.scales.LinkMode
import com.health.openscale.core.bluetooth.scales.MGBHandler
import com.health.openscale.core.bluetooth.scales.MedisanaBs44xHandler
import com.health.openscale.core.bluetooth.scales.MiScaleHandler
import com.health.openscale.core.bluetooth.scales.MiScaleS400Handler
import com.health.openscale.core.bluetooth.scales.OkOkHandler
import com.health.openscale.core.bluetooth.scales.OmronWlcHandler
import com.health.openscale.core.bluetooth.scales.PicoocHandler
import com.health.openscale.core.bluetooth.scales.OneByoneHandler
import com.health.openscale.core.bluetooth.scales.OneByoneNewHandler
import com.health.openscale.core.bluetooth.scales.QNHandler
import com.health.openscale.core.bluetooth.scales.QNHandlerBroadcast
import com.health.openscale.core.bluetooth.scales.RealmeSmartScaleHandler
import com.health.openscale.core.bluetooth.scales.RelaxmedicHandler
import com.health.openscale.core.bluetooth.scales.RenphoES26BBHandler
import com.health.openscale.core.bluetooth.scales.RenphoHandler
import com.health.openscale.core.bluetooth.scales.RobiS9Handler
import com.health.openscale.core.bluetooth.scales.RunstarR5Handler
import com.health.openscale.core.bluetooth.scales.RunstarR6Handler
import com.health.openscale.core.bluetooth.scales.RyFitHandler
import com.health.openscale.core.bluetooth.scales.SanitasSbf72Handler
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.bluetooth.scales.ScaleupHandler
import com.health.openscale.core.bluetooth.scales.SenssunHandler
import com.health.openscale.core.bluetooth.scales.SinocareHandler
import com.health.openscale.core.bluetooth.scales.SoehnleHandler
import com.health.openscale.core.bluetooth.scales.StandardBeurerSanitasHandler
import com.health.openscale.core.bluetooth.scales.TaylorBIAHandler
import com.health.openscale.core.bluetooth.scales.TrisaBodyAnalyzeHandler
import com.health.openscale.core.bluetooth.scales.VitafitVT701Handler
import com.health.openscale.core.bluetooth.scales.WeightGurusA3Handler
import com.health.openscale.core.bluetooth.scales.XiaomiS800Handler
import com.health.openscale.core.bluetooth.scales.YunmaiHandler
import com.health.openscale.core.bluetooth.scales.YunmaiXHandler
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.UUID

/**
 * The one place a supported scale is written down.
 *
 * [fixtures] pairs a synthetic advertisement with the handler that must claim it, and serves two
 * jobs at once:
 *
 *  - `ScaleFactoryTest` asserts that the real registry — [ScaleFactory.createHandlers] in its real
 *    order, first match wins — still routes each device to its own driver.
 *  - `ScaleCatalogTest` asks the winning handler for its
 *    [com.health.openscale.core.bluetooth.scales.DeviceSupport] and renders the wiki page
 *    "Supported scales in openScale" from it.
 *
 * So adding a scale is one line here on top of the handler itself: the regression test and the
 * published table follow automatically. Nothing about a device is written down twice — display
 * name, link mode and capabilities all come from `supportFor`.
 *
 * Advertisements are built from the names and fingerprints documented in the handlers themselves,
 * never from personal captures.
 *
 * Human-written notes for the table's "Remarks" column live in
 * `src/test/resources/scale_catalog_remarks.txt`, the photo gallery in `scale_catalog_gallery.md`.
 */
object ScaleCatalog {

    // --- Fixtures ---------------------------------------------------------------------------

    /** An advertisement together with the handler that has to claim it. */
    data class Fixture(
        val device: ScannedDeviceInfo,
        val handler: Class<out ScaleDeviceHandler>,
    )

    fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    /** A connectable device: a name and, optionally, the services it advertises. */
    fun device(name: String, vararg services: UUID) = ScannedDeviceInfo(
        name = name,
        address = "C0:FF:EE:12:34:56",
        rssi = -50,
        serviceUuids = services.toList(),
        manufacturerData = null,
    )

    /** A broadcast advertisement: manufacturer records keyed by company id, as `ScanRecord` has them. */
    fun advertisement(
        name: String = "",
        services: List<UUID> = emptyList(),
        manufacturerData: List<Pair<Int, ByteArray>> = emptyList(),
    ) = ScannedDeviceInfo(
        name = name,
        address = "C0:FF:EE:12:34:56",
        rssi = -50,
        serviceUuids = services,
        manufacturerData = SparseArray<ByteArray>().apply {
            manufacturerData.forEach { (id, data) -> put(id, data) }
        },
    )

    /** Service 0xFFB0 — the LeFu-style service shared by a whole family of unrelated scales. */
    private val SERVICE_FFB0 = uuid16(0xFFB0)

    /** Etekcity's company id; with service 0xFFD0 the only fingerprint of the nameless Fit 8S. */
    private const val ETEKCITY_COMPANY_ID = 0x06D0

    private infix fun ScannedDeviceInfo.claimedBy(handler: Class<out ScaleDeviceHandler>) =
        Fixture(this, handler)

    /**
     * One entry per supported device. Where a handler reports several products under different
     * names (Beurer, Medisana, Omron, Huawei, 1byone, …) each product gets its own fixture, because
     * each is its own row in the published table.
     */
    val fixtures: List<Fixture> = listOf(
        // --- Matched by advertised name ---
        device("AE BS-06") claimedBy ActiveEraBF06Handler::class.java,
        device("AFU-BH-TZ-B1", uuid16(0xFC50)) claimedBy AfuB1Handler::class.java,
        device("Keep_S3") claimedBy KeepS3Handler::class.java,
        // The S3 Lite V2.0 advertises as PICOOC-CQ; the Latin series carries no vendor prefix.
        device("PICOOC-CQ") claimedBy PicoocHandler::class.java,
        device("Latin-S") claimedBy PicoocHandler::class.java,
        device("Beurer BF450") claimedBy BeurerBF450Handler::class.java,
        device("BIA SCALE", SERVICE_FFB0) claimedBy TaylorBIAHandler::class.java,
        device("RYFIT") claimedBy RyFitHandler::class.java,
        device("CULT Smart Scale Pro") claimedBy CultSmartScaleProHandler::class.java,
        device("realme Smart Scale") claimedBy RealmeSmartScaleHandler::class.java,
        device("YUNMAI-ISSE-1234") claimedBy YunmaiHandler::class.java,
        device("YUNMAI-SIGNAL-1234") claimedBy YunmaiHandler::class.java,
        device("01257B1234") claimedBy TrisaBodyAnalyzeHandler::class.java,
        device("SANITAS SBF72") claimedBy SanitasSbf72Handler::class.java,
        device("SANITAS SBF73") claimedBy SanitasSbf72Handler::class.java,
        device("Beurer BF915") claimedBy SanitasSbf72Handler::class.java,
        device("Beurer BF105") claimedBy StandardBeurerSanitasHandler::class.java,
        device("Beurer BF1000") claimedBy StandardBeurerSanitasHandler::class.java,
        device("Beurer BF500") claimedBy StandardBeurerSanitasHandler::class.java,
        device("Beurer BF600") claimedBy StandardBeurerSanitasHandler::class.java,
        device("Beurer BF950") claimedBy StandardBeurerSanitasHandler::class.java,
        device("Shape200") claimedBy SoehnleHandler::class.java,
        device("Weight Scale") claimedBy SinocareHandler::class.java,
        device("SENSSUN FAT") claimedBy SenssunHandler::class.java,
        // No QN service advertised, so the QN driver must not take it.
        device("RENPHO-SCALE-1234") claimedBy RenphoHandler::class.java,
        device("QN-Scale", uuid16(0xFFE0)) claimedBy QNHandler::class.java,
        device("Health Scale") claimedBy OneByoneHandler::class.java,
        device("eufy T9146") claimedBy OneByoneHandler::class.java,
        device("eufy T9147") claimedBy OneByoneHandler::class.java,
        device("eufy T9120") claimedBy OneByoneHandler::class.java,
        device("1byone scale") claimedBy OneByoneNewHandler::class.java,
        device("XMTZC14HM") claimedBy MiScaleS400Handler::class.java,
        device("MIJIA SCALE S800") claimedBy XiaomiS800Handler::class.java,
        device("MI_SCALE") claimedBy MiScaleHandler::class.java,
        device("MIBFS") claimedBy MiScaleHandler::class.java,
        device("runstar-r6") claimedBy RunstarR6Handler::class.java,
        device("RUNSTAR-R5") claimedBy RunstarR5Handler::class.java,
        device("relaxmedic") claimedBy RelaxmedicHandler::class.java,
        device("robi") claimedBy RobiS9Handler::class.java,
        device("Vitafit VT701") claimedBy VitafitVT701Handler::class.java,
        device("EEBBL") claimedBy EEBBLHandler::class.java,
        device("FITTRACK Dara") claimedBy FitTrackDaraHandler::class.java,
        device("SSW532", SERVICE_FFB0) claimedBy DrTrustSSW532Handler::class.java,
        device("swan", SERVICE_FFB0) claimedBy MGBHandler::class.java,
        device("0203B1234") claimedBy MedisanaBs44xHandler::class.java,
        device("0131971234") claimedBy MedisanaBs44xHandler::class.java,
        device("000fatscale01") claimedBy InlifeHandler::class.java,
        device("IHEALTH HS3") claimedBy IHealthHS3Handler::class.java,
        device("HUAWEI AH100") claimedBy HuaweiAhCh100Handler::class.java,
        device("HUAWEI CH100") claimedBy HuaweiAhCh100Handler::class.java,
        device("CH100S") claimedBy HuaweiCH100SHandler::class.java,
        device("HUAWEI Scale 2 Pro") claimedBy HuaweiHagridWspHandler::class.java,
        device("Hagrid-B29") claimedBy HuaweiHagridWspHandler::class.java,
        device("HUAWEI Scale 3") claimedBy HuaweiHagridWspHandler::class.java,
        device("Hoffen BS-8107") claimedBy HoffenBbs8107Handler::class.java,
        device("PC-PW 3008 BT") claimedBy HoffenBbs8107Handler::class.java,
        device("yunchen") claimedBy HesleyHandler::class.java,
        device("vscale") claimedBy ExingtechY1Handler::class.java,
        device("Body Fat-B2") claimedBy EbelterBodyFatB2Handler::class.java,
        device("Electronic Scale") claimedBy ExcelvanCF36xHandler::class.java,
        device("Etekcity Smart Fitness Scale") claimedBy EtekcityESF551Handler::class.java,
        device("EUFY C20") claimedBy EufyC20Handler::class.java,
        device("eufy T9148") claimedBy EufyP2Handler::class.java,
        device("ES-CS20M") claimedBy ESCS20MHandler::class.java,
        device("ES-26BB-B") claimedBy RenphoES26BBHandler::class.java,
        device("Mengii") claimedBy DigooDGSO38HHandler::class.java,
        device("openScale") claimedBy CustomOpenScaleHandler::class.java,
        device("BEURER BF700") claimedBy BeurerSanitasHandler::class.java,
        device("BEURER BF710") claimedBy BeurerSanitasHandler::class.java,
        device("SANITAS SBF70") claimedBy BeurerSanitasHandler::class.java,
        device("AAA002") claimedBy AAAxHandler::class.java,
        device("1BODY CONNECT") claimedBy BodyConnectHandler::class.java,
        device("1X-LINE") claimedBy BodyConnectHandler::class.java,
        device("10376BAA") claimedBy WeightGurusA3Handler::class.java,

        // Omron reports its model as the GAP name once bonded; advertised local names carry the
        // model id instead (see OmronWlcHandler.MODELS_BY_ADVERTISED_ID).
        device("HBF-702T") claimedBy OmronWlcHandler::class.java,
        device("KRD-703T") claimedBy OmronWlcHandler::class.java,
        device("HBF-227T") claimedBy OmronWlcHandler::class.java,
        device("HBF-228T") claimedBy OmronWlcHandler::class.java,
        device("HBF-230T") claimedBy OmronWlcHandler::class.java,
        device("HBF-222T") claimedBy OmronWlcHandler::class.java,
        device("BCM-500") claimedBy OmronWlcHandler::class.java,
        device("VIVA") claimedBy OmronWlcHandler::class.java,
        device("BLEsmart_0001000C0080E1A2B3C4") claimedBy OmronWlcHandler::class.java,

        // --- Matched by advertisement fingerprint, not by name ---
        // Yunmai X: advertised service 0x1320.
        advertisement(services = listOf(uuid16(0x1320))) claimedBy YunmaiXHandler::class.java,
        // QN broadcast variant: company id 0xFFFF with the AABB magic header.
        advertisement(
            manufacturerData = listOf(
                0xFFFF to byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x00, 0x00, 0x00, 0x00)
            )
        ) claimedBy QNHandlerBroadcast::class.java,
        // OKOK: company id behind one of the known names.
        advertisement(name = "ADV", manufacturerData = listOf(0x20CA to ByteArray(16)))
            claimedBy OkOkHandler::class.java,
        advertisement(name = "ADV", manufacturerData = listOf(0x11CA to ByteArray(16)))
            claimedBy OkOkHandler::class.java,
        advertisement(name = "ADV", manufacturerData = listOf(0xF0FF to ByteArray(16)))
            claimedBy OkOkHandler::class.java,
        // Etekcity Fit 8S: nameless, company id 0x06D0 plus service 0xFFD0.
        advertisement(
            services = listOf(uuid16(0xFFD0)),
            manufacturerData = listOf(ETEKCITY_COMPANY_ID to ByteArray(20)),
        ) claimedBy EtekcityFit8SHandler::class.java,
        // Scaleup: any manufacturer record whose company-id low byte is 0xD0 (measuring) or 0xE0.
        advertisement(manufacturerData = listOf(0x1DD0 to ByteArray(9)))
            claimedBy ScaleupHandler::class.java,
    )

    // --- Registry queries -------------------------------------------------------------------

    /**
     * The handler [ScaleFactory.createCommunicator] would pick for [device], or null.
     *
     * A fresh registry per call: several handlers stash state in `supportFor` (e.g.
     * StandardBeurerSanitasHandler remembers the matched model), so a shared instance would let one
     * fixture bleed into the next.
     */
    fun winner(device: ScannedDeviceInfo): ScaleDeviceHandler? =
        ScaleFactory.createHandlers().firstOrNull { it.supportFor(device) != null }

    /** Every handler that claims [device] — more than one means two drivers overlap. */
    fun claimants(device: ScannedDeviceInfo): List<ScaleDeviceHandler> =
        ScaleFactory.createHandlers().filter { it.supportFor(device) != null }

    // --- Catalog ----------------------------------------------------------------------------

    /** One published row: what the winning handler reports for one fixture. */
    data class Row(
        val displayName: String,
        val handler: String,
        val linkMode: LinkMode,
        val capabilities: Set<DeviceCapability>,
        val implemented: Set<DeviceCapability>,
    )

    /** Runs every fixture through the registry and collects what the winning handler reports. */
    fun rows(): List<Row> =
        fixtures.mapNotNull { fixture ->
            ScaleFactory.createHandlers().firstNotNullOfOrNull { handler ->
                handler.supportFor(fixture.device)?.let { support ->
                    Row(
                        displayName = support.displayName,
                        handler = handler.javaClass.simpleName,
                        linkMode = support.linkMode,
                        capabilities = support.capabilities,
                        implemented = support.implemented,
                    )
                }
            }
        }
            .distinctBy { it.displayName }
            .sortedBy { it.displayName.lowercase() }

    // --- Rendering --------------------------------------------------------------------------

    /** Where a handler's source file lives, relative to the repository root. */
    fun sourcePath(handler: String): String =
        "android_app/app/src/main/java/com/health/openscale/core/bluetooth/scales/$handler.kt"

    private const val SOURCE_BASE_URL = "https://github.com/oliexdev/openScale/blob/master"

    /** A literal pipe in a display name or remark would split the markdown cell. */
    private fun cell(text: String): String = text.replace("|", "\\|")

    private fun mark(row: Row, capability: DeviceCapability): String = when {
        capability in row.implemented -> "&#10003;"   // ✓ supported in openScale
        capability in row.capabilities -> "o"         // offered by the scale, not implemented yet
        else -> "n/a"                                 // not available on the scale
    }

    private fun linkLabel(mode: LinkMode): String = when (mode) {
        LinkMode.CONNECT_GATT -> "BLE GATT"
        LinkMode.BROADCAST_ONLY -> "BLE Broadcast"
        // SPP runs over RFCOMM on Bluetooth Classic (BR/EDR), not over BLE — the distinction
        // matters to users because those scales pair differently.
        LinkMode.CLASSIC_SPP -> "Classic SPP"
    }

    /** The capability columns, in the order they appear in the table. */
    private val CAPABILITY_COLUMNS = listOf(
        DeviceCapability.BODY_COMPOSITION to "Body metrics",
        DeviceCapability.HISTORY_READ to "History data",
        DeviceCapability.LIVE_WEIGHT_STREAM to "Live weight",
        DeviceCapability.TIME_SYNC to "Time sync",
        DeviceCapability.USER_SYNC to "User sync",
        DeviceCapability.UNIT_CONFIG to "Unit config",
        DeviceCapability.BATTERY_LEVEL to "Battery",
    )

    /**
     * Renders the wiki page. [remarks] is keyed by display name, falling back to the handler class
     * name so one note can cover every product a driver reports.
     */
    fun renderMarkdown(rows: List<Row>, remarks: Map<String, String>): String = buildString {
        appendLine("## Scale support overview")
        appendLine()
        appendLine("<!--")
        appendLine("  GENERATED FILE — do not edit the table by hand.")
        appendLine("  Produced by ScaleCatalogTest from the handler registry (ScaleFactory.createHandlers).")
        appendLine("  CI regenerates it on every push to master; edits made here are overwritten.")
        appendLine("  To change a row, change the handler's supportFor(); to change a remark or the")
        appendLine("  photo gallery, edit android_app/app/src/test/resources/scale_catalog_remarks.txt")
        appendLine("  or scale_catalog_gallery.md. To render it locally:")
        appendLine("    SCALE_CATALOG_OUT=<wiki>/Supported-scales-in-openScale.md ./gradlew testDebugUnitTest --tests '*ScaleCatalogTest'")
        appendLine("-->")
        appendLine()
        appendLine("> [!IMPORTANT]")
        appendLine("> - I do **not own** every scale.")
        appendLine("> - openScale is an **open-source, community-driven project**.")
        appendLine("> - Some scales may be **incompatible**, and I cannot guarantee that all features will work.")
        appendLine("> - Please understand that this project is maintained **voluntarily**, and there is **no official warranty or support**.")
        appendLine("> - Users should have **realistic expectations**: not every scale will work perfectly, and encountering issues is normal.")
        appendLine("> - Bluetooth protocols can **change at any time**, and some cheap scales are **poorly implemented by the manufacturer**.")
        appendLine("> - openScale relies on user contributions. If you can help test scales, improve support, or fix issues, **pull requests are very welcome**.")
        appendLine()
        appendLine("> [!NOTE]")
        appendLine("> If you want to help support your Bluetooth scale, please read [How to support a new scale](How-to-support-a-new-scale) for further information.")
        appendLine()

        val header = listOf("Scale", "Handler", "Connection") +
            CAPABILITY_COLUMNS.map { it.second } +
            listOf("Remarks")
        appendLine(header.joinToString(" | ", prefix = "| ", postfix = " |"))
        appendLine(header.joinToString("|", prefix = "|", postfix = "|") { "---" })

        for (row in rows) {
            val remark = remarks[row.displayName] ?: remarks[row.handler] ?: "-"
            val cells = listOf(
                cell(row.displayName),
                "[${row.handler}]($SOURCE_BASE_URL/${sourcePath(row.handler)})",
                linkLabel(row.linkMode),
            ) + CAPABILITY_COLUMNS.map { (capability, _) -> mark(row, capability) } +
                listOf(cell(remark))
            appendLine(cells.joinToString(" | ", prefix = "| ", postfix = " |"))
        }

        appendLine()
        appendLine("&#10003; : supported in openScale <br>")
        appendLine("o : supported by the scale but still needs to be reverse engineered<br>")
        appendLine("n/a : not available on the scale")
        appendLine()
        appendLine("_${rows.size} scales, generated from ${ScaleFactory.createHandlers().size} registered handlers._")

        // The photo gallery is hand-curated (there is nothing in the code to derive a picture
        // from), so it is carried through verbatim rather than regenerated.
        loadGallery()?.let {
            appendLine()
            append(it)
        }
    }

    /**
     * Reads the hand-written remarks. Format is one `displayName = remark` per line; `#` starts a
     * comment. Deliberately not JSON — the file is edited by hand, and a stray comma should not
     * cost a maintainer a test run.
     */
    fun loadRemarks(): Map<String, String> {
        val stream = javaClass.classLoader?.getResourceAsStream(REMARKS_RESOURCE) ?: return emptyMap()
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val separator = line.indexOf('=')
                    if (separator <= 0) null
                    else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
                .toMap()
        }
    }

    /**
     * The hand-curated "Detailed information" photo gallery, appended below the generated table.
     * Edit it as plain markdown; it is copied through untouched.
     */
    fun loadGallery(): String? =
        javaClass.classLoader?.getResourceAsStream(GALLERY_RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }

    const val REMARKS_RESOURCE = "scale_catalog_remarks.txt"

    const val GALLERY_RESOURCE = "scale_catalog_gallery.md"
}
