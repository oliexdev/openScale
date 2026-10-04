# Beurer BF500 Bluetooth protocol

This documents the BF500 path implemented in openScale, including the existing
user/profile protocol and the stored-reading assembler. It describes what the
code sends and decodes; it is not a complete manufacturer specification.

Evidence comes from a vendor-app Bluetooth capture, openScale logs, and a physical
BF500 running firmware 1.13. Existing-user consent and offline weight transfer
were verified on that scale. New-user registration and profile writes are
implemented in the shared driver but were not exercised in this test. Packet
formats accepted by code should not be taken as proof that the BF500 emits every
optional field or accepts every command.

## Implementation references

- [StandardBeurerSanitasHandler](../../android_app/app/src/main/java/com/health/openscale/core/bluetooth/scales/StandardBeurerSanitasHandler.kt): BF500 detection, vendor characteristics, user list, activity level, and measurement request.
- [StandardWeightProfileHandler](../../android_app/app/src/main/java/com/health/openscale/core/bluetooth/scales/StandardWeightProfileHandler.kt): connection setup, clock, battery, user mapping, consent, registration, and profile writes.
- [BeurerBf500Lib](../../android_app/app/src/main/java/com/health/openscale/core/bluetooth/libs/BeurerBf500Lib.kt): weight/composition decoding and record assembly for live and stored readings.
- [StandardBeurerSanitasHandlerTest](../../android_app/app/src/test/java/com/health/openscale/core/bluetooth/scales/StandardBeurerSanitasHandlerTest.kt): sanitized packet replay and regression coverage.

## Discovery and GATT characteristics

The handler matches a case-insensitive device name containing `bf500` and connects
through BLE GATT. The observed advertisement includes the Weight Scale service
`181D`. The vendor app also examines advertisement data for users with pending
readings; openScale does not currently decode that pending-user information.

Short UUIDs below expand to `0000xxxx-0000-1000-8000-00805f9b34fb`.
Characteristic handles are connection/device details and must not be hard-coded.
Multi-byte integers in the implemented payloads are little-endian unless stated
otherwise. Writes use GATT write requests, with responses. The transport library
selects notification or indication subscriptions from characteristic properties.
The capture used indications for weight, composition, and the User Control Point,
and notifications for the vendor user list.

| Service | Characteristic | Driver use |
| --- | --- | --- |
| `180A` Device Information | `2A29` Manufacturer Name | Read and log UTF-8 manufacturer |
| `180A` Device Information | `2A24` Model Number | Read and log UTF-8 model |
| `1805` Current Time | `2A2B` Current Time | Write phone's local time |
| `180F` Battery | `2A19` Battery Level | Read percent; attempt subscription |
| `181D` Weight Scale | `2A9D` Weight Measurement | Subscribe and decode weight records |
| `181B` Body Composition | `2A9C` Body Composition Measurement | Subscribe and attach composition |
| `181C` User Data | `2A9F` User Control Point | Subscribe; consent and registration commands |
| `181C` User Data | `2A99` Database Change Increment | Subscribe and log changes; write during new-user setup |
| `181C` User Data | `2A85` Date of Birth | Write during new-user setup |
| `181C` User Data | `2A8C` Gender | Write during new-user setup |
| `181C` User Data | `2A8E` Height | Write during new-user setup |
| `FFFF` Beurer vendor service | `FFF1` User List | Subscribe, write `00`, decode profile records |
| `FFFF` Beurer vendor service | `FFF2` Activity Level | Write during new-user setup |
| `FFFF` Beurer vendor service | `FFF4` Measurement Request | Write `00` after successful consent |

The BF500 profile does not configure vendor initials or target-weight writes.
The driver does not query weight/body-composition feature characteristics to
negotiate resolutions; the decoding factors below are those currently implemented.

## Connection and clock/battery setup

On connection the driver resets its BF500 measurement buffer, then queues:

1. Subscriptions to weight, composition, database-change increment, User Control
   Point, and battery.
2. A Current Time write.
3. Manufacturer, model, and battery reads.
4. Consent using a saved user-slot mapping and code, if available. A mapped slot
   without a saved code prompts for consent instead.
5. If no mapping is available, a User Control Point `04` request, which the shared
   handler calls `LIST_ALL_USERS`.

If the chosen slot has no saved consent, the BF500 handler additionally subscribes
to `FFFF/FFF1` and writes `00` to request the vendor user list. This vendor list
is the existing-user discovery path verified on the BF500. The shared `04`
request has fallback handling for failure/unsupported responses; a working BF500
user list must not depend on that request succeeding.

Current Time is ten bytes:

```text
year:u16 month:u8 day:u8 hour:u8 minute:u8 second:u8
weekday:u8 fractions256:u8 adjustReason:u8
```

The driver uses local phone time, weekday `1` for Monday through `7` for Sunday,
and zero for fractions and adjustment reason. Measurement timestamps likewise
have no timezone field and are interpreted in the phone's local timezone.

