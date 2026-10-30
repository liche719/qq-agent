import io
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")
root = r'C:\Users\33721\Desktop\wechat-agent\qq-agent\src\main\java\com\liche\qqagent\controller'
paths = set()
for name in os.listdir(root):
    if not name.endswith('.java'):
        continue
    text = io.open(os.path.join(root, name), encoding='utf-8').read()
    base = re.search(r'@RequestMapping\("([^"]+)"\)', text)
    base = base.group(1) if base else ''
    for match in re.finditer(r'@GetMapping\((?:value\s*=\s*)?"([^"]+)"', text):
        sub = match.group(1)
        if '{' in sub or '**' in sub:
            continue
        paths.add((base.rstrip('/') + '/' + sub.lstrip('/')).rstrip('/'))

print(len(paths), "个可直接 GET 的面板接口")
for path in sorted(paths):
    print(path)
