#!/usr/bin/env python3
"""本地真实 PostgreSQL 验收脚本（对运行中的 http://127.0.0.1:8080）。
覆盖：报名转正式/候补、正式退出触发递补、接受转正式、邀请超时自动顺延、
20 并发报名上限 4 恰好 4 正式、同一账号并发多次只有一条记录。
运行前：使用隔离测试库，开启 DEMO_ENABLED=true，设置 INVITE_TTL=10、SWEEP_SECONDS=2。
设置 INVITE_DAY_START_HOUR=0、INVITE_DAY_END_HOUR=24；脚本开头会重置演示数据。
"""
import concurrent.futures, json, os, sys, time, urllib.request

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080") + "/api"
from datetime import datetime, timedelta
# 活动时间取相对日期，避免写死日期过期导致创建被拒（2026-11-08 首次过期：截止 11-07 已过）。
# 形状与旧写死值一致：截止=明天 20:00，开始=后天 20:00，结束=后天 22:00。
_NOW = datetime.now()
DEADLINE = (_NOW + timedelta(days=1)).strftime("%Y-%m-%dT20:00:00")
START = (_NOW + timedelta(days=2)).strftime("%Y-%m-%dT20:00:00")
END = (_NOW + timedelta(days=2)).strftime("%Y-%m-%dT22:00:00")

results = []
def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS" if cond else "FAIL"), "-", name, detail)

def req(method, path, token=None, body=None, key=None):
    r = urllib.request.Request(BASE + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json",
                 **({"Authorization": "Bearer " + token} if token else {}),
                 **({"Idempotency-Key": key} if key is not None else {})})
    try:
        with urllib.request.urlopen(r) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try: return json.loads(e.read())
        except Exception: return {"ok": False, "error": f"HTTP {e.code}"}

def login(acc):
    d = req("POST", "/login", body={"account": acc, "password": "123456"})
    assert d.get("ok"), f"login {acc} failed: {d}"
    return d["token"]

def my(token):
    return {"participations": req("GET", "/my", token=token)}

def detail(token, aid):
    return req("GET", f"/activities/{aid}", token=token)

def authed_reset():
    """演示重置现在需要登录（审计修复）：先用种子组织者登录再重置；
    空库极端情况下先注册一个账号再登录重置。"""
    try:
        tok = login("organizer")
    except AssertionError:
        req("POST", "/register", body={"account": "auditboot", "password": "12345678", "nickname": "审计"})
        tok = login("auditboot", "12345678")
    r = req("POST", "/demo/reset", token=tok)
    assert r.get("ok"), f"reset failed: {r}"

