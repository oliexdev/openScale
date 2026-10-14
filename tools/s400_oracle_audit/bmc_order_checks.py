"""Recompute observable BMC/BMCP/SLM arithmetic, without claiming vendor code."""
from decimal import Decimal, ROUND_HALF_UP
import json
from pathlib import Path
ROOT = Path(__file__).resolve().parent
SOURCES = [ROOT/'raw_numeric.json'] + [ROOT/f'bmc_fresh_batch{i}_fixture.json' for i in (15,16,17,18)]
def q(x):return x.quantize(Decimal('.1'), rounding=ROUND_HALF_UP)
def number(x):return Decimal(str(x))
all_rows=[]
for source in SOURCES:
    data=json.loads(source.read_text())
    rows=data if isinstance(data,list) else data['cases']
    all_rows.extend(rows)
unique={}
for row in all_rows:
    p=row['params'];key=(p['height'],p['weight'],*p['current_impedance'])
    if key in unique:assert unique[key]['metrics']['bmc']==row['metrics']['bmc']
    else:unique[key]=row
checks={'reverse':0,'forward':0,'ffm_minus_slm':0};failures=[];max_bmc=Decimal('-1');at_five=0
for row in unique.values():
    p=row['params'];m=row['metrics'];w=Decimal(p['weight'])/10;bone=number(m['bmc']);pct=number(m['bmcp'])
    reverse=q(100*bone/w)==pct;forward=q(w*pct/100)==bone;lean=number(m['ffm'])-number(m['slm'])==bone
    checks['reverse']+=reverse;checks['forward']+=forward;checks['ffm_minus_slm']+=lean
    if not forward:failures.append({'label':row.get('label','old'),'weight_kg':str(w),'bmc':str(bone),'bmcp':str(pct),'derived_bmc':str(q(w*pct/100))})
    max_bmc=max(bone,max_bmc);at_five+=bone==5
print(json.dumps({'loaded_records':len(all_rows),'unique_physical_inputs':len(unique),'checks':checks,'maximum_observed_bmc':str(max_bmc),'n_at_5kg':at_five,'forward_failures':failures},indent=2))
assert checks['reverse']==checks['ffm_minus_slm']==len(unique)
assert len(failures)==len(unique)-checks['forward']
# A non-cap counterexample matters: percent-first fails even below the ceiling.
assert any(number(e['bmc'])<5 for e in failures)
