"""Independent verification of returned sqrt BMC hypothesis; no network."""
from pathlib import Path
import hashlib
import json
import numpy as np
ROOT=Path(__file__).resolve().parent
SOURCE=ROOT/'raw_numeric.json'
DATA=json.loads(SOURCE.read_text())
CHILD=json.loads((ROOT/'bmc_sqrt_child_candidate.json').read_text())
NAMES=['1','h_cm_squared','weight_kg','R0_ohm','sqrt_R0','R1_ohm','sqrt_R1']
def physical(e):
 p=e['params'];return (p['height'],p['weight'],*p['current_impedance'])
def vec(e):
 p=e['params'];h=p['height']/10;w=p['weight']/10;r,t=np.array(p['current_impedance'])/10
 return [1,h*h,w,r,np.sqrt(r),t,np.sqrt(t)]
def q(v):return np.floor(np.maximum(0,v)*10+.5+1e-9)/10

groups={}
for e in DATA:
 k=physical(e)
 if k in groups:assert groups[k]['metrics']['bmc']==e['metrics']['bmc']
 else:groups[k]=e
unique=list(groups.values());X=np.array([vec(e) for e in unique]);y=np.array([e['metrics']['bmc'] for e in unique])
scale=np.std(X,axis=0);scale[scale<1e-10]=1;A=X/scale
lo=np.where(y>0,y-.05+1e-7,-np.inf);hi=y+.05-1e-7

def solve(indices,max_iter):
 a=A[indices];yy=y[indices];ll=lo[indices];hh=hi[indices]
 # Floored zero must not be a target of raw least-squares.
 positive=yy>0;c=np.linalg.lstsq(a[positive],yy[positive],rcond=1e-12)[0]
 assert max_iter > 0
 corr=np.zeros(len(indices));norm=np.sum(a*a,axis=1)
 iteration = -1
 for iteration in range(max_iter):
  for i,row in enumerate(a):
   z=c+corr[i]*row;value=z@row
   step=(min(hh[i],max(ll[i],value))-value)/norm[i]
   c=z+step*row;corr[i]=-step
  if iteration%100==0:
   val=a@c;worst=float(max(np.max(ll-val),np.max(val-hh),0))
   if worst<1e-9:break
 val=a@c;worst=float(max(np.max(ll-val),np.max(val-hh),0))
 return c,{'iterations':iteration+1,'maximum_slab_violation_kg':worst,'converged':worst<1e-9}

def summary(raw,yy):
 err=np.abs(q(raw)-yy)
 return {'n':len(yy),'exact':int(sum(err<1e-8)),'mae_kg':float(np.mean(err)),'max_error_kg':float(max(err))}

# Re-evaluate the child fixed model on all 329 records.
c=CHILD['coefficients'];fixed=[]
for e in DATA:
 p=e['params'];h=p['height']/10;w=p['weight']/10;r,t=np.array(p['current_impedance'])/10
 fixed.append(c['c0']+c['c1_h2']*h*h+c['c2_w']*w+c['g0_r0']*(np.sqrt(r)-c['m0'])**2+c['g1_r1']*(np.sqrt(t)-c['m1'])**2)
report={'status':'EXPLORATORY_NOT_VALIDATED_FOR_PRODUCTION','source_sha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
 'features':NAMES,'raw_unique_inputs':len(DATA),'physical_groups_without_age_sex':len(unique),
 'child_fixed_model':summary(np.array(fixed),np.array([e['metrics']['bmc'] for e in DATA])),
 'caveats':['No quantization ceiling is established. Exact quantized parity can in principle be 100%.',
 'Child centres were searched over the full corpus; reported child CV is not fully nested model-selection validation.',
 'Removing sex/age dependence is supported by paired cases only, not proved globally.',
 'Zero floor observation imposes a one-sided raw upper bound, not a positive raw lower bound.',
 'All existing responses already inspected. This is exploratory CV, not fresh oracle validation.']}
cf,m=solve(np.arange(len(unique)),12000)
report['shared_full_interval_fit']={**m,**summary(A@cf,y),'coefficients':(cf/scale).tolist()}
folds=np.array([int(hashlib.sha256(repr(physical(e)).encode()).hexdigest(),16)%5 for e in unique]);cv=np.empty(len(unique));out=[]
for f in range(5):
 train=np.where(folds!=f)[0];test=np.where(folds==f)[0];cc,meta=solve(train,3000)
 cv[test]=A[test]@cc;out.append({'fold':f,**meta,**summary(cv[test],y[test])})
report['shared_grouped_cv']={**summary(cv,y),'folds':out}
report['cv_mismatches']=[{'params':unique[i]['params'],'predicted':float(q(cv)[i]),'observed':float(y[i])} for i in range(len(unique)) if abs(q(cv)[i]-y[i])>1e-8]
OUT=ROOT/'bmc_sqrt_verification_report.json';OUT.write_text(json.dumps(report,indent=2))
print(json.dumps({k:v for k,v in report.items() if k!='cv_mismatches'},indent=2));print('REPORT',OUT)
