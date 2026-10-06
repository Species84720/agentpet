package online.thenightwatcher.agentpet

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.random.Random

/** Local-only Tamagotchi needs. These actions never grant AI-care XP or alter Cloudflare care. */
object MobilePetGame {
    private const val PREFS = "android_pet_game"
    private const val HOUR = 3_600_000L
    private const val AUTONOMY_INTERVAL = 90 * 60_000L
    private const val JOURNAL_LIMIT = 6
    private const val NAP_DURATION = 5 * 60_000L
    private const val OVERFULL_DURATION = 8 * 60_000L

    enum class Personality(val title: String, val introduction: String) {
        SCOUT("Scout", "Curious about every little corner."),
        DREAMER("Dreamer", "Collects stories and loves a cozy spot."),
        RASCAL("Rascal", "Playful, inventive, and a little untidy.")
    }

    data class Reaction(val message: String, val mood: String = "celebrate")
    enum class Room(val title: String, val bondRequired: Int, val colors: IntArray, val decor: String) {
        NEST("Cozy nest", 0, intArrayOf(0xff29334b.toInt(), 0xff172134.toInt()), "🛏️  🧸  🪴"),
        GARDEN("Garden hideout", 35, intArrayOf(0xff244d40.toInt(), 0xff142e30.toInt()), "🌻  🪴  🦋  🍄"),
        SPACE("Star cabin", 50, intArrayOf(0xff493662.toInt(), 0xff1e213b.toInt()), "🌙  ⭐  🚀  🔭"),
        CASTLE("Tiny castle", 70, intArrayOf(0xff624936.toInt(), 0xff302741.toInt()), "🏰  👑  🕯️  📚")
    }
    enum class Toy(val title: String, val bondRequired: Int, val story: String) {
        BALL("Bouncy ball", 30, "I chased our bouncy ball and caught it with my paws!"),
        KITE("Rainbow kite", 45, "Our kite danced above the garden. I almost flew too!"),
        PUZZLE("Treasure puzzle", 60, "I solved our puzzle and found a tiny pretend treasure!")
    }
    enum class Trick(val title: String, val bondRequired: Int, val story: String, val mood: String) {
        DANCE("Happy dance", 35, "Ta-da! I practiced this happy dance just for you!", "celebrate"),
        BOW("Sleepy bow", 50, "A very dramatic bow... and a little yawn!", "sleepy"),
        MAGIC("Tiny magic show", 70, "Watch closely! A star appeared from behind my ear!", "done")
    }

    data class State(
        val hunger: Int,
        val happiness: Int,
        val cleanliness: Int,
        val energy: Int,
        val sleepRemainingMs: Long,
        val overfullRemainingMs: Long,
        val bond: Int,
        val personality: Personality,
        val activity: String,
        val idleMood: String,
        val journal: List<String>,
        val finds: Set<String>,
        val feeds: Int,
        val playSessions: Int,
        val groomings: Int,
        val stars: Int,
        val questAction: String,
        val questComplete: Boolean,
        val room: Room,
        val bestBond: Int,
    ) {
        val sleeping: Boolean get() = sleepRemainingMs > 0
        val overfull: Boolean get() = overfullRemainingMs > 0
        val playBlockReason: String? get() = when {
            sleeping -> "Sleeping"
            overfull -> "Too full to play"
            energy < 35 -> "Too tired to play"
            hunger < 25 -> "Too hungry to play"
            else -> null
        }
        val exploreBlockReason: String? get() = when {
            sleeping -> "Sleeping"
            overfull -> "Too full to explore"
            energy < 40 -> "Too tired to explore"
            hunger < 25 -> "Too hungry to explore"
            else -> null
        }
        val hungerLabel: String get() = when { hunger >= 80 -> "Full"; hunger >= 55 -> "Satisfied"; hunger >= 30 -> "Hungry"; else -> "Very hungry" }
        val happinessLabel: String get() = when { happiness >= 80 -> "Delighted"; happiness >= 55 -> "Content"; happiness >= 30 -> "Lonely"; else -> "Needs play" }
        val cleanlinessLabel: String get() = when { cleanliness >= 80 -> "Fresh"; cleanliness >= 55 -> "Okay"; cleanliness >= 30 -> "Messy"; else -> "Needs a clean" }
        val energyLabel: String get() = when { energy >= 75 -> "Bouncy"; energy >= 45 -> "Ready"; energy >= 20 -> "Sleepy"; else -> "Exhausted" }
    }

