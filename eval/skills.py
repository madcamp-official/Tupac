"""스킬 — 모델이 불러 쓰는 절차서.

스킬은 코드가 아니라 마크다운 문서다(eval/skills/*.md). 호출하는 함수 같은 것이
아니다 — 절차서 전체가 처음부터 프롬프트에 참고자료로 실려 있고, 모델이 그걸
읽다가 맞는 상황(로그인 화면, 개인정보 폼)을 만나면 스스로 그 절차를 따른다.
불러오는 단계도, 활성화 상태도 없다.

판단 주체는 모델이다. 이전에는 파이썬 코드가 화면을 보고 모델보다 먼저
가로챘는데, 그러면 모델은 스킬의 존재도 모르고 감지 규칙도 코드에 하드코딩된다.
지금은 어느 절차서가 지금 상황에 맞는지도 모델이 읽고 정한다. 스킬을 늘리는
것도 마크다운 한 장이면 된다. 코드는 파일을 읽어 프롬프트에 싣는 것뿐이다.

안전은 스킬이 아니라 도구가 지킨다. 값이 폰을 벗어나지 않는 것, 계정이 화면의
앱 것으로 묶이는 것은 device_fill_field가 보장한다. 모델이 절차를 틀려도
그 보증은 깨지지 않는다.

파일 형식:
    ---
    name: login
    when: 아이디와 비밀번호를 입력하는 로그인 화면을 만났을 때
    ---
    (절차 본문)
"""
import pathlib
import re

DIR = pathlib.Path(__file__).resolve().parent / "skills"


def load():
    """스킬 이름 -> {"when": 언제 쓰는지, "body": 절차 본문}."""
    catalog = {}
    for path in sorted(DIR.glob("*.md")):
        meta, body = _parse(path.read_text(encoding="utf-8"))
        name = meta.get("name") or path.stem
        catalog[name] = {"when": meta.get("when", ""), "body": body.strip()}
    return catalog


def reference_text(catalog, field_hint="", no_submit=False):
    """절차서 전체를 참고자료로 렌더링한다. 매 스텝 프롬프트에 그대로 실린다.

    본문을 처음부터 다 준다. 목록만 주고 모델이 불러오게 하는 방식은 절차서를
    "호출하는 함수"로 만드는 것이라 버렸다. 문서는 읽으라고 있는 것이다 —
    어느 절차서가 지금 화면에 맞는지도 모델이 읽고 정한다.
    """
    if not catalog:
        return ""
    parts = [f"◆ {name} — 이럴 때: {skill['when']}\n{skill['body']}"
             for name, skill in catalog.items()]
    if field_hint:
        parts.append(f"금고 필드: {field_hint}")
    if no_submit:
        parts.append("주의: 이번 실행에서는 제출·로그인 버튼을 누르지 마세요. 채우기까지만.")
    return ("절차서 — 아래 상황을 만나면 해당 절차를 그대로 따르세요.\n\n"
            + "\n\n".join(parts))


def _parse(text):
    match = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.DOTALL)
    if not match:
        return {}, text
    meta = {}
    for line in match.group(1).splitlines():
        key, _, value = line.partition(":")
        if value:
            meta[key.strip()] = value.strip()
    return meta, match.group(2)
