const element = id => document.getElementById(id);
let activeTab = 'overview';
let refreshing = false;
let timer;
let lastSuccess = '';
let selectedUserId = '';
let selectedUserPage = 0;

async function api(path, method = 'GET') {
    const response = await fetch('/api/admin' + path, {
        method,
        cache: 'no-store', signal: AbortSignal.timeout(15000)
    });
    if (!response.ok) {
        const error = new Error(response.status === 403 ? '当前来源不允许访问管理后台' : '请求失败 HTTP ' + response.status);
        error.status = response.status;
        throw error;
    }
    return response.json();
}

function textNode(tag, text) {
    const node = document.createElement(tag);
    node.textContent = text == null ? '—' : String(text);
    return node;
}

function table(target, columns, rows, action) {
    const result = document.createElement('table');
    const header = document.createElement('tr');
    columns.forEach(column => header.append(textNode('th', column[0])));
    if (action) header.append(textNode('th', '操作'));
    result.append(header);
    for (const row of rows) {
        const line = document.createElement('tr');
        columns.forEach(column => line.append(textNode('td', row[column[1]])));
        if (action) {
            const cell = document.createElement('td');
            action(cell, row);
            line.append(cell);
        }
        result.append(line);
    }
    element(target).replaceChildren(rows.length ? result : textNode('p', '暂无数据'));
}

function button(parent, label, handler) {
    const control = textNode('button', label);
    control.onclick = async () => {
        control.disabled = true;
        try { await handler(); } catch (error) { showError(error); }
        finally { control.disabled = false; }
    };
    parent.append(control);
}

function showError(error) {
    if (error.status === 403) {
        clearInterval(timer);
        element('interval').value = '0';
    }
    element('status').textContent = '数据已过期：' + error.message + (lastSuccess ? ' · 最后成功：' + lastSuccess : '');
    element('status').className = 'stale';
}

async function detail(userId) {
    selectedUserId = userId;
    selectedUserPage = 0;
    return loadDetailPage();
}

async function loadDetailPage() {
    const userId = selectedUserId;
    const page = selectedUserPage;
    element('userDetailTitle').textContent = '正在加载用户详情…';
    element('userDetailTitle').scrollIntoView({block: 'center'});
    try {
        const data = await api('/users/' + encodeURIComponent(userId) + '?page=' + page + '&size=50');
        element('userDetailTitle').textContent = '用户详情 · ' + userId + ' · 第 ' + (page + 1) + ' 页';
        const sections = [];
        for (const [key, value] of Object.entries(data)) {
            const section = document.createElement('details');
            section.append(textNode('summary', key + (Array.isArray(value) ? '（' + value.length + '）' : '')));
            section.append(textNode('pre', JSON.stringify(value, null, 2)));
            sections.push(section);
        }
        element('userDetail').replaceChildren(...sections);
        const pager = element('userDetailPager');
        pager.replaceChildren();
        if (page > 0) button(pager, '上一页', async () => { selectedUserPage--; await loadDetailPage(); });
        if (['conversations', 'coreMemories', 'workMemories', 'episodicMemories', 'media', 'reminders'].some(key => Array.isArray(data[key]) && data[key].length === 50)) {
            button(pager, '下一页', async () => { selectedUserPage++; await loadDetailPage(); });
        }
    } catch (error) {
        element('userDetailTitle').textContent = '详情加载失败：' + error.message;
        throw error;
    }
}

function renderOverviewCards(overview) {
    const jvm = overview.jvm || {};
    const dependencies = overview.dependencies || {};
    const heap = Number(jvm.heapUsed) && Number(jvm.heapMax) ? Math.round(Number(jvm.heapUsed) / Number(jvm.heapMax) * 100) + '%' : '—';
    const cards = [
        ['运行状态', overview.status || '—', overview.status === 'UP' ? 'ok' : 'warn'],
        ['JVM 堆内存', heap, heap !== '—' && Number(heap.slice(0, -1)) > 85 ? 'bad' : 'ok'],
        ['线程数', jvm.threads, ''],
        ['MySQL / Redis / Quartz', ['mysql', 'redis', 'quartz'].map(key => key + ':' + (dependencies[key] || '—')).join('  '), Object.values(dependencies).every(value => value === 'UP') ? 'ok' : 'bad']
    ];
    element('overviewCards').replaceChildren(...cards.map(([label, value, cls]) => {
        const card = document.createElement('div'); card.className = 'card';
        card.append(textNode('div', label)); const content = textNode('div', value); content.className = 'value ' + cls; card.append(content); return card;
    }));
}

