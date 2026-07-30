"""회귀 케이스 — 지금까지 실측으로 고쳐온 것들을 데이터로 박제한다.

여기 있는 케이스는 대부분 "예전에 이렇게 틀렸다"의 기록이다. 커밋 메시지에
남아 있던 실측을 코드가 스스로 지키게 옮겼다고 보면 된다. 각 케이스의 마지막
칸(why)이 그 사연이고, 깨졌을 때 무엇을 되돌린 것인지 알려준다.

모델도 폰도 필요 없다. 여기서 재는 것은 판단을 모델에게 넘기기 전과 후에
code가 하는 일 — 규칙 바로가기, 응답 파싱, 칸 배정, 마스킹, 화면 렌더링이다.
모델의 판단 자체를 재는 것은 uitree.py의 데이터셋이 맡는다.

화면은 손으로 만든 것이다. 실기기에서 본 특징(카카오톡 아이디 칸 라벨, 크롬
주소창의 view_id, 삼성 밝기 슬라이더의 눈금)만 그대로 옮겨 담았다. 실기기
화면으로 바꿔 끼우려면 check.py --record 를 쓴다.
"""

# 금고가 알려주는 필드 설명. 폰의 device_fill_field 스키마에 실려 오는 것과
# 같은 형식이다(SecretVault.FIELDS). assign이 화면 라벨과 필드를 잇는 근거다.
FIELD_HINT = (
    "name(이름, 성명, 받는분, 받는사람, 수령인), "
    "phone(휴대폰 번호, 연락처, 전화번호), "
    "email(이메일 주소, 메일 주소), "
    "birthday(생년월일, 생일), "
    "address(주소, 기본주소, 도로명주소, 자택주소), "
    "address_detail(상세주소, 나머지 주소, 동호수), "
    "postcode(우편번호), "
    "username(아이디, 로그인 ID, 사용자 이름), "
    "password(비밀번호, 패스워드)"
)


def node(node_id, text=None, hint=None, desc=None, view_id=None,
         editable=False, password=False, clickable=False, scrollable=False,
         span=None):
    """observe가 돌려주는 노드 하나. 안 준 것은 폰이 주는 기본값과 같게 둔다."""
    return {
        "id": node_id, "text": text, "content_description": desc, "hint": hint,
        "class_name": None, "view_id": view_id,
        "clickable": clickable, "editable": editable, "password": password,
        "scrollable": scrollable, "enabled": True, "checked": None,
        "range": span, "depth": 1,
        "bounds": {"left": 0, "top": 0, "right": 100, "bottom": 50},
    }


def screen(package, *nodes):
    return {
        "success": True, "snapshot_id": f"fake-{package}",
        "package_name": package, "node_count": len(nodes),
        "meaningful_node_count": len(nodes), "returned_node_count": len(nodes),
        "truncated": False, "nodes": list(nodes),
    }


# ─────────────────────────────── 화면 ───────────────────────────────

# 카카오톡 로그인. 아이디 칸 라벨이 "이메일 또는 전화번호"라 라벨로 맞추면
# email(공통 정보)로 간다. 정작 필요한 건 그 앱의 username이다.
KAKAO_LOGIN = screen(
    "com.kakao.talk",
    node("node_1", text="카카오계정 로그인"),
    node("node_2", hint="이메일 또는 전화번호", editable=True),
    node("node_3", hint="비밀번호", editable=True, password=True),
    node("node_4", text="로그인", clickable=True),
    node("node_5", text="비밀번호 찾기", clickable=True),
    node("node_6", text="회원가입", clickable=True),
)

