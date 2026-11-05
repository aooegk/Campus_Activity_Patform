#!/usr/bin/env python3
"""后端审计复现脚本（对运行中的 http://127.0.0.1:8080）。
只用 /demo/reset（演示接口，会恢复种子数据）做清理，不删库。
每个检查打印 PASS（行为符合预期）或 FAIL（复现出问题），并打印原始响应/状态码。
"""
import concurrent.futures, json, sys, urllib.request, urllib.error

BASE = "http://127.0.0.1:8080/api"
results = []
def check(name, cond, detail=""):
    results.append(bool(cond)); print(("PASS" if cond else "FAIL"), "-", name, str(detail)[:400])
def req(method, path, token=None, body=None):
    """返回 (http_status, json_or_text)。"""
    r = urllib.request.Request(BASE + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read())
        except Exception: return e.code, {"error": "non-json"}
    except Exception as e:
        return 0, {"error": str(e)}
def login(a, p="123456"):
    s, d = req("POST", "/login", body={"account": a, "password": p})
    assert d.get("ok"), d; return d["token"]
def my(t): return req("GET", "/my", token=t)[1]
def mine_for(t, aid):
    rows = [p for p in my(t) if p["activity_id"] == aid]; return rows[0] if rows else None
def make_activity(tok, title, cap=2):
    s, d = req("POST", "/activities", token=tok, body={
        "title": title, "category": "其他", "description": "审计测试",
        "conditions": "", "location": "线上", "capacity": cap,
        "deadline": "2026-11-07T20:00:00", "start_time": "2026-11-08T20:00:00", "end_time": "2026-11-08T22:00:00"})
    assert d.get("ok"), d; return d["id"]

# --- 0. 基线：先登录（种子已存在），再用演示重置恢复干净状态 ---
try:
    org = login("organizer")
except Exception:
    # 空库极端情况：先注册一个账号再登录（reset 修复后也需要这个分支）
    req("POST", "/register", body={"account": "auditboot", "password": "12345678", "nickname": "审计"})
    org = login("auditboot", "12345678")
s, _ = req("POST", "/demo/reset", token=org)
print("reset with token:", s)
org = login("organizer")

# --- 1. 注册：昵称超长应报昵称错误，而不是“账号已存在” ---
s, d = req("POST", "/register", body={"account": "auditlong1", "password": "12345678", "nickname": "x" * 31})
check("A1 注册昵称31字应400且不误报账号已存在", s == 400 and "账号已存在" not in str(d.get("error")), (s, d))
s, d = req("POST", "/register", body={"account": "auditlong1", "password": "12345678", "nickname": "x" * 31})
check("A1b 第二次同样注册仍不误报账号已存在（未产生脏数据）", s == 400 and "账号已存在" not in str(d.get("error")), (s, d))

# --- 2. 创建活动：非法入参应400，而不是500 ---
for name, body in [
    ("A2 空标题", {"title": "", "category": "其他", "description": "", "conditions": "", "location": "线上", "capacity": 2,
                    "deadline": "2026-11-07T20:00:00", "start_time": "2026-11-08T20:00:00", "end_time": "2026-11-08T22:00:00"}),
    ("A3 缺少地点", {"title": "缺地点", "category": "其他", "description": "", "conditions": "", "capacity": 2,
                    "deadline": "2026-11-07T20:00:00", "start_time": "2026-11-08T20:00:00", "end_time": "2026-11-08T22:00:00"}),
    ("A4 非法类别", {"title": "非法类别", "category": "不存在", "description": "", "conditions": "", "location": "线上", "capacity": 2,
                    "deadline": "2026-11-07T20:00:00", "start_time": "2026-11-08T20:00:00", "end_time": "2026-11-08T22:00:00"}),
    ("A5 缺少容量", {"title": "缺容量", "category": "其他", "description": "", "conditions": "", "location": "线上",
                    "deadline": "2026-11-07T20:00:00", "start_time": "2026-11-08T20:00:00", "end_time": "2026-11-08T22:00:00"}),
]:
    s, d = req("POST", "/activities", token=org, body=body)
    check(name + "应返回400", s == 400, (s, d))

# --- 3. 并发创建：返回的 id 必须与自己创建的标题对应 ---
def create_one(i):
    s, d = req("POST", "/activities", token=org, body={
        "title": f"并发创建-{i}", "category": "其他", "description": "", "conditions": "", "location": "线上", "capacity": 2,
        "deadline": "2026-11-07T20:00:00", "start_time": "2026-11-08T20:00:00", "end_time": "2026-11-08T22:00:00"})
    return i, d.get("id")
with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
    pairs = list(ex.map(create_one, range(10)))
