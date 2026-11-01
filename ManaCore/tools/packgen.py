#!/usr/bin/env python3
"""
產生 ManaCore 的介面資源包（manacore-pack.zip）。
- 箱子介面背景：每種列數 × 分區色一張，透過自訂字型字元畫在標題位置（蓋住原版箱子外觀）
- 按鈕圖示：深色圓角磁磚＋符號，透過 item_model 元件套在紙上
- 原版按鈕、提示框、選單背景的重新上色（對話框也會套用）
用法：python3 packgen.py <輸出 zip> [預覽資料夾]
"""
import json, os, sys, zipfile, io
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
PREVIEW = sys.argv[2] if len(sys.argv) > 2 else None
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
NS = 'manacore'

BASE = (22, 26, 38)          # 面板底色
BASE2 = (30, 35, 50)
BORDER = (64, 72, 96)
SLOT = (32, 38, 54)
SLOT_B = (48, 55, 76)
SLOT_DIM = (27, 32, 45)
TEXT_STRIP = (44, 50, 68)
ACCENTS = {'self': (104, 214, 128), 'social': (236, 128, 180), 'life': (86, 196, 236), 'alchemy': (178, 118, 240), 'info': (150, 160, 184)}
ACCENT_ORDER = ['self', 'social', 'life', 'alchemy', 'info']

def blend(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

files = {}  # path -> bytes
def put_png(path, img):
    buf = io.BytesIO(); img.save(buf, 'PNG', optimize=True); files[path] = buf.getvalue()
def put_text(path, text):
    files[path] = text.encode('utf-8')

# ---------------- 箱子背景 ----------------
def background(rows, accent):
    # 只蓋住上方的選單區（標題列＋格子），下方的背包區保留原版介面
    W, H = 176, 17 + rows * 18
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, W - 1, H + 6), radius=5, fill=BASE, outline=BORDER)   # 下緣超出畫布 → 底邊是直的，和背包區相接
    d.rounded_rectangle((1, 1, W - 2, H + 5), radius=4, outline=(40, 46, 64))
    d.line((0, H - 1, W - 1, H - 1), fill=BORDER)
    # 標題橫幅
    head = blend(BASE, accent, 0.22)
    d.rounded_rectangle((1, 1, W - 2, 15), radius=4, fill=head)
    d.rectangle((1, 10, W - 2, 15), fill=head)
    d.line((2, 16, W - 3, 16), fill=blend(BASE, accent, 0.7))
    d.line((2, 17, W - 3, 17), fill=blend(BASE, accent, 0.25))
    # 標題列（第 0 列）淡一點，內容列正常
    for r in range(rows):
        for c in range(9):
            x, y = 8 + c * 18, 18 + r * 18
            fill = SLOT_DIM if r == 0 else SLOT
            d.rounded_rectangle((x - 1, y - 1, x + 16, y + 16), radius=2, fill=fill, outline=SLOT_B if r else blend(SLOT_B, BASE, 0.4))
    return img

# ---------------- 圖示磁磚 ----------------
ICONS = {
    # key: (符號, 顏色)
    'hub_self': ('⛏', ACCENTS['self']), 'hub_social': ('♥', ACCENTS['social']), 'hub_trade': ('⚖', ACCENTS['life']),
    'hub_life': ('TREE', (120, 220, 140)), 'hub_records': ('☰', (220, 224, 232)), 'help': ('?', (250, 210, 90)),
    'skills': ('⚒', ACCENTS['self']), 'dex': ('❖', (90, 210, 220)), 'orders': ('⚑', (250, 190, 70)),
    'alchemy': ('⚗', ACCENTS['alchemy']), 'altar': ('◈', ACCENTS['life']), 'market': ('⚖', (90, 220, 170)),
    'board': ('✉', (120, 190, 240)), 'request': ('✎', (120, 190, 240)), 'works': ('▣', (250, 190, 70)),
    'visit': ('◎', (90, 210, 220)), 'comments': ('✉', (220, 224, 232)), 'mentor': ('★', (250, 210, 90)),
    'thanks': ('♥', (240, 90, 110)), 'gift': ('◆', (86, 196, 236)), 'tree': ('TREE', (120, 220, 140)),
    'projects': ('⌂', (250, 190, 70)), 'log': ('☰', (220, 224, 232)), 'today': ('◷', (250, 210, 90)),
    'back': ('↩', (220, 224, 232)), 'close': ('✕', (240, 90, 110)), 'prev': ('◀', (220, 224, 232)), 'next': ('▶', (220, 224, 232)),
    'stall': ('▤', (90, 220, 170)), 'mine': ('▦', (250, 190, 70)), 'deposit': ('⬇', ACCENTS['alchemy']), 'sell': ('⬆', (90, 220, 170)),
    'empty': ('·', (90, 96, 120)), 'info': ('i', (150, 160, 184)),
}

