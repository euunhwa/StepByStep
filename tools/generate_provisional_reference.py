"""User-authorized arbitrary evaluation reference. No expert labels or public-data fitting.
Regeneration is deterministic and uses the explicitly synthetic submission drive only.
"""
from pathlib import Path
import json, hashlib
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
out=ASSETS/'evaluation';out.mkdir(exist_ok=True)
source=ASSETS/'demo/signals/auto-drive.json'
frames=json.loads(source.read_text())
windows=[('START',0,6800),('LEFT',8600,17900),('RIGHT',21600,30900),('U_TURN',34800,45800),('STOP',49200,56100),('T_PARK',59000,90900),('PARALLEL_PARK',93700,110400)]
notice='평가용 데이터셋은 임의로 만든 자료이며 개선이 필요합니다.'
limits={
 'entry_speed':(4.0,2.0,1.5),'rotation_speed':(3.5,2.0,1.5),'exit_speed':(4.0,2.0,1.5),
 'acceleration':(3.0,3.0,3.0),'deceleration_if_cause_known':(3.5,3.0,3.0),
 'longitudinal_jerk':(6.0,6.0,2.0),'steering_rate':(160.0,160.0,1.0),'exit_steering_rate':(120.0,120.0,1.0),
 'parking_speed':(1.5,1.0,1.0),'entry_pose_error_for_space':(15.0,30.0,1.0),'axis_angle_error':(3.0,7.0,1.0),
 'verified_vehicle_footprint_outside_slot':(0.0,1.0,1.0)}
episodes=[]
for kind,begin,end in windows:
 samples=[]
 for f in frames:
  if begin<=f['timeMs']<=end:
   samples.append({'time_ms':f['timeMs'],'speed_mps':f['speed']['value'],'steering_wheel_deg':f['steering']['value'],'gear':f['gear']['value'],'heading_deg':f['heading']['value']})
 episodes.append({'maneuver':kind,'synthetic':True,'expert_reviewed':False,'reference_is_arbitrary':True,'start_ms':begin,'end_ms':end,'samples':samples})
dataset={'schema_version':1,'id':'arbitrary-evaluation-reference-0.2','created_on':'2026-11-06',
 'source_type':'ARBITRARY_SYNTHETIC','arbitrary':True,'expert_validated':False,'requires_improvement':True,
 'app_notice':notice,'authoring':'AI generated following user instruction; thresholds, scales and taus are arbitrary prototype choices.',
 'usage':'Provisional coaching comparison only; not safety certification, not an ML-trained scoring model.',
 'source_fixture_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
 'limits':{k:{'bound':b,'scale':s,'tau_seconds':t,'source':'ARBITRARY_SYNTHETIC / arbitrary-evaluation-reference-0.2','validation_status':'ARBITRARY_SYNTHETIC'} for k,(b,s,t) in limits.items()},
 'templates':[{'maneuver':e['maneuver'],'start_ms':e['start_ms'],'end_ms':e['end_ms'],'sample_count':len(e['samples'])} for e in episodes],
 'time_series_asset':'evaluation/arbitrary-reference-episodes-v1.json',
 'limitations':['No instructor ratings','No target-vehicle calibration','No independently validated safe/unsafe boundaries','Same synthetic family as demo; cannot measure generalization','Missing live signals still hold the affected total']}
(out/'arbitrary-reference-v1.json').write_text(json.dumps(dataset,ensure_ascii=False,indent=2)+'\n')
(out/'arbitrary-reference-episodes-v1.json').write_text(json.dumps({'dataset_id':dataset['id'],'notice':notice,'episodes':episodes},ensure_ascii=False,indent=2)+'\n')
policy=json.loads((out/'evaluation-policy-draft.json').read_text())
policy.update(version='evaluation-prototype-0.4',status='ARBITRARY_SYNTHETIC_PROTOTYPE',provisional_scoring={'enabled':True,'dataset_id':dataset['id'],'score_unconfirmed_braking_observations':True,'score_meaning':'OBSERVED_CONTROL_SMOOTHNESS_NOT_FAULT'},app_notice=notice)
policy['real_world_scoring_enabled']=False
policy['score_contract']['unconfirmed_braking_cause']='Provisional observation mode scores measured smoothness without assigning fault; missing required signals still HOLD. Expert/legacy policies retain the cause gate.'
policy['score_contract']['tau_semantics']='Arbitrary prototype exposure seconds, not instructor-calibrated; never current maneuver duration.'
(out/'evaluation-policy-prototype.json').write_text(json.dumps(policy,ensure_ascii=False,indent=2)+'\n')
p=json.loads((ASSETS/'profiles/auto-coach-draft.json').read_text())
p.update(version='arbitrary-prototype-0.3',validationStatus='ARBITRARY_SYNTHETIC_PROTOTYPE',source=notice+' 전문가/공개 평가 데이터로 학습하거나 보정한 기준이 아닙니다.',conditions='임의 생성 시제품 비교 조건 · 실제 차종/도로별 보정 필요')
p['scoring']['acceleration']['maxAbs']=limits['acceleration'][0]
p['scoring']['jerk']['maxAbs']=limits['longitudinal_jerk'][0]
p['phaseBands']['deceleration']['maxAbs']=limits['deceleration_if_cause_known'][0]
(ASSETS/'profiles/arbitrary-prototype-v1.json').write_text(json.dumps(p,ensure_ascii=False,indent=2)+'\n')
print('Generated arbitrary reference:',len(episodes),'maneuvers,',sum(len(e['samples']) for e in episodes),'samples. Expert validated: false.')
