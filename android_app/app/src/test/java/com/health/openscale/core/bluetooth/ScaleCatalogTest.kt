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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Keeps the published scale list honest and regenerates it.
 *
 * The wiki page "Supported scales in openScale" used to be maintained by hand and drifted behind
 * the registry — scales were added to [ScaleFactory] for releases without ever reaching the table.
 * [ScaleCatalog] derives that table from `supportFor` instead; this test is the half that stops it
 * from going stale again: a handler nobody probes fails the build.
 *
 * Robolectric is required because some handlers touch the Android framework while being constructed
 * (e.g. QNHandler creates a main-looper Handler) and because the probes build `SparseArray`s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScaleCatalogTest {

    /**
     * The guard that matters: every registered handler must be reachable — it has to win at least
     * one probe. A handler that wins nothing is either missing from [ScaleCatalog.probes] (the
     * table would silently omit the scale) or unreachable behind a broader matcher earlier in the
     * list (the device would never reach its own driver).
     */
    @Test
    fun `every registered handler is covered by a probe`() {
        val registered = ScaleFactory.createHandlers().map { it.javaClass.simpleName }.toSet()
        val covered = ScaleCatalog.rows().map { it.handler }.toSet()

        assertThat(registered - covered).isEmpty()
    }

    /** A probe that no handler claims is dead weight and hides a matcher that has been narrowed. */
    @Test
    fun `every probe is claimed by a handler`() {
        val orphans = ScaleCatalog.probes
            .filter { ScaleCatalog.winner(it) == null }
            .map { "${it.name.ifEmpty { "<nameless>" }} / services=${it.serviceUuids}" }

        assertThat(orphans).isEmpty()
    }

    /** Two rows with the same product name would publish a duplicate line in the wiki table. */
    @Test
    fun `catalog rows are unique per display name`() {
        val rows = ScaleCatalog.rows()

        assertThat(rows.map { it.displayName }).containsNoDuplicates()
    }

    /**
     * `implemented` is documented as a subset of `capabilities`: the table prints a capability as
     * "supported" from the first set and "known but not implemented" from the difference, so a
     * handler listing an implemented capability it never declared would publish a hole.
     */
    @Test
    fun `implemented capabilities are a subset of the declared ones`() {
        for (row in ScaleCatalog.rows()) {
            assertThat(row.capabilities).containsAtLeastElementsIn(row.implemented)
        }
    }

    /** Every remark must reach a row; a typo in a key would otherwise drop the note silently. */
    @Test
    fun `every hand-written remark matches a catalog row`() {
        val rows = ScaleCatalog.rows()
        val keys = rows.map { it.displayName }.toSet() + rows.map { it.handler }.toSet()

        assertThat(ScaleCatalog.loadRemarks().keys - keys).isEmpty()
    }

    /**
     * Writes the wiki page. Defaults to the build directory; point it straight at a wiki clone with
     *
     *     SCALE_CATALOG_OUT=$HOME/Workspace/openScale.wiki/Supported-scales-in-openScale.md \
     *         ./gradlew testDebugUnitTest --tests '*ScaleCatalogTest'
     *
     * An environment variable rather than a system property: Gradle forks a JVM for unit tests, and
     * `-D` flags stay with the Gradle daemon unless they are forwarded in build.gradle.kts, while
     * the environment is inherited.
     */
    @Test
    fun `generate the supported scales page`() {
        val rows = ScaleCatalog.rows()
        val markdown = ScaleCatalog.renderMarkdown(rows, ScaleCatalog.loadRemarks())

        val target = File(
            System.getenv("SCALE_CATALOG_OUT")
                ?: "build/reports/scale-catalog/Supported-scales-in-openScale.md"
        )
        target.parentFile?.mkdirs()
        target.writeText(markdown)

        println("Scale catalog: ${rows.size} scales written to ${target.absolutePath}")

        assertThat(markdown).contains("| Scale | Connection |")
        assertThat(rows).isNotEmpty()
    }
}
