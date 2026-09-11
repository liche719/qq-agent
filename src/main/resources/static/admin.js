'use strict';

/* ---------- 常量 ---------- */
const KEY_STORAGE = 'admin.key';
const LOGIN_PAGE = '/login.html';

/* 接口返回的状态值统一翻成中文，界面不出现英文状态词 */
const ZH = {
    app: { UP: '正常', DEGRADED: '降级', DOWN: '异常' },
    qq: { UP: '正常', DOWN: '异常', DISABLED: '未启用' },
    dep: { UP: '正常', DOWN: '异常', STANDBY: '待机' },
    task: { RUNNING: '运行中', FAILED: '失败', UNKNOWN_RESULT: '结果未知', REPLY_SENT: '已回复' },
    channel: { qq: 'QQ', wechat: '微信', wechat_ilink: '微信', clawbot: '微信', simulator: '模拟器' },
    source: { operation: '操作审计', application: '应用日志' },
    level: { INFO: '信息', WARN: '警告', ERROR: '错误' },
    action: { QQ_RECONNECT: '触发 QQ 重连', TASK_RETRY: '重试任务', CACHE_CLEANUP: '清理缓存' }
};

function zh(group, value) {
    if (value === undefined || value === null || value === '') return '—';
    return (ZH[group] && ZH[group][value]) || String(value);
}

/* ---------- 基础工具 ---------- */
const el = id => document.getElementById(id);

const state = {
    tab: 'overview',
    interval: 10000,
    timer: null,
    tickTimer: null,
    tickStart: Date.now(),
    refreshing: false,
    stopped: false,
    lastSuccess: '',
    taskPage: 0,
    taskSize: 20,
    taskTotal: 0,
    userId: '',
    userPage: 0
};

function accessKey() {
    try { return sessionStorage.getItem(KEY_STORAGE) || ''; } catch (ignored) { return ''; }
}

function leaveToLogin(expired) {
    try { sessionStorage.removeItem(KEY_STORAGE); } catch (ignored) { /* 忽略 */ }
    window.location.replace(LOGIN_PAGE + (expired ? '?expired=1' : ''));
}

function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined && text !== null) element.textContent = String(text);
    return element;
}

function fmtBytes(value) {
    const number = Number(value);
    if (!Number.isFinite(number) || number <= 0) return '—';
    const units = ['字节', 'KB', 'MB', 'GB', 'TB'];
    let index = 0;
    let size = number;
    while (size >= 1024 && index < units.length - 1) { size /= 1024; index++; }
    const text = index === 0 ? String(size) : size.toFixed(size >= 100 ? 0 : 1);
    return text + ' ' + units[index];
}

function fmtNum(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return value === undefined || value === null || value === '' ? '—' : String(value);
    return number.toLocaleString('zh-CN');
}

function fmtTime(value) {
    if (value === undefined || value === null || value === '') return '—';
    const text = String(value);
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(text)) return text;
    const parsed = new Date(text);
    if (Number.isNaN(parsed.getTime())) return text;
    const pad = n => String(n).padStart(2, '0');
    return parsed.getFullYear() + '-' + pad(parsed.getMonth() + 1) + '-' + pad(parsed.getDate()) +
        ' ' + pad(parsed.getHours()) + ':' + pad(parsed.getMinutes()) + ':' + pad(parsed.getSeconds());
}

function fmtMs(value) {
    const number = Number(value);
    return Number.isFinite(number) && number > 0 ? Math.round(number) + ' 毫秒' : '—';
}

/* ---------- 接口 ---------- */
async function api(path, method = 'GET') {
    const key = accessKey();
    if (!key) { leaveToLogin(false); throw new Error('未登录'); }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    let response;
    try {
        response = await fetch('/api/admin' + path, {
            method,
            cache: 'no-store',
            headers: { 'X-Agent-Admin-Key': key },
            signal: controller.signal
        });
    } catch (error) {
        throw new Error(error.name === 'AbortError' ? '请求超时（15 秒）' : '网络不可达');
    } finally {
        clearTimeout(timeout);
    }
    if (!response.ok) {
        const message = response.status === 401 ? '登录状态已失效，请重新输入口令'
            : response.status === 403 ? '当前来源 IP 不允许访问运维面板'
            : response.status === 429 ? '请求过于频繁，已暂停访问，请稍后再试'
            : response.status >= 500 ? '服务端异常（' + response.status + '）'
            : '请求失败（' + response.status + '）';
        const error = new Error(message);
        error.status = response.status;
        throw error;
    }
    return response.json();
}

