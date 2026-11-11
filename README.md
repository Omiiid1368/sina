# NEON RUSH: Escape the Void

A three-lane, pseudo-3D arcade runner. Pilot an energy cube through an endless neon tunnel, jump low blocks, change lanes around barriers and laser gates, and chain energy crystals for a higher score. Original graphics render on Canvas; original music and effects are synthesized with Web Audio.

No account, backend, analytics, runtime CDN, or paid service is required. Gameplay continues offline after the page and its local modules have loaded. Scores, achievements, cosmetics, and preferences stay in the browser's local storage when storage is available.

## Play and develop

Use Node.js 20 or newer. Python 3 is needed only to create the release ZIPs. The game and build have **zero npm dependencies**, so installation is unnecessary. Optional browser automation is documented below.

```sh
cd /workspace/sina
npm run dev
```

Open the server on port 4173 in a browser. Use a static server rather than opening `index.html` with `file://`; browser security rules restrict JavaScript modules on local files. To use another port, run `npm run dev -- --port 8080`.

```sh
npm test          # deterministic gameplay and persistence checks
npm run build    # static production files + verified release / source ZIPs
npm run preview  # serve the production build on port 4173
```

Build outputs:

- `dist/`: complete static site, with `index.html` at its root.
- `artifacts/neon-rush-itch.zip`: upload this archive to itch.io.
- `artifacts/neon-rush-source.zip`: complete editable source, scripts, tests, artwork, and documentation.
- `artifacts/*.zip.sha256`: archive checksums.

The build checks local module dependencies and archive integrity. It does not minify the source: the small, readable modules are already the browser-ready production code. Any static host supporting normal JavaScript MIME types can serve `dist/`. All game paths are relative, including when embedded below a URL subdirectory.

## Controls

| Action | Keyboard | Touch |
| --- | --- | --- |
| Move left | A or Left Arrow | Swipe left / left button |
| Move right | D or Right Arrow | Swipe right / right button |
| Jump | Space or Up Arrow | Swipe up / jump button |
| Pause / resume | P or Escape | Pause / Resume button |

Touch controls can be toggled in Settings. The game supports portrait and landscape layouts. Losing browser focus or switching tabs pauses an active run; resume explicitly to continue. Jump over short blocks. Full-height barriers and laser gates require a lane change. Obstacles are introduced gradually; generated rows retain a reachable safe corridor as speed rises.

The first run begins with a short interactive lesson: change lanes, then jump. Practice ends on completion or after eight seconds, resets the model, and starts a real run. Practice is unscored and grants no crystals, records, or completed-run achievements.

## Progress and settings

Distance earns points. Energy crystals add bonus points and increase a collection combo. Missing crystals breaks the chain. New personal records are celebrated and saved at the end of a run. ION is available immediately; three additional skins unlock through play:

| Skin | Achievement |
| --- | --- |
| SOLAR | Collect 25 lifetime crystals |
| PHANTOM | Reach a personal best of 2,500 points |
| AFTERGLOW | Complete 10 runs |

Skins have identical gameplay behavior.

Music and effects can be muted independently. Audio starts only after a player gesture, respecting browser autoplay rules. Settings also include reduced motion and optional on-screen controls. Reduced motion limits camera movement and decorative effects without changing obstacle timing. Rendering adapts its visual detail when frame performance falls.

If local storage is blocked or full, the game still works with in-memory progress for that page session. Clearing site data removes records, unlocks, and preferences. In an itch.io embed, storage availability depends on browser privacy settings.

## Architecture

The application uses native JavaScript ES modules. Canvas draws the tunnel and game objects, while accessible HTML buttons and panels provide menus and settings.

| Location | Responsibility |
| --- | --- |
| `index.html`, `styles.css` | Responsive interface and game shell |
| `src/` | Renderer, state orchestration, player model, seeded generation, scoring, collisions, audio, and persistence |
| `tests/` | Node model / persistence suites and optional Playwright browser integration checks |
| `scripts/serve.mjs` | Dependency-free development / production preview server |
| `scripts/build.mjs` | Static release copy, dependency verification, ZIP and checksum |
| `assets/cover.svg`, `assets/cover.png` | Original cover artwork and upload-ready 630 × 500 PNG |
| `docs/ITCH_IO.md` | Publishing instructions and store copy |

To change optional monetization, set `DONATION_URL` in `src/config.js` to the developer's support page, then rebuild. Leave it empty to hide the link. No payment processing is integrated and play never depends on donating.

## Release checks

Before publishing a modified game, run the test suite, build, and serve `dist/`. Exercise Play → collect / dodge / jump → pause → resume → collision → retry. Check Settings, How to Play, Skins, record persistence across reload, touch swipes, portrait and landscape layouts, and mute behavior after a gesture. Confirm browser console and network requests are clean. Test the extracted ZIP as a static site as well.

### Optional automated browser checks

`tests/browser.mjs` exercises the built game in real Chromium with desktop, portrait touch, and landscape touch viewports. It checks menus, keyboard input, touch buttons and swipes, independent audio settings, pause/resume, tutorial rewards and timeout, restart, collisions, saved records, offline continuation, missing assets, and runtime errors. It starts and stops its own static server on port 4185. Set `QA_PORT` to select another port. Results and screenshots are written under `artifacts/`.

Playwright and Chromium are test tools only; neither is shipped in the game or required for development/build. In the managed environment, Playwright and `/usr/bin/chromium` are already available. Elsewhere, install optional automation without adding a package dependency or lockfile:

```sh
npm install --no-save --package-lock=false playwright
npm run build
CHROMIUM_PATH=/path/to/chromium npm run test:browser
```

`CHROMIUM_PATH` defaults to `/usr/bin/chromium`. If you need a browser download, run `npx playwright install chromium`, then `node -e "console.log(require('playwright').chromium.executablePath())"` to find its executable and supply that path through `CHROMIUM_PATH`. Linux machines also need Chromium's standard system libraries. The browser suite is separate from `npm test` and fails with a missing-tool error when optional automation is unavailable.

Current execution evidence and browser limitations are recorded in [docs/QA.md](docs/QA.md) and reported with delivery; passing unit tests alone do not establish browser compatibility. Target browsers are modern Chrome, Firefox, Safari, and Edge. Test real iOS/Android devices before claiming device-wide certification.

All shipped visuals and synthesized audio are created for this project. No external art, font, audio, or engine files are fetched at runtime. See [the publishing guide](docs/ITCH_IO.md) for itch.io setup, suggested tags and description, and the cover-art concept.
