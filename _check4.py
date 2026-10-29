import sys

sys.stdout.reconfigure(encoding="utf-8")
import docx

d = docx.Document(r"C:\Users\33721\Desktop\简历\01-投递材料\梁超文_Agent开发_简历.docx")
texts = [p.text.strip() for p in d.paragraphs if p.text.strip()]
print("段落", len(texts), "总字数", sum(len(t) for t in texts))
for i in range(10, len(texts)):
    print(i, "|", texts[i][:100])