def main():
    authed_reset()
    org = login("organizer")
    toks = {f"demo{i:02d}": login(f"demo{i:02d}") for i in range(1, 21)}
    acts = req("GET", "/activities", token=org)
    a = [x for x in acts if x["title"] == "羽毛球双打"][0]
    aid = a["id"]
    check("种子活动 A 上限 4、已有 3 正式", a["formal_count"] == 3 and a["capacity"] == 4, str(a))

    # 报名：demo04 正式、demo05/06 候补
    conflicting_seed = next(row for row in req("GET", "/activities", token=org) if row["title"] == "桌游之夜")
    r4 = req("POST", f"/activities/{aid}/signup", token=toks["demo04"], body={"conditions_confirmed": True, "confirmed_conflicts": [conflicting_seed["id"]]})
    r5 = req("POST", f"/activities/{aid}/signup", token=toks["demo05"], body={"conditions_confirmed": True})
    r6 = req("POST", f"/activities/{aid}/signup", token=toks["demo06"], body={"conditions_confirmed": True})
    check("第 4 人报名转正式", r4.get("my_status") == "FORMAL", str(r4))
    check("第 5 人报名转候补第 1", r5.get("my_status") == "WAITING" and r5.get("my_rank") == 1, str(r5))
    check("第 6 人报名转候补第 2", r6.get("my_status") == "WAITING" and r6.get("my_rank") == 2, str(r6))

    # 正式退出触发递补：demo04 退出 -> demo05 收到邀请
    p4 = [p for p in my(toks["demo04"])["participations"] if p["activity_id"] == aid][0]
    w = req("POST", f"/participations/{p4['id']}/withdraw", token=toks["demo04"])
    check("正式退出成功", w.get("my_status") == "WITHDRAWN", str(w))
    m5 = [p for p in my(toks["demo05"])["participations"] if p["activity_id"] == aid][0]
    check("退出后候补第 1 收到待确认邀请", m5["status"] == "INVITED" and m5["invitation_id"], str(m5))
    d = detail(org, aid)
    check("递补后有效占用仍 = 上限（正式 3 + 预留 1）",
          d["formal"] == 3 and d["reserved"] == 1, f"formal={d['formal']} reserved={d['reserved']} waiting={d['waiting']}")

    # 接受邀请转正式
    acc = req("POST", f"/invitations/{m5['invitation_id']}/accept", token=toks["demo05"])
    check("接受邀请转正式", acc.get("my_status") == "FORMAL", str(acc))
    d = detail(org, aid)
    check("接受后正式回到 4、预留 0", d["formal"] == 4 and d["reserved"] == 0, str(d))

    # 超时顺延：demo05 退出 -> demo06 邀请；等 10 秒 TTL + 扫描，demo06 邀请过期，demo07 收到邀请
    p5 = [p for p in my(toks["demo05"])["participations"] if p["activity_id"] == aid][0]
    req("POST", f"/participations/{p5['id']}/withdraw", token=toks["demo05"])
    # 先让 demo07 排队（此时 demo06 已是 INVITED，demo07 为候补）
    r7 = req("POST", f"/activities/{aid}/signup", token=toks["demo07"], body={"conditions_confirmed": True})
    check("demo07 报名为候补", r7.get("my_status") == "WAITING", str(r7))
    old_inv = detail(toks["demo06"], aid)["invitation_id"]
    print("  等待邀请超时（TTL 10s + 扫描）约 15 秒……")
    time.sleep(15)
    m6 = [p for p in my(toks["demo06"])["participations"] if p["activity_id"] == aid][0]
    m7 = [p for p in my(toks["demo07"])["participations"] if p["activity_id"] == aid][0]
    check("超时后原邀请失效（demo06 回到候补或过期）", m6["status"] in ("WAITING", "EXPIRED"), str(m6))
    check("超时后顺延到下一候补 demo07 收到邀请", m7["status"] == "INVITED" and m7["invitation_id"], str(m7))
    rej = req("POST", f"/invitations/{old_inv}/accept", token=toks["demo06"])
    check("过期邀请确认被拒绝", rej.get("ok") is False and rej.get("invitation_status") == "EXPIRED", str(rej))

    # 并发：新建上限 4 活动，20 个不同账号同时报名
    newa = req("POST", "/activities", token=org, body={
        "title": "并发测试活动", "category": "其他", "description": "并发测试",
        "conditions": "", "location": "线上", "capacity": 4,
        "deadline": DEADLINE, "start_time": START, "end_time": END})
    check("新建并发测试活动", newa.get("ok") is True, str(newa))
    cid = newa["id"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as ex:
        outs = list(ex.map(lambda t: req("POST", f"/activities/{cid}/signup", token=t), toks.values()))
    dc = detail(org, cid)
    check("20 并发报名：恰好 4 正式、16 候补", dc["formal"] == 4 and dc["waiting"] == 16,
          f"formal={dc['formal']} waiting={dc['waiting']} reserved={dc['reserved']}")

    # 同一账号并发多次：demo01 对另一新活动并发 10 次
    newa2 = req("POST", "/activities", token=org, body={
        "title": "重复提交测试", "category": "其他", "description": "重复测试",
        "conditions": "", "location": "线上", "capacity": 10,
        "deadline": DEADLINE,  "start_time": (datetime.fromisoformat(END) + timedelta(hours=1)).isoformat(),
        "end_time": (datetime.fromisoformat(END) + timedelta(hours=2)).isoformat()})
    cid2 = newa2["id"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
        list(ex.map(lambda _: req("POST", f"/activities/{cid2}/signup", token=toks["demo01"]), range(10)))
    rows = [p for p in my(toks["demo01"])["participations"] if p["activity_id"] == cid2]
    check("同一账号并发 10 次只有一条有效记录", len(rows) == 1, f"rows={len(rows)}")

    failed = [r for r in results if not r[1]]
    print(f"\n共 {len(results)} 项，失败 {len(failed)} 项")
    sys.exit(1 if failed else 0)

if __name__ == "__main__":
    main()
