#!/usr/bin/env python3
"""18-slide SDD deck: 找个搭子 · 系统概要设计 (CEM-SDD-0001 v1.0).
Design: Claude REA PPT translation (skill section 10) + local pattern.
White cards, 1pt border #E8E6DC; card radius 12px = 9pt via
adjustment = radius_pt / min(w_pt, h_pt), never 0.166.
Single-closed-border rule: only outer cards carry a line; inner elements
use fill only or hairline dividers. No gradients, no shadows (border-only).
Fonts: Noto Serif SC except STXingkai cover/closing display text (>=50pt),
with explicit a:ea in XML. All slide runs >= 14pt.
Sole content source: ~/workspace/user/files/17.docx (CEM-SDD-0001 v1.0).
"""
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn
from PIL import Image
import os

HOME = os.path.expanduser("~")
BG = os.path.join(HOME, "workspace/campus-buddy/assets/media-generation-bg-centerblank-content.jpg")
FIGS = os.path.join(HOME, "workspace/campus-buddy/ppt17-work/sdd-figs")
OUT = os.path.join(HOME, "workspace/campus-buddy/找个搭子-系统概要设计-15分钟.pptx")

INK = RGBColor(0x14, 0x14, 0x13)
CLAY = RGBColor(0xD9, 0x77, 0x57)
GREY = RGBColor(0x5E, 0x5D, 0x59)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
BORDER = RGBColor(0xE8, 0xE6, 0xDC)

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
BLANK = prs.slide_layouts[6]
SERIF = "Noto Serif SC"
DISPLAY = "STXingkai"

def new_slide():
    s = prs.slides.add_slide(BLANK)
    s.shapes.add_picture(BG, 0, 0, prs.slide_width, prs.slide_height)
    return s

def tb(s, x, y, w, h, paras, align=PP_ALIGN.LEFT, space_after=8, line_spacing=1.12):
    tx = s.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tx.text_frame
    tf.word_wrap = True
    for i, runs in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        p.space_after = Pt(space_after)
        p.line_spacing = line_spacing
        for (t, size, bold, color, font) in runs:
            r = p.add_run()
            r.text = t
            r.font.size = Pt(size)
            r.font.bold = bold
            r.font.color.rgb = color
            r.font.name = font
    return tx

def R(t, size=16, bold=False, color=INK, font=SERIF):
    return (t, size, bold, color, font)

def bullets(s, x, y, w, h, items, size=16, space_after=12):
    paras = []
    for lead, rest in items:
        runs = [R("▸ ", size, True, CLAY)]
        if lead:
            runs.append(R(lead, size, True))
        runs.append(R(rest, size))
        paras.append(runs)
    return tb(s, x, y, w, h, paras, space_after=space_after, line_spacing=1.15)

def card(s, x, y, w, h, radius_pt=9.0):
    shp = s.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(h))
    shp.fill.solid()
    shp.fill.fore_color.rgb = WHITE
    shp.line.color.rgb = BORDER
    shp.line.width = Pt(1)
    shp.shadow.inherit = False
    try:
        shp.adjustments[0] = radius_pt / min(w * 72.0, h * 72.0)
    except Exception:
        pass
    return shp

def hairline(s, x, y, w):
    shp = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(0.014))
    shp.fill.solid()
    shp.fill.fore_color.rgb = BORDER
    shp.line.fill.background()
    shp.shadow.inherit = False
    return shp

def claybar(s, x, y, w):
    shp = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(x), Inches(y), Inches(w), Inches(0.045))
    shp.fill.solid()
    shp.fill.fore_color.rgb = CLAY
    shp.line.fill.background()
    shp.shadow.inherit = False
    return shp

def header(s, kicker, title):
    tb(s, 1.0, 0.42, 11.3, 0.35, [[R(kicker, 14, True, CLAY)]])
    tb(s, 1.0, 0.78, 11.3, 0.65, [[R(title, 28, True)]])
    claybar(s, 1.0, 1.42, 0.9)

def footer(s, n):
    tb(s, 1.0, 7.08, 11.33, 0.32,
       [[R("找个搭子 · 系统概要设计", 14, False, GREY), R("    %d / 18" % n, 14, True, GREY)]])

def notes(s, txt):
    s.notes_slide.placeholders[1].text = txt

def pic_fit(s, path, x, y, w_in):
    iw, ih = Image.open(path).size
    h_in = w_in * ih / iw
    s.shapes.add_picture(path, Inches(x), Inches(y), width=Inches(w_in), height=Inches(h_in))
    return h_in

