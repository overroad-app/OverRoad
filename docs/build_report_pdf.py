"""OverRoad 통합보고서 PDF 만들기.

철학·Tier 표, 기획서(접수양식 02장 원고), 기술 리포트, 기능 개선 기록, 지연시간 실측을 HTML 한 장으로 묶고
Chrome 헤드리스로 PDF를 만든다. (이 컴퓨터에는 pandoc·LibreOffice가 없어 간단한 마크다운 변환을 직접 한다)

  python3 -I docs/build_report_pdf.py   → docs/OverRoad_통합보고서.pdf
"""
import html
import os
import re
import subprocess
import sys
import time
from pathlib import Path

DOCS = Path(__file__).resolve().parent
OUT_HTML = DOCS / "_report.html"
# PAIRED=1: 요약형 접수양식(content_diet.py)과 짝을 이루는 상세판. 2부가 "기획서 상세판"이 되고, 양식 문항 ↔ 보고서 부 안내를 넣는다
PAIRED = os.environ.get("PAIRED") == "1"
OUT_PDF = DOCS / ("OverRoad_통합보고서_상세판.pdf" if PAIRED else "OverRoad_통합보고서.pdf")
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"


def inline(t):
    t = html.escape(t, quote=False)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1", t)
    return t


def md_to_html(md, shift=0):
    """이 문서들에 쓰인 마크다운만 처리한다: 제목, 문단, 목록(한 단계 들여쓰기), 표, 코드 블록, 인용, 구분선, 그림."""
    out, lines, i = [], md.splitlines(), 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("```"):
            j = i + 1
            while j < len(lines) and not lines[j].startswith("```"):
                j += 1
            out.append("<pre>" + html.escape("\n".join(lines[i + 1:j])) + "</pre>")
            i = j + 1
            continue
        m = re.match(r"!\[(.*?)\]\((.+?)\)\s*$", line)
        if m:   # 그림: docs/ 기준 상대 경로 (HTML도 docs/에 만들어지므로 그대로 쓴다)
            out.append(f'<figure style="margin:14px 0;text-align:center"><img src="{html.escape(m.group(2))}" style="max-width:100%;border:1px solid #e2e8f0;border-radius:8px">'
                       f'<figcaption style="font-size:10pt;color:#64748b;margin-top:4px">{html.escape(m.group(1))}</figcaption></figure>')
            i += 1
            continue
        m = re.match(r"(#{1,4}) (.*)", line)
        if m:
            lv = min(len(m.group(1)) + shift, 6)
            out.append(f"<h{lv}>{inline(m.group(2))}</h{lv}>")
            i += 1
            continue
        if line.strip() == "---":
            out.append("<hr>")
            i += 1
            continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-{3,}:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            head, *body = rows
            # 줄맞춤: 칸 내용이 모두 짧은 열은 줄바꿈하지 않는다 (좁은 열에서 낱말이 쪼개지는 것을 막는다)
            plain = lambda c: re.sub(r"[`*]", "", c)
            ncol = len(head)
            body = [(r + [""] * ncol)[:ncol] for r in body]
            nw = [max(len(plain(r[k])) for r in [head] + body) <= 12 for k in range(ncol)]
            cls = lambda k: ' class="nw"' if nw[k] else ""
            out.append("<table><thead><tr>" + "".join(f"<th{cls(k)}>{inline(c)}</th>" for k, c in enumerate(head)) + "</tr></thead><tbody>"
                       + "".join("<tr>" + "".join(f"<td{cls(k)}>{inline(c)}</td>" for k, c in enumerate(r)) + "</tr>" for r in body)
                       + "</tbody></table>")
            continue
        if line.startswith(">"):
            block = []
            while i < len(lines) and lines[i].startswith(">"):
                block.append(lines[i][1:].strip())
                i += 1
            out.append("<blockquote>" + md_to_html("\n".join(block)) + "</blockquote>")
            continue
        if re.match(r"\s*(- |\d+\. )", line):
            ordered = bool(re.match(r"\s*\d+\. ", line))
            items = []
            while i < len(lines) and (re.match(r"\s*(- |\d+\. )", lines[i]) or (lines[i].startswith("  ") and items)):
                l = lines[i]
                if re.match(r"\s{2,}(- |\d+\. )", l):
                    items[-1][1].append(re.sub(r"^\s*(- |\d+\. )", "", l))
                elif re.match(r"\s*(- |\d+\. )", l):
                    items.append([re.sub(r"^\s*(- |\d+\. )", "", l), []])
                else:
                    items[-1][0] += " " + l.strip()
                i += 1
            tag = "ol" if ordered else "ul"
            out.append(f"<{tag}>" + "".join(
                f"<li>{inline(t)}" + ("<ul>" + "".join(f"<li>{inline(s)}</li>" for s in subs) + "</ul>" if subs else "") + "</li>"
                for t, subs in items) + f"</{tag}>")
            continue
        if not line.strip():
            i += 1
            continue
        para = [line]
        i += 1
        while i < len(lines) and lines[i].strip() and not re.match(r"(#|\||>|```|\s*- |\s*\d+\. |---)", lines[i]):
            para.append(lines[i])
            i += 1
        out.append("<p>" + inline(" ".join(p.strip() for p in para)) + "</p>")
    return "\n".join(out)


