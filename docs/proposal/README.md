# 기획서(접수양식 02장) 원고

- `content.py`: 접수양식 02장 1~6번 작성란의 원고. 여기서 고친다.
  `## ` = 소제목, `- 굵은 머리말:| 본문` = 글머리표, 그 외 = 본문 문단
- `build_form.py`: 원고를 접수양식 Word 파일의 1~6번 표 안에 넣는다 (기존 서식 그대로).

```bash
# 원본 양식(개인정보 칸이 비어 있는 접수양식)을 풀어 둔 폴더가 필요하다
unzip -q HyperConnect_접수양식_1023백업.docx -d /tmp/form_src
cp -R /tmp/form_src /tmp/form_out
python3 docs/proposal/build_form.py /tmp/form_src/word/document.xml /tmp/form_out/word/document.xml docs/proposal/content.py
(cd /tmp/form_out && zip -qXr ../OverRoad_접수양식.docx .)
```

접수양식 Word 파일 자체는 개인정보(참가 정보·서명)를 적게 되므로 저장소에 올리지 않는다.