    fun state(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        initialize(prefs)
        val now = System.currentTimeMillis()
        finishNapIfReady(prefs, now)
        finishOverfullIfReady(prefs, now)
        if (prefs.getLong("sleep_until", 0L) <= now) autonomousMoment(prefs, now)
        val sleepUntil = prefs.getLong("sleep_until", 0L)
        val sleeping = sleepUntil > now
        val overfullUntil = prefs.getLong("overfull_until", 0L)
        val hunger = value(prefs, "hunger", now, 78, 5.0)
        val happiness = value(prefs, "happiness", now, 74, 4.0)
        val cleanliness = value(prefs, "cleanliness", now, 86, 2.0)
        val energy = if (sleeping) {
            val started = prefs.getLong("sleep_started_at", now)
            val startEnergy = prefs.getInt("sleep_energy_start", 0)
            (startEnergy + 45 * (now - started).coerceIn(0, NAP_DURATION) / NAP_DURATION).toInt().coerceIn(0, 100)
        } else value(prefs, "energy", now, 82, 6.0)
        val personality = personality(prefs)
        val questDay = java.time.LocalDate.now().toString()
        val questAction = listOf("feed", "play", "groom", "nap", "explore", "talk")[(java.time.LocalDate.now().toEpochDay() % 6).toInt()]
        val recent = prefs.getString("activity", "").orEmpty()
            .takeIf { now - prefs.getLong("activity_at", 0L) < AUTONOMY_INTERVAL && it.isNotBlank() }
        val activity = when {
            sleeping -> "Shhh... I am sleeping. ${timeLeft(sleepUntil - now)} until I wake."
            overfullUntil > now -> "I ate too much. My tummy needs ${timeLeft(overfullUntil - now)} to settle."
            energy < 35 -> "I'm getting sleepy. A nap would feel lovely."
            hunger < 28 -> "I keep thinking about a snack..."
            cleanliness < 28 -> "My nest could use a tidy up."
            happiness < 28 -> "I saved a story to tell you when you return."
            recent != null -> recent
            else -> idleThought(personality, now)
        }
        return State(
            hunger, happiness, cleanliness, energy,
            (sleepUntil - now).coerceAtLeast(0L), (overfullUntil - now).coerceAtLeast(0L),
            friendship(prefs, now), personality, activity,
            if (sleeping || overfullUntil > now || energy < 35) "sleepy"
            else if (prefs.getLong("activity_mood_until", 0L) > now) prefs.getString("activity_mood", "idle") ?: "idle" else "idle",
            prefs.getString("journal", "").orEmpty().lines().filter(String::isNotBlank).take(JOURNAL_LIMIT),
            prefs.getStringSet("finds", emptySet())?.toSet().orEmpty(),
            prefs.getInt("feeds", 0),
            prefs.getInt("play_sessions", 0),
            prefs.getInt("groomings", 0),
            prefs.getInt("stars", 0), questAction, prefs.getString("quest_completed_day", "") == questDay,
            runCatching { Room.valueOf(prefs.getString("room", "NEST") ?: "NEST") }.getOrDefault(Room.NEST),
            maxOf(prefs.getInt("best_bond", prefs.getInt("bond", 28)), friendship(prefs, now)),
        )
    }

