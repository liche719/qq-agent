"""MySQL → PostgreSQL 数据迁移演练 v2（本地，2026-09-17）。

v1 用 `--batch` TSV 失败在两处（都是实测踩到的）：
  ① MySQL 的 `bit(1)` 列在批量输出里是**原始控制字节**（0x00/0x01），PG 的 text COPY 直接拒收；
  ② 长文本里未转义的换行会把一行拆开 → "missing data for column"。
v2 改用 `mysql --xml` 导出：NULL 用 `xsi:nil` 明确表达、特殊字符由 XML 承载，
转义与 NULL 标记全部由我在 Python 里按 PG `COPY ... FORMAT text` 的规则生成。

QRTZ_* 不迁：Quartz 调度状态由 `scheduled_task` 表在启动时重新 sync（项目已有自愈逻辑）。
"""
import subprocess
import sys
import xml.etree.ElementTree as ET

MYSQL_CONTAINER = 'wechat-agent-mysql'
PG_CONTAINER = 'wa-pg-rehearsal'
DB = 'wechat_agent'
NIL = '{http://www.w3.org/2001/XMLSchema-instance}nil'


def sh(args, input_bytes=None):
    """python 3.6 兼容：`input=` 和 `stdin=` 不能同时出现（3.6 只看关键字在不在），
    所以有 input 时**不传 stdin**。服务器上只有 3.6，这个坑必踩。"""
    kwargs = {'input': input_bytes} if input_bytes is not None else {'stdin': subprocess.DEVNULL}
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)


def mysql_base(pw):
    return ['docker', 'exec', '-i', '-e', 'MYSQL_PWD=' + pw, MYSQL_CONTAINER, 'mysql',
            '--default-character-set=utf8mb4', DB]


def psql_base():
    return ['docker', 'exec', '-i', PG_CONTAINER, 'psql', '-U', 'postgres', '-d', DB,
            '-q', '-v', 'ON_ERROR_STOP=1']


def copy_escape(value):
    """PG COPY text 格式的转义（\\N 之外的都按规则转义）"""
    return (value.replace('\\', '\\\\').replace('\t', '\\t')
            .replace('\n', '\\n').replace('\r', '\\r'))


def main():
    pw = sh(['docker', 'exec', MYSQL_CONTAINER, 'printenv', 'MYSQL_ROOT_PASSWORD']).stdout.decode().strip()
    my = mysql_base(pw)
    pg = psql_base()

    tables = sh(my + ['-N', '-B', '-e', 'show tables']).stdout.decode().split()
    tables = [t for t in tables if not t.lower().startswith('qrtz')]
    print('待迁移表数: %d' % len(tables))

    failures = []
    report = []
    for table in tables:
        cols = sh(my + ['-N', '-B', '-e',
                        "select column_name from information_schema.columns where table_schema='%s' "
                        "and table_name='%s' order by ordinal_position" % (DB, table)]).stdout.decode().split()
        if not cols:
            continue
        types = sh(my + ['-N', '-B', '-e',
                         "select data_type from information_schema.columns where table_schema='%s' "
                         "and table_name='%s' order by ordinal_position" % (DB, table)]).stdout.decode().split()
        quoted = ','.join('"%s"' % c for c in cols)
        # bit(1) 列必须转成数字：MySQL 批量/XML 输出都会吐原始控制字节（0x00/0x01），
        # 前者让 PG 的 COPY 拒收、后者让 XML 本身不合法（实测踩到两次）
        select_list = ','.join(
            ('cast(`%s` as unsigned) as `%s`' % (c, c)) if t == 'bit' else ('`%s`' % c)
            for c, t in zip(cols, types)) if len(types) == len(cols) else '*'

        p = sh(pg + ['-c', 'truncate table "%s" cascade' % table])
        if p.returncode != 0:
            failures.append((table, 'truncate: ' + p.stderr.decode()[:200]))
            continue

        xml_bytes = sh(my + ['--xml', '-e', 'select %s from `%s`' % (select_list, table)]).stdout
        try:
            root = ET.fromstring(xml_bytes)
        except ET.ParseError as exc:
            failures.append((table, 'xml: %s' % exc))
            continue

        out = []
        for row in root.findall('row'):
            fields = row.findall('field')
            if len(fields) != len(cols):
                failures.append((table, '列数不符: %d != %d' % (len(fields), len(cols))))
                out = []
                break
            values = []
            for field in fields:
                # 只有 xsi:nil 才是 NULL；空元素是**空字符串**（这个区别一开始搞错，丢了 19 条文本）
                if field.get(NIL) == 'true':
                    values.append('\\N')
                else:
                    values.append(copy_escape(field.text or ''))
            out.append('\t'.join(values))
        if not out and failures and failures[-1][0] == table:
            continue

        payload = ('\n'.join(out) + ('\n' if out else '')).encode('utf-8')
        load = sh(pg + ['-c', '\\copy "%s" (%s) FROM STDIN WITH (FORMAT text)' % (table, quoted)], payload)
        if load.returncode != 0:
            failures.append((table, 'load: ' + load.stderr.decode()[:300]))
            continue

        if 'id' in cols:
            sh(pg + ['-c', "select setval(pg_get_serial_sequence('%s','id'), "
                           "coalesce((select max(id) from \"%s\"), 1))" % (table, table)])

        src = sh(my + ['-N', '-B', '-e', 'select count(*) from `%s`' % table]).stdout.decode().strip()
        dst = sh(pg + ['-t', '-c', 'select count(*) from "%s"' % table]).stdout.decode().strip()
        report.append((table, src, dst, 'OK' if src == dst else 'MISMATCH'))

    print('\n%-30s %8s %8s  %s' % ('table', 'mysql', 'pg', 'status'))
    for table, src, dst, status in report:
        print('%-30s %8s %8s  %s' % (table, src, dst, status))
    print('\n合计: mysql=%d pg=%d' % (sum(int(r[1]) for r in report), sum(int(r[2]) for r in report)))

    print('\nNULL 抽查:')
    for table, col in [('conversation_memory', 'expires_at'), ('stored_media', 'extracted_text'),
                       ('agent_quest_run', 'reason'), ('reminder_task', 'cron'),
                       ('user_work_memory', 'valid_until')]:
        a = sh(my + ['-N', '-B', '-e', 'select count(*) from `%s` where `%s` is null' % (table, col)]).stdout.decode().strip()
        b = sh(pg + ['-t', '-c', 'select count(*) from "%s" where "%s" is null' % (table, col)]).stdout.decode().strip()
        print('  %-24s %-16s mysql=%-5s pg=%-5s %s' % (table, col, a, b, 'OK' if a == b else 'DIFF'))

    print('\nbit(1) 抽查（PG 应为 boolean t/f）:')
    for table, col in [('user_profile', 'memory_enabled'), ('user_work_memory', 'archived')]:
        b = sh(pg + ['-t', '-c', 'select %s, count(*) from "%s" group by 1 order by 1' % (col, table)]).stdout.decode().strip()
        print('  %-24s %-16s -> %s' % (table, col, ' | '.join(b.split('\n'))))

    if failures:
        print('\n失败 %d 张:' % len(failures))
        for table, why in failures:
            print('  %s -> %s' % (table, why))
        sys.exit(1)
    bad = [r for r in report if r[3] != 'OK']
    print('\n全部成功' if not bad else '\n行数不一致: %s' % bad)


if __name__ == '__main__':
    main()
