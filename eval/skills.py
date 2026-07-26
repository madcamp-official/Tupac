"""스킬 — 모델이 불러 쓰는 절차서.

스킬은 코드가 아니라 마크다운이다(eval/skills/*.md). 모델은 프롬프트에서
"어떤 절차서가 있는지"(이름과 언제 쓰는지)만 보다가, 로그인 화면처럼 아는
상황을 만나면 `skill login`을 호출한다. 그러면 그 절차서 본문이 다음 스텝부터
프롬프트에 실리고, 모델은 그걸 참고해 fill·tap·wait 같은 도구를 조작한다.

판단 주체는 모델이다. 이전에는 파이썬 코드가 화면을 보고 모델보다 먼저
가로챘는데, 그러면 모델은 스킬의 존재도 모르고 감지 규칙도 코드에 하드코딩된다.
지금 구조에서는 상황 인식과 절차 수행이 모두 모델의 일이고, 코드는 절차서를
건네줄 뿐이다. 스킬을 늘리는 것도 마크다운 한 장이면 된다.

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


def catalog_text(catalog):
    """프롬프트에 실을 목록. 본문은 싣지 않는다 — 호출해야 들어온다."""
    if not catalog:
        return ""
    lines = ", ".join(f"{name}({skill['when']})" for name, skill in catalog.items())
    return (f"절차서: 아는 상황을 만나면 skill 이름 으로 절차서를 불러 그대로 따르세요.\n"
            f"있는 절차서: {lines}")


def active_text(name, skill, field_hint="", no_submit=False):
    """호출된 스킬의 본문. 이때부터 매 스텝 프롬프트에 실린다."""
    parts = [f"◆ 지금 따르는 절차서: {name}\n{skill['body']}"]
    if field_hint:
        parts.append(f"금고 필드: {field_hint}")
    if no_submit:
        parts.append("주의: 이번 실행에서는 제출·로그인 버튼을 누르지 마세요. 채우기까지만.")
    return "\n\n".join(parts)


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