/* ---------- 渲染零件 ---------- */
function setPill(id, label, tone) {
    const pill = el(id);
    pill.className = 'pill' + (tone ? ' ' + tone : '');
    pill.lastElementChild.textContent = label;
}

function setStat(id, value, meta, tone) {
    const target = el(id);
    target.textContent = value === undefined || value === null || value === '' ? '—' : String(value);
    target.className = 'v' + (tone ? ' ' + tone : '');
    if (el(id + '-meta')) el(id + '-meta').textContent = meta || '';
}

function kv(containerId, rows) {
    const host = el(containerId);
    host.replaceChildren(...rows.map(([label, value]) => {
        const row = node('div', 'row');
        row.append(node('div', 'k', label), node('div', 'v', value));
        return row;
    }));
}

function table(containerId, columns, rows, decorate) {
    const host = el(containerId);
    if (!rows || !rows.length) {
        host.replaceChildren(node('div', 'empty', '暂无数据'));
        return;
    }
    const result = document.createElement('table');
    const head = document.createElement('thead');
    const headRow = document.createElement('tr');
    columns.forEach(column => headRow.append(node('th', null, column[0])));
    if (decorate) headRow.append(node('th', null, '操作'));
    head.append(headRow);
    result.append(head);

    const body = document.createElement('tbody');
    for (const row of rows) {
        const line = document.createElement('tr');
        columns.forEach(column => {
            const cell = node('td', column[2] || null);
            cell.dataset.label = column[0];
            const value = column[3] ? column[3](row) : row[column[1]];
            if (value instanceof window.Node) cell.append(value);
            else cell.textContent = value === undefined || value === null || value === '' ? '—' : String(value);
            line.append(cell);
        });
        if (decorate) {
            const cell = node('td');
            cell.dataset.label = '操作';
            decorate(cell, row);
            line.append(cell);
        }
        body.append(line);
    }
    result.append(body);
    host.replaceChildren(result);
}

function actionButton(parent, label, handler) {
    const control = node('button', 'link', label);
    control.onclick = async () => {
        control.disabled = true;
        try { await handler(); } catch (error) { showError(error); } finally { control.disabled = false; }
    };
    parent.append(control);
}

function outlineButton(parent, label, handler) {
    const control = node('button', 'quiet sm', label);
    control.onclick = async () => {
        control.disabled = true;
        try { await handler(); } catch (error) { showError(error); } finally { control.disabled = false; }
    };
    parent.append(control);
}

function tag(text, tone) {
    return node('span', 'tag' + (tone ? ' ' + tone : ''), text);
}

function toneOf(value) {
    return value === 'UP' ? 'ok' : value === 'DEGRADED' || value === 'STANDBY' || value === 'DISABLED' ? 'warn' : 'bad';
}

function haltAutoRefresh() {
    state.stopped = true;
    clearInterval(state.timer);
    state.timer = null;
    el('interval').value = '0';
    el('tick').style.width = '0%';
}

function showError(error) {
    if (error.status === 401) { leaveToLogin(true); return; }
    if (error.status === 429) haltAutoRefresh();
    const suffix = state.lastSuccess ? ' · 最后成功刷新：' + state.lastSuccess : '';
    const line = el('status');
    line.textContent = '刷新失败：' + error.message + suffix;
    line.className = 'foot stale';
}