# 크롬 배송지 폼. 주소창이 editable이라 입력창 목록에 그대로 섞인다. 갈라내는
# 기준은 view_id — 브라우저 자기 위젯만 "<패키지>:id/"를 달고 있다.
CHROME_FORM = screen(
    "com.android.chrome",
    node("node_1", text="주소 및 검색어 입력",
         view_id="com.android.chrome:id/url_bar", editable=True),
    node("node_2", hint="받는사람", editable=True),
    node("node_3", hint="연락처", editable=True),
    node("node_4", hint="우편번호", editable=True),
    node("node_5", text="저장", clickable=True),
)

# 간편 로그인이 함께 있는 화면. 제외 낱말이 없으면 "로그인"이 든 버튼이 셋이
# 되어 후보가 갈린다. 후보가 여럿이면 아무것도 누르지 않는 게 맞다 — 회원가입
# 흐름으로 빠지는 건 되돌리기 어렵다.
KAKAO_LOGIN_EASY = screen(
    "com.kakao.talk",
    node("node_1", text="카카오계정 로그인"),
    node("node_2", hint="이메일 또는 전화번호", editable=True),
    node("node_3", hint="비밀번호", editable=True, password=True),
    node("node_4", text="로그인", clickable=True),
    node("node_5", text="간편 로그인", clickable=True),
    node("node_6", text="다른 계정으로 로그인", clickable=True),
)

# 로그인을 웹으로 넘긴 앱의 비밀번호 단계. 아이디를 앞 화면에서 받았으므로
# 이 화면의 "비밀번호가 아닌 입력창"은 주소창 하나뿐이다. 안 거르면 플래그로
# 가르는 규칙이 그걸 아이디 칸으로 보고 아이디를 주소창에 넣는다.
CHROME_LOGIN_PW = screen(
    "com.android.chrome",
    node("node_1", text="accounts.kakao.com",
         view_id="com.android.chrome:id/url_bar", editable=True),
    node("node_2", hint="비밀번호", editable=True, password=True),
    node("node_3", text="로그인", clickable=True),
)

# 이메일과 주소가 한 화면에 있는 폼. "이메일 주소"를 공백으로 쪼개면 "주소"
# 조각이 생겨서, 아래 "주소" 칸이 email로 잡힌다.
SIGNUP_FORM = screen(
    "com.example.shop",
    node("node_1", hint="이메일 주소", editable=True),
    node("node_2", hint="주소", editable=True),
    node("node_3", hint="상세주소", editable=True),
)

# 값이 이미 들어 있는 배송지 폼. 앱이 지난 주문 값을 남겨두거나 자동완성이
# 채워둔 경우다. 채워진 칸은 text에 라벨이 아니라 그 값이 들어 있어서, text를
# 먼저 보면 "홍길동"이 라벨이 되어 어느 필드에도 안 걸린다.
FILLED_FORM = screen(
    "com.example.shop",
    node("node_1", text="홍길동", hint="받는사람", editable=True),
    node("node_2", hint="연락처", editable=True),
    node("node_3", text="04524", hint="우편번호", editable=True),
)

# 삼성 설정의 밝기 화면. 슬라이더가 라벨도 clickable도 없고, 눈금은 위젯
# 마음대로다(0~267386880 = 255의 2^20배).
BRIGHTNESS = screen(
    "com.android.settings",
    node("node_1", text="밝기"),
    node("node_2", span={"min": 0.0, "max": 267386880.0, "current": 200278016.0}),
    node("node_3", text="191"),
    node("node_4", text="적응형 밝기", clickable=True),
)

# 은행 앱. 라벨이 무해해 보여도 화면 자체를 클라우드에 보내면 안 된다.
BANK = screen(
    "com.kbstar.kbbank",
    node("node_1", text="계좌 조회"),
    node("node_2", text="123-456-7890"),
)

# 카카오톡 대화 목록. 남의 이야기가 그대로 떠 있다.
KAKAO_CHATS = screen(
    "com.kakao.talk",
    node("node_1", text="채팅"),
    node("node_2", text="김철수"),
    node("node_3", text="내일 회의 자료 준비되면 미리 좀 보내줄 수 있을까?"),
    node("node_4", text="엄마"),
    node("node_5", text="주민번호 901010-1234567 이거 맞지?"),
    node("node_6", text="비밀번호 알려줄게 나중에 꼭 지워라"),
    node("node_7", text="박영희"),
)

