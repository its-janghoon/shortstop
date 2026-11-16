#!/usr/bin/env python3
"""Edit the Shortstop contest video (contest spec section 8.2, video-brief.md).

    python video/edit.py <raw dir> <out.mp4>

<raw dir> holds takes/ (screen takes s1-s6 and the driver logs s4.log, s5.log), scenes/ (rendered
flat scenes) and vo/ (Gemini TTS lines plus words.json word timings). Needs ffmpeg, numpy,
opencv-python and pillow. Video files are not committed; only this script is.

Every take is played at real speed unless a badge on screen says otherwise ("4x speed" while the
model thinks, "1/2 speed" for the masking replay), so nothing shown is faster than it really is
without saying so.
"""
import difflib
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import wave

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RAW, OUT = sys.argv[1], sys.argv[2]
FPS, W, H = 30, 1920, 1080
FONT = os.environ.get("FONT", os.path.expanduser("~/.local/share/fonts/pretendard/Pretendard-Bold.otf"))
# Screen takes sit in a frame on a flat background; captions go in the band under it.
SC = 0.86
FW, FH = round(W * SC), round(H * SC)
FX, FY = (W - FW) // 2, 22
BG = (25, 36, 13)  # BGR of #0d2419
CAP_Y = 1018  # caption box centre
F = (960, 540, 1.0)  # full view

# ---------------------------------------------------------------- scenes
# segs: (src from, src to, speed); a scene lasts as long as its segments. cam: (src time, (cx, cy, zoom)),
# eased between keys. sfx: sound events in source time. Event times were read off the takes
# (frame differences and the driver logs): see the comments per scene.
SCENES = [
    dict(id="open", kind="full", src="scenes/open.mp4", dur=5.2),
    dict(id="problem", kind="full", src="scenes/problem.mp4", dur=7),
    dict(id="name", kind="full", src="scenes/name.mp4", dur=7.3),
    # S1: Wi-Fi click 3.4, input click 6.85, paste 7.37, Check 9.5, result 20.53, verdict 21.93.
    dict(id="s1", kind="screen", src="takes/s1.mp4",
         segs=[(2.6, 4.0, 1), (4.0, 7.0, 2), (7.0, 9.8, 1), (9.8, 20.5, 10), (20.5, 24.4, 1)],
         cam=[(2.6, F), (3.1, (1290, 160, 2.1)), (4.9, (1290, 165, 2.25)), (5.5, F), (9.8, (760, 520, 1.14)), (20.5, (900, 540, 1.1)),
              (20.9, (540, 420, 1.55)), (24.6, (540, 440, 1.75))],
         sfx=[("click", 3.4), ("click", 6.85), ("ticks", 7.37), ("click", 9.5), ("rise", 9.8, 20.5),
              ("whoosh", 20.75, 0), ("thwack", 21.93), ("stamp", 22.63)]),
    # S2: input click 4.0, paste 4.53, Check 6.6, result 17.17 (masking 17.2-19.2), throw to 1B 19.42-20.5,
    # "Exactly what left" 19.95, verdict 20.72.
    dict(id="s2", kind="screen", src="takes/s2.mp4",
         segs=[(3.9, 6.9, 1), (6.9, 17.1, 6), (17.1, 21.4, 0.75), (21.4, 27.6, 1)],
         cam=[(3.9, F), (6.9, (760, 520, 1.12)), (17.1, (960, 540, 1.06)), (17.4, (530, 220, 1.85)), (19.1, (530, 225, 1.95)),
              (19.45, (1175, 500, 1.3)), (20.45, (1175, 505, 1.36)), (20.8, (960, 540, 1.05)), (21.5, (960, 540, 1.1)),
              (21.9, (1175, 880, 1.75)), (23.9, (1175, 880, 1.85)), (24.3, (530, 740, 1.75)), (27.6, (530, 740, 1.92))],
         sfx=[("click", 4.0), ("ticks", 4.53), ("click", 6.6), ("rise", 6.9, 17.1), ("whoosh", 17.37, 0),
              ("whoosh", 19.42, 1), ("thwack", 20.72), ("stamp", 21.42)]),
    # S3: input click 4.15, paste 4.67, Check 6.75, result 17.0, verdict 18.4.
    dict(id="s3", kind="screen", src="takes/s3.mp4",
         segs=[(4.0, 7.1, 1), (7.1, 17.0, 8), (17.0, 21.4, 1)],
         cam=[(4.0, (960, 540, 1.0)), (7.1, (960, 540, 1.04)), (17.0, (960, 540, 1.06)), (17.6, (540, 400, 1.55)), (21.4, (540, 410, 1.72))],
         sfx=[("click", 4.15), ("ticks", 4.67), ("click", 6.75), ("rise", 7.1, 17.0), ("whoosh", 17.2, 0), ("pop", 18.4)]),
    # S4: right-click 3.87, menu item 6.18 (driver log), side panel result 24.97, verdict 26.37.
    dict(id="s4", kind="screen", src="takes/s4.mp4", log="takes/s4.log",
         segs=[(1.2, 6.6, 1), (6.6, 24.9, 10), (24.9, 30.6, 1)],
         cam=[(1.4, F), (2.6, (1000, 470, 2.0)), (6.2, (1040, 520, 2.2)), (6.7, F), (24.9, F), (25.4, (1620, 420, 1.9)),
              (30.6, (1620, 430, 2.05))],
         sfx=[("rise", 6.6, 24.9), ("whoosh", 25.17, 0), ("thwack", 26.37), ("stamp", 27.07)]),
    # S5: composer click 2.78, paste 3.56 (guard pops up 3.5), "Paste masked" 6.32, Enter 7.9, reply 9.1.
    dict(id="s5", kind="screen", src="takes/s5.mp4", log="takes/s5.log",
         segs=[(1.4, 12.4, 1)],
         cam=[(1.4, F), (2.4, (900, 820, 1.55)), (3.4, (960, 880, 1.8)), (6.0, (980, 890, 1.9)), (7.7, (980, 890, 1.9)),
              (8.6, (820, 760, 1.6)), (12.4, (820, 760, 1.72))],
         sfx=[("ticks", 3.56), ("ticks", 6.4)]),
    # S6 (Android, constant-frame-rate copy): long-press 2.4, menu 4.2, Share 5.5, Shortstop opens 10.7,
    # verdict 21.25, "Send to family" 28.55.
    dict(id="s6", kind="phone", src="takes/s6cfr.mp4",
         segs=[(1.5, 10.7, 4), (10.7, 11.4, 1), (11.4, 21.2, 5), (21.2, 23.6, 1), (23.6, 28.4, 5), (28.4, 30.1, 1)],
         sfx=[("click", 2.4), ("click", 4.2), ("click", 5.5), ("rise", 11.4, 21.2), ("thwack", 21.25), ("click", 28.55)]),
    dict(id="s7", kind="full", src="scenes/s7.mp4", dur=14,
         sfx=[("whoosh", 1.1, 0), ("whoosh", 3.5, 0), ("whoosh", 6.3, 0), ("whoosh", 9.1, 0), ("whoosh", 10.9, 1)]),
    dict(id="end", kind="full", src="scenes/end.mp4", dur=5.6, sfx=[("pop", 0.25)]),
]
t = 0
for s in SCENES:
    if "segs" in s:
        s["dur"] = sum((b - a) / sp for a, b, sp in s["segs"])
    s["start"] = t
    t += s["dur"]