/* ---------- 总览 ---------- */
function renderSummary(overview) {
    const tasks = overview.tasks || {};
    const metrics = overview.qqMetrics || {};
    const status = overview.status || '';
    const qq = overview.qq || '';

    setPill('pill-app', '应用 ' + zh('app', status), toneOf(status));
    setPill('pill-qq', 'QQ 通道 ' + zh('qq', qq), toneOf(qq));

    const memory = ['coreMemories', 'workMemories', 'episodes']
        .map(key => Number(overview[key]) || 0).reduce((left, right) => left + right, 0);
    const abnormal = Number(tasks.UNKNOWN_RESULT) || 0;

    setStat('s-app', zh('app', status), '启动于 ' + fmtTime(overview.startedAt), toneOf(status));
    setStat('s-qq', zh('qq', qq),
        '发送成功 ' + fmtNum(metrics.textSendSuccess) + ' · 失败 ' + fmtNum(metrics.textSendFailure),
        toneOf(qq));
    setStat('s-users', fmtNum(overview.users),
        '对话 ' + fmtNum(overview.conversations) + ' · 记忆 ' + fmtNum(memory), '');
    setStat('s-tasks', fmtNum(abnormal),
        '运行中 ' + fmtNum(tasks.RUNNING) + ' · 失败 ' + fmtNum(tasks.FAILED), abnormal > 0 ? 'bad' : 'ok');

    el('overview-json').textContent = JSON.stringify(overview, null, 2);
    el('deps-json').textContent = JSON.stringify(
        { dependencies: overview.dependencies || {}, moduleErrors: overview.moduleErrors || {} }, null, 2);
    el('alerts').replaceChildren(...(overview.alerts || []).map(text => node('div', 'alert', text)));
}

function renderRuntime(overview) {
    const jvm = overview.jvm || {};
    const heapUsed = Number(jvm.heapUsed) || 0;
    const heapMax = Number(jvm.heapMax) || 0;
    const percent = heapMax > 0 ? Math.round(heapUsed / heapMax * 100) : -1;
    const cpu = Number(jvm.systemCpuLoad);
    kv('runtime', [
        ['堆内存占用', fmtBytes(heapUsed) + (percent >= 0 ? ' / ' + fmtBytes(heapMax) : '')],
        ['堆内存比例', percent >= 0 ? percent + '%' : '—'],
        ['线程数', fmtNum(jvm.threads)],
        ['处理器负载', Number.isFinite(cpu) && cpu >= 0 ? Math.round(cpu * 100) + '%' : '—'],
        ['可用处理器', fmtNum(jvm.processors)],
        ['磁盘可用', fmtBytes(jvm.diskFree) + ' / ' + fmtBytes(jvm.diskTotal)],
        ['服务时间', fmtTime(overview.time)]
    ]);
}

function renderCounts(overview) {
    const tasks = overview.tasks || {};
    kv('counts', [
        ['用户', fmtNum(overview.users)],
        ['对话证据', fmtNum(overview.conversations)],
        ['核心记忆', fmtNum(overview.coreMemories)],
        ['工作记忆', fmtNum(overview.workMemories)],
        ['情景记忆', fmtNum(overview.episodes)],
        ['提醒任务', fmtNum(overview.reminders)],
        ['任务·运行中', fmtNum(tasks.RUNNING)],
        ['任务·失败', fmtNum(tasks.FAILED)],
        ['任务·结果未知', fmtNum(tasks.UNKNOWN_RESULT)],
        ['任务·已回复', fmtNum(tasks.REPLY_SENT)]
    ]);
}

