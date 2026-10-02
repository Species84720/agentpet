package online.thenightwatcher.agentpet

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Phone-local Tamagotchi progression. No desktop/cloud profile sync by design. */
object MobilePetCare {
    private const val PREFS = "android_pet_care"
    private const val TOKENS_PER_XP = 5_000
    private const val MEAL_XP = 25

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

    fun feed(context: Context, tokens: Int = 25_000) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); rollover(p, today()); markFed(p)
        val carry = p.getInt("carry", 0) + tokens
        p.edit().putInt("carry", carry % TOKENS_PER_XP).putInt("xp", p.getInt("xp", 0) + carry / TOKENS_PER_XP)
            .putInt("tokens_today", p.getInt("tokens_today", 0) + tokens).putInt("total_tokens", p.getInt("total_tokens", 0) + tokens).apply()
    }

    fun play(context: Context) { val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); markFed(p); p.edit().putInt("xp", p.getInt("xp", 0) + 10).apply() }

    fun recordEvent(context: Context, event: JSONObject) {
        val name = event.optString("eventName").lowercase()
        if (name in setOf("userpromptsubmit", "user_prompt_submit", "beforeagent", "preinvocation")) {
            val settings = context.getSharedPreferences("relay", Context.MODE_PRIVATE)
            if (settings.getBoolean("care_reward_queries", true)) {
                feed(context, settings.getInt("care_query_tokens", TOKENS_PER_XP).coerceIn(1_000, 25_000))
                val queryPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                queryPrefs.edit().putInt("queries_today", queryPrefs.getInt("queries_today", 0) + 1)
                    .putInt("total_queries", queryPrefs.getInt("total_queries", 0) + 1).apply()
            }
            return
        }
        if (name !in setOf("stop", "done", "sessionend", "session_end")) return
        val id = event.optString("sessionId"); if (id.isBlank()) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = p.getStringSet("completed_sessions", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!seen.add(id)) return
        while (seen.size > 200) seen.remove(seen.first())
        rollover(p, today()); markFed(p)
        p.edit().putStringSet("completed_sessions", seen).putInt("xp", p.getInt("xp", 0) + MEAL_XP)
            .putInt("meals_today", p.getInt("meals_today", 0) + 1).putInt("total_meals", p.getInt("total_meals", 0) + 1).apply()
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
