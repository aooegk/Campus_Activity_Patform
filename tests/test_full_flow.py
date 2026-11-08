#!/usr/bin/env python3
"""端到端完整业务流（对运行中的 http://127.0.0.1:8080，TTL=10s、扫描=2s）。
按用户真实使用顺序：注册 -> 发布 -> 报名 -> 满员候补 -> 退出递补 -> 接受
-> 超时顺延 -> 通知 -> 退出后重新报名 -> 组织者取消活动。
"""
import json, os, sys, time, urllib.request, urllib.error

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
    results.append(bool(cond)); print(("PASS" if cond else "FAIL"), "-", name, detail)
def req(method, path, token=None, body=None):
    r = urllib.request.Request(BASE + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(r) as resp: return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try: return json.loads(e.read())
        except Exception: return {"ok": False, "error": f"HTTP {e.code}"}
def login(a, p="123456"):
    d = req("POST", "/login", body={"account": a, "password": p}); assert d.get("ok"), d; return d["token"]
def my(t): return req("GET", "/my", token=t)
def mine_for(t, aid):
    rows = [p for p in my(t) if p["activity_id"] == aid]; return rows[0] if rows else None

try:
    _tok = login("organizer")
except AssertionError:
    req("POST", "/register", body={"account": "auditboot", "password": "12345678", "nickname": "审计"})
    _tok = login("auditboot", "12345678")
assert req("POST", "/demo/reset", token=_tok).get("ok"), "reset failed"
# 1 注册新用户（前端新增的注册入口对应的接口）
r = req("POST", "/register", body={"account": "newuser1", "password": "12345678", "nickname": "新同学"})
check("注册新用户", r.get("ok") is True, str(r))
dup = req("POST", "/register", body={"account": "newuser1", "password": "12345678"})
check("重复账号注册被拒绝", dup.get("ok") is False, str(dup))
org = login("organizer"); u1 = login("newuser1", "12345678")
d1, d2, d3 = login("demo01"), login("demo02"), login("demo03")

# 2 发布活动（上限 2）
a = req("POST", "/activities", token=org, body={
    "title": "全流程测试：羽毛球", "category": "羽毛球", "description": "端到端测试",
    "conditions": "", "location": "体育馆", "capacity": 2,
    "deadline": DEADLINE, "start_time": START, "end_time": END})
check("组织者发布活动", a.get("ok") is True, str(a)); aid = a["id"]

# 3 报名：2 正式，第 3 人候补
s1 = req("POST", f"/activities/{aid}/signup", token=d1)
s2 = req("POST", f"/activities/{aid}/signup", token=d2)
s3 = req("POST", f"/activities/{aid}/signup", token=u1)
check("前 2 人正式", s1.get("my_status") == "FORMAL" and s2.get("my_status") == "FORMAL", f"{s1.get('my_status')},{s2.get('my_status')}")
check("第 3 人候补第 1", s3.get("my_status") == "WAITING" and s3.get("my_rank") == 1, str(s3))

# 4 退出递补：demo01 退出 -> 新同学收到邀请并接受
p1 = mine_for(d1, aid)
req("POST", f"/participations/{p1['id']}/withdraw", token=d1)
m = mine_for(u1, aid)
check("退出后候补收到待确认邀请", m["status"] == "INVITED" and m["invitation_id"], str(m))
acc = req("POST", f"/invitations/{m['invitation_id']}/accept", token=u1)
check("接受邀请转正式", acc.get("my_status") == "FORMAL", str(acc))
n = req("GET", "/notifications", token=u1)
check("新同学收到邀请/报名通知", len(n["list"]) >= 2, f"通知 {len(n['list'])} 条")
req("POST", "/notifications/read-all", token=u1)
check("通知全部已读后未读为 0", req("GET", "/notifications", token=u1)["unread"] == 0)

# 5 超时顺延：新同学退出 -> demo03 候补收到邀请；等超时 -> demo03 过期，下一候补（无）；
#    为验证顺延，先让 demo03 候补、再注册第二候补
s3b = req("POST", f"/activities/{aid}/signup", token=d3)
check("demo03 候补", s3b.get("my_status") == "WAITING", str(s3b))
p_new = mine_for(u1, aid)
req("POST", f"/participations/{p_new['id']}/withdraw", token=u1)
m3 = mine_for(d3, aid)
check("再次退出后 demo03 收到邀请", m3["status"] == "INVITED", str(m3))
print("  等待邀请超时约 15 秒……"); time.sleep(15)
m3b = mine_for(d3, aid)
check("超时后 demo03 邀请过期", m3b["status"] == "EXPIRED", str(m3b))

# 6 退出后重新报名（前端新增的重新报名入口对应的接口）
re = req("POST", f"/activities/{aid}/signup", token=d3)
check("过期后重新报名成功（复用同一记录、不产生第二条）",
      re.get("my_status") in ("FORMAL", "WAITING") and len([p for p in my(d3) if p["activity_id"] == aid]) == 1, str(re))

# 7 组织者取消活动
c = req("POST", f"/activities/{aid}/cancel", token=org)
check("组织者取消活动", c.get("ok") is True, str(c))
det = req("GET", f"/activities/{aid}", token=org)
check("取消后活动状态为 CANCELLED", det["status"] == "CANCELLED", str(det["status"]))
check("取消后报名被拒绝", req("POST", f"/activities/{aid}/signup", token=d2).get("ok") is False)

print(f"\n共 {len(results)} 项，失败 {results.count(False)} 项")
sys.exit(1 if False in results else 0)
