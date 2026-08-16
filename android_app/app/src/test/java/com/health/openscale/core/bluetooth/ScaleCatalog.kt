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
import com.health.openscale.core.bluetooth.scales.DeviceCapability
import com.health.openscale.core.bluetooth.scales.LinkMode
import com.health.openscale.core.bluetooth.scales.ScaleDeviceHandler
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.UUID

/**
 * The scale catalog: the wiki's "Supported scales in openScale" table, derived from the code that
 * actually decides support.
 *
 * Every row is produced by asking the real registry — [ScaleFactory.createHandlers] in its real
 * order — what it makes of a synthetic advertisement, and then reading the returned
 * [com.health.openscale.core.bluetooth.scales.DeviceSupport]. Nothing about a scale is written down
 * twice: display name, link mode and the capability sets all come from `supportFor`, so a driver
 * that gains (or loses) a capability changes the published table on the next generator run.
 *
 * The one thing that cannot be derived is the input: `supportFor` only answers when it is handed an
 * advertisement it recognises. [probes] is that input — one synthetic advertisement per supported
 * device, built from the names and fingerprints documented in the handlers themselves, never from
 * personal captures. `ScaleCatalogTest` fails when a registered handler has no probe, which is what
 * keeps the table from silently falling behind the registry.
 *
 * Human-written notes (the "Remarks" column) live in `src/test/resources/scale_catalog_remarks.txt`
 * and are merged in by display name — see [renderMarkdown].
 */
object ScaleCatalog {

    // --- Probe construction ---------------------------------------------------------------

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

    /** Etekcity's company id; with service 0xFFD0 the only fingerprint of the nameless Fit 8S. */
    private const val ETEKCITY_COMPANY_ID = 0x06D0

    /** Service 0xFFB0 — the LeFu-style service shared by a whole family of unrelated scales. */
    private val SERVICE_FFB0 = uuid16(0xFFB0)

