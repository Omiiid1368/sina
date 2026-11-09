#!/usr/bin/env python3
"""Validate and synchronize both canonical active v2 banks; never rebuild legacy content."""
import json,shutil
from pathlib import Path
from validate_bank import validate,validate_finals,validate_provenance
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets'
m=json.loads((root/'content/questions.json').read_text());f=json.loads((root/'content/finals.json').read_text())
errors=validate(m,assets)[0]+validate_finals(f,assets)[0]+validate_provenance(m+f)
if errors:raise SystemExit('\n'.join(errors))
for name in ['questions.json','finals.json']:shutil.copyfile(root/'content'/name,assets/name)
print(f'Validated and synchronized {len(m)} MCQ and {len(f)} finals questions.')
