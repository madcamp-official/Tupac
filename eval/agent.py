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
  --no-submit    로그인 스킬이 값만 채우고 제출 버튼은 누르지 않는다
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

import brains
import privacy
import skills

MCP_PORT = int(os.environ.get("POCKETMCP_PORT", "9911"))
MCP_URL = f"http://127.0.0.1:{MCP_PORT}/mcp"

MAX_PROMPT_NODES = 45
STALL_LIMIT = 3          # 화면이 이만큼 연속으로 안 바뀌면 중단한다


def describe(observation):
    """화면에 보이는 라벨을 한 줄로. 사람이 어느 화면인지 알아보게만 하면 된다."""
    labels = [(n.get("text") or n.get("content_description") or "").strip()
              for n in observation.get("nodes", [])]
    return " / ".join(label for label in labels if label)[:150]


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
        label = (node.get("text") or node.get("content_description") or "").replace("\n", " ")
        if redact:
            label = privacy.redact(label, observation.get("package_name"))
        if not label and not all_nodes:
            continue                      # 라벨 없는 노드는 모델이 고를 근거가 없다
        if node["editable"]:
            flag = "type"
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
        result = mcp("device_type_text", {"text": action.get("text", "")})
        return "성공: 입력함" if result.get("success") else f"실패: {result.get('error')}"

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
        result = mcp("device_fill_field", {"field": action.get("field", "")})
        # 성공 메시지에도 값은 없다. 폰이 "password 값을 입력했습니다"까지만 준다.
        return (f"성공: {result.get('message')}" if result.get("success")
                else f"실패: {result.get('message') or result.get('error')}")

    if kind == "list_apps":
        result = mcp("device_list_apps", {"query": action.get("app", "")})
        return f"설치된 앱: {result.get('message')}"

    return f"알 수 없는 행동: {kind}"


def run(goal, cloud, fallback, max_steps, all_nodes, dry, auto_submit=True):
    """cloud로 진행하다가, 민감한 화면을 만나면 fallback(기기 안 모델)으로 넘긴다."""
    note = "" if cloud is fallback else f", 민감 화면은 {fallback.name}"
    print(f"목표: {goal}  (brain: {cloud.name}{note})\n{'=' * 60}")
    shortcuts = shortcut_hint()      # 폰이 지원하는 바로가기. 스텝마다 바뀌지 않는다.
    fields = field_hint()            # 금고가 다루는 필드. 스킬이 쓴다.
    history = []
    previous_id = None
    pending = None          # 직전 행동의 이력. 화면이 바뀌었는지는 아직 모른다.
    stalled = 0             # 화면이 연속으로 안 바뀐 횟수
    for step in range(1, max_steps + 1):
        observation = mcp("device_observe", {"max_nodes": 500})
        if "snapshot_id" not in observation:
            print("observe 실패:", observation)
            return

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
            stalled = 0 if changed else stalled + 1
            if not changed:
                print(f"     ↳ 화면이 바뀌지 않았습니다 ({stalled}회 연속)")
            pending = None
        previous_id = observation["snapshot_id"]

        # 아무것도 안 바뀌는 상태로 계속 도는 건 진전이 아니라 낭비다. 스텝마다
        # 모델을 부르므로 할당량까지 태운다. 잠금화면처럼 에이전트가 원리상
        # 벗어날 수 없는 화면에서 특히 그렇다(실측: 12스텝 내내 잠금 해제 시도).
        if stalled >= STALL_LIMIT:
            print(f"{'=' * 60}\n{stalled}스텝 연속으로 화면이 전혀 바뀌지 않아 중단합니다.")
            print(f"마지막 화면: [{observation['package_name']}] "
                  f"{describe(observation)}")
            print("→ 폰이 잠겨 있거나, 에이전트가 조작할 수 없는 화면일 수 있습니다.")
            return

        # 라우팅. 민감한 화면은 기기 밖으로 내보내지 않는다. 판정 단위가 화면인
        # 이유는 observe가 화면 텍스트를 통째로 주기 때문이다. type만 로컬로
        # 돌려봐야 이미 입력된 주민번호가 관찰 단계에서 나가버린다.
        reason = privacy.sensitive_reason(observation)
        brain = fallback if (reason and cloud.online) else cloud
        if reason and brain is not cloud:
            print(f"     ↳ 민감 화면({reason}) → {brain.name} 모델로 처리")

        screen = render_screen(observation, all_nodes, redact=brain.online)
        # 실수로 민감 화면이 나가는 일을 코드로 막는다. 라우팅 조건을 나중에
        # 손대다 어긋나면 조용히 유출되므로, 여기서 멈추는 편이 낫다.
        if brain.online and reason:
            sys.exit(f"[중단] 민감 화면({reason})을 온라인 모델로 보내려 했습니다.")

        # 정해진 절차로 끝나는 일은 모델에게 묻지 않는다. 로그인처럼 개인정보를
        # 다루는 화면이 그렇다. 스킬이 맡지 않겠다고 할 때만 모델을 부른다.
        started = time.time()
        action = skills.next_action(observation, history, fields, auto_submit)
        raw = "(skill)"
        if action is None:
            try:
                action, raw = brain.decide(goal, screen, observation, history, shortcuts)
            except brains.BrainError as error:
                sys.exit(f"모델 호출 실패: {error}\n  {brain.hint(error.status)}")
        elapsed = time.time() - started

        if action is None:
            print(f"[{step}] 응답을 해석하지 못해 이 스텝을 건너뜁니다: {raw[:120]}")
            history.append(f"step{step}: 형식 오류 → 건너뜀")
            continue

        # 모델이 넘긴 인자를 로그에 남긴다. 없으면 실패했을 때 무엇을 넘겼는지
        # 알 수가 없다(실측: task만 찍히고 web_search인지 timer인지 안 보였다).
        detail = " ".join(str(action.get(key)) for key in
                          ("node_id", "screen", "task", "value", "app", "field",
                           "direction", "text")
                          if action.get(key))
        print(f"[{step}] {action['action']} {detail}"
              f"  ({observation['meaningful_node_count']}노드, {elapsed:.1f}s)")
        if action.get("reason"):
            print(f"     이유: {action['reason'][:100]}")

        if action["action"] == "done":
            print(f"{'=' * 60}\n모델이 목표 달성을 선언했습니다. 화면을 확인하세요.")
            return

        outcome = execute(action, observation, dry)
        print(f"     결과: {outcome}")
        # 아직 history에 넣지 않는다. 다음 observe로 화면 변화를 확인한 뒤 붙인다.
        pending = f"step{step}: {action['action']} {detail} → {outcome}"
        time.sleep(1.2)          # 화면 전환이 끝날 때까지 잠깐 기다린다

    # done을 못 뽑았다고 실패는 아니다. 목표를 이미 이뤘는데도 완료 선언만
    # 못 하는 경우가 잦아, 마지막 화면을 보여주고 사람이 판단하게 한다.
    final = mcp("device_observe", {"max_nodes": 500})
    print(f"{'=' * 60}\n{max_steps}스텝을 모두 사용했습니다 (모델이 done을 선언하지 않음).")
    print(f"마지막 화면: [{final.get('package_name', '?')}] {describe(final)}")
    print("→ 목표가 달성됐는지 폰 화면으로 확인하세요.")


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
        parsed["all_nodes"], parsed["dry"], not parsed["no_submit"])