TOTAL = t

# Voice pieces: (scene, "src"|"out", time, line id, first word, end word (exclusive), word placed at time).
# Word indices count the words of the line in video/lines.json.
VO = [
    ("open", "out", 0.4, "open", 0, None, None),
    ("problem", "out", 0.3, "problem", 0, None, None),
    ("name", "out", 0.4, "name", 0, None, None),
    ("s1", "src", 3.45, "s1", 0, 1, None),  # Offline.  (Wi-Fi click)
    ("s1", "src", 8.5, "s1", 1, 3, None),  # On device.  (pasted, before Check)
    ("s1", "src", 21.9, "s1", 3, 4, None),  # Scam.  (verdict)
    ("s2", "src", 4.5, "s2", 0, 2, None),  # Harder one.
    ("s2", "src", 13.0, "s2", 2, 5, None),  # Shortstop isn't sure,  (while it works)
    ("s2", "src", 19.45, "s2", 5, 11, 7),  # so it throws to first base.  ("throws" on the throw)
    ("s2", "src", 21.45, "s2", 11, 19, None),  # Only the masked text goes to the cloud.
    ("s2", "src", 24.3, "s2", 19, 26, None),  # It never sees Jane's name or account.
    ("s3", "src", 4.7, "s3", 0, 5, None),  # Now a real delivery notice.
    ("s3", "src", 18.45, "s3", 5, 6, None),  # Safe.
    ("s3", "src", 19.3, "s3", 6, None, None),  # It doesn't cry wolf.
    ("s4", "src", 1.4, "s4", 0, 5, None),  # John gets a bank e-mail.
    ("s4", "src", 6.0, "s4", 5, 9, None),  # One letter is off.
    ("s4", "src", 26.3, "s4", 9, None, None),  # Shortstop flags it before he clicks.
    ("s5", "src", 1.6, "s5", 0, 8, None),  # John pastes a lease into an AI chat.
    ("s5", "src", 4.95, "s5", 8, 11, None),  # Same engine, outgoing.
    ("s5", "src", 8.5, "s5", 11, None, None),  # Names and numbers stay home.
    ("s6", "out", 0.25, "s6", 0, 4, None),  # Same engine on Android.
    ("s6", "src", 11.0, "s6", 4, 7, None),  # Share the text,
    ("s6", "src", 21.25, "s6", 7, 10, None),  # get the verdict,
    ("s6", "src", 28.4, "s6", 10, None, None),  # send it to family.
    ("s7", "out", 0.5, "s7", 0, None, None),
    ("end", "out", 0.5, "end", 0, None, None),
]


