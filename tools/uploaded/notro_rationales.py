"""Exact source rationale segmentation for user-supplied NOTRO compilations.

No absent rationale is fabricated. Returned None means an educational analysis is
still needed; the full source solution remains available separately.
"""
from __future__ import annotations
import re
import unicodedata
from typing import Any

_DIGITS = str.maketrans('۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩', '01234567890123456789')

def normalize(value: str) -> str:
    return unicodedata.normalize('NFKC', value).translate(_DIGITS).replace('ي', 'ی').replace('ك', 'ک').replace('\u200e','').replace('\u200f','').replace('\u200c',' ')

_SECTION = re.compile(r'بررسی\s+(?:سایر|دیگر|بقی[هۀ]|هم[هۀ])\s+(?:ی\s+)?(?:گزین[هۀ](?:\s*ها(?:ی)?)?|موارد)\s*[:：]?', re.M)
# Source explanation lines use both «گزینۀ (۱):» and simply «۱)».
_LABEL = re.compile(r'(?:^|\n)\s*(?:گزین[هۀ](?:\s*[ٔ‌]?\s*های?)?\s*[ٔ‌]?[\s\(\)\[\]«»:：،-]*([1-4])[\s\(\)\[\]«»:：،-]*|[\(\[]?\s*([1-4])\s*[\)\](]\s*[:：]?)\s*', re.M)
_SHARED_LABEL = re.compile(r'(?:^|\n)\s*گزین[هۀ](?:\s*[ٔ‌]?\s*های?)\s*([1-4](?:\s*(?:و|،|,)\s*[1-4])+)\s*[:：(\)]\s*', re.M)

def _tidy(text: str) -> str:
    text = text.strip(' \n\t:：')
    text = re.sub(r'(?m)^\s*بررسی\s+(?:سایر|دیگر)\s+گزین[هۀ]\s*ها\s*:?\s*$', '', text).strip()
    return text


def rationale_details(question: dict[str, Any]) -> list[dict[str, Any]]:
    """Return exactly four records, preserving supplied language or an explicit gap.

    Correct-answer reasoning may use the source's opening explanatory paragraph.
    A single general paragraph is never copied into all four distractor slots.
    """
    lines = question.get('solutionLines') or []
    raw = '\n'.join(str(line.get('text', '')) for line in lines).strip()
    if not raw:
        raw = str(question.get('solution') or '').strip()
    text = normalize(raw)
    answer = question.get('answer')
    results = [{'text': None, 'kind': 'missing_individual_analysis', 'source': 'supplied_compilation_solution'} for _ in range(4)]
    if not text or answer not in range(4):
        return results
    matches: list[tuple[int, int, list[int]]] = []
    for m in _SHARED_LABEL.finditer(text):
        matches.append((m.start(),m.end(),[int(n)-1 for n in re.findall('[1-4]',m.group(1))]))
    for m in _LABEL.finditer(text):
        if any(a <= m.start() < b for a,b,_ in matches):
            continue
        matches.append((m.start(),m.end(),[int(m.group(1) or m.group(2))-1]))
    matches.sort()
    for pos, (_, end, indices) in enumerate(matches):
        next_start = matches[pos+1][0] if pos+1 < len(matches) else len(text)
        value = _tidy(text[end:next_start])
        if value and any(ch.isalpha() for ch in value):
            for index in indices:
                results[index] = {'text': value, 'kind': 'supplied_individual_analysis', 'source': 'supplied_compilation_solution'}
    first_section = _SECTION.search(text)
    leading_end = min([m[0] for m in matches] + ([first_section.start()] if first_section else []) + [len(text)])
    leading = _tidy(text[:leading_end])
    if leading and any(ch.isalpha() for ch in leading) and not results[answer]['text']:
        results[answer] = {'text': leading, 'kind': 'supplied_correct_answer_explanation', 'source': 'supplied_compilation_solution'}
    return results


def split_option_rationales(question: dict[str, Any]) -> list[str | None]:
    return [row['text'] for row in rationale_details(question)]

# Short alias for wrappers.
split_rationales = split_option_rationales
