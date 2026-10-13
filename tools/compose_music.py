"""《星骸》背景音樂產生器：7 首，程式合成（沒有取樣、沒有版權問題）。輸出 44.1kHz 立體聲 wav，之後用 ffmpeg 壓成 ogg、統一響度。"""
import numpy as np, wave, sys
from scipy.signal import butter, sosfilt
SR = 44100

def hz(m): return 440.0 * 2 ** ((m - 69) / 12)
def env(n, a, r, hold=None):
    e = np.ones(n); A = max(1, min(n, int(a * SR))); R = max(1, min(n - A, int(r * SR)))
    e[:A] = np.linspace(0, 1, A); e[n - R:] *= np.linspace(1, 0, R); return e
def lp(x, f, order=2):
    return sosfilt(butter(order, min(f, SR / 2 - 100) / (SR / 2), 'low', output='sos'), x)
def hp(x, f, order=2):
    return sosfilt(butter(order, f / (SR / 2), 'high', output='sos'), x)
def t_(d): return np.arange(int(d * SR)) / SR

# ---- 樂器 ----
def pad(f, d, bright=1.0):
    t = t_(d); s = 0
    for det, w in [(1, 1), (1.004, .7), (0.996, .7), (2, .2 * bright), (0.5, .3)]:
        s = s + w * np.sin(2 * np.pi * f * det * t + 0.4 * np.sin(2 * np.pi * 0.17 * t))
    return s * env(len(t), min(1.6, d * .4), min(2.0, d * .5)) / 3
def choir(f, d):
    t = t_(d); s = 0
    for det in (1, 1.006, 0.994, 1.012):
        ph = (f * det * t + 0.002 * np.sin(2 * np.pi * 5 * t)) % 1
        s = s + (2 * ph - 1)
    return lp(s, 1400) * env(len(t), min(1.2, d * .4), min(1.5, d * .4)) / 4
def pluck(f, d, dec=3.0):
    t = t_(d)
    s = (np.sin(2 * np.pi * f * t) + .45 * np.sin(4 * np.pi * f * t) + .18 * np.sin(6 * np.pi * f * t)) * np.exp(-t * dec)
    return s * env(len(t), .004, min(.2, d * .3))
def bell(f, d):
    t = t_(d)
    s = (np.sin(2 * np.pi * f * t) + .5 * np.sin(2 * np.pi * 2.76 * f * t) * np.exp(-t * 4)
         + .25 * np.sin(2 * np.pi * 5.4 * f * t) * np.exp(-t * 8)) * np.exp(-t * 1.3)
    return s * env(len(t), .002, min(.5, d * .3))
def flute(f, d):
    t = t_(d); vib = 1 + .004 * np.sin(2 * np.pi * 5.2 * t) * np.clip(t * 2, 0, 1)
    s = np.sin(2 * np.pi * f * vib * t) + .15 * np.sin(4 * np.pi * f * vib * t)
    s += .03 * lp(np.random.default_rng(int(f)).standard_normal(len(t)), 3000)
    return s * env(len(t), .08, min(.3, d * .4))
def lead(f, d):  # 銅管感
    t = t_(d); vib = 1 + .005 * np.sin(2 * np.pi * 5.5 * t) * np.clip(t * 3 - .3, 0, 1)
    ph = (f * vib * t) % 1; s = 2 * ph - 1
    cut = 900 + 2500 * np.exp(-t * 3)
    return lp(s, 2600) * env(len(t), .03, min(.15, d * .3)) * .6 + np.sin(2 * np.pi * f * t) * .3 * env(len(t), .03, .1)
def bass(f, d, cut=600):
    t = t_(d); ph = (f * t) % 1
    s = lp(2 * ph - 1, cut) * .7 + np.sin(2 * np.pi * f * t) * .6
    return s * env(len(t), .005, min(.08, d * .3))
def sub(f, d):
    t = t_(d); return np.sin(2 * np.pi * f * t) * env(len(t), 1.5, 1.5)

_rng = np.random.default_rng(7)
def kick(g=1.0):
    t = t_(.35); s = np.sin(2 * np.pi * (45 + 90 * np.exp(-t * 30)) * t) * np.exp(-t * 7)
    return s * g
