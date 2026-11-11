import { CONFIG as C } from './config.js';

const clamp = (x, lo, hi) => Math.max(lo, Math.min(hi, x));

/** Stable 32-bit generator: the same seed always produces the same course. */
export function seededRandom(seed = 1) {
  let state = Number(seed) >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ value >>> 15, value | 1);
    value ^= value + Math.imul(value ^ value >>> 7, value | 61);
    return ((value ^ value >>> 14) >>> 0) / 4294967296;
  };
}

const isHazard = (cell) => cell && cell.type !== 'crystal';

/**
 * Generation reserves one clear lane at every row. Adjacent reserved lanes are
 * at most one lane apart and have enough time for reaction plus movement even
 * at maximum speed. Lasers move only inside their own lane, outside that path.
 */
export class ObstacleGenerator {
  constructor(seed = 1) {
    this.random = seededRandom(seed);
    this.index = 0;
    this.worldZ = 22;
    this.safeLane = 1;
    this.last = null;
    this.slalomDirection = 1;
  }

  next() {
    const random = this.random;
    const age = this.worldZ / 35;
    const level = clamp(age / 100, 0, 1);
    let pattern = 'opening';
    let safe = this.safeLane;
    if (age >= 2.5) {
      const choices = ['barrier'];
      if (age >= 7) choices.push('hurdles');
      if (age >= 14) choices.push('laser');
      if (age >= 24) choices.push('slalom');
      if (age >= 35) choices.push('gauntlet');
      pattern = choices[Math.floor(random() * choices.length)];
      if (pattern === 'slalom') {
        if (safe === 0) this.slalomDirection = 1;
        if (safe === 2) this.slalomDirection = -1;
        safe += this.slalomDirection;
      } else if (random() < 0.58) {
        safe = clamp(safe + (random() < 0.5 ? -1 : 1), 0, 2);
      }
    }
    const lanes = [null, null, null];
    lanes[safe] = { type: 'crystal', collected: false };
    const hazards = [0, 1, 2].filter((lane) => lane !== safe);
    if (pattern !== 'opening') {
      const first = hazards[Math.floor(random() * hazards.length)];
      const type = pattern === 'hurdles' ? 'block' : pattern === 'laser' ? 'laser' : 'barrier';
      lanes[first] = { type, phase: random() * Math.PI * 2, offset: 0 };
      if (pattern === 'gauntlet' || pattern === 'slalom' || random() < level * 0.6) {
        const second = hazards.find((lane) => lane !== first);
        lanes[second] = {
          type: pattern === 'gauntlet' ? (random() < 0.5 ? 'laser' : 'block') : type,
          phase: random() * Math.PI * 2,
          offset: 0,
        };
      }
    }
    const row = { id: this.index++, worldZ: this.worldZ, z: this.worldZ,
      safeLane: safe, pattern, lanes, passed: false };
    const previous = this.last;
    // Runtime validation is cheap and makes the reachability invariant explicit.
    if (!validateRow(row, previous)) throw new Error('Unsafe generated row');
    this.last = row;
    this.safeLane = safe;
    this.worldZ += C.MIN_ROW_GAP + (1 - level) * 9 + random() * 6;
    return row;
  }
}

export function validateRow(row, previous = null) {
  if (!row || !Array.isArray(row.lanes) || row.lanes.length !== 3) return false;
  if (!Number.isInteger(row.safeLane) || row.safeLane < 0 || row.safeLane > 2) return false;
  if (isHazard(row.lanes[row.safeLane])) return false;
  if (!previous) return true;
  const delta = Math.abs(row.safeLane - previous.safeLane);
  const available = (row.worldZ - previous.worldZ) / C.MAX_SPEED;
  return delta <= 1 && available >= delta * C.SAFE_LANE_TIME + C.REACTION_ALLOWANCE;
}

export function generateSequence(seed, count = 100) {
  const generator = new ObstacleGenerator(seed);
  return Array.from({ length: count }, () => generator.next());
}

/** Pure gameplay state. UI owns pause, screen transitions, audio and persistence. */
export class Model {
  constructor({ seed = 1, onEvent = () => {} } = {}) {
    this.onEvent = onEvent;
    this.reset(seed);
  }

