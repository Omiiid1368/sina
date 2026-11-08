#!/usr/bin/env python3
"""Check the content-update ledger, retired IDs, source pixels and shared membership.
This validates recorded evidence, not independent scientific or publisher approval.
"""
from pathlib import Path
from collections import Counter
import hashlib,json
from PIL import Image
ROOT=Path(__file__).resolve().parents[2]
ASSETS=ROOT/'app/src/main/assets'
BATCH=ROOT/'content/imports/v013'
def read(p):return json.loads(p.read_text())
def main():
 old=read(ROOT/'content/legacy/v012/questions.json');bank=read(ROOT/'content/questions.json');finals=read(ROOT/'content/finals.json')
 byid={q['id']:q for q in bank};assert len(byid)==len(bank)
 retired=read(BATCH/'retired.json');new=read(BATCH/'new-questions.json');changes=read(BATCH/'transcription-corrections.json');report=read(BATCH/'report.json')
 retiredids={q['id'] for q in retired};newids={q['id'] for q in new};oldids={q['id'] for q in old}
 assert not retiredids&set(byid) and not newids&oldids
 assert set(byid)==(oldids-retiredids)|newids
 for q in old:
  if q['id'] in retiredids:
   assert next(r['question'] for r in retired if r['id']==q['id'])==q;continue
  for c in changes:
   if c['id']==q['id']:
    field,index=c['field'].split('/');assert q[field][int(index)]==c['before'];q[field][int(index)]=c['after']
  assert q==byid[q['id']],q['id']
 assert read(ROOT/'content/legacy/v012/finals.json')==finals
 assert len(old)-len(retired)+len(new)==len(bank)==report['mainCount']==report['uniqueMcqCount']
 assert (len(retired),len(new),len(changes))==(report['retiredExisting'],report['newAdded'],report['existingTranscriptionCorrections'])
 assert report['newNumberedNonQbQuestions']==len(new)+len(read(BATCH/'duplicates.json'))+len(read(BATCH/'skipped.json'))
 keys=read(ROOT/'app/src/androidTest/assets/upload-compilation-keys.json')
 for q in bank:
  assert len(q['options'])==len(set(q['options']))==len(q['optionExplanations'])==4 and q['answer'] in range(4)
  assert q['explanation']==q['optionExplanations'][q['answer']]
  if q['id'] in keys:assert q['answer']==keys[q['id']]
 for q in new:
  assert q==byid[q['id']]
  assert q['answerSource']=={'kind':'educational_answer','label':'پاسخ و تحلیل آموزشی','suppliedKey':False,'officialKeyVerified':False}
  assert q['review']['singleAnswerApproved'] and q['review']['allFourAnalysesApproved']
  assert not q['review']['independentHumanEditorialApproval']
  assert q['provenance']['officialOriginalIndependentlyVerified'] is False
  assert q['requiresImage'] and (ASSETS/q['image']).is_file()
 for r in read(BATCH/'duplicates.json'):assert r['canonicalId'] in byid
 assert not ({q['id'] for q in read(BATCH/'skipped.json')}&set(byid))
 pixels=read(BATCH/'image-integrity.json')
 for r in pixels:
  with Image.open(ASSETS/r['path']) as im:
   rgb=im.convert('RGB');assert rgb.size==(r['width'],r['height'])
   assert hashlib.sha256(rgb.tobytes()).hexdigest()==r['pixelSha256']
   assert max(rgb.size)<=8000 and rgb.width*rgb.height<=24000000
 members=Counter(m for q in bank for m in q.get('collections',['main']))
 assert members['main']==report['mainCount'] and members['qb_very_difficult']==report['veryDifficultCount']==0
 assert not report['qbEligibleRedCountKnown'] and not report['qbLegendVerified']
 for n in ['questions.json','finals.json']:assert (ROOT/'content'/n).read_bytes()==(ASSETS/n).read_bytes()
 out={'passed':True,'baseline':len(old),'retained':len(old)-len(retired),'withdrawnRecoverable':len(retired),'added':len(new),'transcriptionCorrections':len(changes),'main':len(bank),'veryDifficult':0,'shared':0,'unique':len(bank),'finalsUnchanged':len(finals),'retainedSourceKeyMatches':sum(q['id'] in keys for q in bank),'newLosslessImagePixelMatches':len(pixels),'humanScientificApproval':False,'officialPaperKeyCertified':False,'qbEligibleRedCountKnown':False}
 print(json.dumps(out,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
