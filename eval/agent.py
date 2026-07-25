"""로컬 LLM 에이전트 루프 — 목표를 주면 스스로 observe→판단→act를 반복한다.

구조:
    사용자 목표 → [모델(두뇌)] ⇄ [폰(MCP 도구 서버)]
    매 스텝: observe로 현재 화면을 읽고, 모델이 다음 행동 하나를 JSON으로 정한 뒤 실행.

준비 (터미널 2개):
  # 1) 모델 서버 (한 번 띄워두면 계속 재사용)
  llama-server -m eval/models/EXAONE-4.0-1.2B-Q4_K_M.gguf -c 4096 --port 8080

  # 2) 에이전트 실행
  export TOKEN=$(adb shell run-as com.example.mobileguiagent \
      cat /data/data/com.example.mobileguiagent/shared_prefs/pocket_mcp_auth.xml \
      | sed -n 's/.*name="bearer_token">\\([^<]*\\)<.*/\\1/p')
  adb forward tcp:9911 tcp:8765
  python3 eval/agent.py "와이파이 켜줘"

옵션:
  --steps N      최대 스텝 수 (기본 12)
  --all-nodes    라벨 없는 노드까지 모델에게 보여준다 (기본은 라벨 있는 것만)
  --dry          모델 판단만 보고 실제로 폰을 조작하지는 않는다
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

MCP_PORT = int(os.environ.get("POCKETMCP_PORT", "9911"))
MCP_URL = f"http://127.0.0.1:{MCP_PORT}/mcp"
LLM_URL = os.environ.get("LLM_URL", "http://127.0.0.1:8080/v1/chat/completions")

MAX_HISTORY = 3          # 1.2B 모델은 긴 이력을 감당 못 하므로 최근 것만 보여준다
MAX_PROMPT_NODES = 45

SYSTEM_PROMPT = """당신은 안드로이드 휴대폰을 조작해서 사용자의 목표를 달성합니다.
매 턴마다 현재 화면의 노드 목록을 보고, 다음 행동 하나를 고릅니다.

가능한 행동:
- tap: 노드를 누른다. 버튼·목록 항목·앱 아이콘에 사용. node_id가 필요.
- scroll: 화면 밖 내용을 드러낸다. direction은 보고 싶은 방향(down/up/right/left).
- type: 입력창에 글자를 넣는다. 먼저 입력창을 tap 해야 한다. text가 필요.
- back: 이전 화면으로 돌아간다.
- done: 목표가 이미 이 화면에서 달성됐다.

규칙:
- 목표와 관련된 노드가 화면에 있으면 반드시 tap 하세요. 함부로 scroll 하지 마세요.
- 목록에 있는 node_id만 쓰세요. 없는 번호를 지어내지 마세요.
- 라벨이 목표와 정확히 같지 않아도, 목표로 가는 길목이면 그것을 고르세요.
  (예: 글자 크기는 "디스플레이" 안에 있고, Wi-Fi는 "연결" 안에 있습니다.)
- 목표한 것이 화면에 전혀 없을 때만 scroll 하세요.