# 제출로 볼 만한 버튼이 둘인 화면. 둘 다 제외 낱말에 안 걸려서 code가 어느
# 쪽인지 정할 근거가 없다. 이럴 땐 아무것도 누르지 않는다.
TWO_BUTTONS = screen(
    "com.example.shop",
    node("node_1", hint="인증번호", editable=True),
    node("node_2", text="확인", clickable=True),
    node("node_3", text="다음", clickable=True),
)

# 주민번호를 이미 입력한 본인확인 화면. 메신저가 아니라서 길이로 가리는 규칙은
# 안 걸리고, 형식으로 알아보는 마스킹만 걸린다.
VERIFY_FORM = screen(
    "com.example.shop",
    node("node_1", text="본인 확인"),
    node("node_2", text="901010-1234567"),
    node("node_3", text="확인", clickable=True),
)

# 대화 목록 위에 뜬 오류 안내. 항목이 많아 "대화상자라서 봐준다"는 예외가
# 안 걸린다. 안내문과 대화를 말투로 갈라야만 하는 경우다.
KAKAO_WITH_ERROR = screen(
    "com.kakao.talk",
    node("node_1", text="채팅"),
    node("node_2", text="네트워크에 연결할 수 없습니다"),
    node("node_3", text="김철수"),
    node("node_4", text="아까 말한 거 그거 어떻게 됐어 진짜 궁금한데"),
    node("node_5", text="박영희"),
    node("node_6", text="엄마"),
    node("node_7", text="다시 시도", clickable=True),
)

# 로그인 실패 안내. 항목이 적은 대화상자이고, 앱이 건네는 말이다.
LOGIN_ERROR = screen(
    "com.kakao.talk",
    node("node_1", text="비밀번호가 일치하지 않습니다"),
    node("node_2", text="확인", clickable=True),
)

# 인스타그램 로그인 화면. 콘텐츠 앱(대화·사진 설명이 있는 앱) 목록에 들어 있어서
# 긴 라벨이 통째로 가려지는데, 아이디 칸의 hint가 25자짜리 명사구다. 실측으로
# <내용 25자>가 되어 바깥 모델이 아이디 칸을 알아볼 수 없었다.
INSTAGRAM_LOGIN = screen(
    "com.instagram.android",
    node("node_18", text="Instagram", clickable=True),
    node("node_22", text="계정을 잊으셨나요? 도움을 받으세요", clickable=True),
    node("node_26", hint="사용자 이름, 이메일 주소 또는 휴대폰 번호", editable=True),
    node("node_28", text="비밀번호"),
    node("node_32", hint="비밀번호", editable=True, password=True),
    node("node_33", text="로그인", clickable=True),
    node("node_36", text="비밀번호를 잊으셨나요?", clickable=True),
)


# ─────────────────────────────── 케이스 ───────────────────────────────

