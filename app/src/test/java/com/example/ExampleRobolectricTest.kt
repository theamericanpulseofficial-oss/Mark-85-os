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
        assertEquals("Mark OS", appName)
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
        assertEquals(JarvisSettings.DEFAULT_NVIDIA_AGENT_MODEL, settings.nvidiaModel)
        assertEquals(JarvisSettings.DEFAULT_NVIDIA_AGENT_MODEL, settings.voiceModel)
        assertEquals(JarvisSettings.TTS_PROVIDER_INWORLD, settings.ttsProvider)
        assertEquals("Dennis", settings.inworldVoiceId)
    }

    @Test
    fun `inworld kokoro tts engine initializes with fallback support`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = JarvisSettings()
        val engine = com.example.audio.InworldKokoroTtsEngine(context, settings)
        assertNotNull(engine)
    }
}