# ---------------- Slide 1 · cover ----------------
s = new_slide()
tb(s, 1.0, 0.85, 11.3, 0.5, [[R("计算机学院 · 问题求解实战课程", 16, True, CLAY)]])
tb(s, 1.0, 1.55, 11.3, 1.5, [[R("找个搭子", 64, True, INK, DISPLAY)]])
tb(s, 1.0, 3.15, 11.3, 0.7, [[R("校园活动组队平台 · 系统概要设计", 28, True)]])
hairline(s, 1.0, 4.05, 11.33)
tb(s, 1.0, 4.35, 11.3, 1.6, [
    [R("文档编号  CEM-SDD-0001  v1.0", 16, True)],
    [R("陈子逸小组 · 陈子逸  张梓轩  吉浩铭  唐裕翔", 16)],
    [R("指导老师  陈正茂 · 2026-11-03", 16)],
], space_after=10)
footer(s, 1)
notes(s, "约40秒：大家好，我们是陈子逸小组，今天汇报校园活动组队平台找个搭子的系统概要设计，文档编号是CEM-SDD-0001，版本1.0。汇报大约十五分钟，先讲设计目标和约束，再讲架构、事务、数据库和部署，最后讲验证与尚未解决的问题，请大家带着名额怎么算清、状态怎么追溯这两个问题来听。汇报结束后欢迎对照说明书逐条提问，重点章节都已在目录页标明出处。")

# ---------------- Slide 2 · 目录 ----------------
s = new_slide()
header(s, "目录", "目录 · 七组十三章")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.95, 10.7, 4.6, [
    ("问题与约束", " —— 第1–3章：背景、条件限制与需求规定"),
    ("运行环境与体系架构", " —— 第4–5章：运行环境、物理结构、分层与事务一致性"),
    ("逻辑结构", " —— 第6章：十个模块、流程与接口约定"),
    ("数据库", " —— 第7章：逻辑结构、物理结构与数据保全"),
    ("部署与运行", " —— 第8–9章：单机发布、备份恢复与运行目录"),
    ("性能与容错", " —— 第10–11章：指标、并发与出错处理"),
    ("实施与遗留", " —— 第12–13章：开发环境与尚未解决事项"),
], size=17, space_after=16)
footer(s, 2)
notes(s, "约35秒：目录按说明书的十三章归成七组，便于十五分钟讲完。前面是问题与约束，中间是运行环境与架构、逻辑结构和数据库，这是本次汇报的重点，后面是部署运行、性能容错，最后是开发环境与遗留问题。每一组都对应说明书的具体章节，编号可以在会后对照文档查找原文，不用在汇报里逐章展开。分组方式只是汇报顺序，说明书原文十三章的编号和标题保持不变。")

# ---------------- Slide 3 · 设计目标 ----------------
s = new_slide()
header(s, "01 · 设计目标", "设计目标：名额算得清，状态查得到")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.95, 10.7, 4.6, [
    ("名额真实", " —— 正式人数加有效预留永不超过容量，绝不超员、不出现负人数。"),
    ("队列有序", " —— 满员后按入队顺序递补，队列存在时新用户不能抢占空缺。"),
    ("状态可追溯", " —— 每次变更追加历史，出勤与信用更正重算序列、有据可查。"),
    ("一句话架构", " —— 单体分层应用加 MySQL 事务，通知走事务 Outbox，规则只写在一处。"),
], size=17, space_after=18)
footer(s, 3)
notes(s, "约45秒：先定三个目标。第一是名额真实，正式加预留不能超过容量，这是整份设计的底线。第二是队列有序，满员不是先到先得的抢，而是按入队顺序递补。第三是状态可追溯，改过什么、为什么改，都要留历史。实现上用一句话概括：单体分层加MySQL事务，通知走Outbox，规则只写在一处，避免多处各算各的账。这三个目标也是后面验收时判断设计是否合格的标尺，不是口号。")

