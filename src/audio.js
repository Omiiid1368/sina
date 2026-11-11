/** Original, procedural soundtrack and effects; nothing is downloaded or decoded. */
export class AudioEngine {
  constructor({ music = true, sfx = true } = {}) {
    this.music = music;
    this.sfx = sfx;
    this.active = false;
    this.context = null;
    this.timer = null;
    this.voices = new Set();
    this.step = 0;
    this.nextBeat = 0;
    this.destroyed = false;
  }

  /** Call from a pointer/keyboard gesture. Never initializes during page load. */
  async unlock() {
    if (this.destroyed) return false;
    try {
      if (!this.context) {
        const Context = globalThis.AudioContext || globalThis.webkitAudioContext;
        if (!Context) return false;
        this.context = new Context();
        this.master = this.context.createGain();
        this.master.gain.value = 0.5;
        this.master.connect(this.context.destination);
        this.musicBus = this.context.createGain();
        this.musicBus.gain.value = this.music ? 0.48 : 0;
        this.musicBus.connect(this.master);
        this.sfxBus = this.context.createGain();
        this.sfxBus.gain.value = this.sfx ? 0.65 : 0;
        this.sfxBus.connect(this.master);
        this.noise = this.context.createBuffer(1, this.context.sampleRate, this.context.sampleRate);
        const samples = this.noise.getChannelData(0);
        // Deterministic noise gives each run the same original percussion texture.
        let seed = 9173;
        for (let i = 0; i < samples.length; i++) {
          seed = (1664525 * seed + 1013904223) >>> 0;
          samples[i] = seed / 2147483648 - 1;
        }
      }
      if (this.context.state === 'suspended') await this.context.resume();
      this.syncMusic();
      return this.context.state === 'running';
    } catch { return false; }
  }

  setMusic(enabled) {
    this.music = Boolean(enabled);
    if (this.context) this.musicBus.gain.setTargetAtTime(this.music ? 0.48 : 0, this.context.currentTime, 0.025);
    this.syncMusic();
  }

  setSfx(enabled) {
    this.sfx = Boolean(enabled);
    if (this.context) this.sfxBus.gain.setTargetAtTime(this.sfx ? 0.65 : 0, this.context.currentTime, 0.015);
  }

  setActive(active) {
    this.active = Boolean(active);
    this.syncMusic();
  }

  syncMusic() {
    const shouldRun = this.context?.state === 'running' && this.music && this.active && !this.destroyed;
    if (!shouldRun) {
      if (this.timer) clearInterval(this.timer);
      this.timer = null;
      for (const voice of [...this.voices]) {
        if (voice.bus === this.musicBus) {
          try { voice.source.stop(); } catch { /* Already ended. */ }
        }
      }
      return;
    }
    if (this.timer) return;
    this.nextBeat = this.context.currentTime + 0.035;
    this.schedule();
    this.timer = setInterval(() => this.schedule(), 25);
  }

  note(midi) { return 440 * 2 ** ((midi - 69) / 12); }

  schedule() {
    if (!this.context || this.context.state !== 'running') return;
    // 112 BPM, original four-chord minor progression, sixteenth-note pulse.
    const stepDuration = 60 / 112 / 4;
    // Recover from browser throttling without scheduling a burst of old notes.
    if (this.nextBeat < this.context.currentTime - 0.1) this.nextBeat = this.context.currentTime + 0.02;
    while (this.nextBeat < this.context.currentTime + 0.12) {
      const s = this.step % 64;
      const root = [37, 33, 40, 35][Math.floor(s / 16)];
      if (s % 4 === 0) {
        this.tone(120, 38, 0.16, 0.62, 'sine', this.nextBeat, this.musicBus);
        this.tone(this.note(root), this.note(root), 0.21, 0.24, 'triangle', this.nextBeat, this.musicBus);
      }
      if (s % 8 === 4) this.hiss(0.11, 0.09, 1700, this.nextBeat, this.musicBus);
      if (s % 2 === 0) this.hiss(0.035, s % 4 === 0 ? 0.028 : 0.05, 7200, this.nextBeat, this.musicBus);
      const interval = [0, 7, 12, 15, 7, 12, 19, 15][s % 8];
      if (s % 2 === 1) {
        const pitch = this.note(root + 24 + interval);
        this.tone(pitch, pitch * 0.998, 0.14, 0.052, 'sine', this.nextBeat, this.musicBus);
        this.tone(pitch, pitch, 0.2, 0.015, 'triangle', this.nextBeat + stepDuration * 0.75, this.musicBus);
      }
      this.nextBeat += stepDuration;
      this.step++;
    }
  }

