# S400 component audit: raw oracle data, offline exploration

## Scope

No inherited coefficients or prior model performance claims are used as evidence.
Reloaded raw numeric oracle responses, deduplicated by the complete parameter object:
556 loaded rows collapse to **329 unique inputs** (164 female, 165 male), with no
conflicting values in duplicate BFP/BMC/SMM/BWP/VFL/SLM/FFM responses.
No new Xiaomi requests or device writes were issued. Production formulas are unchanged.

Numeric corpus SHA256:
`c634a88db48d347527f5a7020ce6e2cef25adac2b696e44f9d6943dd254b3ae6`.

## Method

Exploratory five-fold cross-validation groups inputs by sex, height, weight and
both impedance readings. All age variants of the same physical inputs remain in
the same fold. Data were already inspected; this is **not fresh independent
validation**. Polynomial and rational regressions are hypotheses, not recovered
vendor source. No per-case offsets or lookups are used.

Water is fitted as latent mass from recorded BWP * weight / 100 and transformed
back to percentage before evaluation. VFL is quantized to integers with an
observed lower bound of 1. No upper bound is inferred. All reported exact counts
compare rounded predictions; tolerances are not called exact matches.

## Water and visceral fat: initial results

| Metric | Sex | Candidate | Exact | MAE | Maximum error |
|---|---|---|---|---|---|
| BWP | Female | Rational, 15 terms | 5/164 | 1.087 pp | 22.6 pp |
| BWP | Male | Rational, 15 terms | 60/165 | 0.864 pp | 19.0 pp |
| VFL | Female | Rational, 15 terms | 112/164 | 0.372 levels | 4 levels |
| VFL | Male | Rational, 15 terms | 98/165 | 0.630 levels | 12 levels |

These candidates **fail parity**. Quadratic/cubic candidates were also evaluated
and did not solve parity. Increasing basis size is not evidence of the correct
algorithm. They must not be installed in OpenScale as Xiaomi-equivalent formulas.

Controlled second-impedance sweeps change BMC, SMM, BWP and VFL while other inputs
are fixed. Both bands matter for these metrics. Observed water percentages range
from 29.6 to 85.0%; a universal 35..75% output clamp contradicts these data.
Bone and VFL do not change in the eight repeated-age input groups observed;
this supports testing age-independent hypotheses, not a universal proof.
Some SMM/water changes coincide with age-dependent SLM changes at a muscle bound.

## Bone and skeletal muscle: grouped cross-validation

The same five grouped folds were used to fit fresh coefficients; no inherited model
was loaded. Bone output is rounded to tenths with a nonnegative floor. For SMM,
training excludes cases close to the observed ratio bounds, and evaluation clamps
the latent prediction to `0.45 * SLM .. 0.70 * SLM` using **recorded oracle SLM**.
The interior is rounded to tenths; exact bound products are not rounded again.
This SMM result is therefore **conditional**, not end-to-end parity from raw inputs.

| Metric | Sex | Candidate | Exact | MAE kg | Maximum error kg |
|---|---|---|---|---|---|
| BMC | Female | Rational, 15 terms | 147/164 | 0.01463 | 0.6 |
| BMC | Male | Rational, 15 terms | 150/165 | 0.00909 | 0.1 |
| SMM (oracle SLM supplied) | Female | Rational, 15 terms | 128/164 | 0.02537 | 0.3 |
| SMM (oracle SLM supplied) | Male | Rational, 15 terms | 139/165 | 0.01645 | 0.2 |

In the SMM interior alone, exact matches are 88/123 (female) and 100/126 (male).
Thus the total exact count benefits from already-known bound values; it does not
mean the latent muscle formula has been fully recovered. All these candidates
still fail exact parity and were **not integrated into OpenScale**.

The largest BMC error is a female fixed-profile R0=300 ohm, R1=525 ohm case:
prediction 1.3 kg vs observed 0.7 kg. A more flexible model fitting this point
alone would not establish correctness elsewhere. Age variants must stay grouped.

Observed `SMM/SLM` ratios are within 0.45..0.70: 37 unique inputs are exactly at
the lower ratio and 43 at the upper ratio. The remaining 249 are interior.
`SLM = round1(displayed FFM - displayed BMC)` matches **329/329** inputs;
`SMM = SLM` matches **0/329**. Preserve this distinction in OpenScale's muscle field.

## Subsequent BMC square-root hypothesis: independently checked

A delegated investigation proposed an additive model:

```
raw = c0 + cH * height_cm^2 + cW * weight_kg
      + g0 * (sqrt(R0_ohm) - m0)^2
      + g1 * (sqrt(R1_ohm) - m1)^2
BMC = round1(max(0, raw))
```

