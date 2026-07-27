"""스킬 — 모델이 읽는 절차서.

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

흐름은 셋으로 나뉜다.

    클라우드 모델   화면까지 찾아가고, "여기엔 어떤 값이 필요하다"까지 판단한다.
                    값은 받지 않는다. need <필드들> 로 키만 넘긴다.
    code            그 키로 폰의 금고에서 값을 꺼내 기기 안 모델에게 넘긴다.
    기기 안 모델    받은 값을 화면의 칸에 넣고 제출까지 한다.

절차서는 두 쪽 모두를 위한 문서다. 클라우드용 한 줄("need를 내라")과 기기 안
모델용 절차가 같은 파일에 있다. 상황과 절차가 한 곳에 있어야 어긋나지 않는다.

파일 형식:
    ---
    name: login
    when: 아이디와 비밀번호를 입력하는 로그인 화면
    needs: username, password
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
        cloud, device = _sections(body)
        catalog[name] = {
            "when": meta.get("when", ""),
            "needs": [key.strip() for key in meta.get("needs", "").split(",") if key.strip()],
            # 제출까지 하는 절차인지. 로그인은 실패해도 다시 하면 되지만 배송지·
            # 결제 폼은 주문으로 이어지므로 사람이 누른다.
            "submit": meta.get("submit", "no").lower() in ("yes", "true", "y"),
            "cloud": cloud,
            "device": device,
        }
    return catalog


def for_fields(catalog, fields):
    """요청된 필드를 가장 많이 담당하는 절차서. 없으면 None.

    클라우드가 need username password 를 내면 login 절차서가, name·주소 계열을
    내면 form 절차서가 뽑힌다. 기기 안 모델에게는 그 하나만 준다 — 관계없는
    절차까지 주면 짧은 모델이 엉뚱한 쪽을 따라간다.
    """
    wanted = set(fields)
    best, best_hits = None, 0
    for name, skill in catalog.items():
        hits = len(wanted & set(skill["needs"]))
        if hits > best_hits:
            best, best_hits = name, hits
    return best


def reference_text(catalog, field_hint="", no_submit=False):
    """절차서 전체를 참고자료로 렌더링한다. 매 스텝 프롬프트에 그대로 실린다.

    본문을 처음부터 다 준다. 목록만 주고 모델이 불러오게 하는 방식은 절차서를
    "호출하는 함수"로 만드는 것이라 버렸다. 문서는 읽으라고 있는 것이다 —
    어느 절차서가 지금 화면에 맞는지도 모델이 읽고 정한다.
    """
    if not catalog:
        return ""
    parts = [f"◆ {name} — 이럴 때: {skill['when']}"
             + (f" (필요한 값: {', '.join(skill['needs'])})" if skill["needs"] else "")
             + f"\n{skill['cloud']}"
             for name, skill in catalog.items()]
    if field_hint:
        parts.append(f"금고 필드: {field_hint}")
    if no_submit:
        parts.append("주의: 이번 실행에서는 제출·로그인 버튼을 누르지 마세요. 채우기까지만.")
    return ("절차서 — 아래 상황을 만나면 해당 절차를 그대로 따르세요.\n\n"
            + "\n\n".join(parts))


def _sections(body):
    """본문을 "## 클라우드" / "## 기기" 두 쪽으로 가른다.

    한 파일에 두 쪽을 다 적되, 각자에게는 자기 몫만 준다. 실측: 기기 안 모델이
    "클라우드 모델: need를 내세요"까지 읽고 혼란스러워했다. 읽는 쪽에 필요 없는
    지시는 짧은 모델에게 그냥 소음이다.
    """
    cloud, device, current = [], [], None
    for line in body.splitlines():
        heading = line.strip().lstrip("#").strip()
        if line.strip().startswith("##"):
            current = cloud if "클라우드" in heading else device
            continue
        if current is not None:
            current.append(line)
    return "\n".join(cloud).strip(), "\n".join(device).strip()


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


def handoff_text(plan, current):
    """기기 안 모델에게 줄 문맥. 할 단계 목록과 지금 할 한 줄.

    어느 칸에 무엇을 넣을지는 code가 이미 정했다(assign.py). 모델이 화면을 보고
    고를 필요가 없도록 노드 번호까지 박아 보여주고, 다음에 할 한 줄을 따로
    짚어준다. 실측으로 1.2B는 구체적인 예시를 그대로 베끼는 성향이 강한데,
    여기서는 그 성향이 그대로 도움이 된다 — 베낄 대상이 곧 정답이다.

    값이 프롬프트에 그대로 실린다. 클라우드에는 절대 가지 않지만, 기기 안 모델의
    컨텍스트에는 들어간다는 뜻이다. 그래서 agent.py가 로그와 이력에서는 이 값을
    가린다 — 터미널 기록이나 다음 스텝 프롬프트로 새어나가지 않게.
    """
    lines = []
    for step in plan:
        mark = "[완료]" if step["state"] == "done" else ("→" if step is current else "     ")
        lines.append(f"  {mark} {step['line']}   ({step['why']})")
    return ("정해진 순서대로 하나씩 실행합니다.\n" + "\n".join(lines)
            + f"\n\n지금 할 것: {current['line']}\n이 한 줄을 그대로 답하세요.")
