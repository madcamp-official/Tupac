"""폰과 클라이언트 사이에 서서 요청을 건네주는 우체국.

폰에는 남이 접속할 주소가 없다. 통신사 NAT 뒤에 있고, 캠퍼스 Wi-Fi는 기기끼리
통신을 막는다(실측: 같은 공유기에 있는 맥북조차 폰에 못 닿았다). 그래서 방향을
뒤집는다 — 폰이 먼저 여기로 접속해서 할 일을 기다린다.

    클라이언트 ──POST /rpc──▶ [여기] ◀──GET /poll── 폰 (일이 생길 때까지 대기)
              ◀──응답────           ──POST /reply──▶

WebSocket이 아니라 롱폴링인 이유는 폰 쪽 사정이다. 앱에 네트워크 라이브러리가
하나도 없어서, WebSocket을 쓰려면 의존성을 더하거나 프레이밍을 직접 짜야 한다.
롱폴링은 HttpURLConnection만으로 되고, 한 왕복 늦는 것은 지금 흐름에서 티가 안
난다(한 스텝이 이미 4초다).

여기는 아무것도 저장하지 않는다. 지나가는 것은 화면 텍스트와 도구 호출이라,
남겨두면 그게 곧 유출이다.

    RELAY_TOKEN=... python3 relay/server.py
"""
import json
import os
import queue
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("RELAY_PORT", "8790"))
TOKEN = os.environ.get("RELAY_TOKEN", "")

# 폰이 할 일을 기다리는 시간. 이보다 길게 붙들면 중간 프록시가 먼저 끊는다.
POLL_SECONDS = 25.0

# 클라이언트가 응답을 기다리는 시간. 넉넉해야 한다 — device_fill_secrets는
# 기기 안 모델까지 돌면 한 번에 25초쯤 걸린다.
RPC_SECONDS = 180.0

# 폰이 이만큼 조용하면 붙어 있지 않다고 본다. 폴링 주기보다 넉넉해야 한 번
# 놓쳤다고 끊어졌다고 오해하지 않는다.
OFFLINE_AFTER = POLL_SECONDS + 15.0

# 기다리는 동안 폰이 살아 있는지 들여다보는 간격.
WATCH_SECONDS = 2.0

MAX_BODY = 4 * 1024 * 1024

# 폰이 가져갈 요청들. 폰은 한 번에 하나씩 처리하므로 줄을 세운다.
todo = queue.Queue()

# ticket -> Slot. 클라이언트가 어느 응답을 기다리는지 알아보는 표다.
waiting = {}
lock = threading.Lock()

# 폰을 마지막으로 본 시각. 폴링하러 왔거나 답을 주고 갔을 때 갱신한다.
phone_seen = 0.0


class Slot:
    """응답 하나를 기다리는 자리. 폰이 답을 넣으면 기다리던 쪽이 깨어난다."""

    def __init__(self):
        self.arrived = threading.Event()
        self.body = None
        # 폰이 이 일을 가져갔는가. 아직 안 가져갔는데 폰이 조용해지면 기다릴
        # 이유가 없다 — 반면 가져간 뒤라면 오래 걸리는 도구일 수 있으니 기다린다.
        self.taken = False


def touch_phone():
    global phone_seen
    phone_seen = time.time()


def phone_online():
    return time.time() - phone_seen < OFFLINE_AFTER


def offline_error(detail):
    """폰이 없다고 알리는 JSON-RPC 오류. 무엇을 확인해야 하는지 함께 적는다."""
    return {
        "jsonrpc": "2.0",
        "id": None,
        "error": {
            "code": -32003,
            "message": (
                f"{detail}\n"
                "  1) 폰에서 앱이 켜져 있는지\n"
                "  2) 폰이 인터넷에 연결돼 있는지 (Wi-Fi 또는 모바일 데이터)\n"
                "  3) 릴레이 주소와 토큰이 맞는지"
            ),
        },
    }


