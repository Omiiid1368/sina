import http from 'node:http';
import path from 'node:path';
import fs from 'node:fs';
import { stat, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
function option(name, fallback) {
  const i = args.lastIndexOf(name);
  if (i === -1) return fallback;
  if (!args[i + 1] || args[i + 1].startsWith('--')) throw new Error(`Missing value for ${name}`);
  return args[i + 1];
}
const root = await realpath(path.resolve(project, option('--root', '.')));
const port = Number(option('--port', '4173'));
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Port must be between 1 and 65535');
const types = {
  '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon',
  '.wav': 'audio/wav', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg',
  '.woff2': 'font/woff2'
};
const inside = (file) => { const rel = path.relative(root, file); return rel === '' || (!rel.startsWith('..' + path.sep) && rel !== '..' && !path.isAbsolute(rel)); };
const server = http.createServer(async (req, res) => {
  const end = (code, message) => { res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8' }); res.end(message); };
  if (!['GET', 'HEAD'].includes(req.method)) { res.setHeader('Allow', 'GET, HEAD'); end(405, 'Method not allowed'); return; }
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    if (pathname.includes('\0')) { end(400, 'Invalid path'); return; }
    let file = path.resolve(root, '.' + pathname);
    if (!inside(file)) { end(403, 'Forbidden'); return; }
    if (pathname.split('/').some((part) => part.startsWith('.'))) { end(404, 'Not found'); return; }
    let info = await stat(file);
    if (info.isDirectory()) { file = path.join(file, 'index.html'); info = await stat(file); }
    const actual = await realpath(file);
    if (!inside(actual) || !info.isFile()) { end(403, 'Forbidden'); return; }
    res.writeHead(200, {
      'Content-Type': types[path.extname(actual).toLowerCase()] || 'application/octet-stream',
      'Content-Length': info.size, 'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff'
    });
    if (req.method === 'HEAD') { res.end(); return; }
    const stream = fs.createReadStream(actual);
    stream.on('error', () => res.destroy());
    stream.pipe(res);
  } catch (error) {
    if (error instanceof URIError) end(400, 'Invalid path');
    else if (['ENOENT', 'ENOTDIR'].includes(error.code)) end(404, 'Not found');
    else { console.error(error.message); end(500, 'Server error'); }
  }
});
server.on('error', (error) => { console.error(error.message); process.exitCode = 1; });
server.listen(port, '0.0.0.0', () => console.log(`Serving ${path.relative(project, root) || '.'} on port ${port}`));
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
