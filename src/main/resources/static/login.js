'use strict';

/* 登录页：口令存在 sessionStorage，随每次请求以 X-Agent-Admin-Key 头发给后端校验。
   后端连续失败 5 次会按来源 IP 暂时封禁，所以这里不重试、只提示。 */

const KEY_STORAGE = 'admin.key';
const keyInput = document.getElementById('key');
const message = document.getElementById('msg');
const submit = document.getElementById('submit');
const form = document.getElementById('form');

function show(text, bad) {
    message.className = 'msg' + (bad ? ' bad' : '');
    message.textContent = '';
    if (text) message.append(bad ? Object.assign(document.createElement('span'), { className: 'box', textContent: text }) : text);
}

function describe(status) {
    if (status === 401) return '口令不正确，请重新输入。';
    if (status === 403) return '当前来源不允许访问运维面板。';
    if (status === 429) return '尝试次数过多，已暂停访问，请稍后再试。';
    if (status >= 500) return '服务暂时不可用，请稍后再试。';
    return '登录失败（HTTP ' + status + '）。';
}

async function verify(key) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
        const response = await fetch('/api/admin/overview', {
            headers: { 'X-Agent-Admin-Key': key },
            cache: 'no-store',
            signal: controller.signal
        });
        return response.status;
    } catch (error) {
        return error.name === 'AbortError' ? 'timeout' : 'network';
    } finally {
        clearTimeout(timer);
    }
}

function enter() {
    window.location.replace('/admin.html');
}

form.addEventListener('submit', async event => {
    event.preventDefault();
    const key = keyInput.value.trim();
    if (!key) {
        show('请输入访问口令。', true);
        keyInput.focus();
        return;
    }
    submit.disabled = true;
    submit.textContent = '正在验证…';
    show('', false);
    const status = await verify(key);
    submit.disabled = false;
    submit.textContent = '进入面板';
    if (status === 200) {
        try { sessionStorage.setItem(KEY_STORAGE, key); } catch (ignored) { /* 隐私模式下忽略 */ }
        enter();
        return;
    }
    if (status === 'timeout') { show('连接超时，请检查网络后重试。', true); return; }
    if (status === 'network') { show('网络不可达，请检查网络后重试。', true); return; }
    show(describe(status), true);
    keyInput.select();
});

/* 若本标签页已存有口令，直接尝试进入，避免重复输入 */
(async function boot() {
    let saved = '';
    try { saved = sessionStorage.getItem(KEY_STORAGE) || ''; } catch (ignored) { /* 忽略 */ }
    if (new URLSearchParams(window.location.search).get('expired') === '1') {
        try { sessionStorage.removeItem(KEY_STORAGE); } catch (ignored) { /* 忽略 */ }
        show('登录状态已失效，请重新输入口令。', true);
        return;
    }
    if (!saved) return;
    show('正在使用已保存的口令进入…', false);
    const status = await verify(saved);
    if (status === 200) { enter(); return; }
    try { sessionStorage.removeItem(KEY_STORAGE); } catch (ignored) { /* 忽略 */ }
    show(status === 401 ? '已保存的口令已失效，请重新输入。' : describe(status), true);
})();