# ---------------------------------------------------------------- helpers
def ease(x):
    x = min(1.0, max(0.0, x))
    return 4 * x * x * x if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2


def out_to_src(s, u):
    """Seconds into the scene -> (source seconds, speed)."""
    for a, b, sp in s["segs"]:
        d = (b - a) / sp
        if u < d:
            return a + u * sp, sp
        u -= d
    a, b, sp = s["segs"][-1]
    return b, sp


def src_to_out(s, x):
    u = 0.0
    for a, b, sp in s["segs"]:
        if x <= b:
            return u + max(0.0, x - a) / sp
        u += (b - a) / sp
    return u


def cam_at(keys, x):
    if not keys:
        return F
    if x <= keys[0][0]:
        return keys[0][1]
    for (t0, c0), (t1, c1) in zip(keys, keys[1:]):
        if x <= t1:
            e = ease((x - t0) / (t1 - t0))
            # zoom eases in log space so the speed of the push feels even
            z = math.exp(math.log(c0[2]) + (math.log(c1[2]) - math.log(c0[2])) * e)
            return (c0[0] + (c1[0] - c0[0]) * e, c0[1] + (c1[1] - c0[1]) * e, z)
    return keys[-1][1]


def crop_zoom(img, cx, cy, z, ow, oh):
    h, w = img.shape[:2]
    cw, ch = w / z, h / z
    x0 = min(max(cx - cw / 2, 0), w - cw)
    y0 = min(max(cy - ch / 2, 0), h - ch)
    # affine warp keeps sub-pixel camera motion smooth
    m = np.array([[ow / cw, 0, -x0 * ow / cw], [0, oh / ch, -y0 * oh / ch]], np.float32)
    return cv2.warpAffine(img, m, (ow, oh), flags=cv2.INTER_AREA if z < 1.01 else cv2.INTER_LINEAR)


def blend(dst, rgba, x, y, alpha=1.0):
    """Alpha-blend an RGBA (numpy, BGRA order) image onto dst at (x, y)."""
    h, w = rgba.shape[:2]
    x0, y0, x1, y1 = max(x, 0), max(y, 0), min(x + w, dst.shape[1]), min(y + h, dst.shape[0])
    if x0 >= x1 or y0 >= y1:
        return
    s = rgba[y0 - y:y1 - y, x0 - x:x1 - x]
    a = s[:, :, 3:4].astype(np.float32) / 255 * alpha
    d = dst[y0:y1, x0:x1]
    d[:] = (s[:, :, :3] * a + d * (1 - a)).astype(np.uint8)


def pil_bgra(im):
    a = np.array(im.convert("RGBA"))
    return a[:, :, [2, 1, 0, 3]].copy()


class Src:
    def __init__(self, path):
        self.cap = cv2.VideoCapture(path)
        self.n = int(self.cap.get(cv2.CAP_PROP_FRAME_COUNT))
        self.i, self.f = -1, None

    def at(self, x):
        k = min(max(int(round(x * FPS)), 0), self.n - 1)
        if k < self.i:
            self.cap.set(cv2.CAP_PROP_POS_FRAMES, k)
            self.i = k - 1
        while self.i < k:
            ok = self.cap.grab()
            if not ok:
                break
            self.i += 1
            if self.i == k:
                ok, f = self.cap.retrieve()
                if ok:
                    self.f = f
        if self.f is None:
            ok, self.f = self.cap.retrieve()
        return self.f