    fun feed(context: Context): Reaction {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = state(context)
        if (current.sleeping) return Reaction(current.activity, "sleepy")
        if (current.overfull) return Reaction("No more snacks until my tummy settles!", "sleepy")
        if (current.hunger >= 90) {
            val message = "Oh! One snack too many. I need a quiet tummy break before we play."
            prefs.edit().putLong("overfull_until", System.currentTimeMillis() + OVERFULL_DURATION).apply()
            set(prefs, "happiness", current.happiness - 8)
            set(prefs, "energy", current.energy - 8)
            remember(prefs, message, 0)
            prefs.edit().putInt("feeds", current.feeds + 1).apply()
            return Reaction(message, "sleepy")
        }
        set(prefs, "hunger", current.hunger + 35)
        set(prefs, "happiness", current.happiness + 6)
        val message = when (current.personality) {
            Personality.SCOUT -> "Yum! I wonder what we can discover next."
            Personality.DREAMER -> "That was lovely. I shall dream about it."
            Personality.RASCAL -> "Delicious! I definitely did not hide a crumb."
        }
        remember(prefs, message, 2, "feed")
        prefs.edit().putInt("feeds", current.feeds + 1).apply()
        return Reaction(message)
    }

    fun play(context: Context): Reaction {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = state(context)
        current.playBlockReason?.let { return Reaction("$it. Let's rest or eat first.", "sleepy") }
        set(prefs, "happiness", current.happiness + 15)
        set(prefs, "energy", current.energy - 12)
        val message = "That was fun! I want to play again soon."
        remember(prefs, message, 2, "play")
        prefs.edit().putInt("play_sessions", current.playSessions + 1).apply()
        return Reaction(message)
    }

    fun groom(context: Context): Reaction {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = state(context)
        if (current.sleeping) return Reaction(current.activity, "sleepy")
        if (current.cleanliness >= 95) return Reaction("I'm already neat as a pin!", current.idleMood)
        set(prefs, "cleanliness", current.cleanliness + 35)
        set(prefs, "happiness", current.happiness + 5)
        val message = "All brushed! My little corner looks lovely now."
        remember(prefs, message, 2, "groom")
        prefs.edit().putInt("groomings", current.groomings + 1).apply()
        return Reaction(message)
    }

    fun rest(context: Context): Reaction {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = state(context)
        if (current.sleeping) return Reaction(current.activity, "sleepy")
        if (current.energy >= 85) return Reaction("I am wide awake. Let's do something fun!", "idle")
        startNap(prefs, System.currentTimeMillis(), current.energy)
        val message = "I tucked myself in. Wake me in five minutes!"
        remember(prefs, message, 1, "nap")
        return Reaction(message, "sleepy")
    }

    /** A small direction guessing game; a miss is still a pleasant play session. */
    fun guess(context: Context, choice: Int, hidingPlace: Int): Reaction {
        val current = state(context)
        current.playBlockReason?.let { return Reaction("$it. Let's rest or eat first.", "sleepy") }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val found = choice == hidingPlace
        set(prefs, "energy", current.energy - 12)
        set(prefs, "happiness", current.happiness + if (found) 18 else 8)
        val place = listOf("left", "middle", "right")[hidingPlace.coerceIn(0, 2)]
        val message = if (found) "You found my star! One more round?" else "Hehe, my star was under the $place cup. I had fun anyway!"
        remember(prefs, message, if (found) 4 else 2, "play")
        prefs.edit().putInt("play_sessions", current.playSessions + 1).apply()
        return Reaction(message, if (found) "celebrate" else "idle")
    }

    fun explore(context: Context, destination: Int): Reaction {
        val current = state(context)
        current.exploreBlockReason?.let { return Reaction("$it. Adventure can wait!", "sleepy") }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val index = destination.coerceIn(0, 2)
        val find = listOf("Garden pebble", "Attic ribbon", "Rooftop star")[index]
        val foundNew = find !in current.finds
        val finds = current.finds.toMutableSet().apply { add(find) }
        set(prefs, "energy", current.energy - 15)
        set(prefs, "hunger", current.hunger - 6)
        set(prefs, "happiness", current.happiness + 12)
        prefs.edit().putStringSet("finds", finds).apply()
        val message = if (foundNew) "I found a $find for our collection!" else "We visited the ${listOf("garden", "attic", "rooftop")[index]} again. I remembered our first trip!"
        remember(prefs, message, 3, "explore")
        return Reaction(message)
    }

