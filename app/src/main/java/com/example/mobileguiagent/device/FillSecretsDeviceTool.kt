package com.example.mobileguiagent.device

import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import com.example.mobileguiagent.agent.FieldAssign
import com.example.mobileguiagent.agent.SecretFiller
import org.json.JSONArray
import org.json.JSONObject

/**
 * 개인정보를 화면에 채우는 일을 통째로 앱 안에서 끝낸다.
 *
 * 이 도구가 따로 있는 이유가 이 프로젝트의 전부다. 부르는 쪽(바깥 모델)은
 * "무슨 값이 필요한지"만 말하고, 그 값이 무엇인지는 묻지도 받지도 못한다.
 *
 *     바깥 모델 -> fill_secrets { fields: ["username","password"], submit: true }
 *                    앱 안에서만: 금고 조회 -> 칸 배정 -> 입력 -> 제출
 *                 <- "아이디와 비밀번호를 넣고 로그인을 눌렀습니다"
 *
 * 낱개 도구(fill_field, type_node)로도 같은 일을 할 수는 있다. 하지만 그러면
 * 어느 칸에 무엇을 넣을지 바깥 모델이 정해야 하고, 그러려면 화면과 값을 함께
 * 봐야 한다. 그 순간 개인정보가 밖으로 나간다. 그래서 한 덩어리로 묶었다.
 *
 * 제출은 절차가 아니라 결정이다:
 *   submit=true는 로그인처럼 되돌릴 수 있는 것에만 쓴다. 주문·결제·회원가입은
 *   false로 두고 사람이 누른다. 값을 덜 채운 채로는 어느 경우에도 누르지 않는다
 *   (SecretFiller가 막는다).
 */
object FillSecretsDeviceTool : DeviceTool {
    const val NAME = "fill_secrets"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description =
            "Fills personal data into the form on screen, entirely on the device. " +
                "Say which kinds of value are needed; the values themselves stay on the " +
                "phone and are never returned. Use this instead of typing personal data " +
                "yourself — you cannot see the values, and you do not need to. " +
                "Call it once the form is on screen. Set submit only for reversible " +
                "actions like signing in; leave it off for orders, payments and sign-ups " +
                "so the person presses the final button.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "fields",
                        JSONObject()
                            .put("type", "array")
                            .put("items", JSONObject().put("type", "string"))
                            .put(
                                "description",
                                "필요한 값의 이름만. " + SecretFiller.fieldHint(),
                            ),
                    )
                    .put(
                        "submit",
                        JSONObject()
                            .put("type", "boolean")
                            .put(
                                "description",
                                "다 채운 뒤 제출 버튼까지 누를지. 로그인처럼 되돌릴 수 " +
                                    "있는 것에만 true. 기본값은 false.",
                            ),
                    ),
            )
            .put("required", JSONArray().put("fields"))
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val requested = arguments.optJSONArray("fields")
            ?.let { array -> (0 until array.length()).map { array.optString(it).trim() } }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        if (requested.isEmpty()) {
            return DeviceToolResult.Error(
                code = "MISSING_FIELDS",
                message = "필요한 값의 이름을 적어주세요. " + SecretFiller.fieldHint(),
            )
        }

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        return when (val outcome = SecretFiller.fill(service, requested, arguments.optBoolean("submit"))) {
            is SecretFiller.Outcome.Done -> DeviceToolResult.Success(message = outcome.message)
            is SecretFiller.Outcome.Failed ->
                DeviceToolResult.Error(code = outcome.code, message = outcome.message)
        }
    }

    /** 진행 상황 표시에 쓸 짧은 설명. 값은 절대 싣지 않는다. */
    fun describe(node: FieldAssign.Step): String = node.line
}