  reset(seed = this.seed) {
    this.seed = Number(seed) >>> 0;
    this.generator = new ObstacleGenerator(this.seed);
    this.player = { lane: 1, x: 0, height: 0, jump: 0, jumping: false };
    this.rows = [];
    this.speed = C.START_SPEED;
    this.distance = 0;
    this.elapsed = 0;
    this.bonus = 0;
    this.score = 0;
    this.combo = 1;
    this.streak = 0;
    this.crystals = 0;
    this.dead = false;
    this.visitedLanes = new Set([1]);
    this.jumped = false;
    this.fillRows();
    return this;
  }

  fillRows() {
    while (this.generator.worldZ - this.distance < C.FAR + 45) {
      const row = this.generator.next();
      row.z = row.worldZ - this.distance;
      this.rows.push(row);
    }
  }

  move(direction) {
    if (this.dead || !Number.isFinite(direction) || direction === 0) return false;
    const lane = clamp(this.player.lane + Math.sign(direction), 0, 2);
    if (lane === this.player.lane) return false;
    this.player.lane = lane;
    this.visitedLanes.add(lane);
    this.onEvent('move', { lane });
    return true;
  }

  jump() {
    if (this.dead || this.player.jumping) return false;
    this.player.jumping = true;
    this.player.jump = 0;
    this.jumped = true;
    this.onEvent('jump', {});
    return true;
  }

  update(dt) {
    if (this.dead || !Number.isFinite(dt) || dt <= 0) return;
    // Never fast-forward a hidden tab. Small fixed substeps prevent tunnelling.
    let remaining = Math.min(dt, 0.25);
    while (remaining > 1e-8 && !this.dead) {
      const step = Math.min(remaining, 1 / 120);
      this.step(step);
      remaining -= step;
    }
  }

  step(dt) {
    this.elapsed += dt;
    this.speed = C.START_SPEED + (C.MAX_SPEED - C.START_SPEED) * (1 - Math.exp(-this.elapsed / 70));
    this.distance += this.speed * dt;
    const player = this.player;
    player.x += ((player.lane - 1) - player.x) * (1 - Math.exp(-C.LANE_RESPONSE * dt));
    if (player.jumping) {
      player.jump = Math.min(1, player.jump + dt / C.JUMP_DURATION);
      player.height = Math.sin(player.jump * Math.PI) * C.JUMP_HEIGHT;
      if (player.jump >= 1) {
        player.jumping = false;
        player.height = 0;
        player.jump = 0;
      }
    }
    for (const row of this.rows) {
      const previousZ = row.z;
      row.z = row.worldZ - this.distance;
      for (let lane = 0; lane < 3; lane++) {
        const cell = row.lanes[lane];
        if (!cell) continue;
        if (cell.type === 'laser') cell.offset = Math.sin(this.elapsed * 3 + cell.phase) * 0.12;
        const center = lane - 1 + (cell.offset || 0);
        const lateral = Math.abs(player.x - center);
        if (cell.type === 'crystal') {
          if (!cell.collected && row.z <= 0.85 && previousZ >= -0.85 && lateral < 0.48) {
            cell.collected = true;
            this.crystals++;
            this.streak++;
            const oldCombo = this.combo;
            this.combo = Math.min(C.MAX_COMBO, 1 + Math.floor(this.streak / 5));
            const points = C.CRYSTAL_POINTS * this.combo;
            this.bonus += points;
            this.onEvent('collect', { lane, points, combo: this.combo, crystals: this.crystals });
            if (this.combo !== oldCombo) this.onEvent('combo', { combo: this.combo });
          }
        } else if (row.z <= C.HIT_DEPTH && row.z >= -C.HIT_DEPTH && lateral < C.PLAYER_HALF_WIDTH + C.OBSTACLE_HALF_WIDTH) {
          if (cell.type !== 'block' || player.height < C.BLOCK_CLEARANCE) {
            this.dead = true;
            this.score = Math.floor(this.distance * 4 + this.bonus);
            this.onEvent('collision', { lane, type: cell.type, score: this.score });
            break;
          }
        }
      }
      if (this.dead) break;
      if (row.z < -C.HIT_DEPTH && !row.passed) {
        row.passed = true;
        const missed = row.lanes.some((cell) => cell?.type === 'crystal' && !cell.collected);
        if (missed) {
          this.streak = 0;
          this.combo = 1;
        }
        const near = row.lanes.some((cell, lane) => isHazard(cell) && Math.abs(player.x - (lane - 1)) < 0.8);
        if (near) this.onEvent('near', { pattern: row.pattern });
      }
    }
    this.score = Math.floor(this.distance * 4 + this.bonus);
    this.rows = this.rows.filter((row) => row.z > -15);
    this.fillRows();
  }
}
