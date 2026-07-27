"""두뇌(모델) 어댑터 — 같은 화면을 모델 급에 맞는 방식으로 물어본다.

왜 나눠놓는가:
    1.2B 로컬 모델을 붙들고 만든 우회들(프롬프트를 짧게 자르기, 선택지를 화면에서
    뽑아 제시하기, 형식이 무너진 답을 관대하게 해석하기)은 큰 모델에게는 오히려
    해롭다. 규칙을 줄여놓으면 판단 근거가 사라지고, 자유 형식 파싱은 틀릴 여지만
    남긴다. 그래서 프롬프트와 파싱을 통째로 브레인 안에 가둬 서로 간섭하지 않게 한다.

    - LocalBrain  : llama-server(EXAONE 1.2B). 지금까지의 우회를 그대로 유지.
    - GeminiBrain : 클라우드 모델. 규칙 전문 + JSON 스키마 강제 + 전체 이력.

행동 어휘(tap/scroll/type/back/open/task/launch/done)는 둘이 똑같다. agent.py의
execute가 어느 브레인의 답이든 그대로 실행할 수 있어야 하기 때문이다. 다만
같은 어휘를 설명하는 방식은 다르다. Gemini에는 키마다 한글 설명이 붙은 목록을
주고, 1.2B에는 키만 준다(설명까지 주면 프롬프트가 화면 목록을 밀어낸다).

환경변수:
    LLM_URL         로컬 모델 서버 (기본 http://127.0.0.1:8080/v1/chat/completions)
    GEMINI_API_KEY  Gemini API 키 (--brain gemini 일 때 필수)
    GEMINI_MODEL    모델 이름 (기본 gemini-3.6-flash)
    GEMINI_URL      엔드포인트 직접 지정 (테스트용 스텁을 붙일 때)
    GEMINI_THINKING 사고 설정 JSON. 기본은 안 보냄(모델 기본값을 따름).
                    예: GEMINI_THINKING='{"thinkingLevel":"low"}'
    AGENT_VERBOSE   프롬프트와 모델 원문을 그대로 출력
"""
import json
import os
import re
import time
import urllib.error
import urllib.request

LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8080/v1/chat/completions")
# flash 계열을 쓴다. UI 에이전트는 스텝마다 모델을 부르므로 지연이 곧 체감 속도다.
# 쓸 수 있는 모델은 계정마다 다르다. 404가 나면 아래로 확인:
#   curl -s -H "x-goog-api-key: $GEMINI_API_KEY" \
#     https://generativelanguage.googleapis.com/v1beta/models
GEMINI_MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.6-flash")
GEMINI_URL = os.environ.get("GEMINI_URL") or (
    f"https://generativelanguage.googleapis.com/v1beta/models/{GEMINI_MODEL}:generateContent")

MAX_QUOTA_WAIT = 90          # 429 재시도에 쓸 누적 대기 상한(초)

ACTIONS = ("tap", "scroll", "type", "back", "open", "task", "launch", "system",
           "set", "list_apps", "fill", "need", "wait", "done")
DIRECTIONS = ("up", "down", "left", "right")


class BrainError(Exception):
    """모델 호출 실패. agent.py가 사람이 읽을 안내와 함께 종료한다."""

    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status         # HTTP 상태 코드 (429 재시도 판단에 쓴다)


def retry_delay(message, fallback):
    """429 응답이 알려주는 대기 시간을 그대로 쓴다.

    Gemini는 본문에 "Please retry in 13.79s"와 retryDelay 필드를 준다. 고정
    간격으로 기다리면 너무 짧아 또 맞거나(재시도 낭비) 너무 길어 시간을 버린다.
    """
    for pattern in (r"retry in ([0-9.]+)s", r'"retryDelay"\s*:\s*"([0-9.]+)s"'):
        found = re.search(pattern, message)
        if found:
            return min(float(found.group(1)) + 1, 65)   # 1초 여유, 상한 65초
    return fallback


def squash(text):
    """공백·대소문자를 지운다. "글자 크기"와 "글자크기"를 같게 보려는 것."""
    return re.sub(r"\s+", "", text.lower())


def overlap(text, haystack):
    """두 문자열이 연속으로 몇 글자나 겹치는지(3글자 미만은 0).

    두 글자 조각을 세는 방식은 우연한 겹침이 쌓여 엉뚱한 걸 고른다. 실측:
    "Play 스토어 열어줘"가 display(화면, 밝기, 글자 크기...)로 3점을 받았다.
    연속으로 겹쳐야 인정하면 "블루투스" 같은 진짜 일치만 남는다.
    """
    for size in range(len(text), 2, -1):
        for start in range(len(text) - size + 1):
            if text[start:start + size] in haystack:
                return size
    return 0


def shortcut_entries(shortcuts):
    """"open"/"task" + 키 + 설명 3튜플 목록으로 쪼갠다.

    설명 안에 괄호가 또 있어서(`dial(... (value=전화번호). 걸지는 않음)`) 괄호
    짝을 세는 방식은 못 쓴다. 대신 "줄 첫머리나 쉼표 뒤의 영문 키 + 여는 괄호"가
    나오는 위치를 찾아, 그 사이를 통째로 설명으로 본다.
    """
    entries = []
    for block in shortcuts.split("\n\n"):
        head, _, body = block.partition(":\n")
        kind = ("open" if "screen" in head else "task" if "task" in head
                else "system" if "key" in head else None)
        if not kind:
            continue
        starts = list(re.finditer(r"(?:^|,\s*)([a-z_][a-z0-9_]*)\(", body))
        for index, found in enumerate(starts):
            end = starts[index + 1].start() if index + 1 < len(starts) else len(body)
            description = body[found.end():end].rstrip().rstrip(",").rstrip(")")
            entries.append((kind, found.group(1), description))
    return entries


