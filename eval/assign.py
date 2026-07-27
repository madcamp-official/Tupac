"""어느 칸에 어느 값이 들어가는지 code가 정한다.

기기 안 모델의 역할을 줄이기 위한 것이다. 실측으로 1.2B는 "화면을 보고 아이디
칸을 찾아라" 수준도 해내지 못했다 — 비밀번호만 세 번 반복해 넣거나, 이력 형식을
흉내낸 문자열("step2: type node_15 값을 입력 / tap node_16")을 그대로 칸에
집어넣었다. 프롬프트를 줄여도 나아지지 않았다.

그래서 판단을 code가 가져온다. 화면에서 칸을 고르는 일은 규칙으로 충분히
정확하다(아래 근거). 모델에게는 "이 줄을 실행하라"만 남긴다.

칸을 고르는 근거는 둘이다.

    password 플래그   접근성 트리가 알려주는 값이라 라벨보다 확실하다. 로그인
                      화면에서 비밀번호가 아닌 입력창은 곧 계정 식별자다.
    라벨 매칭         칸이 여럿인 폼에서 쓴다. 실측으로 라벨 10종 10/10.

두 근거 모두 앞서 실기기에서 검증했다. 카카오톡 로그인 화면(아이디 칸 라벨이
"이메일 또는 전화번호"라 라벨로는 못 맞추는 곳)과 크롬 배송지 폼에서다.
"""
import re

# 제출 버튼으로 볼 말과, 로그인 화면에 같이 있어서 눌러선 안 되는 말.
SUBMIT_WORDS = ("로그인", "signin", "login", "확인", "다음", "계속")
NOT_SUBMIT_WORDS = ("찾기", "가입", "취소", "다른", "간편", "재설정", "도움", "문의", "만들기")


def label_of(node):
    """그 칸이 무엇인지 알려주는 글자.

    빈 입력창은 text가 비어 있고 안내 문구가 hint에만 있는 경우가 있다(크롬의
    웹 폼이 그렇다). 셋 다 봐야 어느 앱에서든 칸을 알아본다.
    """
    return (node.get("text") or node.get("content_description")
            or node.get("hint") or "").strip()


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
    """입력창 라벨이 어느 필드인지. 못 정하면 None."""
    haystack = squash(label)
    if not haystack:
        return None
    best, best_score = None, 0
    for key, description in entries:
        # 쉼표로만 쪼갠다. 공백으로도 쪼개면 여러 낱말로 된 이름이 조각나서
        # 엉뚱한 필드에 걸린다. 실측: "이메일 주소"가 ["이메일", "주소"]로
        # 갈라져, 배송지 화면의 "주소" 칸이 email로 잡혔다.
        for word in (squash(part) for part in description.split(",")):
            if len(word) >= 2 and word in haystack and len(word) > best_score:
                best, best_score = key, len(word)
        if key in haystack and len(key) > best_score:
            best, best_score = key, len(key)
    return best


def input_targets(observation, wanted, entries):
    """(노드, 필드) 목록. 화면에 나온 순서 그대로.

    아이디 한 칸 + 비밀번호 한 칸이면 플래그로 가른다. 라벨은 앱마다 제각각이라
    믿을 게 못 된다 — 카카오톡은 아이디 칸을 "이메일 또는 전화번호"라고 부르는데,
    라벨로 맞추면 email(공통 정보)로 가지만 정작 필요한 건 그 앱의 username이다.

    칸 구성이 다르면(배송지 폼, 인증번호 화면) 라벨을 본다. 확실한 것만 고르고
    나머지는 건드리지 않는다. 모르는 칸을 채우느니 비워두는 편이 낫다.
    """
    editables = [node for node in observation.get("nodes", []) if node.get("editable")]
    passwords = [node for node in editables if node.get("password")]
    others = [node for node in editables if not node.get("password")]

    if "password" in wanted and len(passwords) == 1 and len(others) == 1:
        pairs = [(node, "password" if node.get("password") else "username")
                 for node in editables]
    else:
        pairs = []
        for node in editables:
            field = "password" if node.get("password") else field_for(label_of(node), entries)
            if field:
                pairs.append((node, field))
    return [(node, field) for node, field in pairs if field in wanted]


def submit_button(observation):
    """제출 버튼 노드. 확실하지 않으면 None.

    후보가 여럿이면 고르지 않는다. 로그인 화면에서 엉뚱한 버튼을 누르는 건
    되돌리기 어렵고(회원가입 흐름으로 빠질 수 있다), 사람이 한 번 누르는 비용보다
    크다. 실측: 카카오톡 로그인 화면에서 후보가 정확히 하나("로그인")였다.
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


def plan_fields(observation, values, field_hint):
    """채울 필드를 화면에 나온 순서대로. 노드 번호는 담지 않는다.

    노드 번호를 계획에 박아두면 안 된다. 번호는 스냅샷마다 새로 매겨지는데,
    한 칸을 채우면 화면이 바뀌면서(예: "입력한 내용 삭제" 버튼이 생긴다) 뒤 칸의
    번호가 밀린다. 실측: 아이디를 넣은 뒤 계획의 node_15가 비밀번호 칸이 아니게
    되어 "입력창이 아닌 노드입니다"로 세 번 연속 실패했다.

    그래서 계획은 "무엇을 채울지"만 정하고, "어느 칸인지"는 매 스텝 다시 찾는다.
    """
    entries = field_entries(field_hint)
    return [field for _, field in input_targets(observation, set(values), entries)]


def steps_now(observation, values, field_hint, order, want_submit, done, submitted):
    """지금 화면 기준으로 만든 단계 목록과, 그중 지금 할 단계.

    돌려주는 각 단계의 line은 모델이 그대로 답하면 되는 한 줄이다.
    """
    entries = field_entries(field_hint)
    found = {field: node for node, field in input_targets(observation, set(values), entries)}

    steps, current = [], None
    for field in order:
        node = found.get(field)
        line = f"type {node['id']} {values[field]}" if node else f"(화면에서 {field} 칸을 못 찾음)"
        step = {"action": "type", "field": field, "node_id": node["id"] if node else None,
                "line": line, "why": f"{field} — {label_of(node)[:20] if node else '못 찾음'}",
                "state": "done" if field in done else "todo"}
        steps.append(step)
        if current is None and step["state"] == "todo" and node:
            current = step

    if want_submit:
        button = submit_button(observation)
        step = {"action": "tap", "field": None,
                "node_id": button["id"] if button else None,
                "line": f"tap {button['id']}" if button else "(제출 버튼을 못 찾음)",
                "why": f"제출 — {label_of(button)[:20] if button else '못 찾음'}",
                "state": "done" if submitted else "todo"}
        steps.append(step)
        if current is None and not submitted and button:
            current = step

    steps.append({"action": "done", "field": None, "node_id": None,
                  "line": "done", "why": "마무리", "state": "todo"})
    if current is None:
        current = steps[-1]
    return steps, current
