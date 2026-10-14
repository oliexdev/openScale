# Experimental iHealth HS5 Wi-Fi setup

This change adds **Settings → HS5 Wi-Fi setup** within the normal openScale app.
This screen provisions Wi-Fi. The separate
[HS5 measurements screen](hs5-measurements.md) implements experimental local
measurement collection; see that document for its hardware-validation status.

Pair the HS5 in Android Bluetooth settings, close iHealth MyVitals, wake the
scale in SET mode, and select it on the setup screen. Check the Bluetooth
connection, then enter the visible 2.4 GHz SSID, password, and security mode.
Credentials are kept in memory, never saved in preferences or logs, and the
password field is cleared after a provisioning attempt. The measurement connector
now requires Android's Internet permission for local Wi-Fi UDP; the provisioning
screen still uses Bluetooth only. Status 2 from the scale is required to report Wi-Fi success; a socket
disconnect is not treated as success.

## Protocol evidence

Sources inspected:

- Public iHealth Android SDK example:
  <https://github.com/MyVitals/iHealth-android-sdk-example>,
  `app/libs/iHealthSDK_2.15.1.jar`:
  `Hs5ControlForBt`, `Hs5InsSet`, `BtCommProtocol`, and
  `iHealthDevicesManager$g`.
- Older SDK 2.2.3 preserved at
  <https://github.com/chichengjunma/iHealthDeviceLabs-Android>:
  `lib/iHealthLibrary_2.2.3.jar`, `Hs5ControlForBt`, the obfuscated instruction
  class `com.ihealth.communication.ins.v`, and `BtCommProtocol`.
  `examples/DeviceLibTest/src/com/ihealth/devices/HS5BT.java` provides the
  security-mode mapping.
- The test phone reports the paired scale as `iHealth HS517AF1`, transport
  BR/EDR, with an SPP service. The SDK connects using the standard SPP UUID
  `00001101-0000-1000-8000-00805f9b34fb`.

The implementation is independent Java code. It does not bundle or execute the
iHealth SDK or call its authentication/cloud services.

## Credential record

Both SDK versions send exactly 66 bytes directly over RFCOMM, without the
normal command envelope:

| Offset | Size | Contents |
|---|---:|---|
| 0 | 1 | Zero |
| 1 | 1 | Security: 0=open, 1=WEP, 2=WPA, 3=WPA2, 4=mixed WPA/WPA2 |
| 2 | 32 | SSID, zero padded |
| 34 | 32 | Password, zero padded |

Android's default string encoding used by the SDK is UTF-8. Field limits are
byte counts. Oversized inputs and embedded NULs are rejected rather than
truncated. The vendor recommends lowercase alphanumeric credentials for this
legacy model; modern WPA3 networks are not supported.

## Commands and replies

Host command: `B0 04 00 SEQUENCE A9 COMMAND CHECKSUM`.
Checksum is the low byte of the sum starting at offset 2 and ending before the
checksum. A sent command advances the sequence by two modulo 256. A normal
device reply sets the next transmit sequence to its sequence plus one; six-byte
ACK frames do not change it.

- `F1`: request identity. New SDK initialization uses this; the old SDK does not.
- `F0`: device identity response; send command `F0` in reply.
- `E9`: poll Wi-Fi state, starting 500 ms after the credential record and then
  once per second, as in the SDK.
- `E0`: Wi-Fi status reply. First payload byte is 1=connecting, 2=success,
  3=failure; other values are reported as unknown.

Device frames start with `A0`, have a length byte equal to total length minus
three, and use the same checksum rule. For ordinary replies, device type is at
offset 4, command at offset 5, and payload starts at offset 6. The decoder handles
fragmented and concatenated reads and rejects bad checksums.

The setup screen first allows 2.5 seconds for the identity exchange. If no
identity arrives, it follows the old SDK's direct-credential sequence. A
connection check waits six seconds and sends no credentials. Provisioning is
bounded to 60 seconds; each RFCOMM connect is bounded to 15 seconds. As in the
SDK, a failed secure SPP connection is followed by one attempt using
`createInsecureRfcommSocketToServiceRecord` on the already paired device.
Cancel closes the socket and interrupts the worker. Already-sent credentials
cannot be undone.

## Validation

`Hs5ProtocolTest` covers fixed-width fields, byte limits, spaces, embedded NULs,
known command/checksum vectors, fragmentation, concatenation, malformed packets,
and an identity/credential/success exchange with a simulated scale.

Run:

```sh
cd android_app
./gradlew :app:testDebugUnitTest --tests com.health.openscale.hs5.Hs5ProtocolTest :app:assembleDebug
```

On 2026-10-14, the debug APK was installed on a Samsung SM-A566E running Android
API 36. The app found the bonded HS5 and opened a secure SPP connection while
the scale displayed SET. The connection check received no identity reply within
six seconds. Provisioning then succeeded through the original setup sequence:

```text
17:51:01  Wi-Fi settings sent
17:51:01  Scale Wi-Fi status: 1 (connecting)
17:51:18  Scale Wi-Fi status: 2 (connected)
```

The phone's scan independently confirmed the target network was WPA2-PSK on
2437 MHz (2.4 GHz). Its SSID contained a space. An earlier attempt returned
status 3 (failed); the successful attempt followed correction of the form
contents. No credentials are included in this document or the application logs.

The owner then confirmed that SET disappeared and normal weighing works.
This validates credential delivery, Wi-Fi success reporting, and exit from SET
on this HS5. Cloud behavior has not been validated. Measurement collection uses
the separate [local Wi-Fi connector](hs5-measurements.md).
All six protocol tests passed and the debug APK built successfully. These
results do not establish compatibility with every HS5 firmware revision.
