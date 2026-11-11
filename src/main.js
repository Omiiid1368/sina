import { Model } from './model.js';
import { DONATION_URL } from './config.js';
import { Renderer } from './renderer.js';
import { AudioEngine } from './audio.js';
import { loadSave, saveData, SKINS } from './save.js';

const $ = selector => document.querySelector(selector);
const pad = n => String(Math.floor(n)).padStart(6, '0');
const save = loadSave();
const audio = new AudioEngine(save.settings);
const renderer = new Renderer($('#world'));
let state = 'home', modal = null, lastTime = performance.now(), toastTimer;
let runBest = save.best, newRecord = false, committed = false, overDelay = 0;
let tutorialPhase = save.tutorialDone ? 3 : 0, tutorialDelay = 0;
let tutorialElapsed = 0, nextSaveAt = 0;
let unlockedAtStart = SKINS.filter(s => s.unlocked(save)).length;
let priorFocus = null, lastHud = '';
const model = new Model({ seed: randomSeed(), onEvent: onGameEvent });
const skin = () => SKINS.find(s => s.id === save.selectedSkin) || SKINS[0];

function randomSeed() {
  return globalThis.crypto?.getRandomValues(new Uint32Array(1))[0] ?? Date.now() >>> 0;
}
function persist() { saveData(save); }
function toast(text) {
  $('#toast').textContent = text; $('#toast').classList.add('visible');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => $('#toast').classList.remove('visible'), 1900);
}
function onGameEvent(name, payload) {
  audio.play(name);
  renderer.event(name, skin());
  if (name === 'collect' && model.combo > 1) toast(`+${payload.points} ENERGY · ×${model.combo}`);
  if (name === 'combo') toast(`FLOW STATE · ×${payload.combo} COMBO`);
  if (name === 'collision') endRun();
  if (name === 'move' && tutorialPhase === 0) { tutorialPhase = 1; updateTutorial(); }
  if (name === 'jump' && tutorialPhase === 1) { tutorialPhase = 2; tutorialDelay = 1; updateTutorial(); }
}
function applySettings() {
  renderer.reducedMotion = save.settings.reducedMotion;
  document.body.classList.toggle('reduced-motion', save.settings.reducedMotion);
  audio.setMusic(save.settings.music); audio.setSfx(save.settings.sfx);
  audio.setActive(state === 'playing');
  const audible = save.settings.music || save.settings.sfx;
  $('#quick-audio span').textContent = audible ? 'ON' : 'OFF';
  $('#quick-audio').setAttribute('aria-label', audible ? 'Mute all audio' : 'Enable audio');
  updateTouch();
}
function updateTouch() {
  $('#touch-controls').hidden = state !== 'playing' || !save.settings.touchControls ||
    (!matchMedia('(pointer: coarse)').matches && window.innerWidth > 760);
}
function updateHome() {
  $('#home-best').textContent = pad(save.best);
  $('#skin-count').textContent = `0${SKINS.filter(s => s.unlocked(save)).length} / 04`;
}
function setState(next) {
  state = next; document.body.classList.remove('home','playing','paused','over'); document.body.classList.add(next);
  $('#home').hidden = next !== 'home'; $('#hud').hidden = next === 'home';
  $('#tutorial').hidden = next !== 'playing' || tutorialPhase >= 3;
  $('#footer').hidden = next === 'over';
  audio.setActive(next === 'playing'); updateTouch();
}
function closeModal() {
  $('#overlay').hidden = true; modal = null;
  if (priorFocus?.isConnected && !priorFocus.closest('[hidden]')) priorFocus.focus({ preventScroll: true });
}
function showPanel(type, html) {
  if ($('#overlay').hidden) priorFocus = document.activeElement;
  modal = type; $('#panel').innerHTML = html; $('#overlay').hidden = false;
  requestAnimationFrame(() => $('#panel button:not([disabled])')?.focus({ preventScroll: true }));
}
const heading = (tag,title,close = true) => `<div class="panel-header"><div><div class="eyebrow">${tag}</div><h2 id="overlay-title">${title}</h2></div>${close ? '<button class="close" data-action="back" aria-label="Close dialog">×</button>' : ''}</div>`;

