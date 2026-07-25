"""UI-tree grounding 평가용 덤프 수집/라벨링 도구.

목적: observe 페이로드를 화면별로 박제해서, 모델을 바꿔가며 같은 데이터로
      "어느 node_id를 골라야 하는가" 정확도를 재기 위한 데이터셋을 만든다.

준비:
  export TOKEN=$(adb shell run-as com.example.mobileguiagent \
      cat /data/data/com.example.mobileguiagent/shared_prefs/pocket_mcp_auth.xml \
      | sed -n 's/.*name="bearer_token">\\([^<]*\\)<.*/\\1/p')
  adb forward tcp:9911 tcp:8765

사용:
  python3 eval/uitree.py observe                    # 현재 화면 노드 목록
  python3 eval/uitree.py dump settings_home         # 현재 화면을 덤프로 저장
  python3 eval/uitree.py label settings_home node_41,node_42 "와이파이 켜기"
      (정답 노드는 콤마로 여러 개 가능 — 같은 항목의 제목/요약처럼
       어느 쪽을 눌러도 같은 결과인 노드들을 모두 정답으로 인정)
  python3 eval/uitree.py list                       # 데이터셋 현황
  python3 eval/uitree.py tap node_25                # 화면 이동용
  python3 eval/uitree.py scroll down
  python3 eval/uitree.py type 안녕하세요
"""
import json
import os
import sys
import urllib.request
from pathlib import Path

PORT = int(os.environ.get("POCKETMCP_PORT", "9911"))
URL = f"http://127.0.0.1:{PORT}/mcp"
EVAL_DIR = Path(__file__).resolve().parent
DUMP_DIR = EVAL_DIR / "dumps"
DATASET = EVAL_DIR / "dataset.json"


