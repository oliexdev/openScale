# S400 calculation origin: resolved via dynamic instrumentation (Frida)

## Scope

This replaces speculation with direct evidence. The question was: where does
the S400 compute BFP/SMM/BMC/water/visceral fat — Xiaomi's server, the
Xiaomi Home app (Java or React Native), or the scale's own firmware? Answered
by instrumenting the real `com.xiaomi.smarthome` process (v11.9.521,
versionCode 110915211) on a Pixel 7 with Frida 17.23.3, during real physical
weigh-ins on the user's own S400 unit. No Xiaomi account credentials, request
headers, or device identifiers beyond the scale's own MAC are reproduced here.

## Method: four vectors tested, three eliminated, one confirmed

### 1. Server (`/eco/scale/bcalc`) — eliminated

Hooked all 13 methods of `com.xiaomi.smarthome.network.SmartHomeRequestManager`
(the app's generic HTTP entry point) plus the `NetRequest` constructor, across
three independent physical weigh-ins. Result: **zero calls carrying a `bcalc`
path.** The only network requests observed during a weigh-in are:

```
POST /eco/scale/account/list
POST /eco/common/scale/add              (persist the already-computed result)
POST /eco/scale/account/updateWeight
POST /eco/common/scale/getUserDataByPage  (×8-9, history pagination)
```

These are read/write-history calls, not calculation calls. Confirmed stable
across repeated weigh-ins with no process restart.

### 2. App-side native calculation (`HmPaceScalesModule.computeBodyParam`) — eliminated

Found a strong-looking candidate: a native Java method literally named
"compute body parameter", signature
`computeBodyParam(double, double, int, int, int, Callback)`, exposed as a
React Native native module. Hooked with full argument/return logging across
two independent weigh-ins (verified twice, including re-checking with a
line-wrap-safe joined-blob grep after an initial scan looked clean). Result:
**zero invocations during either weigh-in.** This method exists and is
reachable, but is not part of the S400 BLE weigh-in flow — likely used by a
different, manual-entry flow elsewhere in the app.

### 3. React Native JS-side calculation — eliminated as the origin (it only displays)

Static analysis of the decompiled RN bundle
(`s400_rea_audit/modules/10466.js`, `handleSpecData`, event `'event.8.2'`)
confirms the JS layer receives a **plain CSV string with all 32 fields
already computed** and does nothing but `split(',')` it into a JS object for
display. No arithmetic happens here. See field mapping below.

### 4. Native BLE channel manager (`SpecChannelManager`) — CONFIRMED source of the pre-computed payload

`com.xiaomi.smarthome.core.server.internal.bluetooth.channel.SpecChannelManager`
is the class that receives the raw bytes from the GATT notification and
reassembles/forwards them before they ever reach React Native. Hooked its
four `byte[]`-handling methods (`OooOoO0`, `OooOoo0`, `OooOo00`, `OooOOoo`) —
obfuscated names, confirmed live via `toGenericString()` reflection — during a
full physical weigh-in. One capture (of 68 events) produced the complete
plaintext CSV, **already carrying every computed body-composition value**:

```
6873713555,1,1,873,78,0,1792037139,230,634,567,37,9,151,363,252,742,-41,-131,-90,11,1822,4,33,79,727,42,201,671,494,132,4969,4514
```

### Field mapping (confirmed against the RN bundle's own parsing order)

| # | field    | raw value  | interpreted      |
|---|----------|------------|------------------|
| 0 | miid     | 6873713555 | —                |
| 1 | duid     | 1          | —                |
| 2 | userType | 1          | —                |
| 3 | weight   | 873        | 87.3 kg          |
| 4 | heartRate| 78         | 78 bpm           |
| 5 | status   | 0          | —                |
| 6 | time     | 1792037139 | unix epoch       |
| 7 | **bfp**  | 230        | **23.0 %**       |
| 8 | slm      | 634        | 63.4 kg          |
| 9 | bwp      | 567        | 56.7 %           |
| 10| **bmc**  | 37         | **3.7 kg**       |
| 11| vfl      | 9          | visceral level 9 |
| 12| pp       | 151        | —                |
| 13| **smm**  | 363        | **36.3 kg**      |
| 14| **bmi**  | 252        | **25.2**         |
| 15| swt      | 742        | —                |
| 16| mc       | -41        | —                |
| 17| wc       | -131       | —                |
| 18| fc       | -90        | —                |
| 19| whr      | 11         | —                |
| 20| bmr      | 1822       | 1822 kcal        |
| 21| bt       | 4          | —                |
| 22| ma       | 33         | metabolic age 33 |
| 23| sbc      | 79         | —                |
| 24| slp      | 727        | —                |
| 25| bmcp     | 42         | bone mass %      |
| 26| bfm      | 201        | fat mass (×0.1kg)|
| 27| ffm      | 671        | fat-free mass    |
| 28| bwm      | 494        | body water mass  |
| 29| pm       | 132        | protein mass     |
| 30| bodyRes  | 4969       | raw impedance    |
| 31| bodyRes2 | 4514       | raw impedance    |

Column order and names are taken directly from the app's own `data_arr`
destructuring in `10466.js` lines 728-762 — this is not a guess, it is the
app's own documented parsing order, now confirmed against a real live payload.

## Conclusion

**The S400's firmware computes every body-composition metric before
transmission.** The phone app — server, Java, and JS alike — is a pure
pass-through-and-persist pipeline: receive the CSV over BLE, reformat it,
show it, upload it for history sync. There is no vendor formula in any layer
we have access to; it lives inside the scale's own firmware, which we have
not dumped or analyzed.

## Implication for openScale parity (changes the project's whole approach)

Prior work in this repo (`docs/s400_component_empirical_audit.md`,
`docs/s400_v235_body_fat_recovery.md`, `S400BodyFatV235`,
`S400BodyFatV235Experimental`, the BMC/SMM/water regression attempts) was all
aimed at **reverse-engineering Xiaomi's formula from input/output pairs**,
because at the time we did not know the values were pre-computed firmware
output rather than a server- or app-side calculation.

That entire effort is now understood to be the wrong layer: regression
against `/eco/scale/bcalc` responses was approximating a black box that
itself does no computation — it was just relaying firmware output, so the
regressions were curve-fitting noise in the firmware's own (non-formulaic,
possibly per-unit-calibrated or lookup-table-based) output, not recovering a
reproducible formula. This explains the BMC/SMM parity failures documented in
`docs/s400_component_empirical_audit.md` (prospective validation dropping to
5/16 and 12/16): **there was never a formula to fit.**