function commitRun(complete = false) {
  if (committed) return;
  committed = true;
  if (tutorialPhase < 3) return; // Practice never grants records or achievements.
  const before = unlockedAtStart;
  save.best = Math.max(save.best, model.score);
  save.totalCrystals += model.crystals;
  if (complete) save.runs++;
  persist(); updateHome();
  if (SKINS.filter(s => s.unlocked(save)).length > before) setTimeout(() => toast('NEW SKIN UNLOCKED · CHECK YOUR GARAGE'), 600);
}
function start() {
  if (state === 'playing' || state === 'paused') commitRun();
  audio.unlock(); closeModal();
  runBest = save.best; newRecord = false; committed = false; overDelay = 0;
  unlockedAtStart = SKINS.filter(s => s.unlocked(save)).length;
  renderer.particles = []; renderer.flash = 0; renderer.shake = 0;
  model.reset(randomSeed()); tutorialPhase = save.tutorialDone ? 3 : 0; tutorialDelay = 0; tutorialElapsed = 0;
  setState('playing'); updateTutorial(); updateHud();
  // Keep keyboard focus out of hidden menus and give controls immediate ownership.
  document.activeElement?.blur();
}
function pause() {
  if (state !== 'playing') return;
  setState('paused'); showPause();
}
function showPause() {
  showPanel('pause', `${heading('SIGNAL HELD','TAKE A BREATHER.',false)}<p>The void can wait. Your run is right where you left it.</p><button class="primary" data-action="resume"><span class="play-icon"></span> RESUME RUN</button><button class="full" data-action="restart">Restart</button><button class="full" data-action="settings">Settings</button><button class="text-button" data-action="home">Main menu</button>`);
}
function resume() {
  if (state !== 'paused' || document.hidden) return;
  audio.unlock(); closeModal(); lastTime = performance.now(); setState('playing'); document.activeElement?.blur();
}
function showHome() {
  if (state === 'playing' || state === 'paused') commitRun();
  closeModal(); setState('home'); updateHome(); $('#tutorial').hidden = true;
}
function endRun() {
  newRecord = model.score > runBest;
  commitRun(true); setState('over'); $('#tutorial').hidden = true;
  overDelay = .65;
  if (newRecord) setTimeout(() => { audio.play('record'); renderer.event('record',skin()); }, 500);
}
function showResults() {
  showPanel('over', `${heading(newRecord ? 'PERSONAL BEST UPDATED' : 'CONNECTION LOST',newRecord ? 'BEYOND YOUR LIMIT.' : 'THE VOID GOT YOU.',false)}${newRecord ? '<div class="record-banner">✦ &nbsp; NEW RECORD. NEW STANDARD.</div>' : '<p>Every run takes you a little further.</p>'}<div class="result-score"><span class="micro">FINAL SCORE</span>${pad(model.score)}</div><div class="stats"><div><span class="micro">BEST</span><strong>${pad(save.best)}</strong></div><div><span class="micro">DISTANCE</span><strong>${Math.floor(model.distance)} m</strong></div><div><span class="micro">ENERGY</span><strong>◇ ${model.crystals}</strong></div></div><button class="primary" data-action="play">↻ &nbsp; ONE MORE RUN</button><button class="full" data-action="home">Main menu</button>`);
}
function showSettings() {
  if (state === 'playing') setState('paused');
  const row = (key,title,desc) => `<div class="setting-row"><div>${title}<small>${desc}</small></div><button class="toggle" data-setting="${key}" role="switch" aria-label="${title}" aria-checked="${save.settings[key]}"></button></div>`;
  showPanel('settings', `${heading('PERSONALIZE YOUR SIGNAL','SETTINGS')}<p>Make the rush your own.</p>${row('music','Music','Original synth soundtrack')}${row('sfx','Sound effects','Movement, energy & impact')}${row('reducedMotion','Reduced motion','Less shake, fewer moving effects')}${row('touchControls','Touch buttons','Show controls on mobile screens')}<p class="settings-note">Progress and preferences stay on this device.</p><button class="primary" data-action="back">DONE</button>`);
}
function showSkins() {
  const cards = SKINS.map(s => {
    const unlocked = s.unlocked(save), selected = save.selectedSkin === s.id;
    return `<button class="skin-card ${unlocked?'':'locked'} ${selected?'selected':''}" data-skin="${s.id}" style="--skin:${s.color}" aria-label="${s.name}, ${selected?'selected':unlocked?'available':s.requirement}" ${unlocked?'':'aria-disabled="true"'}><span class="skin-status">${selected?'EQUIPPED':unlocked?'READY':'LOCKED'}</span><i class="skin-cube"></i><strong>${s.name}</strong><small>${s.requirement}</small></button>`;
  }).join('');
  showPanel('skins', `${heading('THE GARAGE','YOUR ENERGY. YOUR STYLE.')}<p>Earn your next look. Every cube plays the same.</p><div class="skin-grid">${cards}</div><div class="micro">${save.totalCrystals} CRYSTALS · ${save.runs} COMPLETED RUNS</div><button class="primary" data-action="back">LOOKING GOOD</button>`);
}
function showHelp() {
  showPanel('help', `${heading('FLIGHT MANUAL','STAY IN THE FLOW.')}<div class="help-item"><span class="help-glyph">⇆</span><div><strong>Move between three lanes</strong><small>← → or A / D · swipe left / right</small></div></div><div class="help-item"><span class="help-glyph">↑</span><div><strong>Jump over low gold blocks</strong><small>Space or ↑ · swipe up</small></div></div><div class="help-item"><span class="help-glyph">◇</span><div><strong>Follow the gold crystals</strong><small>Collect 5 in a row to build your multiplier.<br>Miss a crystal and your combo resets.</small></div></div><div class="legend"><span><i class="pink"></i>DODGE WALLS</span><span><i class="gold"></i>JUMP BLOCKS</span></div><p>Lasers always need a lane change. P or Escape pauses. Tap the on-screen arrows for touch controls.</p><button class="primary" data-action="back">GOT IT</button>`);
}
function back() { if (state === 'paused') showPause(); else closeModal(); }
function updateTutorial() {
  $('#tutorial').hidden = state !== 'playing' || tutorialPhase >= 3;
  const touch = matchMedia('(pointer: coarse)').matches;
  const steps = [
    ['01 / 02','FIND YOUR LANE',touch ? 'Swipe left or right, or tap an arrow' : 'Press ← / → or A / D to move','← &nbsp; →'],
    ['02 / 02','TAKE THE LEAP',touch ? 'Swipe up or tap Jump' : 'Press Space or ↑ to jump','↑'],
    ['SYSTEM SYNCED','YOU’RE READY','Follow the gold. Dodge the pink.','◇'],
  ];
  if (tutorialPhase < 3) {
    const [step,title,copy,keys] = steps[tutorialPhase];
    $('#tutorial-step').textContent=step; $('#tutorial-title').textContent=title; $('#tutorial-copy').textContent=copy; $('#tutorial-keys').innerHTML=keys;
  }
}
function updateHud() {
  if (tutorialPhase < 3) {
    $('#score').textContent = '000000'; $('#distance').textContent = 'PRACTICE';
    $('#crystals').textContent = '◇ 0'; $('#combo').textContent = '×1';
    $('#sector').textContent = 'GUIDED SYNC · RUN STARTS IN A MOMENT';
    $('#record-flag').hidden = true;
    return;
  }
  const score = pad(model.score), dist = Math.floor(model.distance), kmh = Math.round(model.speed*3.6);
  const signature = `${score}|${model.combo}|${dist}|${model.crystals}|${kmh}|${newRecord}`;
  if (signature === lastHud) return;
  lastHud = signature;
  $('#score').textContent=score; $('#combo').textContent=`×${model.combo}`;
  $('#distance').textContent=`${dist} M`; $('#crystals').textContent=`◇ ${model.crystals}`;
  $('#speed').textContent=`${kmh} KM/H`; $('#progress-line').style.width=`${(dist%1000)/10}%`;
  const sector = Math.min(5,1+Math.floor(model.elapsed/25));
  $('#sector').textContent=`SECTOR 0${sector} · ${['AWAKENING','ACCELERATION','AFTERBURN','OVERDRIVE','SINGULARITY'][sector-1]}`;
  if (!newRecord && model.score > runBest && runBest > 0) {
    newRecord = true; audio.play('record'); renderer.event('record',skin()); toast('NEW PERSONAL BEST · KEEP GOING');
  }
  $('#record-flag').hidden=!newRecord;
  if (model.score > save.best) {
    save.best=model.score;
    if (performance.now() >= nextSaveAt) { persist(); nextSaveAt = performance.now() + 1000; }
  }
}

