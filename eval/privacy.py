"""개인정보가 기기 밖으로 나가지 않게 막는다.

이 파일이 지키려는 두 가지:

1. 민감한 화면은 관찰 자체가 나가면 안 된다.
   type만 로컬로 돌리는 걸로는 부족하다. device_observe가 화면 텍스트를 통째로
   주기 때문에, 이미 입력된 주민번호·계좌번호가 노드 텍스트에 그대로 들어있다.
   그래서 라우팅 단위는 행동이 아니라 화면이다.

2. 평범한 화면에도 개인정보는 섞인다.
   실측: Wi-Fi 설정 화면의 SSID가 사용자 실명이었고, 홈 화면 날씨 위젯에 사는
   동네가 떠 있었다. 민감 화면이 아니어도 눈에 띄는 값은 가리고 보낸다.

스크린샷을 클라우드로 보내지 않는 것도 같은 이유다. 픽셀은 이렇게 가릴 수 없다.
"""
import re

# 값 자체를 가린다. 자리표시자에 번호를 붙여 같은 값이 여러 번 나와도 모델이
# "같은 것"임을 알 수 있게 한다(예: 확인용으로 두 번 적힌 전화번호).
PATTERNS = (
    ("주민번호", re.compile(r"\b\d{6}\s*[-–]\s*[1-4]\d{6}\b")),
    ("카드번호", re.compile(r"\b(?:\d{4}[ -]?){3}\d{4}\b")),
    ("전화번호", re.compile(r"\b01[016-9][ -]?\d{3,4}[ -]?\d{4}\b")),
    # 마지막 묶음을 4자리 이상으로 묶어야 날짜를 안 잡는다. \d{2,7}로 두면
    # "2026-07-26" 같은 날짜가 전부 계좌번호로 잡혔다.
    ("계좌번호", re.compile(r"\b\d{2,6}-\d{2,6}-\d{4,7}\b")),
    ("이메일", re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.]+\b")),
    ("생년월일", re.compile(r"\b(19|20)\d{2}[.\-/](0?[1-9]|1[0-2])[.\-/](0?[1-9]|[12]\d|3[01])\b")),
)

# 값 하나만 보여도 그 화면 전체를 민감하다고 볼 것들.
DECISIVE = ("주민번호", "카드번호", "계좌번호")

# 이 낱말이 라벨에 있으면 그 화면은 민감하다고 본다. 값이 아직 안 채워졌어도
# (빈 입력 폼) 곧 채워질 화면이므로 미리 로컬로 돌린다.
SENSITIVE_LABELS = (
    "비밀번호", "패스워드", "password", "pin", "인증번호", "otp",
    "주민등록번호", "주민번호", "카드번호", "cvc", "cvv", "유효기간",
    "계좌번호", "송금", "이체", "결제", "출금", "잔액",
    "여권", "운전면허", "본인확인", "본인인증", "생체", "지문",
)

# 앱 자체가 민감한 경우. 화면 라벨이 무해해 보여도 통째로 로컬에서 다룬다.
SENSITIVE_PACKAGES = (
    "bank", "shinhan", "kookmin", "kbstar", "wooribank", "hanabank", "nonghyup",
    "ibk", "kakaobank", "kbank", "tossbank", "toss", "payco", "kakaopay",
    "samsungpay", "cert", "pass", "npki", "yessign", "keychain",
)


def label_of(node):
    return (node.get("text") or node.get("content_description")
            or node.get("hint") or "").strip()


def mask(text):
    """알아볼 수 있는 개인정보를 자리표시자로 바꾼다.

    구조는 남긴다. 모델은 "여기에 전화번호가 있다"는 것만 알면 되고, 값이
    무엇인지는 알 필요가 없다. 값을 지우는 대신 통째로 삭제하면 모델이 화면을
    잘못 읽으므로 자리표시자를 남긴다.
    """
    for name, pattern in PATTERNS:
        text = pattern.sub(f"<{name}>", text)
    return text


# 화면에 남의 이야기가 그대로 떠 있는 앱들. 대화 내용·메일 본문·사진 설명은
# 에이전트가 길을 찾는 데 필요 없다. 어느 항목을 누를지만 알면 된다.
CONTENT_PACKAGES = (
    "kakao.talk", "line", "telegram", "whatsapp", "messenger", "facebook",
    "instagram", "discord", "slack", "mms", "messaging", "android.email",
    "gm", "mail", "gallery", "photos", "band", "everytime",
)

# 이 길이를 넘는 글은 항목 이름이 아니라 내용으로 본다. 설정 항목이나 사람
# 이름은 짧고("연결", "김철수"), 대화 미리보기나 메일 제목은 길다.
CONTENT_LENGTH = 14

# 항목이 이보다 적으면 대화상자나 로딩 화면으로 본다. 대화 목록은 항목이 많다.
# 실측: 로그인 실패 안내가 "안내문 + 확인 버튼" 두 개짜리 화면으로 떴다.
DIALOG_NODES = 6

