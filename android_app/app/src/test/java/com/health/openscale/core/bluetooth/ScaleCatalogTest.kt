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
 * from going stale again: a handler nobody lists a fixture for fails the build.
 *
 * Robolectric is required because some handlers touch the Android framework while being constructed
 * (e.g. QNHandler creates a main-looper Handler) and because the fixtures build `SparseArray`s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScaleCatalogTest {

    /**
     * The guard that matters: every registered handler must be reachable — it has to win at least
     * one fixture. A handler that wins nothing is either missing from [ScaleCatalog.fixtures] (the
     * table would silently omit the scale) or unreachable behind a broader matcher earlier in the
     * registry (the device would never reach its own driver).
     */
    @Test
    fun `every registered handler is covered by a fixture`() {
        val registered = ScaleFactory.createHandlers().map { it.javaClass.simpleName }.toSet()
        val covered = ScaleCatalog.rows().map { it.handler }.toSet()

        assertThat(registered - covered).isEmpty()
    }

    /** Two rows with the same product name would publish a duplicate line in the wiki table. */
    @Test
    fun `catalog rows are unique per display name`() {
        assertThat(ScaleCatalog.rows().map { it.displayName }).containsNoDuplicates()
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

    /**
     * The table links every handler to its source file. The path is derived from the class name, so
     * a driver whose file is named differently would publish a 404.
     */
    @Test
    fun `every handler links to an existing source file`() {
        // Unit tests run with the module directory (android_app/app) as the working directory.
        val repositoryRoot = File("../..").canonicalFile
        val missing = ScaleFactory.createHandlers()
            .map { it.javaClass.simpleName }
            .distinct()
            .map { ScaleCatalog.sourcePath(it) }
            .filterNot { File(repositoryRoot, it).isFile }

        assertThat(missing).isEmpty()
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

        assertThat(markdown).contains("| Scale | Handler | Connection |")
        assertThat(rows).isNotEmpty()
    }
}
