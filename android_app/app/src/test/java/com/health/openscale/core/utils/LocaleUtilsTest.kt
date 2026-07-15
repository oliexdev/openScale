/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
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
package com.health.openscale.core.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocaleUtilsTest {

    private companion object {
        const val EPS = 1e-4f
    }

    @Test
    fun parseLocalizedFloat_acceptsDotSeparator() {
        assertThat(LocaleUtils.parseLocalizedFloat("80.5")).isWithin(EPS).of(80.5f)
    }

    @Test
    fun parseLocalizedFloat_acceptsCommaSeparator() {
        // Comma decimals are what Spanish (and other) number keyboards emit.
        assertThat(LocaleUtils.parseLocalizedFloat("80,5")).isWithin(EPS).of(80.5f)
        assertThat(LocaleUtils.parseLocalizedFloat("1,75")).isWithin(EPS).of(1.75f)
    }

    @Test
    fun parseLocalizedFloat_trimsWhitespace() {
        assertThat(LocaleUtils.parseLocalizedFloat("  72,3  ")).isWithin(EPS).of(72.3f)
    }

    @Test
    fun parseLocalizedFloat_returnsNullForInvalidInput() {
        assertThat(LocaleUtils.parseLocalizedFloat("")).isNull()
        assertThat(LocaleUtils.parseLocalizedFloat("abc")).isNull()
    }
}