# 앱이 사용자에게 건네는 말. 대화 내용이라면 좀처럼 쓰지 않는 낱말만 골랐다.
# "확인", "다시" 같은 흔한 말은 일부러 뺐다 — 대화에도 자주 나온다.
UI_MARKERS = (
    "오류", "실패", "일치하지", "올바르지", "유효하지", "잘못된",
    "인증", "로그인", "비밀번호", "계정", "권한", "네트워크",
    "업데이트", "다시 시도", "다시시도", "사용할 수 없", "입력해", "입력하세요",
)


# 앱이 사용자에게 말할 때 쓰는 격식체 어미. 대화에서는 좀처럼 이렇게 끝나지
# 않는다("...할래?", "...하자", "...야").
FORMAL_ENDINGS = (
    "습니다", "합니다", "됩니다", "입니다", "없습니다",
    "하세요", "주세요", "세요", "십시오", "하십시오", "하시겠습니까",
)


def is_ui_text(label):
    """앱이 건네는 안내·오류 문구로 보이는지.

    대화 내용과 안내문을 가르는 확실한 표시가 접근성 트리에는 없다. 그래서
    낱말로 가늠하되, 틀렸을 때 손해가 적은 쪽으로 기운다. 안내문을 가려버리면
    모델이 왜 실패했는지 몰라 막힐 뿐이지만, 대화를 안 가리면 그대로 유출이다.

    낱말만으로는 샌다. "비밀번호 알려줄게 나중에 지워라" 같은 대화가 그대로
    나갔다. 그래서 말투까지 함께 본다 — 앱은 격식체로 말하고("...습니다",
    "...하세요"), 대화는 그렇지 않다. 둘 다 맞아야 UI로 인정한다.
    """
    if not any(marker in label for marker in UI_MARKERS):
        return False
    stripped = label.rstrip(" .!?~…")
    return stripped.endswith(FORMAL_ENDINGS)


def redact(label, observation):
    """클라우드로 내보낼 라벨을 다듬는다.

    두 단계다. 먼저 형식이 뚜렷한 식별번호를 자리표시자로 바꾸고, 그다음
    메신저·메일 같은 앱에서는 긴 글을 내용으로 보고 통째로 가린다.

    다만 무턱대고 길이로만 가르면 안내문까지 가려진다(실측: "비밀번호가 일치하지
    않습니다"가 통째로 사라져, 모델이 로그인 실패 이유를 볼 수 없었다). 그래서
    두 가지를 예외로 둔다. 항목이 적은 화면(대화상자·로딩)과, 앱이 건네는
    말로 보이는 문구다.

    사람 이름은 남는다. 짧아서 걸러지지 않고, 누를 항목을 가리키려면 필요하다.
    이름도 개인정보라는 점에서 이 방식은 완전하지 않다.
    """
    label = mask(label)
    if len(label) <= CONTENT_LENGTH:
        return label

    package = (observation.get("package_name") or "").lower()
    if not any(marker in package for marker in CONTENT_PACKAGES):
        return label

    nodes = observation.get("meaningful_node_count") or len(observation.get("nodes", []))
    if nodes < DIALOG_NODES or is_ui_text(label):
        return label
    return f"<내용 {len(label)}자>"


def sensitive_reason(observation):
    """민감 화면이면 이유를, 아니면 None.

    이유를 문자열로 돌려주는 건 로그에 남기기 위해서다. 어느 신호 때문에 로컬로
    돌렸는지 보이지 않으면 오판을 고칠 수가 없다.
    """
    package = (observation.get("package_name") or "").lower()
    for marker in SENSITIVE_PACKAGES:
        if marker in package:
            return f"민감한 앱({package})"

    nodes = observation.get("nodes", [])
    if any(node.get("password") for node in nodes):
        return "비밀번호 입력창이 있음"

    for node in nodes:
        lowered = label_of(node).lower()
        for word in SENSITIVE_LABELS:
            if word in lowered:
                return f"라벨에 '{word}'"

    for node in nodes:
        # 패턴을 직접 돌리지 않고 mask()를 거친다. PATTERNS는 순서가 중요한데
        # (전화번호가 계좌번호보다 먼저 와야 010-1234-5678이 계좌로 안 잡힌다)
        # 여기서 따로 돌리면 그 순서를 놓친다. 실측으로 연락처 화면이 통째로
        # 민감 화면이 되어버렸다.
        masked = mask(label_of(node))
        for name in DECISIVE:
            # 이메일이나 전화번호 하나 떴다고 은행 화면 취급하면 과하다. 그 자체로
            # 결정적인 것만 화면 전체를 민감하다고 본다. 나머지는 mask()가 가린다.
            if f"<{name}>" in masked:
                return f"화면에 {name}가 보임"
    return None
