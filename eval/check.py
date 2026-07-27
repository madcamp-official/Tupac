"""회귀 검사 — 프롬프트나 규칙을 고쳤을 때 무엇이 깨졌는지 알려준다.

모델도 폰도 없이 돈다. 여기서 재는 것은 code가 하는 판단이다:

    규칙 바로가기   모델을 부르지 않고 첫 행동을 정하는 부분
    응답 파싱       모델이 뱉은 글을 행동으로 옮기는 부분
    칸 배정         어느 입력창에 어느 값을 넣을지 정하는 부분
    마스킹          클라우드로 내보낼 화면에서 무엇을 가릴지
    화면 렌더링     모델에게 화면을 어떻게 보여줄지

왜 이것부터인가:
    지금까지의 수정은 전부 한 번씩 손으로 돌려본 관찰에 기대고 있었다. 그 방식은
    고칠 때는 되지만 지키지는 못한다 — 프롬프트 한 줄을 고쳤을 때 예전에 고쳐둔
    것이 되살아나도 알 방법이 없다. 케이스마다 "예전에 이렇게 틀렸다"를 적어둔
    이유다(cases.py). 깨지면 그 줄이 그대로 실패 사유로 나온다.

    모델의 판단 자체(어느 node를 골라야 하는가)는 여기서 재지 않는다. 그건
    uitree.py의 데이터셋이 맡는다. 여기는 모델을 부르기 전과 후의 code다.

사용:
  python3 eval/check.py              # 전부 검사
  python3 eval/check.py rules parse  # 일부만
  python3 eval/check.py --list       # 케이스 목록만 보기

실기기 화면을 케이스에 넣고 싶을 때(폰 연결 필요):
  python3 eval/check.py --record login_kakao   # 지금 화면을 fixtures/에 저장
  python3 eval/check.py --shortcuts            # 바로가기 목록을 폰 것으로 갱신
"""
import json
import sys
from pathlib import Path

import agent
import assign
import brains
import cases
import privacy

FIXTURES = Path(__file__).resolve().parent / "fixtures"
SHORTCUTS_FILE = FIXTURES / "shortcuts.txt"

# 규칙 검사에 쓸 최소 화면. 규칙은 화면을 보지 않지만, 규칙이 안 잡혔을 때
# 프롬프트를 만드는 코드가 화면을 읽으므로 형태는 갖춰야 한다.
STUB_SCREEN = cases.screen("com.android.settings", cases.node("node_1", text="설정"))


class ModelCalled(Exception):
    """규칙이 안 잡아서 모델을 부르려 한 순간."""


class RuleOnlyBrain(brains.LocalBrain):
    """모델을 부르는 순간 멈추는 브레인.

    규칙만 따로 떼어내 부르지 않는 이유는, 그러면 규칙을 거치는 순서(설정 화면
    먼저, 그다음 작업)를 여기에 한 번 더 옮겨 적게 되기 때문이다. 옮겨 적은
    순서는 실제 순서와 어긋날 수 있다. 진짜 decide를 부르고 모델 호출만 막으면
    지금 코드가 실제로 하는 일을 그대로 잰다.
    """

    def _ask(self, messages):
        raise ModelCalled


def rule_action(shortcuts, goal):
    """규칙이 정한 행동. 규칙이 비켜서서 모델로 넘어가면 None."""
    try:
        action, _ = RuleOnlyBrain().decide(goal, "", STUB_SCREEN, [], shortcuts, "")
        return action
    except ModelCalled:
        return None


def matches(actual, expected):
    """기대한 키만 본다. reason이나 final처럼 덤으로 붙는 키는 따지지 않는다."""
    if expected is None:
        return actual is None
    if actual is None:
        return False
    return all(actual.get(key) == value for key, value in expected.items())


