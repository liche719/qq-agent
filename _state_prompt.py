import io

p = r'C:\Users\33721\Desktop\wechat-agent\qq-agent\src\main\java\com\liche\qqagent\memory\MemoryExtractor.java'
s = io.open(p, encoding='utf-8').read()

old = '''                .append("\\"duplicates\\":[]}\\n")'''
new = '''                .append("\\"duplicates\\":[],")
                // 2026-11-01：state 替代了原来的上下文摘要——提取时顺带产出"这段聊到哪了"
                .append("\\"state\\":\\"\\"}\\n")
                .append("（**state 是必填的**：用 2~4 句话说明这段对话聊到哪了——他在忙什么、卡在哪、"
                        + "已经定下来的事、还没做完的事。写给"下一次对话的我自己"看，不要罗列事实、不要评价，"
                        + "也不要抄原话；没有实质性进展就写空字符串）\\n")'''
assert old in s, '没找到 JSON 样例行'
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8').write(s)
print('extraction prompt now asks for state')
