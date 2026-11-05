#!/usr/bin/env python3
"""Hand-author ppt-master Quick Generate SVGs for the Campus Buddy deck.
Canvas: ppt169, viewBox 0 0 1280 720, lang zh-CN, Windows target font Microsoft YaHei.
Flat pages: one root data-pptx-page-role, every root <g> carries data-pptx-bounds,
no root-group overlap, no stylesheets/classes/scripts.
"""
import os, html

OUT = os.path.expanduser("~/workspace/campus-buddy/ppt-work/campus-buddy_20261029/svg_output")
os.makedirs(OUT, exist_ok=True)

NAVY = "#1B2A4E"; TEAL = "#0EA5A4"; LIGHT = "#EEF3F7"; WHITE = "#FFFFFF"
DARK = "#1F2937"; GREY = "#5A6B7D"; ORANGE = "#E86B2E"; PALE = "#C7D2E0"

def esc(s):
    return html.escape(s, quote=False)

def text_block(x, y, lines, size=22, weight="normal", fill=DARK, dy=None):
    """One <text> per paragraph; later lines are positioned tspans."""
    if dy is None:
        dy = int(size * 1.55)
    parts = []
    for i, line in enumerate(lines):
        if i == 0:
            parts.append(esc(line))
        else:
            parts.append(f'<tspan x="{x}" dy="{dy}">{esc(line)}</tspan>')
    return (f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" '
            f'fill="{fill}">{"".join(parts)}</text>')

def group(gid, bounds, inner):
    x, y, w, h = bounds
    return f'<g id="{gid}" data-pptx-bounds="{x} {y} {w} {h}">\n{inner}\n</g>'

def rect(x, y, w, h, fill, rx=0):
    r = f' rx="{rx}"' if rx else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}"{r} fill="{fill}"/>'

def header(num, title, sub):
    inner = []
    inner.append(rect(60, 52, 8, 72, TEAL))
    inner.append(text_block(88, 82, [title], size=40, weight="bold", fill=NAVY))
    if sub:
        inner.append(text_block(88, 118, [sub], size=20, fill=GREY))
    return group("header", (60, 36, 1160, 104), "\n".join(inner))

def footer(num, total=10):
    inner = text_block(60, 686, [f"找个搭子 · 数据库课程设计第一次汇报    {num} / {total}"],
                       size=16, fill=GREY)
    return group("footer", (60, 660, 1160, 36), inner)

def page(fname, role, bg, groups):
    body = "\n".join(groups)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 720" '
           f'lang="zh-CN" font-family="Microsoft YaHei" data-pptx-page-role="{role}">\n'
           f'<rect id="bg" data-pptx-role="background" x="0" y="0" width="1280" height="720" fill="{bg}"/>\n'
           f'{body}\n</svg>\n')
    with open(os.path.join(OUT, fname), "w", encoding="utf-8") as f:
        f.write(svg)

def card(gid, x, y, w, h, title, lines, accent=TEAL, tsize=22, bsize=20):
    inner = [rect(x, y, w, h, LIGHT, rx=16), rect(x, y, w, 8, accent),
             text_block(x + 24, y + 48, [title], size=tsize, weight="bold", fill=NAVY)]
    inner.append(text_block(x + 24, y + 92, lines, size=bsize, fill=DARK, dy=32))
    return group(gid, (x, y, w, h), "\n".join(inner))

def bullets(gid, x, y, w, h, items, size=22):
    lines = []
    for t, d in items:
        lines.append(f"▸ {t}  {d}")
    inner = text_block(x, y + 32, lines, size=size, fill=DARK, dy=52)
    return group(gid, (x, y, w, h), inner)

# 01 cover
page("01_cover.svg", "cover", NAVY, [
    group("cover-kicker", (80, 90, 900, 40),
          text_block(80, 118, ["数据库课程设计 · 第一次汇报"], size=20, weight="bold", fill=TEAL)),
    group("cover-title", (80, 170, 1120, 220),
          text_block(80, 240, ["找个搭子"], size=88, weight="bold", fill=WHITE, dy=100) + "\n" +
          text_block(80, 350, ["校园活动组队与候补平台"], size=36, fill=WHITE)),
    group("cover-tagline", (80, 420, 1120, 60),
          text_block(80, 452, ["满员不是结束，候补也要排得明明白白"], size=24, fill=PALE)),
    group("cover-team", (80, 540, 1120, 80),
          text_block(80, 572, ["13 小组 · 陈子逸 张梓轩 吉浩铭 唐裕翔"], size=22, fill=WHITE, dy=40) + "\n" +
          text_block(80, 612, ["汇报约 4–5 分钟：背景、技术路线、要达成的目标"], size=18, fill=PALE)),
])

