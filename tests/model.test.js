import test from 'node:test';
import assert from 'node:assert/strict';
import { CONFIG as C } from '../src/config.js';
import { Model, ObstacleGenerator, generateSequence, validateRow } from '../src/model.js';

const tick = (model, seconds) => { for (let t = 0; t < seconds; t += 1 / 120) model.update(1 / 120); };

test('seeded generation is deterministic and different seeds vary', () => {
  assert.deepEqual(generateSequence(1234, 80), generateSequence(1234, 80));
  assert.notDeepEqual(generateSequence(1234, 80), generateSequence(1235, 80));
});

test('100,000 generated rows reserve a physically reachable path at maximum speed', () => {
  const patterns = new Set();
  for (let seed = 1; seed <= 100; seed++) {
    const rows = generateSequence(seed, 1000);
    rows.forEach((row, i) => {
      patterns.add(row.pattern);
      assert.equal(validateRow(row, rows[i - 1]), true, `seed ${seed}, row ${i}`);
      assert.ok(!row.lanes[row.safeLane] || row.lanes[row.safeLane].type === 'crystal');
    });
  }
  for (const pattern of ['opening', 'barrier', 'hurdles', 'laser', 'slalom', 'gauntlet']) assert.ok(patterns.has(pattern));
});

test('reachability validator rejects a forced two-lane leap and blocked safe lane', () => {
  const a = { worldZ: 0, safeLane: 0, lanes: [null, null, null] };
  assert.equal(validateRow({ worldZ: 30, safeLane: 2, lanes: [null, null, null] }, a), false);
  assert.equal(validateRow({ worldZ: 30, safeLane: 0, lanes: [{ type: 'barrier' }, null, null] }, a), false);
  assert.equal(validateRow({ worldZ: 2, safeLane: 1, lanes: [null, null, null] }, a), false);
});

test('movement is bounded, responsive and smooth; jump follows a complete arc', () => {
  const events = [];
  const model = new Model({ onEvent: (event) => events.push(event) });
  assert.equal(model.move(-1), true);
  assert.equal(model.move(-1), false);
  model.update(1 / 60);
  assert.ok(model.player.x < 0 && model.player.x > -1);
  tick(model, 0.2);
  assert.ok(model.player.x < -0.98);
  assert.equal(model.jump(), true);
  assert.equal(model.jump(), false);
  tick(model, C.JUMP_DURATION / 2);
  assert.ok(model.player.height > 1.3);
  tick(model, C.JUMP_DURATION / 2 + 0.02);
  assert.equal(model.player.height, 0);
  assert.equal(model.player.jumping, false);
  assert.ok(events.includes('move') && events.includes('jump'));
});

function place(model, type, z = 4, lane = 1) {
  model.rows = [{ id: 500, z, worldZ: model.distance + z, lanes: [null, null, null], passed: false }];
  model.rows[0].lanes[lane] = { type, phase: 0, offset: 0 };
  model.generator.worldZ = 500;
}

test('collision kills once and prevents further gameplay updates', () => {
  const events = [];
  const model = new Model({ onEvent: (event) => events.push(event) });
  place(model, 'barrier');
  tick(model, 0.3);
  assert.equal(model.dead, true);
  assert.equal(events.filter((event) => event === 'collision').length, 1);
  const distance = model.distance;
  tick(model, 1);
  assert.equal(model.distance, distance);
  assert.equal(model.move(1), false);
});

test('jump clears low blocks, but tall barriers remain hazardous', () => {
  const jumper = new Model();
  place(jumper, 'block', 8);
  jumper.jump();
  tick(jumper, 0.5);
  assert.equal(jumper.dead, false);
  const grounded = new Model();
  place(grounded, 'block', 8);
  tick(grounded, 0.5);
  assert.equal(grounded.dead, true);
  const tall = new Model();
  place(tall, 'barrier', 8);
  tall.jump();
  tick(tall, 0.5);
  assert.equal(tall.dead, true);
});

test('crystals add bonuses, build combos, and misses reset consecutive streak', () => {
  const model = new Model();
  for (let i = 0; i < 6; i++) {
    place(model, 'crystal', 1);
    tick(model, 0.1);
  }
  assert.equal(model.crystals, 6);
  assert.equal(model.combo, 2);
  assert.equal(model.bonus, 4 * 25 + 2 * 50);
  assert.equal(model.score, Math.floor(model.distance * 4 + model.bonus));
  place(model, 'crystal', 1, 0);
  tick(model, 0.15);
  assert.equal(model.combo, 1);
  assert.equal(model.streak, 0);
});

test('restart clears score, motion, rows, combos and events retain callback', () => {
  const model = new Model({ seed: 77 });
  model.move(1);
  model.jump();
  tick(model, 0.5);
  model.bonus = 1000;
  model.dead = true;
  model.reset();
  assert.equal(model.score, 0);
  assert.equal(model.dead, false);
  assert.equal(model.elapsed, 0);
  assert.equal(model.player.lane, 1);
  assert.equal(model.player.height, 0);
  assert.equal(model.combo, 1);
  assert.deepEqual(model.rows, new Model({ seed: 77 }).rows);
});

test('autopilot physically follows reserved path for a full three minute run', () => {
  for (const seed of [1, 7, 42, 1865]) {
    const model = new Model({ seed });
    for (let frame = 0; frame < 180 * 60 && !model.dead; frame++) {
      const next = model.rows.find((row) => row.z > -C.HIT_DEPTH);
      if (next) {
        const delta = next.safeLane - model.player.lane;
        if (delta) model.move(Math.sign(delta));
      }
      model.update(1 / 60);
    }
    assert.equal(model.dead, false, `seed ${seed}`);
    assert.ok(model.elapsed > 179);
    assert.ok(model.crystals > 80);
    assert.ok(model.speed > C.START_SPEED && model.speed < C.MAX_SPEED);
  }
});

test('non-finite updates and inputs do not corrupt state', () => {
  const model = new Model();
  model.update(NaN);
  model.update(-1);
  model.move(NaN);
  assert.equal(model.elapsed, 0);
  assert.equal(model.player.lane, 1);
  assert.ok(Number.isFinite(model.score));
});

test('safe path survives maximum speed at low frame rates, including oscillating lasers', () => {
  for (const frameTime of [1 / 30, 1 / 12]) {
    const model = new Model({ seed: 9 });
    // Fully matured speed from the first row stresses every generated pattern.
    model.elapsed = 1e6;
    const end = model.elapsed + 480;
    while (model.elapsed < end && !model.dead) {
      const next = model.rows.find(row => row.z > -C.HIT_DEPTH);
      if (next) {
        const delta = next.safeLane - model.player.lane;
        if (delta) model.move(Math.sign(delta));
      }
      model.update(frameTime);
    }
    assert.equal(model.speed, C.MAX_SPEED);
    assert.equal(model.dead, false, `frame time ${frameTime}`);
    assert.ok(model.crystals > 600);
  }
});

test('collision result is stable across 30, 60 and 120 FPS and rapid opposite-lane inputs', () => {
  const distances = [];
  for (const frameTime of [1 / 30, 1 / 60, 1 / 120]) {
    const model = new Model();
    place(model, 'barrier', 12, 2);
    model.move(-1);
    model.move(1);
    model.move(1);
    while (!model.dead && model.elapsed < 1) model.update(frameTime);
    assert.equal(model.dead, true);
    assert.equal(model.player.lane, 2);
    distances.push(model.distance);
  }
  assert.ok(Math.max(...distances) - Math.min(...distances) < 0.001);
});
