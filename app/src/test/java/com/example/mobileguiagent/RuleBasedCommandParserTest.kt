package com.example.mobileguiagent

import com.example.mobileguiagent.repository.AgentAction
import com.example.mobileguiagent.repository.RuleBasedCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleBasedCommandParserTest {
    @Test
    fun parsesWifiCommand() {
        assertEquals(
            AgentAction.OpenWifi,
            RuleBasedCommandParser.parse("와이파이 메뉴 열어"),
        )
    }

    @Test
    fun parsesTextClickCommand() {
        assertEquals(
            AgentAction.TapText("Bluetooth"),
            RuleBasedCommandParser.parse("눌러 Bluetooth"),
        )
    }

    @Test
    fun rejectsUnknownCommand() {
        assertNull(RuleBasedCommandParser.parse("아무거나 해"))
    }
}