# ---------------- Slide 4 · 需求与约束 ----------------
s = new_slide()
header(s, "02 · 需求与约束", "需求与约束（第1–3章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 10.7, 4.7, [
    ("痛点", " —— 群里报名统计不一致，满员后顺序不清，空缺补位迟缓。"),
    ("范围", " —— 发布、浏览、报名、候补、通知、签到、出勤、评价、信用、治理。"),
    ("固定类别", " —— 仅羽毛球、桌游、学习组三类，类别不在运行期随意新增。"),
    ("明确不做", " —— 支付、场地预约、地图导航、学校统一身份认证。"),
    ("规模", " —— 10个模块（CEM-01至10），38个用例。"),
    ("优先级", " —— P0：报名到候补确认闭环；P1：出勤、信用与治理。"),
], size=16, space_after=13)
footer(s, 4)
notes(s, "约50秒：需求来自真实痛点，群里报名经常出现人数对不上、满员后谁先补说不清、空位迟迟补不上的问题。功能范围覆盖发布到治理十个环节，但边界要讲清：只做羽毛球、桌游、学习组三类固定活动，不做支付、场地预约、地图导航和学校统一身份认证。规模是十个模块三十八个用例，优先级上先保报名到候补确认的闭环，再做出勤信用与治理。边界越早讲清，实施时返工越少，不做清单与要做清单同等重要。")

# ---------------- Slide 5 · 运行环境 ----------------
s = new_slide()
header(s, "03 · 运行环境", "运行环境（第4章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.95, 10.7, 4.6, [
    ("访问方式", " —— 浏览器访问，服务端集中处理，浏览器不能直接写库。"),
    ("技术栈", " —— Java 17、Spring Boot 3.x、Vue 3、MySQL 8.x。"),
    ("部署形态", " —— 单机同源部署：后端同时提供静态页面与接口。"),
    ("资源下限", " —— 4核处理器、8GB内存、20GB可用磁盘。"),
    ("会话", " —— Cookie 会话，服务端持久化会话状态；闲置30分钟、绝对12小时到期。"),
], size=17, space_after=16)
footer(s, 5)
notes(s, "约40秒：运行环境很朴素，就是浏览器加一台服务器。技术栈是Java17、SpringBoot3、Vue3和MySQL8，单机同源部署，后端既出静态页面也出接口。资源下限是四核、八吉内存、二十吉可用磁盘。会话用Cookie，服务端持久化状态，闲置三十分钟或登录满十二小时到期，封禁或重置会立即撤销旧会话。单机部署是课程验收的基线，公网形态只在反向代理之后才成立。")

# ---------------- Slide 6 · 体系架构 ----------------
s = new_slide()
header(s, "04 · 体系架构", "体系架构（第5章）")
card(s, 1.0, 1.65, 11.33, 2.05)
bullets(s, 1.35, 1.85, 10.7, 1.7, [
    ("物理结构（图5.1）", " —— 浏览器 → Spring Boot 单节点（静态页面＋/api/v1）→ MySQL（JDBC）＋受控附件目录。"),
    ("公网", " —— 经 HTTPS 反向代理 443 接入；数据库与附件目录不向公网开放。"),
    ("分层", " —— 表示／接口／业务／数据访问／持久；Controller 不能绕过业务服务改参与状态。"),
    ("周期任务", " —— 生命周期、超时与结算任务调用共享业务服务，不另写一套规则。"),
], size=15, space_after=8)
pic_fit(s, os.path.join(FIGS, "image1.png"), 2.77, 3.90, 7.8)
footer(s, 6)
notes(s, "约55秒：架构看一张物理图（图5.1）加分层文字，物理上是浏览器到单节点应用再到数据库和受控附件目录，公网只经过四四三反向代理，数据库和附件目录不对公网开放。逻辑上分五层，关键约束是浏览器不能直接写库，接口层也不能绕过业务服务改参与状态，周期任务同样调用共享业务服务，这样名额规则在全系统只有一处实现。分层不是为了好看，而是让每一次参与状态变更都经过同一道检查。")

# ---------------- Slide 7 · 事务与一致性 ----------------
s = new_slide()
header(s, "04 · 体系架构", "事务与一致性（第5章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 10.7, 4.7, [
    ("串行化边界", " —— 锁活动行 → 重核有效性与到期邀请 → 查正式＋有效预留 → 改参与／邀请 → 追加历史 → 写通知事件。"),
    ("同一服务", " —— 报名、退出、扩容、移出、取消、接受、拒绝、超时走同一容量服务。"),
    ("锁外余量", " —— 锁外看到的余量只是快照，不能作为分配依据。"),
    ("通知 Outbox", " —— 通知事件与业务同事务提交；事件与接收者唯一键去重、失败补发。"),
    ("不变式 INV-01–03", " —— 0≤正式＋有效预留≤容量；同人同活动最多一条有效参与；每邀请最多一终态。"),
    ("不变式 INV-04–06", " —— 队列存在时新用户不抢空缺；更正可追溯、结算不重复；取消后不再报名／递补／签到。"),
], size=15, space_after=11)
footer(s, 7)
notes(s, "约55秒：名额正确的核心是一条串行化边界，锁住活动行之后再重核有效性、清理到期邀请、查正式加预留、改状态、写历史、写通知事件，全部走同一容量服务。锁外看到的余量只是快照，不能用来分配。通知用事务Outbox与业务同提交，并按事件加接收者去重。六条不变式兜底：不超员、一人一条有效参与、一邀请一终态、有队列不抢空缺、更正可追溯、取消即终态。不变式写进设计还不够，验收用例要逐条构造违反场景来验证它们。")