    fun talk(context: Context): Reaction {
        val current = state(context)
        if (current.sleeping) return Reaction(current.activity, "sleepy")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastVisit = prefs.getLong("last_interaction_at", System.currentTimeMillis())
        val message = when {
            System.currentTimeMillis() - lastVisit >= 6 * HOUR -> "You're back! ${current.activity}"
            current.overfull -> "I think my tummy and I both need a little quiet time."
            current.hunger < 30 -> "Could we find a snack together?"
            current.energy < 25 -> "I want to hear your story after my nap."
            current.cleanliness < 30 -> "I made a bit of a mess while I was busy..."
            else -> when (current.personality) {
                Personality.SCOUT -> "I wonder what is behind the next door. Let's explore!"
                Personality.DREAMER -> "I had a dream about a sky full of tiny lanterns."
                Personality.RASCAL -> "I hid a smile here for you to find. Got it?"
            }
        }
        set(prefs, "happiness", current.happiness + 3)
        remember(prefs, message, 1)
        return Reaction(message, "idle")
    }

    fun chooseRoom(context: Context, room: Room): Boolean {
        if (state(context).bestBond < room.bondRequired) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("room", room.name).apply()
        return true
    }

    fun playWithToy(context: Context, toy: Toy): Reaction {
        val current = state(context)
        current.playBlockReason?.let { return Reaction(it, current.idleMood) }
        if (current.bestBond < toy.bondRequired) return Reaction("This toy unlocks at ${toy.bondRequired}% friendship.", "idle")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        set(prefs, "energy", current.energy - 10); set(prefs, "happiness", current.happiness + 12)
        remember(prefs, toy.story, 2, "play")
        prefs.edit().putInt("play_sessions", current.playSessions + 1).apply()
        return Reaction(toy.story)
    }

    fun performTrick(context: Context, trick: Trick): Reaction {
        val current = state(context)
        current.playBlockReason?.let { return Reaction(it, current.idleMood) }
        if (current.bestBond < trick.bondRequired) return Reaction("This trick unlocks at ${trick.bondRequired}% friendship.", "idle")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        set(prefs, "energy", current.energy - 6); set(prefs, "happiness", current.happiness + 6)
        remember(prefs, trick.story, 1, "play")
        prefs.edit().putString("activity_mood", trick.mood).putLong("activity_mood_until", System.currentTimeMillis() + 8_000).apply()
        return Reaction(trick.story, trick.mood)
    }

    fun brainContext(context: Context): org.json.JSONObject {
        val s = state(context)
        return org.json.JSONObject().put("personality", s.personality.title).put("hunger", s.hunger)
            .put("happiness", s.happiness).put("cleanliness", s.cleanliness).put("energy", s.energy)
            .put("bond", s.bond).put("stars", s.stars).put("sleeping", s.sleeping)
            .put("diary", org.json.JSONArray(s.journal))
    }

    fun rememberAi(context: Context, message: String, mood: String): Reaction {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A conversation cannot wake the pet, award AI XP, or bypass care rules.
        remember(prefs, message.take(800), 0)
        return Reaction(message.take(800), if (mood in listOf("idle", "celebrate", "sleepy")) mood else "idle")
    }