def draw_tree(d, color):
    # 簡單的樹形：三層樹冠＋樹幹（不用任何花朵符號）
    dark = tuple(int(c * 0.65) for c in color)
    d.polygon([(16, 5), (24, 14), (8, 14)], fill=color, outline=dark)
    d.polygon([(16, 9), (26, 20), (6, 20)], fill=color, outline=dark)
    d.rectangle((14, 20, 17, 26), fill=(150, 100, 60), outline=(90, 60, 35))

def tile(symbol, color, glow=False):
    S = 32
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((1, 1, S - 2, S - 2), radius=7, fill=(36, 42, 60), outline=(74, 84, 112))
    d.rounded_rectangle((2, 2, S - 3, S - 3), radius=6, outline=(50, 58, 80))
    # 上方微光
    for y in range(3, 12):
        a = int(26 * (1 - (y - 3) / 9))
        d.line((4, y, S - 5, y), fill=(255, 255, 255, a))
    if symbol == 'TREE':
        draw_tree(d, color)
        return img
    font = ImageFont.truetype(FONT, 20)
    bbox = font.getbbox(symbol)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x, y = (S - w) // 2 - bbox[0], (S - h) // 2 - bbox[1]
    d.text((x + 1, y + 1), symbol, font=font, fill=(0, 0, 0, 160))
    d.text((x, y), symbol, font=font, fill=color + (255,))
    return img

def gem():
    S = 32
    img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx, cy = 16, 16
    pts = [(cx, 3), (28, 13), (cx, 29), (4, 13)]
    d.polygon(pts, fill=(40, 120, 230), outline=(16, 52, 120))
    d.polygon([(cx, 3), (28, 13), (cx, 13)], fill=(120, 190, 255))
    d.polygon([(cx, 3), (4, 13), (cx, 13)], fill=(80, 160, 250))
    d.polygon([(cx, 13), (28, 13), (cx, 29)], fill=(30, 100, 210))
    d.polygon([(cx, 13), (4, 13), (cx, 29)], fill=(22, 80, 180))
    d.polygon([(cx, 7), (22, 13), (cx, 19), (10, 13)], fill=(200, 235, 255))
    return img

# ---------------- 原版元件重新上色 ----------------
def button(state):
    img = Image.new('RGBA', (200, 20), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    fill = {'normal': (40, 46, 66), 'hover': (58, 92, 128), 'disabled': (28, 31, 42)}[state]
    outline = {'normal': (84, 94, 124), 'hover': (120, 200, 240), 'disabled': (48, 52, 66)}[state]
    d.rounded_rectangle((0, 0, 199, 19), radius=4, fill=fill, outline=outline)
    for y in range(1, 8):
        d.line((2, y, 197, y), fill=(255, 255, 255, int(22 * (1 - y / 8))))
    return img

def tooltip_bg():
    img = Image.new('RGBA', (100, 100), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, 99, 99), radius=6, fill=(18, 20, 30, 242))
    return img

def tooltip_frame():
    img = Image.new('RGBA', (100, 100), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, 99, 99), radius=6, outline=(86, 196, 236, 200))
    d.rounded_rectangle((1, 1, 98, 98), radius=5, outline=(86, 196, 236, 70))
    return img

def list_bg(dark):
    img = Image.new('RGBA', (16, 16), (16, 18, 26, 255) if dark else (24, 27, 38, 255))
    d = ImageDraw.Draw(img)
    for i in range(0, 16, 4):
        d.point((i, 0), fill=(28, 32, 44, 255))
    return img