# 02 topic source
page("02_topic.svg", "content", WHITE, [
    header("02", "选题从哪来", "对照选题参考，不是凭空想的题目"),
    bullets("topic-list", 60, 170, 700, 360, [
        ("参考题目：", "社团活动管理与报名系统"),
        ("我们的落点：", "校园活动组队平台“找个搭子”"),
        ("已有基础：", "小组需求文档，11 个模块、41 项功能"),
        ("本次只讲 P0：", "发布、报名、满员候补、退出递补"),
    ]),
    card("topic-focus", 800, 170, 400, 360, "一句话定位",
         ["不是再做一个活动列表。", "重点是满员之后怎么办：", "谁排队、谁先补、", "多久不确认就顺延。", "这正是数据库要管的事。"],
         accent=ORANGE, bsize=20),
    footer(2),
])

# 03 pain -> goal
page("03_goal.svg", "content", WHITE, [
    header("03", "痛点 → 目标", "找不到搭子，满员就只能放弃"),
    card("pain-1", 60, 170, 360, 280, "痛点 1 · 找不到人",
         ["想打球、想自习、想拼桌游，", "身边人不一定有空，", "活动常常凑不齐人而取消。"]),
    card("pain-2", 450, 170, 360, 280, "痛点 2 · 满员就乱",
         ["名额满了只能在群里问，", "谁先来、谁替补全凭口头，", "退出一个名额也补不及时。"]),
    card("goal", 840, 170, 360, 280, "目标",
         ["发布活动 → 报名", "→ 满员自动候补", "→ 有人退出自动递补", "→ 限时确认，超时顺延"], accent=ORANGE),
    group("goal-bar", (60, 500, 1160, 80),
          text_block(60, 540, ["衡量标准：人数不能算错，排队不能插队，邀请不能一女二嫁。"],
                     size=24, weight="bold", fill=NAVY)),
    footer(3),
])

# 04 DB difficulty
page("04_db.svg", "content", WHITE, [
    header("04", "这为什么是数据库课设", "页面不难，难的是并发下的数据一致"),
    card("rule-1", 60, 170, 565, 200, "有效占用 ≤ 上限",
         ["有效占用 = 正式人数", "+ 未过期待确认邀请数", "两者之和永远不能超过上限。"]),
    card("rule-2", 655, 170, 565, 200, "防重复 · 防超员",
         ["同一活动同一用户只有一条", "当前参与记录；", "多人同时报名也不能冲破上限。"]),
    card("rule-3", 60, 400, 565, 200, "邀请只能有一种终态",
         ["接受、拒绝、超时、失效互相竞争，", "先成功提交的一方生效，", "后到的只返回当前结果。"]),
    card("rule-4", 655, 400, 565, 200, "候补排序要稳定",
         ["按入队时间排，时间相同按记录编号排；", "退出重进要重新排队，", "不保留原排名。"], accent=ORANGE),
    footer(4),
])

# 05 data model
page("05_model.svg", "content", WHITE, [
    header("05", "数据模型：6 张表", "对应需求文档第 4 章，关系和约束先定死"),
    card("t-user", 60, 170, 360, 200, "user 用户",
         ["id / account 唯一", "nickname / role / status"], bsize=19),
    card("t-activity", 450, 170, 360, 200, "activity 活动",
         ["organizer_id → user", "capacity / deadline / start / end"], bsize=19),
    card("t-part", 840, 170, 360, 200, "participation 参与",
         ["activity_id + user_id 唯一", "FORMAL / WAITING / INVITED"], bsize=19),
    card("t-inv", 60, 400, 360, 200, "invitation 邀请",
         ["participation_id → 参与", "PENDING / ACCEPTED / 超时等"], bsize=19),
    card("t-notif", 450, 400, 360, 200, "notification 通知",
         ["user_id + event_key 唯一", "type / content / read_at"], bsize=19),
    card("t-feed", 840, 400, 360, 200, "feedback 评价 P2",
         ["participation_id 唯一", "score 1–5 / content ≤ 500 字"], bsize=19, accent=ORANGE),
    footer(5),
])

