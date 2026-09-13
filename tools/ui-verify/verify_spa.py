"""验证 Vue 单页应用：登录（含记住账号密码）、白色玻璃主题、各页签、聊天式记忆、手机端。

BASE 由环境变量指定：默认本机代理（http://127.0.0.1:8899），
也可设为 https://120.25.170.92:8443 直接验证公网部署。
"""
import os
import sys
from playwright.sync_api import sync_playwright

BASE = os.environ.get("SPA_BASE", "http://127.0.0.1:8899")
INSECURE = BASE.startswith("https")
KEY = os.environ["WG_PW"]
USERNAME = os.environ.get("ADMIN_USERNAME", "rootlcw")
OUT = os.path.dirname(os.path.abspath(__file__))
TAG = os.environ.get("SPA_TAG", "v5")
EXPECT_ICP = os.environ.get("EXPECT_ICP", "")   # 设成备案号可验证页脚显示；不设则断言页脚不渲染
FAIL = []


def check(name, ok, detail=""):
    print(("  [通过] " if ok else "  [失败] ") + name + (("  → " + str(detail)) if detail else ""))
    if not ok:
        FAIL.append(name)


def collect(page, sink):
    page.on("pageerror", lambda e: sink.append(str(e)))
    page.on("console", lambda m: sink.append(m.text) if m.type == "error" else None)


def shot(page, name):
    page.screenshot(path=os.path.join(OUT, TAG + "-" + name + ".png"), full_page=True)


