import test from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_SAVE, SAVE_KEY, SKINS, loadSave, saveData } from '../src/save.js';

function memoryStorage(initial = null) {
  let value = initial;
  return { getItem(key) { assert.equal(key, SAVE_KEY); return value; }, setItem(key, next) { assert.equal(key, SAVE_KEY); value = next; } };
}

test('progress, cosmetics, and independent sound preferences survive storage round trip', () => {
  const storage = memoryStorage();
  const data = loadSave(storage);
  Object.assign(data, { best: 6543, totalCrystals: 35, runs: 12, tutorialDone: true, selectedSkin: 'solar' });
  data.settings.music = false;
  data.settings.sfx = true;
  data.settings.reducedMotion = true;
  assert.equal(saveData(data, storage), true);
  assert.deepEqual(loadSave(storage), data);
});

test('malformed, missing, and inaccessible storage never prevent play', () => {
  for (const value of [null, '{broken', 'null', '42', '[]']) {
    assert.equal(loadSave(memoryStorage(value)).best, 0);
  }
  const unavailable = { getItem() { throw new Error('blocked'); }, setItem() { throw new Error('quota'); } };
  assert.equal(loadSave(unavailable).selectedSkin, 'cyan');
  assert.equal(saveData(DEFAULT_SAVE, unavailable), false);
  assert.equal(saveData(DEFAULT_SAVE, null), false);
});

test('numeric corruption, locked selections, and invalid preference values are normalized', () => {
  const data = loadSave(memoryStorage(JSON.stringify({ best: -100, runs: '10', totalCrystals: 25.8, selectedSkin: 'violet', settings: { music: 'false', sfx: false } })));
  assert.equal(data.best, 0);
  assert.equal(data.runs, 0);
  assert.equal(data.totalCrystals, 25);
  assert.equal(data.selectedSkin, 'cyan');
  assert.equal(data.settings.music, true);
  assert.equal(data.settings.sfx, false);
  assert.equal(loadSave(memoryStorage('{"selectedSkin":"unknown"}')).selectedSkin, 'cyan');
});

test('each achievement unlocks exactly its cosmetic without balance changes', () => {
  const data = loadSave(memoryStorage());
  assert.deepEqual(SKINS.filter(skin => skin.unlocked(data)).map(skin => skin.id), ['cyan']);
  data.totalCrystals = 25;
  assert.equal(SKINS.find(skin => skin.id === 'solar').unlocked(data), true);
  data.best = 2500;
  assert.equal(SKINS.find(skin => skin.id === 'violet').unlocked(data), true);
  data.runs = 10;
  assert.equal(SKINS.every(skin => skin.unlocked(data)), true);
  for (const skin of SKINS) assert.deepEqual(Object.keys(skin).sort(), ['color', 'id', 'name', 'requirement', 'secondary', 'unlocked']);
});

test('loading creates fresh defaults without mutating shared preferences', () => {
  const first = loadSave(memoryStorage());
  first.settings.music = false;
  const second = loadSave(memoryStorage());
  assert.equal(second.settings.music, true);
  assert.equal(DEFAULT_SAVE.settings.music, true);
});