# 06 tech route
page("06_stack.svg", "content", WHITE, [
    header("06", "技术路线", "主流方案，Windows 上能直接演示"),
    card("stack-fe", 60, 170, 360, 180, "Vue 3 前端",
         ["活动大厅 / 我的活动 / 通知"], bsize=20),
    card("stack-be", 450, 170, 360, 180, "Spring Boot 3 后端",
         ["REST 接口 + 事务 + 定时任务"], bsize=20),
    card("stack-db", 840, 170, 360, 180, "PostgreSQL",
         ["事务 / 唯一约束 / 行锁"], bsize=20, accent=ORANGE),
    bullets("stack-notes", 60, 390, 1160, 240, [
        ("国内可直连：", "Maven 走阿里云镜像，npm 走 npmmirror；前端打包进后端，不依赖国外 CDN。"),
        ("Windows 演示：", "装好 JDK 17 和 PostgreSQL 后，双击 run.bat 启动，浏览器打开就能操作。"),
        ("配置分离：", "端口、数据库连接、确认窗口、扫描周期都放配置文件，不写死路径和密码。"),
    ], size=20),
    footer(6),
])

# 07 demo script
steps = [
    ("1 发布", ["羽毛球，", "上限 4 人"]),
    ("2 报满", ["4 人正式，", "第 5 人候补第 1"]),
    ("3 退出", ["1 名正式成员", "退出，空出名额"]),
    ("4 递补", ["给候补第 1 发", "限时邀请，预留名额"]),
    ("5 确认", ["接受 → 转正式；", "超时或拒绝 → 顺延"]),
]
groups = [header("07", "演示剧本：一个 4 人活动", "第二次汇报就按这条线现场演示")]
for i, (t, lines) in enumerate(steps):
    x = 60 + i * 231
    groups.append(card(f"step-{i+1}", x, 190, 216, 260, t, lines, bsize=18,
                       accent=ORANGE if i == 4 else TEAL))
groups.append(group("demo-bar", (60, 510, 1160, 80),
    text_block(60, 550, ["演示时重点看三处数字：正式人数、预留邀请数、候补排名，三个数加起来永远对得上。"],
               size=22, weight="bold", fill=NAVY)))
groups.append(footer(7))
page("07_demo.svg", "content", WHITE, groups)

# 08 acceptance
page("08_accept.svg", "content", WHITE, [
    header("08", "验收标准：先说好怎么算通过", "来自需求文档第 5 章，不是临场发挥"),
    bullets("accept-list", 60, 170, 1160, 440, [
        ("20 人同时报名，上限 4 人：", "最终恰好 4 名正式，其余为候补。"),
        ("同一账号并发提交多次：", "只产生一条有效参与记录。"),
        ("确认与超时同时到达：", "同一邀请只出现一种终态，不重复分配名额。"),
        ("超时后：", "原邀请失效并顺延，过期确认接口直接拒绝，不等扫描任务。"),
        ("重启后：", "已提交的参与记录不丢失，定时任务补扫未处理邀请。"),
    ]),
    footer(8),
])

# 09 plan
page("09_plan.svg", "content", WHITE, [
    header("09", "下一步计划", "从第一次汇报到第二次汇报"),
    bullets("plan-list", 60, 170, 1160, 440, [
        ("第 1 步：", "建表 + 核心接口：报名、退出、递补确认、超时扫描。"),
        ("第 2 步：", "前端页面：活动大厅、详情、我的活动、通知。"),
        ("第 3 步：", "按上一页标准做并发、超时、重启三组测试，留测试记录。"),
        ("第二次汇报：", "6–7 分钟，现场演示为主，事先不只放录像。"),
        ("期末：", "提交纸质课程设计报告，附表结构、事务设计和测试结果。"),
    ]),
    footer(9),
])

# 10 closing
page("10_end.svg", "ending", NAVY, [
    group("end-title", (80, 200, 1120, 120),
          text_block(80, 260, ["谢谢，请提问"], size=64, weight="bold", fill=WHITE)),
    group("end-line", (80, 360, 1120, 120),
          text_block(80, 400, ["一句话总结：满员不可怕，可怕的是名额算不清。"], size=26, fill=PALE, dy=48) + "\n" +
          text_block(80, 448, ["我们的办法，就是用事务和约束把这笔账算清楚。"], size=26, fill=PALE)),
    group("end-foot", (80, 560, 1120, 60),
          text_block(80, 592, ["13 小组 · 找个搭子 · Vue 3 + Spring Boot 3 + PostgreSQL"], size=20, fill=TEAL)),
])

print("wrote", len(os.listdir(OUT)), "svgs to", OUT)