# ---------------- Slide 8 · 逻辑结构 ----------------
s = new_slide()
header(s, "05 · 逻辑结构", "逻辑结构：10个模块（第6章）")
card(s, 1.0, 1.65, 11.33, 5.15)
tb(s, 1.35, 1.85, 10.7, 0.4, [[R("公共支持 CEM-COM：", 15, True), R("鉴别、校验、时间、事务、幂等、错误码、文件访问、审计", 15)]])
hairline(s, 1.35, 2.30, 10.63)
bullets(s, 1.35, 2.50, 5.25, 4.1, [
    ("CEM-01", " 用户与权限：注册、登录、资料、密码恢复"),
    ("CEM-02", " 活动浏览：列表、搜索、分类与详情"),
    ("CEM-03", " 活动管理：发布、修改、取消、名单"),
    ("CEM-04", " 报名管理：正式／候补分配与退出"),
    ("CEM-05", " 候补管理：按序递补、限时确认"),
], size=15, space_after=14)
bullets(s, 6.85, 2.50, 5.25, 4.1, [
    ("CEM-06", " 个人活动查询：报名／候补／创建／历史"),
    ("CEM-07", " 通知管理：事件落库、补发与已读"),
    ("CEM-08", " 出勤与反馈：签到码、核对与评价"),
    ("CEM-09", " 平台治理：封禁、下架、投诉与申诉"),
    ("CEM-10", " 定时任务与信用：生命周期与结算"),
], size=15, space_after=14)
footer(s, 8)
notes(s, "约50秒：逻辑结构是十个业务模块加一个公共支持。公共支持提供鉴别、校验、时间、事务、幂等、错误码、文件访问和审计，业务模块不各自重复造轮子。前五个管人和活动：用户权限、浏览、管理、报名、候补；后五个管个人视角和闭环：个人查询、通知、出勤反馈、治理、定时任务与信用。模块划分与说明书表三点一完全一致。模块边界划分清楚后，各小组可以按模块分工，接口先行对齐。")

# ---------------- Slide 9 · 报名与候补流程 ----------------
s = new_slide()
header(s, "05 · 逻辑结构", "报名与候补流程（第6章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 10.7, 4.7, [
    ("报名", " —— 校验 → 锁 → 重核 → 先清理到期邀请并递补 → 无队列且有空缺才直接正式，否则新批次进队尾。"),
    ("时间冲突", " —— max(开始1,开始2)＜min(结束1,结束2)为冲突，首尾相接不冲突；冲突需再次确认，提交时重算。"),
    ("队列顺序", " —— 按（queue_at，participation_id）升序，同秒用自增批次号打破平局。"),
    ("邀请期限", " —— expires_at＝min（发出后10分钟，报名截止）；待确认项返回绝对期限与 serverTime。"),
    ("接受条件", " —— 归属正确＋PENDING＋now＜expires_at＋now＜deadline＋账号活动有效；转换不增加总占用。"),
    ("超时／拒绝", " —— 释放预留并递补下一人；阅读消息不触发接受。"),
], size=15, space_after=11)
footer(s, 9)
notes(s, "约65秒：报名流程的关键顺序不能错：先校验、加锁、重核，再清理到期邀请并递补，最后才看有没有空缺，没有队列又有空缺才能直接正式，否则一律进队尾。时间冲突用半开区间公式判定，首尾相接不算冲突，冲突要用户再次确认且提交时重算。候补按入队时间和批次号排序，邀请期限取发出后十分钟和报名截止的较早值，接受要同时满足归属、状态、两个时间和账号有效，超时或拒绝就释放预留递补下一人。流程里每一步失败都要整体回滚，不能留下半正式半候补的中间态。")

