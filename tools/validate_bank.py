#!/usr/bin/env python3
"""Validate bundled supplemental content and the separate official archive; never pad counts."""
import argparse
import collections
import hashlib
import json
import math
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHAPTERS = {10: 7, 11: 9, 12: 8}
TYPES = {'true_false','fill_blank','short','definition_comparison','essay','diagram_label','matching'}


def normal(text):
    return re.sub(r'\s+', ' ', str(text).replace('ي','ی').replace('ك','ک').replace('\u200c',' ')).strip()


def clean(value):
    if isinstance(value,str): return re.sub(r'\\u([0-9a-fA-F]{4})',lambda m:chr(int(m[1],16)),value)
    if isinstance(value,list): return [clean(x) for x in value]
    if isinstance(value,dict): return {k:clean(v) for k,v in value.items()}
    return value


def image_valid(path, asset_dir):
    if not isinstance(path,str) or not path or Path(path).is_absolute() or '..' in Path(path).parts:
        return False
    target=(asset_dir/path).resolve()
    return target.is_relative_to(asset_dir.resolve()) and target.is_file() and target.stat().st_size>0 and target.suffix.lower() in {'.png','.jpg','.jpeg','.webp'}


def common(q, errors, asset_dir, ids, stems, finals=False):
    label=q.get('id','<missing>')
    def check(ok,detail):
        if not ok: errors.append(f'{label}: {detail}')
    check(isinstance(label,str) and label.startswith(('V2-','UP-')) and label not in ids,'invalid, legacy or duplicate ID')
    ids.add(label)
    check(type(q.get('grade')) is int and q['grade'] in CHAPTERS,'invalid grade')
    check(type(q.get('chapter')) is int and 1<=q['chapter']<=CHAPTERS.get(q.get('grade'),0),'invalid chapter')
    for field in ['topic','stem']:
        check(isinstance(q.get(field),str) and len(q[field].strip())>2,f'missing {field}')
    stem=(normal(q.get('stem','')),tuple(sorted(normal(x) for x in q.get('options',[])))) if not finals else normal(q.get('stem',''))
    check(stem not in stems,'duplicate normalized stem')
    stems.add(stem)
    origin=q.get('origin',{})
    check(isinstance(origin,dict) and origin.get('kind') in {'authored','official_konkur','official_final','coordinated','institution','user_supplied_compilation','user_supplied_exam_booklet'},'missing or unknown origin')
    if origin.get('kind')=='authored':
        check(not any(origin.get(k) for k in ['year','session','questionNumber','bookletCode','institution']),'authored question has misleading exam attribution')
    license_info=q.get('license',{})
    check(license_info.get('distributionAllowed') is True,'redistribution not allowed')
    check(bool(q.get('sourceId')) and bool(q.get('licenseId')),'missing source/license catalog IDs')
    check(license_info.get('id')==q.get('licenseId'),'license reference mismatch')
    if q.get('image'):
        check(image_valid(q['image'],asset_dir),'missing/unsafe/unsupported image')
        rights=q.get('imageSource',{})
        check(bool(rights.get('sourceId')) and bool(rights.get('licenseId') or q.get('imageLicenseId')),'image provenance/rights missing')
    for extra in [q.get('solutionImage')]+q.get('optionImages',[]):
        if extra: check(image_valid(extra,asset_dir),'missing solution/option image')
    if q.get('requiresImage') is True:
        check(bool(q.get('image')),'required question image is absent')
    if any(s in q.get('stem','') for s in ['در شکل','در نمودار','مطابق شکل','نمودار زیر','شکل زیر','تصویر زیر','با توجه به شکل']):
        check(bool(q.get('image')),'image-dependent stem has no image')
    check(not any(s in q.get('stem','').lower() for s in ['placeholder','lorem ipsum','todo','جای‌نگهدار']),'placeholder content')
    return check


