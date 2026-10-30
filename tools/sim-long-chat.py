import json
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

MESSAGES = [
    "我下周改考自命题了，不考 408 了",
    "记住：我的高数老师叫陈立中",
    "今天有点累",
    "明天下午三点提醒我交重修申请表",
    "周三的课改到 15:00 了，教室 8B305",
    "我最近喜欢喝美式",
    "我在准备 Java 后端的实习",
    "帮我把刚才说的都记一下",
    "其实我不太想去大厂，想去小团队",
    "周末想休息一下",
]


def send(text):
    body = json.dumps({"userId": "sim-rescue", "content": text}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        "http://127.0.0.1:18080/api/sim/send",
        data=body,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        data = json.loads(response.read().decode("utf-8"))
    return len(data.get("reply") or "")


for index, message in enumerate(MESSAGES, 1):
    try:
        length = send(message)
        print("%2d 发送成功 回复=%d 字 | %s" % (index, length, message[:24]))
    except Exception as error:  # noqa: BLE001
        print("%2d 失败: %s" % (index, error))
    time.sleep(1.5)
