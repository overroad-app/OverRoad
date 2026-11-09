"""접수양식 02장(1~6번 작성란)을 새 내용으로 바꾼다. 서식은 기존 문단 서식을 그대로 쓴다."""
import re, sys
from xml.sax.saxutils import escape

src, dst = sys.argv[1], sys.argv[2]
x = open(src, encoding='utf-8').read()

def body(t):
    return ('<w:p><w:pPr><w:spacing w:after="60" w:line="300" w:lineRule="auto"/></w:pPr>'
            f'<w:r><w:t xml:space="preserve">{escape(t)}</w:t></w:r></w:p>')

def sub(t):
    return ('<w:p><w:pPr><w:keepNext/><w:spacing w:before="100" w:after="40" w:line="300" w:lineRule="auto"/></w:pPr>'
            f'<w:r><w:rPr><w:b/><w:bCs/></w:rPr><w:t xml:space="preserve">{escape(t)}</w:t></w:r></w:p>')

def bullet(t):
    lead, rest = (t.split('|', 1) if '|' in t else ('', t))
    runs = '<w:r><w:t xml:space="preserve">· </w:t></w:r>'
    if lead:
        runs += f'<w:r><w:rPr><w:b/><w:bCs/></w:rPr><w:t xml:space="preserve">{escape(lead)}</w:t></w:r>'
    runs += f'<w:r><w:t xml:space="preserve">{escape(rest)}</w:t></w:r>'
    return ('<w:p><w:pPr><w:spacing w:after="40" w:line="300" w:lineRule="auto"/><w:ind w:left="240" w:hanging="200"/></w:pPr>'
            + runs + '</w:p>')

def render(lines):
    out = []
    for l in lines:
        if l.startswith('## '): out.append(sub(l[3:]))
        elif l.startswith('- '): out.append(bullet(l[2:]))
        else: out.append(body(l))
    return ''.join(out)

exec(open(sys.argv[3], encoding='utf-8').read())  # SECTIONS = [list of lines] x 6~7
APP_NAME = 'OverRoad'
# 앱명 / 프로젝트명 칸 (01 참가신청서, 02 소개 머리말 두 곳)
x = x.replace('프로젝트명: HyperConnect', '프로젝트명: ' + APP_NAME).replace('>HyperConnect<', '>' + APP_NAME + '<')
assert 'HyperConnect' not in x, '양식에 가제가 남아 있음'

# 1~6번 작성란 = 문서의 2~7번째 표. 각 표 셀의 문단들을 새 내용으로 바꾼다.
tbls = [m for m in re.finditer(r'<w:tbl>.*?</w:tbl>', x, flags=re.S)]
assert len(tbls) == 10, len(tbls)
# 1~6번 + (원고에 있으면) 7번 참고자료 = 2~8번째 표
for k in range(len(SECTIONS), 0, -1):  # 뒤에서부터 바꿔야 앞쪽 위치가 유지된다
    t = tbls[k]
    tb = t.group()
    tcs = re.findall(r'<w:tc>.*?</w:tc>', tb, flags=re.S)
    assert len(tcs) == 1, (k, len(tcs))
    tc = tcs[0]
    head = re.match(r'<w:tc>(?:<w:tcPr>.*?</w:tcPr>)?', tc, flags=re.S).group()
    new_tc = head + render(SECTIONS[k - 1]) + '</w:tc>'
    tb2 = tb.replace(tc, new_tc)
    x = x[:t.start()] + tb2 + x[t.end():]
open(dst, 'w', encoding='utf-8').write(x)
print('ok')
