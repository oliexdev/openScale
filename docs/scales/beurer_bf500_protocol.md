# Beurer BF500 stored measurements

The BF500 uses the standard Weight Scale (181D/2A9D), Body Composition
(181B/2A9C), and User Data (181C/2A9F) services alongside the Beurer FFFF service.

## Capture evidence

A vendor-app Bluetooth capture from firmware 1.13 showed this sequence:

1. Subscribe to weight and body-composition indications.
2. Submit consent for an existing scale user through the User Control Point.
3. Receive a weight indication with a timestamp 32 minutes before the connection.
4. Receive body composition without a timestamp or user index.
5. Receive the successful consent response.

The stored reading therefore arrived automatically during consent. There was no
separate history-read command in this exchange. Subscribe before sending consent;
do not wait for its response before accepting measurement indications.

The captured weight flags were 0x0e (kg, timestamp, user index, BMI/height).
Composition flags were 0x0398 (basal metabolism, muscle percentage, soft lean
mass, water mass, impedance). The composition belongs to the preceding weight
and inherits its timestamp and user mapping. Each subsequent weight starts a new
record, even when the previous reading has no composition.

The driver reuses the existing consent/profile flow. It does not create another
scale user or overwrite an existing profile on consent. Consent codes and raw
phone captures are excluded from the repository; tests use sanitized packets.

## Validation

Packet replay tests cover timestamp preservation, consecutive records, user
mapping, incomplete packets, and missing body-analysis values. All 11 handler
tests and 6 scale-catalog tests passed, and the debug APK built successfully.

On a physical BF500 running firmware 1.13, two readings taken with phone Bluetooth
disabled were imported on connection to the debug app using an existing scale
profile. Both retained their original timestamps, about nine and eleven minutes
before the sync. Weight and composition indications for both records arrived
before the successful consent response. Composition contained zero fat, muscle,
soft-lean mass, water, and impedance, so only weight was published. Nonzero body
composition is verified by replay of the earlier vendor capture, but still needs
a physical test with this debug app.

This retrieves readings still available on the scale. It is not an import from
the Beurer application's database or cloud. After the physical openScale sync,
the vendor app decoded the BF500 advertisement
as having no pending measurements for the same user slot and skipped connecting.
The two readings were therefore no longer offered to the vendor app. Both apps
can retain the same profile, but should not expect to import the same offline
queue independently. The maximum amount of retained history has not been tested.
Fragmented body-composition packets have not been observed and are
rejected rather than published partially.
