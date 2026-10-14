"""Exploratory offline grouped CV. Never queries Xiaomi or imports previous models."""
import hashlib
import itertools
import json
from pathlib import Path
import numpy as np

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'raw_numeric.json'
DATA = json.loads(SOURCE.read_text())

def physical_key(e):
    p = e['params']
    return (p['sex'], p['height'], p['weight'], *p['current_impedance'])

def fold(e):
    return int(hashlib.sha256(repr(physical_key(e)).encode()).hexdigest(), 16) % 5

def numeric(e):
    p = e['params']
    return p['height']/10, p['weight']/10, p['age'], p['current_impedance'][0]/10, p['current_impedance'][1]/10

def vector(e, kind):
    h,w,a,r,t = numeric(e)
    z = [(h-170)/25, (w-75)/30, (r-600)/200, (t-500)/150]
    if kind in ('poly2','poly3'):
        degree = 2 if kind == 'poly2' else 3
        terms = [1.0]
        for d in range(1,degree+1):
            for indices in itertools.combinations_with_replacement(range(4),d):
                terms.append(float(np.prod([z[i] for i in indices])))
        return terms
    if kind == 'rational':
        return [1,h,w,r,t,h*h/r,h*h/t,r/t,t/r,h*h*t/r**2,h*h*r/t**2,r*r,t*t,h*h,w/h**2*10000]
    raise ValueError(kind)

def fit(X,y):
    scale = np.std(X,axis=0)
    scale[scale < 1e-10] = 1
    return np.linalg.lstsq(X/scale,y,rcond=1e-10)[0]/scale

def stats(pred,obs,integer=False):
    q = np.floor(np.asarray(pred)*(1 if integer else 10)+.5)/(1 if integer else 10)
    err = np.abs(q-np.asarray(obs))
    return {'n':len(obs),'exact':int(sum(err<1e-8)), 'mae':float(np.mean(err)),
            'max_error':float(max(err)), 'within_one_display_step':int(sum(err <= (1 if integer else .1)+1e-8))}

report = {'status':'EXPLORATORY_CROSS_VALIDATION_NOT_FRESH_HOLDOUT',
          'input_sha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
          'unique_inputs':len(DATA),'folds':5,
          'grouping':'sex,height,weight,impedance pair; all age variants together',
          'results':[], 'conditional_checks':{},
          'caveats':['All existing data previously inspected; no fresh independent validation.',
                     'Latent water fit uses recorded bwp*weight/100, not recovered original latent mass.',
                     'Finite samples cannot identify complete clamps or the original algorithm.',
                     'No OpenScale production changes and no network queries.']}
for sex in (0,1):
    rows = [e for e in DATA if e['params']['sex']==sex]
    for metric in ('bwp','vfl'):
        for kind in ('poly2','poly3','rational'):
            X = np.array([vector(e,kind) for e in rows]); predictions=[];observed=[];samples=[]
            for k in range(5):
                train=[i for i,e in enumerate(rows) if fold(e)!=k]
                test=[i for i,e in enumerate(rows) if fold(e)==k]
                assert not ({physical_key(rows[i]) for i in train}&{physical_key(rows[i]) for i in test})
                target=np.array([e['metrics'][metric] for e in rows],dtype=float)
                if metric=='bwp': target *= np.array([numeric(e)[1]/100 for e in rows])
                c=fit(X[train],target[train])
                raw=X[test]@c
                for i,v in zip(test,raw,strict=True):
                    if metric=='bwp': v=v*100/numeric(rows[i])[1]
                    else: v=max(1,float(v))  # observed lower floor only; no upper floor inferred
                    predictions.append(float(v));observed.append(float(rows[i]['metrics'][metric]))
                    samples.append({'params':rows[i]['params'],'predicted_raw':float(v),'observed':observed[-1]})
            report['results'].append({'sex':sex,'metric':metric,'kind':kind,
                                      'terms':X.shape[1],**stats(predictions,observed,metric=='vfl'), 'samples':samples})

# Conditional component identities: these use measured upstream metrics, not predictions.
for name,calc,target in [
    ('water_percent_from_reported_mass',lambda e:100*e['metrics']['bwm']/numeric(e)[1],'bwp'),
    ('water_mass_from_reported_percent',lambda e:numeric(e)[1]*e['metrics']['bwp']/100,'bwm'),
    ('water_percent_70pct_of_fatfree_percent',lambda e:.7*(100-e['metrics']['bfp']),'bwp'),
    ('protein_mass_from_reported_percent',lambda e:numeric(e)[1]*e['metrics']['pp']/100,'pm')]:
    rows=[e for e in DATA if target in e['metrics']]
    report['conditional_checks'][name]=stats([calc(e) for e in rows],[e['metrics'][target] for e in rows])

# Paired R250 changes: all other inputs including age identical.
groups={}
for e in DATA:
    p=e['params'];key=(p['sex'],p['age'],p['height'],p['weight'],p['current_impedance'][0])
    groups.setdefault(key,[]).append(e)
report['r1_effect_groups']={m:sum(len(g)>1 and len({e['metrics'][m] for e in g})>1 for g in groups.values()) for m in ['bmc','smm','bwp','vfl']}
OUT=ROOT/'water_visceral_cv_report.json'
OUT.write_text(json.dumps(report,indent=2))
for r in report['results']:print({k:v for k,v in r.items() if k!='samples'})
print('conditional',report['conditional_checks'])
print('r1_effect',report['r1_effect_groups'])
print('REPORT',OUT)
