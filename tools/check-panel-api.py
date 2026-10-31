import io
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

web_root = r'C:\Users\33721\Desktop\wechat-agent\qq-agent\web\src'
java_root = r'C:\Users\33721\Desktop\wechat-agent\qq-agent\src\main\java\com\liche\qqagent\controller'

# 1) 前端调用到的接口路径
front = set()
for base, _, files in os.walk(web_root):
    for name in files:
        if not name.endswith(('.vue', '.js', '.ts')):
            continue
        text = io.open(os.path.join(base, name), encoding='utf-8', errors='ignore').read()
        for match in re.finditer(r"""api\(\s*[`'"]([^`'"]+)""", text):
            path = match.group(1).split('?')[0]
            path = re.sub(r'\$\{[^}]+\}', '{id}', path)
            if path.startswith('/'):
                front.add(path)

# 2) 后端声明的路径
back = set()
for name in os.listdir(java_root):
    if not name.endswith('.java'):
        continue
    text = io.open(os.path.join(java_root, name), encoding='utf-8').read()
    base = re.search(r'@RequestMapping\("([^"]+)"\)', text)
    base = base.group(1) if base else ''
    for match in re.finditer(r'@(?:Get|Post|Delete|Put)Mapping\((?:value\s*=\s*)?"([^"]+)"', text):
        sub = match.group(1)
        sub = re.sub(r'\{[^}]+\}', '{id}', sub)
        full = (base.rstrip('/') + '/' + sub.lstrip('/')) if sub else base
        back.add(full.rstrip('/'))

print("前端调用路径:", len(front), "| 后端声明路径:", len(back))
missing = []
for path in sorted(front):
    # /api/admin 前缀在前端是相对 api() 的，后端声明里带着 /api/admin
    candidate = '/api/admin' + path if not path.startswith('/api') else path
    if candidate in back:
        continue
    # 容错：后端可能用 {id} 占位
    if any(b.count('/') == candidate.count('/') and
           all(x == y or y == '{id}' for x, y in zip(b.split('/'), candidate.split('/')))
           for b in back):
        continue
    missing.append(candidate)

print("\n前端调了但后端没声明的路径:", len(missing))
for item in missing:
    print("  -", item)
