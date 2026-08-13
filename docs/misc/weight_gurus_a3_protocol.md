# Weight Gurus 0376 (Transtek A3) protocol

Protocol reference for `WeightGurusA3Handler`. Everything here was confirmed against a Weight
Gurus 0376 running firmware 1.0 unless marked otherwise. Byte values are hex, multi-byte fields
are little-endian.

## Device identity

Advertises as `10376B` while waiting to pair and as `00376B<serial>` once paired. Device
Information (0x180A) reports manufacturer `GURUS`, model `380`, hardware and firmware `1.0`.
There is no battery service.

## GATT layout

Service `0d005750-c36b-11e3-9c1a-0800200c9a66`:

| Characteristic | Properties | Direction | Purpose |
|---|---|---|---|
| 0x8A20 | READ | | Feature |
| 0x8A22 | INDICATE | device to host | Body composition |
| 0x8A24 | INDICATE | device to host | Weight |
| 0x8A81 | WRITE | host to device | Commands |
| 0x8A82 | INDICATE | device to host | Events |

This firmware does not expose 0x8A25, though other devices in the family do.

## Opcodes

Device to host, on 0x8A82: `A0` password, `A1` random challenge, `83` slot status, `C0` profile
echo.

Host to device, on 0x8A81: `02` set time, `03` add user, `10` set weight unit, `20` verification
code, `21` account ID, `22` enable disconnect, `51` user profile.

There is no acknowledgement, delete, history-request or slot-selection opcode. The host cannot
tell the scale what it has received, cannot request history, and cannot choose which slot a
reading is attributed to.

## Commands

### 20 verification code

`[20][4 bytes]`, the byte-wise XOR of the 4-byte password and the 4-byte random. Sent in every
session, including the pairing session.

Observed: password `f180a0cb` XOR random `010040a1` gives `f080e06a`.

### 21 account ID

`[21][4 bytes]`. Any non-zero value works; the scale stores it as a broadcast ID. Sent once,
in response to the password frame, to complete pairing.

### 02 set time

`[02][int32]` where the value is `UTC_seconds - 0x4B3D3B00 + timezone_offset_seconds`.
`0x4B3D3B00` is 2010-01-01 00:00:00 UTC. The timezone offset is added, so the scale stores local
time. Sent at most once per session.

### 03 add user

`[03][slot][18-byte name]`, 20 bytes. The name is truncated to 18 characters and right-padded
with `20`. The slot number picks the destination and the write overwrites whatever is there, so
the eight slots behave as rewritable registers and an occupied slot can be taken over. No
delete-user opcode is needed.

### 51 user profile

| Offset | Value |
|---|---|
| 0 | `51` |
| 1 | `17`, field mask |
| 2 | slot (uint8) |
| 3 | gender (uint8) |
| 4 | age in years (uint8) |
| 5-6 | height (uint16) |
| 7 | weight unit (uint8) |

Gender is 1 male, 2 female, with 3 and 4 as the athlete variants. Weight unit is 0 kilograms,
1 pounds, 2 stones.

Height is an IEEE-11073 SFLOAT in metres, not centimetres: `(height_cm * 10) | 0xD000`. The `D`
nibble is exponent -3, so 170 cm is `1700 x 10^-3` and encodes as `D6A4`. The mantissa is a
signed 12-bit field, so the encoding tops out at 204.7 cm; above that the sign bit is set and the
height decodes negative. `WeightGurusA3Handler` clamps to 204 cm.

### 22 enable disconnect

`[22]`, one byte. The host writes it and then waits. The scale closes the link itself once it has
committed. Dropping the link from the host side interrupts that commit and the scale reports E1.

### 10 set weight unit

Not implemented. Its relationship to the weight unit byte in `51` is untested.

## Session flow

### Pairing

```
A0 password     -> write 21 account ID
A1 random       -> write 20 verification code
83 slots 1..8   -> collect, write nothing until the last slot has arrived
                   write 03 add user
                   write 51 profile
                   write 02 set time
                   write 22 enable disconnect
                   wait for the scale to close the link
```

The scale streams all eight slot frames. Writing back mid-stream, for example on the first
occupied slot, drops it into E1 instead of completing.

`WeightGurusA3Handler` prompts the user to choose a slot once the eighth frame arrives, and
writes nothing until they answer. The scale held the link open for 5.8 s during one such prompt
and completed normally. The upper bound is untested; if the scale does drop, the choice is
already persisted and reconnecting completes the registration.

### Established session

