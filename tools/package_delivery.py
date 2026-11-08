#!/usr/bin/env python3
"""Package active offline source and delivery files. This never builds or signs Android artifacts."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_VERSION = '0.12.0-preview'
ROOT_FILES = {'.gitignore', 'README.md', 'build.gradle.kts', 'settings.gradle.kts',
              'gradle.properties', 'gradlew', 'gradlew.bat'}
TOOLS = {'validate_bank.py', 'sync_bank.py', 'konkur_coverage.py',
         'test_konkur_coverage.py', 'package_delivery.py'}
HISTORICAL_FILES = {'docs/v2/USER_LICENSE_DECLARATION.md', 'docs/FONT-LICENSE.txt'}
PRIVATE_PARTS = {'.git', '.gradle', '.kotlin', '.idea', '.venv', 'venv',
                 '__pycache__', 'node_modules', 'build', 'dist', 'cache', 'caches',
                 'logs', 'log', 'server', 'admin', 'ops', 'data', 'backups',
                 'retired', '.pytest_cache', '.mypy_cache', '.ruff_cache'}
PRIVATE_SUFFIXES = {'.jks', '.keystore', '.key', '.pem', '.p12', '.pfx', '.asc',
                    '.gpg', '.p8', '.pk8', '.pkcs12', '.ppk', '.db', '.sqlite', '.sqlite3', '.pyc', '.log', '.lck',
                    '.apk', '.aab', '.zip', '.tar', '.gz', '.iml', '.hprof', '.class'}
PRIVATE_NAMES = {'local.properties', 'key.properties', 'keystore.properties',
                 'pass.txt', 'password.txt', '.netrc', '.npmrc', '.pypirc',
                 'google-services.json', 'service-account.json'}
REQUIRED = {'README.md', 'gradlew', 'gradlew.bat', 'build.gradle.kts',
            'settings.gradle.kts', 'gradle.properties', 'app/build.gradle.kts',
            'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
            'app/src/main/AndroidManifest.xml', 'app/src/main/java/ir/hezarzist/MainActivity.kt',
            'content/questions.json', 'content/finals.json', 'content/konkur/coverage.json',
            'content/konkur/questions.json', 'content/konkur/README.md',
            'content/legacy/v1/questions.json', 'content/v2/sources.json',
            'content/v2/licenses.json', 'docs/FONT-LICENSE.txt', 'docs/offline/BUILD.fa.md',
            'docs/offline/COVERAGE.json', 'docs/offline/COVERAGE.fa.md',
            'docs/offline/archive/SOURCE_GAPS.fa.md',
            'release/myket/MYKET_READINESS.json', 'tools/package_delivery.py',
            'tools/validate_bank.py', 'tools/sync_bank.py', 'tools/konkur_coverage.py',
            'app/src/main/java/ir/hezarzist/StudyTimeScreen.kt',
            'app/src/main/java/ir/hezarzist/StudyTimeStore.kt',
            'app/src/main/java/ir/hezarzist/AboutCreatorScreen.kt',
            'content/imports/2026-11-08/report.json',
            'content/imports/2026-11-08/ready-mcq.json',
            'content/imports/2026-11-08/pending-mcq.json',
            'docs/offline/v012/TEST_REPORT.fa.md', 'docs/offline/v012/IMPORT_REPORT.fa.md'}
TEXT_SUFFIXES = {'.kt', '.kts', '.py', '.properties', '.xml', '.json', '.md', '.txt', '.sh', '.bat', '.yml', '.yaml'}


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def private_path(name: str) -> bool:
    path = PurePosixPath(name)
    parts = [p.lower() for p in path.parts]
    basename = path.name.lower()
    return (path.is_absolute() or '..' in path.parts or '\\' in name
            or any(p in PRIVATE_PARTS for p in parts)
            or basename in PRIVATE_NAMES or basename.startswith('.env')
            or any(word in basename for word in ('credential', 'password', 'passwd', 'secret', 'private_key', 'token', 'signing'))
            or basename.startswith(('id_rsa', 'id_ed25519', 'id_ecdsa'))
            or path.suffix.lower() in PRIVATE_SUFFIXES
            or basename.endswith(('.db-wal', '.db-shm', '.sqlite-wal', '.sqlite-shm')))


def source_allowed(name: str) -> bool:
    if private_path(name):
        return False
    path = PurePosixPath(name)
    parts = path.parts
    return (name in ROOT_FILES or name in HISTORICAL_FILES
            or (len(parts) > 1 and parts[0] in {'app', 'gradle'})
            or name in {'content/questions.json', 'content/finals.json', 'content/legacy/v1/questions.json'}
            or name.startswith(('content/konkur/', 'content/v2/', 'content/imports/',
                                'content/legacy/v011/', 'docs/offline/', 'docs/v2/research/', 'release/myket/'))
            or name.startswith('tools/uploaded/')
            or (len(parts) == 2 and parts[0] == 'tools' and parts[1] in TOOLS))


def delivery_allowed(name: str) -> bool:
    return (name.startswith(('docs/offline/', 'docs/v2/research/', 'release/myket/', 'content/konkur/'))
            or name in HISTORICAL_FILES
            or name in {'content/questions.json', 'content/finals.json', 'content/v2/sources.json',
                        'content/v2/licenses.json', 'content/v2/LICENSES.md', 'content/v2/CHAPTER_COUNTS.md'}
            or name in {'content/imports/2026-11-08/report.json',
                        'content/imports/2026-11-08/pending-mcq.json',
                        'content/imports/2026-11-08/duplicates.json',
                        'content/imports/2026-11-08/dedup-review.json',
                        'content/imports/2026-11-08/sources.json'}
            or name.startswith(('app/src/main/assets/diagrams/', 'app/src/main/assets/konkur/images/')))


def inspect_public_text(name: str, data: bytes) -> None:
    if PurePosixPath(name).suffix.lower() not in TEXT_SUFFIXES:
        return
    # Report only the filename, never a possibly sensitive matched value.
    if re.search(rb'(?m)^[ \t]*-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----', data):
        raise ValueError(f'Private key content found in public candidate: {name}')
    if re.search(rb'(?:gh[pousr]_[A-Za-z0-9]{32,}|github_pat_[A-Za-z0-9_]{50,})', data):
        raise ValueError(f'Credential-like content found in public candidate: {name}')
    if name.endswith('.properties'):
        for line in data.decode('utf-8').splitlines():
            if not line.strip() or line.lstrip().startswith(('#', '!')):
                continue
            key, sep, value = line.partition('=')
            if sep and value.strip() and re.search(r'password|secret|token|credential|api[._-]?key|storepass|keypass', key, re.I):
                raise ValueError(f'Credential property found in public candidate: {name}')


def snapshot(root: Path, version: str, output: Path) -> dict[str, bytes]:
    files = {}
    for path in sorted(root.rglob('*')):
        name = path.relative_to(root).as_posix()
        if not path.is_file() or not source_allowed(name):
            continue
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            raise ValueError(f'Symlink/outside-root source is not packaged: {name}')
        if path.resolve().is_relative_to(output):
            continue
        files[name] = path.read_bytes()
        inspect_public_text(name, files[name])
    missing = REQUIRED - files.keys()
    if missing:
        raise ValueError('Required active files are absent or excluded: ' + ', '.join(sorted(missing)))
    text = files['app/build.gradle.kts'].decode('utf-8')
    actual = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not actual or actual[1] != version:
        raise ValueError('--version must match app/build.gradle.kts versionName')
    for name in ('questions.json', 'finals.json'):
        if files[f'content/{name}'] != files.get(f'app/src/main/assets/{name}'):
            raise ValueError(f'Canonical and bundled bank differ: {name}')
    for name in ('coverage.json', 'questions.json'):
        if files[f'content/konkur/{name}'] != files.get(f'app/src/main/assets/konkur/{name}'):
            raise ValueError(f'Canonical and bundled official archive differ: {name}')
    return files


def content_status(files: dict[str, bytes]) -> dict:
    mcq = json.loads(files['content/questions.json'])
    finals = json.loads(files['content/finals.json'])
    official = json.loads(files['content/konkur/questions.json'])
    coverage = json.loads(files['docs/offline/COVERAGE.json'])
    readiness = json.loads(files['release/myket/MYKET_READINESS.json'])
    if not all(isinstance(bank, list) for bank in (mcq, finals, official)):
        raise ValueError('Each question bank must be a JSON array')
    if coverage.get('officialQuestionCount') != len(official):
        raise ValueError('Coverage report official count differs from the official bank')
    for rows in (mcq, finals):
        for row in rows:
            for image in [row.get('image'), row.get('solutionImage'), *row.get('optionImages', [])]:
                if image and f'app/src/main/assets/{image}' not in files:
                    raise ValueError('A question/solution/option image is not in the public snapshot')
    imported = json.loads(files['content/imports/2026-11-08/report.json'])
    return {'officialKonkurQuestions': len(official), 'supplementalMcq': len(mcq),
            'supplementalFinals': len(finals), 'officialArchiveComplete': coverage.get('complete') is True,
            'authoredMcq': sum(q.get('origin', {}).get('kind') == 'authored' for q in mcq),
            'uploadedMcqAdded': imported['addedMcq'], 'uploadedMcqPending': imported['pendingQuestions'],
            'uploadedImportComplete': imported['complete'],
            'myketReadyForPublicSubmission': readiness.get('readyForPublicSubmission') is True,
            'buildAndDeviceTests': 'Not executed or certified by this packaging tool',
            'scientificAndLicenseReview': 'Not certified by this packaging tool'}


def explicit_android(path: Path | None, kind: str, version: str) -> tuple[str, bytes] | None:
    if path is None:
        return None
    path = path.expanduser().resolve(strict=True)
    if not path.is_file() or path.suffix.lower() != f'.{kind}':
        raise ValueError(f'--{kind} must name an existing .{kind} file')
    data = path.read_bytes()
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = set(archive.namelist())
            required = {'AndroidManifest.xml', 'classes.dex'} if kind == 'apk' else {'BundleConfig.pb', 'base/manifest/AndroidManifest.xml'}
            if not required <= names or archive.testzip() is not None:
                raise ValueError('missing Android entries or corrupt ZIP')
    except (zipfile.BadZipFile, ValueError) as exc:
        raise ValueError(f'--{kind} is not a readable Android {kind.upper()} archive') from exc
    # Shape and CRC checks do not establish signature validity or version identity.
    return f'artifacts/HezarZist-{version}.{kind}', data


def manifest(files: dict[str, bytes], version: str, kind: str, status: dict) -> bytes:
    value = {'schemaVersion': 1, 'version': version, 'kind': kind, 'contentStatus': status,
             'artifactValidation': 'Explicit Android paths only; no build, signing, installation or store submission is performed.',
             'files': {name: {'bytes': len(data), 'sha256': digest(data)} for name, data in sorted(files.items())}}
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def zip_payload(files: dict[str, bytes], prefix: str) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(f'{prefix}/{name}', (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            mode = 0o100755 if name == 'gradlew' else 0o100644
            info.external_attr = mode << 16
            archive.writestr(info, data)
    return buffer.getvalue()


def check_archive(data: bytes, prefix: str, expected: dict[str, bytes], allow_artifacts: bool = False) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError('Package contains duplicate or corrupt ZIP entries')
        if set(names) != {f'{prefix}/{n}' for n in expected}:
            raise ValueError('Package entry inventory differs from the planned snapshot')
        for name, content in expected.items():
            artifact = allow_artifacts and (name.startswith('artifacts/') or name.startswith('source/'))
            if not artifact and name != 'MANIFEST.json' and private_path(name):
                raise ValueError(f'Excluded file unexpectedly reached archive: {name}')
            if archive.read(f'{prefix}/{name}') != content:
                raise ValueError(f'Archive byte mismatch: {name}')
        stored = json.loads(archive.read(f'{prefix}/MANIFEST.json'))
        for name, record in stored['files'].items():
            content = archive.read(f'{prefix}/{name}')
            if digest(content) != record['sha256'] or len(content) != record['bytes']:
                raise ValueError(f'Archive manifest mismatch: {name}')


def policy_self_check() -> None:
    blocked = ['app/build/output.apk', '.git/config', 'app/src/main/assets/password.txt',
               'docs/offline/.env.example', 'gradle/credentials.properties', 'app/id_rsa',
               'app/signing.jks', 'server/app.py', 'content/konkur/cache/source.pdf',
               'docs/offline/logs/test.txt', 'app/local.properties', '../escape.txt',
               'content/konkur/secret-key.json', 'app/src/main/assets/state.db-wal']
    assert all(not source_allowed(name) for name in blocked)
    assert source_allowed('content/konkur/sources/final-key.pdf')
    assert source_allowed('app/src/main/java/ir/hezarzist/LocalBackup.kt')
    assert not source_allowed('content/legacy/v1/grade10.json')
    assert not delivery_allowed('content/legacy/v1/questions.json')
    for name, value in [('gradle.properties', b'storePassword=fixture'),
                        ('docs/offline/fixture.txt', b'-----BEGIN PRIVATE KEY-----')]:
        try:
            inspect_public_text(name, value)
        except ValueError:
            pass
        else:
            raise ValueError('Private-content self-check did not reject fixture')


def atomic_write(path: Path, data: bytes) -> None:
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.hezarzist-', delete=False) as handle:
        temporary = Path(handle.name)
        handle.write(data)
    try:
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', default=DEFAULT_VERSION, help='must match Android versionName')
    parser.add_argument('--dist', type=Path, default=ROOT / 'dist', help='output directory; never read automatically for APK/AAB')
    parser.add_argument('--apk', type=Path, help='explicit already-built APK; no signature/test claim is made')
    parser.add_argument('--aab', type=Path, help='optional explicit already-built Android App Bundle')
    parser.add_argument('--dry-run', action='store_true', help='check content and report plan without creating output files')
    parser.add_argument('--self-check', action='store_true', help='also verify exclusion policy negative fixtures; archive checks always run')
    args = parser.parse_args(argv)
    try:
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}', args.version):
            raise ValueError('--version must be a safe filename component')
        output = args.dist.expanduser().resolve()
        if output == ROOT or ROOT.is_relative_to(output):
            raise ValueError('--dist must not be the project root or its ancestor')
        if args.self_check:
            policy_self_check()
        files = snapshot(ROOT, args.version, output)
        status = content_status(files)
        artifacts = [item for item in (explicit_android(args.apk, 'apk', args.version),
                                      explicit_android(args.aab, 'aab', args.version)) if item]
        base_name = f'HezarZist-{args.version}'
        source_files = dict(files)
        source_files['MANIFEST.json'] = manifest(source_files, args.version, 'active-offline-source', status)
        source_data = zip_payload(source_files, base_name)
        check_archive(source_data, base_name, source_files)
        delivery = {name: data for name, data in files.items() if delivery_allowed(name)}
        delivery[f'source/{base_name}-source.zip'] = source_data
        delivery.update(artifacts)
        delivery['DELIVERY.fa.md'] = (f'# تحویل {args.version}\n\n'
            f'سؤال رسمی کنکورِ تطبیق‌شده: {status["officialKonkurQuestions"]}؛ کل تست فعال: {status["supplementalMcq"]}؛ '
            f'تمرین نهایی تألیفی: {status["supplementalFinals"]}.\n\n'
            f'تست افزوده از فایل‌های ارسالی: {status["uploadedMcqAdded"]}؛ تست نیازمند بررسی: {status["uploadedMcqPending"]}. '
            'ورود ۱۶۵ صفحهٔ تشریحی هنوز کامل نشده و شمار سؤال/زیرسؤال آن‌ها نامعلوم است.\n\n'
            'بستهٔ source را جدا استخراج کنید؛ سورس کامل فعال و راهنمای ساخت در آن است. بانک‌های قابل ویرایش، '
            'همهٔ شکل‌های سؤال/گزینه/پاسخ، قلم‌ها، منابع و ابزارها در ZIP سورس هستند؛ تصاویر حجیم در لایهٔ بیرونی تکرار نمی‌شوند. '
            'بانک مستقل به‌همراه تصاویر نیز جدا تحویل می‌شود.\n\n'
            'APK/AAB فقط در صورت ارائهٔ صریح مسیر در artifacts قرار می‌گیرد؛ نبود فایل در آن مسیر به معنی '
            'تحویل APK نیست. بسته‌بندی، ساخت، امضا، نصب، آزمون دستگاه یا پذیرش فروشگاه را تأیید نمی‌کند. '
            'گزارش واقعی هر APK و SHA-256 آن باید جدا بررسی شود.\n\n'
            'در وضعیت فعلی آرشیو رسمی ناقص و ضوابط رسمی مایکت خوانده‌نشده‌اند؛ این نسخه پیش‌نمایش است. '
            'جزئیات در docs/offline/archive/SOURCE_GAPS.fa.md و release/myket/MYKET_READINESS.json آمده است.\n').encode('utf-8')
        delivery['MANIFEST.json'] = manifest(delivery, args.version, 'offline-delivery-preview', status)
        delivery_data = zip_payload(delivery, base_name)
        check_archive(delivery_data, base_name, delivery, allow_artifacts=True)
        bank_files = {name: data for name, data in files.items()
                      if name.startswith(('content/imports/', 'content/v2/'))
                      or name in {'content/questions.json', 'content/finals.json'}
                      or name.startswith(('app/src/main/assets/imports/', 'app/src/main/assets/diagrams/'))}
        bank_files['BANK.fa.md'] = (
            '# بانک قابل ویرایش و تصاویر\n\n'
            '`content/questions.json` بانک فعال تست‌ها و `content/finals.json` تمرین‌های نهایی است. '
            '`content/imports/2026-11-08/ready-mcq.json` فقط تست‌های تازهٔ فعال را دارد. '
            'اقلام pending و all-extracted بانک بازی نیستند؛ بدون تکمیل بررسی وارد برنامه نشوند.\n\n'
            'مسیر نسبی تصاویر JSON از `app/src/main/assets/` شروع می‌شود؛ تصویر سؤال، گزینه و پاسخ‌نامه '
            'با همان ساختار پوشه همراه است. گزارش منابع، تکرارها، صفحات و وضعیت مجوز نیز در content قرار دارد.\n\n'
            'برای ساخت پس از ویرایش از ZIP سورس استفاده کنید و tools/sync_bank.py را اجرا کنید. '
            'ممیزی کلید منبع و حفظ بانک قبلی مربوط به snapshot تحویل است؛ تغییر علمی عمدیِ پاسخ باید '
            'با ثبت منشأ اصلاح و بازبینی دوباره همراه باشد. کلید/تحلیل آموزشی را به منبع رسمی نسبت ندهید.\n'
        ).encode('utf-8')
        bank_files['MANIFEST.json'] = manifest(bank_files, args.version, 'editable-bank-and-images', status)
        bank_data = zip_payload(bank_files, base_name)
        check_archive(bank_data, base_name, bank_files)
        products = {f'{base_name}-source.zip': source_data, f'{base_name}-delivery.zip': delivery_data,
                    f'{base_name}-bank.zip': bank_data,
                    f'{base_name}-package-manifest.json': delivery['MANIFEST.json']}
        summary = {'version': args.version, 'dryRun': args.dry_run, 'sourceFileCount': len(files),
                   'explicitAndroidArtifacts': [name for name, _ in artifacts], 'contentStatus': status,
                   'checks': 'allowlist, exclusions, canonical/bundled equality, required files, CRC and per-file SHA-256',
                   'products': {name: {'bytes': len(data), 'sha256': digest(data)} for name, data in products.items()}}
        if not args.dry_run:
            output.mkdir(parents=True, exist_ok=True)
            for name, data in products.items():
                atomic_write(output / name, data)
            checksums = ''.join(f'{digest(data)}  {name}\n' for name, data in sorted(products.items()))
            atomic_write(output / f'{base_name}-SHA256SUMS.txt', checksums.encode('utf-8'))
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, AssertionError, UnicodeError, json.JSONDecodeError, zipfile.BadZipFile) as exc:
        print(f'Packaging failed: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