def log(message):
    print(f"[relay] {message}", file=sys.stderr, flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass                                    # 기본 접근 로그는 끈다. 우리가 따로 남긴다

    # ─────────────────────────────── 공통 ───────────────────────────────

    def authorized(self):
        if self.headers.get("Authorization") == f"Bearer {TOKEN}":
            return True
        self.send_json(401, {"error": "유효한 릴레이 토큰이 필요합니다."})
        return False

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return None
        return self.rfile.read(length)

    def send_json(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def send_empty(self, status):
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ─────────────────────────────── 폰 ───────────────────────────────

    def do_GET(self):
        if self.path.split("?")[0] != "/poll":
            self.send_empty(404)
            return
        if not self.authorized():
            return
        touch_phone()
        try:
            ticket, body = todo.get(timeout=POLL_SECONDS)
        except queue.Empty:
            # 할 일이 없다. 폰은 이걸 받고 곧바로 다시 물어본다.
            touch_phone()
            self.send_empty(204)
            return
        with lock:
            slot = waiting.get(ticket)
            if slot is not None:
                slot.taken = True
        touch_phone()
        log(f"폰에게 넘김 {ticket}")
        self.send_json(200, {"ticket": ticket, "body": json.loads(body)})

    # ────────────────────── 클라이언트 · 폰의 응답 ──────────────────────

    def do_POST(self):
        path = self.path.split("?")[0]
        if path not in ("/rpc", "/reply"):
            self.send_empty(404)
            return
        if not self.authorized():
            return
        body = self.read_body()
        if body is None:
            self.send_json(400, {"error": "요청 본문이 없습니다."})
            return
        if path == "/rpc":
            self.handle_rpc(body)
        else:
            self.handle_reply(body)

    def handle_rpc(self, body):
        """클라이언트의 요청을 큐에 넣고, 폰이 답할 때까지 붙들고 있는다."""
        # 폰이 안 붙어 있으면 큐에 넣지 않는다. 넣어봐야 가져갈 쪽이 없어서
        # 시간제한을 다 채우고 실패하는데, 그동안 부르는 쪽은 아무것도 못 하고
        # 기다린다. 실측: 화면에서 Wi-Fi를 끈 순간 폰이 인터넷을 잃었고, 그 뒤
        # 도구 호출마다 3분씩 멈춰 있다가 실패했다.
        if not phone_online():
            log("폰이 붙어 있지 않아 곧바로 알립니다")
            self.send_json(503, offline_error(
                "폰이 릴레이에 붙어 있지 않습니다. " + (
                    "이 릴레이가 켜진 뒤로 한 번도 붙은 적이 없습니다."
                    if phone_seen == 0.0
                    else f"마지막으로 본 지 {int(time.time() - phone_seen)}초 지났습니다."
                )))
            return

        ticket = uuid.uuid4().hex[:12]
        slot = Slot()
        with lock:
            waiting[ticket] = slot
        todo.put((ticket, body))

        started = time.time()
        if not self.wait_for(slot, ticket):
            with lock:
                waiting.pop(ticket, None)
            return

        with lock:
            waiting.pop(ticket, None)
        log(f"응답 전달 {ticket} ({time.time() - started:.1f}초)")
        raw = slot.body
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def wait_for(self, slot, ticket):
        """답을 기다린다. 받았으면 True, 포기했으면 알리고 False.

        중간에 폰이 조용해지면 시간제한을 다 채우지 않고 끊는다. 다만 폰이 이미
        일을 가져간 뒤라면 기다린다 — 처리하는 동안에는 폴링을 쉬기 때문에
        조용한 것이 정상이고, device_fill_secrets처럼 오래 걸리는 것도 있다.
        """
        deadline = time.time() + RPC_SECONDS
        while time.time() < deadline:
            if slot.arrived.wait(WATCH_SECONDS):
                return True
            if not slot.taken and not phone_online():
                log(f"기다리는 사이 폰이 끊겼습니다 {ticket}")
                self.send_json(503, offline_error(
                    "요청을 넣은 뒤 폰이 릴레이에서 끊겼습니다. 화면에서 Wi-Fi나 "
                    "모바일 데이터를 끄면 이렇게 됩니다."))
                return False
        log(f"응답 없음 {ticket} ({RPC_SECONDS:.0f}초)")
        self.send_json(504, {
            "jsonrpc": "2.0",
            "id": None,
            "error": {
                "code": -32002,
                "message": (
                    "폰이 가져갔지만 시간 안에 답하지 않았습니다. 앱이 살아 있는지, "
                    "잠금이 풀려 있는지 확인하세요."
                ),
            },
        })
        return False

    def handle_reply(self, body):
        """폰이 돌려준 답을 기다리던 자리에 놓는다."""
        touch_phone()
        try:
            parsed = json.loads(body)
            ticket = parsed["ticket"]
            answer = json.dumps(parsed["body"], ensure_ascii=False).encode()
        except (json.JSONDecodeError, KeyError, TypeError) as error:
            self.send_json(400, {"error": f"ticket과 body가 필요합니다: {error}"})
            return

        with lock:
            slot = waiting.get(ticket)
        if slot is None:
            # 클라이언트가 이미 포기했다. 폰 탓이 아니므로 조용히 받아만 준다.
            log(f"기다리는 곳이 없는 응답 {ticket}")
            self.send_empty(204)
            return
        slot.body = answer
        slot.arrived.set()
        self.send_empty(204)


def main():
    if not TOKEN:
        sys.exit("RELAY_TOKEN 환경변수가 필요합니다. 폰 토큰과 다른 값을 쓰세요.")
    # ThreadingHTTPServer여야 한다. 기본 HTTPServer는 한 번에 하나만 처리해서,
    # 폰이 /poll로 25초를 붙들고 있으면 그동안 /rpc가 아예 안 들어온다.
    log(f"http://0.0.0.0:{PORT} 에서 기다립니다 (poll {POLL_SECONDS:.0f}s, rpc {RPC_SECONDS:.0f}s)")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
