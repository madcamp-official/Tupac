package com.example.mobileguiagent.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.util.Log
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * 안드로이드 기본 기능을 인텐트로 바로 실행한다. 빅스비가 하는 일의 대부분이 이것이다.
 *
 * OpenScreenDeviceTool과 나눠놓은 이유는 인자 때문이다. 설정 화면 열기는 "어느
 * 화면"만 있으면 되지만, 이쪽은 전화번호·검색어·시각 같은 값을 받아야 한다.
 *
 * 발신·전송은 하지 않는다:
 *   dial은 다이얼러에 번호를 채워줄 뿐 전화를 걸지 않고(ACTION_CALL이 아니라
 *   ACTION_DIAL), sms·email도 작성 화면까지만 연다. 전화와 메시지는 되돌릴 수
 *   없고 상대방에게 남는 행동이라, 마지막 한 번은 사람이 눌러야 한다.
 *
 * 반면 alarm·timer는 실제로 등록되고 시작된다:
 *   ACTION_SET_ALARM과 ACTION_SET_TIMER가 그렇게 동작한다(실측: 삼성 시계 앱은
 *   알람을 바로 켜고 타이머를 바로 돌린다). 기기 안에서 끝나고 사용자가 쉽게
 *   되돌릴 수 있어 그대로 두지만, "화면만 연다"고 오해하면 안 된다.
 */
object SystemTaskDeviceTool : DeviceTool {
    const val NAME = "start_task"

    /** 키 -> 모델에게 보여줄 설명. 어떤 인자가 필요한지까지 적어야 모델이 채운다. */
    private val hints: Map<String, String> = linkedMapOf(
        "dial" to "전화 앱에 번호 입력 (value=전화번호). 걸지는 않음",
        "sms" to "문자 작성 화면 (value=전화번호, text=내용). 보내지는 않음",
        "email" to "메일 작성 화면 (value=이메일 주소, text=내용). 보내지는 않음",
        "web_search" to "웹 검색, 인터넷 찾기 (value=검색어)",
        "open_url" to "브라우저, 인터넷으로 주소 열기 (value=URL)",
        "map" to "지도, 길찾기로 장소 찾기 (value=장소 이름)",
        "alarm" to "알람을 바로 등록하고 켠다 (value=HH:MM, text=알람 이름)",
        "timer" to "타이머를 바로 시작한다 (value=분 단위 숫자)",
        "show_alarms" to "알람 목록",
        "torch" to "손전등, 플래시, 후레쉬를 바로 켜거나 끈다 (value=on 또는 off)",
        "camera" to "카메라, 사진 촬영",
        "gallery" to "갤러리, 사진 보기",
        "contacts" to "연락처, 주소록 목록",
        "calendar" to "일정, 캘린더 추가 화면 (value=일정 제목)",
    )

