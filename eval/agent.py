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
  --steps N      최대 스텝 수 (기본 12)
  --all-nodes    라벨 없는 노드까지 모델에게 보여준다 (기본은 라벨 있는 것만)
  --dry          모델 판단만 보고 실제로 폰을 조작하지는 않는다
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

import brains

MCP_PORT = int(os.environ.get("POCKETMCP_PORT", "9911"))
MCP_URL = f"http://127.0.0.1:{MCP_PORT}/mcp"

MAX_PROMPT_NODES = 45


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


def run(goal, brain, max_steps, all_nodes, dry):
    print(f"목표: {goal}  (brain: {brain.name})\n{'=' * 60}")
    history = []
    for step in range(1, max_steps + 1):
        observation = mcp("device_observe", {"max_nodes": 500})
        if "snapshot_id" not in observation:
            print("observe 실패:", observation)
            return

        screen = render_screen(observation, all_nodes)
        started = time.time()
        try:
            action, raw = brain.decide(goal, screen, observation, history)
        except brains.BrainError as error:
            sys.exit(f"모델 호출 실패: {error}\n  {brain.hint()}")
        elapsed = time.time() - started

        if action is None:
            print(f"[{step}] 응답을 해석하지 못해 이 스텝을 건너뜁니다: {raw[:120]}")
            history.append(f"step{step}: 형식 오류 → 건너뜀")
            continue

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

    # done을 못 뽑았다고 실패는 아니다. 목표를 이미 이뤘는데도 완료 선언만
    # 못 하는 경우가 잦아, 마지막 화면을 보여주고 사람이 판단하게 한다.
    final = mcp("device_observe", {"max_nodes": 500})
    labels = [(n.get("text") or n.get("content_description") or "").strip()
              for n in final.get("nodes", [])]
    preview = " / ".join(l for l in labels if l)[:150]
    print(f"{'=' * 60}\n{max_steps}스텝을 모두 사용했습니다 (모델이 done을 선언하지 않음).")
    print(f"마지막 화면: [{final.get('package_name', '?')}] {preview}")
    print("→ 목표가 달성됐는지 폰 화면으로 확인하세요.")


def parse_argv(argv):
    """--flag / --flag VALUE 와 목표 문장을 갈라낸다."""
    options = {"brain": "local", "steps": 12, "all_nodes": False, "dry": False}
    words = []
    index = 0
    while index < len(argv):
        token = argv[index]
        if token == "--brain" and index + 1 < len(argv):
            options["brain"] = argv[index + 1]
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
    except brains.BrainError as error:
        sys.exit(str(error))
    run(parsed["goal"], selected, parsed["steps"], parsed["all_nodes"], parsed["dry"])
