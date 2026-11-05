#!/usr/bin/env python3
"""Build the 4-5 minute first-presentation deck for Campus Buddy.
Output is a standard .pptx that opens in Windows PowerPoint / WPS.
Speaker notes total ~1100 Chinese characters, ~30 seconds per slide.
"""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
import os

NAVY = RGBColor(0x1B, 0x2A, 0x4E)
TEAL = RGBColor(0x0E, 0xA5, 0xA4)
LIGHT = RGBColor(0xEE, 0xF3, 0xF7)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
DARK = RGBColor(0x22, 0x22, 0x22)
GREY = RGBColor(0x5A, 0x6B, 0x7D)
ORANGE = RGBColor(0xE8, 0x6B, 0x2E)

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]

def bg(slide, color=WHITE):
    shp = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, prs.slide_width, prs.slide_height)
    shp.fill.solid(); shp.fill.fore_color.rgb = color; shp.line.fill.background()
    return shp

def box(slide, x, y, w, h, fill, line=None):
    shp = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    shp.fill.solid(); shp.fill.fore_color.rgb = fill
    if line is None:
        shp.line.fill.background()
    else:
        shp.line.color.rgb = line; shp.line.width = Pt(1)
    shp.shadow.inherit = False
    return shp

def text(slide, x, y, w, h, s, size=20, bold=False, color=DARK, align=PP_ALIGN.LEFT):
    tx = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tx.text_frame; tf.word_wrap = True
    lines = s.split("\n")
    for i, line in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.text = line; p.font.size = Pt(size); p.font.bold = bold
        p.font.color.rgb = color; p.alignment = align
        p.font.name = "Microsoft YaHei"; p.space_after = Pt(4)
    return tx

def header(slide, num, title, sub=""):
    bg(slide)
    bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0), Inches(0), Inches(13.333), Inches(1.05))
    bar.fill.solid(); bar.fill.fore_color.rgb = NAVY; bar.line.fill.background()
    text(slide, 0.5, 0.22, 1.0, 0.6, f"{num:02d}", size=28, bold=True, color=TEAL)
    text(slide, 1.4, 0.2, 9.5, 0.6, title, size=28, bold=True, color=WHITE)
    if sub:
        text(slide, 1.4, 0.62, 9.5, 0.4, sub, size=13, color=RGBColor(0xC7, 0xD2, 0xE0))
    text(slide, 11.2, 0.3, 1.6, 0.5, "找个搭子", size=14, bold=True, color=WHITE, align=PP_ALIGN.RIGHT)

def bullets(slide, x, y, w, h, items, size=20):
    tx = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tx.text_frame; tf.word_wrap = True
    for i, (t, d) in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(14)
        r = p.add_run(); r.text = "▸ " + t; r.font.size = Pt(size); r.font.bold = True
        r.font.color.rgb = NAVY; r.font.name = "Microsoft YaHei"
        if d:
            r2 = p.add_run(); r2.text = "  " + d; r2.font.size = Pt(size)
            r2.font.color.rgb = DARK; r2.font.name = "Microsoft YaHei"
    return tx

def notes(slide, s):
    slide.notes_slide.placeholders[1].text = s

def card(slide, x, y, w, h, title, body, accent=TEAL):
    box(slide, x, y, w, h, LIGHT)
    bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(0.08))
    bar.fill.solid(); bar.fill.fore_color.rgb = accent; bar.line.fill.background()
    text(slide, x + 0.25, y + 0.25, w - 0.5, 0.6, title, size=19, bold=True, color=NAVY)
    text(slide, x + 0.25, y + 0.9, w - 0.5, h - 1.1, body, size=16, color=DARK)

