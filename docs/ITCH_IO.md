# Publish NEON RUSH on itch.io

## Create the upload

1. From the project root, run `npm test`, then `npm run build`.
2. Confirm `artifacts/neon-rush-itch.zip` exists. The build verifies the archive and that `index.html` is at the archive root. Upload this ZIP itself, not the separate `neon-rush-source.zip` developer archive, the source directory, `node_modules`, or a ZIP containing an extra `dist` parent folder. Both archives receive SHA-256 checksum files.
3. Run `npm run preview` and exercise the built game before uploading. For another port, use `npm run preview -- --port 8080`.

## Configure the itch.io page

1. Sign in to itch.io and choose **Dashboard → Create new project**.
2. Set the title to **NEON RUSH: Escape the Void**.
3. Choose **Kind of project: HTML** and **Classification: Games**.
4. Upload `artifacts/neon-rush-itch.zip` and select **This file will be played in the browser**.
5. Set the embedded viewport to **960 × 640** as a comfortable desktop default. Enable the fullscreen button and mobile-friendly / touch options offered by the editor. The canvas adapts to the actual iframe dimensions and both phone orientations.
6. Avoid enabling automatic startup sound: this game initializes audio after the player's first gesture. Use the game's own independent Music and SFX switches.
7. Add a description, tags, and cover image. Upload the supplied `assets/cover.png` (630 × 500). Editable vector artwork is also supplied as `assets/cover.svg`. Review text cropping in the page thumbnail.
8. Save the page as a **draft** and open its preview. Test Play, pause/resume, keyboard focus, full screen, mobile swipes, settings, and retry inside the actual iframe. Click inside the game before using keyboard controls.
9. Reload and verify that the best score persists when browser storage is permitted. Test audio after pressing a button. Confirm that no files request external CDNs and no requests fail.
10. When satisfied, choose the page visibility and publish it through itch.io. Building or uploading the archive does not automatically publish the page.

For a free release with optional donations, use itch.io's own pricing settings. No in-game processor or account is needed. An optional external support link is controlled by the initially empty `DONATION_URL` in `src/config.js`; rebuild and upload again after changing it. Keep the game fully playable regardless of support.

## Suggested description

**One cube. Three lanes. No way back.**

The void is closing in. Race through an electric tunnel, dodge neon barriers, jump low blocks, and slip past moving laser gates. Collect energy crystals to build your combo as the corridor gets faster and the patterns get sharper.

Chase a new personal best in quick arcade runs. Unlock three cosmetic cube skins through play. No login, downloads, or purchases required — just press Play and see how far you can go.

- Fast three-lane survival with procedural, reachable routes.
- Responsive keyboard controls, touch swipes, and optional on-screen buttons.
- Original neon visuals and synthesized electronic music.
- Local records, gameplay achievements, and cosmetic unlocks.
- Independent music / effects controls and a reduced-motion setting.

**Controls:** A/D or Left/Right to change lanes. Space or Up to jump. P or Escape to pause. On mobile, swipe left/right/up or use the touch buttons.

**Tip:** Short blocks can be jumped. Tall barriers and laser gates need a lane change. Follow crystal lines when it is safe — your combo is worth the risk.

Records and settings are stored locally in your browser. Browser privacy restrictions or clearing site data can remove them.

## Recommended tags and metadata

- Genre: Arcade.
- Tags: Endless Runner, Neon, Cyberpunk, Fast-Paced, High Score, Singleplayer, Procedural Generation, HTML5, Touch-Friendly, Short.
- Platforms: HTML5 / browser.
- Inputs: Keyboard, touchscreen.
- Languages: English; concise visual controls suit international players.
- Session length: about 30 seconds to 3 minutes, depending on skill.
- Accessibility: reduced-motion setting, independent audio controls, large touch buttons.

Do not advertise tested browser or device coverage beyond the actual release checks. The game is designed for modern Chrome, Firefox, Safari, and Edge; browser testing in the current environment does not replace checks on physical mobile devices.

## Cover-art concept

The supplied original vector cover uses a deep midnight-purple field, a cyan perspective tunnel with magenta outer rails, and a luminous isometric energy cube pulling a neon trail toward the viewer. Large white **NEON RUSH** lettering stays readable at thumbnail size; **ESCAPE THE VOID** is the smaller cyan subtitle. A few golden crystal highlights add contrast. Everything is vector geometry and system typography, with no third-party assets or external fonts.

Suggested page colors: background `#080713`, panel `#121020`, accent `#49f7ed`, secondary accent `#ff3cb5`, text `#f3f5ff`.
