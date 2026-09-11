'use strict';

/* ---------- 基础工具 ---------- */
const el = id => document.getElementById(id);

const state = {
    tab: 'overview',
    interval: 10000,
    timer: null,
    tickTimer: null,
    tickStart: Date.now(),
    refreshing: false,
    halted: false,
    lastSuccess: '',
    taskPage: 0,
    taskSize: 20,
    taskTotal: 0,
    userId: '',
    userPage: 0
};

function node(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined && text !== null) element.textContent = String(text);
    return element;
}

function fmtBytes(value) {
    const number = Number(value);
    if (!Number.isFinite(number) || number <= 0) return '—';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let index = 0;
    let size = number;
    while (size >= 1024 && index < units.length - 1) { size /= 1024; index++; }
    return (index === 0 ? size : size.toFixed(size >= 100 ? 0 : 1)) + ' ' + units[index];
}

function fmtNum(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return value === undefined || value === null || value === '' ? '—' : String(value);
    return number.toLocaleString('zh-CN');
}

/** 接口里的时间可能是「yyyy-MM-dd HH:mm:ss」或 ISO 字符串，统一显示成本地可读格式 */
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
    return Number.isFinite(number) && number > 0 ? Math.round(number) + ' ms' : '—';
}

/* ---------- 接口 ---------- */
async function api(path, method = 'GET') {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    let response;
    try {
        response = await fetch('/api/admin' + path, { method, cache: 'no-store', signal: controller.signal });
    } catch (error) {
        throw new Error(error.name === 'AbortError' ? '请求超时（15 秒）' : '网络不可达');
    } finally {
        clearTimeout(timeout);
    }
    if (!response.ok) {
        const message = response.status === 401 ? '登录凭据已失效，请刷新页面重新输入账号密码'
            : response.status === 403 ? '当前来源 IP 不允许访问管理后台'
            : response.status === 429 ? '请求过于频繁，已被限流，请稍后再试'
            : response.status >= 500 ? '服务端异常 HTTP ' + response.status
            : '请求失败 HTTP ' + response.status;
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

function actionButton(parent, label, handler, className) {
    const control = node('button', 'sm' + (className ? ' ' + className : ''), label);
    control.onclick = async () => {
        control.disabled = true;
        try { await handler(); } catch (error) { showError(error); } finally { control.disabled = false; }
    };
    parent.append(control);
}

function tag(text, tone) {
    return node('span', 'tag' + (tone ? ' ' + tone : ''), text);
}

function statusTone(value) {
    return value === 'UP' ? 'ok' : value === 'DEGRADED' || value === 'STANDBY' || value === 'DISABLED' ? 'warn' : 'bad';
}

function showError(error) {
    if (error.status === 401) haltAutoRefresh();
    const suffix = state.lastSuccess ? ' · 最后成功刷新：' + state.lastSuccess : '';
    const line = el('status');
    line.textContent = '刷新失败：' + error.message + suffix;
    line.className = 'foot stale';
}

function haltAutoRefresh() {
    state.halted = true;
    clearInterval(state.timer);
    state.timer = null;
    el('interval').value = '0';
    el('tick').style.width = '0%';
}

/* ---------- 总览 ---------- */
function renderSummary(overview) {
    const jvm = overview.jvm || {};
    const tasks = overview.tasks || {};
    const qqMetrics = overview.qqMetrics || {};
    const status = overview.status || '—';
    const qq = overview.qq || '—';

    setPill('pill-app', '应用 ' + status, statusTone(status));
    setPill('pill-qq', 'QQ ' + qq, qq === 'UP' ? 'ok' : qq === 'DISABLED' ? 'warn' : 'bad');

    const memory = ['coreMemories', 'workMemories', 'episodes']
        .map(key => Number(overview[key]) || 0).reduce((left, right) => left + right, 0);
    const abnormal = Number(tasks.UNKNOWN_RESULT) || 0;

    setStat('s-app', status, '启动于 ' + fmtTime(overview.startedAt), statusTone(status));
    setStat('s-qq', qq, '发送成功 ' + fmtNum(qqMetrics.textSendSuccess) + ' · 失败 ' + fmtNum(qqMetrics.textSendFailure),
        qq === 'UP' ? 'ok' : qq === 'DISABLED' ? 'warn' : 'bad');
    setStat('s-users', fmtNum(overview.users), '对话 ' + fmtNum(overview.conversations) + ' · 记忆 ' + fmtNum(memory), '');
    setStat('s-tasks', fmtNum(abnormal), '运行 ' + fmtNum(tasks.RUNNING) + ' · 失败 ' + fmtNum(tasks.FAILED), abnormal > 0 ? 'bad' : 'ok');

    el('overview-json').textContent = JSON.stringify(overview, null, 2);
    el('deps-json').textContent = JSON.stringify({ dependencies: overview.dependencies || {}, moduleErrors: overview.moduleErrors || {} }, null, 2);
    el('alerts').replaceChildren(...(overview.alerts || []).map(message => node('div', 'alert', message)));
}

function renderRuntime(overview) {
    const jvm = overview.jvm || {};
    const heapUsed = Number(jvm.heapUsed) || 0;
    const heapMax = Number(jvm.heapMax) || 0;
    const heapPercent = heapMax > 0 ? Math.round(heapUsed / heapMax * 100) : -1;
    const cpu = Number(jvm.systemCpuLoad);
    kv('runtime', [
        ['堆内存', fmtBytes(heapUsed) + (heapPercent >= 0 ? ' / ' + fmtBytes(heapMax) : '')],
        ['堆占用', heapPercent >= 0 ? heapPercent + '%' : '—'],
        ['线程数', fmtNum(jvm.threads)],
        ['CPU 负载', Number.isFinite(cpu) && cpu >= 0 ? Math.round(cpu * 100) + '%' : '—'],
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
        ['任务·异常结果', fmtNum(tasks.UNKNOWN_RESULT)],
        ['任务·已回复', fmtNum(tasks.REPLY_SENT)]
    ]);
}

function renderQq(overview) {
    const metrics = overview.qqMetrics || {};
    const status = overview.qq;
    const known = [
        ['网关状态', status === 'UP' ? '已连接' : status === 'DISABLED' ? '未启用' : '已断开'],
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
    const errorStatus = metrics.apiErrorsByStatus && Object.keys(metrics.apiErrorsByStatus).length
        ? Object.entries(metrics.apiErrorsByStatus).map(([code, count]) => code + '×' + count).join('  ')
        : '无';
    known.push(['接口错误按状态码', errorStatus]);
    if (metrics.lastApiError) known.push(['最近接口错误', String(metrics.lastApiError)]);
    kv('qq-kv', known);
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

/* ---------- 各标签页数据 ---------- */
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
        ['状态', 'status', null, task => tag(String(task.status), String(task.status) === 'FAILED' || String(task.status) === 'UNKNOWN_RESULT' ? 'bad' : String(task.status) === 'REPLY_SENT' ? 'ok' : 'warn')],
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
    el('task-hint').textContent = '共 ' + state.taskTotal + ' 条，当前显示 ' + from + '–' + to;
    const pager = el('taskPager');
    pager.replaceChildren();
    if (state.taskPage > 0) actionButton(pager, '上一页', async () => { state.taskPage--; await loadTasks(); });
    if (to < state.taskTotal) actionButton(pager, '下一页', async () => { state.taskPage++; await loadTasks(); });
    if (!pager.childElementCount) pager.append(node('span', 'info', '全部结果已在此页'));
}

async function loadUsers() {
    const users = await api('/users');
    table('userTable', [
        ['用户', 'displayUserId', 'cell-mono'],
        ['最近活动', 'lastSeenAt', null, user => fmtTime(user.lastSeenAt)],
        ['通道', 'channel'],
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
        const sections = [];
        const labels = {
            profile: '资料与人设', conversations: '对话证据', coreMemories: '核心记忆', workMemories: '工作记忆',
            episodicMemories: '情景记忆', media: '媒体文件', reminders: '提醒任务'
        };
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
        if (state.userPage > 0) actionButton(pager, '上一页', async () => { state.userPage--; await loadUserDetail(); });
        if (data.truncated) actionButton(pager, '下一页', async () => { state.userPage++; await loadUserDetail(); });
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
        ['级别 / 操作', 'action', null, entry => entry.level || entry.action || '—'],
        ['用户', 'userId', 'cell-mono'],
        ['详情', 'detail', 'cell-wide', entry => entry.detail || entry.message || '—'],
        ['来源', 'source']
    ], entries);
}

async function loadActive() {
    const tab = state.tab;
    if (tab === 'tasks') return loadTasks();
    if (tab === 'usersTab') return loadUsers();
    if (tab === 'logs') return loadLogs();
    return undefined;
}

/* ---------- 主刷新 ---------- */
async function refresh(quiet) {
    if (state.refreshing) return;
    state.refreshing = true;
    try {
        const overview = await api('/overview');
        renderSummary(overview);
        renderRuntime(overview);
        renderCounts(overview);
        renderQq(overview);
        renderChart(await api('/metrics/history'));
        await loadActive();
        state.halted = false;
        state.lastSuccess = new Date().toLocaleTimeString('zh-CN', { hour12: false });
        const line = el('status');
        line.className = 'foot';
        line.textContent = '最后成功刷新：' + state.lastSuccess
            + (state.interval > 0 ? ' · 每 ' + state.interval / 1000 + ' 秒自动刷新' : ' · 自动刷新已关闭');
    } catch (error) {
        if (!quiet) showError(error);
    } finally {
        state.refreshing = false;
        state.tickStart = Date.now();
        el('tick').style.width = '0%';
    }
}

function startAutoRefresh() {
    clearInterval(state.timer);
    state.timer = null;
    clearInterval(state.tickTimer);
    state.tickTimer = null;
    if (state.interval <= 0) { el('tick').style.width = '0%'; return; }
    state.tickStart = Date.now();
    state.timer = setInterval(() => { if (!state.halted) refresh(); }, state.interval);
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
        try { localStorage.setItem('admin.tab', state.tab); } catch (ignored) { /* 隐私模式下忽略 */ }
        try { await loadActive(); } catch (error) { showError(error); }
    };
});

el('refresh').onclick = () => refresh();

el('taskLoad').onclick = async () => {
    state.taskPage = 0;
    try { await loadTasks(); } catch (error) { showError(error); }
};

el('logLoad').onclick = async () => {
    try { await loadLogs(); } catch (error) { showError(error); }
};

for (const [id, path, message] of [
    ['reconnect', '/actions/qq/reconnect', '确认触发 QQ 重连？'],
    ['cleanup', '/actions/cache/cleanup', '确认清理过期缓存？']
]) {
    el(id).onclick = async () => {
        if (!confirm(message)) return;
        const control = el(id);
        control.disabled = true;
        try {
            const result = await api(path, 'POST');
            if (result.accepted === false) throw new Error(result.message || '操作未被接受');
            await refresh(true);
            el('status').textContent = '操作已执行 · ' + new Date().toLocaleTimeString('zh-CN', { hour12: false });
        } catch (error) { showError(error); } finally { control.disabled = false; }
    };
}

el('interval').onchange = () => {
    state.interval = Number(el('interval').value) || 0;
    state.halted = false;
    try { localStorage.setItem('admin.interval', String(state.interval)); } catch (ignored) { /* 忽略 */ }
    startAutoRefresh();
    el('status').textContent = state.interval > 0 ? '已切换为每 ' + state.interval / 1000 + ' 秒自动刷新' : '已关闭自动刷新';
    el('status').className = 'foot';
};

/* ---------- 启动 ---------- */
(function boot() {
    try {
        const savedInterval = Number(localStorage.getItem('admin.interval'));
        if ([0, 5000, 10000, 30000, 60000].includes(savedInterval) && localStorage.getItem('admin.interval') !== null) {
            state.interval = savedInterval;
            el('interval').value = String(savedInterval);
        }
        const savedTab = localStorage.getItem('admin.tab');
        const target = savedTab && document.querySelector('.tab[data-tab="' + savedTab + '"]');
        if (target && savedTab !== 'overview') target.click();
    } catch (ignored) { /* 忽略 */ }
    startAutoRefresh();
    refresh();
})();
