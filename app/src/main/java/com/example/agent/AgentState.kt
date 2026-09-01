package com.example.agent

/**
 * Visual and operational states for the JARVIS assistant.
 */
enum class AgentState(val label: String) {
    OFFLINE("OFFLINE"),
    LISTENING_FOR_WAKE_WORD("LISTENING FOR \"HEY JARVIS\""),
    LISTENING("LISTENING"),
    THINKING("THINKING"),
    EXECUTING("EXECUTING"),
    SPEAKING("SPEAKING"),
    ERROR("ERROR")
}
