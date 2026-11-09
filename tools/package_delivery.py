#!/usr/bin/env python3
"""Package current Android source, editable banks and an explicitly supplied APK."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tempfile
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_VERSION = '0.14.0'
ASSETS = 'app/src/main/assets/'
ROOT_FILES = {'.gitignore', 'README.md', 'build.gradle.kts', 'settings.gradle.kts',
              'gradle.properties', 'gradlew', 'gradlew.bat'}
TOOLS = {'validate_bank.py', 'sync_bank.py', 'konkur_coverage.py',
         'test_konkur_coverage.py', 'package_delivery.py'}
PUBLIC_DOCS = {'docs/release/BUILD.fa.md', 'docs/release/TEST_REPORT.fa.md',
               'docs/release/COUNTS.md', 'docs/FONT-LICENSE.txt', 'docs/SOURCES.fa.md',
               'content/v2/LICENSES.md', 'docs/v2/USER_LICENSE_DECLARATION.md'}
BANK_DOCS = {name for name in PUBLIC_DOCS if not name.startswith('docs/release/')}
CATALOG_FILES = {'content/v2/sources.json', 'content/v2/licenses.json',
                 'content/sources.json',
                 'content/release/summary.json', 'content/release/qb-color-guide.json'}
BANK_FILES = {'content/questions.json', 'content/finals.json'}
OFFICIAL_FILES = {'content/konkur/questions.json', 'content/konkur/coverage.json'}
RUNTIME_JSON = {ASSETS + name for name in ('questions.json', 'finals.json',
                'content-counts.json', 'konkur/questions.json',
                'konkur/coverage.json', 'konkur/coverage-audit.json')}
MEDIA_SUFFIXES = {'.png', '.webp', '.jpg', '.jpeg', '.gif', '.svg', '.avif',
                  '.mp3', '.ogg', '.wav', '.mp4', '.webm'}
PRIVATE_PARTS = {'.git', '.gradle', '.kotlin', '.idea', '.venv', 'venv',
                 '__pycache__', 'node_modules', 'build', 'dist', 'cache', 'caches',
                 'logs', 'log', 'backend', 'server', 'admin', 'ops', 'backups',
                 'retired', '.pytest_cache', '.mypy_cache', '.ruff_cache'}
PRIVATE_SUFFIXES = {'.jks', '.keystore', '.key', '.pem', '.p12', '.pfx', '.asc',
                    '.gpg', '.p8', '.pk8', '.pkcs12', '.ppk', '.db', '.sqlite',
                    '.sqlite3', '.pyc', '.log', '.lck', '.apk', '.aab', '.zip',
                    '.tar', '.gz', '.iml', '.hprof', '.class', '.pdf'}
PRIVATE_NAMES = {'local.properties', 'key.properties', 'keystore.properties',
                 'pass.txt', 'password.txt', '.netrc', '.npmrc', '.pypirc',
                 'google-services.json', 'service-account.json'}
TEXT_SUFFIXES = {'.kt', '.kts', '.py', '.properties', '.xml', '.json', '.md',
                 '.txt', '.sh', '.bat', '.yml', '.yaml', '.toml'}
REQUIRED = (ROOT_FILES | PUBLIC_DOCS | BANK_FILES | OFFICIAL_FILES | RUNTIME_JSON
            | {'app/build.gradle.kts', 'gradle/wrapper/gradle-wrapper.jar',
               'gradle/wrapper/gradle-wrapper.properties',
               'app/src/main/AndroidManifest.xml',
               'app/src/main/java/ir/hezarzist/MainActivity.kt',
               'content/v2/sources.json', 'content/v2/licenses.json',
               'content/release/summary.json'}
            | {f'tools/{name}' for name in TOOLS})


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_name(name: str) -> bool:
    """Accept normalized relative POSIX archive and asset paths only."""
    if not isinstance(name, str) or not name or '\\' in name or ':' in name:
        return False
    path = PurePosixPath(name)
    return (not path.is_absolute() and path.as_posix() == name
            and all(part not in {'', '.', '..'} for part in name.split('/'))
            and not any(ord(char) < 32 or ord(char) == 127 for char in name))


def private_path(name: str) -> bool:
    if not safe_name(name):
        return True
    path = PurePosixPath(name)
    parts = tuple(part.lower() for part in path.parts)
    basename = parts[-1]
    return (any(part in PRIVATE_PARTS for part in parts)
            or basename in PRIVATE_NAMES or basename.startswith('.env')
            or any(word in basename for word in ('credential', 'password', 'passwd',
                       'secret', 'private_key', 'token', 'signing'))
            or basename.startswith(('id_rsa', 'id_ed25519', 'id_ecdsa'))
            or path.suffix.lower() in PRIVATE_SUFFIXES
            or basename.endswith(('.db-wal', '.db-shm', '.sqlite-wal', '.sqlite-shm')))


def source_allowed(name: str) -> bool:
    if private_path(name):
        return False
    parts = PurePosixPath(name).parts
    if name in ROOT_FILES | PUBLIC_DOCS | CATALOG_FILES | BANK_FILES | OFFICIAL_FILES | RUNTIME_JSON:
        return True
    if name.startswith(ASSETS):
        # Snapshot narrows media candidates to references in the active banks.
        return PurePosixPath(name).suffix.lower() in MEDIA_SUFFIXES
    return (name == 'app/build.gradle.kts'
            or name.startswith(('app/src/', 'gradle/'))
            or (len(parts) == 2 and parts[0] == 'tools' and parts[1] in TOOLS))


def delivery_allowed(name: str) -> bool:
    return not private_path(name) and name in PUBLIC_DOCS | BANK_FILES | CATALOG_FILES


def inspect_public_text(name: str, data: bytes) -> None:
    if (PurePosixPath(name).suffix.lower() not in TEXT_SUFFIXES
            and name not in {'gradlew', '.gitignore'}):
        return
    # Identify the filename in errors, never a matched credential value.
    patterns = (
        rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----',
        rb'\b(?:gh[pousr]_[A-Za-z0-9]{32,}|github_pat_[A-Za-z0-9_]{50,})\b',
        rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b',
        rb'\bAIza[0-9A-Za-z_-]{35}\b',
        rb'\bxox[baprs]-[A-Za-z0-9-]{20,}\b',
        rb'\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}\b',
        rb'(?i)["\']?(?:api[_-]?key|access[_-]?token|client[_-]?secret|'
        rb'private[_-]?key)["\']?\s*[:=]\s*["\'][A-Za-z0-9_+/.=-]{24,}["\']',
        rb'(?i)["\']?(?:password|passwd|storepassword|keypassword|storepass|keypass)'
        rb'["\']?\s*[:=]\s*["\'][^"\'\r\n]+["\']',
        rb'(?i)-D[^\s=]*(?:password|passwd|secret|token|storepass|keypass)=[^\s"\']+',
    )
    if any(re.search(pattern, data) for pattern in patterns):
        raise ValueError(f'Private key or credential content in public candidate: {name}')
    if name.endswith('.properties'):
        for line in data.decode('utf-8').splitlines():
            if not line.strip() or line.lstrip().startswith(('#', '!')):
                continue
            match = re.match(r'\s*([^=: \t]+)\s*[=: \t]\s*(.*)', line)
            if (match and match[2].strip()
                    and re.search(r'password|secret|token|credential|api[._-]?key|storepass|keypass', match[1], re.I)):
                raise ValueError(f'Credential property in public candidate: {name}')


def read_public(root: Path, name: str) -> bytes:
    if not safe_name(name):
        raise ValueError(f'Unsafe public path: {name}')
    path = root / name
    cursor = root
    for part in PurePosixPath(name).parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise ValueError(f'Symlink in public candidate: {name}')
    if not path.resolve().is_relative_to(root) or not path.is_file():
        raise ValueError(f'Missing or outside-root public file: {name}')
    data = path.read_bytes()
    if not data:
        raise ValueError(f'Empty public file: {name}')
    inspect_public_text(name, data)
    return data


def bank_rows(files: dict[str, bytes], name: str) -> list[dict]:
    rows = json.loads(files[name])
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise ValueError(f'Question bank must be an array of objects: {name}')
    return rows


def referenced_media(files: dict[str, bytes]) -> set[str]:
    paths = set()
    for name in sorted(BANK_FILES | {'content/konkur/questions.json'}):
        for row in bank_rows(files, name):
            options = row.get('optionImages') or []
            if not isinstance(options, list):
                raise ValueError(f'optionImages must be an array: {name}')
            for image in (row.get('image'), row.get('solutionImage'), *options):
                if image is None or image == '':
                    continue
                if (not isinstance(image, str) or not safe_name(image)
                        or PurePosixPath(image).suffix.lower() not in MEDIA_SUFFIXES
                        or not source_allowed(ASSETS + image)):
                    raise ValueError(f'Unsafe question media path in {name}')
                paths.add(ASSETS + image)
    return paths


def snapshot(root: Path, version: str, output: Path) -> dict[str, bytes]:
    root = root.resolve()
    files = {}
    for directory, directories, names in os.walk(root, followlinks=False):
        folder = Path(directory)
        kept = []
        for name in sorted(directories):
            child = folder / name
            if child.resolve().is_relative_to(output) or name.lower() in PRIVATE_PARTS:
                continue
            if child.is_symlink():
                relative = child.relative_to(root).as_posix()
                if (relative in {'app', 'gradle', 'tools', 'content', 'docs'}
                        or relative.startswith(('app/src', 'gradle/'))):
                    raise ValueError(f'Symlink in source tree: {relative}')
                continue
            kept.append(name)
        directories[:] = kept
        for name in sorted(names):
            path = folder / name
            relative = path.relative_to(root).as_posix()
            if (not source_allowed(relative)
                    or relative.startswith(ASSETS) and relative not in RUNTIME_JSON):
                continue
            if path.resolve().is_relative_to(output):
                continue
            files[relative] = read_public(root, relative)
    missing = REQUIRED - files.keys()
    if missing:
        raise ValueError('Required current files absent or excluded: ' + ', '.join(sorted(missing)))
    actual = re.search(r'versionName\s*=\s*"([^"]+)"', files['app/build.gradle.kts'].decode('utf-8'))
    if not actual or actual[1] != version:
        raise ValueError('--version must match app/build.gradle.kts versionName')
    if json.loads(files['content/release/summary.json']).get('version') != version:
        raise ValueError('Release summary version differs from Android versionName')
    for name in ('questions.json', 'finals.json', 'konkur/questions.json', 'konkur/coverage.json'):
        if files[f'content/{name}'] != files[ASSETS + name]:
            raise ValueError(f'Canonical and bundled JSON differ: {name}')
    for name in referenced_media(files):
        files[name] = read_public(root, name)
    # These hashes are integrity inputs consumed by the Android repository.
    audit = json.loads(files[ASSETS + 'konkur/coverage-audit.json'])
    for name, key in [('questions.json', 'questionFileSha256'), ('coverage.json', 'coverageFileSha256')]:
        if audit.get(key) != digest(files[f'content/konkur/{name}']):
            raise ValueError(f'Bundled official archive hash differs: {name}')
    return files


def content_status(files: dict[str, bytes]) -> dict[str, int]:
    """Derive factual counts directly from the delivered active banks."""
    mcq = bank_rows(files, 'content/questions.json')
    finals = bank_rows(files, 'content/finals.json')
    ids = [row.get('id') for row in mcq + finals]
    if any(not isinstance(value, str) or not value for value in ids) or len(ids) != len(set(ids)):
        raise ValueError('Active bank IDs must be nonempty and unique')
    counts = {'main': 0, 'veryDifficult': 0, 'shared': 0, 'unique': len(mcq), 'finals': len(finals)}
    for row in mcq:
        members = row.get('collections', ['main'])
        if (not isinstance(members, list) or not members or len(members) != len(set(members))
                or not set(members) <= {'main', 'qb_very_difficult'}):
            raise ValueError('Invalid active collection membership')
        counts['main'] += 'main' in members
        counts['veryDifficult'] += 'qb_very_difficult' in members
        counts['shared'] += 'main' in members and 'qb_very_difficult' in members
    if json.loads(files[ASSETS + 'content-counts.json']) != counts:
        raise ValueError('Bundled collection counts differ from active banks')
    if json.loads(files['content/release/summary.json']).get('counts') != counts:
        raise ValueError('Release collection counts differ from active banks')
    def catalog_ids(name):
        value = json.loads(files[name])
        if isinstance(value, dict):
            value = value.get('items', value.get('sources', value.get('licenses', [])))
        if not isinstance(value, list) or any(not isinstance(row, dict) or not row.get('id') for row in value):
            raise ValueError(f'Invalid delivered catalog: {name}')
        return {row['id'] for row in value}
    sources = catalog_ids('content/v2/sources.json')
    licenses = catalog_ids('content/v2/licenses.json')
    for row in mcq + finals:
        if row.get('sourceId') not in sources or row.get('licenseId') not in licenses:
            raise ValueError('Active question source/license absent from delivered catalogs')
    return counts


def explicit_android(path: Path | None, kind: str, version: str) -> tuple[str, bytes] | None:
    if path is None:
        return None
    if kind != 'apk':
        raise ValueError('Only an explicit APK is supported')
    path = path.expanduser().resolve(strict=True)
    if not path.is_file() or path.suffix.lower() != '.apk':
        raise ValueError('--apk must name an existing .apk file')
    data = path.read_bytes()
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = archive.namelist()
            if (not {'AndroidManifest.xml', 'classes.dex'} <= set(names)
                    or len(names) != len(set(names))
                    or any(archive.getinfo(name).file_size == 0
                           for name in ('AndroidManifest.xml', 'classes.dex'))
                    or any(not safe_name(name[:-1] if name.endswith('/') else name) for name in names)
                    or any(stat.S_ISLNK(info.external_attr >> 16) for info in archive.infolist())
                    or archive.testzip() is not None):
                raise ValueError('Unsafe Android entries or corrupt ZIP')
    except (zipfile.BadZipFile, RuntimeError, ValueError, zlib.error, NotImplementedError) as exc:
        raise ValueError('--apk is not a valid Android ZIP with passing CRC checks') from exc
    return f'artifacts/HezarZist-{version}-release.apk', data


def manifest(files: dict[str, bytes], version: str, kind: str, counts: dict) -> bytes:
    value = {'schemaVersion': 2, 'version': version, 'kind': kind, 'counts': counts,
             'files': {name: {'bytes': len(data), 'sha256': digest(data)}
                       for name, data in sorted(files.items())}}
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def zip_payload(files: dict[str, bytes], prefix: str) -> bytes:
    if not safe_name(prefix):
        raise ValueError('Unsafe archive prefix')
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(files.items()):
            if not safe_name(name):
                raise ValueError(f'Unsafe archive entry: {name}')
            info = zipfile.ZipInfo(f'{prefix}/{name}', (2026, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name == 'gradlew' else 0o100644) << 16
            archive.writestr(info, data)
    return buffer.getvalue()


def check_archive(data: bytes, prefix: str, expected: dict[str, bytes], allow_artifacts: bool = False) -> None:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip() is not None:
            raise ValueError('Package contains duplicate or corrupt ZIP entries')
        if set(names) != {f'{prefix}/{name}' for name in expected}:
            raise ValueError('Package inventory differs from the planned snapshot')
        for name, content in expected.items():
            artifact = allow_artifacts and (name.startswith('artifacts/') or name.startswith('source/'))
            if not safe_name(name) or not artifact and name != 'MANIFEST.json' and private_path(name):
                raise ValueError(f'Excluded file reached archive: {name}')
            if archive.read(f'{prefix}/{name}') != content:
                raise ValueError(f'Archive byte mismatch: {name}')
        if 'gradlew' in expected:
            mode = archive.getinfo(f'{prefix}/gradlew').external_attr >> 16
            if not mode & stat.S_IXUSR:
                raise ValueError('Source gradlew is not executable')
        stored = json.loads(archive.read(f'{prefix}/MANIFEST.json'))
        if set(stored['files']) != set(expected) - {'MANIFEST.json'}:
            raise ValueError('Archive manifest inventory mismatch')
        for name, record in stored['files'].items():
            content = archive.read(f'{prefix}/{name}')
            if digest(content) != record['sha256'] or len(content) != record['bytes']:
                raise ValueError(f'Archive manifest mismatch: {name}')


def policy_self_check() -> None:
    blocked = ['app/build/output.apk', '.git/config', 'app/src/main/assets/password.txt',
               'gradle/credentials.properties', 'app/id_rsa', 'app/signing.jks',
               'server/app.py', 'backend/app.py', 'content/konkur/sources/booklet.pdf',
               'app/local.properties', '../escape.txt', 'app/./escape.txt',
               '/app/escape.txt', 'app//escape.txt', 'C:/escape.txt', 'app\\escape.txt',
               'app/src/main/assets/state.db-wal', 'app/src/main/assets/imports/report.json',
               'content/imports/v014/questions.json', 'content/legacy/v1/questions.json',
               'docs/offline/BUILD.fa.md', 'release/myket/MYKET_READINESS.json',
               'docs/release/bank-validation.json', 'docs/release/DEFECT_REPORT.md']
    if any(source_allowed(name) or delivery_allowed(name) for name in blocked):
        raise ValueError('Exclusion policy self-check failed')
    allowed = ['app/src/main/java/ir/hezarzist/LocalBackup.kt',
               'app/src/main/res/font/vazirmatn_regular.ttf',
               'app/src/androidTest/assets/baseline-v012/questions.json',
               ASSETS + 'imports/fixture/question.webp', ASSETS + 'konkur/coverage-audit.json',
               'docs/release/BUILD.fa.md', 'tools/validate_bank.py']
    if not all(source_allowed(name) for name in allowed):
        raise ValueError('Current source policy self-check failed')
    fixtures = [('gradle.properties', b'storePassword=fixture'),
                ('gradle.properties', b'accessToken:fixture'),
                ('docs/fixture.txt', b'-----BEGIN ' + b'PRIVATE KEY-----'),
                ('content/fixture.json', b'{"api_key":"' + b'a' * 32 + b'"}'),
                ('content/fixture.json', b'{"pass' + b'word":"fixture"}'),
                ('tools/fixture.py', b'ghp_' + b'a' * 36)]
    for name, value in fixtures:
        try:
            inspect_public_text(name, value)
        except ValueError:
            continue
        raise ValueError('Private-content self-check failed')


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
    parser.add_argument('--dist', type=Path, default=ROOT / 'dist', help='output directory')
    parser.add_argument('--apk', type=Path, help='explicit existing Android APK to include')
    parser.add_argument('--dry-run', action='store_true', help='verify packages without writing output files')
    parser.add_argument('--self-check', action='store_true', help='also check the exclusion policy')
    args = parser.parse_args(argv)
    try:
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,79}', args.version):
            raise ValueError('--version must be a safe filename component')
        output = args.dist.expanduser().resolve()
        if output == ROOT or ROOT.is_relative_to(output):
            raise ValueError('--dist must not be the project root or its ancestor')
        if args.self_check:
            policy_self_check()
        if args.apk is None and not args.dry_run:
            raise ValueError('--apk is required when writing delivery packages')
        artifact = explicit_android(args.apk, 'apk', args.version)
        files = snapshot(ROOT, args.version, output)
        counts = content_status(files)
        base_name = f'HezarZist-{args.version}'
        source_files = dict(files)
        source_files['MANIFEST.json'] = manifest(source_files, args.version, 'source', counts)
        source_data = zip_payload(source_files, base_name)
        check_archive(source_data, base_name, source_files)
        media = referenced_media(files)
        bank_files = {name: data for name, data in files.items()
                      if name in BANK_FILES | CATALOG_FILES | BANK_DOCS | media}
        bank_files['MANIFEST.json'] = manifest(bank_files, args.version, 'editable-bank', counts)
        bank_data = zip_payload(bank_files, base_name)
        check_archive(bank_data, base_name, bank_files)
        delivery = {name: data for name, data in files.items() if delivery_allowed(name)}
        delivery[f'source/{base_name}-source.zip'] = source_data
        if artifact:
            delivery[artifact[0]] = artifact[1]
        delivery['MANIFEST.json'] = manifest(delivery, args.version, 'delivery', counts)
        delivery_data = zip_payload(delivery, base_name)
        check_archive(delivery_data, base_name, delivery, allow_artifacts=True)
        products = {f'{base_name}-source.zip': source_data,
                    f'{base_name}-bank.zip': bank_data,
                    f'{base_name}-delivery.zip': delivery_data,
                    f'{base_name}-package-manifest.json': delivery['MANIFEST.json']}
        summary = {'version': args.version, 'dryRun': args.dry_run, 'counts': counts,
                   'sourceFileCount': len(files), 'bankMediaFileCount': len(media),
                   'explicitAndroidArtifacts': [artifact[0]] if artifact else [],
                   'products': {name: {'bytes': len(data), 'sha256': digest(data)}
                                for name, data in products.items()}}
        if not args.dry_run:
            output.mkdir(parents=True, exist_ok=True)
            for name, data in products.items():
                atomic_write(output / name, data)
            checksums = ''.join(f'{digest(data)}  {name}\n' for name, data in sorted(products.items()))
            atomic_write(output / f'{base_name}-SHA256SUMS.txt', checksums.encode('utf-8'))
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, TypeError, KeyError, UnicodeError, json.JSONDecodeError,
            zipfile.BadZipFile, RuntimeError) as exc:
        print(f'Packaging failed: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
