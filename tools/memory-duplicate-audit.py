#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
记忆重复体检（只读，2026-10-28）

用法（在 Windows 本机跑，走 ssh 读生产库，不改任何数据）：

    python tools/memory-duplicate-audit.py
    python tools/memory-duplicate-audit.py --days 30 --threshold 0.55
    python tools/memory-duplicate-audit.py --ssh root@120.25.170.92 --out dump.tsv

它做什么：
  1. 拉 `memory` 表里 ACTIVE 的条目（kind / category / content）；
  2. 用**字符二元组 Jaccard** 找近重复对（不依赖任何模型，纯本地计算）；
  3. 打印重复率与最像的几对，供人工判断是"啰嗦"还是"矛盾"。

为什么用 Jaccard 而不是向量：这是**体检**，要的是可复现、零成本、随时能跑；
向量相似度适合线上召回，不适合当考核指标（模型版本一变数就变）。

判读标准：
  - 近重复对数 / 总条数 持续下降 → 说明"阈值 + 相似带替代"在起作用；
  - 出现"同一属性两个不同值"（例如两个教室号）→ 那才是真矛盾，要单独看。
"""
import argparse
import collections
import io
import re
import subprocess
import sys

SQL = (
    "select id, kind, coalesce(category,''), "
    "left(replace(content, chr(10), ' '), 400) "
    "from memory where status='ACTIVE' order by kind, id;"
)


def fetch_rows(ssh_host, container, db, user):
    """通过 ssh + docker exec 读库（只读 select）"""
    remote = "docker exec {c} psql -U {u} -d {d} -t -A -F '|' -c \"{sql}\"".format(
        c=container, u=user, d=db, sql=SQL.replace('"', '\\"'))
    cmd = ["ssh", "-o", "BatchMode=yes", ssh_host, remote]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="ignore")
    if out.returncode != 0:
        sys.stderr.write("读取失败：%s\n%s\n" % (out.returncode, out.stderr[-500:]))
        sys.exit(2)
    rows = []
    for line in out.stdout.splitlines():
        parts = line.split("|")
        if len(parts) >= 4:
            rows.append((parts[0], parts[1], parts[2], parts[3]))
    return rows


def bigrams(text):
    text = re.sub(r"\s+", "", text or "")
    return set(text[i:i + 2] for i in range(len(text) - 1))


def jaccard(a, b):
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ssh", default="root@120.25.170.92")
    ap.add_argument("--container", default="qq-agent-postgres")
    ap.add_argument("--db", default="wechat_agent")
    ap.add_argument("--user", default="wechat_app")
    ap.add_argument("--threshold", type=float, default=0.55, help="判定近重复的 Jaccard 门槛")
    ap.add_argument("--top", type=int, default=10, help="打印几对")
    ap.add_argument("--out", default="", help="把原始行另存为 tsv（便于后续分析）")
    args = ap.parse_args()
    try:  # Windows 控制台默认 GBK，中文会乱码
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    rows = fetch_rows(args.ssh, args.container, args.db, args.user)
    if args.out:
        io.open(args.out, "w", encoding="utf-8").write(
            "\n".join("|".join(r) for r in rows))
    if not rows:
        print("没有 ACTIVE 记忆")
        return

    by_kind = collections.Counter(r[1] for r in rows)
    print("ACTIVE 记忆：%d 条" % len(rows))
    for kind, count in by_kind.most_common():
        print("  %-12s %d" % (kind, count))

    prepared = [(r[0], r[1], r[2], r[3], bigrams(r[3])) for r in rows]
    pairs = []
    for i in range(len(prepared)):
        for j in range(i + 1, len(prepared)):
            a, b = prepared[i], prepared[j]
            score = jaccard(a[4], b[4])
            if score >= args.threshold:
                pairs.append((score, a, b))
    pairs.sort(key=lambda item: item[0], reverse=True)

    # 指标用"涉及多少条记忆"，而不是"占所有组合的百分比"——
    # 后者分母是 O(n²)，看起来永远接近 0，没法判读。
    involved = set()
    for _, a, b in pairs:
        involved.add(a[0])
        involved.add(b[0])
    print("\n近重复对（Jaccard >= %.2f）：%d 对，涉及 %d 条 / 共 %d 条（%.1f%%）"
          % (args.threshold, len(pairs), len(involved), len(rows),
             100.0 * len(involved) / max(1, len(rows))))

    for score, a, b in pairs[:args.top]:
        print("\n%.2f  %s  #%s / #%s" % (score, a[1], a[0], b[0]))
        print("   A: %s" % a[3][:150])
        print("   B: %s" % b[3][:150])

    print("\n判读：重复率下降 = 「阈值 0.75 + 相似带替代」在起作用；"
          "若出现同一属性两个不同值，那才是真矛盾。")


if __name__ == "__main__":
    main()