반드시 JSON 하나만 출력하세요. reason을 먼저 쓰고 그다음 action을 쓰세요.
예시:
{"reason": "Wi-Fi는 연결 메뉴 안에 있다", "action": "tap", "node_id": "node_41"}
{"reason": "목표한 항목이 화면에 없다", "action": "scroll", "direction": "down"}
{"reason": "검색창에 글자를 넣는다", "action": "type", "text": "와이파이"}
{"reason": "이미 Wi-Fi 화면에 도착했다", "action": "done"}"""

ACTION_SCHEMA = {
    "type": "object",
    "properties": {
        "action": {"type": "string", "enum": ["tap", "scroll", "type", "back", "done"]},
        "node_id": {"type": "string"},
        "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
        "text": {"type": "string"},
        "reason": {"type": "string"},
    },
    "required": ["action"],
}


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
    except urllib.error.URLError as error:
        sys.exit(f"폰 서버 연결 실패({MCP_URL}): {error}\n"
                 f"  adb forward --list 확인 → 없으면 adb forward tcp:{MCP_PORT} tcp:8765")
    return json.loads(outer["result"]["content"][0]["text"])


def ask_model(messages):
    body = json.dumps({
        "messages": messages,
        "temperature": 0.1,          # EXAONE 카드 권장: 한국어는 낮은 온도
        "max_tokens": 200,
        # json_schema로 문법을 강제하면 모델이 생각하기 전에 action부터 확정하게 되어
        # (실측) 계속 scroll만 고르는 문제가 있었다. 형식은 프롬프트로 유도하고
        # 파싱은 parse_action에서 관대하게 처리한다.
    }).encode()
    req = urllib.request.Request(
        LLM_URL, data=body, headers={"Content-Type": "application/json"})
    try:
        data = json.load(urllib.request.urlopen(req, timeout=180))
    except urllib.error.URLError as error:
        sys.exit(f"모델 서버 연결 실패({LLM_URL}): {error}\n"
                 f"  llama-server -m eval/models/EXAONE-4.0-1.2B-Q4_K_M.gguf "
                 f"-c 4096 --port 8080 을 먼저 실행하세요.")
    return data["choices"][0]["message"]["content"]


def render_screen(observation, all_nodes):
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


def parse_action(raw):
    """모델 응답에서 행동을 뽑아낸다.

    작은 모델에 JSON 형식을 문법으로 강제하면 생각하기 전에 action부터 확정해
    판단 품질이 무너진다(실측). 그래서 형식은 프롬프트로만 유도하고, 자연어로
    답하더라도 여기서 관대하게 해석한다.
    """
    for candidate in (raw, raw[raw.find("{"):raw.rfind("}") + 1] if "{" in raw else ""):
        if not candidate:
            continue
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict) and "action" in parsed:
                return parsed
        except json.JSONDecodeError:
            pass

    # 자연어 응답 해석: 언급된 node_id + 행동 키워드
    text = raw.lower()
    node_ids = re.findall(r"node_\d+", raw)
    if any(k in text for k in ("done", "이미", "달성", "완료했")):
        return {"action": "done", "reason": raw[:120]}
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
            return (f"성공: {node_id} 눌림"
                    f"{' (화면 바뀜)' if result.get('screen_changed') else ' (화면 그대로)'}")
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

    return f"알 수 없는 행동: {kind}"


def run(goal, max_steps, all_nodes, dry):
    print(f"목표: {goal}\n{'=' * 60}")
    history = []
    for step in range(1, max_steps + 1):
        observation = mcp("device_observe", {"max_nodes": 500})
        if "snapshot_id" not in observation:
            print("observe 실패:", observation)
            return

        screen = render_screen(observation, all_nodes)
        recent = "\n".join(history[-MAX_HISTORY:]) or "(아직 없음)"
        user = (f"GOAL: {goal}\n\n{screen}\n\n"
                f"RECENT ACTIONS:\n{recent}\n\nNext action?")

        if os.environ.get("AGENT_VERBOSE"):
            print(f"----- 프롬프트(step {step}) -----\n{user}\n{'-' * 30}")

        started = time.time()
        raw = ask_model([{"role": "system", "content": SYSTEM_PROMPT},
                         {"role": "user", "content": user}])
        elapsed = time.time() - started

        if os.environ.get("AGENT_VERBOSE"):
            print(f"모델 원문: {raw}")

        action = parse_action(raw)
        if action is None:
            print(f"[{step}] 모델 응답을 JSON으로 못 읽음: {raw[:200]}")
            return

        detail = action.get("node_id") or action.get("direction") or action.get("text") or ""
        print(f"[{step}] {action['action']} {detail}"
              f"  ({observation['meaningful_node_count']}노드, {elapsed:.1f}s)")
        if action.get("reason"):
            print(f"     이유: {action['reason'][:100]}")

        if action["action"] == "done":
            print(f"{'=' * 60}\n모델이 목표 달성을 선언했습니다. 화면을 확인하세요.")
            return

        outcome = execute(action, observation, dry)
        print(f"     결과: {outcome}")
        history.append(f"step{step}: {action['action']} {detail} → {outcome}")
        time.sleep(1.2)          # 화면 전환이 끝날 때까지 잠깐 기다린다

    print(f"{'=' * 60}\n{max_steps}스텝을 다 썼습니다. 목표 미달성.")


args = [a for a in sys.argv[1:] if not a.startswith("--")]
flags = {a for a in sys.argv[1:] if a.startswith("--")}
if not args:
    print(__doc__)
    sys.exit(0)

steps = 12
for flag in list(flags):
    if flag.startswith("--steps"):
        idx = sys.argv.index(flag)
        if idx + 1 < len(sys.argv):
            steps = int(sys.argv[idx + 1])
            args = [a for a in args if a != sys.argv[idx + 1]]

run(" ".join(args), steps, "--all-nodes" in flags, "--dry" in flags)