# ---------------- Slide 10 · 出勤、信用与治理 ----------------
s = new_slide()
header(s, "05 · 逻辑结构", "出勤、信用与治理（第6章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 10.7, 4.7, [
    ("签到码", " —— 6位、每5分钟轮换、仅组织者可见、按活动与用户唯一。"),
    ("资格快照", " —— 活动开始时保存正式资格快照，不拿结束时的当前列表替代。"),
    ("核对默认", " —— 结束后24小时内组织者核对；逾期系统默认：已签到→到场，未签到→未核实。"),
    ("信用", " —— 到场＋2／核实缺席−10／豁免或未核实0；score＝min（100，max（0，score＋delta）），更正重算序列。"),
    ("申诉期", " —— 7日，自通知可查询时刻（available_at）起算。"),
    ("治理", " —— 封禁／下架／投诉／申诉；复核人必须不同于原处理人，不直接删除旧行。"),
], size=15, space_after=11)
footer(s, 10)
notes(s, "约55秒：出勤先讲签到码，六位、五分钟一换、只有组织者能看到，而且按活动加用户唯一。活动开始时要保存正式资格快照，不能用结束时的名单反推。结束后二十四小时内组织者核对，逾期系统自动默认，已签到算到场、未签到算未核实。信用是到场加二、核实缺席减十、豁免或未核实零分，并钳制在零到一百分之间，更正要重算整个序列。申诉期七天，治理上复核人必须与原处理人不同，旧行只追加不删除。信用分不是惩罚工具，而是让到场记录与后续报名资格挂钩的依据。")

# ---------------- Slide 11 · 接口约定 ----------------
s = new_slide()
header(s, "05 · 逻辑结构", "接口约定（第6.12节）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 5.25, 4.7, [
    ("前缀", " —— /api/v1；GET 不执行业务写操作。"),
    ("时间", " —— ISO 8601 带时区；存储 UTC，界面按 Asia/Shanghai 显示。"),
    ("标识", " —— BIGINT 标识在 JSON 中用十进制字符串。"),
    ("分页", " —— items／page／pageSize／total。"),
    ("响应", " —— 含 serverTime＋traceId；业务含 code／message／data，校验错误含 fieldErrors。"),
], size=15, space_after=14)
bullets(s, 6.85, 1.90, 5.25, 4.7, [
    ("写接口重核", " —— 身份、归属、账号状态、实际时间、预期版本。"),
    ("幂等", " —— Idempotency-Key 按用户＋接口＋键＋请求摘要持久去重，同键不同体返回冲突。"),
    ("内部任务", " —— 内部任务接口不暴露为普通用户 HTTP 入口。"),
    ("错误示例", " —— 409 VERSION_CONFLICT、INVITATION_EXPIRED、IDEMPOTENCY_CONFLICT。"),
], size=15, space_after=14)
footer(s, 11)
notes(s, "约50秒：接口约定先定在第6.12节。统一前缀是api-v1，时间用带时区的ISO格式，库里存UTC、界面按上海时间显示，大整数标识在JSON里用十进制字符串，避免前端精度丢失。分页和响应结构统一，含服务器时间和追踪编号，校验错误要定位到字段。写接口每次都重核身份、归属、账号状态、实际时间和预期版本，幂等键按用户加接口加摘要持久去重，内部任务接口不对外暴露。接口约定定下来之后，前后端可以并行开发，减少联调时的扯皮。")

# ---------------- Slide 12 · 数据库逻辑结构 ----------------
s = new_slide()
header(s, "06 · 数据库", "数据库逻辑结构（第7章）")
card(s, 1.0, 1.65, 6.15, 5.15)
bullets(s, 1.30, 1.90, 5.6, 4.7, [
    ("多对多", " —— 用户—活动经参与批次多对多；重报产生新批次、旧终态保留。"),
    ("邀请", " —— 邀请关联具体批次，而非仅关联用户。"),
    ("唯一", " —— 签到、评价按活动＋用户唯一。"),
    ("出勤与信用", " —— 出勤保留开始时资格快照、事实版本及通知；信用引用有效出勤版本。"),
    ("治理链", " —— 决定／投诉／申诉独立关联，证据经业务记录控权。"),
    ("17组逻辑表", " —— 用户、会话、恢复、活动、参与、邀请、活动限制、签到、出勤、评价、信用、治理、投诉申诉、证据、通知、去重与任务、提醒。"),
], size=15, space_after=11)
pic_fit(s, os.path.join(FIGS, "image7.png"), 7.55, 1.75, 4.75)
tb(s, 7.55, 5.35, 4.75, 0.4, [[R("图7.1 核心业务实体关系", 14, True, GREY)]], align=PP_ALIGN.CENTER)
footer(s, 12)
notes(s, "约50秒：数据库逻辑结构的核心是参与批次。用户和活动不是直接多对多，而是经过参与批次关联，重报就产生新批次，旧的终态保留下来，邀请也挂在具体批次上。签到和评价按活动加用户唯一，出勤保留开始时的资格快照和事实版本，信用只引用有效的出勤版本。治理决定、投诉、申诉是独立关联链，证据的访问要经过所属业务记录控制，右图就是说明书的图七点一。逻辑表分组列出是为了汇报清晰，完整字段与约束以表七点一为准。")