    private fun autonomousMoment(prefs: SharedPreferences, now: Long) {
        val lastInteraction = prefs.getLong("last_interaction_at", now)
        val lastAutonomy = prefs.getLong("last_autonomy_at", now)
        if (now - lastInteraction < AUTONOMY_INTERVAL || now - lastAutonomy < AUTONOMY_INTERVAL) return
        var hunger = value(prefs, "hunger", now, 78, 5.0)
        var happiness = value(prefs, "happiness", now, 74, 4.0)
        var cleanliness = value(prefs, "cleanliness", now, 86, 2.0)
        var energy = value(prefs, "energy", now, 82, 6.0)
        val number = prefs.getInt("autonomy_count", 0)
        val message = when {
            energy < 20 -> { startNap(prefs, now, energy); "I curled up for a little nap while you were away." }
            hunger < 25 -> { hunger += 22; "I found a little snack by myself while you were away." }
            cleanliness < 25 -> { cleanliness += 25; "I tidied my nest while you were away. Look!" }
            personality(prefs) == Personality.SCOUT -> {
                happiness += 8; energy -= 5
                listOf("I mapped the room while you were away.", "I found a sunny patch and watched the world.", "I followed a mysterious shadow around the room.")[number % 3]
            }
            personality(prefs) == Personality.DREAMER -> {
                energy += 10; happiness += 5
                listOf("I made a pillow fort while you were away.", "I collected a story from a passing cloud.", "I took a little nap and dreamed of you.")[number % 3]
            }
            else -> {
                happiness += 10; cleanliness -= 8
                listOf("I hid a tiny treasure in my nest while you were away.", "I practiced a silly dance. There may be confetti.", "I rearranged my toys into a secret club.")[number % 3]
            }
        }
        val editor = prefs.edit()
        listOf("hunger" to hunger, "happiness" to happiness, "cleanliness" to cleanliness, "energy" to energy)
            .forEach { (key, amount) -> editor.putInt("${key}_value", amount.coerceIn(0, 100)).putLong("${key}_at", now) }
        editor.putInt("autonomy_count", number + 1).putLong("last_autonomy_at", now)
            .putString("activity", message).putLong("activity_at", now)
            .putString("journal", journalWith(prefs, now, message)).apply()
    }

    private fun startNap(prefs: SharedPreferences, now: Long, energy: Int) {
        prefs.edit().putLong("sleep_started_at", now).putLong("sleep_until", now + NAP_DURATION)
            .putInt("sleep_energy_start", energy.coerceIn(0, 100)).apply()
    }

    private fun finishNapIfReady(prefs: SharedPreferences, now: Long) {
        val until = prefs.getLong("sleep_until", 0L)
        if (until == 0L || now < until) return
        val energy = (prefs.getInt("sleep_energy_start", 0) + 45).coerceAtMost(100)
        val message = "I woke up refreshed and ready for a little adventure!"
        prefs.edit().remove("sleep_until").remove("sleep_started_at").remove("sleep_energy_start")
            .putInt("energy_value", energy).putLong("energy_at", now)
            .putLong("last_autonomy_at", now)
            .putString("activity", message).putLong("activity_at", now)
            .putString("journal", journalWith(prefs, now, message)).apply()
    }

    private fun finishOverfullIfReady(prefs: SharedPreferences, now: Long) {
        val until = prefs.getLong("overfull_until", 0L)
        if (until == 0L || now < until) return
        prefs.edit().remove("overfull_until")
            .putString("activity", "My tummy feels better. Let's do something fun!")
            .putLong("activity_at", now).apply()
    }

    private fun timeLeft(remainingMs: Long): String {
        val seconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
        return "${seconds / 60}m ${seconds % 60}s"
    }

    private fun initialize(prefs: SharedPreferences) {
        val now = System.currentTimeMillis()
        if (!prefs.getBoolean("initialized", false)) {
            prefs.edit().putBoolean("initialized", true)
                .putInt("hunger_value", 78).putLong("hunger_at", now)
                .putInt("happiness_value", 74).putLong("happiness_at", now)
                .putInt("cleanliness_value", 86).putLong("cleanliness_at", now)
                .putInt("energy_value", 82).putLong("energy_at", now)
                .putLong("last_interaction_at", now).putLong("last_autonomy_at", now).apply()
        }
        if (!prefs.contains("personality")) {
            val personalities = Personality.values()
            prefs.edit().putString("personality", personalities[Random.nextInt(personalities.size)].name).apply()
        }
        // Existing installs had no visit clock. Start it now instead of
        // inventing a long absence and immediately triggering an idle event.
        if (!prefs.contains("last_interaction_at")) prefs.edit().putLong("last_interaction_at", now).putLong("last_autonomy_at", now).apply()
        if (!prefs.contains("bond_at")) prefs.edit().putLong("bond_at", now).apply()
        if (!prefs.contains("best_bond")) prefs.edit().putInt("best_bond", prefs.getInt("bond", 28)).apply()
    }

