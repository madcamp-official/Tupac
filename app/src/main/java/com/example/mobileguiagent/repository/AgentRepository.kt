package com.example.mobileguiagent.repository

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.example.mobileguiagent.accessibility.AgentAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 접근성 서비스가 살아 있는지, 지금 화면에 어느 앱이 떠 있는지만 들고 있다.
 *
 * 예전에는 여기에 명령 해석기와 실행기가 함께 있었다. "와이파이 열어" 같은 한국어를
 * 규칙으로 파싱해서 탭·입력·스크롤·설정 열기를 직접 수행하고, 몇 스텝 걸렸는지를
 * 파일에 적었다. MCP 이전, 앱 화면에서 직접 시켜보던 시절의 경로다.
 *
 * 지금은 그 일을 전부 도구가 한다 — 화면 읽기는 device_observe, 누르기는
 * device_click_node, 설정 화면은 device_open_screen. 규칙 파서가 알아듣던 명령은
 * 여덟 개였고 그마저 부르는 곳이 없어졌다. 같은 일을 하는 코드가 둘이면 나중에
 * 고치는 사람이 어느 쪽이 진짜인지 알 수 없으므로, 안 쓰는 쪽을 지웠다.
 *
 * 남은 것은 상태뿐이다. 접근성 서비스가 이 객체에 연결 여부와 화면 전환을 알리고,
 * 앱 화면과 device_status가 그것을 읽는다.
 */
data class AgentUiState(
    val serviceConnected: Boolean = false,
    val foregroundPackage: String = "",
)

object AgentRepository {
    private val mutableState = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = mutableState.asStateFlow()

    fun syncServiceConnection() {
        onServiceConnectionChanged(AgentAccessibilityService.activeService != null)
    }

    fun onServiceConnectionChanged(connected: Boolean) {
        if (mutableState.value.serviceConnected == connected) return
        mutableState.update { it.copy(serviceConnected = connected) }
    }

    fun onAccessibilityEvent(
        packageName: String,
        className: String,
        eventType: Int,
    ) {
        if (packageName.isNotBlank()) {
            mutableState.update { it.copy(foregroundPackage = packageName) }
        }
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