def obvious_screen(shortcuts, goal):
    """목표가 어느 설정 화면인지 확실할 때 그 화면을 돌려준다. 아니면 None.

    1.2B에게 20개 중 하나를 고르게 하면 못 고른다. 실측(같은 화면·같은 목표로
    프롬프트를 네 가지로 바꿔가며 5문제):

        바로가기 목록만 줌            0/5
        바로가기에 예시를 붙임         2/5
        목표와 관련된 것만 3개로 추림   1/5

    게다가 실행마다 답이 달라졌다. 프롬프트를 더 만져서 될 문제가 아니라고 봤다.
    실패 양상도 뚜렷하다. "블루투스 설정 열어줘"에 open settings를 골랐다 —
    목표에 든 "설정"이라는 낱말에 끌린 것이다.

    반면 이 판단은 규칙으로 충분하다. "블루투스"라는 낱말이 bluetooth 설명에만
    있으면 그게 답이다. 확실할 때만(1등이 2등보다 뚜렷하게 높을 때) 규칙이 정하고,
    애매하면 모델에게 넘긴다.
    """
    text = squash(goal)
    scored = []
    for kind, key, description in shortcut_entries(shortcuts):
        # settings는 "~ 설정 열어줘"라는 흔한 말투 때문에 어떤 목표에도 걸린다.
        # 규칙 후보에서 아예 뺀다. 정말 설정 첫 화면을 원하면 모델이 고르면 된다.
        if kind != "open" or key == "settings":
            continue
        # 설명은 부분 일치를 보고, 영문 키는 통째로 들어맞을 때만 인정한다.
        # 키를 부분 일치로 보면 엉뚱한 게 걸린다. 실측: "Play 스토어 열어줘"가
        # display에 잡혔다 — "display" 안에 "play"가 들어있기 때문이다.
        by_description = overlap(text, squash(description))
        by_key = len(key) if key in text else 0
        scored.append((max(by_description, by_key), key))

    scored.sort(reverse=True)
    if not scored:
        return None
    best_score, best_key = scored[0]
    runner_up = scored[1][0] if len(scored) > 1 else 0
    # 1등이 충분히 겹치고 2등보다 뚜렷하게 높을 때만. 애매하면 모델에게 넘긴다.
    return best_key if best_score >= 3 and best_score > runner_up else None


# 값 없이도 되는 작업만 규칙으로 고른다. 값이 필요한 작업(alarm은 시각, map은
# 장소)을 규칙이 고르면 값을 못 채워 그대로 실패한다. torch는 예외로 둔다 —
# 필요한 값이 켜기/끄기 둘뿐이고, 그건 목표 문장에 반드시 적혀 있다.
VALUE_FREE_TASKS = ("show_alarms", "camera", "gallery", "contacts")
TORCH_OFF_WORDS = ("꺼", "끄", "off")
TORCH_ON_WORDS = ("켜", "on")


def obvious_task(shortcuts, goal):
    """목표가 어느 기본 작업인지 확실할 때 그 작업을 돌려준다. 아니면 None.

    obvious_screen과 같은 이유로 있다. 다만 설정 화면과 달리 기본 작업 중에는
    화면으로 갈 길 자체가 없는 것이 있다. 손전등이 그렇다 — 안드로이드에 손전등을
    켜는 화면이 없어서, 규칙이 못 잡으면 모델이 홈 화면을 헤매다 끝난다(실측:
    "후레쉬 꺼줘"에 같은 아이콘만 세 번 눌렀다).
    """
    text = squash(goal)
    scored = []
    for kind, key, description in shortcut_entries(shortcuts):
        if kind != "task" or (key != "torch" and key not in VALUE_FREE_TASKS):
            continue
        by_description = overlap(text, squash(description))
        by_key = len(key) if key in text else 0
        scored.append((max(by_description, by_key), key))

    scored.sort(reverse=True)
    if not scored:
        return None
    best_score, best_key = scored[0]
    runner_up = scored[1][0] if len(scored) > 1 else 0
    if best_score < 3 or best_score <= runner_up:
        return None

    if best_key == "torch":
        # 켜라는 건지 끄라는 건지는 목표 문장에만 있다. 안 적혀 있으면 규칙으로
        # 정하지 않는다 — 짐작으로 켜면 사람이 원한 것과 반대일 수 있다.
        # 끄기를 먼저 본다("꺼줘"에는 "켜"가 없지만 순서를 명시해둔다).
        if any(word in text for word in TORCH_OFF_WORDS):
            return {"action": "task", "task": "torch", "value": "off"}
        if any(word in text for word in TORCH_ON_WORDS):
            return {"action": "task", "task": "torch", "value": "on"}
        return None
    return {"action": "task", "task": best_key}