**The correct approach is retrieving the already-computed values directly
from the scale over BLE, not re-deriving them.** This means:

1. `MiScaleS400Handler`'s current broadcast-mode decryptor (`S400Decryptor`)
   only extracts weight/impedance/heart-rate from the *advertisement* packets
   (24-26 byte AES-CCM encrypted frames) — it does not use the GATT Spec
   channel at all. It still needs `S400BodyComposition`/`S400BodyFatV235` to
   estimate BFP/SMM/BMC from impedance, which is exactly the unreliable path
   this finding makes obsolete.
2. The real body-composition values are available by **connecting via GATT**
   (not broadcast-only) to the Xiaomi MIoT BLE service already identified in
   `DebugGattHandler.kt` (`00008000-...-6f696d2e696d`, notify characteristic
   `0x8002`), authenticating with the same bind key already used for
   broadcast decryption, and parsing the resulting notification(s) as the
   32-field CSV (or its binary equivalent) documented above — not computing
   anything client-side.
3. Next concrete step: capture the **raw bytes** on characteristic `0x8002`
   (not just the app's already-decoded Java object) during a real weigh-in,
   confirm whether the wire format is this same CSV ASCII string or a binary
   encoding of it, and write an openScale handler that connects via GATT,
   subscribes to `0x8002`, and parses the 32 fields directly — retiring
   `S400BodyComposition`'s impedance-based estimation and the two
   `S400BodyFatV235*` regression helpers for any field this channel actually
   delivers.
4. BLE bind-key/auth requirements for the GATT path still need confirmation
   (broadcast mode and GATT connect may have different auth flows on this
   scale) — this is the next unresolved question, not assumed solved.

## Evidence artifacts (on `v0id`, not committed — contain live device/session state)

- `/tmp/s400-offline-test/hook_spec_channel_bytes.js` — the winning Frida hook.
- `/tmp/s400-offline-test/spec_channel_capture.txt` — raw capture with the CSV above (event #55 of 68).
- `/tmp/s400-offline-test/inspect_spec_channel_manager.js` — reflection dump of `SpecChannelManager`'s method/field signatures used to find the byte[] handlers.
- `/tmp/s400-offline-test/hook_compute_body_param.js`, `verify_all_call_paths.js`, `verify_manager_class.js` — the three eliminated-vector hooks, kept for reproducibility.

No Xiaomi account tokens, request signatures, or other credentials appear in
any of the above; MAC `E8:4A:54:59:FF:E9` is the test scale's own hardware
address, already known to the app pairing it.
