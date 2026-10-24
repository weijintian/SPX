import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, readFile, writeFile, rm, utimes } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scripts = path.dirname(fileURLToPath(import.meta.url));
const iosRoot = path.dirname(scripts);
const php = process.argv[2] || 'php';
const testRoot = await mkdtemp(path.join(tmpdir(), 'spx-ios-upload-test-'));
await writeFile(path.join(testRoot, 'test-marker'), 'isolated upload integration test');
await mkdir(path.join(testRoot, 'state', '9'), { recursive: true });
const listener = createServer();
await new Promise(resolve => listener.listen(0, '127.0.0.1', resolve));
const port = listener.address().port;
await new Promise(resolve => listener.close(resolve));
const args = ['-n'];
if (process.platform === 'win32') args.push('-d', `extension_dir=${path.join(path.dirname(php), 'ext')}`, '-d', 'extension=fileinfo');
args.push('-d', 'upload_max_filesize=2M', '-d', 'post_max_size=3M', '-S', `127.0.0.1:${port}`, path.join(scripts, 'upload-test-router.php'));
const server = spawn(php, args, { cwd: iosRoot, windowsHide: true, env: { ...process.env, SPX_UPLOAD_TEST_ROOT: testRoot }, stdio: ['ignore', 'pipe', 'pipe'] });
let serverErrors = '';
let launchError;
server.on('error', error => { launchError = error; });
server.stderr.on('data', data => { serverErrors = (serverErrors + data).slice(-8000); });
const checks = [];
const chunkSize = 1_048_576;
const totalBytes = chunkSize + 37;
const payload = Buffer.alloc(totalBytes, 65);
payload.fill(66, chunkSize);
const base = `http://127.0.0.1:${port}`;
const query = (id, bytes = totalBytes, uid = 9) => ({ upload_id: id, total_chunks: String(Math.ceil(bytes / chunkSize)), total_bytes: String(bytes), uid: String(uid) });
const request = async (operation, params, form) => {
  const response = await fetch(`${base}/?${new URLSearchParams({ operation, ...params })}`, { method: form ? 'POST' : 'GET', body: form, signal: AbortSignal.timeout(15000) });
  return { status: response.status, envelope: await response.json() };
};
const chunk = async (id, index, bytes = payload.subarray(index * chunkSize, (index + 1) * chunkSize), expectedBytes = totalBytes) => {
  const form = new FormData();
  Object.entries({ ...query(id, expectedBytes), chunk_index: String(index) }).forEach(([key, value]) => form.set(key, value));
  form.set('chunk', new Blob([bytes]), 'part.bin');
  return request('chunk', { uid: '9' }, form);
};
const fixture = async (id, parts, expired = false) => {
  const directory = path.join(testRoot, 'state', '9', id);
  await mkdir(directory, { recursive: true });
  const meta = path.join(directory, 'meta.json');
  await writeFile(meta, JSON.stringify({ total: 2, bytes: totalBytes }));
  for (const index of parts) await writeFile(path.join(directory, `${index}.part`), payload.subarray(index * chunkSize, (index + 1) * chunkSize));
  if (expired) { const date = new Date(Date.now() - 25 * 60 * 60 * 1000); await utimes(meta, date, date); }
  return directory;
};
const check = async (name, operation) => { await operation(); checks.push({ name, passed: true }); };