document.addEventListener('click', event => {
  const target = event.target.closest('button');
  if (!target) return;
  audio.unlock();
  const action = target.dataset.action;
  if (action) {
    audio.play('menu');
    ({play:start,restart:start,resume,pause,home:showHome,settings:showSettings,skins:showSkins,help:showHelp,back})[action]?.();
  }
  if (target.dataset.setting) {
    const key=target.dataset.setting; save.settings[key]=!save.settings[key];
    target.setAttribute('aria-checked',String(save.settings[key])); persist(); applySettings();
  }
  if (target.dataset.skin) {
    const selected=SKINS.find(s=>s.id===target.dataset.skin);
    if (selected.unlocked(save)) { save.selectedSkin=selected.id; persist(); showSkins(); audio.play('menu'); }
    else toast(selected.requirement.toUpperCase());
  }
});
$('.brand').addEventListener('click', event => { event.preventDefault(); showHome(); });
$('#quick-audio').addEventListener('click', () => {
  const enable=!(save.settings.music||save.settings.sfx);
  save.settings.music=enable; save.settings.sfx=enable; persist(); applySettings();
});
document.addEventListener('keydown', event => {
  // Keep dialogs keyboard-accessible and prevent Tab escaping the modal.
  if (event.key==='Tab' && !$('#overlay').hidden) {
    const items=[...$('#panel').querySelectorAll('button:not([disabled]),a[href]')];
    const index=items.indexOf(document.activeElement);
    if (event.shiftKey && index<=0) { event.preventDefault(); items.at(-1)?.focus(); }
    else if (!event.shiftKey && index===items.length-1) { event.preventDefault(); items[0]?.focus(); }
    return;
  }
  if (event.code==='Escape' || event.code==='KeyP') {
    event.preventDefault(); if(event.repeat)return;
    if (state==='playing') pause();
    else if(modal && !['pause','over'].includes(modal)) back();
    else if(state==='paused')resume();
    return;
  }
  if(state!=='playing')return;
  const actions={ArrowLeft:()=>model.move(-1),KeyA:()=>model.move(-1),ArrowRight:()=>model.move(1),KeyD:()=>model.move(1),ArrowUp:()=>model.jump(),Space:()=>model.jump()};
  if(actions[event.code]) {event.preventDefault();if(!event.repeat){audio.unlock();actions[event.code]();}}
});
$('#touch-controls').addEventListener('pointerdown',event=>{
  const target=event.target.closest('[data-control]');
  if(!target||state!=='playing')return;
  event.preventDefault();audio.unlock();
  if(target.dataset.control==='jump')model.jump();else model.move(target.dataset.control==='left'?-1:1);
});
let swipe=null;
$('#game').addEventListener('pointerdown',event=>{
  if(state!=='playing'||event.target.closest('button,a'))return;
  swipe={x:event.clientX,y:event.clientY,id:event.pointerId,used:false};
  event.target.setPointerCapture?.(event.pointerId);
});
$('#game').addEventListener('pointermove',event=>{
  if(!swipe||event.pointerId!==swipe.id||swipe.used||state!=='playing')return;
  const dx=event.clientX-swipe.x,dy=event.clientY-swipe.y;
  if(Math.max(Math.abs(dx),Math.abs(dy))<28)return;
  swipe.used=true;audio.unlock();
  if(Math.abs(dx)>Math.abs(dy))model.move(Math.sign(dx));else if(dy<0)model.jump();
});
for(const name of ['pointerup','pointercancel'])$('#game').addEventListener(name,()=>{swipe=null;});
window.addEventListener('blur',pause);
document.addEventListener('visibilitychange',()=>{if(document.hidden){pause();persist();}lastTime=performance.now();});
window.addEventListener('resize',updateTouch);
window.addEventListener('pagehide',()=>{persist();audio.setActive(false);});

