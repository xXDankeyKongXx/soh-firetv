import math, random
from PIL import Image, ImageDraw, ImageFilter, ImageChops

S = 1024  # master canvas

def bez(p0, p1, p2, p3, n=40):
    out = []
    for i in range(n + 1):
        t = i / n; u = 1 - t
        out.append((u**3*p0[0] + 3*u*u*t*p1[0] + 3*u*t*t*p2[0] + t**3*p3[0],
                    u**3*p0[1] + 3*u*u*t*p1[1] + 3*u*t*t*p2[1] + t**3*p3[1]))
    return out

def path(start, segs, scale=1.0, cx=512, cy=512, dy=0):
    pts = [start]; cur = start
    for c1, c2, end in segs:
        pts += bez(cur, c1, c2, end)[1:]; cur = end
    return [((x - cx) * scale + cx, (y - cy) * scale + cy + dy) for x, y in pts]

# Flame outline in a 1024 box: tall centre tongue, left and right tongues, round base.
FLAME = ((512, 930), [
    ((330, 930), (225, 800), (250, 640)),     # round base, left side
    ((265, 545), (320, 480), (318, 395)),     # up to the left tongue tip
    ((370, 450), (390, 520), (420, 560)),     # back down into the left notch
    ((395, 420), (450, 250), (540, 95)),      # sweep up to the centre tip
    ((560, 240), (600, 330), (640, 420)),     # down the right of the centre tongue
    ((660, 380), (690, 330), (705, 268)),     # right tongue tip
    ((780, 420), (800, 560), (775, 690)),     # right shoulder
    ((745, 850), (640, 930), (512, 930)),     # back to the base
])

def mask_from(points, size=S):
    m = Image.new('L', (size, size), 0)
    ImageDraw.Draw(m).polygon(points, fill=255)
    return m

def vgrad(size, top, bottom):
    g = Image.new('RGBA', (size, size))
    d = ImageDraw.Draw(g)
    for y in range(size):
        t = y / (size - 1)
        d.line([(0, y), (size, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(4)))
    return g

def background(size=S):
    bg = Image.new('RGBA', (size, size))
    px = bg.load()
    cx, cy = size * 0.5, size * 0.62
    inner, outer = (92, 18, 22), (12, 13, 30)
    for y in range(size):
        for x in range(size):
            r = min(1.0, math.hypot(x - cx, y - cy) / (size * 0.72))
            r = r ** 1.3
            px[x, y] = tuple(int(inner[i] + (outer[i] - inner[i]) * r) for i in range(3)) + (255,)
    # a few drifting embers
    rnd = random.Random(7)
    em = Image.new('RGBA', (size, size), (0, 0, 0, 0)); d = ImageDraw.Draw(em)
    for _ in range(22):
        x = rnd.uniform(0.12, 0.88) * size; y = rnd.uniform(0.06, 0.55) * size
        r = rnd.uniform(3, 8) * size / 1024
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, rnd.randint(120, 190), 60, rnd.randint(90, 200)))
    em = em.filter(ImageFilter.GaussianBlur(1.2 * size / 1024))
    return Image.alpha_composite(bg, em)

def flame(scale=1.0, dy=0):
    layer = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    start, segs = FLAME
    outer = path(start, segs, scale, cy=900, dy=dy)
    # glow
    glow = mask_from(outer).filter(ImageFilter.GaussianBlur(38))
    glow_img = Image.new('RGBA', (S, S), (255, 110, 30, 0)); glow_img.putalpha(glow.point(lambda v: int(v * 0.75)))
    layer = Image.alpha_composite(layer, glow_img)
    # three nested layers: outer red-orange, middle orange-gold, inner pale core
    specs = [(1.00, 0,   (255, 70, 25, 255),  (255, 140, 35, 255)),
             (0.74, 70,  (255, 150, 30, 255), (255, 205, 70, 255)),
             (0.46, 150, (255, 228, 140, 255), (255, 250, 225, 255))]
    for k, off, top, bottom in specs:
        # every layer is scaled toward the flame's base so they nest like a real flame
        pts = path(start, segs, scale * k, cy=900, dy=dy)
        if k < 1.0:  # inner layers: shrink around the base of the (already scaled) outer flame
            pts = path(start, segs, k, cy=900)
            pts = [((x - 512) * scale + 512, (y - 900) * scale + 900 + dy) for x, y in pts]
        m = mask_from(pts).filter(ImageFilter.GaussianBlur(1.5))
        fill = vgrad(S, top, bottom); fill.putalpha(m)
        layer = Image.alpha_composite(layer, fill)
    # negative-space sail inside the core: mast, mainsail, jib and a small hull
    cut = Image.new('L', (S, S), 0); d = ImageDraw.Draw(cut)
    def T(x, y):  # place the boat relative to the flame base, following scale
        return ((x - 512) * scale + 512, (y - 900) * scale + 900 + dy)
    d.polygon([T(520, 640), T(520, 820), T(605, 820)], fill=255)        # mainsail
    d.polygon([T(506, 676), T(506, 820), T(455, 820)], fill=255)        # jib
    d.polygon([T(440, 836), T(620, 836), T(595, 866), T(465, 866)], fill=255)  # hull
    cut = cut.filter(ImageFilter.GaussianBlur(1.2))
    boat = Image.new('RGBA', (S, S), (120, 24, 18, 255)); boat.putalpha(cut)
    layer = Image.alpha_composite(layer, boat)
    return layer

