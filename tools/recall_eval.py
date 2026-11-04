#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""记忆召回评测（2026-11-04）。

题库自动生成：从 memory 表抽 N 条，让 LLM 为每条写一句“用户会怎么问这件事”，
再把这句丢进真实召回路径，看那条记忆有没有被注入。

判据：从记忆里取一段独特片段（去标点后的前 10 个字符），出现在注入文本里算命中。
判据不追求完美——要的是“同一套判据下，改参数前后的对比”。
"""
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

HOST = "root@120.25.170.92"
SAMPLE = int(sys.argv[1]) if len(sys.argv) > 1 else 250
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(tempfile.gettempdir(), "recall-eval.json")
BATCH = 10
SQL_TMP = os.path.join(tempfile.gettempdir(), "_recall_q.sql")

ASK = (
    "下面是用户和助手聊天时沉淀的一条记忆：\n{content}\n\n"
    "请写一句用户可能怎么问起这件事，要求：\n"
    "1) 像真人随口问的，20 字以内；\n"
    "2) **如果是时间、地点、教室、数字、人名、编号类的事，问句里要带上那个关键词**"
    "（真人找东西时就是靠这些词，比如「6e206 是哪门课」「陈立中签了没」「521 那次」）；\n"
    "3) 不要出现记忆这个词；不要复述整句原文。\n"
    "只输出这句话本身。"
)


def sh(cmd):
    """只返回 stdout：stderr 里是 SSH 的 post-quantum 警告，混进来会让解析失败"""
    done = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", HOST, cmd],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        universal_newlines=True, encoding="utf-8")
    return done.stdout or ""


def env(name):
    return sh('grep -m1 "^{0}=" /opt/qq-agent/.env | cut -d= -f2-'.format(name)).strip()


LLM_KEY = env("LLM_API_KEY")
LLM_URL = env("LLM_BASE_URL")
LLM_MODEL = env("LLM_MODEL")
ADMIN_KEY = env("ADMIN_API_KEY")


def psql(sql):
    """走和 tools/qq-sql.sh 一样的连接（wechat_app / wechat_agent），SQL 通过 stdin 送进容器"""
    with open(SQL_TMP, "w", encoding="utf-8") as handle:
        handle.write(sql + "\n")
    subprocess.run(["scp", "-q", SQL_TMP, HOST + ":/tmp/_recall_q.sql"],
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return sh("docker exec -i qq-agent-postgres psql -U wechat_app -d wechat_agent "
              "-t -A -F '|' < /tmp/_recall_q.sql")


def sample_memories(limit):
    sql = ("select id || '|' || kind || '|' || replace(replace(content, chr(10), ' '), '|', ' ') "
           "from memory where kind in ('PROFILE','TASK','EXPERIENCE') "
           "and length(content) between 25 and 200 order by md5(id::text) limit {0}".format(limit))
    rows = []
    for line in psql(sql).splitlines():
        parts = line.split("|", 2)
        if len(parts) >= 3 and parts[0].strip().isdigit():
            rows.append({"id": int(parts[0]), "kind": parts[1], "content": parts[2].strip()})
    return rows


def ask_llm(prompt, timeout=180):
    body = json.dumps({
        "model": LLM_MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.3,
        "max_tokens": 1200,
    }).encode()
    request = urllib.request.Request(
        LLM_URL.rstrip("/") + "/chat/completions", data=body,
        headers={"Authorization": "Bearer " + LLM_KEY, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode())["choices"][0]["message"]["content"]


def make_questions(batch):
    """一条记忆一次调用；某条失败只丢那一条，绝不连累整批（2026-11-04 踩过）"""
    questions = []
    for memory in batch:
        text = None
        for _ in range(3):
            try:
                answer = ask_llm(ASK.format(content=memory["content"][:200]), timeout=60)
                candidate = answer.strip().strip('"').splitlines()[0].strip()
                if 3 <= len(candidate) <= 40:
                    text = candidate
                    break
            except Exception:
                text = None
        questions.append(text)
    return questions


def key_of(content):
    cleaned = re.sub(r"[^\u4e00-\u9fa5A-Za-z]", "", content)
    return cleaned[:10] if len(cleaned) >= 6 else None


def run_eval(pairs):
    results = []
    total_batches = (len(pairs) + BATCH - 1) // BATCH
    for index in range(0, len(pairs), BATCH):
        chunk = pairs[index:index + BATCH]
        payload = json.dumps([question for question, _, _ in chunk])
        cmd = ('curl -sk -m 900 -X POST "https://127.0.0.1/api/admin/memory/recall-eval" '
               '-H "X-Agent-Admin-Key: {0}" -H "Content-Type: application/json" -d \'{1}\'').format(
            ADMIN_KEY, payload)
        raw = sh(cmd)
        start = raw.find('{"userId"')
        if start < 0:
            print("  批次 {0} 无响应，跳过".format(index // BATCH + 1), flush=True)
            continue
        try:
            data = json.loads(raw[start:raw.rfind("}") + 1])
        except Exception:
            print("  批次 {0} 解析失败，跳过".format(index // BATCH + 1), flush=True)
            continue
        for (question, memory, key), row in zip(chunk, data.get("rows", [])):
            text = (row.get("core") or "") + (row.get("work") or "")
            results.append({
                "id": memory["id"], "kind": memory["kind"], "question": question,
                "hit": key in text, "chars": row.get("chars", 0), "ms": row.get("ms", 0)})
        print("  批次 {0}/{1} 完成（累计 {2}）".format(
            index // BATCH + 1, total_batches, len(results)), flush=True)
    return results


CACHE = os.path.join(tempfile.gettempdir(), "recall_questions.json")


def load_or_build(memories):
    """题库缓存：第一次生成后存下来，之后的 A/B 用同一份（否则温度 0.3 会让两次题库不同）"""
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as handle:
            cached = json.load(handle)
        by_id = {m["id"]: m for m in memories}
        pairs = []
        for item in cached:
            memory = by_id.get(item["id"])
            if memory and item.get("key"):
                pairs.append((item["question"], memory, item["key"]))
        if pairs:
            print("复用题库 {0} 条（{1}）".format(len(pairs), CACHE), flush=True)
            return pairs

    pairs = []
    total_batches = (len(memories) + BATCH - 1) // BATCH
    for index in range(0, len(memories), BATCH):
        chunk = memories[index:index + BATCH]
        questions = make_questions(chunk)
        for memory, question in zip(chunk, questions):
            if not question:
                continue
            key = key_of(memory["content"])
            if key:
                pairs.append((question, memory, key))
        print("  出题 {0}/{1}（累计 {2}）".format(index // BATCH + 1, total_batches, len(pairs)), flush=True)
    with open(CACHE, "w", encoding="utf-8") as handle:
        json.dump([{"id": m["id"], "question": q, "key": k} for q, m, k in pairs], handle, ensure_ascii=False)
    print("题库已存 {0}".format(CACHE), flush=True)
    return pairs


def main():
    print("抽样 {0} 条记忆…".format(SAMPLE), flush=True)
    memories = sample_memories(SAMPLE)
    print("拿到 {0} 条".format(len(memories)), flush=True)

    pairs = load_or_build(memories)
    print("题库 {0} 条，开始评测…".format(len(pairs)), flush=True)

    results = run_eval(pairs)
    if not results:
        print("没有结果")
        return

    hit = sum(1 for item in results if item["hit"])
    chars = [item["chars"] for item in results]
    latencies = sorted(item["ms"] for item in results)
    summary = {
        "total": len(results),
        "hit": hit,
        "recall": round(hit * 100.0 / len(results), 1),
        "avg_chars": round(sum(chars) / len(chars)),
        "p50_ms": latencies[len(latencies) // 2],
        "p90_ms": latencies[int(len(latencies) * 0.9)],
        "by_kind": {},
    }
    for kind in ("PROFILE", "TASK", "EXPERIENCE"):
        subset = [item for item in results if item["kind"] == kind]
        if subset:
            summary["by_kind"][kind] = {
                "n": len(subset),
                "recall": round(sum(1 for item in subset if item["hit"]) * 100.0 / len(subset), 1)}

    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump({"summary": summary, "results": results}, handle, ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    print("明细：" + OUT)
    misses = [item for item in results if not item["hit"]]
    print("未命中 {0} 条，示例 5 条：".format(len(misses)))
    for item in misses[:5]:
        print("  [{0}] {1}（{2} 字）".format(item["kind"], item["question"], item["chars"]))


if __name__ == "__main__":
    main()