# 규칙 바로가기: 모델을 부르지 않고 code가 정하는 첫 행동.
# (목표, 기대 행동 또는 None, 왜)
RULES = [
    ("와이파이 켜줘", {"action": "open", "screen": "wifi"},
     "hotspot 설명에 '와이파이'를 넣었더니 점수가 같아져 규칙이 포기했었다"),
    ("블루투스 설정 열어줘", {"action": "open", "screen": "bluetooth"},
     "모델은 '설정'이라는 낱말에 끌려 open settings를 골랐다"),
    ("핫스팟 켜줘", {"action": "open", "screen": "hotspot"}, "실측 통과 목록"),
    ("Play 스토어 열어줘", None,
     "'display' 안에 'play'가 들어 있어 키 부분일치로 보면 걸린다"),
    ("후레쉬 꺼줘", {"action": "task", "task": "torch", "value": "off"},
     "손전등은 갈 수 있는 화면이 없다. 규칙이 못 잡으면 모델이 홈을 헤맨다"),
    ("휴대폰 후레쉬 켜줘", {"action": "task", "task": "torch", "value": "on"}, "위와 같음"),
    ("손전등", None, "켜라는 건지 끄라는 건지 없으면 짐작하지 않는다"),
    ("카메라 열어줘", {"action": "task", "task": "camera"}, "값이 필요 없는 작업"),
    ("7시 알람 맞춰줘", None, "값(시각)이 필요한 작업은 규칙이 고르지 않는다"),
    ("타이머 10분", None, "값(분)이 필요하다"),
    ("강남역 지도", None, "값(장소)이 필요하다"),
    ("음량 올려줘", None,
     "volume은 값이 필요해 규칙 후보가 아니다. 모델이 task volume up을 낸다"),
]

# 모델 응답 파싱. (원문, need_text, 기대 결과 또는 None, 왜)
PARSE = [
    ("tap node_41", True, {"action": "tap", "node_id": "node_41"}, "기본형"),
    ("scroll down", True, {"action": "scroll", "direction": "down"}, "기본형"),
    ("type node_18", True, None,
     "넣을 글자가 없다. 예전엔 'node_18'이라는 글자가 아이디 칸에 그대로 들어갔다"),
    ("type node_18", False, {"action": "type", "node_id": "node_18", "text": ""},
     "값 입력 구간에서는 값을 code가 들고 있으므로 통과시켜야 진행이 막히지 않는다"),
    ("type node_12 홍길동", True,
     {"action": "type", "node_id": "node_12", "text": "홍길동"}, "칸 지목형"),
    ("set node_12 60", True, {"action": "set", "node_id": "node_12", "value": "60"},
     "슬라이더"),
    ("set node_9 0.5", True, {"action": "set", "node_id": "node_9", "value": "0.5"},
     "0~1 눈금을 쓰는 슬라이더도 있다"),
    ("set node_3", True, None,
     "값이 없으면 tap으로 흘러가는데, 슬라이더를 누르면 누른 좌표 값으로 튄다"),
    ("system quick_settings", True,
     {"action": "system", "key": "quick_settings"}, "시스템 버튼"),
    ("need username password", True,
     {"action": "need", "fields": ["username", "password"]}, "핸드오프 신호"),
    ("fill node_16 username", True,
     {"action": "fill", "node_id": "node_16", "field": "username"}, "금고 채우기"),
    ('{"action": "tap", "node_id": "node_7"}', True,
     {"action": "tap", "node_id": "node_7"}, "JSON 응답도 받는다"),
    ("<think>어느 걸 눌러야 하나</think>\ntap node_5", True,
     {"action": "tap", "node_id": "node_5"}, "Qwen 계열은 사고 과정을 먼저 쓴다"),
    ("node_5를 눌러야 합니다", True, {"action": "tap", "node_id": "node_5"},
     "자연어로 답해도 건진다"),
    ("이미 와이파이를 켜기는 했지만 아직 연결되지 않았습니다", True, None,
     "done을 자연어로 추론하면 '이미 ~했지만'을 목표 달성으로 오인해 조기 종료한다"),
]