def proposal_html():
    """기획서 원고(docs/proposal/content.py)를 접수양식 항목 제목과 함께 HTML로."""
    ns = {}
    exec((DOCS / "proposal" / "content.py").read_text(encoding="utf-8"), ns)
    titles = ["1. 앱 한 줄 소개", "2. 기획 배경", "3. 앱 주요 기능 및 차량 특화 요소", "4. 주요 사용 시나리오",
              "5. 개발 계획", "6. 기대효과 및 발전 가능성"]
    out = []
    for title, lines in zip(titles, ns["SECTIONS"]):
        out.append(f"<h2>{title}</h2>")
        bullets = []
        def flush():
            if bullets:
                out.append("<ul>" + "".join(bullets) + "</ul>")
                bullets.clear()
        for l in lines:
            if l.startswith("## "):
                flush()
                out.append(f"<h3>{inline(l[3:])}</h3>")
            elif l.startswith("- "):
                lead, rest = l[2:].split("|", 1) if "|" in l else ("", l[2:])
                bullets.append(f"<li>{'<b>' + inline(lead) + '</b>' if lead else ''}{inline(rest)}</li>")
            else:
                flush()
                out.append(f"<p>{inline(l)}</p>")
        flush()
    return "\n".join(out)


PARTS = [
    ("1부. OverRoad가 지향하는 것 · Tier별 기능 구분", lambda: md_to_html((DOCS / "philosophy.md").read_text(), 1)),
    ("2부. 기획서 상세판 (접수양식 각 문항의 자세한 설명)" if PAIRED else "2부. 기획서 (접수양식 02장 · 앱 프로젝트 소개)", proposal_html),
    ("3부. 기술 리포트 — 판별 로직", lambda: md_to_html((DOCS / "technical_report.md").read_text(), 1)),
    ("4부. 기능별 개선 기록 · 기존 서비스 비교", lambda: md_to_html((DOCS / "feature_revisions.md").read_text(), 1)),
    ("5부. 급제동 경고 지연시간 실측", lambda: md_to_html((DOCS / "latency.md").read_text(), 1)),
    ("6부. 예상 MAU · Retention Rate 산정", lambda: md_to_html((DOCS / "metrics.md").read_text(), 1)),
]