The parent independently evaluated the delivered fixed coefficients against all
329 numeric input vectors: **316/329 exact**, MAE 0.003951 kg, maximum displayed
error 0.1 kg. There are 13 mismatches; this is an in-sample result, not fresh
validation. Centres m0/m1 are fitted parameters, not recovered hardware constants.

A fresh seven-term representation `[1,h^2,w,R0,sqrt(R0),R1,sqrt(R1)]` was then
fit using quantized intervals. Collapse both age and sex replicates when they
share height/weight/impedances: there are **209 physical input groups**. All 12
exact sex-paired cases have equal BMC, as do all eight repeated-age groups. These
observations justify testing a shared model, not asserting universal independence.

For an observed zero, latent raw values below zero may map to the nonnegative
output floor; its interval is one-sided, with upper bound 0.05, not `[0,0.05)`
for the pre-floor raw value. The interval solver did **not** converge after
12000 passes. It matched 191/209 groups on training. Exploratory five-fold grouped
CV matched **188/209**, MAE 0.010526 kg, max displayed error 0.2 kg. Nonconvergence
alone is not proof that the basis is mathematically infeasible.

The child's proposed "quantization ceiling" near 92% is rejected: quantized
outputs can in principle be predicted exactly by the correct formula. A small
residual is not proof of rounding artifacts. The child's centres were selected
using the full corpus before its local CV search, so that CV does not fully isolate
model-selection leakage. Parent grouped folds do not split identical physical
inputs by sex or age; nevertheless all this data was previously inspected.

The square-root form is a promising simpler hypothesis, **not production parity**.
Scripts and coefficients are preserved under `tools/s400_oracle_audit/bmc_sqrt_*`.
No BMC implementation, app installation, new Xiaomi request or per-case correction
was applied in this investigation.

## Fresh prospective BMC validation: batch15

After the prior 320-query ledger was exhausted, an earlier explicit user approval
for 40–80 further synthetic calculation-only requests was located in the session
history. The ledger was raised to a conservative **360 total**, not 400. Sixteen
new, unseen physical-input variants and five model predictions were frozen before
any request (`batch14_frozen_predictions.json`, SHA256
`e22f3f43c38161e58f9213b6d9fa85fcbb8549489bc12d462208434751d2b43c`).
An initial Frida Java-bridge crash occurred **before any `issued` event**, with
zero responses. The batch was closed as `failed_before_issue`, its 16 reserved
slots released, and it was never replayed. A request-free Frida Java health
check passed; a new batch name, batch15, then returned **16 issued / 16 valid
responses**. The old batch name is intentionally retired.

Scores on the 16 prospective, frozen predictions (BMC exact after tenth-kg
rounding):

| Candidate | Exact | MAE, kg | Max displayed error, kg |
| --- | ---: | ---: | ---: |
| Delegated centered-square-root candidate | 5/16 | 0.06875 | 0.1 |
| Fresh seven-term square-root OLS | 9/16 | 0.04375 | 0.1 |
| Seven-term OLS + R1² | 12/16 | 0.025 | 0.1 |
| Seven-term OLS + 1/R1 | 12/16 | 0.025 | 0.1 |
| Seven-term OLS + R0·R1 | 12/16 | 0.025 | 0.1 |

All variants were exploratory choices based on inspected old data, then evaluated
prospectively on the same new batch. The best forms still fail four inputs; do
**not** fit those 16 cases and rebrand the result as independent validation.
Representative discriminators: R0=501.0 ohm (male 185cm/87kg) gave 3.6kg,
while the centered candidate predicted 3.5kg; R0=999.0 gave 0.7kg versus
0.8kg; R1=301.0 gave 5.0kg versus 5.1kg; a male 100.1kg case gave 3.9kg
versus 3.8kg. These are incompatible with dismissing every mismatch as an
unavoidable output-rounding effect. A post-hoc intermediate-rounding mechanism
has not been established.

On the same fresh responses, the observed arithmetic identities
`round1(weight_kg * bmcp / 100) == bmc` and `ffm - slm == bmc` hold **16/16**;
they allow derived output checks if `bmcp` or `slm` is already available, but
neither independently recovers bone mass from raw scale inputs. The safe app
status is unchanged: **do not replace the bone estimator with these models or
claim Xiaomi parity**. The ledger now records 336/360 reserved, with 24
remaining under that grant. Preserve the remaining allowance for a genuinely
discriminating hypothesis; no more requests were made in this pass.

Reproducible numeric-only fixtures and frozen prediction scores:
`tools/s400_oracle_audit/bmc_fresh_batch15_fixture.json` and
`tools/s400_oracle_audit/bmc_fresh_batch15_scores.json`.

