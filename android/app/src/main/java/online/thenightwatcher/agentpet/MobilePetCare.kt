package online.thenightwatcher.agentpet

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Android Tamagotchi progression, persisted locally and checkpointed to Cloudflare. */
object MobilePetCare {
    private const val PREFS = "android_pet_care"
    private const val TOKENS_PER_XP = 5_000

    data class State(
        val xp: Int, val tokensToday: Int, val mealsToday: Int,
        val totalTokens: Int, val totalMeals: Int, val queriesToday: Int, val totalQueries: Int, val streakDays: Int,
        val lastFedAt: Long,
    ) {
        val internalLevel: Int get() { var level = 1; while (60 * (level + 1) * level <= xp) level++; return level }
        val displayLevel: Int get() = max(0, internalLevel - 1)
        val stage: String get() = when { internalLevel < 5 -> "Hatchling"; internalLevel < 10 -> "Companion"; internalLevel < 20 -> "Scout"; internalLevel < 35 -> "Hero"; else -> "Legend" }
        val progress: Int get() { val floor = 60 * internalLevel * (internalLevel - 1); val ceil = 60 * (internalLevel + 1) * internalLevel; return (((xp - floor).toDouble() / (ceil - floor)) * 100).toInt().coerceIn(0, 100) }
        val tokensToNextLevel: Int get() { val next = 60 * (internalLevel + 1) * internalLevel; return max(0, (next - xp) * TOKENS_PER_XP) }
        val hunger: String get() { val hours = if (lastFedAt == 0L) 12.0 else (System.currentTimeMillis() - lastFedAt) / 3_600_000.0; return when { hours < 4 -> "Full"; hours < 10 -> "Satisfied"; hours < 24 -> "Peckish"; hours < 48 -> "Hungry"; else -> "Starving" } }
        val achievements: List<String> get() = buildList {
            if (totalMeals >= 1) add("🍽 First Meal"); if (totalMeals >= 100) add("🏆 100 Sessions"); if (totalMeals >= 500) add("🥇 500 Sessions")
            if (totalTokens >= 1_000_000) add("🔥 1M Tokens"); if (totalTokens >= 10_000_000) add("⚡ 10M Tokens"); if (totalTokens >= 50_000_000) add("💥 50M Tokens")
            if (displayLevel >= 5) add("⭐ Level 5"); if (displayLevel >= 10) add("🌟 Level 10"); if (displayLevel >= 20) add("🛡 Level 20"); if (displayLevel >= 35) add("👑 Level 35")
            if (streakDays >= 7) add("📅 7-Day Streak"); if (streakDays >= 14) add("📆 14-Day Streak"); if (streakDays >= 30) add("🗓 30-Day Streak")
        }
    }

    fun state(context: Context): State {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        rollover(p, today())
        return State(p.getInt("xp", 0), p.getInt("tokens_today", 0), p.getInt("meals_today", 0), p.getInt("total_tokens", 0), p.getInt("total_meals", 0), p.getInt("queries_today", 0), p.getInt("total_queries", 0), p.getInt("streak", 0), p.getLong("last_fed", 0))
    }

