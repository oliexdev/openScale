"""Offline bone/SMM hypothesis tests. NumPy only; no network or prior coefficients."""
import hashlib
import itertools
import json
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT/'raw_numeric.json'
DATA = json.loads(SOURCE.read_text())

def key(e):
    p=e['params']
    return (p['sex'],p['height'],p['weight'],*p['current_impedance'])

def fold(e):
    return int(hashlib.sha256(repr(key(e)).encode()).hexdigest(),16)%5

def feature(e,kind):
    p=e['params'];h=p['height']/10;w=p['weight']/10;r,t=np.array(p['current_impedance'])/10
    if kind in ('poly2','poly3'):
        z=[(h-170)/25,(w-75)/30,(r-600)/200,(t-500)/150]
        terms=[1.]
        for degree in range(1,3 if kind=='poly2' else 4):
            for ix in itertools.combinations_with_replacement(range(4),degree):
                terms.append(float(np.prod([z[i] for i in ix])))
        return terms
    if kind=='rational':
        return [1,h,w,r,t,h*h/r,h*h/t,r/t,t/r,h*h*t/r**2,h*h*r/t**2,r*r,t*t,h*h,w/h**2*10000]
    raise ValueError(kind)

def fit(X,y):
    scale=np.std(X,axis=0);scale[scale<1e-10]=1
    return np.linalg.lstsq(X/scale,y,rcond=1e-10)[0]/scale

def summary(pred,obs):
    err=np.abs(np.asarray(pred)-np.asarray(obs))
    return {'n':len(obs),'exact':int(sum(err<1e-8)),'within_0_1kg':int(sum(err<=.1+1e-8)),
            'mae_kg':float(np.mean(err)),'max_error_kg':float(max(err))}

def round1(x):return float(np.floor(x*10+.5+1e-9)/10)

def unclamped(e):
    m=e['metrics'];ratio=m['smm']/m['slm']
    return .453<ratio<.697

report={'status':'EXPLORATORY_NOT_COMPLETE_PARITY','unique_inputs':len(DATA),
        'input_sha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        'split':'5 grouped folds; same physical inputs across ages share a fold',
        'results':[], 'conditional_arithmetic':{},
        'caveats':['Corpus already inspected: grouped crossvalidation is not a fresh independent holdout.',
                   'SMM clamps below use observed oracle SLM. This is conditional validation, not a deployable full pipeline.',
                   'SMM training excludes observed bound cases; classification is based on labels.',
                   'No prior coefficients, no network queries, no production edits.']}
for sex in (0,1):
    rows=[e for e in DATA if e['params']['sex']==sex]
    for metric in ['bmc','smm']:
        for kind in ['poly2','poly3','rational']:
            X=np.array([feature(e,kind) for e in rows]);Y=np.array([e['metrics'][metric] for e in rows])
            predicted=[];observed=[];samples=[];unbounded=[]
            for k in range(5):
                train=[i for i,e in enumerate(rows) if fold(e)!=k and (metric!='smm' or unclamped(e))]
                test=[i for i,e in enumerate(rows) if fold(e)==k]
                assert not ({key(rows[i]) for i in train}&{key(rows[i]) for i in test})
                assert len(train)>X.shape[1]
                c=fit(X[train],Y[train]);raw=X[test]@c
                for i,v in zip(test,raw,strict=True):
                    plain=round1(float(v));unbounded.append(plain)
                    if metric=='bmc': q=round1(max(0,float(v)))
                    else:
                        # Preserve non-tenth exact bound outputs. Interior is displayed as tenths.
                        slm=rows[i]['metrics']['slm'];lo=.45*slm;hi=.70*slm
                        q=lo if v<lo else hi if v>hi else round1(float(v))
                    predicted.append(q);observed.append(float(Y[i]))
                    samples.append({'params':rows[i]['params'],'predicted_raw':float(v),
                                    'predicted_output':q,'observed':float(Y[i]),'oracle_slm':rows[i]['metrics']['slm']})
            report['results'].append({'sex':sex,'metric':metric,'kind':kind,'terms':X.shape[1],
                                      **summary(predicted,observed),'unbounded':summary(unbounded,observed), 'samples':samples})

slm_pred=[round1(e['metrics']['ffm']-e['metrics']['bmc']) for e in DATA]
report['conditional_arithmetic']['slm_from_displayed_ffm_minus_bmc']=summary(slm_pred,[e['metrics']['slm'] for e in DATA])
report['conditional_arithmetic']['smm_is_slm']=summary([e['metrics']['slm'] for e in DATA],[e['metrics']['smm'] for e in DATA])
ratios=[e['metrics']['smm']/e['metrics']['slm'] for e in DATA]
report['observed_smm_bounds']={'min':min(ratios),'max':max(ratios),'at_045':sum(abs(r-.45)<1e-8 for r in ratios), 'at_070':sum(abs(r-.70)<1e-8 for r in ratios)}
OUT=ROOT/'bone_muscle_cv_report.json'
OUT.write_text(json.dumps(report,indent=2))
for r in report['results']: print({k:v for k,v in r.items() if k!='samples'})
print('arithmetic',report['conditional_arithmetic'])
print('bounds',report['observed_smm_bounds'])
print('REPORT',OUT)