# 1 Title
s = prs.slides.add_slide(BLANK); bg(s, NAVY)
text(s, 0.7, 0.7, 3.2, 0.5, "数据库课程设计 · 第一次汇报", size=16, bold=True, color=TEAL)
text(s, 0.7, 1.5, 11.5, 1.4, "找个搭子", size=66, bold=True, color=WHITE)
text(s, 0.7, 2.9, 11.5, 0.8, "校园活动组队与候补平台", size=32, color=WHITE)
text(s, 0.7, 4.0, 11.5, 0.6, "满员不是结束，候补也要排得明明白白", size=20, color=RGBColor(0xC7, 0xD2, 0xE0))
text(s, 0.7, 5.6, 11.5, 0.6, "13 小组　·　陈子逸　张梓轩　吉浩铭　唐裕翔", size=18, color=WHITE)
text(s, 0.7, 6.4, 11.5, 0.5, "汇报约 4–5 分钟：讲背景、技术路线和要达成的目标", size=14, color=RGBColor(0xC7, 0xD2, 0xE0))
notes(s, "约30秒：大家好，我们是13小组，选题是找个搭子，校园活动组队与候补平台。今天第一次汇报只讲三件事：为什么做这个题，核心数据库难题是什么，以及我们用什么技术路线把它做出来。")

# 2 Topic source
s = prs.slides.add_slide(BLANK); header(s, 2, "选题从哪来", "对照选题参考，不是凭空想的题目")
bullets(s, 0.7, 1.5, 7.4, 5.2, [
    ("参考题目：", "社团活动管理与报名系统"),
    ("我们的落点：", "校园活动组队平台“找个搭子”"),
    ("已有基础：", "小组需求规格说明书，11 个模块、41 项功能"),
    ("本次只讲 P0：", "发布、报名、满员候补、退出递补、限时确认"),
])
card(s, 8.5, 1.5, 4.1, 4.6, "一句话定位",
     "不是再做一个活动列表。\n\n重点是满员之后怎么办：\n谁排队、谁先补、多久不确认就顺延。\n\n这正是数据库要管的事。", accent=ORANGE)
notes(s, "约30秒：选题来自参考题目里的社团活动管理与报名系统，我们把它落到找个搭子这个已有需求文档的项目上。第一次汇报先聚焦核心流程，P1通知和P2管理后台、评价往后放。")

# 3 Pain -> goal
s = prs.slides.add_slide(BLANK); header(s, 3, "痛点 → 目标", "找不到搭子，满员就只能放弃")
card(s, 0.7, 1.5, 3.9, 3.2, "痛点 1 · 找不到人",
     "想打球、想自习、想拼桌游，\n身边人不一定有空，\n活动常常因为凑不齐人而取消。")
card(s, 4.7, 1.5, 3.9, 3.2, "痛点 2 · 满员就乱",
     "名额满了只能在群里问，\n谁先来、谁替补全凭口头，\n退出一个名额也补不及时。")
card(s, 8.7, 1.5, 3.9, 3.2, "目标",
     "发布活动 → 报名 → 满员自动候补\n→ 有人退出自动递补\n→ 限时确认，超时顺延。\n\n每个人都看得到自己的状态。", accent=ORANGE)
text(s, 0.7, 5.3, 12, 1.0, "衡量标准：人数不能算错，排队不能插队，邀请不能一女二嫁。",
     size=19, bold=True, color=NAVY)
notes(s, "约30秒：痛点有两个，找不到人和满员混乱。我们的目标是一条完整链路：发布、报名、候补、递补、限时确认。最后那句就是验收标准，人数、排队、邀请三样都不能错。")

# 4 DB difficulty
s = prs.slides.add_slide(BLANK); header(s, 4, "这为什么是数据库课设", "页面不难，难的是并发下的数据一致")
card(s, 0.7, 1.5, 6.0, 2.3, "有效占用 ≤ 上限",
     "有效占用 = 正式人数 + 未过期待确认邀请数\n两者之和永远不能超过活动人数上限。")
card(s, 6.9, 1.5, 5.7, 2.3, "防重复 · 防超员",
     "同一活动同一用户只有一条当前参与记录；\n多人同时报名，正式人数也不能被冲破。")
card(s, 0.7, 4.1, 6.0, 2.3, "邀请只能有一种终态",
     "接受、拒绝、超时、失效互相竞争，\n先成功提交的一方生效，后到的只返回当前结果。")
card(s, 6.9, 4.1, 5.7, 2.3, "候补排序要稳定",
     "按入队时间排，时间相同按记录编号排；\n退出重进要重新排队，不保留原排名。", accent=ORANGE)