    override val definition = DeviceToolDefinition(
        name = NAME,
        description = "Starts a built-in Android task (dial, sms, search, map, alarm, torch, " +
            "camera...). Never places a call or sends a message: " +
            "those open a composer with the values filled in. Note that alarm and timer " +
            "do take effect immediately.",
        inputSchema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "task",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray(hints.keys.toList()))
                            .put("description", taskHints()),
                    )
                    .put(
                        "value",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "작업에 따라 전화번호, 검색어, 시각 등."),
                    )
                    .put(
                        "text",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "문자·메일 내용이나 알람 이름."),
                    ),
            )
            .put("required", JSONArray().put("task"))
            .put("additionalProperties", false),
    )

    fun taskHints(): String = hints.entries.joinToString(", ") { (key, hint) -> "$key($hint)" }

    override fun execute(arguments: JSONObject): DeviceToolResult {
        val task = arguments.optString("task")
        val value = arguments.optString("value").trim()
        val text = arguments.optString("text").trim()

        val service = AgentAccessibilityService.activeService
            ?: return DeviceToolResult.Error(
                code = "ACCESSIBILITY_NOT_CONNECTED",
                message = "접근성 서비스가 연결되지 않았습니다.",
            )

        val intent = when (task) {
            // 손전등만 인텐트가 아니다. 화면을 여는 게 아니라 하드웨어를 직접
            // 건드리므로 여기서 처리하고 끝낸다.
            "torch" -> return setTorch(service, value)

            "dial" -> {
                if (value.isEmpty()) return missingValue(task)
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(value)}"))
            }

            "sms" -> {
                if (value.isEmpty()) return missingValue(task)
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(value)}"))
                    .apply { if (text.isNotEmpty()) putExtra("sms_body", text) }
            }

            "email" -> {
                if (value.isEmpty()) return missingValue(task)
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(value)}"))
                    .apply { if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text) }
            }

            "web_search" -> {
                if (value.isEmpty()) return missingValue(task)
                Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", value)
            }

            "open_url" -> {
                if (value.isEmpty()) return missingValue(task)
                Intent(Intent.ACTION_VIEW, Uri.parse(withScheme(value)))
            }

            "map" -> {
                if (value.isEmpty()) return missingValue(task)
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(value)}"))
            }

            "alarm" -> {
                val parts = value.split(":")
                val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                if (hour == null || hour !in 0..23 || minute !in 0..59) {
                    return DeviceToolResult.Error(
                        code = "BAD_TIME",
                        message = "알람 시각은 HH:MM 형식이어야 합니다(예: 07:30). 받은 값: $value",
                    )
                }
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    .apply { if (text.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, text) }
            }

            "timer" -> {
                val minutes = value.filter(Char::isDigit).toIntOrNull()
                if (minutes == null || minutes <= 0) {
                    return DeviceToolResult.Error(
                        code = "BAD_DURATION",
                        message = "타이머는 분 단위 숫자여야 합니다(예: 10). 받은 값: $value",
                    )
                }
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    .apply { if (text.isNotEmpty()) putExtra(AlarmClock.EXTRA_MESSAGE, text) }
            }

            "show_alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)

            "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)

            "gallery" -> Intent(Intent.ACTION_VIEW).setType("image/*")

            "contacts" -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)

            "calendar" -> Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .apply { if (value.isNotEmpty()) putExtra(CalendarContract.Events.TITLE, value) }

            else -> return DeviceToolResult.Error(
                code = "UNKNOWN_TASK",
                message = "모르는 작업입니다: $task. 가능한 값: ${hints.keys.joinToString()}",
            )
        }

        return runCatching {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            DeviceToolResult.Success(message = "$task 실행했습니다. ${hints[task]}")
        }.getOrElse { error ->
            // 패키지 가시성(Android 11+) 때문에 resolveActivity로 미리 확인하면
            // 실제로는 열리는 화면도 없다고 나온다. 그래서 일단 시도하고 잡는다.
            Log.e(TAG, "Unable to start task: $task", error)
            DeviceToolResult.Error(
                code = if (error is ActivityNotFoundException) {
                    "NO_APP_FOR_TASK"
                } else {
                    "START_TASK_FAILED"
                },
                message = "$task 을(를) 실행하지 못했습니다: ${error.message}",
            )
        }
    }

    /**
     * 손전등을 켜거나 끈다.
     *
     * 다른 작업과 달리 인텐트로는 할 수 없다. 안드로이드에 손전등을 켜는 공개
     * 인텐트가 없어서, 모델이 아무리 길을 잘 찾아도 도달할 화면 자체가 없었다
     * (실측: "후레쉬 꺼줘"에 홈 화면에서 같은 아이콘만 세 번 눌렀다). 대신
     * CameraManager.setTorchMode는 권한 없이 부를 수 있고 바로 반영된다.
     *
     * 카메라가 이미 쓰이는 중이면 실패한다. 그건 막을 방법이 없으니 그대로 알린다.
     */
    private fun setTorch(context: Context, value: String): DeviceToolResult {
        val wanted = when (squash(value)) {
            "on", "true", "1", "켜기", "켜", "켜줘", "켜다" -> true
            "off", "false", "0", "끄기", "꺼", "꺼줘", "끄다" -> false
            else -> return DeviceToolResult.Error(
                code = "MISSING_VALUE",
                message = "손전등은 value가 on 또는 off여야 합니다. 받은 값: \"$value\"",
            )
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return DeviceToolResult.Error(
                code = "NO_CAMERA_SERVICE",
                message = "이 기기에서 카메라 서비스를 쓸 수 없습니다.",
            )

        return runCatching {
            val camera = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return DeviceToolResult.Error(
                code = "NO_FLASH",
                message = "이 기기에는 플래시가 없습니다.",
            )
            manager.setTorchMode(camera, wanted)
            DeviceToolResult.Success(
                message = "손전등을 ${if (wanted) "켰습니다" else "껐습니다"}.",
            )
        }.getOrElse { error ->
            Log.e(TAG, "Unable to set torch", error)
            DeviceToolResult.Error(
                code = "TORCH_FAILED",
                message = "손전등을 바꾸지 못했습니다: ${error.message}. " +
                    "다른 앱이 카메라를 쓰는 중일 수 있습니다.",
            )
        }
    }

    private fun squash(value: String) = value.trim().lowercase().replace(" ", "")

    private fun missingValue(task: String) = DeviceToolResult.Error(
        code = "MISSING_VALUE",
        message = "$task 에는 value가 필요합니다. ${hints[task]}",
    )

    /** 모델이 "google.com"처럼 스킴 없이 줄 때가 있다. */
    private fun withScheme(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"

    private const val TAG = "SystemTaskDeviceTool"
}
