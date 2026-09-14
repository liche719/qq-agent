// ==UserScript==
// @name         东莞理工 课表采集器（只读 · 给机器人用）
// @namespace    wechat-agent/dgut
// @version      1.0.0
// @description  在自己已登录的教务页面上，一键把课表表格原样导出成文件，交给机器人解析。不做任何解析、不发网络请求、不碰账号密码。
// @author       wechat-agent
// @match        https://jwx.dgut.edu.cn/jsxsd/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

/*
 * 为什么"只导出、不解析"：
 *   正方教务各校定制程度不同，课表表格的结构（列/行/单元格怎么写周次）必须先拿到真实样本才能写解析。
 *   这个脚本只负责"把原始 HTML 拿出来"，不猜结构 —— 拿到你的真实文件后，解析写在服务端，改起来不用你重装脚本。
 *
 * 隐私：文件只在你本机生成（下载目录），导出的内容是你自己的课表；#kbtable 存在时只导表格，不导整页。
 * 安全：没有任何 fetch/XHR，不会上传到任何地方，也不会读你输入框里的密码。
 */

(function () {
  'use strict';

  var TABLE_ID = 'kbtable'; // 正方教务课表表格的常见 id；找不到就整页兜底

  function makeButton() {
    var btn = document.createElement('button');
    btn.textContent = '导出课表给机器人';
    btn.style.cssText = [
      'position:fixed', 'right:18px', 'bottom:18px', 'z-index:2147483647',
      'padding:10px 16px', 'border:0', 'border-radius:10px',
      'background:#2b6cb0', 'color:#fff', 'font-size:14px', 'line-height:1.2',
      'cursor:pointer', 'box-shadow:0 3px 10px rgba(0,0,0,.28)', 'font-family:inherit'
    ].join(';');
    return btn;
  }

  function exportIt() {
    var table = document.getElementById(TABLE_ID);
    var payload = {
      kind: 'dgut-kb-collector',
      version: '1.0.0',
      source: 'dgut-jw',
      collectedAt: new Date().toISOString(),
      url: location.href,
      pageTitle: document.title,
      // 页面上显示的学期/学年文字（有的版本在 select 或标题里）
      termHints: Array.prototype.slice
        .call(document.querySelectorAll('select option:checked, .xnxq, #xnxq01id option:checked'))
        .map(function (el) { return (el.textContent || '').trim(); })
        .filter(Boolean),
      foundTable: !!table,
      // 找到就只导表格；找不到才整页兜底（整页会包含你的姓名/学号，只发给我/发到你自己的 QQ）
      kbTableHtml: table ? table.outerHTML : null,
      pageHtmlFallback: table ? null : document.documentElement.outerHTML
    };

    var stamp = new Date().toISOString().replace(/[:.]/g, '-');
    var blob = new Blob([JSON.stringify(payload, null, 1)], { type: 'application/json' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = 'dgut-kb-' + stamp + '.json';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 8000);

    return payload.foundTable;
  }

  function mount() {
    if (document.getElementById('dgut-kb-collector-btn')) return;
    var btn = makeButton();
    btn.id = 'dgut-kb-collector-btn';
    btn.addEventListener('click', function () {
      var found = exportIt();
      btn.textContent = found ? '已导出 ✓（找到课表表格）' : '已导出 ✓（整页兜底）';
      setTimeout(function () { btn.textContent = '导出课表给机器人'; }, 4000);
    });
    document.body.appendChild(btn);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount);
  } else {
    mount();
  }
})();