async function loadActive() {
    if (activeTab === 'usersTab') {
        const users = await api('/users');
        table('userTable', [['用户', 'displayUserId'], ['最近活动', 'lastSeenAt'], ['通道', 'channel']], users,
            (cell, user) => button(cell, '查看记忆与详情', () => detail(user.userId)));
    } else if (activeTab === 'tasks') {
        const data = await api('/tasks?status=' + encodeURIComponent(element('taskStatus').value) + '&taskType=' + encodeURIComponent(element('taskType').value) + '&failureReason=' + encodeURIComponent(element('taskFailure').value));
        table('taskTable', [['任务', 'taskId'], ['状态', 'status'], ['用户', 'userId'], ['错误', 'failureReason']], data.items,
            (cell, task) => {
                if (task.status === 'UNKNOWN_RESULT' && String(task.replaySafe) === 'true') {
                    button(cell, '安全重试', async () => {
                        if (!confirm('确认安全重试该任务？')) return;
                        const result = await api('/actions/tasks/' + encodeURIComponent(task.taskId) + '/retry', 'POST');
                        if (!result.accepted) throw new Error('任务不满足安全重试条件或已被领取');
                        await loadActive();
                    });
                }
            });
    } else if (activeTab === 'logs') {
        const data = await api('/logs?level=' + encodeURIComponent(element('logLevel').value) + '&query=' + encodeURIComponent(element('logQuery').value));
        table('logTable', [['时间', 'createdAt'], ['用户', 'userId'], ['操作', 'action'], ['详情', 'detail']], data);
    }
}

async function refresh() {
    if (refreshing) return;
    refreshing = true;
    try {
        const overview = await api('/overview');
        renderOverviewCards(overview);
        element('app').textContent = overview.status;
        element('qq').textContent = overview.qq;
        element('users').textContent = overview.users ?? '—';
        element('unknown').textContent = overview.tasks?.UNKNOWN_RESULT ?? '—';
        element('overviewJson').textContent = JSON.stringify(overview, null, 2);
        element('qqJson').textContent = JSON.stringify(overview.qqMetrics ?? {}, null, 2);
        element('alerts').replaceChildren(...(overview.alerts ?? []).map(message => {
            const alert = textNode('div', message); alert.className = 'alert'; return alert;
        }));
        const history = await api('/metrics/history');
        const maximum = Math.max(1, ...history.map(sample => Number(sample.jvm?.heapUsed) || 0));
        element('chart').replaceChildren(...history.slice(-60).map(sample => {
            const bar = document.createElement('i');
            bar.className = 'bar';
            bar.style.height = Math.max(3, (Number(sample.jvm?.heapUsed) || 0) / maximum * 100) + '%';
            bar.title = sample.at + ' · ' + sample.jvm?.heapUsed;
            return bar;
        }));
        await loadActive();
        lastSuccess = new Date().toLocaleString();
        element('status').textContent = '数据更新时间 / 最后成功刷新：' + lastSuccess;
        element('status').className = 'muted';
    } catch (error) { showError(error); }
    finally { refreshing = false; }
}

document.querySelectorAll('.tab').forEach(tab => {
    tab.onclick = async () => {
        activeTab = tab.dataset.tab;
        document.querySelectorAll('.tab,.section').forEach(node => node.classList.remove('active'));
        tab.classList.add('active'); element(activeTab).classList.add('active');
        try { await loadActive(); } catch (error) { showError(error); }
    };
});
element('refresh').onclick = refresh;
for (const id of ['taskLoad', 'logLoad']) element(id).onclick = async () => {
    try { await loadActive(); } catch (error) { showError(error); }
};
for (const [id, path, message] of [['reconnect', '/actions/qq/reconnect', '确认 QQ 重连？'], ['cleanup', '/actions/cache/cleanup', '确认清理过期缓存？']]) {
    element(id).onclick = async () => {
        if (!confirm(message)) return;
        try {
            const result = await api(path, 'POST');
            if (result.accepted === false) throw new Error(result.message || '操作未被接受');
            await refresh();
        } catch (error) { showError(error); }
    };
}
element('interval').onchange = () => {
    clearInterval(timer);
    const interval = Number(element('interval').value);
    if (interval > 0) timer = setInterval(refresh, interval);
};
element('interval').onchange();
element('status').setAttribute('role', 'status');
element('status').textContent = '等待首次刷新。';