```
A1 random -> write 20 verification code
             write 02 set time
```

Measurement frames follow. `C0` may arrive unsolicited, reporting a slot's stored profile.

## Measurement frames

### Weight, 0x8A24

Example `3F 58 1B 00 FE 10 20 40 1F 00 00 00 00 88 13 00 FF 01 19`:

```
off 0     flags
off 1-4   weight, 32-bit FLOAT, kg       58 1B 00 FE -> 7000 x 10^-2 = 70.00
then, in flag order:
  0x01    timestamp (uint32)             10 20 40 1F
  0x02    weight delta (32-bit FLOAT)    00 00 00 00, ignored
  0x04    impedance (32-bit FLOAT)       88 13 00 FF -> 5000 x 10^-1 = 500.0 ohm
  0x08    user id (uint8)                01
  0x10    status (uint8)                 19, bit 4 means an append frame follows
```

Flag `0x20` was set but consumed no bytes, so it looks like a boolean flag, not a field.
Unexplained.

### Body composition, 0x8A22

Example `6D 10 20 40 1F 01 B7 F0 58 F2 90 F1 23 F0`:

```
off 0     flags                          6D
off 1-4   timestamp, matches the weight frame  10 20 40 1F
then, in flag order, each a 16-bit SFLOAT unless noted:
  0x01    user id (uint8)                01
  0x02    basal metabolism (uint16 kcal) not sent in any capture
  0x04    body fat %                     B7 F0 -> 18.3
  0x08    body water %                   58 F2 -> 60.0
  0x10    visceral fat level             not sent in any capture
  0x20    muscle mass %                  90 F1 -> 40.0
  0x40    bone mass %                    23 F0 -> 3.5, stored as kg via weight/100
  0x80    battery (uint8)                not sent in any capture
```

Decoded weight matched the scale's own display exactly on every test weighing.

## Slots and stored profiles

The scale has eight user slots. Each holds a name, height, age and gender.

Body composition is computed on the scale from the stored profile of the slot the reading is
attributed to, not from weight and impedance alone. Two readings of the same person seven minutes
apart, differing only in the selected slot:

| | slot 1 (30 yr, 160 cm) | slot 2 (40 yr, 170 cm) |
|---|---|---|
| weight | 70.2 kg | 70.0 kg |
| impedance | 505.0 ohm | 500.0 ohm |
| body fat | 24.6 % | 18.3 % |

Writing `51` to an occupied slot updates its stored profile. After registering slot 1 with age 40
and height 170 cm, the scale's `C0` echo returned `C0 17 01 01 28 A4 D6 01`, and body fat on that
slot moved from 24.6 % to 18.9 %. A stale profile is therefore correctable by registering
into the slot; openScale does not need to derive composition from impedance itself.

A profile with height 0 was not accepted: the echo continued to report the previous values.

Slot selection is a physical button on the scale. The host cannot influence which slot a reading
is attributed to. A scale woken by being stood on, without the button, asks the user to pick a
slot when more than one profile is configured.

## Sync and delivery

All of the following was observed with one client connected and one slot in use.

- Measurements are stored and forwarded, not streamed. A reading taken at 19:46:31 was delivered
  at 19:46:40 to a client that connected at 19:46:38.
- Unsynced readings accumulate and drain in one session, newest first. Five weigh-ins taken
  without syncing arrived together over 2.4 s.
- Queues are per slot. A sync drains only the queue of the slot currently selected on the scale.
  In one test, syncing under slot 2 delivered both pending slot 2 readings and left a pending
  slot 1 reading untouched, which was then delivered two minutes later when syncing under slot 1.
- A delivered reading is discarded by the scale and never sent again. Age-based expiry is ruled
  out: undelivered readings survived at least 13.6 minutes, while a reading delivered 7 minutes
  earlier was absent from the next drain.
- The scale stops advertising once it has synced.

Deduplication is therefore entirely the client's responsibility, and must match on exact
timestamp. Because per-slot queues drain independently and each drains newest first, readings
legitimately arrive older than ones already stored. A high-water-mark filter silently discards
them, and since the scale clears a delivered reading, anything dropped is lost. `publishIfNew`
guards only against a repeat within one session and leaves cross-session duplicates to the unique
`(userId, timestamp)` index on `Measurement`.

## Not tested

- Whether a second client can connect and sync at all, and what it receives.
- The upper bound on how long the scale will hold a connection open.
- Weight-frame flag `0x20`.
- Visceral fat and basal metabolism offsets, which no capture has exercised.
- Command `10`.