def compact_shortcuts(shortcuts):
    """바로가기 목록에서 설명을 걷어내고 키만 남긴다.

    Gemini에게 주는 원본은 키마다 한글 설명이 붙어 1000자가 넘는다. 1.2B에
    그대로 주면 프롬프트가 화면 목록을 밀어내 정작 무엇을 누를지 못 고른다.
    작은 모델에는 어휘만 주고, 무엇을 고를지는 목표 문장과 대조해 판단하게 한다.
    """
    lines = []
    for block in shortcuts.split("\n\n"):
        head, _, body = block.partition(":\n")
        # 설명을 지우는 방식(괄호 안 삭제)은 못 쓴다. 설명 안에 또 괄호가 있어서
        # ("dial(전화 앱에 번호 입력 (value=전화번호). 걸지는 않음)") 안쪽만 지워지고
        # 바깥 껍데기가 남는다. 키를 직접 뽑는 게 안전하다: 줄 첫머리나 쉼표 뒤에
        # 오는 영문 소문자 낱말 + 여는 괄호.
        keys = ", ".join(re.findall(r"(?:^|,\s*)([a-z_][a-z0-9_]*)\(", body))
        if not keys:
            continue
        if "screen" in head:
            lines.append(f"open 화면 — 쓸 수 있는 화면: {keys}")
        elif "task" in head:
            lines.append(f"task 작업 값 — 쓸 수 있는 작업: {keys}")
        elif "key" in head:
            lines.append(f"system 동작 — 쓸 수 있는 동작: {keys}")
    if lines:
        lines.append("launch 앱이름")
    return "\n".join(lines)


def _verbose(title, body):
    if os.environ.get("AGENT_VERBOSE"):
        print(f"----- {title} -----\n{body}\n{'-' * 30}")


def _post_json(url, payload, headers, timeout):
    """POST 후 JSON 응답. 실패 시 서버가 준 본문까지 붙여 올린다.

    HTTPError는 URLError의 하위 클래스라 그냥 잡으면 응답 본문이 사라진다.
    API 키 오류·스키마 거절 같은 건 그 본문에만 이유가 적혀 있어 따로 읽는다.
    """
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace").strip()
        raise BrainError(f"HTTP {error.code} — {detail[:600]}", status=error.code) from error
    except urllib.error.URLError as error:
        raise BrainError(f"연결 실패({url}): {error.reason}") from error


def strip_thinking(raw):
    """<think>...</think> 블록을 걷어낸다.

    Qwen 계열은 기본이 사고 모드라 답 앞에 사고 과정을 먼저 쓴다. 그대로 두면
    파서가 첫 줄(사고의 시작)만 보고 실패해서, 판단이 틀린 것과 형식이 다른 것을
    구분할 수 없다. 모델을 바꿔가며 비교하려면 이걸 먼저 걷어내야 한다.
    """
    if "</think>" in raw:
        raw = raw.split("</think>", 1)[1]
    return raw.strip()


def parse_action(raw, need_text=True):
    """모델 응답에서 행동을 뽑아낸다 (주로 로컬 모델용).

    작은 모델에 JSON 형식을 문법으로 강제하면 생각하기 전에 action부터 확정해
    판단 품질이 무너진다(실측). 그래서 형식은 프롬프트로만 유도하고, 자연어로
    답하더라도 여기서 관대하게 해석한다.

    need_text=False 는 값 입력 구간용이다. 그때는 넣을 값을 code가 들고 있어서
    모델이 값까지 옮겨 적을 필요가 없다.
    """
    # 1순위: "tap node_41" 같은 한 줄 형식. 작은 모델은 JSON 문법(따옴표·중괄호·
    # 쉼표)을 못 지켜 구조가 무너지는 일이 잦아, 가장 쓰기 쉬운 형식을 먼저 본다.
    raw = strip_thinking(raw)
    first_line = raw.strip().splitlines()[0].strip() if raw.strip() else ""
    match = re.match(
        r"^[\s\-*`]*(tap|scroll|type|back|open|task|launch|system|set|fill|need|wait|done)"
        r"\b[:\s]*(.*)$",
        first_line, re.IGNORECASE)
    if match:
        verb, arg = match.group(1).lower(), match.group(2).strip().strip('"\'`')
        if verb == "tap":
            node = re.search(r"node_\d+", arg) or re.search(r"node_\d+", raw)
            if node:
                return {"action": "tap", "node_id": node.group()}
        elif verb == "scroll":
            direction = next((d for d in DIRECTIONS if d in arg.lower()), "down")
            return {"action": "scroll", "direction": direction}
        elif verb == "type":
            # "type node_12 홍길동" — 칸을 지목하면 포커스에 기대지 않는다.
            # "type 홍길동" — 예전 형식도 받는다(포커스된 칸에 들어간다).
            head, _, rest = arg.partition(" ")
            if head.startswith("node_"):
                # "type node_18"처럼 넣을 글자가 없으면 길찾기 중에는 형식 오류로
                # 본다. 예전에는 예전 형식("type 값")으로 넘어가 "node_18"이라는
                # 글자를 그대로 칸에 집어넣었다(실측: 아이디 칸에 node_18이 입력됐다).
                #
                # 값 입력 구간(need_text=False)은 다르다. 넣을 값은 code가 들고
                # 있고 모델은 어느 단계인지만 확인해주면 된다. 거기서 글자가 없다고
                # 버리면 진행이 막힌다(실측: 모델이 "type node_25"까지만 냈다).
                if rest.strip():
                    return {"action": "type", "node_id": head, "text": rest.strip()}
                return None if need_text else {"action": "type", "node_id": head, "text": ""}
            if arg:
                return {"action": "type", "text": arg}
        elif verb == "open":
            if arg:
                return {"action": "open", "screen": arg.split()[0]}
        elif verb == "task":
            # "task alarm 07:30" — 첫 토큰이 작업 이름, 나머지가 값.
            head, _, rest = arg.partition(" ")
            if head:
                found = {"action": "task", "task": head}
                if rest.strip():
                    found["value"] = rest.strip()
                return found
        elif verb == "launch":
            if arg:
                return {"action": "launch", "app": arg}
        elif verb == "system":
            # "system quick_settings" — 시스템 버튼 하나를 누른다.
            if arg:
                return {"action": "system", "key": arg.split()[0]}
        elif verb == "set":
            # "set node_12 60" — 슬라이더를 60%로.
            #
            # 숫자가 없으면 형식 오류로 확정하고 None을 돌려준다. 아래의 관대한
            # 해석으로 흘려보내면 "set node_12"가 tap node_12가 되어버린다 —
            # 슬라이더를 누르면 누른 좌표의 값으로 튀므로, 의도와 무관한 값이
            # 들어간다. "type node_18"을 거절하는 것과 같은 이유다.
            parts = arg.split()
            if len(parts) >= 2 and parts[0].startswith("node_"):
                found = re.search(r"-?\d+(?:\.\d+)?", parts[1])
                if found:
                    return {"action": "set", "node_id": parts[0], "value": found.group()}
            return None
        elif verb == "need":
            # "need username password" — 값이 필요하다는 신호. 값은 code가 꺼낸다.
            wanted = [word for word in arg.replace(",", " ").split() if word]
            if wanted:
                return {"action": "need", "fields": wanted}
        elif verb == "fill":
            # "fill node_16 username" — 칸과 금고 필드를 함께 지목한다.
            parts = arg.split()
            if len(parts) >= 2 and parts[0].startswith("node_"):
                return {"action": "fill", "node_id": parts[0], "field": parts[1]}
        else:
            return {"action": verb}

    for candidate in (raw, raw[raw.find("{"):raw.rfind("}") + 1] if "{" in raw else ""):
        if not candidate:
            continue
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict) and "action" in parsed:
                return parsed
        except json.JSONDecodeError:
            pass

    # 자연어 응답 해석: 언급된 node_id + 행동 키워드.
    # done은 여기서 추론하지 않는다. "이미 ~했지만" 같은 흔한 표현을 목표 달성으로
    # 오인해 루프가 조기 종료된 사례가 있었다. done은 명시적 JSON일 때만 인정한다.
    text = raw.lower()
    node_ids = re.findall(r"node_\d+", raw)
    if any(k in text for k in ("탭", "클릭", "누르", "선택", "tap")) and node_ids:
        return {"action": "tap", "node_id": node_ids[0], "reason": raw[:120]}
    if any(k in text for k in ("스크롤", "scroll", "스와이프", "내려", "올려")):
        direction = "down"
        for word, value in (("위", "up"), ("아래", "down"), ("오른", "right"), ("왼", "left")):
            if word in raw:
                direction = value
                break
        return {"action": "scroll", "direction": direction, "reason": raw[:120]}
    if node_ids:                      # 행동 표현이 없어도 노드를 지목했으면 tap으로 본다
        return {"action": "tap", "node_id": node_ids[0], "reason": raw[:120]}
    return None