  track(source, gain, bus, additional = []) {
    const voice = { source, bus };
    this.voices.add(voice);
    source.onended = () => {
      this.voices.delete(voice);
      source.disconnect();
      gain.disconnect();
      additional.forEach(node => node.disconnect());
    };
  }

  tone(startFrequency, endFrequency, duration, volume, waveform, time, bus) {
    const source = this.context.createOscillator();
    const gain = this.context.createGain();
    source.type = waveform;
    source.frequency.setValueAtTime(Math.max(10, startFrequency), time);
    source.frequency.exponentialRampToValueAtTime(Math.max(10, endFrequency), time + duration);
    gain.gain.setValueAtTime(0.0001, time);
    gain.gain.exponentialRampToValueAtTime(Math.max(0.0002, volume), time + 0.006);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + duration);
    source.connect(gain);
    gain.connect(bus);
    this.track(source, gain, bus);
    source.start(time);
    source.stop(time + duration + 0.025);
  }

  hiss(duration, volume, frequency, time, bus) {
    const source = this.context.createBufferSource();
    const filter = this.context.createBiquadFilter();
    const gain = this.context.createGain();
    source.buffer = this.noise;
    filter.type = 'highpass';
    filter.frequency.value = frequency;
    gain.gain.setValueAtTime(Math.max(0.0002, volume), time);
    gain.gain.exponentialRampToValueAtTime(0.0001, time + duration);
    source.connect(filter);
    filter.connect(gain);
    gain.connect(bus);
    this.track(source, gain, bus, [filter]);
    source.start(time);
    source.stop(time + duration);
  }

  play(name) {
    if (!this.context || this.context.state !== 'running' || !this.sfx || this.destroyed) return;
    const time = this.context.currentTime + 0.005;
    const tone = (a, b, d, v = 0.15, w = 'sine', offset = 0) => this.tone(a, b, d, v, w, time + offset, this.sfxBus);
    switch (name) {
      case 'move': tone(280, 510, 0.055, 0.075, 'triangle'); break;
      case 'jump': tone(210, 900, 0.2, 0.13, 'triangle'); tone(420, 1500, 0.13, 0.045); break;
      case 'collect': tone(880, 1320, 0.1, 0.14); tone(1760, 2100, 0.16, 0.07, 'sine', 0.06); break;
      case 'collision':
        this.hiss(0.36, 0.4, 180, time, this.sfxBus);
        tone(160, 28, 0.5, 0.5, 'sawtooth');
        tone(70, 20, 0.6, 0.4);
        break;
      case 'record':
        [76, 80, 83, 88].forEach((midi, i) => tone(this.note(midi), this.note(midi), 0.32, 0.16, 'triangle', i * 0.1));
        break;
      default: tone(600, 820, 0.055, 0.09, 'sine');
    }
  }

  destroy() {
    this.destroyed = true;
    this.active = false;
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    for (const { source } of this.voices) {
      try { source.stop(); } catch { /* Already stopped. */ }
    }
    this.voices.clear();
    if (this.context && this.context.state !== 'closed') this.context.close().catch(() => {});
    this.context = null;
  }
}