# ---------------- Slide 13 · 数据库物理与保全 ----------------
s = new_slide()
header(s, "06 · 数据库", "数据库物理与保全（第7章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 10.7, 4.7, [
    ("类型与时间", " —— BIGINT 主键；业务时间 DATETIME 精确到秒、存 UTC；created_at／updated_at／version 由服务端生成。"),
    ("引擎与隔离", " —— InnoDB＋严格 SQL 模式，隔离拟 READ COMMITTED；锁定读取＋唯一约束共同保证正确性。"),
    ("约束分工", " —— CHECK 只做单行范围；跨表规则由锁内业务服务检查。"),
    ("查询原则", " —— 容量／邀请查询只用权威业务表；名称搜索用有界参数化 LIKE 并转义通配符。"),
    ("证据", " —— 最多5件、每件≤5MB、仅 PNG／JPEG／PDF；随机 storage_key 存静态根之外，下载按案件鉴权。"),
    ("保全", " —— 首版不自动清理审计证据；删除或更正时保留必要证据、历史与决定链。"),
], size=15, space_after=11)
footer(s, 13)
notes(s, "约50秒：物理层先定类型和时间，主键统一用大整数，业务时间精确到秒并存UTC，三个审计字段由服务端生成。引擎是InnoDB加严格模式，隔离级别拟用读已提交，锁定读取和唯一约束一起保证正确性。约束要分工，CHECK只管单行范围，跨表规则放在锁内业务服务里。容量查询只认权威业务表，名称搜索先用有界参数化模糊查询。证据最多五件、单件不超过五兆、只收图片和PDF，随机键存在静态根之外，首版不自动清理审计证据。物理设计的选择都要能在验收机上复现，参数不能只停留在纸面。")

# ---------------- Slide 14 · 部署与发布 ----------------
s = new_slide()
header(s, "07 · 部署与运行", "部署与发布（第8章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 10.7, 4.7, [
    ("形态", " —— 单机 Windows／Linux；Vue 静态资源置于后端静态目录，打包可执行 JAR。"),
    ("端口", " —— 本地演示 8080 HTTP；公网反向代理 443 终止 HTTPS，应用端口只对代理开放。"),
    ("① 检查", " —— 资源、端口、时钟、备份容量。"),
    ("② 建库", " —— 最小权限账号＋版本化迁移＋初始化3类别＋至少2名管理员，无默认通用密码。"),
    ("③ 外置配置", " —— 连接、文件路径、时区、邀请窗口、任务周期、会话；敏感值不进代码。"),
    ("④–⑥ 联调与发布", " —— 健康检查＋小规模联调；demo 60秒邀请窗口单独启用并记录；发布前备份、发布后核对迁移版本与任务进度。"),
], size=15, space_after=11)
footer(s, 14)
notes(s, "约50秒：部署是单机方案，Windows或Linux都可以，前端静态资源放在后端静态目录里，打成一个可执行JAR。本地演示走八零八零，公网由反向代理在四四三终止HTTPS，应用端口只对代理开放。发布分六步：先检查资源端口时钟和备份容量，再建库并用最小权限账号做版本化迁移、初始化三类和至少两名管理员，接着外置配置、健康检查和小规模联调，演示用的六十秒窗口要单独启用并记录，最后发布前备份、发布后核对版本和任务进度。发布步骤里的每一步都要留记录，出了问题才能按步骤回退。")

