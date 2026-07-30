package com.example.mobileguiagent

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mobileguiagent.cloud.ScreenElementFusion
import com.example.mobileguiagent.cloud.ScreenElementFusion.OcrTrigger
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrTriggerPolicyInstrumentedTest {
    @Test
    fun accessibleWebViewKeepsFastPath() {
        val nodes = buildList {
            add(node("web", className = "android.webkit.WebView"))
            repeat(12) { index ->
                add(node("label_$index", text = "메뉴 $index", clickable = index < 4))
            }
        }

        val decision = ScreenElementFusion.decideOcr(UiSnapshot("example", nodes))

        assertFalse(decision.shouldRun)
    }

    @Test
    fun sparseWebViewRequestsOcr() {
        val decision = ScreenElementFusion.decideOcr(
            UiSnapshot(
                "example",
                listOf(
                    node("web", className = "android.webkit.WebView"),
                    node("canvas", clickable = true),
                ),
            ),
        )

        assertTrue(OcrTrigger.SPARSE_WEBVIEW in decision.triggers)
    }

    @Test
    fun popupLanguageWithoutCloseRequestsOcr() {
        val nodes = buildList {
            add(node("promo", text = "오늘 하루 보지 않기"))
            repeat(8) { index -> add(node("label_$index", text = "항목 $index")) }
        }

        val decision = ScreenElementFusion.decideOcr(UiSnapshot("example", nodes))

        assertTrue(OcrTrigger.POPUP_WITHOUT_ACCESSIBLE_CLOSE in decision.triggers)
    }

    @Test
    fun repeatedUnchangedActionRequestsOcr() {
        val nodes = List(12) { index ->
            node("label_$index", text = "항목 $index", clickable = index < 4)
        }

        val decision = ScreenElementFusion.decideOcr(
            snapshot = UiSnapshot("example", nodes),
            repeatedUnchangedActions = 2,
        )

        assertTrue(OcrTrigger.REPEATED_UNCHANGED_ACTION in decision.triggers)
    }

    private fun node(
        id: String,
        text: String? = null,
        className: String = "android.view.View",
        clickable: Boolean = false,
    ) = UiNode(
        id = id,
        text = text,
        contentDescription = null,
        className = className,
        viewId = null,
        clickable = clickable,
        editable = false,
        scrollable = false,
        enabled = true,
        checked = null,
        bounds = Rect(0, 0, 100, 100),
        depth = 1,
    )
}
