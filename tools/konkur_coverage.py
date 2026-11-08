#!/usr/bin/env python3
"""Audit and import the independently sourced official archive. No network or OCR.

Missing metadata is unknown, never inferred. A structural pass does not mean that
the archive is complete or that a person has scientifically reviewed its content.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tempfile
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
YEARS = set(range(1398, 1406))
CHAPTERS = {10: 7, 11: 9, 12: 8}
REVIEW_KEYS = ("transcription", "biology", "key")
RIGHTS = {"permission_verified", "user_declared_permission"}


def read_json(path: Path):
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def nonempty(value) -> bool:
    return isinstance(value, str) and bool(value.strip())


def integer(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def safe_path(root: Path, value) -> Path | None:
    if not isinstance(value, str) or not value or "\\" in value or "%" in value:
        return None
    rel = PurePosixPath(value)
    if rel.is_absolute() or any(p in ("", ".", "..") for p in value.split("/")):
        return None
    if len(value) > 240 or not all(re.fullmatch(r"[A-Za-z0-9_.-]+", p) for p in rel.parts):
        return None
    candidate = (root / value).resolve()
    if not candidate.is_relative_to(root.resolve()):
        return None
    return candidate


def normalized(text: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", text).replace("ي", "ی").replace("ك", "ک").split())


def fingerprint(question: dict) -> str:
    # Option permutations must share a canonical record; text is never rewritten.
    payload = [normalized(question.get("stem", "")), sorted(normalized(x) for x in question.get("options", []) if isinstance(x, str)), question.get("imageSha256")]
    return hashlib.sha256(json.dumps(payload, ensure_ascii=False).encode()).hexdigest()


def _audit(coverage: dict, questions: list, root: Path = ROOT) -> dict:
    errors, blockers, rows, pdf_pages = [], [], [], {}

    def error(label, message):
        errors.append(f"{label}: {message}")

    def source(doc, label, pdf=True):
        if not isinstance(doc, dict):
            error(label, "source document is missing")
            return False
        path = safe_path(root, doc.get("path"))
        valid = path is not None and path.is_file()
        if not valid:
            error(label, "local source file is absent or path is unsafe")
        elif not re.fullmatch(r"[a-f0-9]{64}", str(doc.get("sha256", ""))) or digest(path) != doc["sha256"]:
            error(label, "source SHA-256 does not match")
            valid = False
        elif pdf and (path.suffix.lower() != ".pdf" or not path.read_bytes().startswith(b"%PDF-")):
            error(label, "original booklet/key must be a PDF file")
            valid = False
        elif pdf:
            try:
                from pypdf import PdfReader
                reader = PdfReader(path, strict=True)
                if reader.is_encrypted or len(reader.pages) == 0:
                    raise ValueError("encrypted or empty PDF")
                pdf_pages[doc["path"]] = len(reader.pages)
            except Exception as exc:
                error(label, f"source PDF cannot be parsed: {exc}")
                valid = False
        if not nonempty(doc.get("url")) or not str(doc.get("url")).startswith("https://"):
            error(label, "original HTTPS publication URL is missing")
        if doc.get("verified") is not True or not nonempty(doc.get("verifiedBy")) or not nonempty(doc.get("verifiedAt")):
            error(label, "independent source matching review is not recorded")
        return valid

    if not isinstance(coverage, dict) or not isinstance(questions, list):
        return {"schemaVersion": 1, "complete": False, "errors": ["coverage must be an object; questions must be a list"], "blockers": [], "years": [], "booklets": [], "officialQuestionCount": 0, "scorableQuestionCount": 0, "occurrenceCount": 0}
    if coverage.get("schemaVersion") != 1:
        error("coverage", "unsupported schemaVersion")
    scope = coverage.get("scope", {})
    if scope.get("firstYear") != 1398 or scope.get("lastYear") != 1405 or scope.get("includeAllHeldSessions") is not True or set(scope.get("countryScopes", [])) != {"inside_iran", "outside_iran"}:
        error("scope", "must explicitly cover all held sessions inside/outside Iran, 1398–1405")
    years = coverage.get("years", [])
    if not isinstance(years, list) or len(years) != 8 or {y.get("year") for y in years if isinstance(y, dict)} != YEARS:
        error("years", "exactly one inventory record per year 1398–1405 is required")
        years = [y for y in years if isinstance(y, dict)] if isinstance(years, list) else []
    exams = coverage.get("exams", [])
    if not isinstance(exams, list):
        error("exams", "must be a list")
        exams = []
    exam_map, booklet_map = {}, {}
    curriculum = {}
    for book in coverage.get("curriculumSources", []):
        if not isinstance(book, dict) or not nonempty(book.get("id")) or book["id"] in curriculum:
            error("curriculumSources", "unique textbook source ids are required")
            continue
        curriculum[book["id"]] = book
        if book.get("grade") not in CHAPTERS or not nonempty(book.get("edition")):
            error(book["id"], "verified textbook grade and print year/edition are required")
        source(book.get("document"), book["id"] + "/textbook")
    for exam in exams:
        if not isinstance(exam, dict):
            error("exam", "must be an object")
            continue
        eid = exam.get("id")
        if not nonempty(eid) or eid in exam_map:
            error("exam", "unique nonempty id is required")
            continue
        exam_map[eid] = exam
        if exam.get("year") not in YEARS or exam.get("location") not in ("inside_iran", "outside_iran"):
            error(eid, "year or location is invalid")
        for field in ("session", "syllabus", "heldDate", "publicationStatus"):
            if not nonempty(exam.get(field)):
                error(eid, f"verified {field} is required, never infer a session/date")
        if exam.get("publicationStatus") != "officially_published":
            error(eid, "only an actually held and officially published exam may enter the archive")
        source(exam.get("heldEvidence"), eid + "/heldEvidence", pdf=False)
        booklets = exam.get("booklets", [])
        if not isinstance(booklets, list) or not booklets:
            error(eid, "at least one original booklet is required")
            continue
        for booklet in booklets:
            if not isinstance(booklet, dict):
                error(eid, "booklet must be an object")
                continue
            bid = booklet.get("id")
            key = (eid, bid)
            if not nonempty(bid) or key in booklet_map:
                error(eid, "booklet id is missing or repeated")
                continue
            booklet_map[key] = booklet
            label = f"{eid}/{bid}"
            source(booklet.get("questionPdf"), label + "/questionPdf")
            source(booklet.get("finalKeyPdf"), label + "/finalKeyPdf")
            if booklet.get("keyStatus") != "final":
                error(label, "a preliminary key cannot be used")
            rights = booklet.get("rights", {})
            if rights.get("status") not in RIGHTS or rights.get("distributionAllowed") is not True or not nonempty(rights.get("evidence")):
                error(label, "redistribution scope/evidence must be recorded (user declaration is labeled separately)")
            rights_file = safe_path(root, rights.get("evidence"))
            if rights_file is None or not rights_file.is_file():
                error(label, "local redistribution evidence/declaration file is absent or unsafe")
            for field in ("expectedNumbers", "extractedNumbers"):
                nums = booklet.get(field)
                if not isinstance(nums, list) or not nums or any(not integer(n) or n <= 0 for n in nums) or len(set(nums)) != len(nums):
                    error(label, f"{field} must be unique positive numbers read from the actual biology booklet")
            keys = booklet.get("finalKeys", {})
            if not isinstance(keys, dict) or set(keys) != {str(n) for n in booklet.get("expectedNumbers", [])}:
                error(label, "finalKeys must account for every expected biology question number")
            elif any(value is not None and (not integer(value) or value not in range(4)) for value in keys.values()):
                error(label, "final key entries must be original-option indices 0–3 or null for officially deleted")
            if booklet.get("reviewStatus") != "verified":
                blockers.append(f"{label}: booklet transcription/key concordance review is pending")

    inventory_rows = []
    for item in years:
        year = item.get("year")
        known_exams = {eid for eid, exam in exam_map.items() if exam.get("year") == year}
        verified = item.get("inventoryStatus") == "verified"
        if verified:
            proof = item.get("inventoryEvidence", [])
            if not isinstance(proof, list) or not proof:
                error(str(year), "verified annual inventory needs authoritative evidence")
            else:
                for index, doc in enumerate(proof):
                    source(doc, f"{year}/inventoryEvidence/{index}", pdf=False)
            held = item.get("heldExamIds")
            if not isinstance(held, list) or len(held) != len(set(held)) or set(held) != known_exams:
                error(str(year), "heldExamIds must match all verified exam records, including location/syllabus variants")
            if item.get("insideOutsideReviewed") is not True or item.get("allSessionsReviewed") is not True or item.get("allBookletVariantsReviewed") is not True:
                error(str(year), "all sessions, both locations and booklet variants need explicit inventory review")
        else:
            if item.get("inventoryStatus") != "unknown" or item.get("heldExamIds") is not None:
                error(str(year), "unverified inventory must remain unknown with heldExamIds=null")
            blockers.append(f"{year}: authoritative all-held-exams inventory is unknown")
        inventory_rows.append({"year": year, "inventoryStatus": "verified" if verified else "unknown", "verifiedExamCount": len(known_exams), "expectedQuestionOccurrences": None if not verified else sum(len(b.get("expectedNumbers", [])) for (eid, _), b in booklet_map.items() if eid in known_exams), "importedQuestionOccurrences": 0, "reviewStatus": "pending"})
    if coverage.get("inventoryComplete") is not True:
        blockers.append("The authoritative inventory is not declared complete.")
    if not exams:
        blockers.append("No original official exam PDF/final key pair has been verified and imported.")

    ids, content_fingerprints, occurrences = set(), {}, {}
    scorable_count = 0
    for q in questions:
        if not isinstance(q, dict):
            error("question", "must be an object")
            continue
        qid = q.get("id")
        label = str(qid)
        if not nonempty(qid) or qid in ids:
            error(label, "canonical question id is missing or duplicated")
        ids.add(qid)
        if q.get("origin") != "official_konkur":
            error(label, "authored/institute questions cannot enter the official archive")
        for field in ("stem", "topic", "explanation", "trap", "editionNotes"):
            if not nonempty(q.get(field)):
                error(label, f"{field} must be nonempty; edition comparison must be explicit")
        for field in ("options", "optionExplanations"):
            values = q.get(field)
            if not isinstance(values, list) or len(values) != 4 or not all(nonempty(x) for x in values):
                error(label, f"{field} must contain four nonempty strings")
        if isinstance(q.get("options"), list) and len(set(map(str, q["options"]))) != 4:
            error(label, "answer options must be distinct")
        grade = q.get("grade")
        if grade not in CHAPTERS or not integer(q.get("chapter")) or not 1 <= q.get("chapter", 0) <= CHAPTERS.get(grade, 0):
            error(label, "primary grade/chapter is invalid")
        if q.get("difficulty") not in ("easy", "medium", "hard"):
            error(label, "difficulty must be easy/medium/hard")
        if not isinstance(q.get("tags"), list) or not q["tags"] or not all(nonempty(t) for t in q["tags"]):
            error(label, "all related topics must be tagged")
        refs = q.get("references")
        if not isinstance(refs, list) or not refs or any(not isinstance(r, dict) or not all(nonempty(r.get(k)) for k in ("sourceId", "section", "edition")) for r in refs):
            error(label, "textbook references require sourceId, section and edition")
        elif any(r["sourceId"] not in curriculum or curriculum[r["sourceId"]].get("edition") != r["edition"] for r in refs):
            error(label, "textbook references must match verified local official textbook editions")
        review = q.get("review", {})
        if any(review.get(k) != "verified" for k in REVIEW_KEYS) or not nonempty(review.get("reviewedBy")) or not nonempty(review.get("reviewedAt")):
            error(label, "transcription, independent biology and final-key reviews must all be recorded")
        if q.get("scorable") is True:
            scorable_count += 1
            if not integer(q.get("answer")) or q["answer"] not in range(4):
                error(label, "scorable question needs exactly one valid answer")
        elif q.get("scorable") is False:
            if q.get("answer") is not None or not nonempty(q.get("deletionReason")):
                error(label, "officially deleted question needs answer=null and a deletion reason")
        else:
            error(label, "scorable must be an explicit boolean")
        image = q.get("image")
        if not isinstance(q.get("requiresImage"), bool):
            error(label, "requiresImage must be an explicit boolean after figure review")
        stem = q.get("stem", "")
        needs_image = q.get("requiresImage") is True or any(phrase in stem for phrase in ("شکل زیر", "نمودار زیر", "با توجه به شکل", "در شکل", "مطابق شکل", "تصویر زیر"))
        if needs_image and not image:
            error(label, "image-dependent question has no bundled image")
        if image:
            path = safe_path(root / "app/src/main/assets", image)
            if path is None or not path.is_file() or not str(image).startswith("konkur/images/"):
                error(label, "official diagram must be a safe local konkur/images path")
            elif path.suffix.lower() not in (".png", ".jpg", ".jpeg", ".webp") or digest(path) != q.get("imageSha256"):
                error(label, "diagram SHA/format is not verified")
            else:
                try:
                    from PIL import Image
                    with Image.open(path) as im:
                        if not (1 <= im.width <= 8000 and 1 <= im.height <= 8000 and im.width * im.height <= 24_000_000):
                            raise ValueError("unsafe dimensions")
                        im.verify()
                except Exception as exc:
                    error(label, f"diagram is not a decodable bounded image: {exc}")
        fp = fingerprint(q)
        if fp in content_fingerprints:
            error(label, f"same exact content appears under another canonical id: {content_fingerprints[fp]}")
        content_fingerprints[fp] = qid
        history = q.get("correctionHistory")
        if not isinstance(history, list):
            error(label, "correctionHistory must be an explicit list, including when empty")
        else:
            for index, correction in enumerate(history):
                if not isinstance(correction, dict) or not all(nonempty(correction.get(k)) for k in ("reason", "publishedAt")):
                    error(label, "each official correction needs reason/publication metadata")
                    continue
                source(correction.get("evidence"), f"{label}/correction/{index}", pdf=False)
                if not all(k in correction for k in ("examId", "bookletId", "number", "previousAnswer", "revisedAnswer")) or any(correction.get(k) is not None and (not integer(correction.get(k)) or correction[k] not in range(4)) for k in ("previousAnswer", "revisedAnswer")):
                    error(label, "correction history needs an original occurrence and previous/revised original key (0–3 or null)")
                correction_booklet = booklet_map.get((correction.get("examId"), correction.get("bookletId")))
                if correction_booklet is None or correction_booklet.get("finalKeys", {}).get(str(correction.get("number"))) != correction.get("revisedAnswer"):
                    error(label, "correction revisedAnswer differs from the recorded final key")
        occs = q.get("occurrences")
        if not isinstance(occs, list) or not occs:
            error(label, "at least one actual booklet occurrence is required")
            continue
        normal_occurrences = 0
        for occurrence in occs:
            if not isinstance(occurrence, dict):
                error(label, "occurrence must be an object")
                continue
            eid, bid, number = (occurrence.get(k) for k in ("examId", "bookletId", "number"))
            key = (eid, bid, number)
            if key in occurrences:
                error(label, f"duplicate booklet question occurrence {key}")
            occurrences[key] = qid
            booklet = booklet_map.get((eid, bid))
            if booklet is None or number not in booklet.get("expectedNumbers", []):
                error(label, f"occurrence does not belong to a verified expected question number: {key}")
                continue
            if not integer(occurrence.get("pdfPage")) or occurrence["pdfPage"] <= 0:
                error(label, "original booklet PDF page is required")
            elif occurrence["pdfPage"] > pdf_pages.get(booklet.get("questionPdf", {}).get("path"), 0):
                error(label, "question PDF page is outside the verified booklet")
            order = occurrence.get("optionOrder")
            if not isinstance(order, list) or len(order) != 4 or any(not integer(i) for i in order) or set(order) != set(range(4)):
                error(label, "optionOrder must map four original option indices to canonical indices")
                continue
            original_options = occurrence.get("originalOptions")
            if occurrence.get("originalStem") != q.get("stem") or original_options != [q.get("options", [None] * 4)[i] for i in order]:
                error(label, "original stem/options do not match canonical text and recorded permutation")
            final_key = booklet.get("finalKeys", {}).get(str(number))
            if occurrence.get("finalAnswer") != final_key:
                error(label, "occurrence final answer differs from verified original final key")
            if final_key is None:
                if occurrence.get("scorable") is not False or not nonempty(occurrence.get("deletionReason")):
                    error(label, "officially deleted occurrence needs scorable=false and a deletion reason")
                if not history or not any(isinstance(h, dict) and (h.get("examId"), h.get("bookletId"), h.get("number")) == key for h in history):
                    error(label, "official deletion requires published correction/deletion evidence")
            elif integer(final_key) and final_key in range(4):
                normal_occurrences += 1
                if occurrence.get("scorable") is not True or q.get("scorable") is not True or q.get("answer") != order[final_key]:
                    error(label, "canonical answer/scoring does not follow the final key after option permutation")
        if q.get("scorable") != (normal_occurrences > 0):
            error(label, "canonical scorable must reflect at least one non-deleted verified occurrence")

    for (eid, bid), booklet in booklet_map.items():
        expected = set(booklet.get("expectedNumbers", []))
        extracted = set(booklet.get("extractedNumbers", []))
        imported = {number for (exam, book, number) in occurrences if exam == eid and book == bid}
        missing = sorted(expected - imported)
        extra = sorted(imported - expected)
        if expected != extracted:
            error(f"{eid}/{bid}", f"extracted question-number set differs; missing={sorted(expected-extracted)}, extra={sorted(extracted-expected)}")
        if missing or extra:
            blockers.append(f"{eid}/{bid}: missing imported numbers={missing}; unexpected={extra}")
        rows.append({"examId": eid, "year": exam_map[eid].get("year"), "session": exam_map[eid].get("session"), "location": exam_map[eid].get("location"), "syllabus": exam_map[eid].get("syllabus"), "bookletId": bid, "expected": len(expected), "extracted": len(extracted), "imported": len(imported), "missingNumbers": missing, "unexpectedNumbers": extra, "reviewStatus": booklet.get("reviewStatus", "pending"), "questionSource": booklet.get("questionPdf", {}), "finalKeySource": booklet.get("finalKeyPdf", {}), "rights": booklet.get("rights", {})})
    for row in inventory_rows:
        selected = [b for b in rows if b["year"] == row["year"]]
        row["importedQuestionOccurrences"] = sum(b["imported"] for b in selected)
        if row["inventoryStatus"] == "verified" and selected and all(b["reviewStatus"] == "verified" and not b["missingNumbers"] and not b["unexpectedNumbers"] for b in selected):
            row["reviewStatus"] = "verified"
    complete = not errors and not blockers and bool(questions) and bool(exams)
    return {"schemaVersion": 1, "asOf": coverage.get("asOf"), "complete": complete, "structurallyValid": not errors, "officialQuestionCount": len(questions), "scorableQuestionCount": scorable_count, "occurrenceCount": len(occurrences), "verifiedExamCount": len(exam_map), "verifiedBookletCount": len(booklet_map), "years": inventory_rows, "booklets": rows, "errors": errors, "blockers": blockers, "rightsNotice": "User-declared redistribution permission is a declaration, not an independently verified institutional certificate.", "scientificReviewNotice": "Review markers are recorded editorial evidence; automatic validation does not establish scientific correctness."}


def audit(coverage: dict, questions: list, root: Path = ROOT) -> dict:
    """Malformed nested JSON fails closed before any import or bundle mutation."""
    try:
        return _audit(coverage, questions, root)
    except (ValueError, TypeError, KeyError, IndexError, AttributeError) as exc:
        return {"schemaVersion": 1, "complete": False, "structurallyValid": False,
                "officialQuestionCount": len(questions) if isinstance(questions, list) else 0,
                "scorableQuestionCount": 0, "occurrenceCount": 0, "years": [], "booklets": [],
                "errors": [f"Malformed archive shape: {type(exc).__name__}: {exc}"], "blockers": []}


def atomic_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json_payload(value)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, prefix=".konkur-", delete=False) as handle:
        handle.write(payload)
        temporary = Path(handle.name)
    temporary.replace(path)


def json_payload(value) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def markdown(report: dict) -> str:
    lines = ["# پوشش آرشیو رسمی زیست کنکور", "", "وضعیت: **" + ("کامل" if report["complete"] else "ناقص؛ نسخهٔ نهایی نیست") + "**", "", f"سؤال رسمی یکتا: {report['officialQuestionCount']}؛ قابل نمره‌دهی: {report['scorableQuestionCount']}؛ رخداد در دفترچه‌ها: {report['occurrenceCount']}.", "", "پیش‌نویس سالانه، آزمونِ برگزارشده محسوب نمی‌شود. «نامعلوم» یعنی فهرست نوبت‌ها، محل برگزاری، کد دفترچه و تعداد مورد انتظار تأیید نشده؛ تعداد مورد انتظار صفر نیست.", "", "| سال | فهرست همهٔ نوبت‌ها | آزمون تأییدشده | تعداد مورد انتظار در دفترچه‌ها | واردشده | بازبینی |", "|---|---|---:|---:|---:|---|"]
    for row in report["years"]:
        expected = row["expectedQuestionOccurrences"] if row["expectedQuestionOccurrences"] is not None else "نامعلوم"
        lines.append(f"| {row['year']} | {'تأییدشده' if row['inventoryStatus']=='verified' else 'نامعلوم'} | {row['verifiedExamCount']} | {expected} | {row['importedQuestionOccurrences']} | {'تأییدشده' if row['reviewStatus']=='verified' else 'انجام نشده'} |")
    lines += ["", "| سال | نوبت | محل | دفترچه | مورد انتظار | استخراج | واردشده | شماره‌های مفقود | بازبینی |", "|---|---|---|---|---:|---:|---:|---|---|"]
    for row in report["booklets"]:
        lines.append(f"| {row['year']} | {row['session']} | {row['location']} | {row['bookletId']} | {row['expected']} | {row['extracted']} | {row['imported']} | {row['missingNumbers']} | {row['reviewStatus']} |")
    if not report["booklets"]:
        lines.append("| — | هیچ دفترچهٔ اصلی و کلید نهایی تأیید نشده است | — | — | نامعلوم | ۰ | ۰ | نامعلوم | انجام نشده |")
    lines += ["", "اعتبار ساختاری: " + ("قبول" if report.get("structurallyValid") else "رد"), "", "## موانع تکمیل", ""]
    lines += ["- " + value for value in report["blockers"]] or ["- ندارد"]
    if report["errors"]:
        lines += ["", "## خطاهای داده", ""] + ["- " + value for value in report["errors"]]
    lines += ["", "اعتبارسنجی خودکار، تأیید علمی و تطبیق با اصل دفترچه را جایگزین نمی‌کند. اجازهٔ بازنشر اعلام‌شده توسط مالک پروژه، گواهی مستقل مؤسسه نیست. سؤال‌های تألیفی و نهایی مستقل در این تعدادها محاسبه نمی‌شوند.", ""]
    return "\n".join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--complete", action="store_true", help="fail unless all 1398–1405 held exams and every booklet number are verified")
    parser.add_argument("--write", action="store_true", help="write coverage reports and copy the audited archive into bundled assets")
    parser.add_argument("--import-staging", type=Path, help="reviewed JSON list of canonical official records; never OCR or generated content")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    coverage_path = root / "content/konkur/coverage.json"
    questions_path = root / "content/konkur/questions.json"
    try:
        coverage, questions = read_json(coverage_path), read_json(questions_path)
        if args.import_staging:
            incoming = read_json(args.import_staging)
            if not isinstance(incoming, list) or not incoming:
                raise ValueError("staging must be a nonempty list of reviewed official questions")
            existing_ids = {q["id"] for q in questions}
            if any(not isinstance(q, dict) or q.get("id") in existing_ids for q in incoming):
                raise ValueError("staging contains an existing id; explicit editorial migration is required")
            candidate = questions + incoming
            report = audit(coverage, candidate, root)
            if report["errors"]:
                raise ValueError("staging rejected before mutation: " + "; ".join(report["errors"]))
            if args.complete and not report["complete"]:
                raise ValueError("staging rejected before mutation: --complete requires the complete verified archive")
            # Incompleteness in other booklets is allowed; malformed imports are not.
            backup = questions_path.with_suffix(".pre-import.json")
            shutil.copyfile(questions_path, backup)
            atomic_json(questions_path, candidate)
            questions = candidate
        report = audit(coverage, questions, root)
        report["questionFileSha256"] = hashlib.sha256(json_payload(questions).encode()).hexdigest()
        report["coverageFileSha256"] = hashlib.sha256(json_payload(coverage).encode()).hexdigest()
        if args.write:
            if report["errors"]:
                raise ValueError("refusing to bundle an invalid official archive: " + "; ".join(report["errors"]))
            atomic_json(root / "docs/offline/COVERAGE.json", report)
            target = root / "docs/offline/COVERAGE.fa.md"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(markdown(report), encoding="utf-8")
            for name, value in (("coverage.json", coverage), ("questions.json", questions), ("coverage-audit.json", report)):
                atomic_json(root / "app/src/main/assets/konkur" / name, value)
        print(json.dumps({k: report[k] for k in ("complete", "structurallyValid", "officialQuestionCount", "scorableQuestionCount", "occurrenceCount", "errors", "blockers")}, ensure_ascii=False, indent=2))
        return 1 if report["errors"] or (args.complete and not report["complete"]) else 0
    except (ValueError, TypeError, KeyError, OSError, json.JSONDecodeError) as exc:
        print(f"Archive validation failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