# ---------------- Slide 15 · 备份、恢复与运行目录 ----------------
s = new_slide()
header(s, "07 · 部署与运行", "备份、恢复与运行目录（第8–9章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 10.7, 4.7, [
    ("备份", " —— 每日低峰一致性备份＋同一时点证据文件与清单，算校验值并验证可读，保留最近7份成功备份。"),
    ("备份纪律", " —— 演示备份单独标识；备份不进公开静态目录、不随代码提交。"),
    ("恢复", " —— 停业务写入 → 隔离恢复 → 核对迁移版本／文件清单／关键行数／容量不变式 → 切换服务 → 补扫任务，旧会话按策略撤销。"),
    ("演练目标", " —— 以实际演练验证 30分钟恢复与 24小时数据窗口，不只看备份文件存在。"),
    ("运行目录", " —— campus-team／{app，config，data／evidence，data／staging，logs，backup，scripts，release}。"),
], size=15, space_after=13)
footer(s, 15)
notes(s, "约50秒：备份每天在低峰做一致性备份，数据库和同一时点的证据文件与清单一起备，算校验值并验证可读，只保留最近七份成功备份，演示备份要单独标识，备份不能放公开静态目录也不能随代码提交。恢复要先停写入、在隔离环境恢复并核对版本清单行数和容量不变式，再切换服务并补扫到期任务，旧会话按策略撤销。最后用实际演练验证三十分钟恢复和二十四小时数据窗口。备份的价值不在备份本身，而在恢复演练证明它真的能用。")

# ---------------- Slide 16 · 性能设计 ----------------
s = new_slide()
header(s, "08 · 性能与容错", "性能设计（第10章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 10.7, 4.7, [
    ("查询类 P95≤2秒", " —— 列表、详情、登录、签到、通知查询。"),
    ("写入类 P95≤3秒", " —— 报名、退出、移出、邀请确认。"),
    ("通知与任务", " —— 业务通知事务提交后5秒内可查询（Outbox，拟1秒轮询）；生命周期／超时／信用≤60秒（30秒扫描）。"),
    ("规模", " —— 100账号／1000活动／10000参与记录，20用户同时常用操作。"),
    ("并发原则", " —— 所有名额写入短事务，锁不跨二次确认或文件上传；任务分批每批100候选起，逐活动重核。"),
    ("串行与重试", " —— 活动行写串行、不同活动可并行；死锁回滚整短事务并有限重试。"),
], size=15, space_after=11)
footer(s, 16)
notes(s, "约45秒：性能指标分三档，查询类包括列表详情登录签到和通知查询，P95不超过两秒；报名退出移出和邀请确认不超过三秒；通知在事务提交后五秒内可查询，生命周期超时和信用结算在六十秒内完成，扫描周期是三十秒。规模按一百账号、一千活动、一万参与记录，二十用户并发来设计。原则是名额写入都用短事务，锁不跨二次确认和文件上传，任务分批、每批一百候选起，活动行写串行、不同活动可并行，死锁回滚整事务并有限重试。性能数字都是在规定规模和并发下的目标，换了规模就要重新测。")

# ---------------- Slide 17 · 验证与出错处理 ----------------
s = new_slide()
header(s, "08 · 性能与容错", "验证与出错处理（第10–11章）")
card(s, 1.0, 1.65, 11.33, 5.15)
bullets(s, 1.35, 1.90, 5.25, 4.7, [
    ("验收", " —— AT-01至22；容量4的活动20账号并发报名＝正式4＋候补16，重复提交不增加有效参与。"),
    ("竞争场景", " —— 期限相等、接受与超时竞争、取消与确认竞争、扩容先递补、停机后补扫、通知失败补发。"),
    ("口径与记录", " —— 服务端收到完整请求到返回完整响应，含排队与处理；记录 commit／版本／数据集／硬件／种子／配置／并发模型／错误率／P95；正确性与延迟分别统计，不预填通过结论。"),
], size=15, space_after=13)
bullets(s, 6.85, 1.90, 5.25, 4.7, [
    ("错误码", " —— 400 VALIDATION_ERROR／401／403／404／409 VERSION_CONFLICT／409 CONFLICT_CONFIRM_REQUIRED／409 INVITATION_EXPIRED／409 IDEMPOTENCY_CONFLICT／429／503。"),
    ("失败处理", " —— 整体回滚业务＋历史＋Outbox；外部响应不泄露 SQL／类名／路径，敏感凭据不写普通日志。"),
], size=15, space_after=13)
footer(s, 17)
notes(s, "约60秒：验证以AT零一到二十二为基准，关键用例是容量四的活动让二十个账号并发报名，结果必须是正式四人加候补十六人，重复提交不能增加有效参与。还要覆盖期限相等、接受与超时竞争、取消与确认竞争、扩容先递补、停机后补扫和通知失败补发。响应时间口径是服务端收到完整请求到返回完整响应，记录要含提交版本、数据集、硬件和P95，正确性和延迟分别统计，文档不预填通过结论。错误码和整体回滚规则见右侧，外部响应不能泄露内部实现细节。错误码统一后，前端才能给出一致的提示，用户才知道下一步做什么。")