CSS = """
@page { size: A4; margin: 18mm 16mm 18mm 16mm; }
body { font-family: 'Apple SD Gothic Neo', 'Noto Sans KR', sans-serif; font-size: 10.5pt; line-height: 1.6; color: #1d2330; }
h1.part { font-size: 20pt; color: #16324F; border-bottom: 3px solid #16324F; padding-bottom: 6px; page-break-before: always; margin-top: 0; }
h2 { font-size: 15pt; color: #16324F; margin-top: 22px; border-bottom: 1px solid #c9d3e0; padding-bottom: 3px; }
h3 { font-size: 12.5pt; color: #24476e; margin-top: 16px; }
h4, h5, h6 { font-size: 11pt; color: #24476e; }
table { border-collapse: collapse; width: 100%; margin: 8px 0 12px; font-size: 9.5pt; page-break-inside: auto; }
tr { page-break-inside: avoid; }
th, td { border: 1px solid #c9d3e0; padding: 4px 6px; vertical-align: top; text-align: left; }
th { background: #eef2f7; vertical-align: middle; }
table { word-break: keep-all; overflow-wrap: break-word; }
.nw { white-space: nowrap; }
td code { word-break: normal; overflow-wrap: anywhere; }
code { font-family: Menlo, monospace; font-size: 9pt; background: #f2f4f7; padding: 0 3px; border-radius: 3px; }
pre { font-family: Menlo, monospace; font-size: 8.5pt; background: #f5f7fa; border: 1px solid #dde3ea; padding: 8px; white-space: pre-wrap; }
blockquote { border-left: 4px solid #9db3cc; margin: 8px 0; padding: 4px 12px; background: #f6f8fb; }
ul, ol { padding-left: 20px; margin: 4px 0 8px; }
li { margin: 2px 0; }
hr { border: none; border-top: 1px dashed #c9d3e0; margin: 18px 0; }
.cover { height: 250mm; display: flex; flex-direction: column; justify-content: center; }
.cover h1 { font-size: 34pt; color: #16324F; margin: 0; border: none; }
.cover .sub { font-size: 15pt; color: #24476e; margin-top: 10px; }
.cover .meta { margin-top: 40px; color: #5a6578; font-size: 11pt; line-height: 1.8; }
.toc { page-break-before: always; }
.toc li { font-size: 12pt; margin: 8px 0; }
"""


GUIDE = """<h2>이 보고서를 읽는 법</h2>
<p>접수양식에는 의도와 설계 철학, 최종 비전을 중심으로 요약해 적었고, 자세한 설명과 근거는 이 보고서에 모았습니다. 접수양식의 각 문항에 해당하는 내용은 아래에서 찾을 수 있습니다.</p>
<table><thead><tr><th class="nw">접수양식 문항</th><th>자세한 내용이 있는 곳</th></tr></thead><tbody>
<tr><td class="nw">2. 기획 배경</td><td>2부 2번 (운전자가 겪는 불편한 순간, 점유율 수치와 기준)</td></tr>
<tr><td class="nw">3. 주요 기능·차량 특화</td><td>2부 3번 (여덟 기능의 동작 조건, 주의 분산 설계) · 3부 (판별 로직과 수치) · 4부 (기능별 개선 기록, 기존 서비스 비교)</td></tr>
<tr><td class="nw">4. 사용 시나리오</td><td>2부 4번 (장면별 화면 문구)</td></tr>
<tr><td class="nw">5. 개발 계획</td><td>2부 5번 (SDK별 사용 내용, 가이드 준수 항목) · 5부 (지연시간 실측)</td></tr>
<tr><td class="nw">6. 기대효과·발전 가능성</td><td>2부 6번 (수익 모델, Tier별 형태와 기능, 인지하고 있는 과제) · 6부 (MAU·Retention Rate 산정) · 1부 (지향점, Tier별 기능과 진입 조건, 구현하지 못한 것과 그 이유)</td></tr>
</tbody></table>"""


def main():
    body = [f"""<div class="cover"><h1>OverRoad</h1>
<div class="sub">점유율 기반 차량 간 연결망 · 보편적인 모빌리티 경험</div>
<div class="meta">현대자동차그룹 차량용 앱 개발 공모전 출품작 통합보고서<br>
기획서 · 철학과 Tier별 기능 · 기술 리포트 · 개선 기록 · 실측 · MAU 산정<br>작성 {time.strftime('%Y-%m-%d')}</div></div>""",
            "<div class='toc'><h2>목차</h2><ol>" + "".join(f"<li>{t}</li>" for t, _ in PARTS) + "</ol>" + (GUIDE if PAIRED else "") + "</div>"]
    for title, make in PARTS:
        body.append(f"<h1 class='part'>{title}</h1>")
        body.append(make())
    OUT_HTML.write_text(f"<!doctype html><html lang='ko'><head><meta charset='utf-8'><title>OverRoad 통합보고서</title>"
                        f"<style>{CSS}</style></head><body>{''.join(body)}</body></html>", encoding="utf-8")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
                    f"--print-to-pdf={OUT_PDF}", OUT_HTML.as_uri()], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    if not len(sys.argv) > 1: OUT_HTML.unlink()
    print(OUT_PDF)


if __name__ == "__main__":
    sys.exit(main())