# ---------------------------------------------------------------- drawn cursor (same as web/src/demo/cursor.ts)
def path_pts(d, k):
    toks = re.findall(r"[MLQZ]|-?\d+(?:\.\d+)?", d)
    pts, i, cur = [], 0, (0, 0)
    while i < len(toks):
        c = toks[i]
        if c in "ML":
            cur = (float(toks[i + 1]), float(toks[i + 2]))
            pts.append(cur)
            i += 3
        elif c == "Q":
            cx, cy, x, y = map(float, toks[i + 1:i + 5])
            for j in range(1, 9):
                u = j / 8
                pts.append(((1 - u) ** 2 * cur[0] + 2 * (1 - u) * u * cx + u * u * x,
                            (1 - u) ** 2 * cur[1] + 2 * (1 - u) * u * cy + u * u * y))
            cur = (x, y)
            i += 5
        else:
            i += 1
    return [(x * k, y * k) for x, y in pts]


def cursor_sprite(scale=1.0):
    k, pad = 4, 12
    big = Image.new("RGBA", ((44 + 2 * pad) * k, (52 + 2 * pad) * k), (0, 0, 0, 0))
    g = ImageDraw.Draw(big)
    arrow = "M6 4 L6 40 Q6 43 9 41 L17 34 L23 47 Q24.5 50 27.5 48.6 L31 47 Q33.6 45.6 32.4 43 L26.4 30.6 L37 29.6 Q40 29.2 37.8 27 L9.2 2.4 Q6 0 6 4 Z"
    pts = [(x + pad * k, y + pad * k) for x, y in path_pts(arrow, k)]
    shadow = Image.new("RGBA", big.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).polygon([(x, y + 4 * k) for x, y in pts], fill=(0, 0, 0, 72))
    shadow = shadow.filter(ImageFilter.GaussianBlur(3 * k))
    g.polygon(pts, fill=(244, 241, 230, 255))
    g.line(pts + [pts[0]], fill=(11, 26, 18, 255), width=3 * k, joint="curve")
    for cx, cy in ((14, 18), (20, 23)):
        r = 2.2 * k
        x, y = (cx + pad) * k, (cy + pad) * k
        g.ellipse((x - r, y - r, x + r, y + r), fill=(11, 26, 18, 255))
    smile = [(x + pad * k, y + pad * k) for x, y in path_pts("M13 27 Q17 30 21 27.6", k)]
    g.line(smile, fill=(11, 26, 18, 255), width=2 * k, joint="curve")
    im = Image.alpha_composite(shadow, big)
    size = (round(im.width / k * scale), round(im.height / k * scale))
    return pil_bgra(im.resize(size, Image.LANCZOS)), (6 + pad) * scale, (4 + pad) * scale


CURSOR = cursor_sprite()
CURSOR_PRESS = cursor_sprite(0.86)


class LogCursor:
    """Cursor path from the driver log: '<ms> pos x y', '<ms> click x y', '<ms> rec ...'."""

    def __init__(self, path):
        ts, xs, ys, self.clicks, rec = [], [], [], [], 0
        for line in open(path):
            p = line.split()
            if len(p) < 2:
                continue
            ms = int(p[0])
            if p[1] == "rec":
                rec = ms
            if p[1] in ("pos", "click", "rclick") and len(p) >= 4:
                ts.append(ms), xs.append(int(p[2])), ys.append(int(p[3]))
                if p[1] != "pos":
                    self.clicks.append(ms)
        # The capture starts about 0.15 s after the logged rec line (measured from menu pop-up frames).
        off = rec + 150
        self.t = (np.array(ts) - off) / 1000
        self.x, self.y = np.array(xs, float), np.array(ys, float)
        self.clicks = [(c - off) / 1000 for c in self.clicks]

    def draw(self, img, x):
        cx, cy = np.interp(x, self.t, self.x), np.interp(x, self.t, self.y)
        for c in self.clicks:
            u = (x - c) / 0.6
            if 0 <= u <= 1:
                e = 1 - (1 - u) ** 3
                r = int(10 * (0.4 + 3.2 * e))
                ov = img.copy()
                cv2.circle(ov, (int(cx), int(cy)), r, (77, 216, 255), 4 + int(4 * (1 - e)), cv2.LINE_AA)
                a = 1 - u
                cv2.addWeighted(ov, a, img, 1 - a, 0, img)
        pressed = any(0 <= x - c < 0.14 for c in self.clicks)
        spr, ox, oy = CURSOR_PRESS if pressed else CURSOR
        blend(img, spr, int(round(cx - ox)), int(round(cy - oy)))


# ---------------------------------------------------------------- captions and badges
def font(sz):
    return ImageFont.truetype(FONT, sz)