Battery is one unsigned percentage byte. The driver warns at `0..10%`. The tested
BF500 allowed battery reads but lacked a subscription descriptor for `2A19`;
the attempted subscription failed while subsequent setup continued successfully.

## Vendor user list

Request the list by subscribing to `FFF1`, then writing a single `00` byte.
The implemented parser treats the first byte as a status:

| Status | Meaning and behavior |
| --- | --- |
| `00` | Profile record; decode the remaining fields |
| `01` | End of list; offer existing slots and a create-user option |
| `02` | No users; clear the current app user's stale mapping/code and offer creation |

Only `00` records followed by `01` were verified in the capture. The parser
actually treats any first byte other than `01` or `02` as a profile record.
Its profile layout is:

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 1 | Record status |
| 1 | 1 | Scale user slot, displayed as `Pxx` |
| 2 | 3 | Initials; `FF FF FF` is displayed as `unknown` |
| 5 | 2 | Birth year |
| 7 | 1 | Birth month |
| 8 | 1 | Birth day |
| 9 | 1 | Height in centimetres |
| 10 | 1 | Gender; `00` male, other values decoded as female |
| 11 | 1 | Activity level, converted to openScale's enum using `value - 1` |

Selecting an existing slot saves its mapping to the current openScale user.
Other slots mapped to that same app user are cleared locally. This selection does
not import measurements from the Beurer app's database and does not create a
second user on the scale.

## Consent, registration, and control-point responses

All commands below are written to `181C/2A9F`.

| Operation | Payload |
| --- | --- |
| Register a new user | `01 consent:u16` |
| Consent to an existing slot | `02 slot:u8 consent:u16` |
| Shared handler's list-users request | `04` |
| Response from scale | `20 requestedOpcode:u8 result:u8 [responseData]` |

The driver handles result `01` as success, `04` as operation failed, and `05`
as user not authorized for consent. A successful registration response includes
one additional byte containing the assigned scale slot: `20 01 01 slot`.
A successful consent response is `20 02 01`.

Consent is an integer encoded little-endian, not the text of its decimal digits.
For example, the **synthetic** code `1234` for slot `1` produces:

```text
02 01 D2 04
```

The existing-user flow persists the mapping and code, then sends consent. Later
connections reuse them automatically. An authorization failure clears the saved
code and prompts again. Consent alone does not overwrite the existing profile.
The UI asks for a code displayed by the scale, but our existing-user flow did not
show such a code on the BF500. The vendor-app capture supplied the consent value:
identify a `02 slot lowByte highByte` write and verify the matching `20 02 01`
response. Actual consent values and raw phone captures are not included here.

For new-user registration, the driver generates a code in `0..9999`, sends the
registration command, saves the returned slot and code, and sends consent. After
successful consent it prompts for a reference weigh-in and writes the new
profile. Registration failure `04` is presented as full user slots by the shared
handler; this interpretation was not verified on the tested BF500.

After any successful consent, `onRequestMeasurement()` writes `00` to
`FFFF/FFF4`. This is the existing measurement-request behavior. Stored readings
in our captures arrived **before** the consent-success response and therefore
before this write; `FFF4/00` should not be described as a verified history-fetch
command.

## Profile writes already implemented

These writes occur after consent for a newly registered user. They are inherited
from the shared driver and BF500 vendor profile; they were not physically tested
in this session.

| Characteristic | Driver payload |
| --- | --- |
| `181C/2A85` | Birth `year:u16 month:u8 day:u8` |
| `181C/2A8C` | One gender byte, `00` male or `01` female |
| `181C/2A8E` | Integer height in centimetres as `u16`, truncated and clamped to `0..300` |
| `181C/2A99` | `01 00 00 00`; the driver writes a fixed increment value of `1` |
| `FFFF/FFF2` | One activity byte, `(openScale activity + 1)` clamped to `1..5` |

These are the payloads produced by the implementation, not claims that every
encoding has been independently verified against the manufacturer protocol.
Database-change indications are currently logged without parsing their value.

## Weight measurement (`2A9D`)

The decoder reads `flags:u8 weight:u16`, then optional fields in the order below.

| Mask | Meaning when set | Bytes consumed |
| --- | --- | --- |
| `01` | Imperial weight: `raw * 0.01` lb, converted to kg; otherwise `raw * 0.005` kg | Units flag only |
| `02` | Timestamp present | `year:u16 month:u8 day:u8 hour:u8 minute:u8 second:u8` |
| `04` | User slot present | `slot:u8` |
| `08` | BMI and height present | `bmi:u16 height:u16`; consumed but not published |

