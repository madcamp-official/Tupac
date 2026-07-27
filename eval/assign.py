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


# 브라우저로 볼 패키지 조각. 로그인을 웹으로 넘기는 앱이 많아 자주 마주친다.
BROWSER_MARKERS = ("chrome", "browser", "firefox", "sbrowser", "whale", "opera", "edge")


def is_browser_ui(node, observation):
    """브라우저가 그린 자기 UI(주소창·탭 버튼)인지. 웹 페이지 내용이 아니다.

    크롬 주소창은 editable이라 입력창 목록에 그대로 섞인다. 아이디를 먼저 받고
    다음 화면에서 비밀번호를 받는 로그인에서는 "비밀번호 아닌 입력창"이 주소창
    하나뿐이 되어, 아이디가 주소창에 입력된다.

    갈라내는 기준은 view_id다. 브라우저 자기 위젯은 "<패키지>:id/..."를 달고
    있고(com.android.chrome:id/url_bar), 웹 페이지 칸은 페이지가 정한
    id("otp-easy-login")거나 아예 없다.
    """
    package = observation.get("package_name") or ""
    if not any(marker in package.lower() for marker in BROWSER_MARKERS):
        return False
    return (node.get("view_id") or "").startswith(f"{package}:id/")


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
    editables = [node for node in observation.get("nodes", [])
                 if node.get("editable") and not is_browser_ui(node, observation)]
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
        if is_browser_ui(node, observation):
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
    """지금 화면 기준의 단계 목록, 지금 할 단계, 막힌 이유.

    기기 안 모델의 일은 "짚어준 한 줄을 실행하기" 하나로 한정한다. 그래서 짚어줄
    줄이 없으면 — 계획한 칸이 화면에 없거나 제출 버튼을 못 찾으면 — 모델에게
    묻지 않고 막힌 이유를 돌려준다. 부르는 쪽이 거기서 멈춘다.

    앞선 단계를 건너뛰고 뒤 단계를 짚지 않는다. 아이디를 못 넣었는데 비밀번호로
    넘어가거나, 값을 덜 채운 채 제출을 누르는 일이 생긴다.
    """
    entries = field_entries(field_hint)
    found = {field: node for node, field in input_targets(observation, set(values), entries)}

    steps, current, blocked = [], None, None
    for field in order:
        # 이미 넣은 칸은 화면에서 다시 찾지 않는다. 값을 넣으면 라벨이 값으로
        # 바뀌어 더는 그 필드로 안 잡히는데(빈 칸의 라벨은 hint에만 있다),
        # 그걸 "칸이 없음"으로 적으면 끝난 일을 못 한 일처럼 보여주게 된다.
        if field in done:
            steps.append({"action": "type", "field": field, "node_id": None,
                          "line": f"{field} 입력함", "why": "끝남", "state": "done"})
            continue
        node = found.get(field)
        # 답으로 베낄 줄에는 값 대신 필드 이름을 둔다. 값은 옆의 설명으로 함께
        # 건네되, 그걸 옮겨 적게 하지는 않는다. 실측: "홍길동"·"04524"는 그대로
        # 베꼈지만 공백이 든 주소에서는 값을 통째로 빠뜨리고 "type node_22"만 냈다.
        line = f"type {node['id']} {field}" if node else f"(화면에 {field} 칸이 없음)"
        step = {"action": "type", "field": field, "node_id": node["id"] if node else None,
                "line": line,
                "why": (f"{label_of(node)[:20]} 칸에 \"{values[field]}\" 를 넣습니다"
                        if node else f"{field} — 못 찾음"),
                "state": "todo"}
        steps.append(step)
        if current is None and blocked is None:
            if node:
                current = step
            else:
                blocked = f"{field}를 넣을 칸이 지금 화면에 없습니다"

    if want_submit:
        button = submit_button(observation)
        step = {"action": "tap", "field": None,
                "node_id": button["id"] if button else None,
                "line": f"tap {button['id']}" if button else "(제출 버튼을 못 찾음)",
                "why": f"제출 — {label_of(button)[:20] if button else '못 찾음'}",
                "state": "done" if submitted else "todo"}
        steps.append(step)
        if current is None and blocked is None and not submitted:
            if button:
                current = step
            else:
                blocked = "제출 버튼을 화면에서 찾지 못했습니다"

    steps.append({"action": "done", "field": None, "node_id": None,
                  "line": "done", "why": "마무리", "state": "todo"})
    if current is None and blocked is None:
        current = steps[-1]
    return steps, current, blocked


def action_for(step, values):
    """짚어준 단계를 그대로 실행할 행동.

    모델의 답에서 값을 다시 읽지 않는다. 모델은 "이 단계를 할 차례"라는 것만
    확인해주면 되고, 무엇을 넣을지는 code가 이미 안다. 값을 모델의 답을 거쳐
    가져오면 옮겨 적다 빠뜨린 만큼 그대로 화면에 들어간다.
    """
    if step["action"] == "type":
        return {"action": "type", "node_id": step["node_id"], "text": values[step["field"]]}
    return {"action": step["action"], "node_id": step["node_id"]}