    /**
     * One synthetic advertisement per supported device.
     *
     * Where a handler reports different products under different names (Beurer, Medisana, Omron,
     * 1byone, Huawei, …) every product gets its own probe, because each yields its own catalog row.
     */
    val probes: List<ScannedDeviceInfo> = listOf(
        // --- Matched by advertised name ---
        device("AE BS-06"),                              // ActiveEraBF06Handler
        device("Keep_S3"),                               // KeepS3Handler
        device("Beurer BF450"),                          // BeurerBF450Handler
        device("BIA SCALE", SERVICE_FFB0),               // TaylorBIAHandler
        device("RYFIT"),                                 // RyFitHandler
        device("CULT Smart Scale Pro"),                  // CultSmartScaleProHandler
        device("realme Smart Scale"),                    // RealmeSmartScaleHandler
        device("YUNMAI-ISSE-1234"),                      // YunmaiHandler(isMini = false)
        device("YUNMAI-SIGNAL-1234"),                    // YunmaiHandler(isMini = true)
        device("01257B1234"),                            // TrisaBodyAnalyzeHandler
        device("SANITAS SBF72"),                         // SanitasSbf72Handler
        device("SANITAS SBF73"),                         // …
        device("Beurer BF915"),                          // …
        device("Beurer BF105"),                          // StandardBeurerSanitasHandler
        device("Beurer BF500"),                          // …
        device("Beurer BF600"),                          // …
        device("Beurer BF950"),                          // …
        device("Shape200"),                              // SoehnleHandler
        device("Weight Scale"),                          // SinocareHandler
        device("SENSSUN FAT"),                           // SenssunHandler
        device("RENPHO-SCALE-1234"),                     // RenphoHandler (no QN service → not QNHandler)
        device("QN-Scale", uuid16(0xFFE0)),              // QNHandler
        device("Health Scale"),                          // OneByoneHandler (1byone classic)
        device("eufy T9146"),                            // … Eufy C1
        device("eufy T9147"),                            // … Eufy P1
        device("eufy T9120"),                            // … Eufy A1
        device("1byone scale"),                          // OneByoneNewHandler
        device("XMTZC14HM"),                             // MiScaleS400Handler
        device("MIJIA SCALE S800"),                      // XiaomiS800Handler
        device("MI_SCALE"),                              // MiScaleHandler (v1)
        device("MIBFS"),                                 // MiScaleHandler (v2)
        device("runstar-r6"),                            // RunstarR6Handler
        device("RUNSTAR-R5"),                            // RunstarR5Handler
        device("relaxmedic"),                            // RelaxmedicHandler
        device("robi"),                                  // RobiS9Handler
        device("Vitafit VT701"),                         // VitafitVT701Handler
        device("EEBBL"),                                 // EEBBLHandler
        device("FITTRACK Dara"),                         // FitTrackDaraHandler
        device("SSW532", SERVICE_FFB0),                  // DrTrustSSW532Handler
        device("swan", SERVICE_FFB0),                    // MGBHandler
        device("0203B1234"),                             // MedisanaBs44xHandler (BS430)
        device("0131971234"),                            // … (BS444/BS440)
        device("000fatscale01"),                         // InlifeHandler
        device("IHEALTH HS3"),                           // IHealthHS3Handler
        device("HUAWEI AH100"),                          // HuaweiAhCh100Handler
        device("HUAWEI CH100"),                          // …
        device("CH100S"),                                // HuaweiCH100SHandler
        device("HUAWEI Scale 2 Pro"),                    // HuaweiHagridWspHandler
        device("Hagrid-B29"),                            // … Scale 3 Pro
        device("HUAWEI Scale 3"),                        // … Scale 3
        device("Hoffen BS-8107"),                        // HoffenBbs8107Handler
        device("yunchen"),                               // HesleyHandler
        device("vscale"),                                // ExingtechY1Handler
        device("Body Fat-B2"),                           // EbelterBodyFatB2Handler
        device("Electronic Scale"),                      // ExcelvanCF36xHandler
        device("Etekcity Smart Fitness Scale"),          // EtekcityESF551Handler
        device("EUFY C20"),                              // EufyC20Handler
        device("eufy T9148"),                            // EufyP2Handler
        device("ES-CS20M"),                              // ESCS20MHandler
        device("ES-26BB-B"),                             // RenphoES26BBHandler
        device("Mengii"),                                // DigooDGSO38HHandler
        device("debug"),                                 // DebugGattHandler
        device("openScale"),                             // CustomOpenScaleHandler
        device("BEURER BF700"),                          // BeurerSanitasHandler (BF700/800/Libra)
        device("BEURER BF710"),                          // … BF710
        device("SANITAS SBF70"),                         // … SBF70/SBF75/Crane
        device("AAA002"),                                // AAAxHandler
        device("1BODY CONNECT"),                         // BodyConnectHandler
        device("1X-LINE"),                               // … Terraillon X-LINE
        device("10376BAA"),                              // WeightGurusA3Handler

        // Omron reports its model as the GAP name once bonded; advertised local names carry the
        // model id instead (see OmronWlcHandler.MODELS_BY_ADVERTISED_ID).
        device("HBF-702T"),
        device("KRD-703T"),
        device("HBF-227T"),
        device("HBF-228T"),
        device("HBF-230T"),
        device("HBF-222T"),
        device("BCM-500"),
        device("VIVA"),

        // --- Matched by advertisement fingerprint, not by name ---
        // Yunmai X: advertised service 0x1320.
        advertisement(services = listOf(uuid16(0x1320))),
        // QN broadcast variant: company id 0xFFFF with the AABB magic header.
        advertisement(
            manufacturerData = listOf(
                0xFFFF to byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x00, 0x00, 0x00, 0x00)
            )
        ),
        // OKOK: company id behind one of the known names.
        advertisement(name = "ADV", manufacturerData = listOf(0x20CA to ByteArray(16))),
        advertisement(name = "ADV", manufacturerData = listOf(0x11CA to ByteArray(16))),
        advertisement(name = "ADV", manufacturerData = listOf(0xF0FF to ByteArray(16))),
        // Etekcity Fit 8S: nameless, company id 0x06D0 plus service 0xFFD0.
        advertisement(
            services = listOf(uuid16(0xFFD0)),
            manufacturerData = listOf(ETEKCITY_COMPANY_ID to ByteArray(20)),
        ),
        // Scaleup: any manufacturer record whose company-id low byte is 0xD0 (measuring) or 0xE0.
        advertisement(manufacturerData = listOf(0x1DD0 to ByteArray(9))),
    )

    // --- Catalog ----------------------------------------------------------------------------

    /** One published row: what the registry reports for one probe. */
    data class Row(
        val displayName: String,
        val handler: String,
        val linkMode: LinkMode,
        val capabilities: Set<DeviceCapability>,
        val implemented: Set<DeviceCapability>,
    )

    /**
     * Runs every probe through the registry and collects what the *winning* handler reports —
     * first match wins, exactly as [ScaleFactory.createCommunicator] resolves a real scan result.
     *
     * A fresh registry per probe: several handlers stash state in `supportFor` (e.g.
     * StandardBeurerSanitasHandler remembers the matched model), so a shared instance would let one
     * probe bleed into the next.
     */
    fun rows(): List<Row> =
        probes.mapNotNull { probe ->
            ScaleFactory.createHandlers().firstNotNullOfOrNull { handler ->
                handler.supportFor(probe)?.let { support ->
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

    /** The handler that claims [probe], or null — the same first-match-wins rule the factory uses. */
    fun winner(probe: ScannedDeviceInfo): ScaleDeviceHandler? =
        ScaleFactory.createHandlers().firstOrNull { it.supportFor(probe) != null }

    // --- Rendering --------------------------------------------------------------------------

    /** A literal pipe in a display name or remark would split the markdown cell. */
    private fun cell(text: String): String = text.replace("|", "\\|")

    private fun mark(row: Row, capability: DeviceCapability): String = when {
        capability in row.implemented -> "&#10003;"   // ✓ supported in openScale
        capability in row.capabilities -> "o"         // offered by the scale, not implemented yet
        else -> "n/a"                                 // not available on the scale
    }

    private fun linkLabel(mode: LinkMode): String = when (mode) {
        LinkMode.CONNECT_GATT -> "BLE (connect)"
        LinkMode.BROADCAST_ONLY -> "BLE (broadcast)"
        LinkMode.CLASSIC_SPP -> "Bluetooth Classic"
    }

    /** Capabilities that get their own column; everything else is listed in "Other capabilities". */
    private val COLUMNS = listOf(
        DeviceCapability.BODY_COMPOSITION,
        DeviceCapability.HISTORY_READ,
        DeviceCapability.LIVE_WEIGHT_STREAM,
    )

    private fun otherCapabilities(row: Row): String {
        val labels = DeviceCapability.entries
            .filter { it !in COLUMNS }
            .filter { it in row.capabilities }
            .map { capability ->
                val name = when (capability) {
                    DeviceCapability.TIME_SYNC -> "time sync"
                    DeviceCapability.USER_SYNC -> "user sync"
                    DeviceCapability.UNIT_CONFIG -> "unit config"
                    DeviceCapability.BATTERY_LEVEL -> "battery"
                    else -> capability.name.lowercase()
                }
                if (capability in row.implemented) name else "$name (o)"
            }
        return if (labels.isEmpty()) "-" else labels.joinToString(", ")
    }

    /**
     * Renders the wiki page. [remarks] is keyed by display name, falling back to the handler class
     * name so a note can cover every product a driver reports.
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
        appendLine("| Scale | Connection | Body metrics | History data | Live weight | Other capabilities | Remarks |")
        appendLine("|---|---|---|---|---|---|---|")
        for (row in rows) {
            val remark = remarks[row.displayName] ?: remarks[row.handler] ?: "-"
            appendLine(
                "| ${cell(row.displayName)} " +
                    "| ${linkLabel(row.linkMode)} " +
                    "| ${mark(row, DeviceCapability.BODY_COMPOSITION)} " +
                    "| ${mark(row, DeviceCapability.HISTORY_READ)} " +
                    "| ${mark(row, DeviceCapability.LIVE_WEIGHT_STREAM)} " +
                    "| ${otherCapabilities(row)} " +
                    "| ${cell(remark)} |"
            )
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
