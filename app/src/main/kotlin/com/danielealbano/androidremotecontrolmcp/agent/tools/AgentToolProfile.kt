package com.danielealbano.androidremotecontrolmcp.agent.tools

/** Un-prefixed MCP tool names available to the on-device agent; a focused subset keeps prompts small. */
object AgentToolProfile {
    const val GET_SCREEN_STATE = "get_screen_state"
    const val WAIT_FOR_IDLE = "wait_for_idle"

    val TOOL_NAMES: Set<String> =
        setOf(
            GET_SCREEN_STATE,
            "find_nodes",
            "click_node",
            "long_click_node",
            "tap_node",
            "scroll_to_node",
            "tap",
            "swipe",
            "scroll",
            "type_append_text",
            "type_clear_text",
            "press_key",
            "press_back",
            "press_home",
            "open_app",
            "list_apps",
            "open_uri",
            WAIT_FOR_IDLE,
        )
}
