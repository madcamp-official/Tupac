"""에이전트 루프 — 목표를 주면 스스로 observe→판단→act를 반복한다.

구조:
    사용자 목표 → [모델(두뇌)] ⇄ [폰(MCP 도구 서버)]
    매 스텝: observe로 현재 화면을 읽고, 모델이 다음 행동 하나를 정한 뒤 실행.

    판단은 brains.py의 브레인이 맡는다. 이 파일은 폰과 이야기하고(mcp), 화면을
    글로 옮기고(render_screen), 행동을 실행하는(execute) 일만 한다.

준비:
  # 폰 연결 (공통)
  export TOKEN=$(adb shell run-as com.example.mobileguiagent \
      cat /data/data/com.example.mobileguiagent/shared_prefs/pocket_mcp_auth.xml \
      | sed -n 's/.*name="bearer_token">\\([^<]*\\)<.*/\\1/p')
  adb forward tcp:9911 tcp:8765

  # A) 로컬 모델 (기본) — 모델 서버를 따로 띄워둔다
  llama-server -m eval/models/EXAONE-4.0-1.2B-Q4_K_M.gguf -c 4096 --port 8080
  python3 eval/agent.py "와이파이 켜줘"

  # B) Gemini
  export GEMINI_API_KEY=...
  python3 eval/agent.py --brain gemini "와이파이 켜줘"

옵션:
  --brain NAME   판단에 쓸 모델: local(기본) | gemini
  --fallback N   민감한 화면을 맡길 모델 (기본 local). --brain gemini일 때만 쓰인다
  --steps N      최대 스텝 수 (기본 12)
  --all-nodes    라벨 없는 노드까지 모델에게 보여준다 (기본은 라벨 있는 것만)
  --dry          모델 판단만 보고 실제로 폰을 조작하지는 않는다
  --no-submit    절차서에 "제출 버튼을 누르지 말라"는 지시를 덧붙인다
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

import assign
import brains
import privacy
import skills

MCP_PORT = int(os.environ.get("POCKETMCP_PORT", "9911"))
MCP_URL = f"http://127.0.0.1:{MCP_PORT}/mcp"

MAX_PROMPT_NODES = 45
STALL_LIMIT = 3          # 화면이 이만큼 연속으로 안 바뀌면 중단한다
STRAY_LIMIT = 2          # 짚어준 단계와 다른 행동이 이만큼 이어지면 중단한다
SCROLL_LIMIT = 8         # 남은 칸을 찾아 화면을 훑어볼 최대 횟수(아래로 훑고 위로)
WAIT_SECONDS = 2.0       # wait 행동이 쉬는 시간


def scrub(text, secrets):
    """로그와 이력에서 금고 값을 가린다.

    핸드오프한 값은 기기 안 모델의 프롬프트에는 들어가지만, 그 밖으로는 나가면
    안 된다. 안 가리면 비밀번호가 터미널 스크롤백에 남고, 이력에 실려 다음 스텝
    프롬프트로 들어가며, 클라우드로 되돌아가는 순간 그대로 나간다.
    """
    for name, value in secrets.items():
        if value:
            text = text.replace(value, f"<{name} 값>")
    return text


def describe(observation, secrets=None):
    """화면에 보이는 라벨을 한 줄로. 사람이 어느 화면인지 알아보게만 하면 된다.

    금고 값을 가리는 건 이 줄이 사람에게만 보인다는 보장이 없어서다. 방금 채운
    폼을 그대로 읽으면 이름과 주소가 통째로 들어간다(실측: 마지막 화면 줄에
    "01000000000 / 서울시 ..."이 그대로 찍혔다). 이 문자열은 MCP 응답으로도
    나가므로 로그와 같은 기준으로 가린다.
    """
    labels = [(n.get("text") or n.get("content_description") or n.get("hint") or "").strip()
              for n in observation.get("nodes", [])]
    line = " / ".join(label for label in labels if label)[:150]
    return scrub(line, secrets) if secrets else line


def mcp(name, arguments):
    token = os.environ.get("TOKEN")
    if not token:
        sys.exit("TOKEN 환경변수가 없습니다.")
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": name, "arguments": arguments}}).encode()
    req = urllib.request.Request(
        MCP_URL, data=body,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    try:
        outer = json.load(urllib.request.urlopen(req, timeout=30))
    except OSError as error:
        # URLError만 잡으면 안 된다. adb forward는 살아 있는데 폰 쪽이 끊긴 경우
        # (기기 offline, 앱 종료) RemoteDisconnected가 나는데 이건 URLError가
        # 아니라 ConnectionResetError라 그대로 traceback이 쏟아진다. 둘 다 OSError다.
        sys.exit(f"폰 서버 연결 실패({MCP_URL}): {error}\n"
                 f"  1) adb devices → device 상태인가? (offline이면 adb kill-server 후 재연결)\n"
                 f"  2) adb forward --list → 없으면 adb forward tcp:{MCP_PORT} tcp:8765\n"
                 f"  3) 폰에서 앱이 켜져 있고 MCP 서버가 시작됐는가?")
    return json.loads(outer["result"]["content"][0]["text"])


def mcp_call(method, params):
    """MCP 서버에 raw JSON-RPC 요청. tools/call 말고 tools/list를 부를 때 쓴다."""
    token = os.environ.get("TOKEN")
    if not token:
        sys.exit("TOKEN 환경변수가 없습니다.")
    body = json.dumps({"jsonrpc": "2.0", "id": 1,
                       "method": method, "params": params}).encode()
    req = urllib.request.Request(
        MCP_URL, data=body,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


# 모델에게 보여줄 이름 -> (MCP tool, 그 tool에서 설명을 뽑을 인자)
SHORTCUT_SOURCES = (
    ("open에 쓸 수 있는 screen 값", "device_open_screen", "screen"),
    ("task에 쓸 수 있는 값", "device_start_task", "task"),
    ("system에 쓸 수 있는 key 값", "device_system_action", "key"),
)


def field_hint():
    """금고가 다루는 필드 설명. 스킬이 화면 라벨과 필드를 잇는 데 쓴다."""
    try:
        tools = {tool["name"]: tool for tool in mcp_call("tools/list", {})["result"]["tools"]}
        return tools["device_fill_field"]["inputSchema"]["properties"]["field"]["description"]
    except (OSError, KeyError, json.JSONDecodeError):
        return ""


def shortcut_hint():
    """폰이 실제로 지원하는 바로가기 목록을 tools/list에서 읽어온다.

    프롬프트에 목록을 하드코딩하면 앱에 기능을 추가했을 때 어긋난다. 스키마의
    enum과 description이 곧 모델에게 줄 설명이므로 그대로 가져온다. 구형 앱이
    깔린 폰에는 이 tool들이 없을 수 있어, 없는 건 조용히 건너뛴다.
    """
    try:
        tools = {tool["name"]: tool for tool in mcp_call("tools/list", {})["result"]["tools"]}
    except (OSError, KeyError, json.JSONDecodeError):
        return ""

    blocks = []
    for title, tool_name, argument in SHORTCUT_SOURCES:
        tool = tools.get(tool_name)
        if not tool:
            continue
        schema = tool["inputSchema"]["properties"][argument]
        described = schema.get("description") or ", ".join(schema.get("enum", []))
        blocks.append(f"{title}:\n{described}")
    return "\n\n".join(blocks)


def number(value):
    """슬라이더 값을 사람이 읽는 형태로. 0~1.0 범위든 0~255든 군더더기 없이."""
    return f"{float(value):g}"


def percent_of(span):
    """슬라이더의 지금 값이 몇 퍼센트인지."""
    width = float(span["max"]) - float(span["min"])
    if width <= 0:
        return 0
    return round((float(span["current"]) - float(span["min"])) * 100 / width)


def raw_of(span, percent):
    """퍼센트를 그 슬라이더가 쓰는 실제 값으로 되돌린다."""
    width = float(span["max"]) - float(span["min"])
    return float(span["min"]) + width * max(0.0, min(100.0, percent)) / 100


def render_screen(observation, all_nodes, redact=False):
    """화면을 모델에게 보여줄 간결한 텍스트로. JSON보다 토큰이 훨씬 적다.

    주의: 노드의 clickable 플래그를 그대로 쓰면 안 된다. 설정 앱처럼 리스트
    항목의 텍스트 노드는 clickable=false이고 라벨 없는 부모가 clickable인
    경우가 많은데, device_click_node는 그럴 때 부모의 clickable 조상으로
    올라가 눌러준다. 그래서 "라벨이 있는 노드"는 실질적으로 tap 대상이다.
    clickable 플래그를 그대로 노출하면 모델이 누를 게 없다고 착각한다.
    """
    lines = [f"SCREEN (app: {observation['package_name']})"]
    shown = 0
    for node in observation["nodes"]:
        # hint에서 온 라벨은 마스킹 기준이 다르다. 입력창의 hint는 앱이 그 칸에
        # 붙여둔 안내 문구라 길이로 가리지 않는다(privacy.redact 참고).
        label = (node.get("text") or node.get("content_description") or "")
        from_hint = not label and bool(node.get("hint"))
        if from_hint:
            label = node["hint"]
        label = label.replace("\n", " ")
        if redact:
            label = privacy.redact(
                label, observation,
                field_hint=from_hint and bool(node.get("editable")),
            )
        span = node.get("range")
        if not label and not span and not all_nodes:
            continue                      # 라벨 없는 노드는 모델이 고를 근거가 없다
        if span:
            # 슬라이더는 tap으로 값을 고를 수 없다. 지금 값을 함께 보여줘야
            # 모델이 "절반으로"를 숫자로 옮길 수 있다. 밝기 슬라이더는 라벨이
            # 아예 없는 경우가 많아, 이 표시가 유일한 단서이기도 하다.
            #
            # 슬라이더가 쓰는 실제 눈금은 감추고 퍼센트로만 보여준다. 눈금은
            # 위젯 마음대로다 — 실측으로 삼성 설정 앱의 밝기 슬라이더는
            # 0~267386880(255의 2^20배)을 쓴다. 그대로 내보내면 모델이 보는 화면이
            # "set 0~2.67387e+08, 지금 2.00278e+08"이 되고, 바로 옆 라벨은 "191"이라
            # 두 숫자가 서로 아무 관계 없어 보인다. 퍼센트는 어느 위젯이든 같다.
            flag = f"set 0~100, 지금 {percent_of(span)}"
        elif node["editable"]:
            # 비밀번호 칸을 표시해줘야 모델이 절차서의 "비밀번호가 아닌 칸이
            # 아이디"라는 구분을 화면에서 해낼 수 있다.
            flag = "type,비밀번호" if node.get("password") else "type"
        elif node["scrollable"] and not node["clickable"] and not label:
            flag = "scroll"
        else:
            flag = "tap"
        lines.append(f"{node['id']} [{flag}] {label[:60]}")
        shown += 1
        if shown >= MAX_PROMPT_NODES:
            lines.append(f"... ({observation['meaningful_node_count'] - shown} more, scroll to see)")
            break
    return "\n".join(lines)


def execute(action, observation, dry):
    """모델이 고른 행동을 실제로 수행하고, 결과를 한 줄 요약으로 돌려준다."""
    kind = action.get("action")
    if dry:
        return f"(dry-run) {kind} 실행 안 함"

    if kind == "tap":
        node_id = action.get("node_id", "")
        if not any(n["id"] == node_id for n in observation["nodes"]):
            return f"실패: {node_id}는 화면에 없는 노드"
        result = mcp("device_click_node",
                     {"snapshot_id": observation["snapshot_id"], "node_id": node_id})
        if result.get("success"):
            # 화면이 바뀌었는지는 여기서 붙이지 않는다. 다음 스텝의 observe와
            # 지문을 비교해 run()이 모든 행동에 똑같은 방식으로 판정한다.
            return f"성공: {node_id} 눌림"
        # 판단하는 사이에 화면이 변해 서버가 클릭을 거부한 경우다. 모델의 판단이
        # 틀린 게 아니므로 "실패"로 적으면 안 된다(다음 스텝에서 멀쩡한 선택지를
        # 피하게 된다). 화면에 시계가 있으면 1분만 지나도 지문이 바뀌므로,
        # 모델 응답이 느릴 때 반드시 발생한다.
        if result.get("error") in ("SCREEN_CHANGED", "STALE_SNAPSHOT"):
            return "무효: 판단하는 사이 화면이 바뀌어 취소됨. 판단 자체는 문제없음"
        return f"실패: {result.get('error') or result.get('message')}"

    if kind == "scroll":
        result = mcp("device_scroll", {"direction": action.get("direction", "down")})
        return "성공: 스크롤함" if result.get("success") else f"실패: {result.get('error')}"

    if kind == "type":
        node_id = action.get("node_id")
        if node_id:
            # 칸을 지목해 넣는다. 포커스에 기대면 크롬 웹 폼처럼 포커스가 안 잡히는
            # 화면에서 모든 값이 첫 칸에 덮어써진다(실측).
            result = mcp("device_type_node", {
                "snapshot_id": observation["snapshot_id"],
                "node_id": node_id,
                "text": action.get("text", ""),
            })
            return (f"성공: {node_id}에 입력함" if result.get("success")
                    else f"실패: {result.get('message') or result.get('error')}")
        result = mcp("device_type_text", {"text": action.get("text", "")})
        return "성공: 입력함" if result.get("success") else f"실패: {result.get('error')}"

    if kind == "set":
        node_id = action.get("node_id", "")
        node = next((n for n in observation["nodes"] if n["id"] == node_id), None)
        if node is None:
            return f"실패: {node_id}는 화면에 없는 노드"
        if not node.get("range"):
            # 화면 표시에 범위가 없는 노드를 set으로 고른 것이다. 폰까지 갈 것도
            # 없이 여기서 돌려준다 — 무엇을 잘못 골랐는지 이력에 남아야 고친다.
            return f"실패: {node_id}는 슬라이더가 아닙니다. tap으로 누르세요"
        try:
            percent = float(action.get("value"))
        except (TypeError, ValueError):
            return f"실패: set에는 숫자 value가 필요합니다. 받은 값: {action.get('value')}"
        if not 0 <= percent <= 100:
            return f"실패: set은 0~100 퍼센트로 주세요. 받은 값: {number(percent)}"
        # 모델은 퍼센트로 답하고, 그 슬라이더가 쓰는 눈금으로 되돌리는 건 여기서 한다.
        result = mcp("device_set_progress", {
            "snapshot_id": observation["snapshot_id"],
            "node_id": node_id,
            "value": raw_of(node["range"], percent),
        })
        if result.get("success"):
            return f"성공: {result.get('message')}"
        # tap과 같은 이유로 "실패"라고 적지 않는다. 슬라이더 화면은 특히 자주
        # 어긋난다 — 자동 밝기가 켜져 있으면 판단하는 사이에도 값이 움직이고,
        # 값이 지문에 들어 있어서 스냅샷이 바로 낡는다.
        if result.get("error") in ("SCREEN_CHANGED", "STALE_SNAPSHOT"):
            return "무효: 판단하는 사이 화면이 바뀌어 취소됨. 판단 자체는 문제없음"
        return f"실패: {result.get('message') or result.get('error')}"

    if kind == "system":
        key = action.get("key", "")
        result = mcp("device_system_action", {"key": key})
        if result.get("success"):
            return f"성공: {result.get('message')}"
        # 잠금화면처럼 시스템이 거부하는 상황이 있다. 이유를 그대로 전달한다.
        return f"실패: {result.get('message') or result.get('error')}"

    if kind == "wait":
        # 화면 전환이나 서버 응답을 기다리는 동안 아무것도 하지 않는다.
        time.sleep(WAIT_SECONDS)
        return "성공: 잠시 기다림"

    if kind == "back":
        result = mcp("device_back", {})
        return "성공: 뒤로감" if result.get("success") else f"실패: {result.get('error')}"

    if kind == "open":
        screen = action.get("screen", "")
        result = mcp("device_open_screen", {"screen": screen})
        if result.get("success"):
            return f"성공: {screen} 화면을 바로 열었음"
        # 기기에 없는 화면이면 모델이 알아서 눌러 찾아가야 한다. 이유를 그대로 전달.
        return f"실패: {result.get('message') or result.get('error')}"

    if kind == "task":
        arguments = {"task": action.get("task", "")}
        for key in ("value", "text"):
            if action.get(key):
                arguments[key] = action[key]
        result = mcp("device_start_task", arguments)
        # 실패 사유(인자 누락, 잘못된 시각 형식...)는 폰이 고칠 방법까지 적어 보낸다.
        return (f"성공: {result.get('message')}" if result.get("success")
                else f"실패: {result.get('message') or result.get('error')}")

    if kind == "launch":
        result = mcp("device_launch_app", {"name": action.get("app", "")})
        if result.get("success"):
            return f"성공: {result.get('message')}"
        # 못 찾았으면 폰이 비슷한 앱 이름을 알려준다. 모델이 다음 스텝에 고쳐 부른다.
        return f"실패: {result.get('message') or result.get('error')}"

    if kind == "fill":
        result = mcp("device_fill_field", {
            "snapshot_id": observation["snapshot_id"],
            "node_id": action.get("node_id", ""),
            "field": action.get("field", ""),
        })
        # 성공 메시지에도 값은 없다. 폰이 "password 값을 입력했습니다"까지만 준다.
        return (f"성공: {result.get('message')}" if result.get("success")
                else f"실패: {result.get('message') or result.get('error')}")

    if kind == "list_apps":
        result = mcp("device_list_apps", {"query": action.get("app", "")})
        return f"설치된 앱: {result.get('message')}"

    return f"알 수 없는 행동: {kind}"


def run(goal, cloud, fallback, max_steps, all_nodes, dry, no_submit=False):
    """cloud로 진행하다가, 민감한 화면을 만나면 fallback(기기 안 모델)으로 넘긴다."""
    note = "" if cloud is fallback else f", 값 입력은 {fallback.name}"
    print(f"목표: {goal}  (brain: {cloud.name}{note})\n{'=' * 60}")
    shortcuts = shortcut_hint()      # 폰이 지원하는 바로가기. 스텝마다 바뀌지 않는다.
    fields = field_hint()            # 금고가 다루는 필드. 절차서에 함께 싣는다.
    # 절차서는 호출 대상이 아니라 참고 문서다. 전체 본문을 처음부터 프롬프트에
    # 싣고, 어느 절차가 지금 상황에 맞는지는 모델이 읽고 판단한다.
    catalog = skills.load()
    reference = skills.reference_text(catalog, fields, no_submit)
    # 클라우드가 need를 내면 여기에 값과 절차서가 담기고, 그때부터 기기 안
    # 모델이 이어받는다. secrets는 로그·이력에서 값을 가리는 데 쓴다.
    handoff, secrets = None, {}
    # 요청했지만 금고에 없던 필드. 값이 빠진 채로 제출하지 않기 위한 것이다.
    # secrets에는 아예 안 담기므로 아래 left 계산만으로는 보이지 않는다.
    unmet = []
    # 계획은 "무엇을 채울지"만 담는다. "어느 칸인지"는 매 스텝 다시 찾는다 —
    # 노드 번호는 스냅샷마다 새로 매겨져서 미리 박아두면 어긋난다.
    order, want_submit, filled, submitted = [], False, set(), False
    plan, current, strays, scrolls = [], None, 0, 0
    hunt = "down"            # 남은 칸을 찾아 훑는 방향. 바닥에 닿으면 뒤집는다
    history = []
    verdict = []           # stray()가 남기는 한 줄 결론. run()이 그대로 돌려준다.

    def stray(what):
        """짚어준 줄과 다른 답이 이어지면 멈춘다. 멈춰야 하면 True.

        모델이 계획 밖에서 화면을 헤집게 두지 않는다. 성공했든 실패했든, 형식이
        틀렸든 모두 "짚어준 것과 다르다"로 똑같이 센다.
        """
        nonlocal strays
        strays += 1
        if strays < STRAY_LIMIT:
            return False
        print(f"{'=' * 60}\n짚어준 단계와 다른 응답이 {strays}번 이어져 중단합니다 ({what}).")
        if current:
            print(f"  짚어준 것: {scrub(current['line'], secrets)}")
        print(f"현재 화면: [{observation['package_name']}] {describe(observation, secrets)}")
        verdict.append("중단: 짚어준 단계와 다른 응답이 이어졌습니다")
        return True

    previous_id = None
    pending = None          # 직전 행동의 이력. 화면이 바뀌었는지는 아직 모른다.
    pending_kind = None
    stalled = 0             # 화면이 연속으로 안 바뀐 횟수
    for step in range(1, max_steps + 1):
        observation = mcp("device_observe", {"max_nodes": 500})
        if "snapshot_id" not in observation:
            print("observe 실패:", observation)
            return "중단: 화면을 읽지 못했습니다"

        # 화면 변화 판정을 여기서 한다. 행동 직후에 폰에게 물어보면 전환
        # 애니메이션 중이라 부정확하고, scroll·back은 애초에 알려주지도 않는다.
        # 다음 스텝의 observe(전환이 끝난 뒤)와 지문을 비교하는 게 정확하고,
        # 모든 행동에 똑같이 적용된다.
        #
        # 이 신호가 없으면 모델은 헛스크롤을 반복한다(실측: 설정 화면에서
        # scroll up을 3연속). 이력에 "성공: 스크롤함"만 남아서 아무 일도
        # 일어나지 않았다는 걸 알 방법이 없었다.
        if pending is not None:
            changed = observation["snapshot_id"] != previous_id
            history.append(f"{pending} ({'화면 바뀜' if changed else '화면 그대로'})")
            # 기다리는 중에 화면이 그대로인 건 정체가 아니라 의도한 것이다.
            waiting = pending_kind == "wait"
            stalled = 0 if (changed or waiting) else stalled + 1
            # 기다리는 중에 화면이 그대로인 건 알릴 일이 아니다. 의도한 것이다.
            if not changed and not waiting:
                print(f"     ↳ 화면이 바뀌지 않았습니다 ({stalled}회 연속)")
            pending = None
        previous_id = observation["snapshot_id"]

        # 아무것도 안 바뀌는 상태로 계속 도는 건 진전이 아니라 낭비다. 스텝마다
        # 모델을 부르므로 할당량까지 태운다. 잠금화면처럼 에이전트가 원리상
        # 벗어날 수 없는 화면에서 특히 그렇다(실측: 12스텝 내내 잠금 해제 시도).
        if stalled >= STALL_LIMIT:
            print(f"{'=' * 60}\n{stalled}스텝 연속으로 화면이 전혀 바뀌지 않아 중단합니다.")
            print(f"마지막 화면: [{observation['package_name']}] "
                  f"{describe(observation, secrets)}")
            print("→ 폰이 잠겨 있거나, 에이전트가 조작할 수 없는 화면일 수 있습니다.")
            return f"중단: {stalled}스텝 연속으로 화면이 바뀌지 않았습니다"

        # 라우팅. 민감한 화면은 기기 밖으로 내보내지 않는다. 판정 단위가 화면인
        # 이유는 observe가 화면 텍스트를 통째로 주기 때문이다. type만 로컬로
        # 돌려봐야 이미 입력된 주민번호가 관찰 단계에서 나가버린다.
        # 라우팅. 클라우드는 로그인 화면까지 보고 "무슨 값이 필요한지"를 판단해야
        # 하므로, 화면이 민감하다는 이유만으로 막지 않는다(막으면 흐름이 끊긴다).
        # 대신 두 가지는 지킨다. 은행·결제·인증 앱은 화면 자체를 클라우드에
        # 안 보내고, 클라우드가 need를 낸 뒤로는 기기 안 모델이 이어받는다.
        blocked = privacy.blocked_app(observation)
        brain = fallback if ((blocked or handoff) and cloud.online) else cloud
        if brain is not cloud:
            why = blocked or "값 입력 구간"
            print(f"     ↳ {why} → {brain.name} 모델로 처리")

        screen = render_screen(observation, all_nodes, redact=brain.online)
        # 실수로 민감 앱이 나가는 일을 코드로 막는다. 라우팅 조건을 나중에
        # 손대다 어긋나면 조용히 유출되므로, 여기서 멈추는 편이 낫다.
        if brain.online and blocked:
            sys.exit(f"[중단] {blocked}을 온라인 모델로 보내려 했습니다.")

        started = time.time()
        try:
            if handoff:
                # 폼이 한 화면보다 길면 아래쪽 칸은 관찰에 아예 없다(실측: 크롬
                # 배송지 폼에서 "상세주소"가 트리에 없었다). 스크롤해서 보일 때마다
                # 계획에 더한다. 계획은 이렇게 자란다.
                for field in assign.plan_fields(observation, secrets, fields):
                    if field not in order:
                        order.append(field)
                # 아직 어느 칸에도 못 놓은 값이 있으면 제출하지 않는다. 덜 채운
                # 폼을 보내는 건 되돌리기 어렵다 — 로그인 실패로 계정이 잠기거나,
                # 주소가 빠진 주문이 들어간다.
                left = [field for field in secrets if field not in order]
                plan, current, blocked = assign.steps_now(
                    observation, secrets, fields, order,
                    want_submit and not left and not unmet, filled, submitted)

                # 보이는 칸을 다 채웠는데 아직 못 넣은 값이 남았으면 화면 밖에 칸이
                # 더 있을 수 있다. 이걸 모델에게 시키지 않는다 — 스크롤은 판단이
                # 아니라 화면을 넓히는 일이고, 기기 안 모델은 헛스크롤을 반복한다.
                waiting = [step_["field"] for step_ in plan
                           if step_["action"] == "type" and step_["state"] == "todo"]
                if not waiting and left and scrolls < SCROLL_LIMIT:
                    scrolls += 1
                    mcp("device_scroll", {"direction": hunt})
                    time.sleep(1.0)
                    after = mcp("device_observe", {"max_nodes": 500})
                    moved = after.get("snapshot_id") != observation["snapshot_id"]
                    where = "아래로" if hunt == "down" else "위로"
                    print(f"[{step}] {where} 스크롤 — {', '.join(left)} 칸을 찾습니다"
                          f" ({'화면 이동' if moved else '끝까지 왔음'})")
                    previous_id = after.get("snapshot_id")
                    if moved:
                        continue
                    if hunt == "down":
                        # 아래는 끝까지 봤다. 시작한 자리보다 위에 있는 칸이 남아
                        # 있을 수 있다(실측: 페이지가 이미 내려간 채로 시작해서
                        # "받는사람"을 지나쳤다). 방향을 뒤집어 되짚는다.
                        hunt = "up"
                        continue
                    # 위아래 모두 훑었다. 없는 칸을 더 찾아봐야 헛일이다.
                    scrolls = SCROLL_LIMIT

                # 짚어줄 줄이 없으면 모델에게 묻지 않는다. 기기 안 모델의 일은
                # 짚어준 줄을 실행하는 것뿐이고, 그 밖의 판단은 여기서 멈춘다.
                if blocked:
                    print(f"{'=' * 60}\n{blocked}")
                    print(f"현재 화면: [{observation['package_name']}] "
                          f"{describe(observation, secrets)}")
                    print("→ 예상과 다른 화면입니다. 직접 확인하세요.")
                    return f"중단: {blocked}"
                # 계획이 끝났으면 모델에게 물을 것이 없다. 여기서 마무리한다.
                # 실측: 다 끝난 뒤 오류 대화상자를 만난 1.2B가 "type node_12 값 /
                # tap node_18 / wait / done" 같은 문자열을 반복해 뱉었다. 남은
                # 판단이 없는데 모델을 부르면 그런 헛수고만 생긴다.
                if current["action"] == "done":
                    did = ", ".join(order) + (" 입력 후 제출" if submitted else " 입력")
                    print(f"{'=' * 60}\n계획한 단계를 모두 마쳤습니다 ({did}).")
                    # 못 채운 이유가 둘이라 따로 알린다. 칸을 못 찾은 것과 값이
                    # 없는 것은 사람이 할 일이 다르다 — 앞은 화면을 확인하는
                    # 일이고, 뒤는 금고에 등록하는 일이다.
                    if left:
                        print(f"→ {', '.join(left)}는 넣을 칸을 못 찾아 비워뒀습니다")
                    if unmet:
                        print(f"→ {', '.join(unmet)}는 금고에 없어 비워뒀습니다")
                    if (left or unmet) and want_submit:
                        print("→ 값이 빠져 제출하지 않았습니다")
                    print(f"현재 화면: [{observation['package_name']}] "
                          f"{describe(observation, secrets)}")
                    print("→ 결과를 화면에서 확인하세요.")
                    return f"완료: {did}"
                context = skills.handoff_text(plan, current)
            else:
                context = reference
            action, raw = brain.decide(goal, screen, observation, history,
                                       shortcuts, context, focused=bool(handoff))
        except brains.BrainError as error:
            sys.exit(f"모델 호출 실패: {error}\n  {brain.hint(error.status)}")
        elapsed = time.time() - started

        if action is None:
            print(f"[{step}] 응답을 해석하지 못해 이 스텝을 건너뜁니다: {raw[:120]}")
            history.append(f"step{step}: 형식 오류 → 건너뜀")
            # 값 입력 구간에서는 이것도 어긋난 것으로 센다. 안 세면 같은 형식
            # 오류로 남은 스텝을 전부 태운다(실측: 12스텝 연속 같은 응답).
            if handoff and stray("형식 오류"):
                return
            continue

        # 모델이 넘긴 인자를 로그에 남긴다. 없으면 실패했을 때 무엇을 넘겼는지
        # 알 수가 없다(실측: task만 찍히고 web_search인지 timer인지 안 보였다).
        detail = " ".join(str(action.get(key)) for key in
                          ("node_id", "screen", "task", "key", "value", "app", "field",
                           "direction", "text", "mark")
                          if action.get(key))
        detail = scrub(detail, secrets)
        print(f"[{step}] {action['action']} {detail}"
              f"  ({observation['meaningful_node_count']}노드, {elapsed:.1f}s)")
        if action.get("reason"):
            print(f"     이유: {action['reason'][:100]}")

        if action["action"] == "need":
            wanted = action.get("fields") or []
            name = skills.for_fields(catalog, wanted)
            # 받아온 값을 바로 secrets에 넣지 않는다. 계정이 반쪽이면 아무것도
            # 쓰지 않고 물러나야 하는데, 미리 넣으면 되돌릴 자리가 없다.
            fetched, missing = {}, []
            for key in wanted:
                got = mcp("device_get_field", {"field": key})
                if got.get("success"):
                    fetched[key] = got["value"]
                else:
                    missing.append(f"{key}({got.get('message') or got.get('error')})")
            half = assign.unusable_account(wanted, fetched)
            if half:
                outcome = (f"실패: {', '.join(half)}가 이 앱에 등록돼 있지 않습니다. "
                           "계정은 아이디와 비밀번호가 모두 있어야 씁니다 "
                           "(앱의 \"내 정보\" 화면에서 등록하세요)")
            elif not fetched or name is None:
                outcome = (f"실패: 값을 얻지 못했습니다. {', '.join(missing)}"
                           if missing else f"실패: {wanted}에 맞는 절차서가 없습니다")
            else:
                secrets.update(fetched)
                # 요청했는데 금고에 없던 것. 아래에서 제출을 막는 데 쓴다.
                unmet = [key for key in wanted if key not in fetched]
                # 어느 칸에 무엇을 넣을지는 여기서 정한다. 모델에게 화면을 보고
                # 고르게 하면 틀린다(실측). 절차서의 submit 여부는 그대로 따른다.
                want_submit = bool(catalog[name].get("submit")) and not no_submit
                order = assign.plan_fields(observation, secrets, fields)
                filled, submitted, scrolls, hunt = set(), False, 0, "down"
                # 지금 화면에 맞는 칸이 하나도 없어도 넘긴다. 폼이 아래로 길면
                # 첫 화면에 아무 칸도 안 보일 수 있고(실측: 크롬 폼을 내린 채로
                # 시작하면 위쪽 칸이 트리에 없다), 그때는 code가 훑어서 찾는다.
                handoff = True
                outcome = ("성공: " + (", ".join(order) + " 순서로 채웁니다"
                                       if order else "화면을 훑어 칸을 찾습니다")
                           + (" (그다음 제출)" if want_submit else ""))
            print(f"     결과: {outcome}")
            pending = f"step{step}: need {' '.join(wanted)} → {outcome}"
            pending_kind = "need"
            continue

        if action["action"] == "done":
            who = "스킬이" if raw == "(skill)" else "모델이"
            print(f"{'=' * 60}\n{who} 마무리했습니다. 화면을 확인하세요.")
            return f"완료: {who} 목표를 마쳤습니다"

        # 값 입력 구간에서는 짚어준 단계만 실행한다. 모델의 답은 "그 단계를 할
        # 차례가 맞다"는 확인으로만 쓰고, 실제로 넣을 값은 code가 들고 있는 것을
        # 쓴다. 모델의 답을 그대로 실행하면 옮겨 적다 빠뜨린 값이 화면에 들어가고,
        # 엉뚱한 칸을 짚었을 때 그 칸이 실제로 채워진다.
        if handoff and current:
            if (action["action"] != current["action"]
                    or action.get("node_id") != current.get("node_id")):
                print(f"     결과: 건너뜀 — 짚어준 것은 {scrub(current['line'], secrets)}")
                if stray("다른 단계를 지목"):
                    return verdict[-1]
                history.append(f"step{step}: 계획과 다른 답 → 실행하지 않음")
                continue
            action = assign.action_for(current, secrets)

        outcome = scrub(execute(action, observation, dry), secrets)
        print(f"     결과: {outcome}")
        # 이 한 번으로 끝나는 행동이었으면 여기서 마친다. 모델에게 "다 됐다"고
        # 말하게 시키면 못 한다(실측: 1.2B는 done을 좀처럼 내지 않는다).
        if action.get("final") and outcome.startswith("성공"):
            print(f"{'=' * 60}\n요청한 작업을 실행했습니다. 화면을 확인하세요.")
            return f"완료: {outcome}"
        if handoff and current and outcome.startswith("성공"):
            if current["action"] == "type":
                filled.add(current["field"])
            elif current["action"] == "tap":
                submitted = True
            strays = 0
        # 아직 history에 넣지 않는다. 다음 observe로 화면 변화를 확인한 뒤 붙인다.
        # 이력은 다음 스텝 프롬프트로 들어가고, 클라우드로 되돌아갈 수도 있다.
        # detail·outcome은 이미 scrub을 거쳤지만 한 번 더 확인한다.
        pending = scrub(f"step{step}: {action['action']} {detail} → {outcome}", secrets)
        pending_kind = action["action"]
        time.sleep(1.2)          # 화면 전환이 끝날 때까지 잠깐 기다린다

    # done을 못 뽑았다고 실패는 아니다. 목표를 이미 이뤘는데도 완료 선언만
    # 못 하는 경우가 잦아, 마지막 화면을 보여주고 사람이 판단하게 한다.
    final = mcp("device_observe", {"max_nodes": 500})
    print(f"{'=' * 60}\n{max_steps}스텝을 모두 사용했습니다 (모델이 done을 선언하지 않음).")
    print(f"마지막 화면: [{final.get('package_name', '?')}] {describe(final, secrets)}")
    print("→ 목표가 달성됐는지 폰 화면으로 확인하세요.")
    return f"미완: {max_steps}스텝을 모두 썼지만 모델이 완료를 선언하지 않았습니다"


def parse_argv(argv):
    """--flag / --flag VALUE 와 목표 문장을 갈라낸다."""
    options = {"brain": "local", "fallback": "local", "steps": 12,
               "all_nodes": False, "dry": False, "no_submit": False}
    words = []
    index = 0
    while index < len(argv):
        token = argv[index]
        if token == "--brain" and index + 1 < len(argv):
            options["brain"] = argv[index + 1]
            index += 2
        elif token == "--fallback" and index + 1 < len(argv):
            options["fallback"] = argv[index + 1]
            index += 2
        elif token == "--steps" and index + 1 < len(argv):
            options["steps"] = int(argv[index + 1])
            index += 2
        elif token == "--all-nodes":
            options["all_nodes"] = True
            index += 1
        elif token == "--dry":
            options["dry"] = True
            index += 1
        elif token == "--no-submit":
            options["no_submit"] = True
            index += 1
        elif token.startswith("--"):
            sys.exit(f"알 수 없는 옵션: {token}\n{__doc__}")
        else:
            words.append(token)
            index += 1
    options["goal"] = " ".join(words)
    return options


if __name__ == "__main__":
    parsed = parse_argv(sys.argv[1:])
    if not parsed["goal"]:
        print(__doc__)
        sys.exit(0)
    try:
        selected = brains.make_brain(parsed["brain"])
        # 클라우드로 돌릴 때만 기기 안 모델을 함께 준비한다. 민감 화면을 만나면
        # 그쪽으로 넘긴다. 애초에 로컬로 돌리는 중이면 넘길 곳이 없다(자기 자신).
        fallback = brains.make_brain(parsed["fallback"]) if selected.online else selected
    except brains.BrainError as error:
        sys.exit(str(error))
    run(parsed["goal"], selected, fallback, parsed["steps"],
        parsed["all_nodes"], parsed["dry"], parsed["no_submit"])
