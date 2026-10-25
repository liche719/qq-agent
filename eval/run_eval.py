#!/usr/bin/env python3
"""记忆评估跑分（2026-10-26）。

只在**本地**实例上跑：脚本会拒绝非 127.0.0.1/localhost 的地址，避免把 eval 数据写进生产。
用法：
    python eval/run_eval.py --url http://127.0.0.1:18080 --key <ADMIN_API_KEY>
"""
import argparse
import json
import sys
import urllib.parse
import urllib.request

LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    parser.add_argument("--key", default="")
    parser.add_argument("--user", default="")
    parser.add_argument("--min-score", type=int, default=0)
    args = parser.parse_args()

    host = urllib.parse.urlparse(args.url).hostname
    if host not in LOCAL_HOSTS:
        print(f"拒绝执行：{host} 不是本地地址（评估只允许跑本地实例）", file=sys.stderr)
        return 2

    user = args.user or "eval-cli"
    url = f"{args.url}/api/admin/eval/memory?userId={urllib.parse.quote(user)}"
    request = urllib.request.Request(url, method="POST")
    if args.key:
        request.add_header("X-Agent-Admin-Key", args.key)
    with urllib.request.urlopen(request, timeout=1800) as response:
        report = json.loads(response.read().decode("utf-8"))

    if not report.get("ok"):
        print("跑分失败：", report.get("error"))
        return 3

    print(f"总分 {report['score']}（{report['passed']}/{report['total']}）")
    print("-" * 72)
    for item in report["results"]:
        mark = "PASS" if item.get("pass") else "FAIL"
        detail = ""
        if not item.get("pass"):
            detail = json.dumps({k: v for k, v in item.items() if k in ("missing", "forbidden", "expected", "got")},
                                ensure_ascii=False)
        print(f"{mark}  {item['id']:<20} {item['type']:<8} {detail}")
    score = int(report["score"].rstrip("%"))
    return 0 if score >= args.min_score else 1


if __name__ == "__main__":
    sys.exit(main())
