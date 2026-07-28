package com.example.mobileguiagent.device

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 설치된 앱을 이름으로 찾아 실행한다.
 *
 * 모델은 패키지 이름을 모른다("카카오톡"이 com.kakao.talk인 걸 알 리 없고,
 * 삼성 기본 앱들은 더 예측하기 어렵다). 그래서 화면에 보이는 이름으로 찾게 하고,
 * 패키지 이름은 폰이 해결한다.
 *
 * 못 찾으면 비슷한 후보를 함께 돌려준다. 모델이 다음 스텝에서 바로 고쳐 부를 수
 * 있어야 왕복이 줄어든다.
 *
 * 주의: Android 11부터는 매니페스트의 <queries>에 MAIN/LAUNCHER 인텐트를
 * 선언해야 다른 앱이 보인다. 선언이 없으면 이 목록이 통째로 비어 온다.
 */
object LaunchAppDeviceTool : DeviceTool {
    const val NAME = "launch_app"

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Launches an installed app by its visible name (Korean or English). " +
            "Returns candidate names when the match is ambiguous or missing.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "name",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "앱 이름. 화면에 보이는 그대로(예: 카카오톡, 설정)."),
                ),
            )
            .put("required", JSONArray().put("name"))
            .put("additionalProperties", false),
    )

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val query = arguments.optString("name").trim()
        if (query.isEmpty()) {
            return DeviceToolResult.Error(
                code = "MISSING_NAME",
                message = "실행할 앱 이름이 필요합니다.",
            )
        }

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )
        val packageManager = service.packageManager
        val installed = launchableApps(packageManager)
        if (installed.isEmpty()) {
            return DeviceToolResult.Error(
                code = "NO_APPS_VISIBLE",
                message = "설치된 앱 목록을 읽지 못했습니다. 매니페스트의 <queries> 선언을 확인하세요.",
            )
        }

        val match = bestMatch(installed, query)
            ?: return DeviceToolResult.Error(
                code = "APP_NOT_FOUND",
                message = notFoundMessage(installed, query),
            )

        val intent = packageManager.getLaunchIntentForPackage(match.second)
            ?: return DeviceToolResult.Error(
                code = "NO_LAUNCH_INTENT",
                message = "${match.first} 은(는) 실행할 수 있는 앱이 아닙니다.",
            )

        return runCatching {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DeviceToolResult.Success(message = "${match.first} 을(를) 실행했습니다 (${match.second})")
        }.getOrElse { error ->
            Log.e(TAG, "Unable to launch: ${match.second}", error)
            DeviceToolResult.Error(
                code = "LAUNCH_FAILED",
                message = "${match.first} 을(를) 실행하지 못했습니다: ${error.message}",
            )
        }
    }

    /** (보이는 이름, 패키지) 목록. 이름순으로 정렬해 후보 제시가 예측 가능하게. */
    fun launchableApps(packageManager: PackageManager): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0)
            .map { info: ResolveInfo ->
                info.loadLabel(packageManager).toString() to info.activityInfo.packageName
            }
            .distinctBy { (_, packageName) -> packageName }
            .sortedBy { (label, _) -> label }
    }

    /**
     * 정확히 같은 이름 > 시작이 같음 > 포함. 공백과 대소문자는 무시한다.
     *
     * "카카오톡"과 "카카오 톡"처럼 띄어쓰기만 다른 경우가 흔해서 공백을 지우고 본다.
     *
     * 원래 검색어로 다 돌고 나서야 별칭으로 넘어간다. 화면 이름 "NAVER 지도"가
     * 검색어 "네이버"의 앞부분과 우연히 겹치는 것보다, 실제로 "네이버"라 적힌
     * 앱이 있다면 그게 항상 먼저여야 한다.
     */
    private fun bestMatch(
        apps: List<Pair<String, String>>,
        query: String,
    ): Pair<String, String>? {
        val candidates = AppAliases.expand(query).map { it.normalize() }
        candidates.forEach { needle ->
            apps.firstOrNull { (label, _) -> label.normalize() == needle }?.let { return it }
        }
        candidates.forEach { needle ->
            apps.firstOrNull { (label, _) -> label.normalize().startsWith(needle) }?.let { return it }
        }
        candidates.forEach { needle ->
            apps.firstOrNull { (label, _) -> label.normalize().contains(needle) }?.let { return it }
        }
        candidates.forEach { needle ->
            apps.firstOrNull { (_, packageName) -> packageName.normalize().contains(needle) }
                ?.let { return it }
        }
        return null
    }

    /**
     * 못 찾았을 때 무엇을 돌려줄지.
     *
     * 앱 목록 앞에서부터 잘라 보여주면(정렬이 알파벳순이라) 질의와 아무 상관 없는
     * 이름이 나온다. 실측: "계산기"를 찾다 실패했더니 "APP, AR 존, Android Trivia"를
     * 추천했다. 글자가 하나라도 겹치는 것만 고르고, 그마저 없으면 목록을 들이밀지
     * 말고 list_apps로 직접 찾으라고 알려준다.
     */
    private fun notFoundMessage(
        apps: List<Pair<String, String>>,
        query: String,
    ): String {
        val letters = query.normalize().toSet()
        val similar = apps
            .map { (label, _) -> label }
            .filter { label -> label.normalize().any { character -> character in letters } }
            .take(SUGGESTION_COUNT)

        return if (similar.isEmpty()) {
            "\"$query\" 앱이 없습니다(설치된 앱 ${apps.size}개). " +
                "list_apps로 어떤 앱이 있는지 먼저 확인하세요."
        } else {
            "\"$query\" 앱이 없습니다. 이름이 비슷한 앱: ${similar.joinToString()}"
        }
    }

    private fun String.normalize(): String = lowercase().filterNot(Char::isWhitespace)

    private const val SUGGESTION_COUNT = 8
    private const val TAG = "LaunchAppDeviceTool"
}