function renderQq(overview) {
    const metrics = overview.qqMetrics || {};
    const status = overview.qq;
    const rows = [
        ['网关状态', zh('qq', status)],
        ['文本发送成功', fmtNum(metrics.textSendSuccess)],
        ['文本发送失败', fmtNum(metrics.textSendFailure)],
        ['媒体发送成功', fmtNum(metrics.mediaSendSuccess)],
        ['媒体发送失败', fmtNum(metrics.mediaSendFailure)],
        ['接口错误总数', fmtNum(metrics.apiErrors)],
        ['重连成功 / 失败', fmtNum(metrics.reconnectSuccess) + ' / ' + fmtNum(metrics.reconnectFailure)],
        ['令牌刷新失败', fmtNum(metrics.tokenRefreshFailure)],
        ['心跳失败', fmtNum(metrics.heartbeatFailure)],
        ['网关帧解析失败', fmtNum(metrics.gatewayFrameParseFailure)],
        ['网关鉴权失败', fmtNum(metrics.gatewayIdentifyFailure)],
        ['网关恢复失败', fmtNum(metrics.gatewayResumeFailure)],
        ['引用查询成功 / 失败', fmtNum(metrics.quoteLookupSuccess) + ' / ' + fmtNum(metrics.quoteLookupFailure)],
        ['分片上传失败', fmtNum(metrics.chunkUploadFailure)],
        ['文本发送平均耗时', fmtMs(metrics.textSendAverageMs)],
        ['媒体发送平均耗时', fmtMs(metrics.mediaSendAverageMs)],
        ['连接建立于', fmtTime(metrics.connectedAt)],
        ['最近网关事件', fmtTime(metrics.lastGatewayEventAt)]
    ];
    const byStatus = metrics.apiErrorsByStatus && Object.keys(metrics.apiErrorsByStatus).length
        ? Object.entries(metrics.apiErrorsByStatus).map(([code, count]) => code + ' 共 ' + count + ' 次').join('；')
        : '无';
    rows.push(['接口错误按状态码', byStatus]);
    if (metrics.lastApiError) rows.push(['最近接口错误', String(metrics.lastApiError)]);
    kv('qq-kv', rows);
    el('qq-json').textContent = JSON.stringify(metrics, null, 2);
}

function renderChart(history) {
    const samples = (history || []).slice(-60);
    const bars = el('chart');
    if (!samples.length) {
        bars.replaceChildren(node('div', 'empty', '暂无采样'));
        el('chart-from').textContent = el('chart-peak').textContent = el('chart-to').textContent = '—';
        return;
    }
    const peak = Math.max(1, ...samples.map(sample => Number(sample.jvm && sample.jvm.heapUsed) || 0));
    bars.replaceChildren(...samples.map(sample => {
        const used = Number(sample.jvm && sample.jvm.heapUsed) || 0;
        const bar = node('i', 'bar');
        bar.style.height = Math.max(3, used / peak * 100) + '%';
        bar.title = fmtTime(sample.at) + ' · ' + fmtBytes(used);
        return bar;
    }));
    el('chart-from').textContent = fmtTime(samples[0].at);
    el('chart-peak').textContent = '峰值 ' + fmtBytes(peak);
    el('chart-to').textContent = fmtTime(samples[samples.length - 1].at);
}

/* ---------- 各标签页 ---------- */
async function loadTasks() {
    const query = '/tasks?status=' + encodeURIComponent(el('taskStatus').value)
        + '&taskType=' + encodeURIComponent(el('taskType').value)
        + '&failureReason=' + encodeURIComponent(el('taskFailure').value)
        + '&query=' + encodeURIComponent(el('taskQuery').value)
        + '&page=' + state.taskPage + '&size=' + state.taskSize;
    const data = await api(query);
    state.taskTotal = Number(data.total) || 0;
    table('taskTable', [
        ['任务', 'taskId', 'cell-mono'],
        ['状态', 'status', null, task => {
            const status = String(task.status);
            return tag(zh('task', status), status === 'FAILED' || status === 'UNKNOWN_RESULT' ? 'bad'
                : status === 'REPLY_SENT' ? 'ok' : 'warn');
        }],
        ['类型', 'taskType'],
        ['用户', 'userId', 'cell-mono'],
        ['失败原因', 'failureReason', 'cell-wide']
    ], data.items, (cell, task) => {
        if (task.status === 'UNKNOWN_RESULT' && String(task.replaySafe) === 'true') {
            actionButton(cell, '安全重试', async () => {
                if (!confirm('确认安全重试该任务？')) return;
                const result = await api('/actions/tasks/' + encodeURIComponent(task.taskId) + '/retry', 'POST');
                if (!result.accepted) throw new Error('任务不满足安全重试条件或已被领取');
                await loadTasks();
                return refresh(true);
            });
        }
    });

    const from = state.taskTotal === 0 ? 0 : state.taskPage * state.taskSize + 1;
    const to = Math.min(state.taskTotal, (state.taskPage + 1) * state.taskSize);
    el('task-hint').textContent = '共 ' + state.taskTotal + ' 条，当前显示第 ' + from + ' 至 ' + to + ' 条';
    const pager = el('taskPager');
    pager.replaceChildren();
    if (state.taskPage > 0) outlineButton(pager, '上一页', async () => { state.taskPage--; await loadTasks(); });
    if (to < state.taskTotal) outlineButton(pager, '下一页', async () => { state.taskPage++; await loadTasks(); });
    if (!pager.childElementCount) pager.append(node('span', 'info', '全部结果都在本页'));
}

