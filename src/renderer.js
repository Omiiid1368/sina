// All artwork is drawn locally. No textures, fonts, or network assets are needed.
const CYAN = '#72fff1', PINK = '#ff459c', VIOLET = '#8052ff', GOLD = '#ffda7a';
const clamp = (n, a, b) => Math.max(a, Math.min(b, n));

export class Renderer {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d', { alpha: false });
    this.width = 1; this.height = 1;
    this.time = 0; this.particles = []; this.shake = 0; this.flash = 0;
    this.quality = 1; this.frameAverage = 16; this.sampleTime = 0;
    this.reducedMotion = false;
    this.resize = this.resize.bind(this);
    window.addEventListener('resize', this.resize);
    this.resize();
  }

  resize() {
    this.width = this.canvas.clientWidth;
    this.height = this.canvas.clientHeight;
    this.dpr = Math.min(window.devicePixelRatio || 1, this.quality < .7 ? 1.25 : 2);
    this.canvas.width = Math.round(this.width * this.dpr);
    this.canvas.height = Math.round(this.height * this.dpr);
    this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
  }

  project(x, y, z = 0) {
    const p = 1 / (1 + Math.max(-3, z) * .052);
    return { x: this.center + x * this.scale * p,
      y: this.horizon + (this.ground - this.horizon) * p - y * this.scale * p,
      s: this.scale * p, p };
  }

  line(points, color, width = 1, glow = 0) {
    const c = this.ctx;
    c.strokeStyle = color; c.lineWidth = width;
    c.shadowColor = color; c.shadowBlur = this.quality > .65 ? glow : 0;
    c.beginPath();
    points.forEach((p, i) => i ? c.lineTo(p.x, p.y) : c.moveTo(p.x, p.y));
    c.stroke(); c.shadowBlur = 0;
  }

  polygon(points, fill, stroke, width = 1, glow = 0) {
    const c = this.ctx;
    c.beginPath(); points.forEach((p, i) => i ? c.lineTo(p.x, p.y) : c.moveTo(p.x, p.y));
    c.closePath(); c.fillStyle = fill; c.fill();
    if (stroke) { c.strokeStyle = stroke; c.lineWidth = width; c.shadowColor = stroke;
      c.shadowBlur = this.quality > .65 ? glow : 0; c.stroke(); c.shadowBlur = 0; }
  }

  burst(color, count = 20, height = .3) {
    const p = this.project(this.playerX || 0, height, 0);
    for (let i = 0; i < count * this.quality; i++) {
      const a = Math.random() * Math.PI * 2, speed = 40 + Math.random() * 190;
      this.particles.push({ x: p.x, y: p.y, vx: Math.cos(a) * speed,
        vy: Math.sin(a) * speed, life: .35 + Math.random() * .45, max: .8, color, size: 1 + Math.random() * 3 });
    }
    if (this.particles.length > 180) this.particles.splice(0, this.particles.length - 180);
  }

  event(name, skin) {
    if (name === 'collect') this.burst(GOLD, 20, .35);
    if (name === 'jump' || name === 'move') this.burst(skin.color, 7, .1);
    if (name === 'collision') { this.burst(PINK, 65, .3); this.shake = this.reducedMotion ? 0 : 14; this.flash = .65; }
    if (name === 'record') this.burst(GOLD, 50, 1);
  }

  draw(dt, model, mode, skin) {
    const c = this.ctx, w = this.width, h = this.height;
    this.time += dt;
    this.sampleTime += dt;
    this.frameAverage = this.frameAverage * .97 + dt * 1000 * .03;
    if (this.sampleTime > 4 && this.frameAverage > 24 && this.quality > .65) {
      this.quality = .6; this.resize();
    }
    const menu = mode === 'home';
    const active = mode === 'playing';
    const distance = menu ? this.time * (this.reducedMotion ? 0 : 12) : model.distance;
    this.scale = Math.min(w * .235, h * .255, 215);
    this.center = menu ? w * (w < 760 ? .78 : .685) : w * .5;
    this.horizon = h * (menu ? .4 : .34);
    this.ground = h * (w / h > 1.8 ? .85 : .82);
    const camera = this.reducedMotion ? 0 : Math.sin(this.time * .7) * 2;
    this.center += camera;
    this.playerX = menu ? .12 : model.player.x;
    c.save();
    if (this.shake > .1) {
      c.translate((Math.random() - .5) * this.shake, (Math.random() - .5) * this.shake);
      this.shake *= Math.exp(-dt * 10);
    }
    c.fillStyle = '#080713'; c.fillRect(-20, -20, w + 40, h + 40);
    const ambience = c.createRadialGradient(this.center, this.horizon, 5, this.center, this.horizon, Math.max(w, h) * .66);
    ambience.addColorStop(0, '#442267'); ambience.addColorStop(.27, '#1e1235'); ambience.addColorStop(.65, '#0b091a'); ambience.addColorStop(1, '#080713');
    c.fillStyle = ambience; c.fillRect(0, 0, w, h);
    this.stars();
    this.portal();
    this.floor(distance);
    this.arches(distance);
    this.speedLines(active ? model.speed : 25);

    let rows = model.rows;
    if (menu) rows = [
      { z: 12, lanes: [{ type: 'crystal' }, null, { type: 'barrier' }] },
      { z: 43, lanes: [{ type: 'barrier' }, { type: 'crystal' }, null] },
      { z: 78, lanes: [null, { type: 'laser' }, { type: 'crystal' }] },
      { z: 114, lanes: [{ type: 'block' }, null, { type: 'crystal' }] },
    ];
    const sorted = [...rows].sort((a, b) => b.z - a.z);
    for (const row of sorted) {
      if (row.z < -3 || row.z > 165) continue;
      const alpha = clamp((165 - row.z) / 40, 0, 1);
      c.globalAlpha = alpha;
      row.lanes.forEach((cell, lane) => {
        if (!cell || cell.collected) return;
        const x = lane - 1 + (cell.offset || 0);
        if (cell.type === 'crystal') this.crystal(x, row.z);
        else this.obstacle(x, row.z, cell.type);
      });
      c.globalAlpha = 1;
    }
    const px = this.playerX, py = menu ? .05 + (this.reducedMotion ? 0 : Math.sin(this.time * 2) * .025) : model.player.height;
    if (mode !== 'over' || this.flash > .1) {
      this.player(px, py, skin, menu ? 0 : model.player.lane - 1 - px, distance);
    } else {
      const p = this.project(px, .1, 0);
      c.globalAlpha = .45; this.line([{x:p.x-12,y:p.y-12},{x:p.x+12,y:p.y+12}], PINK, 2, 5);
      this.line([{x:p.x+12,y:p.y-12},{x:p.x-12,y:p.y+12}], PINK, 2, 5); c.globalAlpha = 1;
    }
    this.drawParticles(dt);
    if (this.flash > .01) { c.fillStyle = `rgba(255,55,120,${this.flash * .22})`; c.fillRect(0, 0, w, h); this.flash *= Math.exp(-dt * 7); }
    // Fine scanlines give the scene texture without costly postprocessing.
    if (this.quality > .65) { c.fillStyle = '#07041b18'; for (let y = 0; y < h; y += 4) c.fillRect(0, y, w, 1); }
    c.restore();
  }

  stars() {
    const c = this.ctx;
    for (let i = 0; i < 65 * this.quality; i++) {
      const x = ((i * 739 + 97) % 1000) / 1000 * this.width;
      const y = ((i * 211 + 21) % 700) / 1000 * this.height;
      const a = .16 + ((i * 13) % 10) / 28;
      c.fillStyle = `rgba(182,161,232,${a})`;
      c.fillRect(x, y, i % 7 === 0 ? 2 : 1, 1);
    }
  }

  portal() {
    const c = this.ctx, x = this.center, y = this.horizon - 10, r = this.height * .11;
    const g = c.createRadialGradient(x, y, 0, x, y, r * 2.8);
    g.addColorStop(0, '#a557eb30'); g.addColorStop(.5, '#6431b211'); g.addColorStop(1, '#00000000');
    c.fillStyle = g; c.fillRect(x-r*3, y-r*3, r*6, r*6);
    for (let i = 0; i < 3; i++) {
      c.beginPath(); c.arc(x, y, r * (1 + i * .18), Math.PI * 1.08, Math.PI * 1.92);
      c.strokeStyle = ['#c16cff48', '#b06aff25', '#8f55ff15'][i]; c.lineWidth = 1; c.stroke();
    }
    c.fillStyle = '#cd91ff'; c.shadowColor = '#c56aff'; c.shadowBlur = 15;
    c.fillRect(x - 1, y - r - 1, 2, 2); c.shadowBlur = 0;
  }

  floor(distance) {
    const c = this.ctx;
    this.polygon([this.project(-1.75,0,160),this.project(1.75,0,160),this.project(1.75,0,-5),this.project(-1.75,0,-5)], '#121027');
    for (let z = 160 - distance % 7; z > -5; z -= 7) {
      const p = this.project(0, 0, z);
      c.globalAlpha = .12 + p.p * .28;
      this.line([this.project(-1.75,0,z), this.project(1.75,0,z)], '#8b5dc5', .7);
    }
    for (let x = -1.75; x <= 1.76; x += .25) {
      c.globalAlpha = .08;
      this.line([this.project(x,0,160),this.project(x,0,-5)], '#8b6bcd', .7);
    }
    c.globalAlpha = 1;
    for (const x of [-1.55, -.5, .5, 1.55]) {
      const edge = Math.abs(x) > 1;
      const color = edge ? x < 0 ? '#70fff0' : '#ff56be' : '#7563b255';
      this.line([this.project(x,0,160),this.project(x,0,-5)], color, edge ? 1.6 : 1, edge ? 10 : 0);
      if (edge) this.line([this.project(x + Math.sign(x)*.045,0,160),this.project(x + Math.sign(x)*.045,0,-5)], '#9d7add30', 1);
    }
    // Paired runway dashes make velocity legible.
    for (let z = 150 - distance % 18; z > -3; z -= 18) {
      for (const x of [-1.66, 1.66]) this.line([this.project(x,.02,z),this.project(x,.02,z+5)], x<0 ? '#72fff193' : '#ff67c3a0', 2, 4);
    }
  }

  arches(distance) {
    const c = this.ctx;
    for (let z = 155 - distance % 22; z >= -5; z -= 22) {
      const p = this.project(0,0,z);
      const alpha = clamp((160-z)/70,0,.55);
      c.globalAlpha = alpha;
      const path = [this.project(-1.85,0,z),this.project(-2.05,1.6,z),this.project(-1.1,2.75,z),this.project(1.1,2.75,z),this.project(2.05,1.6,z),this.project(1.85,0,z)];
      this.line(path, '#8651bc', 1 + p.p, 6);
      this.line([path[0],this.project(-1.93,.67,z)], CYAN, 1.5, 6);
      this.line([path[5],this.project(1.93,.67,z)], PINK, 1.5, 6);
      this.line([this.project(-.28,2.75,z),this.project(.28,2.75,z)], '#ba8cff', 1.5, 6);
    }
    c.globalAlpha = 1;
    for (const x of [-2.05,2.05]) this.line([this.project(x,1.6,160),this.project(x,1.6,-5)], '#8a46b522', 1);
  }

  speedLines(speed) {
    if (this.reducedMotion) return;
    const c = this.ctx;
    c.globalAlpha = .18;
    for (let i = 0; i < 15 * this.quality; i++) {
      const z = (i * 17 + 150 - (this.time * speed * 1.2) % 150) % 150;
      const side = i % 2 ? 1 : -1, x = side * (2.2 + (i % 4)*.4), y = .25+(i%5)*.5;
      this.line([this.project(x,y,z),this.project(x,y,z+7)], i%3 ? '#a483d4' : '#7cf5f2', 1);
    }
    c.globalAlpha = 1;
  }

  box(x, height, z, width, color, fill) {
    const p = this.project(x,0,z), w = width*p.s, h = height*p.s, d = .11*p.s;
    const left = p.x - w/2, right = p.x+w/2, bottom = p.y, top = bottom-h;
    const face = [{x:left,y:top},{x:right,y:top},{x:right,y:bottom},{x:left,y:bottom}];
    this.polygon([{x:left,y:top},{x:left+d,y:top-d},{x:right+d,y:top-d},{x:right,y:top}], '#322242', color, 1, 3);
    this.polygon([{x:right,y:top},{x:right+d,y:top-d},{x:right+d,y:bottom-d},{x:right,y:bottom}], '#1d152b', color, 1, 3);
    this.polygon(face, fill, color, 1.5, 8);
    return { p, left, right, top, bottom, w, h };
  }

  obstacle(x,z,type) {
    const c = this.ctx;
    if (type === 'laser') {
      const p = this.project(x,0,z), s = p.s;
      const color = '#e67bff';
      for (const side of [-1,1]) {
        const px = p.x + side*.32*s;
        this.polygon([{x:px-.035*s,y:p.y},{x:px+.035*s,y:p.y},{x:px+.035*s,y:p.y-1.85*s},{x:px-.035*s,y:p.y-1.85*s}], '#381b49', color, 1, 5);
      }
      for (const y of [.18,.48,.78,1.08,1.38,1.7]) {
        const yy = p.y - y*s;
        this.line([{x:p.x-.3*s,y:yy},{x:p.x+.3*s,y:yy}], color, 2, 10);
        this.line([{x:p.x-.3*s,y:yy},{x:p.x+.3*s,y:yy}], '#fff0ff', .5);
      }
      return;
    }
    const block = type === 'block', color = block ? GOLD : PINK;
    const b = this.box(x, block ? .36 : 1.85, z, .63, color, block ? '#43263c' : '#351228');
    // High contrast chevrons distinguish jumpable low blocks from full barriers.
    const cy = b.top+b.h*.48;
    if (block) {
      this.line([{x:b.p.x-b.w*.18,y:cy+b.h*.17},{x:b.p.x,y:cy-b.h*.12},{x:b.p.x+b.w*.18,y:cy+b.h*.17}], GOLD, 2, 4);
    } else {
      this.line([{x:b.left+b.w*.2,y:b.bottom-b.h*.2},{x:b.right-b.w*.2,y:b.top+b.h*.2}], '#ff61ab80', 2);
      this.line([{x:b.left+b.w*.2,y:b.top+b.h*.2},{x:b.right-b.w*.2,y:b.bottom-b.h*.2}], '#ff61ab80', 2);
    }
    const p = this.project(x,0,z);
    this.line([{x:p.x-b.w*.6,y:p.y+3},{x:p.x+b.w*.6,y:p.y+3}], color, 1.5, 9);
  }

  crystal(x,z) {
    const c = this.ctx, p = this.project(x,.34,z), size = p.s*.125;
    const bob = this.reducedMotion ? 0 : Math.sin(this.time*4+z*.12)*size*.15;
    const points = [{x:p.x,y:p.y-size*1.55+bob},{x:p.x+size*.7,y:p.y+bob},{x:p.x,y:p.y+size*1.55+bob},{x:p.x-size*.7,y:p.y+bob}];
    this.polygon(points, '#ffd87a44', GOLD, 1.4, 12);
    this.line([points[0],points[2]], '#ffedbbcc', .8, 3);
    c.fillStyle = '#fff2d0'; c.fillRect(p.x-1,p.y-1+bob,2,2);
  }

  player(x,y,skin,lean,distance) {
    const c = this.ctx, floor = this.project(x,0,0), p = this.project(x,y,0), size = this.scale*.37;
    const g = c.createRadialGradient(floor.x,floor.y+4,0,floor.x,floor.y+4,size*1.8);
    g.addColorStop(0,skin.color+'38'); g.addColorStop(1,skin.color+'00');
    c.fillStyle = g; c.beginPath(); c.ellipse(floor.x,floor.y+4,size*1.8,size*.45,0,0,Math.PI*2); c.fill();
    if (!this.reducedMotion) {
      for (let i = 8; i >= 1; i--) {
        const t = (i+(distance*.15)%1)/9;
        const q = this.project(x,.05,t*10);
        c.globalAlpha = (1-t)*.25;
        this.line([{x:q.x-size*.2*(1-t),y:q.y},{x:q.x+size*.2*(1-t),y:q.y}], skin.color, Math.max(1,size*.12*(1-t)), 9);
      }
      c.globalAlpha=1;
    }
    c.save(); c.translate(p.x,p.y-size*.5); c.rotate(clamp(lean,-1,1)*.2);
    const d=size*.22, l=-size/2, r=size/2, t=-size/2, b=size/2;
    this.polygon([{x:l,y:t},{x:l+d,y:t-d},{x:r+d,y:t-d},{x:r,y:t}], skin.color+'65',skin.color,1.5,10);
    this.polygon([{x:r,y:t},{x:r+d,y:t-d},{x:r+d,y:b-d},{x:r,y:b}],skin.secondary+'90',skin.color,1.5,8);
    this.polygon([{x:l,y:t},{x:r,y:t},{x:r,y:b},{x:l,y:b}], '#123540',skin.color,2,18);
    this.polygon([{x:l+size*.14,y:t+size*.14},{x:r-size*.14,y:t+size*.14},{x:r-size*.14,y:b-size*.14},{x:l+size*.14,y:b-size*.14}],skin.color+'16',skin.color+'85',1);
    this.line([{x:-size*.13,y:0},{x:0,y:-size*.13},{x:size*.13,y:0},{x:0,y:size*.13},{x:-size*.13,y:0}],skin.color,1.5,8);
    c.restore();
  }

  drawParticles(dt) {
    const c = this.ctx;
    for (const p of this.particles) {
      p.life -= dt; p.x += p.vx*dt; p.y += p.vy*dt; p.vy += 80*dt;
      c.globalAlpha = Math.max(0,p.life/p.max); c.fillStyle=p.color;
      c.fillRect(p.x,p.y,p.size,p.size);
    }
    c.globalAlpha=1; this.particles=this.particles.filter(p=>p.life>0);
  }

  destroy() { window.removeEventListener('resize',this.resize); }
}
