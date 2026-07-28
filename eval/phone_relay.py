"""Claude Desktop과 폰 앱 사이의 우체통. 판단은 하지 않는다.

폰 앱이 곧 MCP 서버다. 화면을 읽고, 노드를 누르고, 개인정보를 채우는 도구를
전부 앱이 내놓는다. 그러니 Claude는 앱하고만 이야기하면 된다 — 문제는 닿는
길뿐이다.

    Claude Desktop ──stdio──▶ 이 파일 ──HTTP──▶ 폰 앱
                   ◀────────           ◀────────

Claude Desktop은 로컬 프로세스를 띄워 표준 입출력으로 말한다. 폰은 HTTP로
말한다. 그 사이를 줄 단위로 옮기는 것이 전부다. 요청을 읽지도, 고치지도,
판단하지도 않는다 — 그래서 "연산은 폰에서 한다"는 말이 그대로 유지된다.

폰에 닿는 길이 둘이다. 바꾸는 것은 주소뿐이라 이 파일은 어느 쪽인지 모른다.

  adb    맥북에 폰이 붙어 있어야 한다. 개발 중에는 이쪽이 빠르다.
             adb forward tcp:9911 tcp:8765
             PHONE_MCP_URL=http://127.0.0.1:9911/mcp   (기본값)

  릴레이  폰이 릴레이로 나가서 대기한다. 케이블이 필요 없다.
             PHONE_MCP_URL=https://<릴레이 주소>/rpc
             TOKEN 은 폰 토큰이 아니라 릴레이 토큰이다

붙이는 법 (Claude Desktop > 설정 > 개발자 > 구성 파일 편집):

    {
      "mcpServers": {
        "tupac-phone": {
          "command": "python3",
          "args": ["/Users/parkminsu/Tupac/eval/phone_relay.py"],
          "env": {
            "TOKEN": "<토큰>",
            "PHONE_MCP_URL": "http://127.0.0.1:8790/rpc"
          }
        }
      }
    }

폰에서 앱을 켜고 잠금을 풀어둔다. 잠긴 화면에서는 접근성 서비스가 아무것도
못 하므로, 어느 길로 붙든 마찬가지다.
"""
import json
import os
import sys
import urllib.error
import urllib.request

PHONE_URL = os.environ.get("PHONE_MCP_URL", "http://127.0.0.1:9911/mcp")
PROTOCOL = "2025-11-25"
TIMEOUT_SECONDS = 180        # 폼을 채우는 도구는 화면을 여러 번 오가서 오래 걸린다


def log(message):
    """표준 오류로만 적는다. 표준 출력은 JSON-RPC 전용이다."""
    print(f"[relay] {message}", file=sys.stderr, flush=True)


def forward(body, token):
    """요청을 그대로 폰에 넘기고 응답을 그대로 돌려준다."""
    request = urllib.request.Request(
        PHONE_URL,
        data=body,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        # 폰은 알림(id 없는 요청)에 202를 주고 본문을 비운다. 그때는 돌려줄 것이 없다.
        raw = response.read()
        return raw if raw.strip() else None


def hello(request_id):
    """initialize에는 우리가 직접 답한다.

    폰이 꺼져 있거나 릴레이가 안 떠 있으면 이 첫 인사가 실패하고, 그러면
    Claude Desktop은 그 자리에서 연결을 끊는다. 재시도하지 않는다. 대화창에는
    도구가 하나도 없는 상태로 남아 "저는 폰을 조작할 수 없어요"만 나오고, 진짜
    이유는 로그를 뒤져야 나온다 — 오늘 세 번 그렇게 헤맸다.

    첫 인사는 서버 소개일 뿐이라 폰이 없어도 답할 수 있다. 여기서 답해두면
    커넥터가 살아 있고, 도구를 부를 때 무엇이 잘못됐는지가 대화창에 보인다.
    폰이 돌아오면 그때부터 그냥 된다.
    """
    return json.dumps({
        "jsonrpc": "2.0",
        "id": request_id,
        "result": {
            "protocolVersion": PROTOCOL,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "Tupac Phone (relay)", "version": "0.1.0"},
            "instructions": (
                "Android phone control, reached through a relay. The tool list and "
                "everything else comes from the phone itself. If a tool call fails, "
                "the error says what to check — read it out to the person instead of "
                "concluding you cannot control the phone."
            ),
        },
    }, ensure_ascii=False).encode()


