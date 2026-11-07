"""Inspect four small published CAN arrays; stdlib only, no emulator/model training."""
import ast, hashlib, json, math, statistics, struct, sys, urllib.parse, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
LIMIT=65536

def load_npy(path):
    raw=path.read_bytes()
    if raw[:6]!=b'\x93NUMPY':raise ValueError('not an NPY file')
    major=raw[6];offset=10 if major==1 else 12
    length=struct.unpack('<H' if major==1 else '<I',raw[8:offset])[0]
    h=ast.literal_eval(raw[offset:offset+length].decode('latin1'))
    if not (len(h['shape'])==1 or (len(h['shape'])==2 and h['shape'][1]==1)) or h['descr'] not in ('<f8','<f4'):raise ValueError('unsupported scalar numeric array')
    fmt='<d' if h['descr']=='<f8' else '<f'
    values=[x[0] for x in struct.iter_unpack(fmt,raw[offset+length:])]
    if len(values)!=h['shape'][0] or not all(math.isfinite(x) for x in values):raise ValueError('shape/nonfinite')
    return values,h

def main():
    tree=json.loads((ROOT/'evidence/comma2k19-tree.json').read_text())
    selected=[e for e in tree['tree'] if e.get('type')=='blob' and any(e['path'].endswith('/CAN/'+s+'/'+v) for s in ('speed','steering_angle') for v in ('t','value'))]
    assert len(selected)==4 and sum(e['size'] for e in selected)<262144
    folder=ROOT/'sample-data/comma2k19';folder.mkdir(parents=True,exist_ok=True)
    manifest=[]
    for e in selected:
        suffix=e['path'].split('/CAN/')[1].replace('/','_')+'.npy';p=folder/suffix
        url='https://raw.githubusercontent.com/commaai/comma2k19/master/'+urllib.parse.quote(e['path'],safe='/')
        if '--download' in sys.argv:
            with urllib.request.urlopen(url,timeout=15) as r:data=r.read(LIMIT+1)
            if len(data)>LIMIT:raise ValueError('download cap')
            p.write_bytes(data)
        data=p.read_bytes()
        blob=hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
        assert blob==e['sha'],('published blob mismatch',suffix)
        manifest.append(dict(file=str(p.relative_to(ROOT)),url=url,bytes=len(data),git_blob_sha1=blob,sha256=hashlib.sha256(data).hexdigest()))
    summary={'status':'ONE_HIGHWAY_SEGMENT_INSPECTED_NOT_EXPERT_REFERENCE','total_downloaded_bytes':sum(e['bytes'] for e in manifest),'signals':{},'not_available_in_inspected_files':['gear','parking_space','maneuver_labels','expert_quality_scores'],'files':manifest}
    for s,unit in [('speed','m/s'),('steering_angle','degree:as-described-by-source')]:
        t,_=load_npy(folder/(s+'_t.npy'));v,h=load_npy(folder/(s+'_value.npy'));assert len(t)==len(v)
        dt=[b-a for a,b in zip(t,t[1:])];positive=[d for d in dt if d>0]
        summary['signals'][s]=dict(count=len(v),dtype=h['descr'],duration_s=t[-1]-t[0],strictly_increasing=all(d>0 for d in dt),duplicate_or_reverse_count=sum(d<=0 for d in dt),median_dt_s=statistics.median(positive),median_hz=1/statistics.median(positive),max_gap_s=max(dt),minimum=min(v),maximum=max(v),unit_from_documentation=unit)
    out=ROOT/'evidence/comma2k19-sample-inspection.json';out.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in summary.items() if k!='files'},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
