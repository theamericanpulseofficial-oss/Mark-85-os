package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.agent.AgentState
import com.example.ai.ChatMessage
import com.example.settings.JarvisSettings
import com.example.tools.OpenAppTool
import com.example.tools.TimerTool
import com.example.tools.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("JARVIS", appName)
    }

    @Test
    fun `tool registry contains essential phone tools`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val registry = ToolRegistry(context)
        val definitions = registry.getAllDefinitions()
        assertTrue(definitions.any { it.name == "open_app" })
        assertTrue(definitions.any { it.name == "set_alarm" })
        assertTrue(definitions.any { it.name == "set_timer" })
        assertTrue(definitions.any { it.name == "phone_call" })
        assertTrue(definitions.any { it.name == "control_media" })
        assertTrue(definitions.any { it.name == "web_search" })
    }

    @Test
    fun `jarvis settings default model matches requirements`() {
        val settings = JarvisSettings()
        assertEquals("nemotron-3-ultra-550b-a55b", settings.nvidiaModel)
        assertEquals("nemotron-voicechat", settings.voiceModel)
    }
}