# ─────────────────────────────── 로컬 (1.2B) ───────────────────────────────

# 작은 모델(1.2B)은 system 역할을 약하게 취급해 긴 지시를 무시한다(실측: 화면과
# 무관한 조언을 늘어놓음). 지시는 짧게 줄여 user 메시지 안에 넣는다.
LOCAL_SYSTEM_PROMPT = "휴대폰 화면을 조작하는 도우미. 한 줄로만 답한다."

LOCAL_RULES = """휴대폰 화면을 보고, 목표에 가까워지는 다음 행동 하나만 고르세요.

규칙:
- 바로가기(open/task/launch)로 한 번에 갈 수 있으면 그것부터 쓰세요.
- 홈 화면이라면 scroll down을 통해 검색을 tap 하세요.
- 목표와 관련된 항목이 화면에 있으면 그것을 tap 하세요.
- 라벨이 목표와 똑같지 않아도 목표로 가는 길목이면 고르세요.
- 목록이 화면에 다 안 보일 수 있습니다. 찾는 항목이 없으면 scroll down 하세요.
- 화면 맨 위의 제목은 누르지 마세요. 목록 항목을 고르세요.
- 최근 행동에서 이미 누른 node를 다시 누르지 마세요.
- 목표한 화면에 도착했으면 더 누르지 말고 done을 쓰세요.
  (예: 목표가 "글자 크게"인데 화면에 글자 크기 조절이 보이면 done)"""


