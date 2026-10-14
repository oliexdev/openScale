# Offline S400 oracle audit

Python with NumPy required. No network requests, app credentials, or device writes.

```sh
python3 tools/s400_oracle_audit/bone_muscle_cv.py
python3 tools/s400_oracle_audit/water_visceral_cv.py
```

The included corpus contains 329 unique numeric input/output pairs deduplicated
from previously captured calculation responses. It contains synthetic profiles,
not the user's physical measurement or authentication data. Its SHA256 is
`c634a88db48d347527f5a7020ce6e2cef25adac2b696e44f9d6943dd254b3ae6`.

Scripts generate detailed JSON reports next to the input. Recorded summary JSONs
are committed; generated detailed reports are intentionally ignored. Compare each
report's aggregate `results` and `conditional_*` entries with the summary files.

All age variants of sex/height/weight/impedance-pair share a fold. Fits are
exploratory grouped crossvalidation on already-inspected data, not new independent
holdout validation. Coefficients are refitted from scratch in each fold; no old
model is imported. Candidate selection after inspecting these scores requires
new frozen validation before any exact-parity production claim.

SMM evaluation supplies recorded SLM to its empirical clamps and excludes observed
clamp-region cases from latent training. This is conditional evaluation only.
Neither this pipeline nor the candidate regressions are production replacements.
See `docs/s400_component_empirical_audit.md` for results and unresolved failures.