function frame(now) {
  const dt=Math.min(.05,Math.max(0,(now-lastTime)/1000));lastTime=now;
  if(state==='playing') {
    // The two-action first-run lesson uses a clear practice corridor.
    if(tutorialPhase<3) {
      tutorialElapsed += dt;
      for(const row of model.rows)row.lanes=row.lanes.map(c=>c?.type==='crystal'?c:null);
      if(tutorialPhase===2) tutorialDelay-=dt;
      if((tutorialPhase===2 && tutorialDelay<=0) || tutorialElapsed>=8) {
        tutorialPhase=3; save.tutorialDone=true; persist(); model.reset(model.seed);
        lastHud=''; updateTutorial(); toast('SIGNAL SYNCED · LET’S GO');
      }
    }
    model.update(dt); updateHud();
  }
  if(state==='over'&&overDelay>0) {overDelay-=dt;if(overDelay<=0)showResults();}
  renderer.draw(dt,model,state,skin());
  requestAnimationFrame(frame);
}
if(DONATION_URL) {
  try { const url=new URL(DONATION_URL); if(url.protocol==='https:') {$('#support').href=url.href;$('#support').hidden=false;} } catch { /* Invalid optional URLs remain hidden. */ }
}
applySettings();updateHome();setState('home');requestAnimationFrame(frame);
// Explicit test mode only: production visitors do not receive a debug API.
if(new URLSearchParams(location.search).has('test')) {
  window.__neonRush={model,get state(){return state;},get modal(){return modal;},save,audio,renderer,start,pause,showHome};
}