def call(name, arguments):
    token = os.environ.get("TOKEN")
    if not token:
        sys.exit("TOKEN 환경변수가 없습니다. export TOKEN=... 먼저 실행하세요.")
    body = json.dumps(
        {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
         "params": {"name": name, "arguments": arguments}}
    ).encode()
    req = urllib.request.Request(
        URL, data=body,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    try:
        outer = json.load(urllib.request.urlopen(req, timeout=30))
    except urllib.error.URLError as error:
        sys.exit(f"서버 연결 실패({URL}): {error}\n"
                 f"  adb forward --list 확인 → 없으면 adb forward tcp:{PORT} tcp:8765")
    return json.loads(outer["result"]["content"][0]["text"])


def fetch_observe():
    o = call("device_observe", {"max_nodes": 500})
    if "snapshot_id" not in o:
        sys.exit(f"observe 실패: {json.dumps(o, ensure_ascii=False)}")
    return o


def prompt_cost(observation):
    """이 화면을 프롬프트에 실었을 때의 대략적 크기.

    보급형 폰(A시리즈)은 prefill이 느려 프롬프트 길이가 응답 지연을 좌우한다.
    한국어+JSON 혼합은 대략 2.5자당 1토큰으로 잡는다(어림값).
    """
    chars = sum(
        len(n.get("text") or "") + len(n.get("content_description") or "") + 60
        for n in observation["nodes"]
    )
    return chars, chars // 250 * 100  # (문자수, 100토큰 단위 반올림 추정)


def print_nodes(observation):
    chars, approx = prompt_cost(observation)
    print(f"[{observation['package_name']}]  전체={observation['node_count']}  "
          f"의미있음={observation['meaningful_node_count']}  "
          f"snapshot={observation['snapshot_id'][:12]}")
    print(f"  프롬프트 추정: 약 {chars:,}자 / 약 {approx:,}토큰")
    for n in observation["nodes"]:
        label = (n.get("text") or n.get("content_description") or "").replace("\n", " ")
        flags = "".join(c for c, v in
                        [("C", n["clickable"]), ("E", n["editable"]), ("S", n["scrollable"])] if v)
        print(f"  {n['id']:>8}  {flags:<3}  {label[:55]}")


def load_dataset():
    if not DATASET.is_file():
        return {"cases": []}
    data = json.loads(DATASET.read_text(encoding="utf-8"))
    # 초기 버전은 정답이 단수(answer_node_id)였다. 복수 형식으로 통일한다.
    for case in data.get("cases", []):
        if "answer_node_ids" not in case:
            case["answer_node_ids"] = [case.pop("answer_node_id")]
            case["answer_labels"] = [case.pop("answer_label", "")]
    return data


def save_dataset(data):
    DATASET.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def cmd_observe():
    print_nodes(fetch_observe())


def cmd_dump(name):
    DUMP_DIR.mkdir(parents=True, exist_ok=True)
    observation = fetch_observe()
    chars, approx = prompt_cost(observation)
    path = DUMP_DIR / f"{name}.json"
    path.write_text(
        json.dumps(
            {
                "name": name,
                "package_name": observation["package_name"],
                "node_count": observation["node_count"],
                "meaningful_node_count": observation["meaningful_node_count"],
                "prompt_chars": chars,
                "prompt_tokens_approx": approx,
                "observation": observation,
            },
            ensure_ascii=False, indent=2,
        ) + "\n",
        encoding="utf-8",
    )
    print_nodes(observation)
    print(f"\n저장됨 → {path.relative_to(EVAL_DIR.parent)}")
    print(f"다음: python3 eval/uitree.py label {name} <정답node_id> \"<태스크>\"")


def cmd_label(name, node_ids, task):
    """정답 노드를 등록한다.

    node_ids는 콤마 구분으로 여러 개를 받는다. click_node가 라벨 없는 노드에서
    부모의 clickable 조상으로 올라가기 때문에, 같은 항목의 제목/요약 노드는
    어느 쪽을 골라도 실제로는 같은 곳이 눌린다. 그런 경우를 오답으로 세지
    않으려면 정답을 복수로 둬야 한다.
    """
    path = DUMP_DIR / f"{name}.json"
    if not path.is_file():
        sys.exit(f"덤프가 없습니다: {path}. 먼저 dump {name} 하세요.")
    dump = json.loads(path.read_text(encoding="utf-8"))
    ids = [i.strip() for i in node_ids.split(",") if i.strip()]
    labels = []
    for node_id in ids:
        node = next(
            (n for n in dump["observation"]["nodes"] if n["id"] == node_id), None
        )
        if node is None:
            sys.exit(f"{node_id}가 덤프 {name}에 없습니다.")
        labels.append(node.get("text") or node.get("content_description") or "")

    data = load_dataset()
    data["cases"] = [c for c in data["cases"]
                     if not (c["dump"] == name and c["task"] == task)]
    data["cases"].append({
        "dump": name,
        "package_name": dump["package_name"],
        "task": task,
        "answer_node_ids": ids,
        "answer_labels": labels,
    })
    data["cases"].sort(key=lambda c: (c["dump"], c["task"]))
    save_dataset(data)
    shown = ", ".join(f"{i}({l})" for i, l in zip(ids, labels))
    print(f"라벨 추가: [{name}] \"{task}\" → {shown}")
    print(f"현재 케이스 {len(data['cases'])}개 → {DATASET.relative_to(EVAL_DIR.parent)}")


def cmd_list():
    data = load_dataset()
    if not data["cases"]:
        print("아직 케이스가 없습니다. dump → label 순서로 추가하세요.")
        return
    print(f"케이스 {len(data['cases'])}개")
    for c in data["cases"]:
        dump_path = DUMP_DIR / f"{c['dump']}.json"
        size = ""
        if dump_path.is_file():
            d = json.loads(dump_path.read_text(encoding="utf-8"))
            size = f"노드{d['meaningful_node_count']:>3} ~{d['prompt_tokens_approx']:>5}tok"
        answers = ",".join(c["answer_node_ids"])
        print(f"  {c['dump']:<20} {size}  {answers:<20}  {c['task']}")


def cmd_tap(node_id):
    observation = fetch_observe()
    node = next((n for n in observation["nodes"] if n["id"] == node_id), None)
    if node is None:
        sys.exit(f"{node_id}가 현재 화면에 없습니다. observe 다시 하세요.")
    if node["scrollable"] and not node["clickable"]:
        print(f"주의: {node_id}는 scrollable 컨테이너입니다. scroll을 쓰세요.")
    res = call("device_click_node",
               {"snapshot_id": observation["snapshot_id"], "node_id": node_id})
    print("tap:", json.dumps(res, ensure_ascii=False))


cmd = sys.argv[1] if len(sys.argv) > 1 else "observe"
if cmd == "observe":
    cmd_observe()
elif cmd == "dump":
    cmd_dump(sys.argv[2])
elif cmd == "label":
    cmd_label(sys.argv[2], sys.argv[3], " ".join(sys.argv[4:]))
elif cmd == "list":
    cmd_list()
elif cmd == "tap":
    cmd_tap(sys.argv[2])
elif cmd == "scroll":
    print("scroll:", json.dumps(call("device_scroll", {"direction": sys.argv[2]}),
                                ensure_ascii=False))
elif cmd == "type":
    print("type:", json.dumps(call("device_type_text", {"text": " ".join(sys.argv[2:])}),
                              ensure_ascii=False))
else:
    print(__doc__)