notes(s, "约35秒：这是我们选题最像数据库课设的地方。四个规则都不能只靠前端按钮挡，必须落到事务、唯一约束和条件更新里。后面会讲表怎么设计来保证这四条。")

# 5 Data model
s = prs.slides.add_slide(BLANK); header(s, 5, "数据模型：6 张表", "对应需求文档第 4 章，关系和约束先定死")
tables = [
    ("user 用户", "id / account 唯一\nnickname / role / status"),
    ("activity 活动", "id / organizer_id → user\ncapacity / deadline\nstart / end / status"),
    ("participation 参与", "activity_id + user_id 唯一\nFORMAL / WAITING / INVITED\nqueued_at / joined_at"),
    ("invitation 邀请", "participation_id → 参与\nPENDING / ACCEPTED…\nissued_at / expires_at"),
    ("notification 通知", "user_id + event_key 唯一\ntype / content / read_at"),
    ("feedback 评价 P2", "participation_id 唯一\nscore 1–5 / content ≤500 字"),
]
for i, (t, b) in enumerate(tables):
    x = 0.7 + (i % 3) * 4.15; y = 1.5 + (i // 3) * 2.55
    card(s, x, y, 3.85, 2.25, t, b)
text(s, 0.7, 6.85, 12, 0.4, "关系：用户 1–N 活动；用户 N–M 活动（经参与记录）；参与 1–N 邀请历史，但同时只能有一个待确认邀请。",
     size=15, color=GREY)
notes(s, "约35秒：六张表，用户、活动、参与记录、邀请、通知、评价。关键约束念一遍：账号唯一，活动加用户唯一，通知按用户加事件键去重，邀请表上对状态加过期时间建组合索引，方便定时任务扫描。")

# 6 Tech route
s = prs.slides.add_slide(BLANK); header(s, 6, "技术路线", "主流方案，Windows 上能直接演示")
box(s, 0.7, 1.5, 3.6, 1.6, LIGHT); text(s, 0.95, 1.7, 3.1, 1.2, "Vue 3 前端\n活动大厅 / 我的活动 / 通知", size=18, bold=True, color=NAVY)
text(s, 4.5, 1.9, 0.6, 0.6, "→", size=28, bold=True, color=TEAL, align=PP_ALIGN.CENTER)
box(s, 5.3, 1.5, 3.6, 1.6, LIGHT); text(s, 5.55, 1.7, 3.1, 1.2, "Spring Boot 3 后端\nREST 接口 + 事务 + 定时任务", size=18, bold=True, color=NAVY)
text(s, 9.1, 1.9, 0.6, 0.6, "→", size=28, bold=True, color=TEAL, align=PP_ALIGN.CENTER)
box(s, 9.9, 1.5, 2.7, 1.6, LIGHT); text(s, 10.15, 1.7, 2.2, 1.2, "PostgreSQL\n事务 / 唯一约束 / 行锁", size=18, bold=True, color=NAVY)
bullets(s, 0.7, 3.6, 12, 3.0, [
    ("国内可直连：", "Maven 走阿里云镜像，npm 走 npmmirror；前端打包进后端，演示时不依赖国外 CDN。"),
    ("Windows 演示：", "装好 JDK 17 和 PostgreSQL 后，双击 run.bat 启动，浏览器打开就能操作。"),
    ("配置分离：", "端口、数据库连接、确认窗口、扫描周期都放配置文件，不写死机器路径和密码。"),
], size=18)
notes(s, "约35秒：路线是 Vue 3 加 Spring Boot 3 加 PostgreSQL，都是主流方案。补充两点：一是依赖源全部换成国内可直连的镜像，二是演示版把前端打包进后端，Windows 上只要有 JDK 17 和 PostgreSQL 就能跑。")

# 7 Demo script
s = prs.slides.add_slide(BLANK); header(s, 7, "演示剧本：一个 4 人活动", "第二次汇报就按这条线现场演示")
steps = [
    ("1 发布", "羽毛球，上限 4 人"),
    ("2 报满", "4 人正式，第 5 人自动候补第 1"),
    ("3 退出", "1 名正式成员退出，空出 1 个名额"),
    ("4 递补", "系统给候补第 1 发限时邀请，预留名额"),
    ("5 确认", "接受 → 转正式；超时或拒绝 → 顺延下一人"),
]
for i, (t, b) in enumerate(steps):
    x = 0.7 + i * 2.5
    card(s, x, 1.6, 2.3, 3.0, t, b)
    if i < 4:
        text(s, x + 2.3, 2.8, 0.2, 0.5, "›", size=28, bold=True, color=TEAL, align=PP_ALIGN.CENTER)
text(s, 0.7, 5.1, 12, 1.2, "演示时重点看三处数字：正式人数、预留邀请数、候补排名，三个数加起来永远对得上。",
     size=19, bold=True, color=NAVY)
notes(s, "约30秒：演示剧本用一个上限四人的羽毛球活动走完整流程，重点不是页面好不好看，而是正式人数、预留数、候补排名这三个数字在每一步都对得上，超时顺延那一步也会现场等它发生。")

# 8 Acceptance
s = prs.slides.add_slide(BLANK); header(s, 8, "验收标准：先说好怎么算通过", "来自需求文档第 5 章，不是临场发挥")
bullets(s, 0.7, 1.5, 12, 5.0, [
    ("20 人同时报名，上限 4 人：", "最终恰好 4 名正式，其余为候补。"),
    ("同一账号并发提交多次：", "只产生一条有效参与记录。"),
    ("确认与超时同时到达：", "同一邀请只出现一种终态，不重复分配名额。"),
    ("超时后：", "原邀请失效并顺延，过期确认接口直接拒绝，不等扫描任务。"),
    ("重启后：", "已提交的参与记录不丢失，定时任务补扫未处理邀请。"),
], size=20)
notes(s, "约30秒：验收标准提前定好，写进需求文档第五章。并发、重复提交、确认和超时打架、超时顺延、重启恢复，这五条都通过，才算核心流程跑通，而不是某一次演示碰巧成功。")

# 9 Plan
s = prs.slides.add_slide(BLANK); header(s, 9, "下一步计划", "从第一次汇报到第二次汇报")
bullets(s, 0.7, 1.5, 12, 5.0, [
    ("第 1 步：", "建表 + 核心接口：报名、退出、递补确认、超时扫描。"),
    ("第 2 步：", "前端页面：活动大厅、详情、我的活动、通知。"),
    ("第 3 步：", "按上一页标准做并发、超时、重启三组测试，留测试记录。"),
    ("第二次汇报：", "6–7 分钟，现场演示为主，事先不只放录像。"),
    ("期末：", "提交纸质课程设计报告，附表结构、事务设计和测试结果。"),
], size=20)
notes(s, "约25秒：下一步分三步，接口、页面、测试，最后第二次汇报用六到七分钟现场演示，期末再交纸质报告。时间上先保证核心流程和测试记录，P1和P2功能有余力再补。")

# 10 Thanks
s = prs.slides.add_slide(BLANK); bg(s, NAVY)
text(s, 0.7, 1.8, 12, 1.2, "谢谢，请提问", size=54, bold=True, color=WHITE)
text(s, 0.7, 3.3, 12, 0.8, "一句话总结：满员不可怕，可怕的是名额算不清。", size=22, color=RGBColor(0xC7, 0xD2, 0xE0))
text(s, 0.7, 4.4, 12, 0.6, "我们的办法，就是用事务和约束把这笔账算清楚。", size=22, color=RGBColor(0xC7, 0xD2, 0xE0))
text(s, 0.7, 5.8, 12, 0.6, "13 小组　·　找个搭子　·　Vue 3 + Spring Boot 3 + PostgreSQL", size=16, color=TEAL)
notes(s, "约15秒：谢谢大家。总结一句话，满员不可怕，可怕的是名额算不清，我们的办法就是用事务和约束把这笔账算清楚。请老师和同学提问。")

out = os.path.expanduser("~/workspace/campus-buddy/CampusBuddy-第一次汇报.pptx")
prs.save(out)
print(out)