try {
  let ready = false;
  for (let attempt = 0; attempt < 50; attempt++) {
    if (launchError) throw launchError;
    if (server.exitCode !== null) throw Error(`PHP server exited: ${serverErrors}`);
    try { ready = (await request('health', {})).status === 200; } catch { }
    if (ready) break;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, 'Loopback PHP test server did not start');
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jP1sAAAAASUVORK5CYII=', 'base64');
  await check('small image uploads through the same single-file contract as Android', async () => {
    const form = new FormData(); form.set('file', new Blob([png], { type: 'image/png' }), 'photo.png');
    const r = await request('single', { uid: '9' }, form);
    assert.equal(r.status, 200); assert.equal(r.envelope.data.message_type, 'image');
    assert.deepEqual(await readFile(path.join(testRoot, 'messages', path.basename(r.envelope.data.url))), png);
  });
  await check('image chunks assemble into a complete image and retain its type', async () => {
    const r = await chunk(randomUUID(), 0, png, png.length);
    assert.equal(r.status, 200); assert.equal(r.envelope.data.message_type, 'image');
    assert.deepEqual(await readFile(path.join(testRoot, 'messages', path.basename(r.envelope.data.url))), png);
  });
  await check('voice WAV upload retains its voice type and all bytes', async () => {
    const voice = Buffer.alloc(44 + 16_000);
    voice.write('RIFF', 0); voice.writeUInt32LE(voice.length - 8, 4); voice.write('WAVEfmt ', 8);
    voice.writeUInt32LE(16, 16); voice.writeUInt16LE(1, 20); voice.writeUInt16LE(1, 22);
    voice.writeUInt32LE(8_000, 24); voice.writeUInt32LE(16_000, 28);
    voice.writeUInt16LE(2, 32); voice.writeUInt16LE(16, 34); voice.write('data', 36); voice.writeUInt32LE(16_000, 40);
    const form = new FormData(); form.set('file', new Blob([voice], { type: 'audio/wav' }), 'voice.wav');
    const r = await request('single', { uid: '9' }, form);
    assert.equal(r.status, 200); assert.equal(r.envelope.data.message_type, 'voice');
    assert.deepEqual(await readFile(path.join(testRoot, 'messages', path.basename(r.envelope.data.url))), voice);
  });
  await check('iOS M4A container is classified as a voice attachment', async () => {
    // Container/MIME fixture only; actual AAC recording and playback require a device.
    const voice = Buffer.alloc(128);
    voice.writeUInt32BE(24, 0); voice.write('ftypM4A ', 4); voice.writeUInt32BE(512, 12);
    voice.write('M4A isom', 16); voice.writeUInt32BE(104, 24); voice.write('free', 28);
    const form = new FormData(); form.set('file', new Blob([voice], { type: 'audio/mp4' }), 'voice.m4a');
    const r = await request('single', { uid: '9' }, form);
    assert.equal(r.status, 200); assert.equal(r.envelope.data.message_type, 'voice');
    assert.deepEqual(await readFile(path.join(testRoot, 'messages', path.basename(r.envelope.data.url))), voice);
  });
  await check('a multi-chunk MP4 container retains video type and all bytes', async () => {
    // Protocol fixture only: an ISO BMFF ftyp/free container, not a playback test.
    const video = Buffer.alloc(chunkSize + 128);
    video.writeUInt32BE(24, 0); video.write('ftypisom', 4, 'ascii'); video.writeUInt32BE(512, 12);
    video.write('isommp42', 16, 'ascii'); video.writeUInt32BE(video.length - 24, 24); video.write('free', 28, 'ascii');
    const uploadID = randomUUID();
    assert.equal((await chunk(uploadID, 0, video.subarray(0, chunkSize), video.length)).status, 200);
    const status = await request('status', query(uploadID, video.length));
    assert.deepEqual(status.envelope.data.received_chunks, [0]);
    const r = await chunk(uploadID, 1, video.subarray(chunkSize), video.length);
    assert.equal(r.status, 200); assert.equal(r.envelope.data.message_type, 'video');
    assert.deepEqual(await readFile(path.join(testRoot, 'messages', path.basename(r.envelope.data.url))), video);
  });
  const id = randomUUID();
  await check('new upload has no completed chunks', async () => {
    const r = await request('status', query(id));
    assert.equal(r.status, 200); assert.deepEqual(r.envelope.data.received_chunks, []);
  });
  await check('first chunk is persisted and reported', async () => {
    assert.equal((await chunk(id, 0)).status, 200);
    assert.deepEqual((await request('status', query(id))).envelope.data.received_chunks, [0]);
  });
  await check('duplicate chunk is idempotent', async () => {
    const r = await chunk(id, 0); assert.equal(r.status, 200); assert.equal(r.envelope.data.received, 1);
  });
  await check('different contents for the same chunk are rejected', async () => {
    assert.equal((await chunk(id, 0, Buffer.alloc(chunkSize, 90))).status, 409);
  });
  await check('different account cannot read the checkpoint', async () => {
    assert.deepEqual((await request('status', query(id, totalBytes, 10))).envelope.data.received_chunks, []);
  });
  await check('changed upload dimensions are rejected', async () => {
    assert.equal((await request('status', query(id, totalBytes + 1))).status, 409);
  });
  await check('invalid final chunk length is rejected before persistence', async () => {
    assert.equal((await chunk(id, 1, Buffer.alloc(12, 66))).status, 422);
    assert.deepEqual((await request('status', query(id))).envelope.data.received_chunks, [0]);
  });
  await check('resuming only the missing chunk yields the exact original file', async () => {
    const r = await chunk(id, 1); assert.equal(r.status, 200); assert.ok(r.envelope.data.url);
    const saved = await readFile(path.join(testRoot, 'messages', path.basename(r.envelope.data.url)));
    assert.equal(createHash('sha256').update(saved).digest('hex'), createHash('sha256').update(payload).digest('hex'));
  });
  await check('finished result is reusable without uploading again', async () => {
    const status = (await request('status', query(id))).envelope.data;
    assert.ok(status.result.url); assert.equal(status.received, 2);
    assert.equal((await chunk(id, 1)).envelope.data.url, status.result.url);
  });
  await check('lost merge result recovers by resending the last saved chunk', async () => {
    const recoveryID = randomUUID(); await fixture(recoveryID, [0, 1]);
    const before = (await request('status', query(recoveryID))).envelope.data;
    assert.deepEqual(before.received_chunks, [0, 1]); assert.equal(before.result, null);
    assert.ok((await chunk(recoveryID, 1)).envelope.data.url);
  });
  await check('expired partial checkpoints restart consistently', async () => {
    const expiredID = randomUUID(); await fixture(expiredID, [0], true);
    assert.deepEqual((await request('status', query(expiredID))).envelope.data.received_chunks, []);
    assert.equal((await chunk(expiredID, 0)).status, 200);
    assert.deepEqual((await request('status', query(expiredID))).envelope.data.received_chunks, [0]);
  });
  await check('incomplete persisted chunks are rejected', async () => {
    const brokenID = randomUUID(); const directory = await fixture(brokenID, [0]);
    await writeFile(path.join(directory, '0.part'), Buffer.alloc(4));
    assert.equal((await request('status', query(brokenID))).status, 409);
  });
  await check('malformed IDs and size limits are rejected', async () => {
    for (const id of ['../escape', 'not-a-uuid', '']) assert.equal((await request('status', query(id))).status, 422);
    assert.equal((await request('status', query(randomUUID(), 1_073_741_825))).status, 422);
  });
  const report = { passed: true, checks, php_sapi: 'cli-server', scope: 'loopback isolated storage; no production API, account or database used' };
  await mkdir(path.join(iosRoot, 'Validation'), { recursive: true });
  await writeFile(path.join(iosRoot, 'Validation', 'media-upload-integration.json'), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} catch (error) {
  console.error(error); console.error(serverErrors); process.exitCode = 1;
} finally {
  const exited = new Promise(resolve => server.once('exit', resolve));
  if (server.exitCode === null) { server.kill(); await exited; }
  await rm(testRoot, { recursive: true, force: true });
}