# 칸 배정. (화면, 가진 값, 기대 순서, 왜)
FIELDS = [
    (KAKAO_LOGIN, {"username": "minsu", "password": "pw1234"},
     ["username", "password"],
     "라벨이 '이메일 또는 전화번호'라 라벨로 맞추면 email로 간다. password 플래그로 가른다"),
    (CHROME_FORM, {"name": "홍길동", "phone": "01012345678", "postcode": "04524"},
     ["name", "phone", "postcode"],
     "주소창이 editable이라 안 거르면 첫 값이 주소창에 들어간다"),
    (SIGNUP_FORM, {"email": "a@b.com", "address": "서울시", "address_detail": "101동"},
     ["email", "address", "address_detail"],
     "'이메일 주소'를 공백으로 쪼개면 아래 '주소' 칸이 email로 잡힌다"),
    (CHROME_LOGIN_PW, {"username": "minsu", "password": "pw1234"}, ["password"],
     "주소창을 안 거르면 '비밀번호 아닌 칸'이 주소창뿐이라 아이디가 거기 들어간다"),
    (FILLED_FORM, {"name": "김철수", "phone": "01012345678", "postcode": "06236"},
     ["name", "phone", "postcode"],
     "이미 채워진 칸은 text가 라벨이 아니라 값이다. text를 먼저 보면 "
     "'홍길동'·'04524'가 라벨로 잡혀 그 칸들이 통째로 빠지고, 남의 주소가 "
     "그대로 남은 채 진행된다"),
]

# 계정이 반쪽일 때. (요청한 필드, 금고에서 받아온 것, 못 쓰는 필드, 왜)
ACCOUNTS = [
    (["username", "password"], {"username": "minsu", "password": "pw1234"}, [],
     "둘 다 있으면 그대로 쓴다"),
    (["username", "password"], {"password": "pw1234"}, ["username"],
     "비밀번호만 넣고 로그인을 누르면 반드시 실패하는데, 그 실패가 앱에 따라 "
     "시도 횟수로 잡혀 계정이 잠긴다. 화면을 건드리기 전에 멈춰야 한다"),
    (["username", "password"], {}, ["username", "password"],
     "그 앱 계정이 아예 등록돼 있지 않은 경우"),
    (["name", "phone", "postcode"], {"name": "홍길동"}, [],
     "공통 정보는 쌍이 아니다. 있는 것만 채우고 제출을 안 하면 된다"),
]

# 제출 버튼. (화면, 기대 node_id 또는 None, 왜)
SUBMIT = [
    (KAKAO_LOGIN, "node_4",
     "'찾기'와 '가입'은 후보에서 빠져 '로그인' 하나만 남아야 한다"),
    (CHROME_FORM, None,
     "'저장'은 제출 낱말이 아니다. 확실하지 않으면 사람이 누른다"),
    (KAKAO_LOGIN_EASY, "node_4",
     "'간편 로그인'·'다른 계정으로 로그인'에도 '로그인'이 들어 있다. "
     "제외 낱말이 없으면 후보가 셋이 되어 아무것도 못 고른다"),
    (TWO_BUTTONS, None,
     "'확인'과 '다음' 둘 다 제출 낱말이다. 후보가 여럿이면 첫 번째를 고르지 "
     "말고 비켜서야 한다 — 엉뚱한 버튼을 누르는 건 되돌리기 어렵다"),
]

# 마스킹. (화면, 라벨, 결과에 있어야 할 것, 없어야 할 것, 왜)
REDACT = [
    (VERIFY_FORM, "901010-1234567", "<주민번호>", "901010",
     "형식이 뚜렷한 식별번호는 값을 자리표시자로 바꾼다"),
    (KAKAO_CHATS, "주민번호 901010-1234567 이거 맞지?", "<내용", "901010",
     "메신저에서는 마스킹 뒤에도 긴 글이면 통째로 가린다. 두 겹이다"),
    (KAKAO_CHATS, "내일 회의 자료 준비되면 미리 좀 보내줄 수 있을까?", "<내용",
     "회의 자료", "메신저의 긴 글은 통째로 가린다"),
    (KAKAO_CHATS, "비밀번호 알려줄게 나중에 꼭 지워라", "<내용", "알려줄게",
     "낱말만 보면 '비밀번호'가 있어 UI로 오인한다. 말투까지 봐야 한다"),
    (LOGIN_ERROR, "비밀번호가 일치하지 않습니다", "일치하지 않습니다", "<내용",
     "안내문을 가리면 모델이 실패 이유를 몰라 막힌다 (항목 적은 대화상자라 봐준다)"),
    (KAKAO_WITH_ERROR, "네트워크에 연결할 수 없습니다", "연결할 수 없습니다", "<내용",
     "항목이 많으면 대화상자 예외가 안 걸린다. 말투(격식체)로 갈라야 남는다"),
    (KAKAO_WITH_ERROR, "아까 말한 거 그거 어떻게 됐어 진짜 궁금한데", "<내용", "궁금한데",
     "같은 화면의 대화는 가려져야 한다. 위 케이스와 짝이다"),
    (KAKAO_CHATS, "김철수", "김철수", "<내용",
     "사람 이름은 남는다. 누를 항목을 가리키려면 필요하다"),
]