    private fun personality(prefs: SharedPreferences): Personality = runCatching {
        Personality.valueOf(prefs.getString("personality", Personality.SCOUT.name) ?: Personality.SCOUT.name)
    }.getOrDefault(Personality.SCOUT)

    private fun idleThought(personality: Personality, now: Long): String {
        val thoughts = when (personality) {
            Personality.SCOUT -> listOf("I am peeking around for our next adventure.", "I spotted something interesting over there!", "I am drawing a tiny map of this place.")
            Personality.DREAMER -> listOf("I am making up a story for you.", "The clouds look like tiny teapots today.", "A cozy day is a good day.")
            Personality.RASCAL -> listOf("I might be planning a harmless prank.", "I am practicing my best silly dance.", "Can you guess where I hid my favorite toy?")
        }
        return thoughts[((now / (30 * 60_000L)) % thoughts.size).toInt()]
    }

    private fun remember(prefs: SharedPreferences, message: String, bondGain: Int, action: String = "talk") {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now()
        val quest = listOf("feed", "play", "groom", "nap", "explore", "talk")[(today.toEpochDay() % 6).toInt()]
        val eligible = bondGain > 0 && now - prefs.getLong("reward_$action", 0L) >= 5 * 60_000L
            && (action != "talk" || (value(prefs, "hunger", now, 78, 5.0) >= 25 && value(prefs, "cleanliness", now, 86, 2.0) >= 25))
        val completed = eligible && action == quest && prefs.getString("quest_completed_day", "") != today.toString()
        val bond = (friendship(prefs, now) + (if (eligible) bondGain else 0) + if (completed) 5 else 0).coerceAtMost(100)
        val editor = prefs.edit()
            .putLong("last_interaction_at", now).putString("activity", message).putLong("activity_at", now)
            .putString("journal", journalWith(prefs, now, message))
        if (eligible) editor.putInt("bond", bond).putLong("bond_at", now).putLong("reward_$action", now)
            .putInt("best_bond", maxOf(prefs.getInt("best_bond", 28), bond))
        if (action != "talk" && action != "nap" && bondGain > 0) editor.putString("activity_mood", "celebrate").putLong("activity_mood_until", now + 8_000)
        if (completed) editor.putString("quest_completed_day", today.toString()).putInt("stars", prefs.getInt("stars", 0) + 1)
        editor.apply()
    }

    private fun friendship(prefs: SharedPreferences, now: Long): Int {
        val absentHours = ((now - prefs.getLong("bond_at", now) - 6 * HOUR).coerceAtLeast(0L) / HOUR).toInt().coerceAtMost(30)
        return (prefs.getInt("bond", 28) - absentHours).coerceIn(0, 100)
    }

    private fun journalWith(prefs: SharedPreferences, now: Long, message: String): String {
        val time = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(now))
        return (listOf("$time · $message") + prefs.getString("journal", "").orEmpty().lines().filter(String::isNotBlank))
            .take(JOURNAL_LIMIT).joinToString("\n")
    }

    private fun value(prefs: SharedPreferences, key: String, now: Long, default: Int, decayPerHour: Double): Int {
        val base = prefs.getInt("${key}_value", default)
        val lastChanged = prefs.getLong("${key}_at", now)
        val elapsedHours = ((now - lastChanged).coerceAtLeast(0L)) / HOUR.toDouble()
        return (base - floor(elapsedHours * decayPerHour).toInt()).coerceIn(0, 100)
    }

    private fun set(prefs: SharedPreferences, key: String, value: Int) {
        prefs.edit().putInt("${key}_value", value.coerceIn(0, 100)).putLong("${key}_at", System.currentTimeMillis()).apply()
    }
}
