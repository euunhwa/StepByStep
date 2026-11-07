"""Lightweight arithmetic/contract checks for the design only; not app scoring."""
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
import hashlib,json,math
ROOT=Path(__file__).resolve().parents[1]
policy=json.loads((ROOT/'evaluation-policy-draft.json').read_text())

def points(cap,exposure,tau):
    if not (math.isfinite(exposure) and exposure>=0 and math.isfinite(tau) and tau>0):raise ValueError('invalid exposure')
    raw=Decimal(cap)*min(Decimal(1),Decimal(str(exposure))/Decimal(str(tau)))
    return int(raw.quantize(Decimal(1),rounding=ROUND_HALF_UP))

def count_penalty(n):
    if n is None:return None
    if type(n) is not int or n<0:raise ValueError('invalid confirmed gear count')
    return min(30,n*(n+1))

def exposure(runs):
    return sum(ms/1000*value for ms,value in runs if ms>=300 and value>0)

def illustrative_total(groups):
    if not groups or any(v is None for v in groups):return None
    return max(0,100-min(100,sum(groups)))

def eligible_for_real_scoring(expert_validated,domain_matches,complete):
    return policy['real_world_scoring_enabled'] and expert_validated and domain_matches and complete

checks=[]
def checked(name,condition):
    if not condition:raise AssertionError(name)
    checks.append(name)
checked('7 types and per-type budgets total 100',len(policy['budgets'])==7 and all(sum(x['cap'] for x in group)==100 for group in policy['budgets'].values()))
checked('real scoring stays disabled even for a hypothetical complete case',not eligible_for_real_scoring(True,True,True))
checked('normative bounds and tau deliberately unfilled',policy['score_contract']['normative_bounds'] is None and policy['score_contract']['per_metric_tau_s'] is None and all(x['normative_bound'] is None for x in policy['metrics']))
checked('actual gear count weighted from first transition',[count_penalty(n) for n in range(6)]==[0,2,6,12,20,30])
checked('missing gear is not zero',count_penalty(None) is None)
checked('short isolated exceedance excluded',exposure([(200,20)])==0)
a=points(40,exposure([(2000,1)]),5);b=points(40,exposure([(2000,2)]),5);c=points(40,exposure([(4000,1)]),5)
checked('larger and longer exceedance never reduce deduction',a==16 and b==32 and c==32)
checked('long normal time cannot dilute prior penalty',points(40,exposure([(2000,1),(50000,0)]),5)==a)
checked('cap and rounding are explicit',points(40,1000,5)==40 and points(1,.5,1)==1)
checked('example sum 100-12-6-0-4-0=78',illustrative_total([12,6,0,4,0])==78)
checked('partial evidence never renormalizes to 100',illustrative_total([12,6,None,4,0]) is None)
checked('inference rejects manual type and future features',{'selected_maneuver_type','ground_truth_label','future_frame'}.issubset(policy['inference_contract']['forbidden_features']))
ins=json.loads((ROOT/'evidence/comma2k19-sample-inspection.json').read_text())
checked('inspected arrays match stored SHA256',all(hashlib.sha256((ROOT/r['file']).read_bytes()).hexdigest()==r['sha256'] for r in ins['files']))
checked('sample remains a sample, not an expert reference',ins['status']=='ONE_HIGHWAY_SEGMENT_INSPECTED_NOT_EXPERT_REFERENCE' and ins['total_downloaded_bytes']==159680)
result=dict(status='PASS',checks=len(checks),details=checks,scope='STDLIB_ONLY_NO_EMULATOR_NO_GRADLE_NO_ML_TRAINING',example_only=dict(parking_count_n3=12,combined_example_score=78))
(ROOT/'evidence/policy-verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