def passthrough(body):
    """릴레이가 준 본문이 그대로 내보낼 만한 JSON-RPC 오류면 그것을, 아니면 None."""
    try:
        parsed = json.loads(body)
    except (json.JSONDecodeError, TypeError):
        return None
    if not isinstance(parsed, dict):
        return None
    problem = parsed.get("error")
    if isinstance(problem, dict) and "message" in problem:
        return body
    return None


def describe(error):
    """HTTP 오류를 사람이 고칠 수 있는 말로.

    401을 "폰에 닿지 못했습니다"라고 적었더니 실제로 헤맸다. 토큰 오타 하나였는데
    릴레이를 다시 띄우고 폰을 재시작하느라 시간을 썼다. 무엇이 틀렸는지 말해야 한다.
    """
    if error.code == 401:
        return ("릴레이 토큰이 맞지 않습니다. 릴레이를 띄운 RELAY_TOKEN과 "
                "이 커넥터의 TOKEN이 같은 값인지 확인하세요.")
    if error.code == 404:
        return f"릴레이에 그런 주소가 없습니다: {PHONE_URL}"
    return f"HTTP {error.code} — {error.reason}"


def unreachable(request_id, reason):
    """폰에 못 닿았다고 알린다. 릴레이가 죽으면 Claude 쪽 연결이 통째로 끊긴다."""
    return json.dumps({
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {
            "code": -32001,
            "message": (
                f"폰에 닿지 못했습니다: {reason}\n"
                "  1) adb devices 로 기기가 붙어 있는지\n"
                "  2) adb forward tcp:9911 tcp:8765 를 걸었는지\n"
                "  3) 폰에서 앱이 켜져 있고 잠금이 풀려 있는지 확인하세요."
            ),
        },
    }, ensure_ascii=False).encode()


def main():
    token = os.environ.get("TOKEN")
    if not token:
        log("TOKEN 환경변수가 없습니다. 폰 앱 화면의 페어링 토큰을 넣어주세요.")
        sys.exit(1)
    log(f"폰으로 넘깁니다: {PHONE_URL}")

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        # id는 오류를 돌려줄 때만 쓴다. 못 읽어도 넘기는 것은 그대로 넘긴다.
        try:
            parsed = json.loads(line)
            request_id = parsed.get("id")
            method = parsed.get("method")
        except json.JSONDecodeError:
            request_id, method = None, None

        # 알림(id 없는 요청)에는 답하지 않는다. adb로 갈 때는 폰이 202에 빈 본문을
        # 주지만, 릴레이는 기다리는 자리를 풀려고 반드시 무언가를 돌려준다. 그것을
        # 그대로 내보내면 묻지도 않은 답이 Claude 쪽으로 간다.
        notification = request_id is None

        # 첫 인사는 폰에 묻지 않고 여기서 답한다. 이유는 hello()에 적어뒀다.
        if method == "initialize":
            sys.stdout.buffer.write(hello(request_id) + b"\n")
            sys.stdout.buffer.flush()
            continue

        try:
            answer = forward(line.encode(), token)
        except urllib.error.HTTPError as error:
            # 릴레이가 JSON-RPC 오류를 담아 4xx/5xx로 돌려주는 경우가 있다(폰이
            # 안 붙어 있을 때가 그렇다). 그건 우리가 지어낸 말보다 정확하므로
            # 그대로 넘긴다. 다만 JSON이라고 다 넘기면 안 된다 — 릴레이의 401은
            # {"error": "..."} 꼴이라 JSON-RPC가 아니고, 그대로 보내면 받는 쪽이
            # 읽지 못한다. 형식을 갖춘 것만 통과시킨다.
            answer = passthrough(error.read()) or unreachable(request_id, describe(error))
        except OSError as error:
            # URLError만 잡으면 안 된다. adb forward는 살아 있는데 폰 쪽이 끊긴
            # 경우(기기 offline, 앱 종료) ConnectionResetError가 난다. 둘 다 OSError다.
            answer = unreachable(request_id, str(error))

        if answer is not None and not notification:
            sys.stdout.buffer.write(answer.rstrip(b"\n") + b"\n")
            sys.stdout.buffer.flush()


if __name__ == "__main__":
    main()