def validate(bank,asset_dir):
    errors=[];counts=collections.Counter();ids=set();stems=set()
    if not isinstance(bank,list) or not bank:
        return ['Active MCQ bank must be a nonempty array'],counts
    for q in bank:
        if not isinstance(q,dict): errors.append('MCQ must be object');continue
        check=common(q,errors,asset_dir,ids,stems)
        options=q.get('options',[]);reasons=q.get('optionExplanations',[])
        check(isinstance(options,list) and len(options)==4 and all(isinstance(x,str) and x.strip() for x in options),'exactly four nonempty options required')
        check(len({normal(x) for x in options})==4,'options not distinct')
        valid_answer=type(q.get('answer')) is int and q['answer'] in range(4)
        check(valid_answer,'answer must be one index 0..3')
        check(isinstance(reasons,list) and len(reasons)==4 and all(isinstance(x,str) and len(x.strip())>=8 for x in reasons),'four substantive option analyses required')
        check(isinstance(q.get('explanation'),str) and len(q['explanation'].strip())>=8,'missing key explanation')
        if valid_answer and isinstance(reasons,list) and len(reasons)==4:
            check(q['explanation']==reasons[q['answer']],'key rationale differs from corresponding option')
        check(q.get('difficulty') in {'easy','medium','hard','unknown'},'invalid difficulty')
        if q.get('origin',{}).get('kind')=='authored':check(isinstance(q.get('trap'),str) and len(q['trap'].strip())>=8,'educational trap missing')
        ref=q.get('reference',{})
        check(bool(ref.get('sourceId')) and len(ref.get('section',''))>5,'book/topic reference missing')
        check(isinstance(q.get('tags'),list) and bool(q['tags']),'topic tags missing')
        members=q.get('collections',['main'])
        check(isinstance(members,list) and bool(members) and len(members)==len(set(members)) and set(members)<={'main','qb_very_difficult'},'invalid collection membership')
        if 'qb_very_difficult' in members:
            evidence=q.get('qbSelection',{})
            check(evidence.get('colorLegendVerified') is True and evidence.get('printedNumberRedVerified') is True and bool(evidence.get('legendReference')) and bool(evidence.get('questionPageReference')),'QB selection lacks verified legend/printed-number evidence')
        counts[q.get('grade'),q.get('chapter')]+=1
    return errors,counts


def validate_finals(bank,asset_dir):
    errors=[];counts=collections.Counter();ids=set();stems=set()
    if not isinstance(bank,list) or not bank:return ['Finals bank must be nonempty array'],counts
    for q in bank:
        if not isinstance(q,dict):errors.append('Final question must be object');continue
        check=common(q,errors,asset_dir,ids,stems,True)
        check(q.get('type') in TYPES,'unknown final-question type')
        check(isinstance(q.get('answer'),str) and len(q['answer'].strip())>5,'criterion answer missing')
        rubric=q.get('rubric',[])
        good=isinstance(rubric,list) and bool(rubric) and all(isinstance(r,dict) and r.get('id') and isinstance(r.get('criterion'),str) and len(r['criterion'].strip())>3 and type(r.get('points')) in [int,float] and math.isfinite(r['points']) and r['points']>0 for r in rubric)
        check(good,'invalid rubric')
        if good:
            check(len({r['id'] for r in rubric})==len(rubric),'rubric IDs duplicate')
            check(type(q.get('totalPoints')) in [int,float] and math.isfinite(q['totalPoints']) and math.isclose(sum(r['points'] for r in rubric),q['totalPoints'],abs_tol=.001),'rubric total mismatch')
        check(q.get('marking') in {'suggested','official'},'marking provenance missing')
        source=q.get('source',{})
        check(source.get('kind') in {'authored','official_final','coordinated'},'final exam provenance missing')
        if source.get('kind')=='authored':check(q.get('marking')=='suggested','authored rubric falsely official')
        if q.get('type')=='true_false':check(type(q.get('truth')) is bool,'true/false expected truth missing')
        if q.get('type')=='diagram_label':check(bool(q.get('image')),'diagram-label question needs actual image')
        counts[q.get('grade'),q.get('chapter')]+=1
    return errors,counts


