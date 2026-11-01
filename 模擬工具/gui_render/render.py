#!/usr/bin/env python3
"""
用 Minecraft 官方客戶端材質重繪箱子介面（給沒有遊戲客戶端的環境看 ManaCore 的選單長什麼樣）。
輸入：dump.js 讀出的 gui.json；輸出：每個選單一張 PNG（GUI scale 4）。
"""
import json, math, os, re, sys, zipfile
from PIL import Image, ImageDraw

S = os.environ.get('MCDIR')
A = os.path.join(S, 'assets/assets/minecraft')
SCALE = 4
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

COLORS = {'black': (0, 0, 0), 'dark_blue': (0, 0, 170), 'dark_green': (0, 170, 0), 'dark_aqua': (0, 170, 170), 'dark_red': (170, 0, 0),
          'dark_purple': (170, 0, 170), 'gold': (255, 170, 0), 'gray': (170, 170, 170), 'dark_gray': (85, 85, 85), 'blue': (85, 85, 255),
          'green': (85, 255, 85), 'aqua': (85, 255, 255), 'red': (255, 85, 85), 'light_purple': (255, 85, 255), 'yellow': (255, 255, 85), 'white': (255, 255, 255)}
LANG = json.load(open(os.path.join(S, 'zh_tw.json'), encoding='utf-8'))
LANG_EN = json.load(open(os.path.join(A, 'lang/en_us.json'), encoding='utf-8'))

# ---------------- 字型 ----------------
class Font:
    def __init__(self):
        self.glyphs = {}  # char -> (Image 'L' mask 8px高(或等比), advance)
        d = json.load(open(os.path.join(A, 'font/include/default.json')))
        for p in d['providers']:
            if p['type'] != 'bitmap': continue
            img = Image.open(os.path.join(A, 'textures/font', p['file'].split('/')[-1])).convert('RGBA')
            rows = p['chars']; cols = len(rows[0])
            cw, ch = img.width // cols, img.height // len(rows)
            h = p.get('height', 8); scale = h / ch
            for r, row in enumerate(rows):
                for c, chv in enumerate(row):
                    if chv == '\x00' or chv in self.glyphs: continue
                    cell = img.crop((c * cw, r * ch, c * cw + cw, r * ch + ch))
                    alpha = cell.split()[3]
                    bbox = alpha.getbbox()
                    if not bbox: continue
                    right = bbox[2]
                    mask = alpha.crop((0, 0, right, ch))
                    if scale != 1: mask = mask.resize((max(1, round(right * scale)), h), Image.NEAREST)
                    self.glyphs[chv] = (mask, int(0.5 + right * scale) + 1, 1.0)
        # unifont（16px，遊戲中以 1/2 縮放顯示）
        self.uni = {}
        for name in os.listdir(os.path.join(S, 'unifont')):
            if not name.endswith('.hex'): continue
            for line in open(os.path.join(S, 'unifont', name)):
                cp, hx = line.strip().split(':')
                self.uni[int(cp, 16)] = hx

    def glyph(self, chv):
        if chv == ' ': return None, 4
        if chv in self.glyphs:
            m, adv, _ = self.glyphs[chv]; return m, adv
        hx = self.uni.get(ord(chv))
        if not hx: return None, 4
        w = len(hx) * 4 // 16  # 8 或 16
        bits = bin(int(hx, 16))[2:].zfill(len(hx) * 4)
        img = Image.new('L', (w, 16), 0)
        px = img.load()
        for y in range(16):
            for x in range(w):
                if bits[y * w + x] == '1': px[x, y] = 255
        bbox = img.getbbox()
        if not bbox: return None, 4
        img = img.crop((bbox[0], 0, bbox[2], 16))
        # 以 0.5 縮放：繪製時用 8px 高的遮罩（寬度減半）
        gw = max(1, math.ceil(img.width / 2))
        mask = img.resize((img.width, 16), Image.NEAREST)
        self.glyphs[chv] = (mask, gw + 1, 0.5)
        return mask, gw + 1

    def width(self, text):
        return sum(self.glyph(c)[1] for c in text)

FONT = Font()

def nbt_plain(o):
    """mineflayer 回傳的 NBT 形式 {"type":..,"value":..} → 一般 JSON"""
    if isinstance(o, dict) and 'type' in o and 'value' in o:
        t, v = o['type'], o['value']
        if t == 'compound': return {k: nbt_plain(x) for k, x in v.items()}
        if t == 'list': return [nbt_plain({'type': v['type'], 'value': x}) for x in v['value']] if isinstance(v, dict) else [nbt_plain(x) for x in v]
        return nbt_plain(v)
    if isinstance(o, dict): return {k: nbt_plain(x) for k, x in o.items()}
    if isinstance(o, list): return [nbt_plain(x) for x in o]
    return o