with sync_playwright() as p:
    browser = p.chromium.launch()

    print("== 桌面端 1440x1000（%s）==" % BASE)
    ctx = browser.new_context(viewport={"width": 1440, "height": 1000}, ignore_https_errors=INSECURE)
    page = ctx.new_page()
    errors = []
    collect(page, errors)
    page.goto(BASE + "/", wait_until="networkidle")
    page.wait_for_timeout(1800)

    check("自动进入登录页", "#/login" in page.url, page.url)
    check("登录页有账号与密码两个输入框", page.is_visible("#username") and page.is_visible("#password"))
    check("账号默认填 rootlcw", page.input_value("#username") == USERNAME, page.input_value("#username"))
    check("有“记住账号密码”勾选", page.is_visible("#remember"))
    body = page.evaluate("""() => { const s = getComputedStyle(document.body);
        return { color: s.color, image: s.backgroundImage, grid: getComputedStyle(document.body, '::before').backgroundImage }; }""")
    check("主色为白：深色文字 + 浅色渐变底",
          body["color"] == "rgb(27, 35, 51)" and "radial-gradient" in body["image"], body["color"])
    check("存在网格背景层", body["grid"].count("gradient") >= 3)
    card = page.evaluate("""() => { const s = getComputedStyle(document.querySelector('.login-card'));
        return { image: s.backgroundImage, blur: (s.backdropFilter || '') + ' ' + (s.webkitBackdropFilter || ''),
                 radius: s.borderTopLeftRadius }; }""")
    check("登录卡片是白色半透明玻璃", "gradient" in card["image"] and "rgba(255, 255, 255" in card["image"], card["image"][:60])
    check("启用了背景模糊", "blur" in card["blur"], card["blur"])
    check("圆角 >= 16px", float(card["radius"].replace("px", "")) >= 16, card["radius"])
    button = page.evaluate("() => getComputedStyle(document.querySelector('.submit')).backgroundImage")
    check("主按钮用淡蓝到白渐变", "gradient" in button and "rgb(207, 224, 255)" in button, button[:70])
    shot(page, "login-desktop")

    print("\n== ICP 备案号页脚 ==")
    if EXPECT_ICP:
        visible = page.is_visible(".site-footer")
        href = page.get_attribute(".site-footer a", "href") if visible else ""
        check("配置了备案号时页脚显示并链接工信部",
              visible and EXPECT_ICP in page.inner_text(".site-footer") and (href or "").startswith("https://beian.miit.gov.cn"),
              page.inner_text(".site-footer") if visible else "（页脚不存在）")
    else:
        check("未配置备案号时页脚不渲染", not page.is_visible(".site-footer"))

    print("\n== 黑白主题切换（登录页）==")
    check("默认白色主题", page.evaluate("() => document.documentElement.dataset.theme") == "light")
    check("登录页有主题切换按钮", page.is_visible("button.theme-toggle"))
    page.click("button.theme-toggle")
    page.wait_for_timeout(600)
    dark_login = page.evaluate("""() => { const s = getComputedStyle(document.body);
        const c = getComputedStyle(document.querySelector('.login-card'));
        return { mode: document.documentElement.dataset.theme, color: s.color, image: s.backgroundImage,
                 card: c.backgroundImage, blur: c.backdropFilter }; }""")
    check("切到黑色主题", dark_login["mode"] == "dark", dark_login["mode"])
    check("黑色主题底色转为近黑",
          "rgb(12, 15, 22)" in dark_login["image"] and dark_login["color"] == "rgb(241, 244, 250)",
          dark_login["color"])
    check("黑色主题仍是半透明玻璃",
          "rgba(255, 255, 255, 0.075)" in dark_login["card"] and "blur" in dark_login["blur"], dark_login["card"][:60])
    shot(page, "login-dark")
    page.reload(wait_until="networkidle")
    page.wait_for_timeout(1200)
    check("主题选择刷新后仍生效", page.evaluate("() => document.documentElement.dataset.theme") == "dark")
    page.click("button.theme-toggle")
    page.wait_for_timeout(500)
    check("可以切回白色主题", page.evaluate("() => document.documentElement.dataset.theme") == "light")

    print("\n== 登录校验 ==")
    page.fill("#password", "definitely-wrong")
    page.click("button[type=submit]")
    page.wait_for_timeout(2400)
    check("错误密码被拒绝且有提示", "#/login" in page.url and "不正确" in page.inner_text(".error"),
          page.inner_text(".error").strip())

    page.fill("#username", "someone-else")
    page.fill("#password", KEY)
    page.click("button[type=submit]")
    page.wait_for_timeout(2400)
    check("错误账号被拒绝", "#/login" in page.url and "不正确" in page.inner_text(".error"),
          page.inner_text(".error").strip())

    page.fill("#username", USERNAME)
    page.fill("#password", KEY)
    # 显式勾上「记住账号密码」：这个勾选框现在**默认是不勾的**（默认勾选会把面板唯一凭据
    # 明文写进 localStorage）。后面的「凭据持久化」断言和手机端那一段都依赖 localStorage
    # 里真的有凭据（storage_state() 只带 localStorage、不带 sessionStorage），所以这里必须自己勾。
    page.check("#remember")
    errors.clear()          # 上面两次故意输错会各记一条 401 资源错误，属预期
    page.click("button[type=submit]")
    page.wait_for_selector(".stat .v", timeout=25000)
    page.wait_for_function("() => document.querySelectorAll('.stat .v')[0].innerText.trim() !== '\u2014'", timeout=25000)
    page.wait_for_timeout(3500)
    check("正确账号密码进入面板", "#/dashboard" in page.url, page.url)
    values = page.eval_on_selector_all(".stat .v", "els => els.map(e => e.innerText.trim())")
    check("应用状态中文正常", page.inner_text(".pill").strip() == "应用 正常", page.inner_text(".pill"))
    check("QQ 通道中文正常", page.eval_on_selector_all(".pill", "els => els[1].innerText.trim()") == "QQ 通道 正常")
    check("用户数非空", values[2] not in ("", "—"), values)
    check("运行时指标非空",
          page.eval_on_selector_all(".kv-item .v", "els => els.filter(e => e.innerText.trim() !== '\u2014').length") >= 6)
    check("趋势图有柱", page.eval_on_selector_all(".chart-bar", "els => els.length") > 1,
          page.eval_on_selector_all(".chart-bar", "els => els.length"))
    check("无横向溢出", page.evaluate("() => document.documentElement.scrollWidth - window.innerWidth") <= 1)
    check("无 JS 报错", not errors, errors)
    shot(page, "panel-desktop")

    print("\n== 各页签 ==")
    for label in ("QQ 通道", "模型与搜索", "任务", "用户与记忆", "日志"):
        page.click(".tab:has-text('%s')" % label)
        page.wait_for_timeout(2800)
        if label == "QQ 通道":
            filled = page.eval_on_selector_all(".kv-item .v", "els => els.filter(e => e.innerText.trim() !== '\u2014').length")
            check("QQ 通道指标有真实数据", filled >= 5, filled)
            check("有“发送测试告警”按钮", page.is_visible("button:has-text('发送测试告警')"))
            shot(page, "panel-qq")
        elif label == "模型与搜索":
            labels = page.eval_on_selector_all(".kv-item .k", "els => els.map(e => e.innerText.trim())")
            check("有模型调用指标", any("调用次数" in text or "成功率" in text for text in labels), labels[:4])
            check("有搜索指标", any("搜索次数" in text for text in labels), labels)
            shot(page, "panel-llm")
        elif label == "任务":
            rows = page.eval_on_selector_all(".table-wrap tbody tr", "els => els.length")
            check("任务表格有数据行", rows > 0, "%d 行" % rows)
            check("任务状态显示中文",
                  any(word in page.inner_text(".table-wrap") for word in ("运行中", "失败", "结果未知", "已回复")))
        elif label == "用户与记忆":
            rows = page.eval_on_selector_all(".table-wrap tbody tr", "els => els.length")
            check("用户列表每个用户一条", rows == int(values[2].replace(",", "")), "表 %d 行 / 卡片 %s" % (rows, values[2]))
            page.click(".table-wrap button.btn-link")
            page.wait_for_timeout(3000)
            check("打开聊天式会话视图", page.is_visible(".chat"))
            bubbles = page.eval_on_selector_all(".chat .bubble", "els => els.length")
            check("会话气泡有内容", bubbles > 0, "%d 条" % bubbles)
            roles = page.eval_on_selector_all(".chat .bubble", "els => [...new Set(els.map(e => e.className))]")
            check("区分用户与机器人气泡", any("user" in r for r in roles) and any("assistant" in r for r in roles), roles)
            scrollable = page.evaluate("() => { const b = document.querySelector('.chat'); return b.scrollHeight > b.clientHeight; }")
            check("会话区可上下滚动", scrollable)
            check("有“加载更早的消息”入口", page.is_visible("button:has-text('加载更早')")
                  or "已经是最早的消息" in page.inner_text(".chat"))
            for tab in ("长期记忆", "提醒任务"):
                page.click(".segment button:has-text('%s')" % tab)
                page.wait_for_timeout(1200)
                check("%s 分段可切换" % tab, page.is_visible(".segment button.active"))
            page.click(".segment button:has-text('聊天记录')")
            page.wait_for_timeout(1000)
            shot(page, "panel-user-chat")
        else:
            rows = page.eval_on_selector_all(".table-wrap tbody tr", "els => els.length")
            check("日志表有数据行", rows > 0, "%d 行" % rows)
    check("各页签切换无 JS 报错", not errors, errors)

    print("\n== 黑色主题（面板）==")
    check("面板顶栏有主题切换按钮", page.is_visible("button.theme-toggle"))
    page.click("button.theme-toggle")
    page.wait_for_timeout(1500)
    dark_panel = page.evaluate("""() => { const t = getComputedStyle(document.querySelector('.topbar'));
        const th = getComputedStyle(document.querySelector('th'));
        return { mode: document.documentElement.dataset.theme, bar: t.backgroundImage, inset: t.boxShadow,
                 th: th.backgroundColor, ink: getComputedStyle(document.body).color }; }""")
    check("面板切到黑色主题", dark_panel["mode"] == "dark", dark_panel["mode"])
    check("黑色主题下玻璃与文字都换了色",
          "rgba(255, 255, 255, 0.075)" in dark_panel["bar"] and dark_panel["ink"] == "rgb(241, 244, 250)",
          dark_panel["bar"][:60])
    check("黑色主题下表头不再发白", "16, 20, 28" in dark_panel["th"], dark_panel["th"])
    accent = page.evaluate("() => getComputedStyle(document.querySelector('.btn-primary')).backgroundImage")
    check("黑色主题下强调色仍是淡蓝到白", "rgb(157, 188, 255)" in accent, accent[:70])
    check("黑色主题无 JS 报错", not errors, errors)
    shot(page, "panel-dark")
    page.click(".tab:has-text('用户与记忆')")
    page.wait_for_timeout(2800)
    page.click(".table-wrap button.btn-link")
    page.wait_for_timeout(3200)
    check("黑色主题下聊天气泡仍是淡蓝渐变",
          "rgb(157, 188, 255)" in page.evaluate("() => getComputedStyle(document.querySelector('.bubble.user .text')).backgroundImage"))
    shot(page, "panel-user-chat-dark")
    page.click("button.theme-toggle")
    page.wait_for_timeout(1000)
    check("切回白色主题", page.evaluate("() => document.documentElement.dataset.theme") == "light")

    print("\n== 记住账号密码 ==")
    check("勾选后凭据写入本地存储（关标签页也不丢）",
          page.evaluate("() => Boolean(localStorage.getItem('admin.auth'))"))
    page.reload(wait_until="networkidle")
    page.wait_for_timeout(2500)
    check("刷新后仍在面板（凭据已持久化）", "#/dashboard" in page.url, page.url)
    state = ctx.storage_state()

    print("\n== 手机端 390x844 ==")
    mobile_ctx = browser.new_context(ignore_https_errors=INSECURE, viewport={"width": 390, "height": 844},
                                     device_scale_factor=2, is_mobile=True, has_touch=True,
                                     storage_state=state)
    mobile = mobile_ctx.new_page()
    mobile_errors = []
    collect(mobile, mobile_errors)
    mobile.goto(BASE + "/", wait_until="networkidle")
    mobile.wait_for_timeout(2500)
    check("手机端无横向溢出", mobile.evaluate("() => document.documentElement.scrollWidth - window.innerWidth") <= 1)
    check("手机端概览卡 2 列",
          mobile.evaluate("() => getComputedStyle(document.querySelector('.grid-4')).gridTemplateColumns.split(' ').length") == 2)
    mobile.click(".tab:has-text('用户与记忆')")
    mobile.wait_for_timeout(3000)
    mobile.click(".table-wrap button.btn-link")
    mobile.wait_for_timeout(3200)
    check("手机端聊天视图可用", mobile.is_visible(".chat"))
    check("手机端聊天气泡有内容",
          mobile.eval_on_selector_all(".chat .bubble", "els => els.length") > 0,
          mobile.eval_on_selector_all(".chat .bubble", "els => els.length"))
    check("手机端聊天视图无横向溢出",
          mobile.evaluate("() => document.documentElement.scrollWidth - window.innerWidth") <= 1)
    check("手机端无 JS 报错", not mobile_errors, mobile_errors)
    mobile.screenshot(path=os.path.join(OUT, TAG + "-panel-mobile.png"), full_page=True)
    mobile.click("button.theme-toggle")
    mobile.wait_for_timeout(1200)
    check("手机端可切黑色主题", mobile.evaluate("() => document.documentElement.dataset.theme") == "dark")
    check("手机端黑色主题无横向溢出",
          mobile.evaluate("() => document.documentElement.scrollWidth - window.innerWidth") <= 1)
    mobile.screenshot(path=os.path.join(OUT, TAG + "-panel-mobile-dark.png"), full_page=True)

    print("\n== 退出登录 ==")
    page.click("button:has-text('退出登录')")
    page.wait_for_timeout(1800)
    check("退出后回到登录页", "#/login" in page.url, page.url)
    check("账号仍被记住，但凭据已清除",
          page.input_value("#username") == USERNAME
          and page.evaluate("() => !localStorage.getItem('admin.auth') && !sessionStorage.getItem('admin.auth')"))

    browser.close()

print("\n===== 结论 =====")
print("全部通过" if not FAIL else "存在失败项：" + "、".join(FAIL))
sys.exit(1 if FAIL else 0)
