"""에이전트 전체를 MCP 도구 하나로 감싼다.

폰 앱도 MCP 서버지만 그건 "화면을 읽고 노드를 누르는" 낱개 도구를 내놓는다.
그걸 Claude Desktop 같은 클라이언트에 그대로 붙이면 판단이 통째로 그쪽으로
넘어간다 — 화면 텍스트가 다 나가고, 개인정보 라우팅도 계획 이탈 감시도 사라진다.
이 파일이 있는 이유가 그거다.

    Claude Desktop  ──"카카오톡 로그인 해줘"──▶  이 서버
                                                  └▶ Gemini + code + 기기 안 모델 ⇄ 폰
                    ◀────"완료: 4스텝, ..."──────┘

밖에서 오는 것은 목표 문장 하나, 나가는 것은 결과 요약 한 덩어리다. 화면도
비밀번호도 이 경계를 넘지 않는다. 어느 두뇌에게 무엇을 보여줄지는 지금까지대로
agent.py가 정한다.

붙이는 법 (Claude Desktop > 설정 > 개발자 > 구성 파일 편집):

    {
      "mcpServers": {
        "tupac": {
          "command": "python3",
          "args": ["/Users/parkminsu/Tupac/eval/mcp_server.py"],
          "env": {
            "TOKEN": "<폰 앱 화면의 페어링 토큰>",
            "GEMINI_API_KEY": "<키>",
            "GEMINI_MODEL": "gemini-3.5-flash"
          }
        }
      }
    }

미리 해둘 것: adb forward tcp:9911 tcp:8765, 그리고 값 입력을 쓸 거면
llama-server(8080)도 띄워둔다.

stdio로 말한다. 표준 출력은 JSON-RPC 전용이라, agent.py가 찍는 진행 로그는
가로채서 응답 본문에 실어 보낸다. 그대로 두면 프로토콜이 깨진다.
"""
import contextlib
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import agent      # noqa: E402
import brains     # noqa: E402

PROTOCOL = "2025-11-25"
DEFAULT_STEPS = 12
MAX_STEPS = 30

TOOLS = [
    {
        "name": "run_on_phone",
        "description": (
            "Runs a goal on the connected Android phone and returns what happened. "
            "Give the goal in plain language, the way a person would say it out loud "
            "(\"turn on the flashlight\", \"log in to KakaoTalk\", \"fill this delivery "
            "form with my info\"). This tool drives the phone by itself: it decides the "
            "steps, handles personal data on-device, and stops when a screen needs a "
            "human (terms of service, placing a call, sending a message). "
            "Screen contents and stored personal values never leave the phone; only the "
            "summary below comes back."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "goal": {
                    "type": "string",
                    "description": "무엇을 해달라는지 한 문장으로. 예: \"블루투스 꺼줘\"",
                },
                "max_steps": {
                    "type": "integer",
                    "description": f"몇 스텝까지 시도할지 (기본 {DEFAULT_STEPS}, 최대 {MAX_STEPS})",
                },
            },
            "required": ["goal"],
            "additionalProperties": False,
        },
    },
    {
        "name": "phone_status",
        "description": (
            "Reports whether the phone is reachable and which app is on screen. "
            "Use it when run_on_phone fails to tell a disconnected phone from a "
            "goal the agent could not carry out."
        ),
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
]


def run_on_phone(arguments):
    """목표 하나를 끝까지 돌리고, 결론 한 줄과 진행 로그를 돌려준다."""
    goal = (arguments.get("goal") or "").strip()
    if not goal:
        return "goal이 비어 있습니다. 무엇을 해달라는지 한 문장으로 적어주세요.", True

    steps = arguments.get("max_steps") or DEFAULT_STEPS
    steps = max(1, min(int(steps), MAX_STEPS))

    cloud = brains.make_brain("gemini") if os.environ.get("GEMINI_API_KEY") else brains.make_brain("local")
    fallback = brains.make_brain("local")

    # agent.py는 폰이 끊기면 sys.exit로 끝낸다. 명령줄에서는 그게 맞지만 여기서는
    # 서버가 통째로 죽는다. 잡아서 이유만 돌려주고 다음 명령을 계속 받는다.
    log = io.StringIO()
    try:
        with contextlib.redirect_stdout(log):
            verdict = agent.run(goal, cloud, fallback, steps,
                                all_nodes=False, dry=False)
    except SystemExit as stop:
        return f"실행하지 못했습니다.\n{stop}", True
    except Exception as error:                      # noqa: BLE001
        return f"예기치 못한 오류: {type(error).__name__}: {error}\n\n{log.getvalue()}", True

    body = log.getvalue().strip()
    return f"{verdict or '결과를 알 수 없습니다'}\n\n--- 진행 ---\n{body}", False


def phone_status(_arguments):
    try:
        observed = agent.mcp("device_observe", {"max_nodes": 40})
    except SystemExit as stop:
        return f"폰에 닿지 않습니다.\n{stop}", True
    if "snapshot_id" not in observed:
        return f"화면을 읽지 못했습니다: {observed}", True
    return (f"연결됨. 지금 화면: [{observed.get('package_name')}] "
            f"{agent.describe(observed)}"), False


HANDLERS = {"run_on_phone": run_on_phone, "phone_status": phone_status}


def handle(request):
    """MCP 요청 하나를 처리한다. 알림(id 없음)이면 None."""
    method = request.get("method")
    request_id = request.get("id")
    if request_id is None:
        return None                                  # 알림에는 답하지 않는다

    if method == "initialize":
        return ok(request_id, {
            "protocolVersion": PROTOCOL,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "Tupac Phone Agent", "version": "0.1.0"},
            "instructions": (
                "Send the user's request as one sentence to run_on_phone. Do not try to "
                "break it into UI steps — this server does that itself, on the phone."
            ),
        })
    if method == "ping":
        return ok(request_id, {})
    if method == "tools/list":
        return ok(request_id, {"tools": TOOLS})
    if method == "tools/call":
        params = request.get("params") or {}
        handler = HANDLERS.get(params.get("name"))
        if handler is None:
            return fail(request_id, -32602, f"모르는 도구입니다: {params.get('name')}")
        text, is_error = handler(params.get("arguments") or {})
        return ok(request_id, {"content": [{"type": "text", "text": text}],
                               "isError": is_error})
    return fail(request_id, -32601, f"지원하지 않는 메서드입니다: {method}")


def ok(request_id, result):
    return {"jsonrpc": "2.0", "id": request_id, "result": result}


def fail(request_id, code, message):
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError as error:
            print(json.dumps(fail(None, -32700, f"JSON을 읽지 못했습니다: {error}")), flush=True)
            continue
        response = handle(request)
        if response is not None:
            print(json.dumps(response, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