def parse_component(obj, color=None, out=None):
    """JSON 文字元件 → [(text, color)]"""
    if out is None: out = []
    if obj is None: return out
    if isinstance(obj, str):
        try: obj = nbt_plain(json.loads(obj))
        except Exception: out.append((obj, color)); return out
        if isinstance(obj, str): out.append((obj, color)); return out
    if isinstance(obj, list):
        for o in obj: parse_component(o, color, out)
        return out
    col = obj.get('color', color)
    if isinstance(col, str):
        col = tuple(int(col[i:i + 2], 16) for i in (1, 3, 5)) if col.startswith('#') else COLORS.get(col, (255, 255, 255))
    if 'text' in obj: out.append((obj['text'], col))
    elif 'translate' in obj:
        key = obj['translate']
        out.append((LANG.get(key) or LANG_EN.get(key) or key, col))
    for e in obj.get('extra', []): parse_component(e, col, out)
    return out

def draw_text(img, x, y, parts, default=(255, 255, 255), shadow=True):
    """x,y 以 GUI 像素為單位；回傳結束 x"""
    for text, col in parts:
        col = col or default
        for chv in text:
            mask, adv = FONT.glyph(chv)
            if mask is not None:
                scale = FONT.glyphs.get(chv, (None, None, 1.0))[2]
                gw = max(1, round(mask.width * scale * SCALE)); gh = round(mask.height * scale * SCALE)
                m = mask.resize((gw, gh), Image.NEAREST)
                if shadow:
                    sh = Image.new('RGBA', m.size, tuple(c // 4 for c in col) + (255,))
                    img.paste(sh, (x * SCALE + SCALE, y * SCALE + SCALE), m)
                fg = Image.new('RGBA', m.size, tuple(col) + (255,))
                img.paste(fg, (x * SCALE, y * SCALE), m)
            x += adv
    return x

# ---------------- 物品圖示 ----------------
MODEL_CACHE = {}
def load_json(rel):
    p = os.path.join(A, rel)
    if not os.path.exists(p): return None
    return json.load(open(p))

def resolve_model(path):
    """回傳 (chain_root_name, textures dict 已解析)"""
    textures, chain = {}, []
    cur = path
    for _ in range(12):
        name = cur.split(':')[-1]
        m = load_json('models/' + name + '.json')
        if not m: break
        chain.append(name)
        for k, v in m.get('textures', {}).items():
            textures.setdefault(k, v)
        if 'parent' not in m: break
        cur = m['parent']
    # 解析 #ref
    for _ in range(6):
        for k, v in list(textures.items()):
            if isinstance(v, str) and v.startswith('#') and v[1:] in textures: textures[k] = textures[v[1:]]
    return chain, textures

def tex(ref):
    if not ref or ref.startswith('#'): return None
    p = os.path.join(A, 'textures', ref.split(':')[-1] + '.png')
    if not os.path.exists(p): return None
    im = Image.open(p).convert('RGBA')
    if im.height > im.width: im = im.crop((0, 0, im.width, im.width))  # 動畫材質取第一格
    return im

CUBES = {'cube_all', 'cube', 'cube_column', 'cube_column_horizontal', 'cube_bottom_top', 'cube_directional', 'cube_mirrored_all',
         'cube_mirrored', 'orientable', 'orientable_with_bottom', 'orientable_vertical', 'cube_top', 'leaves', 'cube_north_west_mirrored',
         'template_glazed_terracotta', 'cube_bottom_top_with_bottom'}

def shade(im, f):
    r, g, b, a = im.split()
    return Image.merge('RGBA', (r.point(lambda v: int(v * f)), g.point(lambda v: int(v * f)), b.point(lambda v: int(v * f)), a))

def iso_cube(top, left, right, size):
    """16 單位的等角方塊 → size px 圖"""
    R = 8  # 高解析倍率
    W = 16 * R
    out = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    def quad(im, pts):
        im = im.resize((W, W), Image.NEAREST)
        # PIL QUAD：把輸出區域對應回來源；用 MESH 反向映射簡化：以 transform(QUAD) 逆向需要來源四點；改用 affine
        # 這裡用 Image.transform 的 PERSPECTIVE 係數
        src = [(0, 0), (W, 0), (W, W), (0, W)]
        coeffs = perspective(pts, src)
        return im.transform((W, W), Image.PERSPECTIVE, coeffs, Image.NEAREST)
    s = R
    # 等角：上面菱形、左面、右面（和遊戲中的方塊圖示相近）
    top_pts = [(8 * s, 0), (16 * s, 4 * s), (8 * s, 8 * s), (0, 4 * s)]
    left_pts = [(0, 4 * s), (8 * s, 8 * s), (8 * s, 16 * s), (0, 12 * s)]
    right_pts = [(8 * s, 8 * s), (16 * s, 4 * s), (16 * s, 12 * s), (8 * s, 16 * s)]
    for im, pts, f in ((top, top_pts, 1.0), (left, left_pts, 0.8), (right, right_pts, 0.6)):
        if im is None: continue
        layer = quad(shade(im, f), pts)
        out.alpha_composite(layer)
    return out.resize((size, size), Image.LANCZOS)

def perspective(dst, src):
    import numpy as np
    matrix = []
    for (x, y), (u, v) in zip(dst, src):
        matrix.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        matrix.append([0, 0, 0, x, y, 1, -v * x, -v * y])
    Am = np.array(matrix, dtype=float)
    B = np.array([c for p in src for c in p], dtype=float)
    res = np.linalg.solve(Am, B)
    return tuple(res)

HEAD_SKIN = os.environ.get('HEAD_SKIN')
def icon_for(slot, size):
    name = slot['name']
    if name == 'player_head' and HEAD_SKIN:
        skin = Image.open(HEAD_SKIN).convert('RGBA')
        top = skin.crop((8, 0, 16, 8)).resize((16, 16), Image.NEAREST)
        front = skin.crop((8, 8, 16, 16)).resize((16, 16), Image.NEAREST)
        side = skin.crop((16, 8, 24, 16)).resize((16, 16), Image.NEAREST)
        return iso_cube(top, front, side, size)
    it = load_json('items/' + name + '.json')
    model = None
    if it:
        m = it.get('model', {})
        model = m.get('model') if isinstance(m.get('model'), str) else None
        if not model:
            # 條件式 / 複合模型：找第一個 model 字串
            txt = json.dumps(it)
            mm = re.search(r'"model":\s*"(minecraft:(?:item|block)/[^"]+)"', txt)
            if mm: model = mm.group(1)
    if not model: model = 'minecraft:item/' + name
    chain, textures = resolve_model(model)
    root = chain[-1].split('/')[-1] if chain else ''
    kinds = [c.split('/')[-1] for c in chain]
    is_item_flat = any(k in ('generated', 'handheld', 'handheld_rod', 'handheld_mace') for k in kinds) or 'layer0' in textures
    if is_item_flat:
        im = tex(textures.get('layer0'))
        if im is None: im = tex('item/' + name)
        if im is None: return None
        out = im.resize((size, size), Image.NEAREST)
        l1 = tex(textures.get('layer1'))
        if l1: out.alpha_composite(l1.resize((size, size), Image.NEAREST))
        return out
    if any(k in CUBES for k in kinds):
        top = tex(textures.get('up') or textures.get('end') or textures.get('top') or textures.get('all'))
        side = tex(textures.get('side') or textures.get('all') or textures.get('north') or textures.get('front'))
        front = tex(textures.get('front') or textures.get('south') or textures.get('side') or textures.get('all'))
        if top is None: top = side
        return iso_cube(top, front, side, size)
    # 其他（十字形、燈籠等）：平面顯示代表材質
    for k in ('cross', 'texture', 'lantern', 'side', 'particle', 'all', 'top', 'front', 'plant', 'pattern'):
        im = tex(textures.get(k))
        if im: return im.resize((size, size), Image.NEAREST)
    for v in textures.values():
        im = tex(v)
        if im: return im.resize((size, size), Image.NEAREST)
    im = tex('item/' + name) or tex('block/' + name)
    return im.resize((size, size), Image.NEAREST) if im else None

def glint(im):
    r, g, b, a = im.split()
    overlay = Image.new('RGBA', im.size, (140, 70, 230, 90))
    overlay.putalpha(a.point(lambda v: 90 if v > 0 else 0))
    im = im.copy(); im.alpha_composite(overlay); return im

# ---------------- 畫介面 ----------------
GEN = Image.open(os.path.join(A, 'textures/gui/container/generic_54.png')).convert('RGBA')

def render(name, page):
    rows = page['rows']
    gw, gh = 176, 114 + rows * 18
    # 畫布：左右留空間給提示框
    margin_r = 200
    W, H = (gw + margin_r + 16) * SCALE, (gh + 16) * SCALE
    img = Image.new('RGBA', (W, H), (28, 30, 38, 255))
    ox, oy = 8, 8  # GUI 左上（GUI px）
    def blit(src_box, dst):
        part = GEN.crop(src_box).resize(((src_box[2] - src_box[0]) * SCALE, (src_box[3] - src_box[1]) * SCALE), Image.NEAREST)
        img.alpha_composite(part, ((ox + dst[0]) * SCALE, (oy + dst[1]) * SCALE))
    blit((0, 0, 176, 17 + rows * 18), (0, 0))
    blit((0, 126, 176, 222), (0, 17 + rows * 18))
    # 標題（資源包樣式：標題裡的背景字元 → 畫背景面板蓋住箱子）
    title_parts = parse_component(page['title'])
    styled = False
    for t, _ in title_parts:
        for ch in t:
            code = ord(ch)
            if 0xE200 <= code < 0xE200 + 20 and PACK:
                ri, ai = divmod(code - 0xE200, 5)
                acc = ['self', 'social', 'life', 'alchemy', 'info'][ai]
                bg = Image.open(os.path.join(PACK, f'bg_{ri + 3}_{acc}.png')).convert('RGBA')
                bg = bg.resize((bg.width * SCALE, bg.height * SCALE), Image.NEAREST)
                img.alpha_composite(bg, (ox * SCALE, oy * SCALE)); styled = True
    title_parts = [(''.join(c for c in t if not (0xE100 <= ord(c) < 0xE300)), col) for t, col in title_parts]
    draw_text(img, ox + 8, oy + 6, title_parts, default=(64, 64, 64), shadow=False)
    draw_text(img, ox + 8, oy + gh - 94, [('物品欄', None)], default=(64, 64, 64), shadow=False)
    hover = HOVER.get(name)
    def draw_slot(slot, sx, sy):
        if not slot: return
        ic = styled_icon(slot, 16 * SCALE) if styled else None
        if ic is None: ic = icon_for(slot, 16 * SCALE)
        if ic is None: return
        if slot.get('glint'): ic = glint(ic)
        img.alpha_composite(ic, ((ox + sx) * SCALE, (oy + sy) * SCALE))
        if slot['count'] > 1:
            t = str(slot['count'])
            draw_text(img, ox + sx + 17 - FONT.width(t), oy + sy + 9, [(t, None)])
    for i, slot in enumerate(page['slots']):
        sx, sy = 8 + (i % 9) * 18, 18 + (i // 9) * 18
        draw_slot(slot, sx, sy)
    for i, slot in enumerate(page.get('hotbar') or []):
        draw_slot(slot, 8 + i * 18, gh - 24)
    # 懸停格：白色半透明 + 提示框
    if hover is not None and page['slots'][hover]:
        i = hover
        sx, sy = 8 + (i % 9) * 18, 18 + (i // 9) * 18
        hl = Image.new('RGBA', (16 * SCALE, 16 * SCALE), (255, 255, 255, 128))
        img.alpha_composite(hl, ((ox + sx) * SCALE, (oy + sy) * SCALE))
        cur = ImageDraw.Draw(img)
        cx, cy = (ox + sx + 11) * SCALE, (oy + sy + 11) * SCALE
        pts = [(cx, cy), (cx, cy + 14 * SCALE // 2), (cx + 4 * SCALE // 2, cy + 10 * SCALE // 2), (cx + 7 * SCALE // 2, cy + 16 * SCALE // 2), (cx + 9 * SCALE // 2, cy + 15 * SCALE // 2), (cx + 6 * SCALE // 2, cy + 9 * SCALE // 2), (cx + 11 * SCALE // 2, cy + 9 * SCALE // 2)]
        cur.polygon(pts, fill=(255, 255, 255), outline=(0, 0, 0), width=SCALE // 2)
        slot = page['slots'][i]
        lines = [parse_component(slot['displayName'])] if slot['displayName'] else [[(LANG.get('item.minecraft.' + slot['name']) or LANG.get('block.minecraft.' + slot['name']) or slot['name'], None)]]
        for l in slot.get('lore', []): lines.append(parse_component(l, (170, 170, 170)))
        tw = max(FONT.width(''.join(t for t, _ in l)) for l in lines)
        th = 8 + (len(lines) - 1) * 10 + (2 if len(lines) > 1 else 0)
        # 提示框放在視窗右側（遊戲中會跟著滑鼠蓋在格子上；這裡為了看清楚整個介面而移到旁邊）
        tx, ty = ox + gw + 10, oy + sy - 4
        if ty < 4: ty = 4
        if tx + tw + 8 > W // SCALE:
            img2 = Image.new('RGBA', ((tx + tw + 8) * SCALE, H), (28, 30, 38, 255)); img2.paste(img, (0, 0)); img = img2; W = img.width
        if ty + th + 4 > H // SCALE - 4:
            # 畫布加高
            extra = (ty + th + 8) * SCALE - H
            img2 = Image.new('RGBA', (W, H + extra), (28, 30, 38, 255)); img2.paste(img, (0, 0)); img = img2
        d = ImageDraw.Draw(img)
        x0, y0, x1, y1 = (tx - 3) * SCALE, (ty - 3) * SCALE, (tx + tw + 3) * SCALE, (ty + th + 3) * SCALE
        d.rectangle((x0, y0, x1, y1), fill=(16, 0, 16, 240))
        # 漸層邊框
        for k in range(y0 + SCALE, y1 - SCALE):
            f = (k - y0) / max(1, (y1 - y0))
            c = tuple(int(a + (b - a) * f) for a, b in zip((80, 0, 255), (40, 0, 127)))
            d.line((x0 + SCALE, k, x0 + 2 * SCALE - 1, k), fill=c); d.line((x1 - 2 * SCALE, k, x1 - SCALE - 1, k), fill=c)
        d.line((x0 + SCALE, y0 + SCALE, x1 - SCALE, y0 + SCALE), fill=(80, 0, 255), width=SCALE)
        d.line((x0 + SCALE, y1 - SCALE, x1 - SCALE, y1 - SCALE), fill=(40, 0, 127), width=SCALE)
        y = ty
        for n, l in enumerate(lines):
            draw_text(img, tx, y, l, default=(255, 255, 255))
            y += 10 if n == 0 and len(lines) > 1 else 10
            if n == 0 and len(lines) > 1: y += 2
    img.convert('RGB').save(os.path.join(OUT, name + '.png'))
    print('saved', name)

# ---- 資源包樣式（styled 模式）----
PACK = os.environ.get('PACKDIR')  # packgen 的預覽資料夾（bg_*.png、icon_*.png）
ICON_BY_NAME = {'生活手藝': 'skills', '圖鑑': 'dex', '城鎮訂單': 'orders', '煉金爐 · 等價交換': 'alchemy', '煉金值': 'alchemy', '生命祭壇': 'altar', '玩家市集': 'market',
    '感謝': 'thanks', '贈與 mana': 'gift', '委託板': 'board', '我的作品': 'works', '參觀附近作品': 'visit', '收到的留言': 'comments', '導師': 'mentor', '導師邀請': 'mentor',
    '生命之樹': 'tree', '社區工程': 'projects', 'mana 紀錄': 'log', '今天的 mana': 'today', '怎麼得到 mana？': 'help', '關閉': 'close', '返回': 'back', '返回分類': 'back',
    '上一頁': 'prev', '下一頁': 'next', '我的攤位': 'stall', '我的委託': 'mine', '投入物品': 'deposit', '上架主手物品': 'sell', '發布物品委託': 'request',
    '靠自己的雙手': 'hub_self', '和大家一起': 'hub_social', '生生不息': 'hub_life', '市集怎麼用？': 'info', '委託板怎麼用？': 'info', '手藝怎麼算？': 'info', '我的圖鑑': 'dex', ' ': 'blank', '全部捐出': 'tree'}
def styled_icon(slot, size):
    if not PACK: return None
    if slot['name'] == 'player_head': key = 'mana'
    elif slot['name'] == 'paper':
        nm = ''.join(t for t, _ in parse_component(slot.get('displayName'))) if slot.get('displayName') else ''
        key = ICON_BY_NAME.get(nm) or next((v for k, v in ICON_BY_NAME.items() if k and nm.startswith(k)), None)
        if key is None and nm.startswith('你的 mana'): key = 'mana'
        if key is None: return None
    else: return None
    if key == 'blank': return Image.new('RGBA', (size, size), (0, 0, 0, 0))
    p = os.path.join(PACK, 'icon_' + key + '.png')
    return Image.open(p).convert('RGBA').resize((size, size), Image.LANCZOS) if os.path.exists(p) else None

HOVER = {'main': 13, 'skills': 11, 'dex': 2, 'orders': 10, 'alchemy': 6, 'market': 10, 'board': 10, 'projects': 10, 'project': 10, 'altar_home': 10, 'altar_stone': 2}
pages = json.load(open(sys.argv[1]))
for name in sys.argv[3:] or pages.keys():
    render(name, pages[name])
