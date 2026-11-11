/** Shared, device-independent rules. Distances are corridor units. */
export const CONFIG = Object.freeze({
  START_SPEED: 25,
  MAX_SPEED: 54,
  FAR: 160,
  PLAYER_HALF_WIDTH: 0.18,
  OBSTACLE_HALF_WIDTH: 0.3,
  HIT_DEPTH: 0.7,
  LANE_RESPONSE: 20,
  JUMP_DURATION: 0.78,
  JUMP_HEIGHT: 1.35,
  BLOCK_CLEARANCE: 0.54,
  MIN_ROW_GAP: 29,
  SAFE_LANE_TIME: 0.22,
  REACTION_ALLOWANCE: 0.28,
  MAX_COMBO: 8,
  CRYSTAL_POINTS: 25,
});

// Set a full HTTPS URL to show the optional support link. Never gates play.
export const DONATION_URL = '';