async function loadUsers() {
    const users = await api('/users');
    table('userTable', [
        ['用户', 'displayUserId', 'cell-mono'],
        ['最近活动', 'lastSeenAt', null, user => fmtTime(user.lastSeenAt)],
        ['通道', 'channel', null, user => zh('channel', user.channel)],
        ['消息', 'messageCount', null, user => fmtNum(user.messageCount)],
        ['记忆', 'memoryCount', null, user => fmtNum(user.memoryCount)],
        ['提醒', 'reminderCount', null, user => fmtNum(user.reminderCount)]
    ], users, (cell, user) => actionButton(cell, '查看详情', () => openUser(user.userId)));
}

async function openUser(userId) {
    state.userId = userId;
    state.userPage = 0;
    el('userDetailCard').hidden = false;
    await loadUserDetail();
}

async function loadUserDetail() {
    const userId = state.userId;
    el('userDetailTitle').textContent = '用户详情 · ' + userId;
    el('userDetailBody').replaceChildren(node('div', 'empty', '加载中…'));
    el('userDetailPager').replaceChildren();
    try {
        const data = await api('/users/' + encodeURIComponent(userId) + '?page=' + state.userPage + '&size=50');
        const labels = {
            profile: '资料与人设', conversations: '对话证据', coreMemories: '核心记忆', workMemories: '工作记忆',
            episodicMemories: '情景记忆', media: '媒体文件', reminders: '提醒任务'
        };
        const sections = [];
        for (const [key, value] of Object.entries(data)) {
            if (key === 'page' || key === 'pageSize' || key === 'truncated' || key === 'userId') continue;
            const details = node('details', 'item');
            const count = Array.isArray(value) ? '（' + value.length + ' 条）' : '';
            details.append(node('summary', null, (labels[key] || key) + count));
            details.append(node('pre', null, JSON.stringify(value, null, 2)));
            sections.push(details);
        }
        el('userDetailBody').replaceChildren(...sections);
        el('userDetailTitle').textContent = '用户详情 · ' + (data.userId || userId) + ' · 第 ' + (state.userPage + 1) + ' 页';

        const pager = el('userDetailPager');
        pager.replaceChildren();
        if (state.userPage > 0) outlineButton(pager, '上一页', async () => { state.userPage--; await loadUserDetail(); });
        if (data.truncated) outlineButton(pager, '下一页', async () => { state.userPage++; await loadUserDetail(); });
        if (!pager.childElementCount) pager.append(node('span', 'info', '该用户数据已全部显示'));
    } catch (error) {
        el('userDetailBody').replaceChildren(node('div', 'empty', '详情加载失败：' + error.message));
        throw error;
    }
}

async function loadLogs() {
    const entries = await api('/logs?level=' + encodeURIComponent(el('logLevel').value)
        + '&query=' + encodeURIComponent(el('logQuery').value));
    table('logTable', [
        ['时间', 'createdAt', null, entry => fmtTime(entry.createdAt)],
        ['级别 / 操作', 'action', null, entry => entry.level ? zh('level', entry.level) : zh('action', entry.action)],
        ['用户', 'userId', 'cell-mono'],
        ['详情', 'detail', 'cell-wide', entry => entry.detail || entry.message || '—'],
        ['来源', 'source', null, entry => zh('source', entry.source)]
    ], entries);
}

async function loadActive() {
    if (state.tab === 'tasks') return loadTasks();
    if (state.tab === 'usersTab') return loadUsers();
    if (state.tab === 'logs') return loadLogs();
    return undefined;
}

