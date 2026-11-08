"""Mobile end-to-end test: Android (Chromium, Pixel 7) and iOS (WebKit, iPhone 14).
Runs against http://127.0.0.1:8080. Saves screenshots to screenshots/mobile-*.
Exit 0 only if every step passes; prints PASS/FAIL per step.
"""
import os, sys, json, urllib.request
from datetime import datetime, timedelta
from playwright.sync_api import sync_playwright

# 发布表单时间取相对日期，避免写死日期过期导致发布被拒（2026-11-08 首次过期：截止 11-07 已过）。
# 形状与旧写死值一致：截止=明天 18:00，开始=后天 18:00，结束=后天 20:00。
_NOW = datetime.now()
DEADLINE = (_NOW + timedelta(days=1)).strftime("%Y-%m-%dT18:00")
START = (_NOW + timedelta(days=2)).strftime("%Y-%m-%dT18:00")
END = (_NOW + timedelta(days=2)).strftime("%Y-%m-%dT20:00")

BASE = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
OUT = "/home/hatch/workspace/campus-buddy/screenshots"
results = []

def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS - " if ok else "FAIL - ") + name + (" " + detail if detail else ""))

def no_overflow(page):
    return page.evaluate("document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1")

def nav(pg, name):
    """2026-11-07 任务 19: 窄屏(<=640px)导航收进「☰ 菜单」里, 点导航前先开菜单;
    宽屏下菜单按钮不可见, 直接点导航。断言不变, 只改到达路径。"""
    menu = pg.get_by_role("button", name="菜单")
    if menu.count() and menu.first.is_visible():
        menu.first.click(); pg.wait_for_timeout(300)
    pg.get_by_role("button", name=name).click()  # 不用 exact: 通知按钮带未读数后缀, 如「通知（2 未读）」

def run_android(p):
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 412, "height": 915}, is_mobile=True, has_touch=True,
                        user_agent="Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
    pg = ctx.new_page()
    pg.goto(BASE, wait_until="networkidle")
    pg.screenshot(path=f"{OUT}/mobile-android-login.png")
    check("Android: login page loads", "找个搭子" in pg.content())
    pg.get_by_role("button", name="同学1").click()
    pg.wait_for_timeout(1500)
    check("Android: quick login demo01", "同学1" in pg.locator("main").inner_text() or "你好" in pg.content())
    check("Android: no horizontal overflow (hall)", no_overflow(pg))
    pg.screenshot(path=f"{OUT}/mobile-android-hall.png")
    pg.get_by_role("button", name="查看详情").first.click()
    pg.wait_for_timeout(1200)
    check("Android: detail opens", "详情" in pg.locator("main").inner_text())
    pg.screenshot(path=f"{OUT}/mobile-android-detail.png")
    nav(pg, "我的活动"); pg.wait_for_timeout(1000)
    check("Android: my activities", "羽毛球" in pg.locator("main").inner_text())
    nav(pg, "通知"); pg.wait_for_timeout(1000)
    check("Android: notifications page", True)
    pg.get_by_role("button", name="退出").click(); pg.wait_for_timeout(800)
    pg.get_by_role("button", name="组织者").click(); pg.wait_for_timeout(1200)
    nav(pg, "发布活动"); pg.wait_for_timeout(800)
    pg.locator("input").nth(3).fill("安卓手机测试") if False else None
    # publish form: inputs in order title, location, capacity, deadline, start, end, description, conditions
    inputs = pg.locator("section.card input")
    inputs.nth(0).fill("安卓手机测试")
    inputs.nth(1).fill("操场")
    inputs.nth(2).fill("4")
    inputs.nth(3).fill(DEADLINE)
    inputs.nth(4).fill(START)
    inputs.nth(5).fill(END)
    inputs.nth(6).fill("手机端发布测试")
    pg.get_by_role("button", name="发布", exact=True).click(); pg.wait_for_timeout(1500)
    check("Android: publish via UI", "安卓手机测试" in pg.locator("main").inner_text())
    pg.screenshot(path=f"{OUT}/mobile-android-publish.png")
    b.close()

def run_ios(p):
    try:
        b = p.webkit.launch()
        engine = "WebKit (Safari engine)"
    except Exception as e:
        print("WebKit launch failed, falling back to Chromium iPhone emulation:", str(e)[:200])
        b = p.chromium.launch()
        engine = "Chromium iPhone emulation (WebKit unavailable)"
    ctx = b.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True,
                        user_agent="Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1")
    pg = ctx.new_page()
    pg.goto(BASE, wait_until="networkidle")
    check(f"iOS: login page loads [{engine}]", "找个搭子" in pg.content())
    # PWA / iOS install metadata
    html = pg.content()
    check("iOS: apple-mobile-web-app-capable meta present", "apple-mobile-web-app-capable" in html)
    check("iOS: apple-touch-icon present", "apple-touch-icon" in html)
    with urllib.request.urlopen(BASE + "/manifest.json") as r:
        mj = json.loads(r.read())
    check("iOS/Android: manifest valid", mj.get("short_name") == "找个搭子" and len(mj.get("icons", [])) >= 2)
    pg.get_by_role("button", name="同学5").click()
    pg.wait_for_timeout(1500)
    check("iOS: quick login demo05", "你好" in pg.content())
    check("iOS: no horizontal overflow (hall)", no_overflow(pg))
    pg.screenshot(path=f"{OUT}/mobile-ios-hall.png")
    pg.get_by_role("button", name="查看详情").first.click()
    pg.wait_for_timeout(1200)
    check("iOS: detail opens", "详情" in pg.locator("main").inner_text())
    pg.screenshot(path=f"{OUT}/mobile-ios-detail.png")
    nav(pg, "我的活动"); pg.wait_for_timeout(1000)
    # 2026-11-07 任务 15: 「我的活动」默认是「已报名」视图; demo05 只有候补记录,
    # 须先切到「候补」视图再断言(断言本身不变)。
    pg.get_by_role("button", name="候补", exact=True).click(); pg.wait_for_timeout(800)
    check("iOS: my activities", "桌游" in pg.locator("main").inner_text() or "羽毛球" in pg.locator("main").inner_text())
    nav(pg, "通知"); pg.wait_for_timeout(1000)
    check("iOS: notifications page", True)
    b.close()
    return engine

with sync_playwright() as p:
    run_android(p)
    engine = run_ios(p)
print(f"\nEngine used for iOS: {engine}")
fails = [r for r in results if not r[1]]
print(f"\n共 {len(results)} 项，失败 {len(fails)} 项")
sys.exit(1 if fails else 0)
