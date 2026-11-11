/** Integration QA against the production build, using real Chromium input. */
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const screenshots = path.join(project, 'artifacts', 'screenshots');
await mkdir(screenshots, { recursive: true });
const port = Number(process.env.QA_PORT || 4185);
const base = `http://127.0.0.1:${port}`;
const server = spawn(process.execPath, ['scripts/serve.mjs', '--root', 'dist', '--port', String(port)], { cwd: project, stdio: ['ignore', 'pipe', 'pipe'] });
let serverOutput = '';
server.stdout.on('data', buffer => { serverOutput += buffer; });
server.stderr.on('data', buffer => { serverOutput += buffer; });
const results = [];
const errors = [];
let browser;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function check(name, callback) {
  try { await callback(); results.push({ name, passed: true }); console.log(`PASS ${name}`); }
  catch (error) { results.push({ name, passed: false, error: error.stack }); console.error(`FAIL ${name}: ${error.message}`); }
}
function observe(page) {
  page.on('pageerror', error => errors.push(error.stack || error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  page.on('response', response => { if (response.status() >= 400) errors.push(`${response.status()} ${response.url()}`); });
  page.on('requestfailed', request => errors.push(`${request.method()} ${request.url()} ${request.failure()?.errorText}`));
}
async function testAPI(page) {
  await page.waitForFunction(() => Boolean(window.__neonRush), { timeout: 10000 });
}
async function action(page, name) { await page.locator(`[data-action="${name}"]:visible`).first().click(); }
async function read(page, expression) { return page.evaluate(expression); }

try {
  for (let attempt = 0; attempt < 80; attempt++) {
    if (server.exitCode !== null) throw new Error(`Production server failed: ${serverOutput}`);
    try { const response = await fetch(base); if (response.ok) break; } catch { /* Await server startup. */ }
    if (attempt === 79) throw new Error(`Production server not ready: ${serverOutput}`);
    await delay(100);
  }
  browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  const desktop = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
  const page = await desktop.newPage();
  observe(page);
  await page.goto(`${base}/?test=1`);
  await testAPI(page);

  await check('production menu renders at desktop resolution', async () => {
    assert.equal(await page.title(), 'NEON RUSH — Escape the Void');
    await page.locator('#home').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#support').isVisible(), false);
    assert.equal(await read(page, () => window.__neonRush.audio.context === null), true, 'audio must await user interaction');
    assert.ok(await page.locator('#world').evaluate(canvas => canvas.width > 0 && canvas.height > 0));
    await page.screenshot({ path: path.join(screenshots, 'desktop-menu.png') });
  });

  await check('how-to-play, skins, and settings menus open and close', async () => {
    await action(page, 'help');
    assert.match(await page.locator('#panel').innerText(), /jump/i);
    await page.locator('#panel .close').click();
    await action(page, 'skins');
    assert.equal(await page.locator('.skin-card').count(), 4);
    assert.equal(await page.locator('.skin-card.locked').count(), 3);
    await page.locator('#panel .close').click();
    await action(page, 'settings');
    assert.match(await page.locator('#panel').innerText(), /music/i);
    await page.locator('#panel .close').click();
  });

  await check('music, effects and reduced-motion preferences work independently and persist', async () => {
    await action(page, 'settings');
    const toggle = pattern => page.locator('.setting-row').filter({ hasText: pattern }).locator('button');
    await toggle(/music/i).click();
    assert.equal(await read(page, () => window.__neonRush.audio.music), false);
    assert.equal(await read(page, () => window.__neonRush.audio.sfx), true);
    await toggle(/sound effects|sfx/i).click();
    assert.equal(await read(page, () => window.__neonRush.audio.sfx), false);
    await toggle(/music/i).click();
    assert.equal(await read(page, () => window.__neonRush.audio.music), true);
    assert.equal(await read(page, () => window.__neonRush.audio.sfx), false);
    await toggle(/reduced motion/i).click();
    assert.equal(await page.locator('body').evaluate(body => body.classList.contains('reduced-motion')), true);
    await page.reload();
    await testAPI(page);
    assert.equal(await read(page, () => window.__neonRush.save.settings.music), true);
    assert.equal(await read(page, () => window.__neonRush.save.settings.sfx), false);
    assert.equal(await read(page, () => window.__neonRush.save.settings.reducedMotion), true);
    await action(page, 'settings');
    await toggle(/sound effects|sfx/i).click();
    await toggle(/reduced motion/i).click();
    await page.locator('#panel .close').click();
  });

  await check('keyboard lane changes and jump respond during real gameplay', async () => {
    await action(page, 'play');
    await page.locator('#hud').waitFor({ state: 'visible' });
    await page.keyboard.press('ArrowLeft');
    assert.equal(await read(page, () => window.__neonRush.model.player.lane), 0);
    await page.keyboard.press('d');
    assert.equal(await read(page, () => window.__neonRush.model.player.lane), 1);
    await page.keyboard.press('Space');
    await page.waitForTimeout(100);
    assert.ok(await read(page, () => window.__neonRush.model.player.height > 0));
    assert.ok(await read(page, () => window.__neonRush.model.distance > 0));
    assert.equal(await read(page, () => window.__neonRush.audio.context.state), 'running');
    assert.equal(await read(page, () => window.__neonRush.audio.timer !== null), true);
    await page.screenshot({ path: path.join(screenshots, 'desktop-gameplay.png') });
    await page.waitForFunction(() => window.__neonRush.save.tutorialDone === true);
    assert.equal(await page.locator('#tutorial').isVisible(), false);
  });

  await check('pause freezes distance and Resume continues', async () => {
    await page.keyboard.press('p');
    await page.locator('#overlay').waitFor({ state: 'visible' });
    const distance = await read(page, () => window.__neonRush.model.distance);
    assert.equal(await read(page, () => window.__neonRush.audio.timer), null);
    await page.waitForTimeout(220);
    assert.equal(await read(page, () => window.__neonRush.model.distance), distance);
    await page.locator('#panel button').filter({ hasText: /resume/i }).click();
    assert.equal(await read(page, () => window.__neonRush.state), 'playing');
    await page.waitForFunction(previous => window.__neonRush.model.distance > previous, distance, { timeout: 5000 });
  });

  await check('pause Restart resets score, combo, crystals and position', async () => {
    await page.keyboard.press('Escape');
    await page.evaluate(() => {
      const model = window.__neonRush.model;
      model.bonus = 1000; model.score = 1000; model.combo = 4; model.crystals = 12;
    });
    await page.locator('#panel button').filter({ hasText: /restart/i }).click();
    const value = await read(page, () => {
      const model = window.__neonRush.model;
      return { score: model.score, combo: model.combo, crystals: model.crystals, lane: model.player.lane, distance: model.distance };
    });
    assert.equal(value.combo, 1);
    assert.equal(value.crystals, 0);
    assert.equal(value.lane, 1);
    assert.ok(value.distance < 10);
    assert.ok(value.score < 100);
  });

  await check('collision ends run, records persist, and Retry starts cleanly', async () => {
    await page.evaluate(() => {
      const model = window.__neonRush.model;
      model.bonus = 3000;
      model.player.x = 0; model.player.lane = 1; model.player.height = 0;
      model.rows = [{ id: -1, z: 1, worldZ: model.distance + 1, pattern: 'barrier', safeLane: 0,
        lanes: [null, { type: 'barrier' }, null], passed: false }];
    });
    await page.waitForFunction(() => window.__neonRush.model.dead);
    await page.locator('#overlay').waitFor({ state: 'visible' });
    assert.match(await page.locator('#panel').innerText(), /new|record|best/i);
    const best = await read(page, () => window.__neonRush.save.best);
    assert.ok(best >= 3000);
    await page.screenshot({ path: path.join(screenshots, 'desktop-gameover.png') });
    await action(page, 'play');
    assert.equal(await read(page, () => window.__neonRush.model.dead), false);
    assert.equal(await read(page, () => window.__neonRush.model.crystals), 0);
    await page.reload();
    await testAPI(page);
    assert.equal(await read(page, () => window.__neonRush.save.best), best);
    assert.match(await page.locator('#home-best').innerText(), /3/);
  });

  await check('blur pauses safely and returning does not automatically resume', async () => {
    await action(page, 'play');
    await page.evaluate(() => window.dispatchEvent(new Event('blur')));
    await page.locator('#overlay').waitFor({ state: 'visible' });
    const distance = await read(page, () => window.__neonRush.model.distance);
    await page.waitForTimeout(150);
    assert.equal(await read(page, () => window.__neonRush.model.distance), distance);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    assert.equal(await page.locator('#overlay').isVisible(), true);
    await page.evaluate(() => window.__neonRush.showHome());
  });

  await check('first-run practice grants no rewards, can be quit, and ends automatically', async () => {
    const firstRun = await browser.newContext({ viewport: { width: 900, height: 650 } });
    const practice = await firstRun.newPage();
    observe(practice);
    await practice.goto(`${base}/?test=1`);
    await testAPI(practice);
    await action(practice, 'play');
    await practice.locator('#tutorial').waitFor({ state: 'visible' });
    await practice.waitForFunction(() => window.__neonRush.model.distance > 24);
    const before = await read(practice, () => ({ best: window.__neonRush.save.best, crystals: window.__neonRush.save.totalCrystals, runs: window.__neonRush.save.runs }));
    assert.deepEqual(before, { best: 0, crystals: 0, runs: 0 });
    assert.equal(await practice.locator('#score').innerText(), '000000');
    assert.equal(await practice.locator('#distance').innerText(), 'PRACTICE');
    await practice.evaluate(() => window.__neonRush.showHome());
    const afterQuit = await read(practice, () => ({ best: window.__neonRush.save.best, crystals: window.__neonRush.save.totalCrystals, runs: window.__neonRush.save.runs, tutorialDone: window.__neonRush.save.tutorialDone }));
    assert.deepEqual(afterQuit, { best: 0, crystals: 0, runs: 0, tutorialDone: false });
    await action(practice, 'play');
    // Intentionally give no controls. The lesson must not be an endless invulnerable run.
    await practice.waitForFunction(() => window.__neonRush.save.tutorialDone === true, undefined, { timeout: 60000 });
    const started = await read(practice, () => ({ elapsed: window.__neonRush.model.elapsed, score: window.__neonRush.model.score, crystals: window.__neonRush.model.crystals, state: window.__neonRush.state }));
    assert.ok(started.elapsed < 0.5, `practice must reset before the real run: ${JSON.stringify(started)}`);
    assert.ok(started.score < 100);
    assert.equal(started.crystals, 0);
    assert.equal(started.state, 'playing');
    assert.equal(await practice.locator('#tutorial').isVisible(), false);
    await firstRun.close();
  });

  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const phone = await mobile.newPage();
  observe(phone);
  await phone.goto(`${base}/?test=1`);
  await testAPI(phone);
  await phone.evaluate(() => {
    window.__qaTouchTrace = [];
    for (const type of ['pointerdown', 'pointerup', 'touchstart', 'touchend', 'click']) {
      document.addEventListener(type, event => {
        const button = event.target.closest('button');
        window.__qaTouchTrace.push({ type, action: button?.dataset.action, setting: button?.dataset.setting,
          pointer: event.pointerType, x: event.clientX, y: event.clientY, time: performance.now(),
          flag: window.__neonRush.save.settings.touchControls, state: window.__neonRush.state, modal: window.__neonRush.modal });
        if (window.__qaTouchTrace.length > 100) window.__qaTouchTrace.shift();
      }, true);
    }
  });

  await check('portrait mobile menu and gameplay fit the viewport', async () => {
    assert.equal(await phone.locator('[data-action="play"]').isVisible(), true);
    assert.equal(await phone.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await phone.screenshot({ path: path.join(screenshots, 'mobile-menu.png') });
    await action(phone, 'play');
    await phone.locator('#touch-controls').waitFor({ state: 'visible' });
    await phone.locator('[data-control="left"]').tap();
    assert.equal(await read(phone, () => window.__neonRush.model.player.lane), 0);
    await phone.locator('[data-control="right"]').tap();
    assert.equal(await read(phone, () => window.__neonRush.model.player.lane), 1);
    await phone.locator('[data-control="jump"]').tap();
    assert.equal(await read(phone, () => window.__neonRush.model.player.jumping), true);
    await phone.screenshot({ path: path.join(screenshots, 'mobile-gameplay.png') });
  });

  await check('real touch swipes steer and jump without scrolling', async () => {
    const cdp = await mobile.newCDPSession(phone);
    const swipe = async (x, y, endX, endY) => {
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: endX, y: endY }] });
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    };
    await phone.waitForTimeout(800);
    await swipe(230, 420, 100, 420);
    assert.equal(await read(phone, () => window.__neonRush.model.player.lane), 0);
    await swipe(100, 420, 230, 420);
    assert.equal(await read(phone, () => window.__neonRush.model.player.lane), 1);
    await swipe(195, 460, 195, 330);
    assert.equal(await read(phone, () => window.__neonRush.model.player.jumping), true);
    assert.equal(await phone.evaluate(() => scrollY), 0);
    await cdp.detach();
  });

  await check('settings from pause preserve the run and touch buttons can be hidden', async () => {
    try {
    await action(phone, 'pause');
    const distance = await read(phone, () => window.__neonRush.model.distance);
    await action(phone, 'settings');
    await phone.locator('[data-setting="touchControls"]').tap();
    await phone.waitForFunction(() => window.__neonRush.save.settings.touchControls === false, undefined, { timeout: 5000 });
    assert.equal(await phone.locator('[data-setting="touchControls"]').getAttribute('aria-checked'), 'false');
    await action(phone, 'back');
    assert.equal(await read(phone, () => window.__neonRush.modal), 'pause');
    assert.equal(await read(phone, () => window.__neonRush.model.distance), distance);
    await action(phone, 'resume');
    assert.equal(await phone.locator('#touch-controls').isVisible(), false);
    await action(phone, 'pause');
    await action(phone, 'settings');
    await phone.locator('[data-setting="touchControls"]').tap();
    await phone.waitForFunction(() => window.__neonRush.save.settings.touchControls === true, undefined, { timeout: 5000 });
    assert.equal(await phone.locator('[data-setting="touchControls"]').getAttribute('aria-checked'), 'true');
    await action(phone, 'back');
    await action(phone, 'resume');
    assert.equal(await phone.locator('#touch-controls').isVisible(), true);
    } catch (error) {
      const trace = await phone.evaluate(() => ({ trace: window.__qaTouchTrace, flag: window.__neonRush.save.settings.touchControls,
        state: window.__neonRush.state, modal: window.__neonRush.modal, panel: document.querySelector('#panel').innerText,
        aria: document.querySelector('[data-setting="touchControls"]')?.getAttribute('aria-checked') }));
      console.error('TOUCH DIAGNOSTICS', JSON.stringify(trace));
      await writeFile(path.join(project, 'artifacts', 'touch-diagnostics.json'), JSON.stringify(trace, null, 2));
      await phone.screenshot({ path: path.join(screenshots, 'touch-failure.png') });
      await phone.evaluate(() => { window.__neonRush.showHome(); window.__neonRush.start(); window.__neonRush.pause(); });
      throw error;
    }
  });

  await check('landscape mobile resizes canvas and menus correctly', async () => {
    await phone.evaluate(() => window.__neonRush.pause());
    await phone.setViewportSize({ width: 844, height: 390 });
    await phone.waitForTimeout(100);
    assert.equal(await phone.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    assert.ok(await phone.locator('#world').evaluate(canvas => canvas.width > canvas.height));
    await phone.screenshot({ path: path.join(screenshots, 'mobile-landscape.png') });
    assert.equal(await phone.locator('#panel button').filter({ hasText: /resume/i }).isVisible(), true);
  });
  await check('short landscape settings panel scrolls with a real touch gesture', async () => {
    await phone.setViewportSize({ width: 844, height: 320 });
    await action(phone, 'settings');
    const panel = phone.locator('#panel');
    assert.ok(await panel.evaluate(element => element.scrollHeight > element.clientHeight), 'short settings panel should overflow');
    const box = await panel.boundingBox();
    const x = box.x + box.width * 0.42;
    const fromY = box.y + box.height * 0.82;
    const toY = box.y + box.height * 0.25;
    const cdp = await mobile.newCDPSession(phone);
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: fromY }] });
    for (let i = 1; i <= 8; i++) {
      await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: fromY + (toY - fromY) * i / 8 }] });
      await phone.waitForTimeout(20); // Give this deliberate swipe a realistic 160ms duration.
    }
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    await phone.waitForFunction(() => document.querySelector('#panel').scrollTop > 0);
    await cdp.detach();
    await action(phone, 'back');
    assert.equal(await read(phone, () => window.__neonRush.modal), 'pause');
  });
  await mobile.close();

  await check('loaded game continues to play with the network offline', async () => {
    await desktop.setOffline(true);
    await action(page, 'play');
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('ArrowUp');
    assert.equal(await read(page, () => window.__neonRush.model.player.lane), 2);
    await page.waitForFunction(() => window.__neonRush.model.score > 0);
    assert.equal(await read(page, () => window.__neonRush.state), 'playing');
    await desktop.setOffline(false);
  });

  await check('all assets load and no browser runtime errors occur', async () => {
    assert.deepEqual(errors, []);
  });
  await desktop.close();
} finally {
  await browser?.close();
  server.kill('SIGTERM');
  await writeFile(path.join(project, 'artifacts', 'browser-results.json'), JSON.stringify({ date: new Date().toISOString(), results, errors }, null, 2));
}
const failed = results.filter(result => !result.passed);
console.log(`${results.length - failed.length}/${results.length} browser checks passed`);
if (failed.length) process.exitCode = 1;