Captured stored packets had flags `0E`: kilograms, timestamp, user, and BMI/height.
A reported slot is translated through the persisted mapping to an app user.
A slot of `FF`, or an absent slot, remains unspecified; an explicit unmapped slot
is not retained as a pending record. Missing timestamps remain unset rather than
being fabricated by the decoder. Invalid dates, truncated packets, and weights
that cannot be recorded as positive finite values are rejected.

## Body composition (`2A9C`)

The decoder reads `flags:u16 bodyFat:u16`, where fat is `raw * 0.1%`.
The remaining fields occur in this order, only when their corresponding bits are
set. Mass fields use `raw * 0.005` kg with bit `0001` clear, or `raw * 0.01` lb
converted to kg with it set.

| Mask | Field | Decoder treatment |
| --- | --- | --- |
| `0001` | Imperial mass units | Changes mass conversion; no additional field |
| `0002` | Timestamp, 7 bytes | Same layout and local-time interpretation as weight |
| `0004` | User slot, 1 byte | Resolve through the saved user mapping |
| `0008` | Basal metabolism, `u16` | Consume, not published |
| `0010` | Muscle percentage, `u16` | Publish `raw * 0.1%` |
| `0020` | Muscle mass, `u16` | Consume, not published |
| `0040` | Fat-free mass, `u16` | Consume, not published |
| `0080` | Soft lean mass, `u16` | Keep positive mass for derived lean/bone values |
| `0100` | Water mass, `u16` | Convert to percent of the paired weight |
| `0200` | Impedance, `u16` | Publish `raw * 0.1` ohms |
| `0400` | Weight, `u16` | Decode mass; the paired weight's value takes precedence |
| `0800` | Height, `u16` | Consume, not published |
| `1000` | Multiple-packet composition | Reject; fragment assembly is not implemented |

Captured packets had flags `0398`: basal metabolism, muscle percentage, soft lean
mass, water mass, and impedance. They lacked timestamps and user slots and thus
belonged to the preceding weight record. Optional encodings outside this observed
layout are implemented decoding paths, not physically verified BF500 behavior.

For a paired weight `w` in kilograms, the assembler computes:

```text
waterPercent = 100 * waterMassKg / w
leanBodyMassKg = w * (1 - bodyFatPercent / 100)
boneMassKg = leanBodyMassKg - softLeanMassKg
```

Lean/bone derivation requires both positive body fat and positive soft lean mass.
The measurement container omits nonpositive/nonfinite values; zero body-analysis
fields therefore do not create zero metrics or fabricated lean/bone mass.

## Live and stored record assembly

Both paths use the same indications and assembler:

1. A valid weight packet starts a pending record. If another record is already
   pending, publish that earlier weight-only record first.
2. Composition requires a pending weight. If both packets specify a user or a
   timestamp, conflicting metadata prevents the merge. Unmapped composition users
   are rejected.
3. A matching composition packet fills missing fields, inherits the weight's
   timestamp/user where needed, performs the conversions above, and publishes the
   completed record.
4. On disconnect, publish the remaining weight-only record and clear the buffer.
   On connection, reset the buffer.

Composition without a preceding weight is ignored, even if it includes its own
weight field. Rejected composition does not complete or clear the pending weight.
There is no BF500-specific stored-history command, end-of-history marker, or
application-level deletion command implemented in this path.

## Stored-reading capture evidence

A vendor-app capture from firmware 1.13 showed:

1. Subscribe to weight and composition indications.
2. Submit consent for an existing scale user.
3. Receive a weight timestamped 32 minutes before the connection.
4. Receive composition without a timestamp or user slot.
5. Receive the successful consent response.

Stored readings therefore arrived automatically during consent. Subscribe before
sending consent and accept measurements before its response arrives. No separate
history-read command was observed. The physical test reproduced this ordering
for two consecutive offline readings.

## Validation and limits

All 11 handler tests and 6 scale-catalog tests passed, and the debug APK built.
Packet replay covers timestamps, consecutive records, user mapping, truncated
packets, missing body analysis, and nonzero composition from the earlier capture.

On the physical BF500 running firmware 1.13, two readings taken with phone
Bluetooth disabled were imported using an existing profile. Both retained their
original timestamps, about nine and eleven minutes before sync. Both weight and
composition records arrived before the consent-success response. Composition had
zero fat, muscle, soft lean mass, water, and impedance, so only weight was
published. Nonzero composition still needs a physical test with this debug app.

This retrieves readings still available on the scale. After openScale synced the
two readings, the vendor app decoded the scale advertisement as having no pending
measurements for the same user slot and skipped connecting. Both apps can retain
the same profile, but cannot be expected to independently import the same offline
queue. The exact device-side clearing mechanism and maximum retained history
have not been established. History already in the Beurer app or cloud is outside
this Bluetooth driver.

New-user/profile writes, imperial packets, and unobserved optional measurement
fields remain unverified on the physical BF500. Fragmented composition is not
supported. Other vendor characteristics and commands outside the implemented
BF500 path are not documented here.