class LocalBrain:
    """llama-server에 붙는 소형 모델. 프롬프트는 짧게, 파싱은 관대하게."""

    name = "local"
    online = False                # 화면 텍스트가 기기 밖으로 나가지 않는다
    max_history = 3               # 1.2B는 긴 이력을 감당 못 하므로 최근 것만

    def __init__(self, url=LLM_URL):
        self.url = url

    def _instructions(self, observation, shortcuts=""):
        """현재 화면에서 실제로 가능한 선택지만 제시한다.

        고정된 예시 목록을 주면 작은 모델이 예시를 그대로 베낀다(실측: 입력창이
        없는 화면에서 예시의 "type 와이파이"를 여섯 스텝 내내 반복). 화면에
        입력창이 있을 때만 type을 노출하고, 예시 node 번호도 실제 화면 것을 쓴다.
        """
        example = next(
            (n["id"] for n in observation["nodes"]
             if (n.get("text") or n.get("content_description"))),
            "node_1",
        )
        options = [f"tap {example}", "scroll down", "scroll up", "back", "done"]
        if any(n["editable"] for n in observation["nodes"]):
            options.insert(1, "type 넣을글자")
            options.insert(2, "fill node_번호 필드이름")
        # 슬라이더가 있을 때만 노출한다. 없는 화면에 예시로 두면 작은 모델이
        # 그대로 베낀다(고정 예시를 베끼는 문제는 이 함수 주석 참고).
        slider = next((n["id"] for n in observation["nodes"] if n.get("range")), None)
        if slider:
            options.insert(1, f"set {slider} 값")
        menu = compact_shortcuts(shortcuts)
        if menu:
            options = menu.splitlines() + options
        return (f"{LOCAL_RULES}\n\n답은 아래 형태 중 하나로 한 줄만 쓰세요. 설명하지 마세요.\n"
                f"tap 뒤에는 화면에 있는 node 번호를 쓰세요.\n\n" + "\n".join(options))

    def _ask(self, messages):
        payload = {
            "messages": messages,
            "temperature": 0.1,      # EXAONE 카드 권장: 한국어는 낮은 온도
            "max_tokens": int(os.environ.get("LOCAL_MAX_TOKENS", "60")),
            # json_schema로 문법을 강제하면 모델이 생각하기 전에 action부터 확정하게 되어
            # (실측) 계속 scroll만 고르는 문제가 있었다. 형식은 프롬프트로 유도하고
            # 파싱은 parse_action에서 관대하게 처리한다.
        }
        data = _post_json(self.url, payload, {"Content-Type": "application/json"}, timeout=180)
        return data["choices"][0]["message"]["content"]

    def decide(self, goal, screen, observation, history, shortcuts="", extra="",
               focused=False):
        # 값 입력 구간에서는 길찾기용 규칙과 바로가기 목록을 통째로 뺀다.
        # 실측: 1.2B에게 필요한 건 절차 네 줄인데 그 앞에 무관한 마흔 줄(바로가기
        # 20개, "홈 화면이라면 scroll down")이 깔려 있었고, 모델은 비밀번호만
        # 세 번 반복해 넣고 아이디 칸을 건드리지 않았다. 길찾기는 이미 끝났고
        # 여기서 할 일은 받은 값을 칸에 넣는 것뿐이다.
        if focused:
            # 이력도 뺀다. 진행 상황은 code가 세고 있고, 이 구간에서 모델이 할 일은
            # 짚어준 한 줄뿐이다. 이력을 보여주면 그걸 베낀다 — 실측: 배송지 폼
            # 네 번째 칸 차례에 "step2: type node_15 name → 성공"을 그대로
            # 되풀이했다. 베낄 것이 정답 하나뿐이어야 정답을 베낀다.
            user = (f"{extra}\n\n{screen}\n\n"
                    "한 줄로만 답하세요. 형태: type node_번호 값 / tap node_번호 "
                    "/ wait / done\n답:")
            _verbose("프롬프트(local, 값 입력)", user)
            raw = self._ask([{"role": "system", "content": LOCAL_SYSTEM_PROMPT},
                             {"role": "user", "content": user}])
            _verbose("모델 원문(local)", raw)
            return parse_action(raw, need_text=False), raw

        # 확실한 설정 화면은 모델에게 묻지 않는다. 물어봤자 못 고른다(obvious_screen
        # 주석의 실측 참고). 첫 스텝에만 적용한다 — 이미 뭔가 하던 중이라면 목표의
        # 낱말만 보고 엉뚱한 화면으로 튀어버릴 수 있다.
        if not history:
            key = obvious_screen(shortcuts, goal)
            if key:
                return ({"action": "open", "screen": key,
                         "reason": "목표에 이 화면 이름이 있어 규칙으로 골랐습니다"},
                        f"(규칙) open {key}")
            found = obvious_task(shortcuts, goal)
            if found:
                found["reason"] = "목표에 이 작업 이름이 있어 규칙으로 골랐습니다"
                # 이 한 번으로 목표가 끝난다. 규칙은 목표가 그 작업 하나로 딱
                # 떨어질 때만 고르고, 작업은 화면을 여는 게 아니라 그 자체가
                # 결과다. 안 알려주면 루프가 계속 돈다 — 손전등은 켜도 화면이
                # 그대로라, 모델이 홈 화면 아이콘을 눌러대다 스텝을 다 썼다.
                found["final"] = True
                value = f" {found['value']}" if found.get("value") else ""  
                return found, f"(규칙) task {found['task']}{value}"

        recent = "\n".join(history[-self.max_history:]) or "(아직 없음)"
        block = f"\n\n{extra}" if extra else ""
        # 마지막 줄을 "답:"으로 끝내면 모델이 곧바로 행동부터 쓰기 시작한다.
        user = (f"{self._instructions(observation, shortcuts)}{block}\n\n목표: {goal}\n\n"
                f"{screen}\n\n최근 행동:\n{recent}\n\n답:")
        _verbose("프롬프트(local)", user)

        messages = [{"role": "system", "content": LOCAL_SYSTEM_PROMPT},
                    {"role": "user", "content": user}]
        raw = self._ask(messages)
        _verbose("모델 원문(local)", raw)
        action = parse_action(raw)
        if action is not None:
            return action, raw

        # 형식이 무너진 응답 하나로 루프 전체를 끝내지 않는다. 형식만 다시
        # 일러주고 한 번 더 물어본다(예: 인자 없는 "type"만 답한 경우).
        retry = self._ask(messages + [
            {"role": "assistant", "content": raw},
            {"role": "user", "content":
             "형식이 틀렸습니다. 아래 중 하나를 그대로 한 줄만 쓰세요.\n"
             "tap node_번호 / scroll down / scroll up / back / done"},
        ])
        _verbose("재시도 원문(local)", retry)
        return parse_action(retry), retry

    def hint(self, status=None):
        return ("llama-server -m eval/models/EXAONE-4.0-1.2B-Q4_K_M.gguf "
                "-c 4096 --port 8080 을 먼저 실행하세요.")