## Follow-up: channel shape, cap, and arithmetic order

All original numeric rows plus batches 15–18 yield **363 loaded rows, 243
unique physical inputs** (same height/weight/R0/R1 deduplicated). New queries
were calculation-only, with frozen input vectors and predictions before each
batch. Scripts and numeric-only responses live under `tools/s400_oracle_audit/`.

1. **No fixed R1 cap at 510 Ω.** The old flat observed plateau does not mean
   Xiaomi clips R1 itself. On eight prospective high-R1 probes, the fixed
   510 Ω cap candidate matched 2/8 (up to 0.5 kg wrong), versus 7/8 for
   both an uncapped square-root candidate and a hinge candidate. Male
   185 cm/87 kg/R0=543.6 Ω rose from BMC 3.6 kg at R1=540 Ω to
   4.1 kg at R1=700 Ω; female 163 cm/66.6 kg/R0=572.5 Ω rose
   from 2.5 at R1=560 Ω to 3.1 at R1=700 Ω. The hinge and
   uncapped shapes were not distinguished by this test. Frozen predictions:
   `batch16_frozen_predictions.json`, SHA256
   `28b4e829e1c904c4b8c0c3060cc5b94250b5835df147718bfb0c9e1d3b9d5491`.
2. **BMC saturation at 5.0 kg is strongly supported, not vendor-source
   confirmed.** Four prospective high-bone probes had BMC [5.0, 4.9,
   5.0, 5.0]; the frozen uncapped smooth model matched 2/4 and the same
   model capped at 5.0 matched 4/4. Derived `bmcp` matched 4/4 after
   rounding `100 * observed BMC / weight`. Old+new data contain ten
   distinct physical inputs reporting exactly 5.0 kg, and none higher.
   These results do not establish whether Xiaomi clamps latent BMC before
   or after tenths rounding. Frozen predictions:
   `batch18_frozen_predictions.json`, SHA256
   `e47f7a13cbe1d361a21e43984ae00b1f08b93f5db27ebc1928106d6c79a4fdcc`.
3. **Observed arithmetic is consistent with BMC → BMCP and BMC → SLM.**
   Recompute with decimal round-half-up: `bmcp = round1(100*BMC/weight)`
   holds 243/243 physical inputs; `FFM-SLM = BMC` holds 243/243.
   The opposite `BMC = round1(weight*bmcp/100)` holds only **239/243**.
   Three failures have displayed BMC 5.0 at weight 101 kg, for which
   inverse reconstruction gives 5.1; a fourth counterexample has BMC
   **4.9** at 110 kg, for which the inverse is 5.0. Thus a cap alone does
   not explain all one-way failures. These identities constrain the
   order of displayed outputs; they are **not proof of internal code order**.
   Reproduce via `python3 tools/s400_oracle_audit/bmc_order_checks.py`.

Batch17 included six prospective order-discriminating inputs; its predicted
A/B values were conditional on an approximate latent formula, which failed
at the high-bone boundary. Do not award its result to either full raw-input
pipeline. The **model-free** reverse/forward identity checks above remain
valid independently of that approximation. No interpolation, decimal tweak,
or per-case exception has recovered the remaining BMC formula. **Do not
integrate BMC as Xiaomi-equivalent.** Ledger: 354/360 issued/reserved,
6 remain for a genuinely new discriminating hypothesis; none were used
in fitting after batch18.

## Conditional arithmetic checks

These use recorded upstream metrics. They validate arithmetic, not a full BIA
input-to-output model:

- `water_kg = round1(weight_kg * BWP / 100)` matches **329/329**.
- Inverting displayed water kg back into BWP matches **328/329**; do not reverse
  the rounding order in OpenScale.
- `protein_kg = round1(weight_kg * PP / 100)` matches **329/329**.
- `BWP = round1(0.7 * (100 - BFP))` matches only **2/329**, maximum error 17.1 pp.

## Reproducible artifacts

Versioned reproducible bundle: `tools/s400_oracle_audit/`.

- `raw_numeric.json`: numeric-only, deduplicated responses, no account data.
- `bone_muscle_cv.py`, `water_visceral_cv.py`: offline analyses requiring NumPy.
- `*_cv_summary.json`: recorded aggregate results.
- Running each script generates `*_cv_report.json` with per-input predictions.
- See the bundle README for execution and limitations.

No BLE keys, auth data, MAC addresses or personal profile identifiers are included.
All four metric investigations have been executed locally; two attempted delegated
analyses failed with a provider HTTP 401 before doing any analysis. Their summaries
were not used. No claims of recovered complete formulas are made here.