# 화면 단위 판정. (화면, blocked_app이 이유를 돌려줘야 하는가, 왜)
BLOCKED = [
    (BANK, True, "은행 앱은 화면에 뜬 것 자체가 잔액·거래내역이다"),
    (KAKAO_LOGIN, False,
     "로그인 화면은 막지 않는다. 클라우드가 '무슨 값이 필요한지'를 봐야 흐름이 이어진다"),
    (BRIGHTNESS, False, "평범한 설정 화면"),
]

# 화면 렌더링. (화면, 그 화면 글에 반드시 있어야 할 조각들, 왜)
RENDER = [
    (BRIGHTNESS, ["node_2 [set 0~100, 지금 75]"],
     "라벨 없는 슬라이더가 보여야 하고, 위젯 눈금이 아니라 퍼센트여야 한다"),
    (KAKAO_LOGIN, ["node_3 [type,비밀번호]"],
     "비밀번호 칸 표시가 있어야 절차서의 '비밀번호가 아닌 칸이 아이디'가 성립한다"),
    (CHROME_FORM, ["node_2 [type]"], "빈 입력창의 라벨은 hint에만 있다"),
]

# 클라우드로 나가는 화면 글. (화면, 남아야 할 조각, 가려져야 할 조각, 왜)
# 여기는 render_screen까지 통과시킨다 — privacy.redact만 따로 부르면 agent.py가
# hint를 hint로 넘기는지(면제 조건을 실제로 전달하는지)는 확인되지 않는다.
REDACTED_RENDER = [
    (INSTAGRAM_LOGIN,
     ["사용자 이름, 이메일 주소 또는 휴대폰 번호"], [],
     "입력창의 hint는 앱이 그 칸에 붙인 안내 문구다. 이게 가려지면 모델이 "
     "아이디 칸을 알아보지 못해 need를 낼 수 없다. 명사구라 UI 낱말·격식체 "
     "어미에 안 걸려서 낱말 목록으로는 못 살린다"),
    (KAKAO_CHATS, [], ["내일 회의 자료 준비되면 미리 좀 보내줄 수 있을까?"],
     "hint 면제를 넣었다고 대화가 새면 안 된다. 면제는 입력창의 hint에만 걸린다"),
]

# 어느 자리표시자로 바뀌는지. 순서가 중요해서 값 하나가 여러 패턴에 걸린다.
# (글, 마스킹 결과, 왜)
PATTERNS = [
    ("2026-07-26", "<생년월일>",
     "계좌번호를 \\d{2,7}로 두면 날짜가 전부 계좌번호로 잡혔다"),
    ("010-1234-5678", "<전화번호>",
     "전화번호가 계좌번호보다 먼저 와야 한다. 뒤에 두면 계좌로 잡힌다"),
    ("123-456-7890", "<계좌번호>", "계좌번호"),
    ("901010-1234567", "<주민번호>", "주민번호"),
    ("1234-5678-9012-3456", "<카드번호>", "카드번호"),
    ("minsu@example.com", "<이메일>", "이메일"),
]
