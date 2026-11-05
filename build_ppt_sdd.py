#!/usr/bin/env python3
"""Campus-buddy SDD deck, 24 slides, ~15 minutes.
Style: cream #FAF9F5, ink #141413, clay #D97757, border #E8E6DC
(solid PPT approximation of CDS 10%-alpha black border).
CDS radius (latest cds-theme.css): card 12px, sub-panel 10px,
button/input 8px, pill 999px, modal 16px. 12px at 96dpi = 0.125in.
ROUNDED_RECTANGLE adjustment = radius_in / min(w, h) -- never the
python-pptx default. Single-closed-border rule: only the outer card
carries a 1pt border; inner elements use fill or hairline dividers only.
Fonts: Noto Serif SC everywhere (a:ea set explicitly); STXingkai only
for the cover title and the closing thank-you display text.
"""
import os
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn
from pptx.oxml import OxmlElement

HOME = os.path.expanduser("~")
BG = os.path.join(HOME, "workspace/campus-buddy/assets/media-generation-bg-centerblank-content.jpg")
FIG51 = os.path.join(HOME, "workspace/campus-buddy/ppt17-work/sdd-figs/image1.png")
FIG71 = os.path.join(HOME, "workspace/campus-buddy/ppt17-work/sdd-figs/image7.png")
OUT = os.path.join(HOME, "workspace/campus-buddy/找个搭子-系统概要设计-15分钟.pptx")

INK = RGBColor(0x14, 0x14, 0x13)
GREY = RGBColor(0x5E, 0x5D, 0x59)
ORANGE = RGBColor(0xD9, 0x77, 0x57)
ORANGE_SOFT = RGBColor(0xFD, 0xF1, 0xEC)
ORANGE_LINE = RGBColor(0xEA, 0xCF, 0xC4)
BORDER = RGBColor(0xE8, 0xE6, 0xDC)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
CREAM = RGBColor(0xFA, 0xF9, 0xF5)
CREAM2 = RGBColor(0xF0, 0xEE, 0xE6)
SERIF = "Noto Serif SC"
CALLIG = "STXingkai"

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]
W, H = 13.333, 7.5


def set_run(run, size, bold=False, color=INK, name=SERIF):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = color
    run.font.name = name
    rPr = run._r.get_or_add_rPr()
    for ea in rPr.findall(qn("a:ea")):
        rPr.remove(ea)
    ea = OxmlElement("a:ea")
    ea.set("typeface", name)
    rPr.append(ea)


def tx(s, x, y, w, h, lines, size=18, bold=False, color=INK, name=SERIF,
       align=PP_ALIGN.LEFT, space_after=6, line_spacing=1.15):
    """lines: str, or list of (text, bold, size, color) tuples / plain strs."""
    box = s.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    if isinstance(lines, str):
        lines = [lines]
    for i, item in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.space_after = Pt(space_after)
        p.line_spacing = line_spacing
        if isinstance(item, tuple):
            parts = [item]
        else:
            parts = [(item, bold, size, color)]
        for text, b, sz, c in parts:
            r = p.add_run()
            r.text = text
            set_run(r, sz, b, c, name)
    return box


def slide():
    s = prs.slides.add_slide(BLANK)
    s.shapes.add_picture(BG, 0, 0, prs.slide_width, prs.slide_height)
    return s


def header(s, kicker, title, sub):
    tx(s, 0.65, 0.32, 11.0, 0.35, kicker, size=13, bold=True, color=ORANGE)
    tx(s, 0.65, 0.62, 12.0, 0.7, title, size=29, bold=True, color=INK)
    tx(s, 0.65, 1.22, 12.0, 0.35, sub, size=14, color=GREY)
    tx(s, 11.35, 0.35, 1.35, 0.35, "找个搭子", size=13, bold=True, color=GREY, align=PP_ALIGN.RIGHT)


def footer(s, n):
    tx(s, 0.65, 7.08, 12.0, 0.3, f"找个搭子 · 系统概要设计    {n} / 24",
       size=11, color=GREY)


def rounded_card(s, x, y, w, h, radius_px=12, fill=WHITE, border=True):
    shp = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                             Inches(x), Inches(y), Inches(w), Inches(h))
    try:
        shp.adjustments[0] = (radius_px / 96.0) / min(w, h)
    except Exception:
        pass
    shp.fill.solid()
    shp.fill.fore_color.rgb = fill
    if border:
        shp.line.color.rgb = BORDER
        shp.line.width = Pt(1)
    else:
        shp.line.fill.background()
    shp.shadow.inherit = False
    return shp


def card(s, x, y, w, h, title, body, title_size=17, body_size=15, radius_px=12):
    """Outer bordered card; inner text has no border (single-border rule).
    body: list of short lines."""
    rounded_card(s, x, y, w, h, radius_px=radius_px)
    # small terracotta accent tick (fill only, not a border)
    tick = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x + 0.28), Inches(y + 0.26),
                              Inches(0.42), Inches(0.055))
    tick.fill.solid(); tick.fill.fore_color.rgb = ORANGE
    tick.line.fill.background(); tick.shadow.inherit = False
    tx(s, x + 0.28, y + 0.42, w - 0.56, 0.45, title, size=title_size, bold=True)
    tx(s, x + 0.28, y + 0.88, w - 0.56, h - 1.05, body, size=body_size,
       color=INK, space_after=4, line_spacing=1.2)


def bullets(s, x, y, w, h, items, size=18, gap=12):
    """items: list of (lead, rest). At most 6 per slide by construction."""
    box = s.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    for i, (lead, rest) in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(gap)
        p.line_spacing = 1.2
        r = p.add_run(); r.text = "▸ " + lead
        set_run(r, size, True, INK)
        if rest:
            r2 = p.add_run(); r2.text = "  " + rest
            set_run(r2, size, False, INK)
    return box


def pill(s, x, y, w, h, text, fill=ORANGE_SOFT, color=INK, size=15, bold=True):
    shp = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                             Inches(x), Inches(y), Inches(w), Inches(h))
    try:
        shp.adjustments[0] = 0.5  # pill: radius 999px -> fully rounded
    except Exception:
        pass
    shp.fill.solid(); shp.fill.fore_color.rgb = fill
    shp.line.fill.background(); shp.shadow.inherit = False
    tf = shp.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
    r = p.add_run(); r.text = text
    set_run(r, size, bold, color)
    return shp


def hairline(s, x, y, w):
    ln = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Pt(1))
    ln.fill.solid(); ln.fill.fore_color.rgb = BORDER
    ln.line.fill.background(); ln.shadow.inherit = False
    return ln


def arrow_right(s, x, y, w=0.42, h=0.28):
    shp = s.shapes.add_shape(MSO_SHAPE.RIGHT_ARROW, Inches(x), Inches(y), Inches(w), Inches(h))
    shp.fill.solid(); shp.fill.fore_color.rgb = ORANGE
    shp.line.fill.background(); shp.shadow.inherit = False
    return shp


def notes(s, text):
    ns = s.notes_slide
    ns.placeholders[1].text = text
    for shape in ns.shapes:
        if shape.has_text_frame:
            for para in shape.text_frame.paragraphs:
                for run in para.runs:
                    set_run(run, 12, False, INK)


def content_slide(n, kicker, title, sub):
    s = slide()
    header(s, kicker, title, sub)
    footer(s, n)
    return s
