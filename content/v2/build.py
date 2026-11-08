#!/usr/bin/env python3
"""Compile the individually authored v2 content; never generate to a target count.

The 4 MCQ and 3 written exercises per chapter are a deliberately small, explicit
curriculum sample, not an attempt to fill the product name with synthetic items.
"""
import hashlib
import importlib
import json
import shutil
from collections import Counter
from pathlib import Path
from schema import ROOT, MCQ, FINALS, CHAPTERS

for module in ['author_mcq10','author_mcq11','author_mcq12','author_finals10','author_finals11','author_finals12']:
    importlib.import_module(module)

def write(path, data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')

legacy=ROOT/'content/legacy/v1'
legacy.mkdir(parents=True,exist_ok=True)
old=ROOT/'content/questions.json'
expected='658ba02f02c328a181dec584c878ed46e81ed469e1cdf3ae8af69898fcc40cf7'
if not (legacy/'questions.json').exists():
    if hashlib.sha256(old.read_bytes()).hexdigest()!=expected:
        raise SystemExit('The original v1 bank is not recognized; refusing to overwrite an unarchived bank.')
    for name in ['questions.json','sources.json','bank-metadata.json','scientific-audit.json']:
        shutil.copyfile(ROOT/'content'/name,legacy/name)
if hashlib.sha256((legacy/'questions.json').read_bytes()).hexdigest()!=expected:
    raise SystemExit('Archived v1 bank checksum mismatch.')

oldrows=json.loads((legacy/'questions.json').read_text())
oldids={q['id'] for q in oldrows}
oldstems={q['stem'] for q in oldrows}
assert not oldids.intersection(q['id'] for q in MCQ+FINALS)
assert not oldstems.intersection(q['stem'] for q in MCQ+FINALS)

for name,rows in [('questions.json',MCQ),('finals.json',FINALS)]:
    write(ROOT/'content'/name,rows)
    write(ROOT/'app/src/main/assets'/name,rows)

sources=[{
    'id':'hezarzist-v2-authored','title':'محتوای مستقل تألیفی ارتقای ۰٫۱۰ پروژهٔ ۱۰۰۰ زیست',
    'kind':'authored','licenseId':'project-authored-v2','activeMcqCount':len(MCQ),
    'activeFinalsCount':len(FINALS),'documentVerified':True,'finalKeyVerified':False,
    'examAttribution':None,'authorship':'New independently authored project text; AI-assisted; not copied or paraphrased from a verified exam archive.',
    'evidence':'content/v2/author_mcq10.py, author_mcq11.py, author_mcq12.py, author_finals10.py, author_finals11.py, author_finals12.py',
    'review':'Conceptual review is separate from official-edition alignment; no human teacher or examination authority certification is claimed.',
    'diagramEvidence':'tools/diagram_questions.py:function figures(); six project-created schematic PNGs, reused as illustrations with newly authored tasks.'
}]
for grade in [10,11,12]:
    sources.append({'id':f'bio{grade}','kind':'curricular_reference','title':f'زیست‌شناسی پایهٔ {grade}',
        'bookCode':str({10:110216,11:111216,12:112216}[grade]),'officialPortal':'https://chap.sch.ir',
        'publicationYear':None,'editionVerified':False,'documentVerified':False,'finalKeyVerified':False,
        'referenceType':'chapter/topic; no invented page or print year','activeCopiedQuestionCount':0,
        'status':'ارجاع موضوعی؛ فایل چاپ رسمی و سال چاپ در این ارتقا تطبیق نشده است. متن و تصویر رسمی وارد بانک نشده است.'})
write(ROOT/'content/v2/sources.json',sources)
write(ROOT/'content/sources.json',sources)
write(ROOT/'content/v2/licenses.json',[{
    'id':'project-authored-v2','status':'original_project_content','distributionAllowed':True,
    'holder':'محتوای مستقل تألیفی پروژه','evidence':'content/v2/LICENSES.md',
    'scope':'New independent authored question text, suggested rubrics and project-created diagrams only; no grant over official books, exams or institute materials.',
    'notAnExternalPermissionCertificate':True,
    'ownerReviewRequiredBeforeCommercialPublication':True,
}, {
    'id':'third-party-user-declaration','status':'user_declared_permission','distributionAllowed':False,
    'evidence':'docs/v2/USER_LICENSE_DECLARATION.md',
    'scope':'The project owner states permission for test reproduction. No actual paper, final key and matching scope have been verified for import in this build.',
    'independentlyVerified':False,'activeQuestionCount':0,
}])
sha=lambda name:hashlib.sha256((ROOT/'content'/name).read_bytes()).hexdigest()
write(ROOT/'content/bank-metadata.json',{
    'schemaVersion':2,'appVersion':'0.10.0-beta','questionCount':len(MCQ),'finalsCount':len(FINALS),
    'officialExamQuestionCount':0,'institutionQuestionCount':0,'authoredFallback':True,
    'bankSha256':sha('questions.json'),'finalsSha256':sha('finals.json'),
    'sourceEditionsVerified':False,'fullScientificApproval':False,
    'images':len({q['image'] for q in MCQ+FINALS if q.get('image')}),
    'grades':dict(Counter(q['grade'] for q in MCQ)),
    'finalsGrades':dict(Counter(q['grade'] for q in FINALS)),
    'notAnOfficialExamArchive':True,'legacyArchive':'content/legacy/v1/questions.json',
    'oldProgressPolicy':'Historical v1 IDs remain in local archival statistics; no old question is reactivated.',
})
audit={
    'status':'pending_editorial_and_official_edition_alignment','bankVersion':2,
    'mcqCount':len(MCQ),'finalsCount':len(FINALS),'bankSha256':sha('questions.json'),
    'finalsSha256':sha('finals.json'),'approvedQuestionIds':[],
    'officialEditionVerification':False,'publicationYearsVerified':False,
    'humanTeacherCertification':False,'inheritedV1ReviewApplies':False,
    'reason':'New independently authored fallback; structural checks and separate conceptual review must not be presented as official-source or human-teacher approval.',
}
review_path=ROOT/'docs/v2/research/independent-content-review.json'
if review_path.exists():
    review=json.loads(review_path.read_text())
    expected_hashes={'content/questions.json':sha('questions.json'),'content/finals.json':sha('finals.json')}
    if review.get('finalHashes')==expected_hashes:
        audit.update(independentConceptualReview={
            'status':review.get('status'), 'report':'docs/v2/research/independent-content-review.json',
            'humanReadableReport':'docs/v2/research/INDEPENDENT_REVIEW.fa.md',
            'counts':review.get('counts'), 'snapshotHashesMatched':True,
            'reviewer':'Separate AI agent; not human teacher or official examination authority',
            'unresolvedReviewFindings':review.get('unresolvedReviewFindings',[]),
        })
write(ROOT/'content/scientific-audit.json',audit)
table=['# پوشش بانک فعال ارتقای ۰٫۱۰','',
       'این نمونهٔ مستقل تألیفی، آرشیو رسمی کنکور یا امتحان نهایی نیست. تعداد از اقلام نوشته‌شده محاسبه می‌شود؛ هدف اجباری ۱۰۰۰ وجود ندارد.',
       '', '| پایه | فصل | نام فصل | چهارگزینه‌ای | آمادگی نهایی |','|---|---|---|---:|---:|']
for g,chapters in CHAPTERS.items():
    for c,title in enumerate(chapters,1):
        table.append(f'| {g} | {c} | {title} | {sum(q["grade"]==g and q["chapter"]==c for q in MCQ)} | {sum(q["grade"]==g and q["chapter"]==c for q in FINALS)} |')
    table.append(f'| **{g}** | **جمع** | | **{sum(q["grade"]==g for q in MCQ)}** | **{sum(q["grade"]==g for q in FINALS)}** |')
table.append(f'| **کل** | | | **{len(MCQ)}** | **{len(FINALS)}** |')
table.extend(['','همهٔ بارم‌ها پیشنهادی و برای خودارزیابی‌اند. هیچ‌کدام کلید یا بارم رسمی آزمون معرفی نمی‌شوند.'])
(ROOT/'content/v2/CHAPTER_COUNTS.md').write_text('\n'.join(table)+'\n')
print(f'Compiled {len(MCQ)} independent MCQs and {len(FINALS)} independent final-study exercises. Original bank archived and checksum verified.')