/* ---------- 主刷新 ---------- */
async function refresh(force) {
    if (state.refreshing) return;
    if (state.stopped && force !== true) return;
    state.refreshing = true;
    try {
        const overview = await api('/overview');
        renderSummary(overview);
        renderRuntime(overview);
        renderCounts(overview);
        renderQq(overview);
        renderChart(await api('/metrics/history'));
        await loadActive();
        state.lastSuccess = new Date().toLocaleTimeString('zh-CN', { hour12: false });
        const line = el('status');
        line.className = 'foot';
        line.textContent = '最后成功刷新：' + state.lastSuccess
            + (state.interval > 0 ? ' · 每 ' + state.interval / 1000 + ' 秒自动刷新' : ' · 已关闭自动刷新');
    } catch (error) {
        if (force !== true) showError(error);
    } finally {
        state.refreshing = false;
        state.tickStart = Date.now();
        el('tick').style.width = '0%';
    }
}

function startAutoRefresh() {
    clearInterval(state.timer);
    clearInterval(state.tickTimer);
    state.timer = null;
    state.tickTimer = null;
    if (state.interval <= 0) { el('tick').style.width = '0%'; return; }
    state.tickStart = Date.now();
    state.timer = setInterval(() => { if (!state.stopped) refresh(); }, state.interval);
    state.tickTimer = setInterval(() => {
        const progress = Math.min(100, (Date.now() - state.tickStart) / state.interval * 100);
        el('tick').style.width = progress + '%';
    }, 1000);
}

/* ---------- 事件绑定 ---------- */
document.querySelectorAll('.tab').forEach(tab => {
    tab.onclick = async () => {
        state.tab = tab.dataset.tab;
        document.querySelectorAll('.tab').forEach(other => other.classList.toggle('active', other === tab));
        document.querySelectorAll('.section').forEach(section => section.classList.toggle('active', section.id === state.tab));
        try { localStorage.setItem('admin.tab', state.tab); } catch (ignored) { /* 忽略 */ }
        try { await loadActive(); } catch (error) { showError(error); }
    };
});

el('refresh').onclick = () => refresh(true);

el('logout').onclick = () => leaveToLogin(false);

el('taskLoad').onclick = async () => {
    state.taskPage = 0;
    try { await loadTasks(); } catch (error) { showError(error); }
};

el('logLoad').onclick = async () => {
    try { await loadLogs(); } catch (error) { showError(error); }
};

for (const [id, path, text] of [
    ['reconnect', '/actions/qq/reconnect', '确认触发 QQ 重连？'],
    ['cleanup', '/actions/cache/cleanup', '确认清理过期缓存？']
]) {
    el(id).onclick = async () => {
        if (!confirm(text)) return;
        const control = el(id);
        control.disabled = true;
        try {
            const result = await api(path, 'POST');
            if (result.accepted === false) throw new Error(result.message || '操作未被接受');
            state.stopped = false;
            await refresh(true);
            const line = el('status');
            line.className = 'foot';
            line.textContent = '操作已执行 · ' + new Date().toLocaleTimeString('zh-CN', { hour12: false });
        } catch (error) { showError(error); } finally { control.disabled = false; }
    };
}

el('interval').onchange = () => {
    state.interval = Number(el('interval').value) || 0;
    state.stopped = false;
    try { localStorage.setItem('admin.interval', String(state.interval)); } catch (ignored) { /* 忽略 */ }
    startAutoRefresh();
    const line = el('status');
    line.className = 'foot';
    line.textContent = state.interval > 0 ? '已切换为每 ' + state.interval / 1000 + ' 秒自动刷新' : '已关闭自动刷新';
};

/* ---------- 启动 ---------- */
(function boot() {
    if (!accessKey()) { leaveToLogin(false); return; }
    try {
        const savedInterval = localStorage.getItem('admin.interval');
        const parsed = Number(savedInterval);
        if (savedInterval !== null && [0, 5000, 10000, 30000, 60000].includes(parsed)) {
            state.interval = parsed;
            el('interval').value = String(parsed);
        }
        const savedTab = localStorage.getItem('admin.tab');
        const target = savedTab && document.querySelector('.tab[data-tab="' + savedTab + '"]');
        if (target && savedTab !== 'overview') target.click();
    } catch (ignored) { /* 忽略 */ }
    startAutoRefresh();
    refresh();
})();
