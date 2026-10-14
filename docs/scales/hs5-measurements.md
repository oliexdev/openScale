# Experimental HS5 measurement collection

Open **openScale → Settings → HS5 Wi-Fi setup → Collect HS5 measurements over Wi-Fi**.
There is one launcher icon; both HS5 screens are accessed inside openScale.

1. Connect the phone to the same local Wi-Fi network as the scale.
2. Tap the scale awake; wait for `0.0` and a steady Wi-Fi symbol. Do not enter SET.
3. Choose the openScale user and tap **Find scale on Wi-Fi**. If the router blocks
   broadcast discovery, use **Find scale by IP address** with the address in the
   router's device list.
4. Select the scale and tap **Connect my scale profile**. Create a new scale
   profile from your openScale age, height and gender, or explicitly select your
   existing numbered scale user. Existing slots are never overwritten or deleted.
5. Weigh yourself barefoot and wait for the scale to finish. Tap **Sync stored
   measurements** while the scale is awake. Keep the screen open until saving
   completes. The selected profile and device are remembered for future syncs.
6. A successful save is reported only after the record is read back from
   openScale. Use **Open measurements** to view the normal charts and table.

## Implementation

- Java implementation of the local HS5 UDP protocol; no vendor SDK or native
  library is bundled or executed, and no cloud endpoint is contacted or changed.
- Socket bound to Android's Wi-Fi network, local port 8000, scale port 10000.
  A multicast lock is held during discovery and sessions.
- Discovery `B0 04 00 00 FF D0 CF`; identity reply `F0`; local challenge/response
  `FA/FB/FC/FD`; user management `31`; profile creation `51`; measurement `32`;
  live/stable/impedance/final/stop `35–39`; history `33/41/42`.
- Wi-Fi envelopes include six client/device identifier bytes before the checksum.
  The HS5 model constant and XXTEA handshake were determined from the public
  iHealth SDK 2.15.1's `A9InsSet`, `IdentifyIns`, `WifiCommProtocol`, `GenerateKap`
  and `libiHealth.so`. Other sources: `manager.h`, `Hs5DataUtil`, `UserListInHs5`.
- Only complete final or history records are saved. Temporary live/stable values
  are displayed, not recorded as completed measurements.
- Records contain packed local dates and weight, body fat, water, muscle mass,
  bone mass, visceral fat rating and daily calorie intake. Muscle arrives in kg
  and is converted to a percentage before using openScale's provider interface.
  Bone and visceral fat use the provider's generic value fields. DCI is retained
  in the private journal, not mislabeled as BMR.
- History accepts both the SDK's exact-length packet and the extra zero padding
  byte observed on this HS5. Incomplete records and other trailing data are rejected.
- Storage goes through the existing Room-backed content provider, including its
  unit conversions, then recalculates derived values. Timestamp/weight matches
  suppress repeated imports; conflicting measurements are not overwritten.
- Each received record is first flushed to the app-private `hs5-readings.jsonl`
  journal. A failed save stops history retrieval before asking for the next packet.
- Wi-Fi collection needs `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`
  and `CHANGE_WIFI_MULTICAST_STATE`. Android calls the first permission Internet
  even when the socket is used only on the LAN.

## Validation and current limits — 2026-10-14

All 16 HS5 JVM tests pass (6 Bluetooth setup tests and 10 Wi-Fi protocol tests).
The tests cover fixed command vectors, checksums, payload boundaries, packed
dates and metric units, malformed records, user identifiers and a cipher vector
independently obtained from the vendor's pure Java XXTEA implementation, plus
the observed padded history format and rejection of truncated history records.

The new Java sources compile against Android SDK 37 and existing openScale build
outputs. The validation environment blocks Gradle's file-lock network service,
so the hardware test APK was assembled incrementally with javac/D8 and AAPT2,
preserving the existing resource table, assets and native libraries. It installed
successfully on a Samsung SM-A566E running Android API 36 and loaded the existing
openScale user. A normal full Gradle build has not been verified in that
environment. With JDK 21 and Android SDK 37 available, run:

```sh
cd android_app
./gradlew :app:testDebugUnitTest --tests 'com.health.openscale.hs5.*' :app:assembleDebug
```

**Discovery, authentication, profile linking and history import are validated
on the owner's HS5.** After initially failing local address resolution, the
scale became reachable following a battery restart. Direct-IP discovery found
it, and the local handshake and user-list command succeeded without changing
cloud settings. An existing scale profile was linked to its owner's openScale
profile. Stored measurements were imported and read back from openScale; the
transfer then completed successfully. Original record dates are preserved.
A fresh reading matching the scale display was subsequently synced and verified
in openScale Overview with the current date and body composition fields. The
subsequent database backup passed SQLite's integrity check. Personal readings
and database backups are not part of this source repository.

**Live measurement remains experimental.** The scale accepted the online
session and sent interim packets, but did not deliver the final reading during
that session. The completed reading was retained on the scale and successfully
retrieved by **Sync stored measurements**. This is the verified collection path
and the first collection action in the screen. Creating a new scale profile is
still untested on hardware.

The original app database/preferences were backed up locally before installing
the update. No synthetic measurements were added to the user's database.