mismatch = []
for i, aid in pairs:
    s, d = req("GET", f"/activities/{aid}", token=org)
    if d.get("title") != f"并发创建-{i}":
        mismatch.append((i, aid, d.get("title")))
check("A6 并发创建10个，返回id与标题一一对应", not mismatch, f"mismatch={mismatch} pairs={pairs}")
check("A6b 并发创建返回id互不相同", len({aid for _, aid in pairs}) == 10, pairs)

# --- 4. 接受 vs 退出并发：是否出现 500 / 死锁 ---
deadlock_hits = 0; rounds = 0
for rnd in range(12):
    aid = make_activity(org, f"死锁测试-{rnd}", cap=1)
    t1, t2 = login("demo01"), login("demo02")
    req("POST", f"/activities/{aid}/signup", token=t1)  # demo01 正式
    req("POST", f"/activities/{aid}/signup", token=t2)  # demo02 候补
    p1 = mine_for(t1, aid)
    req("POST", f"/participations/{p1['id']}/withdraw", token=t1)  # demo02 被邀请
    m2 = mine_for(t2, aid)
    inv, part = m2["invitation_id"], m2["id"]
    assert inv and m2["status"] == "INVITED", m2
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
        f_acc = ex.submit(req, "POST", f"/invitations/{inv}/accept", t2)
        f_wd = ex.submit(req, "POST", f"/participations/{part}/withdraw", t2)
        sa, da = f_acc.result(); sw, dw = f_wd.result()
    rounds += 1
    if sa >= 500 or sw >= 500:
        deadlock_hits += 1
        print(f"  round {rnd}: accept={sa} {da} withdraw={sw} {dw}")
check("A7 接受与退出并发12轮不应出现500（死锁）", deadlock_hits == 0, f"500轮数={deadlock_hits}/{rounds}")

# --- 5. 演示重置后旧 token 是否可冒用新注册用户 ---
s, _ = req("POST", "/register", body={"account": "audituserA", "password": "12345678", "nickname": "A"})
tokA = login("audituserA", "12345678")
s, meA = req("GET", "/me", token=tokA)
old_id = meA["user"]["id"]
req("POST", "/demo/reset", token=org)  # 修复前：无需 token 也能重置；修复后：需登录
# 重新登录组织者（修复后旧 token 已失效，必须重新登录）
org = login("organizer")
s, _ = req("POST", "/register", body={"account": "audituserB", "password": "12345678", "nickname": "B"})
s, meB = req("GET", "/me", token=tokA)
check("A8 重置后旧token不应冒用新用户", not (s == 200 and meB.get("user", {}).get("account") == "audituserB"),
      f"旧token调用 /me: status={s} user={meB.get('user')} (旧id={old_id})")
s, _ = req("POST", "/demo/reset")  # 匿名重置
check("A9 匿名调用演示重置应被拒绝", s in (400, 401, 403), f"status={s}")
org = login("organizer")

# --- 6. 停用账号后旧 token 是否仍可操作 ---
req("POST", "/register", body={"account": "auditdis", "password": "12345678", "nickname": "D"})
tokD = login("auditdis", "12345678")
# 通过 psql 把该账号置为 DISABLED（仅改测试账号，不动其他数据）
import subprocess, os
env = dict(os.environ, LD_LIBRARY_PATH="/tmp/pgsql/usr/lib/x86_64-linux-gnu")
subprocess.run(["/tmp/pgsql/usr/lib/postgresql/16/bin/psql", "-h", "/tmp/pgrun", "-p", "5433",
                "-U", "postgres", "-d", "campus_buddy", "-c",
                "UPDATE app_user SET status='DISABLED' WHERE account='auditdis';"],
               env=env, capture_output=True)
s, d = req("POST", "/activities", token=tokD, body={
    "title": "停用账号创建", "category": "其他", "description": "", "conditions": "", "location": "线上", "capacity": 2,
    "deadline": "2026-11-07T20:00:00", "start_time": "2026-11-08T20:00:00", "end_time": "2026-11-08T22:00:00"})
check("A10 停用账号的旧token不应再创建活动", s in (400, 401, 403), (s, d))
s, d = req("POST", "/login", body={"account": "auditdis", "password": "12345678"})
check("A10b 停用账号重新登录应失败（既有行为对照）", d.get("ok") is False, (s, d))

# --- 7. 不存在记录应400而不是500 ---
s, d = req("GET", "/activities/999999", token=org)
check("A11 不存在活动详情应400而不是500", s == 400, (s, d))
s, d = req("POST", "/participations/999999/withdraw", token=org)
check("A12 不存在参与记录退出应400而不是500", s == 400, (s, d))

print(f"\n共 {len(results)} 项，失败 {results.count(False)} 项")
sys.exit(1 if False in results else 0)