# ─────────────────────────────── Gemini (클라우드) ───────────────────────────────

CLOUD_SYSTEM_PROMPT = """당신은 안드로이드 휴대폰을 대신 조작하는 에이전트입니다.
접근성 트리로 읽은 현재 화면을 받고, 목표에 한 걸음 다가가는 행동 하나를 고릅니다.
화면에 보이는 것만 근거로 삼고, 보이지 않는 것을 추측해 지어내지 마세요."""

CLOUD_RULES = """행동은 다음뿐입니다. 위쪽 네 개를 먼저 고려하세요.
- open      : screen 필수. 안드로이드 설정 화면으로 한 번에 점프합니다.
- task      : task 필수. 전화·문자·검색·지도·알람 같은 기본 기능을 바로 실행합니다.
              값이 필요한 작업은 value에, 문자 내용이나 알람 이름은 text에 씁니다.
- launch    : app 필수. 설치된 앱을 이름으로 실행합니다(예: app="카카오톡").
- system    : key 필수. 시스템 버튼을 누릅니다(빠른 설정, 홈, 알림창, 최근 앱...).
- list_apps : 어떤 앱이 깔려 있는지 모를 때. app에 검색어를 넣으면 걸러 봅니다.
- need   : fields 필수. 개인정보를 입력해야 하는 화면에 도착했을 때, 직접 채우지
             말고 필요한 값의 이름만 넘깁니다(예: fields=["username","password"]).
             값은 당신에게 오지 않습니다. 기기 안 모델이 이어받아 입력합니다.
- fill   : node_id와 field 필수. 폰에 저장된 개인정보를 그 입력창에 넣습니다.
             값은 폰 안에서 처리되며 당신은 값을 보지 못합니다.
- wait   : 화면 전환이나 처리 결과를 기다립니다.
- tap    : node_id 필수. 화면에 실제로 있는 번호만 씁니다.
- scroll : direction 필수(up/down/left/right).
- set    : node_id와 value 필수. 슬라이더를 그 퍼센트로 옮깁니다(0~100).
- type   : text 필수. node_id를 함께 주면 그 칸에 넣습니다(권장).
- back   : 잘못 들어왔거나 막다른 화면일 때 되돌아갑니다.
- done   : 목표 화면에 도착했을 때. 마지막 한 번만.

판단 지침:
- 화면을 눌러 찾아가기 전에 open/task/launch로 한 번에 갈 수 있는지 먼저 보세요.
  한 번의 점프가 tap과 scroll로 예닐곱 번 더듬는 것보다 빠르고 정확합니다.
- open과 대부분의 task는 화면을 열어줄 뿐입니다. 설정을 바꾸는 마지막 동작은
  도착한 화면에서 tap으로 하세요.
- 약관 동의, 권한 허용, 계정 생성, 결제는 사람이 합니다. "동의 및 계속",
  "허용", "확인하고 결제" 같은 버튼은 누르지 말고, 그 화면을 열어둔 채
  done으로 마치면서 무엇을 물어보고 있는지 이유에 적으세요.
  (실측: 지도를 처음 열면 "서비스 약관에 동의하는 것으로 간주합니다" 화면이 뜬다)
- 다만 전화 발신과 문자·메일 전송은 마지막 한 번을 사람이 누릅니다.
  task=dial은 번호만 채워주고, task=sms·email은 작성 화면까지만 엽니다.
  거기서 통화·전송 버튼을 누르지 말고 done으로 마치세요. 목표가 "전화 걸어줘"여도
  번호가 채워진 화면까지가 당신의 일입니다. 상대방에게 남는 행동은 되돌릴 수 없습니다.
- 예외로 task=alarm과 task=timer는 실행하는 순간 실제로 등록·시작됩니다.
  목표가 "알람 맞춰줘"라면 이것만으로 끝나며, 추가로 tap 할 필요가 없습니다.
- 앱 이름이 확실하지 않으면 launch로 찍지 말고 list_apps로 먼저 확인하세요.
- 여러 앱이 처리할 수 있는 작업(검색, 지도, 링크 열기)은 "어느 앱으로 열까요"
  대화상자가 먼저 뜹니다. 화면에 앱 이름들과 "한 번만"/"항상"이 같이 보이면
  그 화면입니다. 앱 이름을 tap 하고 이어서 "한 번만"을 tap 하세요.
  같은 task를 다시 실행해도 이 대화상자가 또 뜰 뿐입니다.
- 와이파이·블루투스·손전등·화면 회전·모바일 데이터처럼 켜고 끄는 토글은
  system=quick_settings로 패널을 내리는 것이 가장 짧습니다. 설정 앱을 열어
  찾아가도 되지만 스텝이 더 듭니다. 패널이 열린 뒤 해당 토글을 tap 하세요.
- 화면 어디에도 길이 안 보이면 system=home으로 홈에서 다시 시작하세요.
  back을 반복하는 것보다 확실합니다.
- 각 줄의 [tap]/[type]/[scroll]은 그 노드에 할 수 있는 행동입니다.
- [set 0~100, 지금 값]으로 표시된 줄은 슬라이더입니다(밝기, 음량).
  tap으로는 값을 고를 수 없으니 set에 node_id와 value를 주세요. value는 언제나
  0~100 퍼센트입니다 — "절반으로"는 50, "최대로"는 100입니다.
- 라벨이 목표와 글자 그대로 같지 않아도, 목표로 가는 길목이면 고르세요.
  (예: Wi-Fi는 "연결"이나 "네트워크" 안에, 글자 크기는 "디스플레이" 안에 있습니다)
- 목록이 화면에 다 안 보일 수 있습니다. 찾는 항목이 없으면 scroll down 하세요.
- 화면 맨 위의 제목/헤더는 대개 누를 대상이 아닙니다. 목록 항목을 고르세요.
- 지금까지 한 행동에는 각각 (화면 바뀜) 또는 (화면 그대로)가 붙어 있습니다.
  (화면 그대로)는 그 행동이 아무 효과가 없었다는 뜻입니다. 같은 행동을 다시
  하지 말고 다른 항목을 고르거나 반대 방향으로 scroll 하거나 back 하세요.
  같은 방향 scroll이 연속 두 번 (화면 그대로)면 그 방향은 끝에 닿은 것입니다.
- 목표한 화면에 이미 도착했다면 더 누르지 말고 done을 쓰세요.

reason에는 "지금 화면이 무엇이고 왜 이 행동인지"를 한 문장으로 먼저 적으세요."""

