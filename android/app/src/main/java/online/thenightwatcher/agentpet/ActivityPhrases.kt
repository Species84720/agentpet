package online.thenightwatcher.agentpet

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Android counterpart of the desktop activity formatter. */
object ActivityPhrases {
    private val counts = ConcurrentHashMap<String, Int>()

    fun message(event: JSONObject, theme: String): String? {
        val eventName = event.optString("eventName").lowercase()
        val explicit = event.optString("message").trim().takeIf { it.isNotEmpty() }
        if (eventName == "notification") return explicit
        if (eventName in setOf("stop", "done", "sessionend", "session_end")) return pick(theme, "done", eventName)
        if (eventName in setOf("permissionrequest", "waiting")) return pick(theme, "waiting", eventName)
        if (eventName in setOf("userpromptsubmit", "user_prompt_submit", "beforeagent", "preinvocation")) return pick(theme, "thinking", eventName)
        val tool = event.optString("toolName").ifBlank { event.optString("tool_name") }
        if (tool.isNotBlank()) return pick(theme, category(tool), tool.lowercase())
        return explicit ?: pick(theme, "generic", eventName)
    }

    private fun category(tool: String): String {
        val value = tool.lowercase()
        return when {
            listOf("task", "agent").any(value::contains) -> "delegating"
            value.contains("skill") -> "reading"
            listOf("search", "grep", "glob", "find", "list", "fetch").any(value::contains) -> "searching"
            listOf("run", "shell", "terminal", "bash", "exec", "command").any(value::contains) -> "running"
            listOf("edit", "write", "create", "patch", "delete").any(value::contains) -> "writing"
            listOf("read", "view").any(value::contains) -> "reading"
            else -> "generic"
        }
    }

    private fun pick(theme: String, category: String, key: String): String {
        val pools = themes[theme] ?: themes.getValue("chef")
        val choices = pools[category] ?: pools.getValue("generic")
        val countKey = "$theme:$category:$key"
        val index = counts.merge(countKey, 1, Int::plus) ?: 1
        return choices[index.mod(choices.size)]
    }

    private val themes = mapOf(
        "chef" to mapOf(
            "thinking" to listOf("Marinating…", "Noodling…", "Planning the menu…"),
            "reading" to listOf("Perusing…", "Studying the recipe…", "Leafing through…"),
            "writing" to listOf("Cooking…", "Baking…", "Crafting…"),
            "running" to listOf("Brewing…", "Simmering…", "Stirring the pot…", "Running the numbers…"),
            "searching" to listOf("Foraging…", "Scouting…", "Hunting for ingredients…"),
            "delegating" to listOf("Hatching a plan…", "Spawning help…", "Rounding up agents…"),
            "waiting" to listOf("Awaiting instructions…", "Standing by…", "Listening…"),
            "done" to listOf("All done!", "Wrapped up!", "Delivered!", "Mission complete!"),
            "generic" to listOf("Working…", "Tinkering…", "Doing the thing…"),
        ),
        "engineer" to mapOf(
            "thinking" to listOf("Architecting…", "Designing…", "Debugging…"), "reading" to listOf("Inspecting…", "Reviewing…", "Parsing…"),
            "writing" to listOf("Refactoring…", "Implementing…", "Patching…"), "running" to listOf("Compiling…", "Building…", "Running the pipeline…"),
            "searching" to listOf("Scanning…", "Grepping…", "Tracing…"), "delegating" to listOf("Forking…", "Dispatching…", "Queueing a job…"),
            "waiting" to listOf("Awaiting input…", "Blocked on dependency…", "Polling…"), "done" to listOf("Build complete!", "Shipped!", "All green!"), "generic" to listOf("Processing…", "Executing…", "Running…"),
        ),
        "wizard" to themed("Pondering the arcane…", "Studying the scrolls…", "Inscribing…", "Casting…", "Scrying…", "Summoning a familiar…", "Awaiting the omens…", "The spell is cast!", "Working the magic…"),
        "explorer" to themed("Plotting a course…", "Mapping the terrain…", "Recording findings…", "Blazing a trail…", "Scouting ahead…", "Dispatching a guide…", "Holding position…", "Discovery made!", "On the trail…"),
        "scientist" to themed("Hypothesizing…", "Analyzing the sample…", "Synthesizing…", "Running the experiment…", "Cross-referencing…", "Tasking the team…", "Awaiting results…", "Hypothesis confirmed!", "Analyzing…"),
    )

    private fun themed(thinking: String, reading: String, writing: String, running: String, searching: String, delegating: String, waiting: String, done: String, generic: String) =
        mapOf("thinking" to listOf(thinking), "reading" to listOf(reading), "writing" to listOf(writing), "running" to listOf(running), "searching" to listOf(searching), "delegating" to listOf(delegating), "waiting" to listOf(waiting), "done" to listOf(done), "generic" to listOf(generic))
}
