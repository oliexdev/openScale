/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import com.health.openscale.core.bluetooth.data.ScaleUser
import org.junit.Test

class BodyPod2HandlerTest {
    @Test
    fun `builds documented user profile and checksum`() {
        val user = ScaleUser(bodyHeight = 173f)
        val packet = BodyPod2Handler.buildUserProfile(user)

        assertThat(packet[0]).isEqualTo(0xFE.toByte())
        assertThat(packet[1]).isEqualTo(0x01.toByte())
        assertThat(packet[2]).isEqualTo(0x01.toByte())
        assertThat(packet[3]).isEqualTo(0x00.toByte())
        assertThat(packet[4]).isEqualTo(0xAD.toByte())
        assertThat(packet[6]).isEqualTo(0x01.toByte())
        assertThat(packet[7]).isEqualTo(xor(packet, 1))
    }

    @Test
    fun `validates CF and BE packet checksums`() {
        // CF: stable weight 0x2451 (92.97 kg); BE: two-frequency segment impedances.
        val cf = hex("cf 00 80 51 24 00 00 00 00 01 00")
        cf[cf.lastIndex] = xor(cf)
        val be = hex("be 00 f7 0c 9d 0c 0e 01 03 07 11 09 00")
        be[be.lastIndex] = xor(be)

        assertThat(BodyPod2Handler.validChecksum(cf)).isTrue()
        assertThat(BodyPod2Handler.uint16le(cf, 3)).isEqualTo(0x2451)
        assertThat(BodyPod2Handler.validChecksum(be)).isTrue()
        assertThat(BodyPod2Handler.validChecksum(be.copyOf().also { it[1] = 2 })).isFalse()
    }

    private fun xor(packet: ByteArray, start: Int = 0): Byte {
        var result = 0
        for (i in start until packet.lastIndex) result = result xor (packet[i].toInt() and 0xFF)
        return result.toByte()
    }
}
