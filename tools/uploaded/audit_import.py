#!/usr/bin/env python3
"""Audit the supplied-PDF cohort and bundled pixels, without certifying biology or rights."""
import hashlib
import json
from collections import Counter
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
BATCH = ROOT / 'content/imports/2026-11-08'
ASSETS = ROOT / 'app/src/main/assets'
def read(path): return json.loads(path.read_text())
def main():
    bank = read(ROOT / 'content/questions.json')
    finals = read(ROOT / 'content/finals.json')
    ready = read(BATCH / 'ready-mcq.json')
    pending = read(BATCH / 'pending-mcq.json')
    extracted = read(BATCH / 'all-extracted-mcq.json')
    duplicates = read(BATCH / 'duplicates.json')
    report = read(BATCH / 'report.json')
    keys = read(ROOT / 'app/src/androidTest/assets/upload-compilation-keys.json')
    by_id = {q['id']: q for q in bank}
    assert len(by_id) == len(bank)
    assert len(extracted) == len(keys) == report['receivedMcq'] == 1077
    assert len(ready) == report['addedMcq']
    assert len(pending) == report['pendingQuestions']
    assert len(duplicates) == report['duplicateQuestions']
    assert len(ready) + len(pending) + len(duplicates) == len(extracted)
    assert not report['complete'] and not report['finalQuestionCountKnown']
    assert report['unreconciledFinalPages'] == 165
    assert not ({q['id'] for q in pending} & set(by_id))
    assert not ({q['id'] for q in duplicates} & set(by_id))
    assert all(qid not in by_id for qid in report['excludedDisputedQuestionIds'])
    for q in ready:
        assert q == by_id[q['id']]
        assert len(q['options']) == len(set(q['options'])) == 4
        assert q['answer'] == keys[q['id']] and q['answer'] in range(4)
        assert len(q['optionExplanations']) == 4 and all(q['optionExplanations'])
        assert q['provenance']['pdfSha256'] in {s['sha256'] for s in report['files']}
        assert q['origin']['kind'] == 'user_supplied_compilation'
        assert not q['review']['independentHumanEditorialApproval']
        for img in [q['image'], q['solutionImage'], *q['optionImages']]:
            if img: assert (ASSETS / img).is_file(), img
    for name, active in [('questions.json', bank), ('finals.json', finals)]:
        previous = read(ROOT / 'content/legacy/v011' / name)
        mapping = {q['id']: q for q in active}
        assert all(mapping[q['id']] == q for q in previous)
        assert (ROOT / 'content' / name).read_bytes() == (ASSETS / name).read_bytes()
    images = read(BATCH / 'image-integrity.json')['files']
    for item in images:
        path = ASSETS / item['encoded']
        with Image.open(path) as image:
            rgb = image.convert('RGB')
            assert rgb.size == (item['width'], item['height'])
            assert hashlib.sha256(rgb.tobytes()).hexdigest() == item['pixelSha256'], str(path)
    out = {'passed': True, 'activeMcq': len(bank), 'activeFinals': len(finals),
           'previousMcqPreserved': 96, 'previousFinalsPreserved': 72,
           'receivedMcq': len(extracted), 'addedMcq': len(ready), 'pendingMcq': len(pending),
           'duplicateMcq': len(duplicates), 'sourceDisputedHeld': len(report['excludedDisputedQuestionIds']),
           'sourceKeysMatched': len(ready), 'imagePixelHashesMatched': len(images),
           'addedByGrade': dict(sorted(Counter(q['grade'] for q in ready).items())),
           'scientificEditorialApproval': False, 'officialArchiveCertified': False,
           'writtenQuestionCountKnown': False, 'unreconciledWrittenPages': 165}
    print(json.dumps(out, ensure_ascii=False, indent=2))
if __name__ == '__main__': main()