def snare(g=1.0):
    t = t_(.25); n = hp(_rng.standard_normal(len(t)), 1500) * np.exp(-t * 18)
    return (n * .7 + np.sin(2 * np.pi * 190 * t) * np.exp(-t * 25) * .5) * g
def hat(g=1.0, open_=False):
    t = t_(.3 if open_ else .06); n = hp(_rng.standard_normal(len(t)), 7000) * np.exp(-t * (8 if open_ else 60))
    return n * g
def tom(f, g=1.0):
    t = t_(.4); return np.sin(2 * np.pi * f * (1 + .5 * np.exp(-t * 20)) * t) * np.exp(-t * 8) * g
def boom(g=1.0):  # 遠處的低沉撞擊
    t = t_(2.0); return (np.sin(2 * np.pi * (35 + 25 * np.exp(-t * 6)) * t) * np.exp(-t * 2.5) + lp(_rng.standard_normal(len(t)), 300) * np.exp(-t * 4) * .4) * g

# ---- 混音 ----
class Mix:
    def __init__(self, seconds):
        self.n = int((seconds + 6) * SR); self.L = np.zeros(self.n); self.R = np.zeros(self.n); self.len = seconds
    def add(self, sig, at, gain=1.0, pan=0.0):
        i = int(at * SR); j = min(self.n, i + len(sig))
        if i >= self.n: return
        l = np.cos((pan + 1) * np.pi / 4) * 1.414; r = np.sin((pan + 1) * np.pi / 4) * 1.414
        self.L[i:j] += sig[:j - i] * gain * l; self.R[i:j] += sig[:j - i] * gain * r
    def echo(self, sec, fb, mix, taps=5):
        d = int(sec * SR)
        for ch in (self.L, self.R):
            y = ch.copy()
            for k in range(1, taps + 1):
                y[d * k:] += ch[:-d * k] * (fb ** k) * mix
            ch[:] = y
    def reverb(self, mix=.25):
        for ch, offs in ((self.L, (1557, 1617, 1491, 1422)), (self.R, (1277, 1356, 1188, 1116))):
            wet = np.zeros_like(ch)
            for o in offs:
                d = o * 3; y = ch.copy()
                for k in range(1, 7): y[d * k:] += ch[:-d * k] * (.6 ** k)
                wet += y - ch
            ch += lp(wet, 5000) * mix / len(offs)
    def write(self, name):
        n = int(self.len * SR)
        L, R = self.L[:n].copy(), self.R[:n].copy()
        L[:self.n - n] += self.L[n:]; R[:self.n - n] += self.R[n:]  # 尾巴繞回開頭：循環接得起來
        peak = max(np.abs(L).max(), np.abs(R).max()); L, R = L / peak * .9, R / peak * .9
        data = np.empty(2 * n, dtype=np.int16); data[0::2] = (L * 32767).astype(np.int16); data[1::2] = (R * 32767).astype(np.int16)
        with wave.open(name + '.wav', 'w') as w:
            w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(data.tobytes())

# ---- 旋律 ----
def scale_notes(root, mode):
    steps = {'minor': [0, 2, 3, 5, 7, 8, 10], 'major': [0, 2, 4, 5, 7, 9, 11], 'phrygian': [0, 1, 3, 5, 7, 8, 10],
             'dorian': [0, 2, 3, 5, 7, 9, 10], 'harm': [0, 2, 3, 5, 7, 8, 11]}[mode]
    return [root + 12 * o + s for o in range(-2, 4) for s in steps]
def phrase(rng, chord_tones, scale, start, length_beats, rhythm):
    """一句旋律：重拍落在和弦音、其他用鄰近音階音；rhythm 是每個音幾拍。"""
    out = []; pos = 0; cur = start
    for dur in rhythm:
        if pos >= length_beats: break
        strong = abs(pos - round(pos)) < 1e-6 and int(round(pos)) % 2 == 0
        if strong:
            cands = [n for n in scale if n % 12 in chord_tones and abs(n - cur) <= 7]
        else:
            cands = [n for n in scale if 0 < abs(n - cur) <= 3]
        cur = min(cands or [cur], key=lambda n: abs(n - cur) + rng.random() * 3)
        out.append((pos, cur, dur)); pos += dur
    return out