def screen_name(observation):
    """화면을 가리키는 이름. 실패 줄에 패키지만 찍히면 어느 케이스인지 모른다.

    화면 dict에 이름을 넣지 않고 여기서 찾는 이유는, 손으로 만든 화면이 실기기
    observe 응답과 똑같은 모양이어야 하기 때문이다. --record로 녹화한 것과
    바꿔 끼울 수 있으려면 없는 키가 섞이면 안 된다.
    """
    for key, value in vars(cases).items():
        if value is observation:
            return key
    return observation.get("package_name", "?")


def shortcuts_text():
    if not SHORTCUTS_FILE.is_file():
        sys.exit(f"{SHORTCUTS_FILE}가 없습니다. --shortcuts로 폰에서 받아오세요.")
    return SHORTCUTS_FILE.read_text(encoding="utf-8").strip()


class Report:
    """통과는 세기만 하고, 실패는 왜 있는 케이스인지까지 보여준다."""

    def __init__(self):
        self.passed = 0
        self.failed = []
        self.group = ""

    def start(self, group):
        self.group = group
        print(f"\n{group}")

    def check(self, ok, what, expected, actual, why):
        if ok:
            self.passed += 1
            return
        self.failed.append((self.group, what, expected, actual, why))
        print(f"  ✗ {what}")
        print(f"      기대: {expected}")
        print(f"      실제: {actual}")
        print(f"      이 케이스가 있는 이유: {why}")

    def done(self):
        total = self.passed + len(self.failed)
        print(f"\n{'=' * 60}")
        if not self.failed:
            print(f"{total}개 모두 통과")
            return 0
        print(f"{total}개 중 {len(self.failed)}개 실패")
        for group, what, _, _, why in self.failed:
            print(f"  [{group}] {what} — {why}")
        return 1


# ─────────────────────────────── 검사 ───────────────────────────────

def check_rules(report):
    """규칙 바로가기 — 모델을 부르지 않고 정하는 첫 행동"""
    report.start("규칙 바로가기 (모델을 부르지 않고 정하는 첫 행동)")
    shortcuts = shortcuts_text()
    for goal, expected, why in cases.RULES:
        actual = rule_action(shortcuts, goal)
        report.check(matches(actual, expected), f'"{goal}"', expected, actual, why)


def check_parse(report):
    """응답 파싱 — 모델이 뱉은 글을 행동으로"""
    report.start("응답 파싱 (모델이 뱉은 글 → 행동)")
    for raw, need_text, expected, why in cases.PARSE:
        actual = brains.parse_action(raw, need_text=need_text)
        shown = raw.replace("\n", "\\n")
        label = f'"{shown}"' + ("" if need_text else " (값 입력 구간)")
        report.check(matches(actual, expected), label, expected, actual, why)


def check_fields(report):
    """칸 배정 — 어느 입력창에 어느 값이 들어가는가"""
    report.start("칸 배정 (어느 입력창에 어느 값을)")
    for observation, values, expected, why in cases.FIELDS:
        actual = assign.plan_fields(observation, values, cases.FIELD_HINT)
        report.check(actual == expected, screen_name(observation),
                     expected, actual, why)


def check_submit(report):
    """제출 버튼 — 확실할 때만 고르고, 애매하면 비켜서는가"""
    report.start("제출 버튼 고르기")
    for observation, expected, why in cases.SUBMIT:
        found = assign.submit_button(observation)
        actual = found["id"] if found else None
        report.check(actual == expected, screen_name(observation),
                     expected, actual, why)


def check_redact(report):
    """마스킹 — 클라우드로 내보낼 라벨에서 무엇을 가리는가"""
    report.start("마스킹 (클라우드로 내보낼 라벨)")
    for observation, label, must_have, must_not, why in cases.REDACT:
        actual = privacy.redact(label, observation)
        ok = must_have in actual and must_not not in actual
        report.check(ok, f'{screen_name(observation)}: "{label[:28]}"',
                     f"{must_have!r} 있고 {must_not!r} 없음", repr(actual), why)