# ---------------- Slide 18 · 尚未解决与总结致谢 ----------------
s = new_slide()
tb(s, 1.0, 0.42, 11.3, 0.35, [[R("09 · 实施与遗留", 14, True, CLAY)]])
tb(s, 1.0, 0.78, 11.3, 0.65, [[R("尚未解决与总结", 28, True)]])
claybar(s, 1.0, 1.42, 0.9)
card(s, 1.0, 1.65, 11.33, 3.40)
bullets(s, 1.35, 1.85, 10.7, 3.2, [
    ("依赖补丁版本冻结", " —— 影响：版本漂移；关闭：提交构建与锁文件及可重复构建记录。"),
    ("管理员与工作日日历", " —— 影响：独立复核缺人手与时限口径；关闭：登记授权回避流程与业务日历排班。"),
    ("人工恢复核验材料", " —— 影响：昵称＋可改联系方式不足以证明归属；关闭：确定可核验材料与拒绝标准。"),
    ("证据与审计保存期限", " —— 影响：隐私与磁盘预算；关闭：明确保留清理规则，首版不自动删争议证据。"),
    ("实际性能与恢复能力", " —— 影响：目标未经实测；关闭：形成 AT-01至22 记录并完成备份恢复演练。"),
    ("公网部署参数", " —— 影响：证书／域名／代理信任／磁盘告警／备份位置未定；关闭：部署前核对并写入受控配置。"),
], size=15, space_after=9)
tb(s, 1.0, 5.25, 11.33, 0.55, [[R("一句话总结：单体分层让规则只写在一处，活动行锁＋唯一约束让名额算得清，Outbox＋版本＋审计让结果可追溯可更正。", 16, True)]])
tb(s, 1.0, 5.90, 11.33, 0.95, [[R("谢谢,请提问", 50, True, INK, DISPLAY)]])
footer(s, 18)
notes(s, "约45秒：最后讲六项尚未解决的问题，每项都给了影响和关闭条件：依赖补丁版本要冻结，管理员复核需要至少两人和工作日日历，人工恢复的核验材料现在还不足以证明归属，证据和审计保存期限要明确，实际性能和恢复能力必须在验收机上实测，公网部署参数要在部署前核对写入配置。总结一句话，单体分层让规则只写在一处，活动行锁加唯一约束让名额算得清，Outbox加版本加审计让结果可追溯可更正。谢谢，请提问。遗留问题不是设计缺陷的遮掩，而是下一阶段工作的明确入口。")

# ---------------- Fonts: Noto Serif SC everywhere; STXingkai only for the
# cover title and closing display text (>=50pt runs), with explicit a:ea.
def fix_fonts(slide):
    for shape in slide.shapes:
        if not shape.has_text_frame:
            continue
        for para in shape.text_frame.paragraphs:
            for run in para.runs:
                size = run.font.size.pt if run.font.size is not None else 0
                name = DISPLAY if (run.text.strip() in ("找个搭子", "谢谢,请提问") and size >= 50) else SERIF
                run.font.name = name
                rPr = run._r.get_or_add_rPr()
                for ea in rPr.findall(qn("a:ea")):
                    rPr.remove(ea)
                rPr.append(rPr.makeelement(qn("a:ea"), {"typeface": name}))

for slide in prs.slides:
    fix_fonts(slide)
    if slide.has_notes_slide:
        fix_fonts(slide.notes_slide)

prs.save(OUT)
print(OUT)

# Notes character count: Chinese characters only, excluding the time hint
# prefix (everything up to and including the first full-width colon).
import re
total = 0
times = []
for slide in prs.slides:
    txt = slide.notes_slide.placeholders[1].text
    m = re.match(r"^约\d+秒：", txt)
    hint = m.group(0) if m else ""
    times.append(hint)
    body = txt[len(hint):]
    total += sum(1 for ch in body if "\u4e00" <= ch <= "\u9fff")
print("slides:", len(prs.slides))
print("time hints:", times)
print("notes Chinese chars (excluding time hints):", total)