def caption_img(text):
    f = font(44)
    l, t_, r, b = f.getbbox(text)
    pw, ph = 30, 16
    w, h = r - l + 2 * pw, 44 + 2 * ph + 8
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    g = ImageDraw.Draw(im)
    g.rounded_rectangle((0, 0, w - 1, h - 1), radius=18, fill=(8, 20, 14, 215))
    g.text((pw - l, ph + 2), text, font=f, fill=(255, 255, 255, 255))
    return pil_bgra(im)


def badge_img(speed):
    f = font(26)
    text = f"{speed:g}× speed" if speed > 1 else "slow motion"
    l, t_, r, b = f.getbbox(text)
    icon = 34
    w, h = r - l + icon + 40, 50
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    g = ImageDraw.Draw(im)
    g.rounded_rectangle((0, 0, w - 1, h - 1), radius=25, fill=(255, 216, 77, 240))
    ink = (11, 26, 18, 255)
    if speed > 1:
        for dx in (0, 13):
            g.polygon([(18 + dx, 15), (18 + dx, 35), (31 + dx, 25)], fill=ink)
    else:
        g.polygon([(20, 15), (20, 35), (35, 25)], fill=ink)
    g.text((icon + 22 - l, 9), text, font=f, fill=ink)
    return pil_bgra(im)


def chunks(words):
    """Split [(word, t0, t1)] into caption chunks of at most ~44 characters."""
    out, cur = [], []
    for w in words:
        if cur and len(" ".join(x[0] for x in cur + [w])) > 44:
            out.append(cur)
            cur = []
        cur.append(w)
        n = len(" ".join(x[0] for x in cur))
        if w[0][-1] in ".?:" or (w[0][-1] == "," and n >= 22):
            out.append(cur)
            cur = []
    if cur:
        out.append(cur)
    # glue a lone short tail (e.g. "sent.") back onto the chunk before it
    merged = []
    for c in out:
        if merged and len(" ".join(x[0] for x in c)) < 8 and len(" ".join(x[0] for x in merged[-1] + c)) <= 52:
            merged[-1] = merged[-1] + c
        else:
            merged.append(c)
    return merged


def sentences(words):
    out, cur = [], []
    for w in words:
        cur.append(w)
        if w[0][-1] in ".?":
            out.append(cur)
            cur = []
    if cur:
        out.append(cur)
    return out


# ---------------------------------------------------------------- audio and caption plan
def timed_line(text, heard):
    """Words of the script line with times taken from the transcript (whose spelling and
    punctuation can be off, e.g. 'cent' for 'sent'), matched character by character."""
    norm = lambda s: re.sub(r"[^a-z0-9]", "", s.lower())
    hc, ht, he = [], [], []
    for w, a, b in heard:
        n = norm(w)
        for j, ch in enumerate(n):
            hc.append(ch)
            ht.append(a + (b - a) * j / max(1, len(n)))
            he.append(b)
    words = text.split()
    sc, owner = [], []
    for i, w in enumerate(words):
        for ch in norm(w):
            sc.append(ch)
            owner.append(i)
    m = [None] * len(sc)
    for a, b, size in difflib.SequenceMatcher(None, sc, hc, autojunk=False).get_matching_blocks():
        for j in range(size):
            m[a + j] = b + j
    out = []
    for i, w in enumerate(words):
        idx = [m[k] for k, o in enumerate(owner) if o == i and m[k] is not None]
        if not idx:
            out.append([w, None, None])
            continue
        out.append([w, ht[idx[0]], he[idx[-1]]])
    # fill gaps from neighbours
    for i, o in enumerate(out):
        if o[1] is None:
            o[1] = out[i - 1][2] if i and out[i - 1][2] is not None else (out[i + 1][1] if i + 1 < len(out) else 0)
    for i, o in enumerate(out):
        if o[2] is None:
            heard_end = [b for w, a, b in heard if a <= o[1] + 1e-6]
            o[2] = out[i + 1][1] if i + 1 < len(out) and out[i + 1][1] is not None and out[i + 1][1] - o[1] < 0.9 else (heard_end[-1] if heard_end else o[1] + 0.3)
        o[2] = max(o[2], o[1] + 0.05)
    return [tuple(o) for o in out]


HEARD = json.load(open(os.path.join(RAW, "vo/words.json")))
LINES = {l["id"]: l["text"] for l in json.load(open(os.path.join(os.path.dirname(__file__), "lines.json")))["lines"]}
WORDS = {k: timed_line(LINES[k], HEARD[k]) for k in LINES}
if os.environ.get("SHOW_WORDS"):
    for k, v in WORDS.items():
        print(k, [(w, round(a, 2), round(b, 2)) for w, a, b in v])
    sys.exit(0)