def compose(full_bleed=True, scale=1.0, dy=0, size=S, round_mask=False):
    img = background(S) if full_bleed else Image.new('RGBA', (S, S), (0, 0, 0, 0))
    img = Image.alpha_composite(img, flame(scale, dy))
    if round_mask:
        m = Image.new('L', (S, S), 0); ImageDraw.Draw(m).ellipse([0, 0, S - 1, S - 1], fill=255)
        img.putalpha(m)
    return img.resize((size, size), Image.LANCZOS)

if __name__ == '__main__':
    import sys
    out = sys.argv[1]
    compose().save(f'{out}/preview_square.png')
    compose(round_mask=True).save(f'{out}/preview_round.png')

def centered(scale):
    # scaling happens toward the base (y=900); shift so the flame stays centred on the canvas
    return dict(scale=scale, dy=-388 * (1 - scale))

def rounded(img, radius_frac=0.18):
    m = Image.new('L', img.size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius=int(img.size[0] * radius_frac), fill=255)
    out = img.copy(); out.putalpha(ImageChops.multiply(out.getchannel('A'), m)); return out

def export(res_dir):
    import os
    dens = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
    legacy_full = compose(**centered(0.86))
    round_full = compose(round_mask=True, **centered(0.80))
    fg = compose(full_bleed=False, **centered(0.64))   # inside the 66dp adaptive-icon safe circle
    for name, k in dens.items():
        d = f'{res_dir}/mipmap-{name}'; os.makedirs(d, exist_ok=True)
        for f in os.listdir(d):
            if f.startswith('ic_launcher'): os.remove(f'{d}/{f}')
        legacy = int(48 * k); fgsz = int(108 * k)
        rounded(legacy_full.resize((legacy, legacy), Image.LANCZOS)).save(f'{d}/ic_launcher.png', optimize=True)
        round_full.resize((legacy, legacy), Image.LANCZOS).save(f'{d}/ic_launcher_round.png', optimize=True)
        fg.resize((fgsz, fgsz), Image.LANCZOS).save(f'{d}/ic_launcher_foreground.png', optimize=True)
    os.makedirs(f'{res_dir}/drawable-nodpi', exist_ok=True)
    background(S).resize((432, 432), Image.LANCZOS).save(f'{res_dir}/drawable-nodpi/ic_launcher_background.png', optimize=True)
    banner().save(f'{res_dir}/drawable-xhdpi/tv_banner.png', optimize=True)

def banner():
    from PIL import ImageFont
    W, H, k = 320, 180, 4
    big = Image.new('RGBA', (W * k, H * k))
    bgsq = background(W * k)                                   # square, crop the middle band
    big.paste(bgsq.crop((0, (W * k - H * k) // 2 + 60, W * k, (W * k - H * k) // 2 + 60 + H * k)), (0, 0))
    fl = flame(**centered(0.86)).resize((H * k - 40, H * k - 40), Image.LANCZOS)
    big.alpha_composite(fl, (34, 20))
    d = ImageDraw.Draw(big)
    serif = '/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf'
    sans = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
    d.text((148 * k, 54 * k), "Ship of", font=ImageFont.truetype(serif, 17 * k), fill=(255, 196, 92))
    d.text((146 * k, 74 * k), "Harkinian", font=ImageFont.truetype(serif, 26 * k), fill=(255, 244, 228))
    d.text((149 * k, 110 * k), "FIRE TV EDITION", font=ImageFont.truetype(sans, 10 * k), fill=(255, 128, 52))
    return big.resize((W, H), Image.LANCZOS).convert('RGB')
