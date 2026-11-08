"""Synthetic fixtures stay in temporary directories; never in the active bank."""
import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest

from pypdf import PdfWriter

import konkur_coverage as archive


class CoverageTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="konkur-test-only-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        folder = self.root / "content/konkur/sources"
        folder.mkdir(parents=True)
        self.pdf = folder / "TEST_ONLY.pdf"
        (folder / "TEST_RIGHTS.txt").write_text("UNIT_TEST_DECLARATION_NOT_A_REAL_LICENSE")
        writer = PdfWriter()
        writer.add_blank_page(width=600, height=800)
        with self.pdf.open("wb") as handle:
            writer.write(handle)
        self.proof = {"path": "content/konkur/sources/TEST_ONLY.pdf", "sha256": archive.digest(self.pdf), "url": "https://example.invalid/TEST_ONLY_NOT_A_REAL_EXAM.pdf", "verified": True, "verifiedBy": "UNIT_TEST_FIXTURE", "verifiedAt": "2000-01-01"}
        self.coverage = {"schemaVersion": 1, "scope": {"firstYear": 1398, "lastYear": 1405, "includeAllHeldSessions": True, "countryScopes": ["inside_iran", "outside_iran"]}, "asOf": "2000-01-01", "inventoryComplete": True, "years": [], "exams": [], "curriculumSources": [{"id": "TEST_TEXTBOOK", "grade": 10, "edition": "TEST_EDITION", "document": copy.deepcopy(self.proof)}]}
        occurrences = []
        for year in sorted(archive.YEARS):
            eid = f"TEST_ONLY_EXAM_{year}"
            self.coverage["years"].append({"year": year, "inventoryStatus": "verified", "inventoryEvidence": [copy.deepcopy(self.proof)], "heldExamIds": [eid], "insideOutsideReviewed": True, "allSessionsReviewed": True, "allBookletVariantsReviewed": True})
            booklet = {"id": "TEST_BOOKLET_A", "questionPdf": copy.deepcopy(self.proof), "finalKeyPdf": copy.deepcopy(self.proof), "keyStatus": "final", "expectedNumbers": [1], "extractedNumbers": [1], "finalKeys": {"1": 1}, "reviewStatus": "verified", "rights": {"status": "user_declared_permission", "distributionAllowed": True, "evidence": "content/konkur/sources/TEST_RIGHTS.txt"}}
            self.coverage["exams"].append({"id": eid, "year": year, "session": "TEST_NOT_A_REAL_SESSION", "location": "inside_iran", "syllabus": "TEST_SYLLABUS", "heldDate": "TEST_DATE", "publicationStatus": "officially_published", "heldEvidence": copy.deepcopy(self.proof), "booklets": [booklet]})
            occurrences.append({"examId": eid, "bookletId": "TEST_BOOKLET_A", "number": 1, "pdfPage": 1, "optionOrder": [0, 1, 2, 3], "originalStem": "TEST_FIXTURE_ONLY_NOT_PRODUCTION", "originalOptions": ["TEST_A", "TEST_B", "TEST_C", "TEST_D"], "finalAnswer": 1, "scorable": True})
        self.questions = [{"id": "TEST_CANONICAL_ONLY", "origin": "official_konkur", "stem": "TEST_FIXTURE_ONLY_NOT_PRODUCTION", "options": ["TEST_A", "TEST_B", "TEST_C", "TEST_D"], "answer": 1, "scorable": True, "explanation": "TEST_ONLY", "optionExplanations": ["TEST_REASON_A", "TEST_REASON_B", "TEST_REASON_C", "TEST_REASON_D"], "trap": "TEST_ONLY", "grade": 10, "chapter": 1, "topic": "TEST_ONLY", "difficulty": "medium", "tags": ["TEST_ONLY"], "references": [{"sourceId": "TEST_TEXTBOOK", "section": "TEST_SECTION", "edition": "TEST_EDITION"}], "editionNotes": "TEST_ONLY", "requiresImage": False, "image": None, "review": {"transcription": "verified", "biology": "verified", "key": "verified", "reviewedBy": "UNIT_TEST_FIXTURE", "reviewedAt": "2000-01-01"}, "correctionHistory": [], "occurrences": occurrences}]

    def audit(self):
        return archive.audit(self.coverage, self.questions, self.root)

    def test_empty_unknown_is_valid_but_never_complete(self):
        self.coverage["exams"] = []
        self.coverage["curriculumSources"] = []
        self.coverage["inventoryComplete"] = False
        self.coverage["years"] = [{"year": y, "inventoryStatus": "unknown", "inventoryEvidence": [], "heldExamIds": None} for y in sorted(archive.YEARS)]
        self.questions = []
        report = self.audit()
        self.assertTrue(report["structurallyValid"], report["errors"])
        self.assertFalse(report["complete"])
        self.assertTrue(all(y["expectedQuestionOccurrences"] is None for y in report["years"]))
        self.write_files()
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(archive.main(["--root", str(self.root), "--complete"]), 1)

    def test_complete_fixture_counts_canonical_once(self):
        report = self.audit()
        self.assertTrue(report["complete"], report)
        self.assertEqual(report["officialQuestionCount"], 1)
        self.assertEqual(report["occurrenceCount"], 8)

    def test_alternate_booklet_is_permutation_not_duplicate(self):
        b = copy.deepcopy(self.coverage["exams"][0]["booklets"][0])
        b["id"] = "TEST_BOOKLET_B"
        b["finalKeys"]["1"] = 3
        self.coverage["exams"][0]["booklets"].append(b)
        o = copy.deepcopy(self.questions[0]["occurrences"][0])
        o.update(bookletId="TEST_BOOKLET_B", optionOrder=[2, 0, 3, 1], originalOptions=["TEST_C", "TEST_A", "TEST_D", "TEST_B"], finalAnswer=3)
        self.questions[0]["occurrences"].append(o)
        report = self.audit()
        self.assertTrue(report["complete"], report)
        self.assertEqual(report["occurrenceCount"], 9)
        self.assertEqual(report["officialQuestionCount"], 1)

    def test_wrong_permutation_answer_is_rejected(self):
        self.questions[0]["occurrences"][0]["optionOrder"] = [1, 0, 2, 3]
        self.questions[0]["occurrences"][0]["originalOptions"] = ["TEST_B", "TEST_A", "TEST_C", "TEST_D"]
        self.assertTrue(any("canonical answer" in e for e in self.audit()["errors"]))

    def test_missing_expected_number_blocks_completion(self):
        b = self.coverage["exams"][0]["booklets"][0]
        b.update(expectedNumbers=[1, 2], extractedNumbers=[1, 2], finalKeys={"1": 1, "2": 2})
        report = self.audit()
        self.assertTrue(report["structurallyValid"], report)
        self.assertFalse(report["complete"])
        self.assertEqual(report["booklets"][0]["missingNumbers"], [2])

    def test_extraction_numbers_must_equal_original_expected(self):
        self.coverage["exams"][0]["booklets"][0]["extractedNumbers"] = [1, 2]
        self.assertTrue(any("extracted question-number set" in e for e in self.audit()["errors"]))

    def test_duplicate_occurrence_rejected(self):
        self.questions[0]["occurrences"].append(copy.deepcopy(self.questions[0]["occurrences"][0]))
        self.assertTrue(any("duplicate booklet question occurrence" in e for e in self.audit()["errors"]))

    def test_duplicate_text_under_another_id_rejected(self):
        clone = copy.deepcopy(self.questions[0]); clone["id"] = "TEST_OTHER_ID"
        self.questions.append(clone)
        self.assertTrue(any("same exact content" in e for e in self.audit()["errors"]))

    def test_authored_substitute_rejected(self):
        self.questions[0]["origin"] = "authored_independent"
        self.assertTrue(any("authored/institute" in e for e in self.audit()["errors"]))

    def test_key_sha_and_bogus_pdf_rejected(self):
        self.pdf.write_bytes(b"%PDF-not-a-readable-document")
        for exam in self.coverage["exams"]:
            for b in exam["booklets"]:
                b["questionPdf"]["sha256"] = archive.digest(self.pdf)
        errors = self.audit()["errors"]
        self.assertTrue(any("cannot be parsed" in e for e in errors))
        self.assertTrue(any("SHA-256" in e for e in errors))

    def test_actual_booklet_page_required(self):
        self.questions[0]["occurrences"][0]["pdfPage"] = 2
        self.assertTrue(any("outside the verified booklet" in e for e in self.audit()["errors"]))

    def test_year_inventory_proof_and_all_variants_required(self):
        self.coverage["years"][0]["inventoryEvidence"] = []
        self.coverage["years"][1]["allBookletVariantsReviewed"] = False
        errors = self.audit()["errors"]
        self.assertTrue(any("authoritative evidence" in e for e in errors))
        self.assertTrue(any("booklet variants" in e for e in errors))

    def delete_occurrence(self, index):
        q = self.questions[0]; o = q["occurrences"][index]
        self.coverage["exams"][index]["booklets"][0]["finalKeys"]["1"] = None
        o.update(finalAnswer=None, scorable=False, deletionReason="TEST_OFFICIAL_DELETION")
        q["correctionHistory"].append({"examId": o["examId"], "bookletId": o["bookletId"], "number": 1, "previousAnswer": 1, "revisedAnswer": None, "reason": "TEST_OFFICIAL_DELETION", "publishedAt": "TEST_DATE", "evidence": copy.deepcopy(self.proof)})

    def test_deleted_questions_preserved_but_not_graded(self):
        for index in range(8):
            self.delete_occurrence(index)
        self.questions[0].update(answer=None, scorable=False, deletionReason="TEST_ALL_OCCURRENCES_DELETED")
        report = self.audit()
        self.assertTrue(report["complete"], report)
        self.assertEqual(report["officialQuestionCount"], 1)
        self.assertEqual(report["scorableQuestionCount"], 0)

    def test_one_deleted_variant_does_not_erase_valid_occurrences(self):
        self.delete_occurrence(0)
        report = self.audit()
        self.assertTrue(report["complete"], report)
        self.assertEqual(report["scorableQuestionCount"], 1)

    def test_deletion_requires_correction_proof(self):
        self.delete_occurrence(0)
        self.questions[0]["correctionHistory"] = []
        self.assertTrue(any("deletion evidence" in e for e in self.audit()["errors"]))

    def test_image_required_and_unsafe_paths_rejected(self):
        self.questions[0]["requiresImage"] = True
        self.assertTrue(any("no bundled image" in e for e in self.audit()["errors"]))
        self.questions[0]["image"] = "konkur/images/../../../outside.png"
        self.assertTrue(any("safe local" in e for e in self.audit()["errors"]))

    def test_valid_bundled_diagram_and_hash_are_required(self):
        from PIL import Image
        path = self.root / "app/src/main/assets/konkur/images/TEST_ONLY.png"
        path.parent.mkdir(parents=True)
        Image.new("RGB", (20, 20), color="white").save(path)
        self.questions[0].update(requiresImage=True, image="konkur/images/TEST_ONLY.png", imageSha256=archive.digest(path))
        self.assertTrue(self.audit()["complete"])
        path.write_bytes(b"TEST_NOT_AN_IMAGE")
        self.questions[0]["imageSha256"] = archive.digest(path)
        self.assertTrue(any("not a decodable" in e for e in self.audit()["errors"]))

    def test_unreviewed_science_or_wrong_book_edition_rejected(self):
        self.questions[0]["review"]["biology"] = "pending"
        self.questions[0]["references"][0]["edition"] = "UNKNOWN"
        errors = self.audit()["errors"]
        self.assertTrue(any("biology" in e for e in errors))
        self.assertTrue(any("textbook editions" in e for e in errors))

    def write_files(self):
        archive.atomic_json(self.root / "content/konkur/coverage.json", self.coverage)
        archive.atomic_json(self.root / "content/konkur/questions.json", self.questions)

    def test_invalid_staging_never_mutates_bank_or_assets(self):
        self.write_files()
        path = self.root / "content/konkur/questions.json"
        previous = path.read_bytes()
        incoming = copy.deepcopy(self.questions[0]); incoming["id"] = "TEST_INVALID_IMPORT"; incoming["options"] = ["TEST_ONLY"]
        staging = self.root / "TEST_STAGING.json"
        archive.atomic_json(staging, [incoming])
        with contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
            status = archive.main(["--root", str(self.root), "--import-staging", str(staging), "--write"])
        self.assertNotEqual(status, 0)
        self.assertEqual(path.read_bytes(), previous)
        self.assertFalse((self.root / "app/src/main/assets/konkur/questions.json").exists())

    def test_reviewed_staging_imports_atomically_and_bundles_matching_sha(self):
        incoming = copy.deepcopy(self.questions)
        self.questions = []
        self.write_files()
        staging = self.root / "TEST_STAGING.json"
        archive.atomic_json(staging, incoming)
        with contextlib.redirect_stdout(io.StringIO()):
            status = archive.main(["--root", str(self.root), "--import-staging", str(staging), "--complete", "--write"])
        self.assertEqual(status, 0)
        self.assertEqual(archive.read_json(self.root / "content/konkur/questions.pre-import.json"), [])
        assets = self.root / "app/src/main/assets/konkur"
        report = archive.read_json(assets / "coverage-audit.json")
        self.assertEqual(report["questionFileSha256"], archive.digest(assets / "questions.json"))
        self.assertEqual(report["coverageFileSha256"], archive.digest(assets / "coverage.json"))

    def test_complete_import_flag_prevents_partial_archive_mutation(self):
        incoming = copy.deepcopy(self.questions)
        self.questions = []
        self.coverage["inventoryComplete"] = False
        self.write_files()
        staging = self.root / "TEST_STAGING.json"
        archive.atomic_json(staging, incoming)
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            status = archive.main(["--root", str(self.root), "--import-staging", str(staging), "--complete"])
        self.assertEqual(status, 2)
        self.assertEqual(archive.read_json(self.root / "content/konkur/questions.json"), [])

    def test_malformed_nested_input_fails_closed(self):
        self.questions[0]["occurrences"][0]["optionOrder"] = [{"not": "an index"}]
        report = self.audit()
        self.assertFalse(report["complete"])
        self.assertTrue(report["errors"])

    def test_source_path_traversal_is_rejected(self):
        self.coverage["exams"][0]["booklets"][0]["questionPdf"]["path"] = "../outside.pdf"
        self.assertTrue(any("unsafe" in e for e in self.audit()["errors"]))


if __name__ == "__main__":
    unittest.main()
