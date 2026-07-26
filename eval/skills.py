"""정해진 절차로 끝나는 일은 모델에게 묻지 않는다.

로그인처럼 개인정보를 다루는 화면은 할 일이 정해져 있다. 아이디 칸을 눌러
채우고, 비밀번호 칸을 눌러 채운다. 화면을 보고 추론할 일이 아니라 순서를
지키는 일이다.

이걸 모델에게 맡기지 않는 이유는 실측 때문이다. 1.2B는 스무 개 중 하나를 고르는
것도 못 했고(0~2/5, 실행마다 답도 달랐다), 규칙으로 좁혀주자 17/17이 됐다.
개인정보를 다루는 자리에서는 그 편차를 감당할 수 없다. 비밀번호가 아이디 칸에
들어가면 화면에 그대로 노출된다.

브레인과 분리해 둔 것은 역할이 다르기 때문이다. 브레인은 "무엇을 할지" 판단하고,
스킬은 "이미 아는 절차"를 밟는다. run()이 스킬을 먼저 물어보고, 스킬이 맡지
않겠다고 하면 그때 모델을 부른다.
"""
import re

# 이 낱말이 화면에 있으면 로그인 화면으로 본다. 입력창이 있는 것만으로는
# 부족하다. 검색창 하나 있는 화면까지 로그인으로 오인하면 곤란하다.
LOGIN_MARKERS = ("로그인", "signin", "sign in", "log in", "login")


def label_of(node):
    return (node.get("text") or node.get("content_description") or "").strip()


def squash(text):
    return re.sub(r"\s+", "", text.lower())


def field_entries(hint):
    """폰이 알려준 "username(아이디, 로그인 ID...), password(비밀번호...)"를 쪼갠다.

    필드 목록을 여기 적어두지 않는 이유는 앱과 어긋나지 않기 위해서다. 금고에
    필드를 추가하면 이쪽이 자동으로 따라온다.
    """
    entries = []
    starts = list(re.finditer(r"(?:^|,\s*)([a-z_][a-z0-9_]*)\(", hint))
    for index, found in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(hint)
        entries.append((found.group(1), hint[found.end():end].rstrip().rstrip(",").rstrip(")")))
    return entries


def field_for(label, entries):
    """입력창 라벨이 어느 필드인지. 못 정하면 None.

    빈 입력창은 대개 힌트 글자를 text로 갖고 있어("비밀번호를 입력하세요")
    라벨만으로 판별된다. 연속 두 글자 이상 겹칠 때만 인정해 우연한 일치를 뺀다.
    """
    haystack = squash(label)
    if not haystack:
        return None
    best, best_score = None, 0
    for key, description in entries:
        for word in re.split(r"[,\s]+", squash(description)):
            if len(word) >= 2 and word in haystack and len(word) > best_score:
                best, best_score = key, len(word)
        if key in haystack and len(key) > best_score:
            best, best_score = key, len(key)
    return best


def is_login_screen(observation):
    """입력창이 있고, 화면 어딘가에 로그인이라는 말이 있으면."""
    nodes = observation.get("nodes", [])
    if not any(node.get("editable") for node in nodes):
        return False
    return any(marker in squash(label_of(node))
               for node in nodes for marker in LOGIN_MARKERS)


def filled_so_far(history):
    """이번 실행에서 이미 채운 필드. 이력 문구에서 읽는다."""
    return {match.group(1)
            for line in history
            for match in [re.search(r"fill (\w+) →.*성공", line)]
            if match}


def login(observation, history, field_hint):
    """로그인 화면에서 할 다음 행동 하나. 맡을 게 없으면 None.

    한 스텝에 하나씩 돌려주는 건 에이전트 루프가 그렇게 돌기 때문이다. 순서는
    칸 누르기 → 채우기다. 포커스가 없으면 화면의 첫 입력창에 들어가므로 반드시
    누르고 채워야 한다.

    제출은 하지 않는다. 자격증명을 보내는 건 되돌릴 수 없고, 잘못된 화면이면
    그대로 유출이다. 마지막 한 번은 사람이 누르는 게 맞다.
    """
    if not is_login_screen(observation):
        return None

    entries = field_entries(field_hint)
    if not entries:
        return None

    done = filled_so_far(history)
    last = history[-1] if history else ""

    for node in observation.get("nodes", []):
        if not node.get("editable"):
            continue
        field = field_for(label_of(node), entries)
        if field is None or field in done:
            continue

        # 방금 이 칸을 눌렀으면 이제 채운다. 아니면 먼저 누른다.
        if f"tap {node['id']} →" in last and "성공" in last:
            return {"action": "fill", "field": field,
                    "reason": f"{field} 칸에 금고 값을 넣습니다 (값은 폰 안에서 처리)"}
        return {"action": "tap", "node_id": node["id"],
                "reason": f"{field} 칸을 누릅니다"}

    if done:
        return {"action": "done",
                "reason": f"{', '.join(sorted(done))}을(를) 채웠습니다. "
                          f"로그인 버튼은 직접 눌러주세요"}
    return None


def next_action(observation, history, field_hint):
    """스킬이 맡을 수 있으면 행동을, 아니면 None을 돌려준다."""
    return login(observation, history, field_hint)