    /** Care progression and feeding are driven exclusively by actual token usage. */
    private fun applyTokenUsage(context: Context, tokens: Int) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); rollover(p, today()); markFed(p)
        val carry = p.getInt("carry", 0) + tokens
        p.edit().putInt("carry", carry % TOKENS_PER_XP).putInt("xp", p.getInt("xp", 0) + carry / TOKENS_PER_XP)
            .putInt("tokens_today", p.getInt("tokens_today", 0) + tokens).putInt("total_tokens", p.getInt("total_tokens", 0) + tokens).apply()
    }

    /** Applies a relay delta once, including after reconnect/retry. */
    fun applyTokenDelta(context: Context, id: String, tokens: Int): Boolean {
        if (id.isBlank() || tokens <= 0) return false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val applied = p.getStringSet("applied_delta_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!applied.add(id)) return false
        while (applied.size > 1_000) applied.remove(applied.first())
        // Commit the id before applying usage so overlapping live/poll syncs cannot
        // count the same delta twice in this process.
        p.edit().putStringSet("applied_delta_ids", applied).commit()
        applyTokenUsage(context, tokens)
        return true
    }

    fun cloudVersion(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("cloud_version", 0)
    fun setCloudVersion(context: Context, version: Int) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("cloud_version", version).apply()
    fun setCareCheckpoint(context: Context, timestamp: Long) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("care_checkpoint", timestamp).apply()
    fun careCheckpoint(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("care_checkpoint", 0L)

    fun exportJson(context: Context): JSONObject {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        rollover(p, today())
        return JSONObject().apply {
            listOf("xp", "carry", "tokens_today", "meals_today", "total_tokens", "total_meals", "queries_today", "total_queries", "streak").forEach { put(it, p.getInt(it, 0)) }
            put("last_fed", p.getLong("last_fed", 0)); put("day", p.getString("day", today())); put("last_fed_day", p.getString("last_fed_day", ""))
        }
    }

    fun importJson(context: Context, value: JSONObject) {
        if (!value.has("xp")) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().apply {
            listOf("xp", "carry", "tokens_today", "meals_today", "total_tokens", "total_meals", "queries_today", "total_queries", "streak").forEach { putInt(it, value.optInt(it, 0)) }
            putLong("last_fed", value.optLong("last_fed", 0)); putString("day", value.optString("day", today())); putString("last_fed_day", value.optString("last_fed_day", ""))
        }.commit()
    }

    fun recordEvent(context: Context, event: JSONObject, eventId: String = ""): Boolean {
        if (isRequestEvent(event.optString("eventName"))) return recordRequest(context, event, eventId)
        return recordCompletion(context, event)
    }

    /** Counts an agent request without manufacturing token usage or XP. */
    fun recordRequest(context: Context, event: JSONObject, eventId: String = ""): Boolean {
        if (!isRequestEvent(event.optString("eventName"))) return false
        val sessionId = event.optString("sessionId")
        if (sessionId.isBlank()) return false
        val name = event.optString("eventName").lowercase()
        val timestampKey = event.opt("timestamp")?.toString().orEmpty()
        val id = eventId.ifBlank { "$sessionId:$name:$timestampKey" }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getStringSet("counted_request_event_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!seen.add(id)) return false
        while (seen.size > 2_000) seen.remove(seen.first())
        rollover(p, today())
        val requestDay = eventDay(event)
        val todayCount = p.getInt("queries_today", 0) + if (requestDay == today()) 1 else 0
        p.edit().putStringSet("counted_request_event_ids", seen)
            .putInt("queries_today", todayCount)
            .putInt("total_queries", p.getInt("total_queries", 0) + 1).apply()
        return true
    }

    private fun isRequestEvent(raw: String): Boolean = raw.lowercase().replace("_", "").replace(".", "").replace("-", "") in setOf(
        "userpromptsubmit", "userpromptsubmitted", "beforeagent", "preinvocation", "beforemodel",
        "beforesubmitprompt", "preuserprompt", "agentstart", "turnstart",
    )

    private fun eventDay(event: JSONObject): String {
        val raw = event.opt("timestamp")
        val millis = when (raw) {
            is Number -> raw.toLong().let { if (it in 1 until 10_000_000_000L) it * 1_000 else it }
            is String -> raw.toLongOrNull()?.let { if (it in 1 until 10_000_000_000L) it * 1_000 else it }
                ?: runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
            else -> null
        } ?: return today()
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    }

    private fun recordCompletion(context: Context, event: JSONObject): Boolean {
        val name = event.optString("eventName").lowercase()
        // Prompt events carry no authoritative usage. Tokens arrive separately
        // from the desktop transcript reader through /v1/care-deltas.
        if (name !in setOf("stop", "done", "sessionend", "session_end", "agentstop", "afteragent", "turncomplete", "session.idle", "agent_end")) return false
        val id = event.optString("sessionId"); if (id.isBlank()) return false
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getStringSet("completed_sessions", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!seen.add(id)) return false
        while (seen.size > 200) seen.remove(seen.first())
        rollover(p, today())
        p.edit().putStringSet("completed_sessions", seen)
            .putInt("meals_today", p.getInt("meals_today", 0) + 1).putInt("total_meals", p.getInt("total_meals", 0) + 1).apply()
        return true
    }

    fun reset(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun rollover(p: android.content.SharedPreferences, day: String) { if (p.getString("day", "") != day) p.edit().putString("day", day).putInt("tokens_today", 0).putInt("meals_today", 0).putInt("queries_today", 0).apply() }
    private fun markFed(p: android.content.SharedPreferences) {
        val now = System.currentTimeMillis(); val day = today(); val prior = p.getString("last_fed_day", null)
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now - 86_400_000))
        val streak = if (prior == day) p.getInt("streak", 0) else if (prior == yesterday) p.getInt("streak", 0) + 1 else 1
        p.edit().putLong("last_fed", now).putString("last_fed_day", day).putInt("streak", streak).apply()
    }
}