# reason을 먼저 쓰게 하는 게 핵심이다. 로컬에서 JSON 스키마를 강제했을 때
# 판단이 무너졌던 건(agent.py 주석 참고) 모델이 생각하기 전에 action부터
# 확정했기 때문인데, Gemini는 propertyOrdering으로 생성 순서를 지정할 수 있어
# 형식을 강제하면서도 "먼저 근거, 그다음 행동" 순서를 지킬 수 있다.
CLOUD_SCHEMA = {
    "type": "OBJECT",
    "properties": {
        "reason": {"type": "STRING"},
        "action": {"type": "STRING", "enum": list(ACTIONS)},
        "node_id": {"type": "STRING"},
        "direction": {"type": "STRING", "enum": list(DIRECTIONS)},
        "text": {"type": "STRING"},
        "screen": {"type": "STRING"},
        "task": {"type": "STRING"},
        "value": {"type": "STRING"},
        "app": {"type": "STRING"},
        "key": {"type": "STRING"},
        "field": {"type": "STRING"},
        "fields": {"type": "ARRAY", "items": {"type": "STRING"}},
    },
    "required": ["reason", "action"],
    "propertyOrdering": ["reason", "action", "node_id", "direction", "text", "screen",
                         "task", "value", "app", "key", "field", "fields"],
}


