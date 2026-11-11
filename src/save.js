/** Local-only progress and preferences. The game also works when storage is blocked. */
export const SAVE_KEY = 'neon-rush.save.v1';
export const DEFAULT_SAVE = Object.freeze({
  version: 1,
  best: 0,
  totalCrystals: 0,
  runs: 0,
  selectedSkin: 'cyan',
  tutorialDone: false,
  settings: Object.freeze({ music: true, sfx: true, reducedMotion: false, touchControls: true }),
});

export const SKINS = Object.freeze([
  Object.freeze({ id: 'cyan', name: 'ION', color: '#5efbff', secondary: '#1285d6', requirement: 'Standard issue', unlocked: () => true }),
  Object.freeze({ id: 'solar', name: 'SOLAR', color: '#ffd76a', secondary: '#f57d32', requirement: 'Collect 25 crystals', unlocked: data => data.totalCrystals >= 25 }),
  Object.freeze({ id: 'violet', name: 'PHANTOM', color: '#c6a0ff', secondary: '#7444ec', requirement: 'Reach 2,500 points', unlocked: data => data.best >= 2500 }),
  Object.freeze({ id: 'rose', name: 'AFTERGLOW', color: '#ff70bf', secondary: '#c72e82', requirement: 'Complete 10 runs', unlocked: data => data.runs >= 10 }),
]);

function systemReducedMotion() {
  try { return globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true; }
  catch { return false; }
}

function integer(value, maximum) {
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.min(maximum, Math.floor(value))) : 0;
}

function normalize(input, useSystemPreference = false) {
  const value = input && typeof input === 'object' && !Array.isArray(input) ? input : {};
  const settings = value.settings && typeof value.settings === 'object' ? value.settings : {};
  const data = {
    version: 1,
    best: integer(value.best, 1e12),
    totalCrystals: integer(value.totalCrystals, 1e9),
    runs: integer(value.runs, 1e9),
    selectedSkin: typeof value.selectedSkin === 'string' ? value.selectedSkin : 'cyan',
    tutorialDone: value.tutorialDone === true,
    settings: {},
  };
  for (const [key, fallback] of Object.entries(DEFAULT_SAVE.settings)) {
    data.settings[key] = typeof settings[key] === 'boolean' ? settings[key] :
      key === 'reducedMotion' && useSystemPreference ? systemReducedMotion() : fallback;
  }
  const skin = SKINS.find(item => item.id === data.selectedSkin);
  if (!skin || !skin.unlocked(data)) data.selectedSkin = 'cyan';
  return data;
}

function storageOrDefault(storage) {
  if (storage !== undefined) return storage;
  try { return globalThis.localStorage; } catch { return null; }
}

export function loadSave(storage) {
  try {
    const raw = storageOrDefault(storage)?.getItem(SAVE_KEY);
    return normalize(raw ? JSON.parse(raw) : null, true);
  } catch {
    return normalize(null, true);
  }
}

/** Returns false when storage is unavailable or full; in-memory play is unaffected. */
export function saveData(data, storage) {
  try {
    const destination = storageOrDefault(storage);
    if (!destination) return false;
    destination.setItem(SAVE_KEY, JSON.stringify(normalize(data)));
    return true;
  } catch { return false; }
}