def check_patterns(report):
    """식별번호 패턴 — 어느 자리표시자로 바뀌는가"""
    report.start("식별번호 패턴 (순서가 결과를 바꾼다)")
    for text, expected, why in cases.PATTERNS:
        actual = privacy.mask(text)
        report.check(actual == expected, f'"{text}"', expected, actual, why)


def check_blocked(report):
    """화면 단위 차단 — 클라우드에 보낼지 말지"""
    report.start("화면 단위 차단 (클라우드에 보낼지 말지)")
    for observation, expected, why in cases.BLOCKED:
        reason = privacy.blocked_app(observation)
        actual = reason is not None
        report.check(actual == expected, screen_name(observation),
                     "차단" if expected else "통과",
                     reason or "통과", why)


def check_render(report):
    """화면 렌더링 — 모델이 보는 글"""
    report.start("화면 렌더링 (모델이 보는 글)")
    for observation, fragments, why in cases.RENDER:
        drawn = agent.render_screen(observation, all_nodes=False)
        missing = [piece for piece in fragments if piece not in drawn]
        report.check(not missing, screen_name(observation),
                     fragments, drawn, why)


CHECKS = {
    "rules": check_rules,
    "parse": check_parse,
    "fields": check_fields,
    "submit": check_submit,
    "redact": check_redact,
    "patterns": check_patterns,
    "blocked": check_blocked,
    "render": check_render,
}


# ─────────────────────────────── 녹화 ───────────────────────────────

def record_screen(name):
    """지금 폰 화면을 fixtures/에 저장한다. 손으로 만든 화면을 실물로 바꿀 때.

    uitree.py의 dump와 형식을 맞춘다. 같은 파일을 양쪽에서 읽을 수 있어야 한다.
    """
    observation = agent.mcp("device_observe", {"max_nodes": 500})
    if "snapshot_id" not in observation:
        sys.exit(f"observe 실패: {observation}")
    FIXTURES.mkdir(parents=True, exist_ok=True)
    path = FIXTURES / f"{name}.json"
    path.write_text(json.dumps(
        {"name": name, "source": "실기기 녹화",
         "package_name": observation["package_name"],
         "observation": observation}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8")
    print(agent.render_screen(observation, all_nodes=False))
    print(f"\n저장됨 → {path}")
    print("cases.py에서 load(\"%s\")로 불러 쓸 수 있습니다." % name)


def record_shortcuts():
    """바로가기 목록을 폰에서 받아 갱신한다. 앱에 tool을 추가한 뒤에 부른다."""
    text = agent.shortcut_hint()
    if not text:
        sys.exit("폰에서 바로가기 목록을 받지 못했습니다. 앱과 adb forward를 확인하세요.")
    FIXTURES.mkdir(parents=True, exist_ok=True)
    SHORTCUTS_FILE.write_text(text + "\n", encoding="utf-8")
    print(text)
    print(f"\n저장됨 → {SHORTCUTS_FILE}")


def load(name):
    """녹화해둔 화면을 불러온다. cases.py에서 손으로 만든 화면 대신 쓸 수 있다."""
    path = FIXTURES / f"{name}.json"
    if not path.is_file():
        sys.exit(f"{path}가 없습니다. --record {name} 으로 먼저 녹화하세요.")
    return json.loads(path.read_text(encoding="utf-8"))["observation"]


def main(argv):
    if "--list" in argv:
        for name, check in CHECKS.items():
            print(f"{name:10} {(check.__doc__ or '').strip()}")
        return 0
    if "--record" in argv:
        record_screen(argv[argv.index("--record") + 1])
        return 0
    if "--shortcuts" in argv:
        record_shortcuts()
        return 0

    wanted = [word for word in argv if not word.startswith("--")]
    unknown = [word for word in wanted if word not in CHECKS]
    if unknown:
        sys.exit(f"모르는 검사: {', '.join(unknown)}\n가능한 값: {', '.join(CHECKS)}")

    report = Report()
    for name in (wanted or CHECKS):
        CHECKS[name](report)
    return report.done()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
