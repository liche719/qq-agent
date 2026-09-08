import json
from pathlib import Path
from urllib.parse import unquote
from playwright.sync_api import sync_playwright


def test_dashboard():
    static = Path(__file__).resolve().parents[1] / 'main' / 'resources' / 'static'
    user_id = "user'\"><img src=x onerror=alert(1)>"
    errors = []
    requests = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True)
        page = browser.new_page()
        page.on('pageerror', lambda error: errors.append(str(error)))

        def route_request(route):
            path = route.request.url.split('8080', 1)[1]
            if path == '/admin.html':
                route.fulfill(path=str(static / 'admin.html'), content_type='text/html; charset=utf-8')
                return
            if path == '/admin.js':
                route.fulfill(path=str(static / 'admin.js'), content_type='text/javascript; charset=utf-8')
                return
            requests.append(path)
            if path == '/api/admin/overview':
                data = {'status': 'UP', 'qq': 'DOWN', 'users': 1, 'alerts': [user_id]}
            elif path == '/api/admin/metrics/history':
                data = []
            elif path == '/api/admin/users':
                data = [{'userId': user_id, 'lastSeenAt': 'today', 'channel': 'qq'}]
            elif path.startswith('/api/admin/users/'):
                assert unquote(path.rsplit('/', 1)[1]) == user_id
                data = {'coreMemories': [{'content': '测试记忆原文'}], 'conversations': []}
            elif path.startswith('/api/admin/tasks'):
                data = {'items': [{'taskId': 'task', 'status': 'UNKNOWN_RESULT', 'replaySafe': 'false'}]}
            elif path.startswith('/api/admin/logs'):
                data = [{'detail': user_id, 'action': 'TEST'}]
            else:
                route.abort()
                return
            route.fulfill(content_type='application/json', body=json.dumps(data))

        page.route('http://127.0.0.1:8080/**', route_request)
        page.goto('http://127.0.0.1:8080/admin.html')
        page.wait_for_load_state('networkidle')
        assert requests == [], 'No authentication attempts before key entry'
        page.locator('#key').fill('test-key')
        page.locator('#refresh').click()
        page.get_by_text('数据更新时间 / 最后成功刷新：', exact=False).wait_for()
        page.locator('[data-tab=usersTab]').click()
        page.get_by_role('button', name='查看记忆与详情').click()
        page.locator('#userDetail summary').first.wait_for()
        page.locator('#userDetail summary').first.click()
        assert page.get_by_text('测试记忆原文', exact=False).is_visible()
        assert page.locator('img').count() == 0
        page.locator('[data-tab=tasks]').click()
        page.get_by_text('UNKNOWN_RESULT', exact=True).last.wait_for()
        assert page.get_by_role('button', name='安全重试').count() == 0
        page.locator('[data-tab=logs]').click()
        page.get_by_text('TEST', exact=True).wait_for()
        assert page.locator('img').count() == 0
        page.locator('#interval').select_option('0')
        assert page.evaluate('localStorage.length') == 0
        assert errors == [], errors
        browser.close()
    print('PASS: dashboard detail, text safety, retry eligibility, pause, key storage, console')


if __name__ == '__main__':
    test_dashboard()
