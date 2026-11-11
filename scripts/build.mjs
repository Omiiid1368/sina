import { cp, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dist = path.join(project, 'dist');
const output = path.join(project, 'artifacts');
for (const item of ['index.html', 'styles.css', 'src']) await stat(path.join(project, item));
await rm(dist, { recursive: true, force: true });
await mkdir(dist, { recursive: true });
for (const item of ['index.html', 'styles.css', 'src', 'assets']) {
  try { await cp(path.join(project, item), path.join(dist, item), { recursive: true, dereference: false }); }
  catch (error) { if (!(item === 'assets' && error.code === 'ENOENT')) throw error; }
}

async function filesIn(dir) {
  const files = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (entry.isSymbolicLink()) throw new Error(`Symlinks are not allowed in the build: ${entry.name}`);
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) files.push(...await filesIn(file));
    else if (entry.isFile()) files.push(file);
  }
  return files.sort();
}
const files = await filesIn(dist);
const html = await readFile(path.join(dist, 'index.html'), 'utf8');
for (const match of html.matchAll(/(?:src|href)\s*=\s*['"]([^'"]+)['"]/g)) {
  const reference = match[1];
  if (reference.startsWith('#') || /^[a-z][a-z\d+.-]*:/i.test(reference)) continue;
  if (reference.startsWith('/')) throw new Error(`Build paths must be relative: ${reference}`);
  const asset = path.resolve(dist, reference.split(/[?#]/)[0]);
  if (!asset.startsWith(dist + path.sep)) throw new Error(`Asset escapes build: ${reference}`);
  await stat(asset);
}
// Catch missing module imports before creating a release archive.
for (const file of files.filter((file) => /\.[cm]?js$/.test(file))) {
  const source = await readFile(file, 'utf8');
  for (const match of source.matchAll(/(?:from\s*|import\s*)['"]([^'"]+)['"]/g)) {
    if (!match[1].startsWith('.')) throw new Error(`External runtime import in ${file}: ${match[1]}`);
    const dependency = path.resolve(path.dirname(file), match[1]);
    if (!dependency.startsWith(dist + path.sep)) throw new Error(`Import escapes build: ${match[1]}`);
    await stat(dependency);
  }
}
await mkdir(output, { recursive: true });
const archive = path.join(output, 'neon-rush-itch.zip');
const sourceArchive = path.join(output, 'neon-rush-source.zip');
const python = process.env.PYTHON || 'python3';
const result = spawnSync(python, ['-c', `
import pathlib, sys, zipfile
dist, release, project, source_zip = map(pathlib.Path, sys.argv[1:])
excluded = {'.git', 'node_modules', 'dist', 'artifacts', 'test-results', '__pycache__'}

def write_archive(root, target, paths, required, label):
    with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for source in sorted(set(paths)):
            relative = source.relative_to(root)
            if any(part in excluded for part in relative.parts): continue
            if source.is_symlink(): raise ValueError(f'Symlink is not allowed in archive: {relative}')
            if source.is_file(): archive.write(source, relative.as_posix())
    with zipfile.ZipFile(target) as archive:
        names = archive.namelist()
        assert all(name in names for name in required), f'Missing required files in {label} ZIP'
        assert archive.testzip() is None, f'{label} ZIP integrity check failed'
        assert not any(name.startswith('/') or '..' in name.split('/') for name in names)
        print(f'Verified {len(names)} files in {label} ZIP')

write_archive(dist, release, dist.rglob('*'), ['index.html'], 'itch.io')
source_paths = [project / name for name in ['index.html', 'styles.css', 'README.md', 'package.json', '.gitignore']]
for directory in ['src', 'assets', 'scripts', 'tests', 'docs']:
    source_paths.extend((project / directory).rglob('*'))
write_archive(project, source_zip, source_paths, ['index.html', 'package.json', 'README.md', 'scripts/build.mjs', 'tests/browser.mjs'], 'source')
`, dist, archive, project, sourceArchive], { encoding: 'utf8' });
if (result.error) throw new Error(`Python 3 is needed to package the ZIP: ${result.error.message}`);
if (result.status !== 0) throw new Error(result.stderr || 'ZIP creation failed');
console.log(result.stdout.trim());
console.log(`Built ${files.length} files to dist/`);
for (const file of [archive, sourceArchive]) {
  const bytes = await readFile(file);
  const checksum = createHash('sha256').update(bytes).digest('hex');
  await writeFile(`${file}.sha256`, `${checksum}  ${path.basename(file)}\n`);
  console.log(`Created artifacts/${path.basename(file)} (${(bytes.length / 1024).toFixed(1)} KiB)`);
}
