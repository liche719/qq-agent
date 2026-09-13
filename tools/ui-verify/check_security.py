"""用真实 Chromium 直连（禁用本机代理）检查 liche.cloud:8443 的安全状态：
证书是否被信任、Chrome 判定的 securityState 与原因、有没有混合内容。
"""
import os
from playwright.sync_api import sync_playwright

BASE = os.environ.get("SPA_BASE", "https://liche.cloud")
KEY = os.environ["WG_PW"]

with sync_playwright() as p:
    browser = p.chromium.launch(args=["--no-proxy-server"])
    ctx = browser.new_context(ignore_https_errors=False, viewport={"width": 1280, "height": 900})
    page = ctx.new_page()

    cdp = ctx.new_cdp_session(page)
    states, console, http_requests = [], [], []
    cdp.send("Security.enable")
    cdp.on("Security.securityStateChanged", lambda e: states.append(e))
    page.on("console", lambda m: console.append((m.type, m.text)))
    page.on("request", lambda r: http_requests.append(r.url) if r.url.startswith("http://") else None)

    print("== 证书是否被浏览器接受 ==")
    try:
        page.goto(BASE + "/", wait_until="networkidle", timeout=30000)
        print("  页面加载成功，未出现证书错误 ✔  当前地址:", page.url)
    except Exception as e:
        print("  加载失败 ->", str(e)[:300])

    page.wait_for_timeout(1500)
    print("\n== Chrome 判定的安全状态 ==")
    for s in states:
        print("  securityState =", s.get("securityState"), " summary =", s.get("summary", ""))
        for ex in s.get("explanations", []):
            print("    原因:", ex.get("securityState"), "|", ex.get("summary", ""), "|", ex.get("description", "")[:120])

    print("\n== 有没有 http:// 资源（混合内容）==")
    print("  http:// 请求数:", len(http_requests))
    for u in http_requests[:10]:
        print("   ", u)

    print("\n== 控制台里的安全相关消息 ==")
    shown = 0
    for t, text in console:
        if any(w in text.lower() for w in ("mixed", "insecure", "certificate", "unsafe", "http")):
            print("  [%s] %s" % (t, text[:200]))
            shown += 1
    if not shown:
        print("  （没有安全相关告警）")

    print("\n== 页面上的表单情况 ==")
    print(page.evaluate("""() => ({
        protocol: location.protocol,
        forms: [...document.forms].map(f => ({action: f.getAttribute('action'), method: f.method})),
        passwordInputs: document.querySelectorAll('input[type=password]').length,
        insecureAttrs: [...document.querySelectorAll('[src],[href]')]
            .map(e => e.getAttribute('src') || e.getAttribute('href'))
            .filter(v => v && v.startsWith('http://'))
    })"""))

    page.screenshot(path=os.path.join(os.path.dirname(os.path.abspath(__file__)), "sec-check.png"), full_page=True)
    browser.close()