SR = 24000
mix = np.zeros(int((TOTAL + 1) * SR), np.float32)
caps = []  # (t0, t1, text)
voice_spans = []  # (t0, t1) of speech, for the gap report
by_id = {s["id"]: s for s in SCENES}
for sid, mode, tt, line, i, j, k in VO:
    s = by_id[sid]
    ws = WORDS[line]
    j = len(ws) if j is None else j
    k = i if k is None else k
    w = wave.open(os.path.join(RAW, f"vo/{line}.wav"))
    assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2
    pcm = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
    a = 0.0 if i == 0 else (ws[i - 1][2] + ws[i][1]) / 2
    b = len(pcm) / SR if j == len(ws) else (ws[j - 1][2] + ws[j][1]) / 2
    a = max(a, ws[i][1] - 0.12)  # never more than a short breath before the first word
    place = s["start"] + (tt if mode == "out" else src_to_out(s, tt))
    at = place - (ws[k][1] - a)  # out time of the piece's audio start
    seg = pcm[int(a * SR):int(b * SR)].copy()
    fade = min(len(seg) // 2, int(0.012 * SR))
    seg[:fade] *= np.linspace(0, 1, fade)
    seg[-fade:] *= np.linspace(1, 0, fade)
    i0 = int(at * SR)
    over = at + len(seg) / SR - (s["start"] + s["dur"])
    if i0 < 0 or over > 0.05:
        print(f"WARNING voice {sid}[{i}:{j}] at {at:.2f}: {over:+.2f}s past the scene end", flush=True)
    if os.environ.get("PLAN"):
        print(f"  vo {sid:8s} {at:7.2f}-{at + len(seg) / SR:7.2f} scene {s['start']:7.2f}-{s['start'] + s['dur']:7.2f} {' '.join(x[0] for x in ws[i:j])}")
    mix[i0:i0 + len(seg)] += seg
    voice_spans.append((at + ws[i][1] - a, at + ws[j - 1][2] - a))
    cs = chunks(ws[i:j])
    for n, c in enumerate(cs):
        t0 = at + c[0][1] - a - 0.08
        t1 = at + (cs[n + 1][0][1] - a - 0.08 if n + 1 < len(cs) else c[-1][2] - a + 0.5)
        caps.append((t0, min(t1, s["start"] + s["dur"] - 0.03), " ".join(x[0] for x in c)))

# Captions of one piece end where the next one starts.
caps.sort()
caps = [(a0, min(a1, caps[n + 1][0]) if n + 1 < len(caps) else a1, tx) for n, (a0, a1, tx) in enumerate(caps)]


# ---------------------------------------------------------------- sound design (synthesized, quiet under the voice)
RNG = np.random.default_rng(7)


def env(n, attack, decay):
    t_ = np.arange(n) / SR
    return np.minimum(1, t_ / max(attack, 1e-4)) * np.exp(-t_ / decay)


def lowpass(x, k):
    return np.convolve(x, np.ones(k) / k, mode="same")


def sfx_click():
    n = int(0.05 * SR)
    t_ = np.arange(n) / SR
    tone = np.sin(2 * np.pi * 2300 * t_) * env(n, 0.0005, 0.008)
    noise = lowpass(RNG.standard_normal(n), 3) * env(n, 0.0003, 0.004)
    return 0.10 * tone + 0.06 * noise


def sfx_ticks():
    out = np.zeros(int(0.45 * SR), np.float32)
    for n in range(9):
        p = int((n * 0.045 + RNG.uniform(0, 0.012)) * SR)
        c = sfx_click() * 0.45 * RNG.uniform(0.7, 1.0)
        out[p:p + len(c)] += c[: len(out) - p]
    return out


def sfx_whoosh(big):
    d = 0.75 if big else 0.5
    n = int(d * SR)
    x = RNG.standard_normal(n)
    # a rising then falling band of noise: two moving averages of changing width
    e = np.sin(np.pi * np.arange(n) / n) ** 2
    y = lowpass(x, 18) - lowpass(x, 60)
    return (0.16 if big else 0.10) * y * e / (np.abs(y).max() + 1e-9) * 2


def sfx_thwack():
    n = int(0.35 * SR)
    t_ = np.arange(n) / SR
    f = 55 + 70 * np.exp(-t_ / 0.03)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, 0.001, 0.09)
    hit = lowpass(RNG.standard_normal(n), 4) * env(n, 0.0005, 0.012)
    return 0.30 * body + 0.10 * hit


