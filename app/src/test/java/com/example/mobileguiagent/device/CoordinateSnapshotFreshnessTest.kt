package com.example.mobileguiagent.device

import android.graphics.Rect
import com.example.mobileguiagent.model.UiNode
import com.example.mobileguiagent.model.UiSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateSnapshotFreshnessTest {
    @Test
    fun exactObservedScreenIsFresh() {
        val expected = snapshot("com.example.app", "좌석 로딩 중")
        val current = snapshot("com.example.app", "좌석 로딩 중")

        assertTrue(coordinateSnapshotIsFresh(expected, current))
    }

    @Test
    fun changedFingerprintIsStale() {
        val expected = snapshot("com.example.app", "좌석 로딩 중")
        val current = snapshot("com.example.app", "A5 판매가능")

        assertFalse(coordinateSnapshotIsFresh(expected, current))
    }

    @Test
    fun changedPackageOrUnavailableTreeIsStale() {
        val expected = snapshot("com.example.app", "좌석 로딩 중")

        assertFalse(coordinateSnapshotIsFresh(expected, snapshot("com.other.app", "좌석 로딩 중")))
        assertFalse(coordinateSnapshotIsFresh(expected, null))
    }

    private fun snapshot(packageName: String, label: String): UiSnapshot =
        UiSnapshot(
            packageName = packageName,
            nodes = listOf(
                UiNode(
                    id = "node_0",
                    text = label,
                    contentDescription = null,
                    className = "android.view.View",
                    viewId = null,
                    clickable = false,
                    editable = false,
                    scrollable = false,
                    enabled = true,
                    checked = null,
                    bounds = Rect(0, 0, 100, 100),
                    depth = 0,
                ),
            ),
        )
}
