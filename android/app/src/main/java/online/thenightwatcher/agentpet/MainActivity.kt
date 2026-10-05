package online.thenightwatcher.agentpet

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.core.content.ContextCompat

/** Small pairing screen. The floating companion itself is owned by the service. */
class MainActivity : AppCompatActivity() {
    private lateinit var overlayStatus: TextView
    private var careStatus: TextView? = null
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PetOverlayService.ACTION_CARE_UPDATED) refreshCareStatus()
            else showOverlayStatus(intent.getStringExtra(PetOverlayService.EXTRA_RELAY_STATUS))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 56, 48, 56); setBackgroundColor(Color.rgb(17, 22, 32)) }
        fun heading(value: String) = TextView(this).apply { text = value; textSize = 19f; setTextColor(Color.rgb(143, 221, 104)); setPadding(0, 32, 0, 8) }
        fun choice(label: String, key: String, values: List<String>, default: String) {
            root.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE) })
            root.addView(Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values.map { it.replace('_', ' ') }); setSelection(values.indexOf(prefs.getString(key, default)).coerceAtLeast(0)); onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onNothingSelected(p: AdapterView<*>?) = Unit; override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { prefs.edit().putString(key, values[pos]).apply() } } })
        }
        root.addView(TextView(this).apply { text = "AgentPet"; textSize = 28f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text = "Android Tamagotchi control center"; setTextColor(Color.LTGRAY) })
        overlayStatus = TextView(this).apply { textSize = 15f; setPadding(16, 16, 16, 16) }
        root.addView(overlayStatus)
        val endpoint = EditText(this).apply { hint = "https://relay.example.com"; setText(prefs.getString("endpoint", "")) }
        val token = EditText(this).apply { hint = "Companion device token"; setText(prefs.getString("token", "")) }
        // Persist during entry, so a back press, overlay permission round-trip,
        // or process shutdown cannot discard an already paired device token.
        endpoint.doAfterTextChanged { prefs.edit().putString("endpoint", it?.toString()?.trim()?.removeSuffix("/") ?: "").apply() }
        token.doAfterTextChanged { prefs.edit().putString("token", it?.toString()?.trim() ?: "").apply() }
        root.addView(endpoint); root.addView(token)
        val petLabel = TextView(this).apply { text = "Pet: loading shared library…" }
        val petSearch = EditText(this).apply { hint = "Search pets by name" }
        val pets = Spinner(this)
        root.addView(petLabel); root.addView(petSearch); root.addView(pets)
        var allPets: List<RemotePet> = emptyList()
        var displayed: List<RemotePet> = emptyList()
        var applyingPetList = false
        fun renderPets(query: String) {
            val normalized = query.trim().lowercase()
            displayed = allPets.filter { normalized.isBlank() || it.name.lowercase().contains(normalized) || it.slug.lowercase().contains(normalized) }
            applyingPetList = true
            pets.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayed.map { it.name })
            val selected = prefs.getString("pet_sheet", "")
            pets.setSelection(displayed.indexOfFirst { it.spritesheetUrl == selected }.coerceAtLeast(0))
            applyingPetList = false
            val selectedPet = allPets.firstOrNull { it.spritesheetUrl == selected }
            petLabel.text = if (displayed.isEmpty()) "No pets match \"${petSearch.text}\"" else selectedPet?.let { "Selected pet: ${it.name} (${it.slug})\n${displayed.size} shown of ${allPets.size}" } ?: "Choose your animated pet (${displayed.size} shown of ${allPets.size})"
        }
        petSearch.doAfterTextChanged { renderPets(it?.toString() ?: "") }
        pets.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!applyingPetList && position in displayed.indices) {
                    val selected = displayed[position]
                    prefs.edit().putString("pet_sheet", selected.spritesheetUrl).putString("pet_name", selected.name).putString("pet_slug", selected.slug).apply()
                    petLabel.text = "Selected pet: ${selected.name} (${selected.slug})\n${displayed.size} shown of ${allPets.size}"
                    sendBroadcast(Intent(PetOverlayService.ACTION_PET_CHANGED).setPackage(packageName))
                }
            }
        }
        PetCatalog.load { loadedPets ->
            if (loadedPets.isEmpty()) { petLabel.text = "Pet library unavailable — the companion will retry."; return@load }
            allPets = loadedPets
            renderPets(petSearch.text.toString())
        }
        root.addView(heading("Pet & animation"))
        val sizeLabel = TextView(this)
        val size = SeekBar(this).apply { max = 180; progress = prefs.getInt("pet_size", 156).coerceIn(80, 260) - 80 }
        fun updateSizeLabel() { sizeLabel.text = "Pet size: ${size.progress + 80}px (takes effect when restarted)" }
        updateSizeLabel()
        size.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { prefs.edit().putInt("pet_size", value + 80).apply(); updateSizeLabel() }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit; override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        root.addView(sizeLabel); root.addView(size)
        val moods = listOf("idle", "working", "waiting", "done", "celebrate", "sleepy")
        val moodPicker = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, moods) }
        val clipPicker = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, (0..8).map { "Clip $it" }) }
        fun syncClip() { clipPicker.setSelection(prefs.getInt("clip_${moods[moodPicker.selectedItemPosition]}", moods.indexOf(moods[moodPicker.selectedItemPosition])).coerceIn(0, 8)) }
        moodPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener { override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit; override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = syncClip() }
        clipPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener { override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit; override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { prefs.edit().putInt("clip_${moods[moodPicker.selectedItemPosition]}", pos).apply() } }
        root.addView(TextView(this).apply { text = "Animation clip for mood (clamped if this pet has fewer clips)" }); root.addView(moodPicker); root.addView(clipPicker)
        root.addView(CheckBox(this).apply { text = "Animate pet"; isChecked = prefs.getBoolean("animations_enabled", true); setTextColor(Color.WHITE); setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("animations_enabled", checked).apply() } })
        val fpsLabel = TextView(this); val fps = SeekBar(this).apply { max = 11; progress = prefs.getInt("animation_fps", 5).coerceIn(1, 12) - 1 }; fun paintFps() { fpsLabel.text = "Animation speed: ${fps.progress + 1} fps" }; paintFps(); fps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { prefs.edit().putInt("animation_fps", p + 1).apply(); paintFps() }; override fun onStartTrackingTouch(s: SeekBar?) = Unit; override fun onStopTrackingTouch(s: SeekBar?) = Unit }); root.addView(fpsLabel); root.addView(fps)

        root.addView(heading("Bubble & agents"))
        root.addView(CheckBox(this).apply { text = "Multi-agent bubble"; isChecked = prefs.getBoolean("multi_agent_bubble", true); setTextColor(Color.WHITE); setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("multi_agent_bubble", checked).apply() } })
        root.addView(CheckBox(this).apply { text = "Reactive activity messages"; isChecked = prefs.getBoolean("reactive_bubbles", true); setTextColor(Color.WHITE); setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("reactive_bubbles", checked).apply() } })
        choice("Display mode", "bubble_mode", listOf("list", "carousel", "compact"), "carousel")
        choice("Session grouping", "bubble_grouping", listOf("byKind", "allSessions"), "byKind")
        choice("Minimum state", "bubble_min_state", listOf("all", "done_above", "working_waiting", "working"), "all")
        choice("Theme", "bubble_theme", listOf("system", "dark", "light"), "system")
        choice("Font size", "bubble_font", listOf("small", "medium", "large"), "medium")
        choice("State indicator", "bubble_dot", listOf("plain", "claude"), "plain")
        choice("Activity phrases", "activity_theme", listOf("chef", "engineer", "wizard", "explorer", "scientist"), "chef")
        val opacityLabel = TextView(this); val opacity = SeekBar(this).apply { max = 70; progress = prefs.getInt("bubble_opacity", 92).coerceIn(30, 100) - 30 }; fun paintOpacity() { opacityLabel.text = "Bubble opacity: ${opacity.progress + 30}%" }; paintOpacity(); opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { prefs.edit().putInt("bubble_opacity", p + 30).apply(); paintOpacity() }; override fun onStartTrackingTouch(s: SeekBar?) = Unit; override fun onStopTrackingTouch(s: SeekBar?) = Unit }); root.addView(opacityLabel); root.addView(opacity)
        listOf("idle", "working", "waiting", "done", "celebrate").forEach { state -> root.addView(EditText(this).apply { hint = "Custom $state message"; setText(prefs.getString("message_$state", "")); doAfterTextChanged { prefs.edit().putString("message_$state", it.toString()).apply() } }) }

        root.addView(heading("Android pet care"))
        val care = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(16, 16, 16, 16); setBackgroundColor(Color.rgb(34, 43, 60)) }; careStatus = care
        refreshCareStatus(); root.addView(care)
        root.addView(TextView(this).apply { text = "Real Claude and Codex token usage is queued in Cloudflare. This pet earns 1 XP per 5,000 consumed tokens, including usage accumulated while this phone is offline."; setTextColor(Color.LTGRAY); setPadding(8, 12, 8, 16) })
        root.addView(Button(this).apply { text = "Feed snack (+25K tokens)"; setOnClickListener { MobilePetCare.feed(this@MainActivity); refreshCareStatus() } })
        root.addView(Button(this).apply { text = "Play (+10 XP)"; setOnClickListener { MobilePetCare.play(this@MainActivity); refreshCareStatus() } })
        root.addView(Button(this).apply { text = "Reset Android care"; setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle("Reset Android care?").setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ -> MobilePetCare.reset(this@MainActivity); refreshCareStatus() }.show() } })

        root.addView(heading("Connection & history"))
        root.addView(Button(this).apply {
            text = "Allow overlay and start pet"
            setOnClickListener {
                prefs.edit().putString("endpoint", endpoint.text.toString().trim().removeSuffix("/")).putString("token", token.text.toString().trim()).apply()
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    Toast.makeText(this@MainActivity, "Allow \"Display over other apps\", then return and tap Start again.", Toast.LENGTH_LONG).show()
                } else startPet()
            }
        })
        root.addView(Button(this).apply { text = "Stop floating pet"; setOnClickListener { stopService(Intent(this@MainActivity, PetOverlayService::class.java)) } })
        root.addView(Button(this).apply { text = "Show cloud activity history"; setOnClickListener {
            RelayClient(endpoint.text.toString().trim().removeSuffix("/"), token.text.toString().trim(), onMessage = {}).fetchHistory { events -> runOnUiThread {
                val content = events?.joinToString("\n\n") { "${it.optString("agentKind", "agent")} · ${it.optString("eventName")}\n${it.optString("message", "")}" } ?: "Could not load history. Check pairing and connection."
                AlertDialog.Builder(this@MainActivity).setTitle("Recent cloud activity").setMessage(content.ifBlank { "No events yet." }).setPositiveButton("Close", null).show()
            } }
        } })
        root.addView(Button(this).apply {
            text = "Clear cloud activity history"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("Clear cloud logs?")
                    .setMessage("This permanently removes activity history from Cloudflare. Your pet profile and current popup state remain.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                        RelayClient(
                            endpoint = endpoint.text.toString().trim().removeSuffix("/"),
                            token = token.text.toString().trim(),
                            onMessage = {},
                        )
                            .clearLogs { ok -> runOnUiThread { Toast.makeText(this@MainActivity, if (ok) "Cloud history cleared" else "Could not clear history", Toast.LENGTH_SHORT).show() } }
                    }.show()
            }
        })
        // Reuse the already-wired controls above, but present them as real pages
        // instead of one endless settings form.
        val original = (0 until root.childCount).map(root::getChildAt)
        root.removeAllViews()
        val petPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bubblePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val carePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val historyPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val connectionPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var target = petPage
        original.drop(3).forEach { view ->
            val label = (view as? TextView)?.text?.toString().orEmpty()
            when {
                view === endpoint || view === token -> connectionPage.addView(view)
                view === petLabel || view === petSearch || view === pets -> petPage.addView(view)
                label == "Pet & animation" -> { target = petPage; target.addView(view) }
                label == "Bubble & agents" -> { target = bubblePage; target.addView(view) }
                label == "Android pet care" -> { target = carePage; target.addView(view) }
                label == "Connection & history" -> { target = connectionPage; target.addView(view) }
                view is Button && label.contains("history", ignoreCase = true) -> historyPage.addView(view)
                else -> target.addView(view)
            }
        }
        fun prepare(page: LinearLayout) {
            page.setPadding(40, 24, 40, 64)
            for (i in 0 until page.childCount) when (val child = page.getChildAt(i)) {
                is EditText -> { child.setTextColor(Color.WHITE); child.setHintTextColor(Color.GRAY) }
                is TextView -> if (child !is Button && child !is CheckBox) child.setTextColor(Color.WHITE)
            }
        }
        listOf(petPage, bubblePage, carePage, historyPage, connectionPage).forEach(::prepare)
        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(17, 22, 32)) }
        original.take(3).forEach(shell::addView)
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 4, 8, 4) }
        val tabScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabBar) }
        val pageHost = FrameLayout(this)
        shell.addView(tabScroll)
        shell.addView(pageHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val pages = listOf("Pet" to petPage, "Bubble" to bubblePage, "Care" to carePage, "History" to historyPage, "Connection" to connectionPage)
        val pageViews = pages.map { (_, page) -> ScrollView(this).apply { addView(page) } }
        val tabButtons = mutableListOf<Button>()
        fun select(index: Int) {
            pageHost.removeAllViews()
            pageHost.addView(pageViews[index])
            tabButtons.forEachIndexed { i, button -> button.setTextColor(if (i == index) Color.rgb(17, 22, 32) else Color.WHITE); button.setBackgroundColor(if (i == index) Color.rgb(143, 221, 104) else Color.rgb(38, 48, 66)) }
        }
        pages.forEachIndexed { index, pair -> tabBar.addView(Button(this).apply { text = pair.first; isAllCaps = false; setOnClickListener { select(index) }; tabButtons += this }) }
        setContentView(shell)
        select(0)
        showOverlayStatus()
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, statusReceiver, IntentFilter().apply { addAction(PetOverlayService.ACTION_RELAY_STATUS); addAction(PetOverlayService.ACTION_CARE_UPDATED) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (::overlayStatus.isInitialized) showOverlayStatus()
    }
    override fun onStop() { unregisterReceiver(statusReceiver); super.onStop() }
    private fun showOverlayStatus(liveStatus: String? = null) {
        val text = getSharedPreferences("overlay", MODE_PRIVATE).getString("last_error", "") ?: ""
        val connection = liveStatus ?: getSharedPreferences("relay", MODE_PRIVATE).getString("connection_status", "NOT CONNECTED — start the pet to connect")
        overlayStatus.text = if (text.isBlank()) "Cloudflare relay\n$connection" else "Overlay issue: $text"
    }
    private fun refreshCareStatus() {
        val s = MobilePetCare.state(this)
        careStatus?.text = "${s.stage} · Lv ${s.displayLevel} · ${s.hunger}\nXP ${s.xp} · ${s.progress}% · ${s.tokensToNextLevel} tokens to next level\nToday ${s.tokensToday} tokens · ${s.queriesToday} queries · ${s.mealsToday} sessions\nLifetime ${s.totalTokens} tokens · ${s.totalQueries} queries · ${s.totalMeals} sessions\nStreak ${s.streakDays} days\n\n${s.achievements.ifEmpty { listOf("No achievements yet") }.joinToString("\n")}" }
    private fun startPet() {
        try {
            startForegroundService(Intent(this, PetOverlayService::class.java))
            Toast.makeText(this, "Starting floating pet…", Toast.LENGTH_SHORT).show()
        } catch (error: SecurityException) {
            Toast.makeText(this, "Android blocked the overlay: allow Display over other apps and try again.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Couldn't start the floating pet: ${error.message ?: "unknown Android error"}", Toast.LENGTH_LONG).show()
        }
    }
}
