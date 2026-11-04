"""HyperConnect 문서 모음 PDF 만들기.

철학·Tier 표, 기획서(접수양식 02장 원고), 기술 리포트, 기능 개선 기록, 지연시간 실측을 HTML 한 장으로 묶고
Chrome 헤드리스로 PDF를 만든다. (이 컴퓨터에는 pandoc·LibreOffice가 없어 간단한 마크다운 변환을 직접 한다)

  python3 -I docs/build_report_pdf.py   → docs/HyperConnect_문서모음.pdf
"""
import html
import re
import subprocess
import sys
import time
from pathlib import Path

DOCS = Path(__file__).resolve().parent
OUT_HTML = DOCS / "_report.html"
OUT_PDF = DOCS / "HyperConnect_문서모음.pdf"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"


def inline(t):
    t = html.escape(t, quote=False)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1", t)
    return t


def md_to_html(md, shift=0):
    """이 문서들에 쓰인 마크다운만 처리한다: 제목, 문단, 목록(한 단계 들여쓰기), 표, 코드 블록, 인용, 구분선."""
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
            out.append("<table><thead><tr>" + "".join(f"<th>{inline(c)}</th>" for c in head) + "</tr></thead><tbody>"
                       + "".join("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in body)
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
    ("1부. HyperConnect가 지향하는 것 · Tier별 기능 구분", lambda: md_to_html((DOCS / "philosophy.md").read_text(), 1)),
    ("2부. 기획서 (접수양식 02장 · 앱 프로젝트 소개)", proposal_html),
    ("3부. 기술 리포트 — 판별 로직", lambda: md_to_html((DOCS / "technical_report.md").read_text(), 1)),
    ("4부. 기능별 개선 기록 · 기존 서비스 비교", lambda: md_to_html((DOCS / "feature_revisions.md").read_text(), 1)),
    ("5부. 급제동 경고 지연시간 실측", lambda: md_to_html((DOCS / "latency.md").read_text(), 1)),
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
th { background: #eef2f7; }
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


def main():
    body = [f"""<div class="cover"><h1>HyperConnect</h1>
<div class="sub">점유율 기반 차량 간 연결망 · 보편적인 모빌리티 경험</div>
<div class="meta">현대자동차그룹 차량용 앱 개발 공모전 출품작 문서 모음<br>
기획서 · 철학과 Tier별 기능 · 기술 리포트 · 개선 기록 · 실측<br>작성 {time.strftime('%Y-%m-%d')}<br>
(앱 이름 HyperConnect는 가칭)</div></div>""",
            "<div class='toc'><h2>목차</h2><ol>" + "".join(f"<li>{t}</li>" for t, _ in PARTS) + "</ol></div>"]
    for title, make in PARTS:
        body.append(f"<h1 class='part'>{title}</h1>")
        body.append(make())
    OUT_HTML.write_text(f"<!doctype html><html lang='ko'><head><meta charset='utf-8'><title>HyperConnect 문서 모음</title>"
                        f"<style>{CSS}</style></head><body>{''.join(body)}</body></html>", encoding="utf-8")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
                    f"--print-to-pdf={OUT_PDF}", OUT_HTML.as_uri()], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    if not len(sys.argv) > 1: OUT_HTML.unlink()
    print(OUT_PDF)


if __name__ == "__main__":
    sys.exit(main())