# ---------------- 組裝 ----------------
providers = []
# 負向空白： = -1,  = -2 …  = -128
advances = {chr(0xE100 + i): -(1 << i) for i in range(8)}
advances[chr(0xE10F)] = 1
providers.append({'type': 'space', 'advances': advances})

bg_index = {}
for ri, rows in enumerate(range(3, 7)):
    for ai, accent in enumerate(ACCENT_ORDER):
        ch = chr(0xE200 + ri * len(ACCENT_ORDER) + ai)
        img = background(rows, ACCENTS[accent])
        name = f'bg_{rows}_{accent}'
        put_png(f'assets/{NS}/textures/font/{name}.png', img)
        providers.append({'type': 'bitmap', 'file': f'{NS}:font/{name}.png', 'ascent': 13, 'height': img.height, 'chars': [ch]})
        bg_index[name] = ch
        if PREVIEW: img.save(os.path.join(PREVIEW, name + '.png'))
put_text(f'assets/{NS}/font/gui.json', json.dumps({'providers': providers}, ensure_ascii=False, indent=1))

for key, (sym, col) in ICONS.items():
    img = tile(sym, col)
    put_png(f'assets/{NS}/textures/item/{key}.png', img)
    if PREVIEW: img.save(os.path.join(PREVIEW, 'icon_' + key + '.png'))
put_png(f'assets/{NS}/textures/item/mana.png', gem())
if PREVIEW: gem().save(os.path.join(PREVIEW, 'icon_mana.png'))
put_png(f'assets/{NS}/textures/item/blank.png', Image.new('RGBA', (16, 16), (0, 0, 0, 0)))
for key in list(ICONS) + ['mana', 'blank']:
    put_text(f'assets/{NS}/models/item/{key}.json', json.dumps({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{NS}:item/{key}'}}))
    put_text(f'assets/{NS}/items/{key}.json', json.dumps({'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{key}'}}))

nine = json.dumps({'gui': {'scaling': {'type': 'nine_slice', 'width': 200, 'height': 20, 'border': 3}}})
for name, st in (('button', 'normal'), ('button_highlighted', 'hover'), ('button_disabled', 'disabled')):
    put_png(f'assets/minecraft/textures/gui/sprites/widget/{name}.png', button(st))
    put_text(f'assets/minecraft/textures/gui/sprites/widget/{name}.png.mcmeta', nine)
tip = json.dumps({'gui': {'scaling': {'type': 'nine_slice', 'width': 100, 'height': 100, 'border': 9}}})
put_png('assets/minecraft/textures/gui/sprites/tooltip/background.png', tooltip_bg()); put_text('assets/minecraft/textures/gui/sprites/tooltip/background.png.mcmeta', tip)
put_png('assets/minecraft/textures/gui/sprites/tooltip/frame.png', tooltip_frame()); put_text('assets/minecraft/textures/gui/sprites/tooltip/frame.png.mcmeta', tip)
put_png('assets/minecraft/textures/gui/menu_list_background.png', list_bg(True))
put_png('assets/minecraft/textures/gui/inworld_menu_list_background.png', list_bg(False))
put_png('assets/minecraft/textures/gui/menu_background.png', list_bg(True))
put_png('assets/minecraft/textures/gui/inworld_menu_background.png', list_bg(False))

put_text('pack.mcmeta', json.dumps({'pack': {'pack_format': 97, 'min_format': [97, 0], 'max_format': [99, 99], 'description': 'ManaCore 介面'}}, ensure_ascii=False))
put_text('manacore-pack-index.json', json.dumps({'backgrounds': bg_index, 'icons': list(ICONS) + ['mana', 'blank']}, ensure_ascii=False))

os.makedirs(os.path.dirname(os.path.abspath(OUT)), exist_ok=True)
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
    for path in sorted(files):
        zi = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, files[path])
print('pack written:', OUT, len(files), 'files,', os.path.getsize(OUT), 'bytes')
print('bg chars:', {k: hex(ord(v)) for k, v in list(bg_index.items())[:3]}, '...')