def sfx_stamp():
    """The CAUGHT/CLEAR stamp: a shorter, quieter thwack."""
    return 0.5 * sfx_thwack()[: int(0.25 * SR)]


def sfx_pop():
    n = int(0.16 * SR)
    t_ = np.arange(n) / SR
    f = 520 + 420 * t_ / t_[-1]
    return 0.09 * np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, 0.003, 0.05)


def sfx_rise(d):
    n = int(d * SR)
    t_ = np.arange(n) / SR
    f = 260 + 220 * (t_ / max(d, 0.01)) ** 1.3
    tone = np.sin(2 * np.pi * np.cumsum(f) / SR) + 0.35 * np.sin(4 * np.pi * np.cumsum(f) / SR)
    trem = 0.75 + 0.25 * np.sin(2 * np.pi * 7 * t_)
    fade = np.minimum(1, np.minimum(t_ / 0.15, (d - t_) / 0.12))
    return 0.028 * tone * trem * np.clip(fade, 0, 1)


fx = np.zeros_like(mix)


def put(x, at):
    i0 = int(at * SR)
    if i0 < 0 or i0 >= len(fx):
        return
    fx[i0:i0 + len(x)] += x[: len(fx) - i0]


for s in SCENES:
    to_out = (lambda x, s=s: s["start"] + (src_to_out(s, x) if "segs" in s else x))
    inside = (lambda x, s=s: "segs" not in s or s["segs"][0][0] <= x <= s["segs"][-1][1])
    events = list(s.get("sfx", []))
    if s.get("log"):
        events += [("click", c) for c in LogCursor(os.path.join(RAW, s["log"])).clicks]
    for ev in events:
        kind, x = ev[0], ev[1]
        if not inside(x):
            continue
        if kind == "rise":
            t0, t1 = to_out(ev[1]), to_out(ev[2])
            put(sfx_rise(t1 - t0), t0)
        else:
            put({"click": sfx_click, "ticks": sfx_ticks, "thwack": sfx_thwack, "stamp": sfx_stamp, "pop": sfx_pop}[kind]()
                if kind != "whoosh" else sfx_whoosh(ev[2]), to_out(x))

def level(x):
    on = np.abs(x) > 1e-4
    return 20 * np.log10(np.sqrt((x[on] ** 2).mean()) + 1e-9), 20 * np.log10(np.abs(x).max() + 1e-9)


(vr, vp), (fr, fp) = level(mix), level(fx)
print(f"voice rms {vr:.1f} dB peak {vp:.1f} dB; effects rms {fr:.1f} dB peak {fp:.1f} dB (before loudness normalising)", flush=True)
mix += fx
gaps = sorted(voice_spans)
between = sorted((((b0 - a1), a1) for (a0, a1), (b0, b1) in zip(gaps, gaps[1:])), reverse=True)
lead, tail = gaps[0][0], TOTAL - gaps[-1][1]
print(f"total {TOTAL:.2f}s, longest gaps between voice lines: " + ", ".join(f"{g:.2f}s at {a:.2f}s" for g, a in between[:3])
      + f"; silence before the first line {lead:.2f}s, after the last {tail:.2f}s", flush=True)
if os.environ.get("PLAN"):
    for a0, a1, tx in caps:
        print(f"{a0:7.2f} {a1:7.2f} {tx}")
    sys.exit(0)

cap_imgs = {c[2]: caption_img(c[2]) for c in caps}
tmp = tempfile.mkdtemp()
wav_path = os.path.join(tmp, "vo.wav")
with wave.open(wav_path, "wb") as w:
    w.setnchannels(1), w.setsampwidth(2), w.setframerate(SR)
    w.writeframes((np.clip(mix, -1, 1) * 32767).astype(np.int16).tobytes())

# ---------------------------------------------------------------- static layers
def frame_layer():
    """Background with a soft shadow and the rounded mask for screen takes."""
    bg = Image.new("RGB", (W, H), BG[::-1])
    sh = Image.new("L", (W, H), 0)
    ImageDraw.Draw(sh).rounded_rectangle((FX, FY + 14, FX + FW, FY + FH + 14), radius=22, fill=150)
    sh = sh.filter(ImageFilter.GaussianBlur(22))
    bg = Image.composite(Image.new("RGB", (W, H), (0, 0, 0)), bg, sh)
    mask = Image.new("L", (FW, FH), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, FW - 1, FH - 1), radius=20, fill=255)
    return np.array(bg)[:, :, ::-1].copy(), (np.array(mask, np.float32) / 255)[:, :, None]


