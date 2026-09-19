"""查生产备份现状：每日 zip 里有什么、有没有全库 dump、媒体/日志占多大。"""
import json
import os

import paramiko

PW_FILE = os.path.join(os.environ.get("TEMP", "."), ".wa-ssh-pw")
with open(PW_FILE, "r", encoding="utf-8") as fh:
    password = fh.read().strip()
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect("120.25.170.92", username="root", password=password, timeout=20)
try:
    os.remove(PW_FILE)
except OSError:
    pass
sftp = client.open_sftp()
PG = 'docker exec -i wechat-agent-postgres sh -c'

ZIP = r'''
import glob, json, zipfile, os
zips = sorted(glob.glob("/opt/wechat-agent-infra/backup/*.zip"))
print("zip_count=%d" % len(zips))
for path in zips[-3:]:
    size = os.path.getsize(path)
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        total = sum(i.file_size for i in z.infolist())
        users = sorted({n.split("/")[0] for n in names})
        state = [n for n in names if n.endswith("state.json")]
        media = [n for n in names if n.startswith("media/")]
        dumps = [n for n in names if n.endswith((".sql", ".dump", ".sql.gz"))]
        print("%s size=%d entries=%d uncompressed=%d users=%d state_json=%d media=%d dumps=%s"
              % (os.path.basename(path), size, len(names), total, len(users), len(state), len(media), dumps))
        if state:
            with z.open(state[0]) as fh:
                data = json.load(fh)
            print("   sample(%s) keys=%s" % (state[0], sorted(data.keys())[:14]))
'''
with sftp.open("/tmp/wa_zipcheck.py", "w") as fh:
    fh.write(ZIP)


def run(cmd, label, timeout=300):
    stdin, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    return {"label": label, "code": stdout.channel.recv_exit_status(),
            "out": out.strip()[-3000:], "err": err.strip()[-400:]}


steps = [
    run("ls -l /opt/wechat-agent-infra/backup/ | tail -8; echo '---media---'; ls /opt/wechat-agent-infra/backup/media | wc -l",
        "backup_dir"),
    run("du -sh /opt/wechat-agent-infra/backup /opt/wechat-agent-infra/stored-media /opt/wechat-agent-infra/logs 2>/dev/null",
        "sizes"),
    run("python3 /tmp/wa_zipcheck.py", "zip_contents"),
    run("find /opt/wechat-agent-infra /root -maxdepth 2 -name '*.sql' -o -maxdepth 2 -name '*.dump' -o -maxdepth 2 -name '*.sql.gz' 2>/dev/null | head -20",
        "dump_files"),
    run("ls -l /root/*.sql 2>/dev/null | tail -8", "root_dumps"),
    run("docker exec wechat-agent-java sh -c 'ls -l /app/backup | tail -5; which pg_dump' 2>&1", "in_container"),
    run("%s 'select datname || %s || pg_size_pretty(pg_database_size(datname)) from pg_database order by datname'"
        % (PG, "'='" + " "), "db_sizes"),
    run("rm -f /tmp/wa_zipcheck.py", "cleanup"),
]
sftp.close()
client.close()
print(json.dumps({"steps": steps}, ensure_ascii=True))
