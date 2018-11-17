/* Copyright (C) 2018 Erik Johansson <erik@ejohansson.se>
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.health.openscale;

import com.health.openscale.core.bluetooth.BluetoothGattUuid;

import org.junit.Test;

import java.util.UUID;

import static junit.framework.Assert.assertEquals;

public class BluetoothGattUuidTest {

    @Test
    public void prettyPrint() throws Exception {
        assertEquals("0x1800 \"GENERIC ACCESS\"",
                BluetoothGattUuid.prettyPrint(BluetoothGattUuid.SERVICE_GENERIC_ACCESS));
        assertEquals("0x2a2b \"CURRENT TIME\"",
                BluetoothGattUuid.prettyPrint(BluetoothGattUuid.CHARACTERISTIC_CURRENT_TIME));
        assertEquals("0x2902 \"CLIENT CHARACTERISTIC CONFIGURATION\"",
                BluetoothGattUuid.prettyPrint(BluetoothGattUuid.DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION));
        assertEquals("0x1801 \"GENERIC ATTRIBUTE\"",
                BluetoothGattUuid.prettyPrint(BluetoothGattUuid.fromShortCode(0x1801)));
        assertEquals("0x0001",
                BluetoothGattUuid.prettyPrint(BluetoothGattUuid.fromShortCode(0x1)));
        assertEquals("0x00010000",
                BluetoothGattUuid.prettyPrint(BluetoothGattUuid.fromShortCode(0x10000)));

        final UUID uuid = UUID.randomUUID();
        assertEquals(uuid.toString(), BluetoothGattUuid.prettyPrint(uuid));
    }
}
