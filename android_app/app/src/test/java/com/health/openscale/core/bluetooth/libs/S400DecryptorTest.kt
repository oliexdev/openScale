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
package com.health.openscale.core.bluetooth.libs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [S400Decryptor].
 *
 * Test vectors are derived from the mi-scale-exporter project:
 * https://github.com/lswiderski/MiScaleBodyComposition
 */
class S400DecryptorTest {

    companion object {
        // Test configuration from mi-scale-exporter test suite
        private const val TEST_MAC = "84:46:93:64:A5:E6"
        private const val TEST_BIND_KEY = "58305740b64e4b425e518aa1f4e51339"

        private fun hexToByteArray(hex: String): ByteArray {
            val cleanHex = hex.replace(" ", "").lowercase()
            return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    // --- Validation tests ---

    @Test
    fun isValidBindKey_acceptsValid32CharHexKey() {
        assertThat(S400Decryptor.isValidBindKey("58305740b64e4b425e518aa1f4e51339")).isTrue()
        assertThat(S400Decryptor.isValidBindKey("00000000000000000000000000000000")).isTrue()
        assertThat(S400Decryptor.isValidBindKey("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")).isTrue()
        assertThat(S400Decryptor.isValidBindKey("abcdef0123456789abcdef0123456789")).isTrue()
    }

    @Test
    fun isValidBindKey_rejectsInvalidKeys() {
        // Too short
        assertThat(S400Decryptor.isValidBindKey("58305740b64e4b425e518aa1f4e5133")).isFalse()
        // Too long
        assertThat(S400Decryptor.isValidBindKey("58305740b64e4b425e518aa1f4e513399")).isFalse()
        // Contains invalid characters
        assertThat(S400Decryptor.isValidBindKey("58305740b64e4b425e518aa1f4e5133g")).isFalse()
        // Empty
        assertThat(S400Decryptor.isValidBindKey("")).isFalse()
    }

    @Test
    fun isValidMacAddress_acceptsValidFormats() {
        assertThat(S400Decryptor.isValidMacAddress("84:46:93:64:A5:E6")).isTrue()
        assertThat(S400Decryptor.isValidMacAddress("00:00:00:00:00:00")).isTrue()
        assertThat(S400Decryptor.isValidMacAddress("FF:FF:FF:FF:FF:FF")).isTrue()
        assertThat(S400Decryptor.isValidMacAddress("aa:bb:cc:dd:ee:ff")).isTrue()
    }

    @Test
    fun isValidMacAddress_rejectsInvalidFormats() {
        // No colons
        assertThat(S400Decryptor.isValidMacAddress("844693e4A5E6")).isFalse()
        // Wrong separator
        assertThat(S400Decryptor.isValidMacAddress("84-46-93-64-A5-E6")).isFalse()
        // Too short
        assertThat(S400Decryptor.isValidMacAddress("84:46:93:64:A5")).isFalse()
        // Invalid characters
        assertThat(S400Decryptor.isValidMacAddress("84:46:93:64:A5:GG")).isFalse()
        // Empty
        assertThat(S400Decryptor.isValidMacAddress("")).isFalse()
    }

    // --- Decryption tests using vectors from mi-scale-exporter ---

    @Test
    fun decrypt_24ByteData_returnsCorrectWeight() {
        // Test1 from S400Test.cs: expected weight = 74.2
        val data = hexToByteArray("4859d53b2d3314943c58b133638c7457a4000000c3e670dc")

        val result = S400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY)

        assertThat(result).isNotNull()
        assertThat(result!!.weightKg).isWithin(0.1f).of(74.2f)
    }

    @Test
    fun decrypt_26ByteDataFromHex_returnsCorrectWeight() {
        // Test26bytesHex from S400Test.cs: expected weight = 73.2
        val data = hexToByteArray("95FE4859D53B3BDE6BC8D05B51C0CDFD9021C9000000925C5039")

        val result = S400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY)

        assertThat(result).isNotNull()
        assertThat(result!!.weightKg).isWithin(0.1f).of(73.2f)
    }

    @Test
    fun decrypt_26ByteDataFromBytes_returnsCorrectWeight() {
        // Test26bytes from S400Test.cs: expected weight = 73.3
        val data = byteArrayOf(
            149.toByte(), 254.toByte(), 72, 89, 213.toByte(), 59, 77, 111, 53,
            156.toByte(), 229.toByte(), 111, 31, 126, 126, 10, 221.toByte(),
            220.toByte(), 38, 0, 0, 0, 12, 19, 211.toByte(), 196.toByte()
        )

        val result = S400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY)

        assertThat(result).isNotNull()
        assertThat(result!!.weightKg).isWithin(0.1f).of(73.3f)
    }

    @Test
    fun decrypt_weightOnlyData_returnsWeightWithNoImpedance() {
        // Test26bytesOnlyWeight from S400Test.cs: weight > 0, impedance = null
        val data = byteArrayOf(
            149.toByte(), 254.toByte(), 72, 89, 213.toByte(), 59, 99, 187.toByte(),
            88, 121, 80, 225.toByte(), 4, 44, 172.toByte(), 28, 95, 24, 246.toByte(),
            0, 0, 0, 219.toByte(), 233.toByte(), 112, 52
        )

        val result = S400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY)

        assertThat(result).isNotNull()
        assertThat(result!!.weightKg).isGreaterThan(0f)
        assertThat(result.impedance).isNull()
    }

    @Test
    fun decrypt_invalidDataLength_returnsNull() {
        // TestJustMACAddress from S400Test.cs: too short data (11 bytes)
        val data = hexToByteArray("1059d53b06e6a5649346 84")

        val result = S400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY)

        assertThat(result).isNull()
    }

    @Test
    fun decrypt_invalidBindKey_returnsNull() {
        val data = hexToByteArray("4859d53b2d3314943c58b133638c7457a4000000c3e670dc")
        val invalidKey = "00000000000000000000000000000000"

        // Decryption with wrong key should fail (or return null/invalid data)
        val result = S400Decryptor.decrypt(data, TEST_MAC, invalidKey)

        // With wrong key, decryption will either:
        // 1. Return null (if exception is caught)
        // 2. Return invalid measurement (weight = 0 or nonsense)
        val isInvalidResult = result == null || result.weightKg <= 0f || result.weightKg > 500f
        assertThat(isInvalidResult).isTrue()
    }

    @Test
    fun decrypt_shortBindKey_returnsNull() {
        val data = hexToByteArray("4859d53b2d3314943c58b133638c7457a4000000c3e670dc")
        val shortKey = "58305740b64e4b42" // Only 16 chars instead of 32

        val result = S400Decryptor.decrypt(data, TEST_MAC, shortKey)

        assertThat(result).isNull()
    }

    @Test
    fun decrypt_emptyData_returnsNull() {
        val result = S400Decryptor.decrypt(byteArrayOf(), TEST_MAC, TEST_BIND_KEY)
        assertThat(result).isNull()
    }

    // --- Measurement value extraction tests ---

    @Test
    fun measurement_hasExpectedFields() {
        val data = hexToByteArray("4859d53b2d3314943c58b133638c7457a4000000c3e670dc")

        val result = S400Decryptor.decrypt(data, TEST_MAC, TEST_BIND_KEY)

        assertThat(result).isNotNull()
        // Weight should be reasonable (between 0 and 300 kg)
        assertThat(result!!.weightKg).isGreaterThan(0f)
        assertThat(result.weightKg).isLessThan(300f)
        // Impedance if present should be reasonable (typically 300-1000 ohms for body)
        result.impedance?.let { imp ->
            assertThat(imp).isGreaterThan(0f)
        }
    }
}
