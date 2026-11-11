# Release validation — 2026-11-11

## Automated gameplay and save tests

`npm test`: **17 passed, 0 failed, 0 skipped**.

- Seed reproducibility and 100,000 generated rows with a reserved reachable route.
- Rejection of blocked safe lanes and impossible two-lane transitions.
- Bounded, smooth steering; complete jump arcs; jumpable blocks and solid tall hazards.
- Single collision events, scoring, crystal combos, missed-collection resets, and clean restarts.
- Four full three-minute simulated safe-path runs.
- Eight-minute runs at maximum speed at 30 and 12 FPS; laser movement and collision timing at 30/60/120 FPS.
- Save round trips, malformed or unavailable storage, preference validation, and all cosmetic achievements.

## Production browser checks

`npm run build` followed by `npm run test:browser`: **16 passed, 0 failed** on the final tested runtime files in Chromium. No JavaScript, console, request, or missing-asset errors were observed.

Desktop viewport: 1440 × 900. Touch viewports: 390 × 844, 844 × 390, and a short 844 × 320 landscape menu. Mobile tests use actual Chromium touch input dispatch, in addition to on-screen button taps.

Verified:

- Initial menu and canvas render; empty donation URL keeps support hidden.
- How to Play, Skins, Settings, and their return navigation.
- Independent Music/SFX preferences, reduced motion, and persistence across reload.
- User-gesture audio initialization, a running Web Audio context, soundtrack scheduling, and music stopping on pause.
- Real keyboard lane changes and jump input.
- Pause freezes simulation; resume continues; restart resets the run.
- Collision, game over, retry, and persisted personal records.
- Blur pauses safely without automatically resuming on focus.
- Practice gives no records or achievement progress, even if abandoned; untouched practice ends automatically and resets into a scored run.
- Mobile layout, touch buttons, real left/right/up swipes, and no page scrolling during play.
- Touch-button settings from pause preserve the run.
- Landscape canvas resizing and real touch scrolling within overflowing settings.
- Gameplay continues when the browser network is switched offline after initial loading.

The runner writes `artifacts/browser-results.json` and screenshots to `artifacts/screenshots/`. On a failed mobile setting assertion, it also captures pointer/touch/click targets and a screenshot.

An earlier synthetic touch run intermittently missed a settings-toggle activation. Its cause was not established. The final suite passed, and a focused repetition of the touch-controls → swipe → settings sequence passed **50 of 50** toggles. Standard native button click handling is retained. Real-device checks are still recommended, and a future recurrence should be investigated using the captured event trace.

## Release and visual checks

- Both archives pass ZIP CRC validation and contain `index.html` at their root. The source archive includes build scripts, tests, documentation, and original assets; neither archive includes Git metadata or dependencies.
- The upload ZIP was extracted to a separate temporary directory and served below a nested URL path inside a **960 × 640 iframe**. Loading, keyboard steering/jumping, tutorial completion, running simulation, pause, and resume passed with no runtime or asset errors.
- Desktop, small portrait, tablet, landscape, and the recommended iframe layout were visually inspected. Icons use SVG/CSS where system glyphs varied. Full barriers and laser gates are taller than the maximum jump so their graphics match the collision rules.
- The static server's GET/HEAD handling, JavaScript MIME type, missing files, malformed URL handling, hidden paths, traversal prevention, and external symlink containment were checked. The documented custom-port override was exercised.
- All game graphics and sounds are generated locally. Runtime modules and assets use relative paths; there are no CDN, analytics, account, or backend requests.

## Limits of validation

Browser automation used Chromium on Linux. Firefox, Safari/WebKit, Edge, physical iOS/Android devices, and an actual uploaded itch.io page were **not tested**. A local iframe check does not establish itch.io hosting or browser privacy behavior. The game has not been published to itch.io.

The adaptive renderer reduces effects and pixel density under sustained slow frames. A short headless software-rendering sample reached a 16.7 ms median frame interval with occasional slower frames; this is not a blanket 60 FPS guarantee. Real device and audio listening checks remain useful before public release.
