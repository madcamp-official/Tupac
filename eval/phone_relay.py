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
            request_id = json.loads(line).get("id")
        except json.JSONDecodeError:
            request_id = None

        # 알림(id 없는 요청)에는 답하지 않는다. adb로 갈 때는 폰이 202에 빈 본문을
        # 주지만, 릴레이는 기다리는 자리를 풀려고 반드시 무언가를 돌려준다. 그것을
        # 그대로 내보내면 묻지도 않은 답이 Claude 쪽으로 간다.
        notification = request_id is None

        try:
            answer = forward(line.encode(), token)
        except urllib.error.HTTPError as error:
            answer = unreachable(request_id, f"HTTP {error.code} — {error.reason}")
        except OSError as error:
            # URLError만 잡으면 안 된다. adb forward는 살아 있는데 폰 쪽이 끊긴
            # 경우(기기 offline, 앱 종료) ConnectionResetError가 난다. 둘 다 OSError다.
            answer = unreachable(request_id, str(error))

        if answer is not None and not notification:
            sys.stdout.buffer.write(answer.rstrip(b"\n") + b"\n")
            sys.stdout.buffer.flush()


if __name__ == "__main__":
    main()