def song(name, bpm, root, mode, prog, bars, layers, seed, echo=.6, rev=.3, lead_oct=0):
    rng = np.random.default_rng(seed); beat = 60 / bpm; bar = 4 * beat
    mix = Mix(bars * bar); scale = scale_notes(root, mode)
    motif_rhythms = [[1, 1, 2], [.5, .5, 1, 2], [1.5, .5, 1, 1], [2, 1, 1], [1, .5, .5, 2]]
    motifs = {}
    for b in range(bars):
        ch = prog[b % len(prog)]; t0 = b * bar
        tones = [ (root + c) % 12 for c in ch ]
        section = (b // 8) % 4  # A B A C
        for L in layers:
            kind = L[0]; g = L[1]
            if kind == 'pad':
                for c in ch: mix.add(pad(hz(root + c), bar + 1.5), t0, g, pan=rng.uniform(-.4, .4))
            elif kind == 'choir' and section != 0:
                for c in ch: mix.add(choir(hz(root + c + 12), bar + .8), t0, g, pan=rng.uniform(-.5, .5))
            elif kind == 'sub':
                mix.add(sub(hz(root + ch[0] - 24), bar + 1), t0, g)
            elif kind == 'arp':
                step = L[2]; n = int(4 / step)
                for k in range(n):
                    m = root + ch[k % len(ch)] + (12 if (k // len(ch)) % 2 else 0)
                    mix.add(pluck(hz(m), beat * step * 2), t0 + k * beat * step, g, pan=(-.35 if k % 2 else .35))
            elif kind == 'bass':
                pat = L[2]
                for p, octv in pat:
                    mix.add(bass(hz(root + ch[0] - 24 + octv), beat * .5 * .95, L[3] if len(L) > 3 else 600), t0 + p * beat, g)
            elif kind in ('bell', 'flute', 'lead') and (section in L[2]):
                key = (section, b % 2)
                if key not in motifs or b % 4 >= 2 and rng.random() < .5:
                    rh = motif_rhythms[rng.integers(len(motif_rhythms))] * 3
                    startn = min((n for n in scale if n % 12 == tones[0] and n >= root + 12 + lead_oct), default=root + 12)
                    motifs[key] = phrase(rng, tones, scale, startn, 4, rh)
                inst = {'bell': bell, 'flute': flute, 'lead': lead}[kind]
                for pos, m, d in motifs[key]:
                    # 換和弦時把旋律音拉回和弦音附近
                    mm = m if (m % 12) in tones or pos % 2 else min((n for n in scale if n % 12 in tones), key=lambda n: abs(n - m))
                    mix.add(inst(hz(mm), d * beat * (1.6 if kind == 'bell' else 1.0)), t0 + pos * beat, g, pan=.1)
            elif kind == 'drums':
                style = L[2]
                for k in range(16):
                    tt = t0 + k * beat / 4
                    if style == 'soft':
                        if k in (0, 10): mix.add(kick(.6), tt, g)
                        if k in (8,): mix.add(snare(.35), tt, g * .8)
                        if k % 4 == 2: mix.add(hat(.25), tt, g, pan=.3)
                    elif style == 'battle':
                        if k in (0, 6, 8, 10) or (section == 2 and k in (3, 14)): mix.add(kick(1), tt, g)
                        if k in (4, 12): mix.add(snare(.9), tt, g)
                        if k % 2 == 0: mix.add(hat(.35), tt, g, pan=.4)
                        if b % 4 == 3 and k >= 12: mix.add(tom(110 - (k - 12) * 15, .7), tt, g, pan=-.3)
                    elif style == 'epic':
                        if k % 2 == 0 and (section >= 1 or k % 4 == 0): mix.add(kick(1), tt, g)
                        if k in (4, 12): mix.add(snare(1), tt, g)
                        if k % 2 == 1: mix.add(hat(.3), tt, g, pan=.4)
                        if k == 0 and b % 2 == 0: mix.add(boom(.8), tt, g)
                        if b % 8 == 7 and k >= 8: mix.add(tom(150 - (k - 8) * 12, .8), tt, g, pan=.3 if k % 2 else -.3)
            elif kind == 'booms' and b % L[2] == 0:
                mix.add(boom(1), t0 + rng.uniform(0, bar * .5), g, pan=rng.uniform(-.6, .6))
            elif kind == 'sparkle':
                for k in range(L[2]):
                    if rng.random() < .6:
                        m = rng.choice([n for n in scale if n % 12 in tones and root + 24 <= n <= root + 40])
                        mix.add(bell(hz(m), beat * 3), t0 + rng.uniform(0, bar), g, pan=rng.uniform(-.8, .8))
    mix.echo(beat * .75, .4, echo); mix.reverb(rev); mix.write(name)
    print(name, round(bars * bar, 1), 's')

TRACKS = {
 # 主世界：平靜、溫暖
 'overworld_1': dict(bpm=76, root=57, mode='minor', prog=[[0, 3, 7], [-4, 0, 3], [3, 7, 10], [-2, 2, 5]], bars=32, seed=11,
                     layers=[('pad', .10), ('sub', .10), ('arp', .09, .5), ('bell', .12, (1, 2, 3))]),
 'overworld_2': dict(bpm=68, root=62, mode='major', prog=[[0, 4, 7, 11], [-3, 0, 4, 7], [-7, -3, 0, 4], [-5, -1, 2, 7]], bars=28, seed=23,
                     layers=[('pad', .09), ('sub', .08), ('arp', .07, 1), ('flute', .11, (1, 2, 3)), ('sparkle', .04, 2)]),
 # 地圖：陰暗、懸疑
 'map_1': dict(bpm=60, root=50, mode='minor', prog=[[0, 3, 7], [-2, 1, 5], [-4, 0, 3], [-5, -1, 2]], bars=24, seed=31, echo=.7, rev=.4,
               layers=[('pad', .11), ('sub', .14), ('sparkle', .06, 3), ('booms', .10, 4), ('bell', .07, (2, 3))]),
 'map_2': dict(bpm=84, root=52, mode='phrygian', prog=[[0, 3, 7], [1, 5, 8], [0, 3, 7], [-2, 1, 5]], bars=32, seed=41, echo=.5,
               layers=[('pad', .08), ('sub', .13), ('bass', .10, [(0, 0), (.75, 0), (1.5, 12), (2, 0), (2.75, 0), (3.5, 12)], 400),
                       ('booms', .08, 8), ('bell', .07, (1, 3))]),
 'map_3': dict(bpm=92, root=61, mode='harm', prog=[[0, 3, 7], [-4, 0, 3], [-7, -3, 0], [-5, -1, 2]], bars=36, seed=53,
               layers=[('pad', .08), ('sub', .09), ('arp', .08, .5), ('drums', .45, 'soft'), ('lead', .07, (1, 3)), ('sparkle', .03, 1)]),
 # Boss
 'boss': dict(bpm=140, root=57, mode='harm', prog=[[0, 3, 7], [0, 3, 7], [-4, 0, 3], [-2, 2, 5]], bars=48, seed=61, echo=.25, rev=.15,
              layers=[('pad', .06), ('drums', .55, 'battle'), ('bass', .16, [(0, 0), (.5, 0), (1, 12), (1.5, 0), (2, 0), (2.5, 12), (3, 0), (3.5, 7)], 900),
                      ('lead', .10, (1, 2, 3)), ('choir', .05)]),
 # 終局、極境、巔峰、金庫之主
 'boss_endgame': dict(bpm=150, root=50, mode='harm', prog=[[0, 3, 7], [1, 5, 8], [-4, 0, 3], [-5, -1, 2]], bars=56, seed=71, echo=.2, rev=.2,
                      layers=[('choir', .09), ('sub', .10), ('drums', .6, 'epic'),
                              ('bass', .17, [(0, 0), (.25, 0), (.5, 0), (1, 12), (1.5, 0), (2, 0), (2.25, 0), (2.5, 0), (3, 13), (3.5, 12)], 1200),
                              ('lead', .12, (1, 2, 3)), ('booms', .10, 8)], lead_oct=0),
}
if __name__ == '__main__':
    names = sys.argv[1:] or list(TRACKS)
    for n in names: song(n, **TRACKS[n])