class GeminiBrain:
    """Gemini API. 규칙 전문 + JSON 스키마 강제 + 전체 이력.

    주의: 이 브레인은 화면 텍스트를 외부로 보낸다. 개인정보가 있는 화면에서는
    호출되면 안 된다(2단계에서 라우터와 마스킹을 붙일 자리다). 스크린샷을
    보내지 않고 접근성 텍스트만 쓰는 것도 그래서다 — 픽셀은 가릴 수가 없다.
    """

    name = "gemini"
    online = True
    max_history = 20              # 큰 모델은 이력이 길수록 같은 실수를 덜 반복한다

    def __init__(self, url=GEMINI_URL, api_key=None, model=GEMINI_MODEL):
        self.url = url
        self.model = model
        self.api_key = api_key if api_key is not None else os.environ.get("GEMINI_API_KEY", "")
        # 사고 예산은 기본으로 건드리지 않는다. 모델 세대마다 형식이 다르고
        # (gemini-3.6-flash는 2.5식 thinkingBudget:0을 400으로 거절), 기본값이
        # 그 모델에 맞게 이미 잡혀 있다. 굳이 조절하고 싶을 때만 환경변수로.
        #   GEMINI_THINKING='{"thinkingLevel":"low"}'
        self._thinking_ok = "GEMINI_THINKING" in os.environ

    def _payload(self, user, with_thinking):
        config = {
            "temperature": 0,
            # 3세대는 사고 토큰도 출력 한도에 포함된다. 짧게 잡으면 답을 쓰기도 전에
            # MAX_TOKENS로 잘려 빈 응답이 온다. 스키마 덕에 실제 출력은 짧으니 넉넉히.
            "maxOutputTokens": 2048,
            "responseMimeType": "application/json",
            "responseSchema": CLOUD_SCHEMA,
        }
        if with_thinking:
            config["thinkingConfig"] = json.loads(os.environ["GEMINI_THINKING"])
        return {
            "systemInstruction": {"parts": [{"text": CLOUD_SYSTEM_PROMPT}]},
            "contents": [{"role": "user", "parts": [{"text": user}]}],
            "generationConfig": config,
        }

    def _ask(self, user):
        headers = {"Content-Type": "application/json", "x-goog-api-key": self.api_key}
        attempts, waited = 5, 0.0
        for attempt in range(attempts):
            try:
                return self._extract(
                    _post_json(self.url, self._payload(user, self._thinking_ok), headers, timeout=60))
            except BrainError as error:
                # 무료 등급은 분당 요청 수 제한이 있다. 에이전트는 스텝마다 부르니
                # 쉽게 걸리는데, 잠깐 기다리면 풀리므로 루프를 죽이지 않는다.
                if error.status == 429 and attempt < attempts - 1:
                    wait = retry_delay(str(error), 20 * (attempt + 1))
                    # 분당 한도면 한 번 기다리면 풀린다. 여러 번 기다려도 계속
                    # 429라면 일당 한도라 아무리 기다려도 안 풀리므로, 몇 분씩
                    # 멈춰 있지 말고 사람에게 상황을 알린다.
                    if waited + wait > MAX_QUOTA_WAIT:
                        raise BrainError(
                            f"{error} (누적 {waited:.0f}초 대기했지만 계속 초과 — "
                            f"분당이 아니라 일당 한도일 수 있습니다)", status=429)
                    print(f"  (할당량 초과 — {wait:.0f}초 기다렸다 재시도합니다)")
                    time.sleep(wait)
                    waited += wait
                    continue
                # thinkingConfig 형식은 모델 세대마다 다르다(2.5의 thinkingBudget을
                # 3세대는 거부). 400 본문에 필드명이 안 나오므로, 이걸 보냈다가
                # 400을 받으면 일단 빼고 다시 시도한다.
                if error.status == 400 and self._thinking_ok:
                    print("  (참고: 이 모델이 GEMINI_THINKING 설정을 거부해 빼고 재시도합니다)")
                    self._thinking_ok = False
                    continue
                raise
        raise BrainError("재시도했으나 계속 실패했습니다.")

    @staticmethod
    def _extract(data):
        candidates = data.get("candidates") or []
        if not candidates:
            # 안전 필터에 걸리면 candidates 자체가 비어 온다. 이유를 그대로 보여준다.
            raise BrainError(f"응답에 candidates가 없습니다: {json.dumps(data)[:400]}")
        parts = candidates[0].get("content", {}).get("parts") or []
        text = "".join(part.get("text", "") for part in parts)
        if not text.strip():
            raise BrainError(
                f"빈 응답 (finishReason={candidates[0].get('finishReason')}). "
                f"maxOutputTokens나 GEMINI_THINKING 설정을 확인하세요.")
        return text

    def decide(self, goal, screen, observation, history, shortcuts="", extra="",
               focused=False):
        recent = "\n".join(history[-self.max_history:]) or "(아직 없음)"
        # 바로가기 목록은 폰이 tools/list로 알려준 것을 그대로 싣는다. 여기에
        # 하드코딩하면 앱에 화면을 추가했을 때 프롬프트가 따라가지 못한다.
        menu = f"\n\n{shortcuts}" if shortcuts else ""
        block = f"\n\n{extra}" if extra else ""
        user = (f"{CLOUD_RULES}{menu}{block}\n\n목표: {goal}\n\n{screen}\n\n"
                f"지금까지 한 행동:\n{recent}")
        _verbose("프롬프트(gemini)", user)
        raw = self._ask(user)
        _verbose("모델 원문(gemini)", raw)
        # 스키마로 형식이 보장되지만, 파싱 실패 시 관대한 해석으로 한 번 더 건진다.
        try:
            action = json.loads(raw)
            if not isinstance(action, dict) or "action" not in action:
                action = None
        except json.JSONDecodeError:
            action = None
        return (action if action is not None else parse_action(raw)), raw

    def hint(self, status=None):
        if status == 429:
            return ("무료 등급 요청 한도를 넘었습니다. 잠시 뒤 다시 돌리거나, "
                    "--steps를 줄이거나, GEMINI_MODEL로 한도가 다른 모델을 쓰세요.")
        if status == 404:
            return (f"모델 {self.model}을(를) 쓸 수 없습니다. GEMINI_MODEL로 바꾸세요. "
                    "목록: curl -s -H \"x-goog-api-key: $GEMINI_API_KEY\" "
                    "https://generativelanguage.googleapis.com/v1beta/models")
        return (f"GEMINI_API_KEY를 확인하세요(https://aistudio.google.com/apikey). "
                f"현재 모델: {self.model}")


SMOKE_SCREEN = """SCREEN (app: com.android.settings)
node_1 [tap] 설정
node_2 [tap] 연결
node_3 [tap] 디스플레이
node_4 [tap] 배터리"""


def smoke_test(name):
    """폰 없이 모델 연결만 확인한다. 가짜 화면 하나를 주고 답을 받아본다.

    실제 API가 스키마(대문자 타입, propertyOrdering, thinkingConfig)를 받아주는지는
    여기서만 확인할 수 있다. 기대 답: tap node_2.
    """
    brain = make_brain(name)
    action, raw = brain.decide("와이파이 켜줘", SMOKE_SCREEN,
                               {"nodes": [{"id": f"node_{i}", "text": "x", "editable": False}
                                          for i in range(1, 5)]},
                               [])
    print(f"[{brain.name}] 원문: {raw.strip()}")
    print(f"[{brain.name}] 파싱: {action}")
    print("→ tap node_2가 나왔다면 정상입니다.")


def make_brain(name):
    if name == "local":
        return LocalBrain()
    if name == "gemini":
        brain = GeminiBrain()
        if not brain.api_key:
            raise BrainError(f"GEMINI_API_KEY가 비어 있습니다. {brain.hint()}")
        return brain
    raise BrainError(f"알 수 없는 브레인: {name} (local | gemini)")


if __name__ == "__main__":
    # 연결 확인용:  python3 eval/brains.py gemini
    import sys
    try:
        smoke_test(sys.argv[1] if len(sys.argv) > 1 else "local")
    except BrainError as error:
        sys.exit(f"실패: {error}")