BG_IMG, MASK = frame_layer()

# Android: the phone sits centred with a short label beside it.
PH_H = 912
PH_W = round(PH_H * 1080 / 2400)
PH_X, PH_Y = (W - PH_W) // 2, 40


def phone_layer():
    im = Image.new("RGB", (W, H), BG[::-1])
    g = ImageDraw.Draw(im)
    sh = Image.new("L", (W, H), 0)
    ImageDraw.Draw(sh).rounded_rectangle((PH_X - 14, PH_Y - 2, PH_X + PH_W + 14, PH_Y + PH_H + 30), radius=56, fill=170)
    sh = sh.filter(ImageFilter.GaussianBlur(24))
    im = Image.composite(Image.new("RGB", (W, H), (0, 0, 0)), im, sh)
    g = ImageDraw.Draw(im)
    g.rounded_rectangle((PH_X - 14, PH_Y - 14, PH_X + PH_W + 14, PH_Y + PH_H + 14), radius=54, fill=(12, 22, 16),
                        outline=(42, 58, 48), width=2)
    g.text((240, 420), "Android", font=font(64), fill=(244, 241, 230))
    g.text((240, 505), "Share a text to Shortstop.", font=font(30), fill=(176, 204, 186))
    g.text((240, 548), "Same engine as the web app.", font=font(30), fill=(176, 204, 186))
    mask = Image.new("L", (PH_W, PH_H), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, PH_W - 1, PH_H - 1), radius=42, fill=255)
    return np.array(im)[:, :, ::-1].copy(), (np.array(mask, np.float32) / 255)[:, :, None]


PH_IMG, PH_MASK = phone_layer()
BADGES = {}

# ---------------------------------------------------------------- render
enc = subprocess.Popen(
    ["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{W}x{H}", "-r", str(FPS),
     "-i", "-", "-i", wav_path, "-af", "aresample=48000,loudnorm=I=-16:TP=-1.5:LRA=11",
     "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p", "-profile:v", "high",
     "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart", "-shortest", OUT],
    stdin=subprocess.PIPE)

n_total = int(round(TOTAL * FPS))
for s in SCENES:
    src = Src(os.path.join(RAW, s["src"]))
    cur = LogCursor(os.path.join(RAW, s["log"])) if s.get("log") else None
    n = int(round(s["dur"] * FPS))
    for i in range(n):
        u = i / FPS
        gt = s["start"] + u
        if s["kind"] == "full":
            frame = src.at(u).copy()
            sp = 1
        else:
            x, sp = out_to_src(s, u)
            img = src.at(x).copy()
            if s["kind"] == "screen":
                if cur:
                    cur.draw(img, x)
                cx, cy, z = cam_at(s.get("cam"), x)
                z *= 1 + 0.04 * u / s["dur"]  # a slow push through every take, so no frame stands still
                view = crop_zoom(img, cx, cy, z, FW, FH)
                frame = BG_IMG.copy()
                roi = frame[FY:FY + FH, FX:FX + FW]
                roi[:] = (view * MASK + roi * (1 - MASK)).astype(np.uint8)
                bx, by = FX + 26, FY + 26
            else:
                view = cv2.resize(img, (PH_W, PH_H), interpolation=cv2.INTER_AREA)
                frame = PH_IMG.copy()
                roi = frame[PH_Y:PH_Y + PH_H, PH_X:PH_X + PH_W]
                roi[:] = (view * PH_MASK + roi * (1 - PH_MASK)).astype(np.uint8)
                bx, by = PH_X + PH_W + 40, PH_Y + 30
            if sp != 1:
                if sp not in BADGES:
                    BADGES[sp] = badge_img(sp)
                blend(frame, BADGES[sp], bx, by)
        for c0, c1, text in caps:
            if c0 <= gt < c1:
                a = min(1.0, (gt - c0) / 0.15, (c1 - gt) / 0.15)
                ci = cap_imgs[text]
                blend(frame, ci, (W - ci.shape[1]) // 2, CAP_Y - ci.shape[0] // 2, a)
        k = int(round(gt * FPS))
        if k < 12:
            frame = (frame * (k / 12)).astype(np.uint8)
        elif k >= n_total - 15:
            frame = (frame * ((n_total - 1 - k) / 15)).astype(np.uint8)
        enc.stdin.write(frame.tobytes())
    print(f"{s['id']} done", flush=True)

enc.stdin.close()
sys.exit(enc.wait())
