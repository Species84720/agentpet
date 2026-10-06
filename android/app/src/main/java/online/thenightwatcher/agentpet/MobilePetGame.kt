package online.thenightwatcher.agentpet

import android.content.Context
import kotlin.math.floor

/** Local-only Tamagotchi needs. These actions never grant AI-care XP or alter Cloudflare care. */
object MobilePetGame {
    private const val PREFS = "android_pet_game"

    data class State(
        val hunger: Int,
        val happiness: Int,
        val cleanliness: Int,
        val energy: Int,
        val feeds: Int,
        val playSessions: Int,
        val groomings: Int,
    ) {
        val hungerLabel: String get() = when { hunger >= 80 -> "Full"; hunger >= 55 -> "Satisfied"; hunger >= 30 -> "Hungry"; else -> "Very hungry" }
        val happinessLabel: String get() = when { happiness >= 80 -> "Delighted"; happiness >= 55 -> "Content"; happiness >= 30 -> "Lonely"; else -> "Needs play" }
        val cleanlinessLabel: String get() = when { cleanliness >= 80 -> "Fresh"; cleanliness >= 55 -> "Okay"; cleanliness >= 30 -> "Messy"; else -> "Needs a clean" }
        val energyLabel: String get() = when { energy >= 75 -> "Bouncy"; energy >= 45 -> "Ready"; energy >= 20 -> "Sleepy"; else -> "Exhausted" }
    }

    fun state(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        initialize(prefs)
        val now = System.currentTimeMillis()
        return State(
            value(prefs, "hunger", now, 78, 5.0),
            value(prefs, "happiness", now, 74, 4.0),
            value(prefs, "cleanliness", now, 86, 2.0),
            value(prefs, "energy", now, 82, 6.0),
            prefs.getInt("feeds", 0),
            prefs.getInt("play_sessions", 0),
            prefs.getInt("groomings", 0),
        )
    }

    fun feed(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = state(context)
        set(prefs, "hunger", 100)
        set(prefs, "happiness", (current.happiness + 10).coerceAtMost(100))
        prefs.edit().putInt("feeds", current.feeds + 1).apply()
    }

    fun play(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = state(context)
        set(prefs, "happiness", 100)
        set(prefs, "energy", (current.energy - 18).coerceAtLeast(0))
        prefs.edit().putInt("play_sessions", current.playSessions + 1).apply()
    }

    fun groom(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = state(context)
        set(prefs, "cleanliness", 100)
        set(prefs, "happiness", (current.happiness + 5).coerceAtMost(100))
        prefs.edit().putInt("groomings", current.groomings + 1).apply()
    }

    fun rest(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        set(prefs, "energy", 100)
    }

    private fun initialize(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean("initialized", false)) return
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean("initialized", true)
            .putInt("hunger_value", 78).putLong("hunger_at", now)
            .putInt("happiness_value", 74).putLong("happiness_at", now)
            .putInt("cleanliness_value", 86).putLong("cleanliness_at", now)
            .putInt("energy_value", 82).putLong("energy_at", now)
            .apply()
    }

    private fun value(prefs: android.content.SharedPreferences, key: String, now: Long, default: Int, decayPerHour: Double): Int {
        val base = prefs.getInt("${key}_value", default)
        val lastChanged = prefs.getLong("${key}_at", now)
        val elapsedHours = ((now - lastChanged).coerceAtLeast(0L)) / 3_600_000.0
        return (base - floor(elapsedHours * decayPerHour).toInt()).coerceIn(0, 100)
    }

    private fun set(prefs: android.content.SharedPreferences, key: String, value: Int) {
        prefs.edit().putInt("${key}_value", value.coerceIn(0, 100)).putLong("${key}_at", System.currentTimeMillis()).apply()
    }
}
