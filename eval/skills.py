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
        # 쪼갠 다음에 공백을 지워야 한다. 먼저 지우면 쪼갤 게 남지 않아서
        # "이메일 주소"가 "이메일주소" 한 덩어리가 되고, "이메일 또는 전화번호"
        # 같은 라벨에 안 걸린다(실측: 카카오톡 아이디 칸을 못 찾았다).
        for word in (squash(part) for part in re.split(r"[,\s]+", description)):
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


def login_fields(observation, entries):
    """로그인 화면의 입력창을 (노드, 필드) 순서대로.

    라벨로 맞추지 않고 password 플래그를 먼저 본다. 로그인 화면에서 비밀번호가
    아닌 입력창은 곧 계정 식별자이기 때문이다. 라벨은 앱마다 제각각이라 믿을 게
    못 된다 — 카카오톡은 아이디 칸을 "이메일 또는 전화번호"라고 부른다. 이걸
    라벨로 맞추면 email(공통 정보)로 가지만, 정작 필요한 건 이 앱의 username이다.

    화면에 나온 순서 그대로 돌려준다. 대개 아이디가 위, 비밀번호가 아래다.

    다만 이 단순화는 "아이디 한 칸 + 비밀번호 한 칸"일 때만 옳다. 인증번호 화면
    처럼 칸이 하나뿐인데 비밀번호가 아니면, 그 칸을 아이디로 보고 채워버린다
    (실측: 2단계 인증 화면에 아이디를 넣으려 했다). 그래서 칸 구성이 로그인
    폼과 정확히 맞을 때만 플래그로 가르고, 아니면 라벨을 보고, 그래도 모르면
    맡지 않는다(None). 모르면 모델에게 넘기는 편이 잘못 채우는 것보다 낫다.
    """
    editables = [node for node in observation.get("nodes", []) if node.get("editable")]
    passwords = [node for node in editables if node.get("password")]
    others = [node for node in editables if not node.get("password")]

    if len(passwords) == 1 and len(others) == 1:
        return [(node, "password" if node.get("password") else "username")
                for node in editables]

    # 칸 구성이 다르면 라벨에 기대는 수밖에 없다. 확실한 것만 남긴다.
    found = []
    for node in editables:
        field = "password" if node.get("password") else field_for(label_of(node), entries)
        if field is not None:
            found.append((node, field))
    return found


# 제출 버튼으로 볼 말. 로그인 화면에는 "비밀번호 찾기", "회원가입"처럼 눌러선
# 안 되는 버튼이 함께 있어서, 걸러낼 말도 같이 둔다.
SUBMIT_WORDS = ("로그인", "signin", "login", "확인", "다음", "계속")
NOT_SUBMIT_WORDS = ("찾기", "가입", "취소", "다른", "간편", "재설정", "도움", "문의")


def submit_button(observation):
    """로그인 제출 버튼 노드. 확실하지 않으면 None.

    후보가 여럿이면 고르지 않는다. 로그인 화면에서 엉뚱한 버튼을 누르는 건
    되돌리기 어렵고(회원가입 흐름으로 빠지거나 계정이 만들어질 수도 있다),
    사람이 한 번 누르는 비용보다 크다.
    """
    found = []
    for node in observation.get("nodes", []):
        if node.get("editable") or not node.get("clickable"):
            continue
        label = squash(label_of(node))
        if not label or len(label) > 12:
            continue
        if any(word in label for word in NOT_SUBMIT_WORDS):
            continue
        if any(word in label for word in SUBMIT_WORDS):
            found.append(node)
    return found[0] if len(found) == 1 else None


def login(observation, history, field_hint, auto_submit=True):
    """로그인 화면에서 할 다음 행동 하나. 맡을 게 없으면 None.

    한 스텝에 하나씩 돌려주는 건 에이전트 루프가 그렇게 돌기 때문이다. 순서는
    칸 누르기 → 채우기다. 포커스가 없으면 화면의 첫 입력창에 들어가므로 반드시
    누르고 채워야 한다.

    제출까지 한다(auto_submit=False면 채우고 멈춘다). 다만 조건이 붙는다.
    비밀번호를 이번 실행에서 스킬이 직접 채웠고, 제출 버튼이 하나로 분명할
    때만이다. 화면에 원래 값이 있었거나 버튼 후보가 여럿이면 사람에게 넘긴다.
    """
    if not is_login_screen(observation):
        return None

    entries = field_entries(field_hint)
    if not entries:
        return None

    done = filled_so_far(history)
    last = history[-1] if history else ""

    for node, field in login_fields(observation, entries):
        if field in done:
            continue

        # 방금 이 칸을 눌렀으면 이제 채운다. 아니면 먼저 누른다.
        if f"tap {node['id']} →" in last and "성공" in last:
            return {"action": "fill", "field": field,
                    "reason": f"{field} 칸에 금고 값을 넣습니다 (값은 폰 안에서 처리)"}
        return {"action": "tap", "node_id": node["id"],
                "reason": f"{field} 칸을 누릅니다"}

    if not done:
        return None

    # 제출은 스킬이 직접 채운 경우에만 누른다. 화면에 이미 값이 있었다거나
    # 사람이 넣은 값이면 무엇이 들어있는지 알 수 없고, 그걸 대신 보내는 건
    # 다른 문제다. 채운 게 우리라는 걸 아는 지금만 안전하다.
    filled = ", ".join(sorted(done))
    if auto_submit and "password" in done:
        button = submit_button(observation)
        if button and f"tap {button['id']} →" not in " ".join(history[-2:]):
            return {"action": "tap", "node_id": button["id"],
                    "reason": f"{filled}을(를) 채웠으니 로그인을 누릅니다"}
    return {"action": "done",
            "reason": f"{filled}을(를) 채웠습니다."
                      + ("" if auto_submit else " 로그인 버튼은 직접 눌러주세요")}


def next_action(observation, history, field_hint, auto_submit=True):
    """스킬이 맡을 수 있으면 행동을, 아니면 None을 돌려준다."""
    return login(observation, history, field_hint, auto_submit)
