"""Explore shared BMC channel plateaus offline. Hypotheses only, not vendor code."""
import hashlib
import json
import math
from pathlib import Path
import numpy as np
ROOT = Path(__file__).resolve().parent
old = json.loads((ROOT/'raw_numeric.json').read_text())
new = json.loads((ROOT/'bmc_fresh_batch15_fixture.json').read_text())['cases']
unique = {}
for e in old+new:
    p=e['params'];key=(p['height'],p['weight'],*p['current_impedance'])
    if key in unique:assert unique[key]['metrics']['bmc']==e['metrics']['bmc']
    else:unique[key]=e
D=list(unique.values())
h=np.array([e['params']['height']/10 for e in D]);w=np.array([e['params']['weight']/10 for e in D]);r=np.array([e['params']['current_impedance'][0]/10 for e in D]);t=np.array([e['params']['current_impedance'][1]/10 for e in D]);y=np.array([e['metrics']['bmc'] for e in D]);
fold=np.array([int(hashlib.sha256(repr(k).encode()).hexdigest(),16)%5 for k in unique]);
def q(a):return np.floor(np.maximum(0,a)*10+.5+1e-9)/10
def features(kind,ar=h,aw=w,rr=r,tt=t):
    base=[np.ones(len(ar)),ar*ar,aw]
    if kind=='sqrt7':return np.array(base+[rr,np.sqrt(rr),tt,np.sqrt(tt)]).T
    if kind.startswith('R1cap'):
        cap=float(kind.split(':')[1]);t2=np.minimum(tt,cap)
        return np.array(base+[rr,np.sqrt(rr),t2,np.sqrt(t2)]).T
    if kind.startswith('R1hinge'):
        cut=float(kind.split(':')[1]);return np.array(base+[rr,np.sqrt(rr),tt,np.sqrt(tt),np.maximum(0,tt-cut)]).T
    if kind.startswith('bothcap'):
        _,a,b=kind.split(':');r2=np.minimum(rr,float(a));t2=np.minimum(tt,float(b))
        return np.array(base+[r2,np.sqrt(r2),t2,np.sqrt(t2)]).T
    if kind.startswith('R0hump_R1cap'):
        _,a,b=kind.split(':');rr2=np.maximum(0,(rr-float(a))**2);t2=np.minimum(tt,float(b))
        return np.array(base+[rr2,t2,np.sqrt(t2)]).T
    raise ValueError(kind)

def fit(X,train):
    scale=X[train].std(axis=0);scale[scale<1e-9]=1
    c=np.linalg.lstsq((X[train]/scale)[y[train]>0],y[train][y[train]>0],rcond=1e-12)[0]/scale
    return X@c,c

def evaluate(kind):
    X=features(kind);train,c=fit(X,np.arange(len(D)))
    cross=np.empty(len(D))
    for k in range(5):
        tr=np.where(fold!=k)[0];te=np.where(fold==k)[0]
        _,c2=fit(X,tr);cross[te]=X[te]@c2
    e1=np.abs(q(train)-y);e2=np.abs(q(cross)-y)
    return dict(name=kind,train=int(sum(e1<1e-8)),cv=int(sum(e2<1e-8)),cv_mae=float(np.mean(e2)),cv_max=float(max(e2)),coefficients=c.tolist())
variants=['sqrt7']+[f'R1cap:{t}' for t in range(480,551,5)]+[f'R1hinge:{t}' for t in range(480,551,10)]+[f'R0hump_R1cap:{a}:{b}' for a in range(540,621,10) for b in range(480,541,10)]
res=[evaluate(k) for k in variants];report={'n_groups':len(D),'note':'All records including batch15 previously inspected. CV exploratory; threshold comparisons selected on CV, not prospective. No extra oracle queries.','leading_models':sorted(res,key=lambda a:(-a['cv'],a['cv_mae']))[:25], 'baseline':res[0]}
out=ROOT/'bmc_shape_explore_report.json';out.write_text(json.dumps(report,indent=2));print(json.dumps({k:v for k,v in report.items() if k!='leading_models'},indent=2));print('LEADERS');print(*[(z['name'],z['train'],z['cv'],round(z['cv_mae'],6),z['cv_max']) for z in report['leading_models'][:20]],sep='\n');print('REPORT',out)