def catalog(path):
    raw=json.loads(path.read_text())
    if isinstance(raw,list):return {x['id']:x for x in raw}
    rows=raw.get('items',raw.get('sources',raw.get('licenses',[])))
    return {x['id']:x for x in rows}


def validate_provenance(questions):
    errors=[]
    sources=catalog(ROOT/'content/v2/sources.json');licenses=catalog(ROOT/'content/v2/licenses.json')
    for q in questions:
        sid=q.get('sourceId');lid=q.get('licenseId');label=q.get('id','?')
        src=sources.get(sid);lic=licenses.get(lid)
        if not src:errors.append(f'{label}: source absent from catalog')
        if not lic or lic.get('distributionAllowed') is not True:errors.append(f'{label}: catalog denies distribution')
        elif q.get('license',{}).get('status')!=lic.get('status'):errors.append(f'{label}: license status/catalog mismatch')
        if q.get('image') and q.get('imageSource',{}).get('licenseId') not in licenses:errors.append(f'{label}: image license absent from catalog')
        kind=q.get('origin',{}).get('kind')
        if kind in {'user_supplied_compilation','user_supplied_exam_booklet'}:
            if not src or not re.fullmatch('[a-f0-9]{64}',src.get('pdfSha256','')):errors.append(f'{label}: uploaded PDF fingerprint missing')
            proof=q.get('provenance',{})
            if not src or proof.get('pdfSha256')!=src.get('pdfSha256') or type(proof.get('pdfPage')) is not int or not 1<=proof['pdfPage']<=src.get('pages',0):errors.append(f'{label}: invalid uploaded source/page')
            answer=q.get('answerSource',{})
            if answer.get('kind')=='educational_answer':
                review=q.get('review',{})
                if answer.get('suppliedKey') is not False or answer.get('officialKeyVerified') is not False or review.get('singleAnswerApproved') is not True or review.get('allFourAnalysesApproved') is not True:
                    errors.append(f'{label}: educational answer provenance/review is invalid')
            elif answer.get('kind')!='supplied_compilation_key':errors.append(f'{label}: answer provenance missing')
            if lic and lic.get('status') not in {'permission_verified','user_declared_permission'}:errors.append(f'{label}: uploaded content redistribution not authorized')
        elif kind!='authored':
            origin=q.get('origin',{})
            if not origin.get('year') or not origin.get('session') or not origin.get('questionNumber') or not origin.get('url'):
                errors.append(f'{label}: actual exam metadata incomplete')
            if not src or src.get('documentVerified') is not True or src.get('finalKeyVerified') is not True:
                errors.append(f'{label}: actual exam document/final key not verified')
            if lic and lic.get('status') not in {'permission_verified','user_declared_permission'}:
                errors.append(f'{label}: third-party redistribution not authorized')
    legacy_path=ROOT/'content/legacy/v1/questions.json'
    if not legacy_path.exists():errors.append('Recoverable archived v1 bank missing')
    else:
        legacy=json.loads(legacy_path.read_text());oldids={q['id'] for q in legacy};oldstems={normal(q['stem']) for q in legacy}
        for q in questions:
            if q['id'] in oldids or (q.get('origin',{}).get('kind')=='authored' and normal(q['stem']) in oldstems):errors.append(f"{q['id']}: legacy question reused in active bank")
    return errors


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--release',action='store_true');parser.add_argument('--merge',action='store_true');args=parser.parse_args()
    if args.merge:raise SystemExit('Legacy --merge is disabled. Edit the canonical v2 banks and use tools/sync_bank.py.')
    assets=ROOT/'app/src/main/assets'
    mcq=json.loads((ROOT/'content/questions.json').read_text());finals=json.loads((ROOT/'content/finals.json').read_text())
    e1,c1=validate(mcq,assets);e2,c2=validate_finals(finals,assets)
    errors=e1+e2+validate_provenance(mcq+finals)
    if {q['id'] for q in mcq}&{q['id'] for q in finals}:errors.append('MCQ/finals IDs overlap')
    for label,counts in [('MCQ',c1),('finals',c2)]:
        missing=[f'{g}/{ch}' for g,n in CHAPTERS.items() for ch in range(1,n+1) if not counts[g,ch]]
        if missing:errors.append(f'{label} missing chapters: {missing}')
    if set(q.get('type') for q in finals)!=TYPES:errors.append('Not all seven finals question types covered')
    from konkur_coverage import audit as audit_konkur
    official=json.loads((ROOT/'content/konkur/questions.json').read_text())
    coverage=json.loads((ROOT/'content/konkur/coverage.json').read_text())
    archive=audit_konkur(coverage,official,ROOT)
    errors += ['Official archive: '+e for e in archive['errors']]
    warnings=['Supplemental authored practice is separate from official Konkur coverage.'] + archive['blockers']
    imports=json.loads((ROOT/'content/imports/2026-11-08/report.json').read_text())
    if imports['receivedMcq'] != imports['addedMcq'] + imports['pendingQuestions'] + imports['duplicateQuestions']:
        errors.append('Uploaded cohort received/added/pending/duplicate counters do not reconcile.')
    update=json.loads((ROOT/'content/imports/v013/report.json').read_text())
    actual_main=sum('main' in q.get('collections',['main']) for q in mcq)
    actual_hard=sum('qb_very_difficult' in q.get('collections',['main']) for q in mcq)
    actual_shared=sum(set(q.get('collections',['main']))=={'main','qb_very_difficult'} for q in mcq)
    if len(mcq)!=update['baselineMain']-update['retiredExisting']+update['newAdded'] or len(mcq)!=update['uniqueMcqCount']:
        errors.append('Content-update baseline/withdrawn/added/unique counts do not reconcile.')
    if (actual_main,actual_hard,actual_shared)!=(update['mainCount'],update['veryDifficultCount'],update['sharedCount']):
        errors.append('Collection counts differ from the active bank.')
    if update['newNumberedNonQbQuestions']!=update['newAdded']+update['newDuplicates']+update['newSkipped']:
        errors.append('New source received/added/duplicate/skipped counts do not reconcile.')
    if not update['complete']: warnings.append('Content targets not reached; see content/imports/v013/report.json. QB eligibility is unknown without the supplied color legend.')
    if not imports['complete']:
        warnings.append('Uploaded source import is incomplete; written-question count is not yet known.')
    if args.release:
        readiness=ROOT/'release/myket/MYKET_READINESS.json'
        state=json.loads(readiness.read_text()) if readiness.exists() else {}
        if not archive['complete']: errors.append('Complete official Konkur archive is not verified; authored questions cannot replace missing exam items.')
        if not imports['complete']: errors.append('All submitted PDFs/questions/subquestions have not been fully imported and reconciled.')
        if not update['complete']: errors.append('Requested main/QB content targets and QB color evidence are unresolved.')
        for key in ['officialGuideVerified','storeAccountReady','archiveCoverageComplete','finalOfflineApkTestReportVerified','localBackupDeviceTestVerified','aboutCreatorDeviceTestVerified','studyTimeDeviceTestVerified','submittedSourcesReconciliationComplete']:
            if state.get(key) is not True:errors.append(f'Myket release readiness unresolved: {key}')
    report={'mcqCount':len(mcq),'finalsCount':len(finals),'uniqueIds':len({q['id'] for q in mcq+finals}),'mcqChapters':{f'{g}/{c}':n for (g,c),n in sorted(c1.items())},'finalsChapters':{f'{g}/{c}':n for (g,c),n in sorted(c2.items())},'errors':errors,'warnings':warnings,'requiresExactly1000':False,'officialKonkurQuestions':archive['officialQuestionCount'],'officialCoverageComplete':archive['complete'],'releaseGate':args.release,'sha256':{'mcq':hashlib.sha256((ROOT/'content/questions.json').read_bytes()).hexdigest(),'finals':hashlib.sha256((ROOT/'content/finals.json').read_bytes()).hexdigest()}}
    out=ROOT/'docs/offline';out.mkdir(parents=True,exist_ok=True)
    (out/('release-validation.json' if args.release else 'bank-validation.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if errors:raise SystemExit(1)

if __name__=='__main__':main()
