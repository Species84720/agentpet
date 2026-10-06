package online.thenightwatcher.agentpet

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import android.content.pm.PackageManager

/** Small pairing screen. The floating companion itself is owned by the service. */
class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_APPROVAL_ID = "approval_id"
        const val EXTRA_APPROVAL_TOOL = "approval_tool"
        const val EXTRA_APPROVAL_SUMMARY = "approval_summary"
        const val EXTRA_APPROVAL_PROJECT = "approval_project"
        const val EXTRA_OPEN_INPUTS = "open_inputs"
        const val EXTRA_OPEN_SETTINGS = "open_settings"
    }
    private lateinit var overlayStatus: TextView
    private var careStatus: TextView? = null
    private var updateStatus: TextView? = null
    private var inputList: LinearLayout? = null
    private var inputConnection: TextView? = null
    private var inputRequestStatus: String? = null
    private var notificationStatus: TextView? = null
    private var gameSummary: TextView? = null
    private var gameXpSummary: TextView? = null
    private var gameXpProgress: ProgressBar? = null
    private var gameActionSummary: TextView? = null
    private var gamePersonality: TextView? = null
    private var gameThought: TextView? = null
    private var gameFinds: TextView? = null
    private var gameJournal: TextView? = null
    private var gameAgentStatus: TextView? = null
    private var gameRewards: TextView? = null
    private var gameRoomCard: LinearLayout? = null
    private var gameDecor: TextView? = null
    private var brainBusy = false
    private var enableOverlayButton: Button? = null
    private var gameRuleStatus: TextView? = null
    private var gamePreview: PetSpriteView? = null
    private var gamePreviewCaption: TextView? = null
    private var gameReactionUntilAt = 0L
    private var activeMainTab = 0
    private val gameButtons = mutableMapOf<String, Button>()
    private val gameBars = mutableMapOf<String, ProgressBar>()
    private val gameBarLabels = mutableMapOf<String, TextView>()
    private var liveApprovals: List<org.json.JSONObject>? = null
    private var inputRelayClient: RelayClient? = null
    private var inputRelayEndpoint: String? = null
    private var inputRelayToken: String? = null
    private val inputRefreshHandler = Handler(Looper.getMainLooper())
    private val inputRefreshTask = object : Runnable {
        override fun run() {
            refreshApprovalRequests()
            inputRefreshHandler.postDelayed(this, 3_000)
        }
    }
    private var openTab: ((Int) -> Unit)? = null
    private val inputTabIndex = 1
    private val gameRefreshHandler = Handler(Looper.getMainLooper())
    private val gameRefreshTask = object : Runnable {
        override fun run() {
            refreshGameScreen()
            gameRefreshHandler.postDelayed(this, 10_000)
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshNotificationPermissionStatus()
        if (it) refreshApprovalRequests()
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == PetOverlayService.ACTION_CARE_UPDATED || intent.action == PetOverlayService.ACTION_AGENTS_UPDATED) refreshGameScreen()
            else if (intent.action == PetOverlayService.ACTION_APPROVALS_UPDATED) refreshInputs()
            else { showOverlayStatus(intent.getStringExtra(PetOverlayService.EXTRA_RELAY_STATUS)); refreshInputs() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 20, 20, 28); setBackgroundColor(Color.rgb(17, 22, 32)) }
        fun heading(value: String) = TextView(this).apply { text = value; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.rgb(143, 221, 104)); setPadding(0, 24, 0, 10) }
        fun choice(label: String, key: String, values: List<String>, default: String) {
            root.addView(TextView(this).apply { text = label; setTextColor(Color.WHITE) })
            root.addView(Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values.map { it.replace('_', ' ') }); setSelection(values.indexOf(prefs.getString(key, default)).coerceAtLeast(0)); onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onNothingSelected(p: AdapterView<*>?) = Unit; override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { prefs.edit().putString(key, values[pos]).apply() } } })
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12)
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_agentpet)
                contentDescription = "AgentPet logo"
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(56, 56).apply { marginEnd = 14 })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply { text = "AgentPet"; textSize = 28f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE) })
                addView(TextView(this@MainActivity).apply { text = "Your little coding companion"; setTextColor(Color.LTGRAY); setPadding(0, 2, 0, 0) })
            })
        })
        overlayStatus = TextView(this).apply { textSize = 14f; setPadding(16, 14, 16, 14); background = panelBackground() }
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
        val petPreview = PetSpriteView(this).apply { configureAnimation(true, 6) }
        gamePreview = petPreview
        val previewName = TextView(this).apply { text = prefs.getString("pet_name", "Choose a pet") ?: "Choose a pet"; textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER }
        val previewCaption = TextView(this).apply { text = "A little care goes a long way."; setTextColor(Color.LTGRAY); textSize = 13f; gravity = Gravity.CENTER }
        gamePreviewCaption = previewCaption
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(16, 12, 16, 16); background = panelBackground()
            addView(previewCaption)
            addView(petPreview, LinearLayout.LayoutParams(220, 190).apply { gravity = Gravity.CENTER })
            addView(previewName)
            gameDecor = TextView(this@MainActivity).apply { textSize = 25f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }.also(::addView)
        }
        gameRoomCard = previewCard
        root.addView(previewCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 12, 0, 12) })
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
                    previewName.text = selected.name
                    petPreview.load(selected.spritesheetUrl) { loaded -> if (!loaded) previewCaption.text = "Preview could not be loaded" }
                    sendBroadcast(Intent(PetOverlayService.ACTION_PET_CHANGED).setPackage(packageName))
                }
            }
        }
        PetCatalog.load { loadedPets ->
            if (loadedPets.isEmpty()) { petLabel.text = "Pet library unavailable — the companion will retry."; return@load }
            allPets = loadedPets
            renderPets(petSearch.text.toString())
            val chosen = allPets.firstOrNull { it.spritesheetUrl == prefs.getString("pet_sheet", "") } ?: allPets.firstOrNull()
            chosen?.let {
                if (prefs.getString("pet_sheet", "").isNullOrBlank()) prefs.edit().putString("pet_sheet", it.spritesheetUrl).putString("pet_name", it.name).putString("pet_slug", it.slug).apply()
                previewName.text = it.name
                petPreview.load(it.spritesheetUrl) { loaded -> if (!loaded) previewCaption.text = "Preview could not be loaded" }
            }
        }
        root.addView(heading("Pet & animation"))
        val sizeLabel = TextView(this)
        val size = SeekBar(this).apply { max = 180; progress = prefs.getInt("pet_size", 156).coerceIn(80, 260) - 80 }
        fun updateSizeLabel() { sizeLabel.text = "Pet size: ${size.progress + 80}px (applied immediately)" }
        updateSizeLabel()
        size.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { prefs.edit().putInt("pet_size", value + 80).apply(); updateSizeLabel() }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit; override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        root.addView(sizeLabel); root.addView(size)
        val moods = listOf("idle", "working", "waiting", "done", "celebrate", "sleepy")
        val moodPicker = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, moods) }
        val clipPicker = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, (0..8).map { "Clip $it" }) }
        fun previewSelectedClip() {
            val mood = moods.getOrElse(moodPicker.selectedItemPosition) { "idle" }
            val bindings = moods.associateWith { prefs.getInt("clip_$it", moods.indexOf(it)) }.toMutableMap()
            bindings[mood] = clipPicker.selectedItemPosition.coerceIn(0, 8)
            petPreview.setClipBindings(bindings)
            petPreview.setMood(mood)
            previewCaption.text = "Previewing $mood · Clip ${clipPicker.selectedItemPosition}"
        }
        fun syncClip() {
            val mood = moods.getOrElse(moodPicker.selectedItemPosition) { "idle" }
            clipPicker.setSelection(prefs.getInt("clip_$mood", moods.indexOf(mood)).coerceIn(0, 8))
            previewSelectedClip()
        }
        moodPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener { override fun onNothingSelected(p: AdapterView<*>?) = Unit; override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) = syncClip() }
        clipPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener { override fun onNothingSelected(p: AdapterView<*>?) = Unit; override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { val mood = moods.getOrElse(moodPicker.selectedItemPosition) { "idle" }; prefs.edit().putInt("clip_$mood", pos).apply(); previewSelectedClip() } }
        root.addView(TextView(this).apply { text = "Animation clip for mood (clamped if this pet has fewer clips)" }); root.addView(moodPicker); root.addView(clipPicker)
        root.addView(CheckBox(this).apply { text = "Animate pet"; isChecked = prefs.getBoolean("animations_enabled", true); setTextColor(Color.WHITE); setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("animations_enabled", checked).apply(); petPreview.configureAnimation(checked, prefs.getInt("animation_fps", 5)) } })
        val fpsLabel = TextView(this); val fps = SeekBar(this).apply { max = 11; progress = prefs.getInt("animation_fps", 5).coerceIn(1, 12) - 1 }; fun paintFps() { fpsLabel.text = "Animation speed: ${fps.progress + 1} fps" }; paintFps(); fps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { prefs.edit().putInt("animation_fps", p + 1).apply(); paintFps(); petPreview.configureAnimation(prefs.getBoolean("animations_enabled", true), p + 1) }; override fun onStartTrackingTouch(s: SeekBar?) = Unit; override fun onStopTrackingTouch(s: SeekBar?) = Unit }); root.addView(fpsLabel); root.addView(fps)
        petPreview.setClipBindings(moods.associateWith { prefs.getInt("clip_$it", moods.indexOf(it)) })
        petPreview.configureAnimation(prefs.getBoolean("animations_enabled", true), prefs.getInt("animation_fps", 5))

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
        root.addView(Button(this).apply { text = "Reset Android care"; setOnClickListener { AlertDialog.Builder(this@MainActivity).setTitle("Reset Android care?").setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ -> MobilePetCare.reset(this@MainActivity); refreshCareStatus() }.show() } })

        root.addView(heading("Connection & history"))
        root.addView(Button(this).apply {
            text = "Allow overlay and start pet"
            setOnClickListener {
                prefs.edit().putString("endpoint", endpoint.text.toString().trim().removeSuffix("/")).putString("token", token.text.toString().trim()).apply()
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    Toast.makeText(this@MainActivity, "Allow \"Display over other apps\", then return. Your pet will start automatically.", Toast.LENGTH_LONG).show()
                } else startPet()
            }
        })
        root.addView(Button(this).apply { text = "Stop floating pet"; setOnClickListener { stopService(Intent(this@MainActivity, PetOverlayService::class.java)) } })
        root.addView(Button(this).apply {
            text = "Clear pet AI memories"
            setOnClickListener {
                val url = endpoint.text.toString().trim(); val secret = token.text.toString().trim()
                if (Uri.parse(url).scheme !in setOf("http", "https") || Uri.parse(url).host.isNullOrBlank() || secret.isBlank()) {
                    Toast.makeText(this@MainActivity, "Pair your pet first.", Toast.LENGTH_LONG).show(); return@setOnClickListener
                }
                AlertDialog.Builder(this@MainActivity).setTitle("Clear AI memories?")
                    .setMessage("Your pet's cloud conversation memories will be deleted. Local care and unlocks stay saved.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                        val relay = RelayClient(url, secret, onMessage = {})
                        relay.clearPetMemories { ok -> runOnUiThread {
                            Toast.makeText(this@MainActivity, if (ok) "AI memories cleared" else "Could not clear memories", Toast.LENGTH_LONG).show()
                            relay.close()
                        } }
                    }.show()
            }
        })
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
        val gamePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 28) }
        val petPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bubblePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val carePage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val historyPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inputsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val connectionPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inputsPage.addView(TextView(this).apply { text = "Requests waiting for your decision"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.rgb(143, 221, 104)); setPadding(0, 8, 0, 8) })
        inputConnection = TextView(this).apply { setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 12) }
        inputsPage.addView(inputConnection)
        inputsPage.addView(Button(this).apply {
            text = "Refresh requests now"
            setOnClickListener { refreshApprovalRequests() }
        })
        inputList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inputsPage.addView(inputList)
        gamePage.addView(TextView(this).apply {
            text = "Your companion"
            textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(4, 4, 4, 2)
        })
        gamePage.addView(TextView(this).apply {
            text = "A tiny friend, a little daily care."
            textSize = 14f; setTextColor(Color.LTGRAY); setPadding(4, 0, 4, 8)
        })
        gameAgentStatus = TextView(this).apply {
            textSize = 14f; setTextColor(Color.rgb(143, 221, 104)); setPadding(8, 6, 8, 10)
        }.also(gamePage::addView)
        enableOverlayButton = Button(this).apply {
            text = "Enable floating pet"; styleMenuButton(this)
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }.also { gamePage.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8); bottomMargin = dp(16) }) }
        var target = petPage
        original.drop(1).forEach { view ->
            val label = (view as? TextView)?.text?.toString().orEmpty()
            when {
                view === endpoint || view === token || view === overlayStatus -> connectionPage.addView(view)
                view === petLabel || view === petSearch || view === pets -> petPage.addView(view)
                view === previewCard -> gamePage.addView(view)
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
                is Button -> {
                    styleMenuButton(child)
                    child.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) }
                }
                is TextView -> if (child !is Button && child !is CheckBox) child.setTextColor(Color.WHITE)
            }
        }
        listOf(petPage, bubblePage, carePage, historyPage, inputsPage, connectionPage).forEach(::prepare)

        val characterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14); background = panelBackground()
        }
        gamePersonality = TextView(this).apply {
            textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
        }.also(characterCard::addView)
        gameThought = TextView(this).apply {
            textSize = 15f; setTextColor(Color.rgb(225, 235, 247)); setPadding(0, 8, 0, 8)
        }.also(characterCard::addView)
        gameFinds = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(255, 197, 91))
        }.also(characterCard::addView)
        gamePage.addView(characterCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })

        val needsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 16); background = panelBackground()
        }
        needsCard.addView(TextView(this).apply {
            text = "Daily needs"; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE); setPadding(0, 0, 0, 8)
        })
        fun needRow(key: String, title: String, tint: Int) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 6, 0, 6) }
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = TextView(this).apply { text = title; textSize = 13f; setTextColor(Color.WHITE) }
            val value = TextView(this).apply { textSize = 12f; setTextColor(Color.LTGRAY); gravity = Gravity.END }
            line.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(value)
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = 50; progressTintList = android.content.res.ColorStateList.valueOf(tint)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(54, 64, 79))
            }
            row.addView(line)
            row.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 9).apply { topMargin = 6 })
            needsCard.addView(row)
            gameBars[key] = bar
            gameBarLabels[key] = value
        }
        needRow("hunger", "🍓  Hunger", Color.rgb(255, 180, 77))
        needRow("happiness", "✨  Happiness", Color.rgb(244, 119, 190))
        needRow("cleanliness", "🫧  Cleanliness", Color.rgb(102, 203, 226))
        needRow("energy", "⚡  Energy", Color.rgb(143, 221, 104))
        needRow("bond", "💗  Friendship", Color.rgb(255, 126, 146))
        gamePage.addView(needsCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })

        val progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 14); background = panelBackground()
        }
        gameSummary = TextView(this).apply { textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE) }
        gameXpSummary = TextView(this).apply { textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, 4, 0, 6) }
        gameXpProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(143, 221, 104))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(54, 64, 79))
        }
        progressCard.addView(gameSummary)
        progressCard.addView(gameXpSummary)
        progressCard.addView(gameXpProgress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8))
        gamePage.addView(progressCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 10 })

        gameActionSummary = TextView(this).apply { textSize = 12f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(4, 8, 4, 2) }
        gamePage.addView(gameActionSummary)
        gameRuleStatus = TextView(this).apply { textSize = 13f; setTextColor(Color.rgb(255, 197, 91)); gravity = Gravity.CENTER; setPadding(8, 8, 8, 2) }
        gamePage.addView(gameRuleStatus)
        val actionGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 6, 0, 0) }
        val reactionHandler = Handler(Looper.getMainLooper())
        var resetReaction: Runnable? = null
        fun react(mood: String, message: String) {
            previewCaption.text = message
            petPreview.setMood(mood)
            gameReactionUntilAt = System.currentTimeMillis() + 2_200
            resetReaction?.let(reactionHandler::removeCallbacks)
            resetReaction = Runnable {
                gameReactionUntilAt = 0L
                refreshGameScreen()
            }
                .also { reactionHandler.postDelayed(it, 2_200) }
        }
        fun reactTo(reaction: MobilePetGame.Reaction) {
            react(reaction.mood, reaction.message)
            sendBroadcast(Intent(PetOverlayService.ACTION_GAME_CHANGED).setPackage(packageName))
            refreshGameScreen()
        }
        fun askAi() {
            val current = MobilePetGame.state(this)
            if (current.sleeping || brainBusy) return
            val endpointUrl = prefs.getString("endpoint", "").orEmpty()
            val deviceToken = prefs.getString("token", "").orEmpty()
            val uri = Uri.parse(endpointUrl)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || deviceToken.isBlank()) {
                Toast.makeText(this, "Pair your pet in Settings → Relay first.", Toast.LENGTH_LONG).show(); return
            }
            val message = EditText(this).apply { hint = "Tell your pet something…"; maxLines = 4 }
            AlertDialog.Builder(this).setTitle("Talk to your AI pet").setView(message)
                .setNegativeButton("Cancel", null).setPositiveButton("Send") { _, _ ->
                    val text = message.text.toString().trim()
                    if (text.isNotEmpty() && text.length <= 500 && !MobilePetGame.state(this).sleeping) {
                        brainBusy = true; previewCaption.text = "Your pet is thinking…"; refreshGameScreen()
                        val relay = RelayClient(endpointUrl, deviceToken, onMessage = {})
                        relay.askPetBrain(text, MobilePetGame.brainContext(this)) { answer, error -> runOnUiThread {
                            brainBusy = false
                            if (answer != null) reactTo(MobilePetGame.rememberAi(this, answer.optString("message"), answer.optString("mood")))
                            else { Toast.makeText(this, error ?: "The AI could not answer.", Toast.LENGTH_LONG).show(); refreshGameScreen() }
                            relay.close()
                        } }
                    } else Toast.makeText(this, "Enter a message of 1–500 characters while your pet is awake.", Toast.LENGTH_LONG).show()
                }.show()
        }
        fun gameButton(key: String, label: String, emoji: String, action: () -> Unit): Button = Button(this).apply {
            text = "$emoji  $label"; isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
            styleMenuButton(this)
            background = buttonSurface(Color.rgb(36, 54, 67), Color.rgb(88, 122, 146))
            setOnClickListener { action() }
            gameButtons[key] = this
        }
        fun actionRow(first: Button, second: Button) {
            actionGrid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(first, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginEnd = dp(6) })
                addView(second, LinearLayout.LayoutParams(0, dp(60), 1f).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        actionRow(gameButton("feed", "Feed", "🍓") { reactTo(MobilePetGame.feed(this)) },
            gameButton("play", "Find the star", "⭐") {
                val hidingPlace = kotlin.random.Random.nextInt(3)
                AlertDialog.Builder(this@MainActivity).setTitle("Where did your pet hide the star?")
                    .setItems(arrayOf("Left cup", "Middle cup", "Right cup")) { _, choice ->
                        reactTo(MobilePetGame.guess(this@MainActivity, choice, hidingPlace))
                    }.show()
            })
        actionRow(gameButton("groom", "Groom", "🫧") { reactTo(MobilePetGame.groom(this)) },
            gameButton("nap", "Nap", "💤") { reactTo(MobilePetGame.rest(this)) })
        actionRow(gameButton("explore", "Explore", "🧭") {
            AlertDialog.Builder(this@MainActivity).setTitle("Where should we explore?")
                .setItems(arrayOf("Garden", "Attic", "Rooftop")) { _, destination ->
                    reactTo(MobilePetGame.explore(this@MainActivity, destination))
                }.show()
        }, gameButton("talk", "Talk", "💬") { reactTo(MobilePetGame.talk(this)) })
        gameRewards = TextView(this).apply {
            textSize = 14f; setTextColor(Color.rgb(255, 210, 120)); setPadding(dp(8), dp(10), dp(8), dp(14))
        }.also(gamePage::addView)
        actionRow(gameButton("room", "Decorate", "🏡") {
            val rooms = MobilePetGame.Room.values()
            val bond = MobilePetGame.state(this).bestBond
            AlertDialog.Builder(this).setTitle("Choose your pet's room")
                .setItems(rooms.map { if (bond >= it.bondRequired) it.title else "🔒 ${it.title} · ${it.bondRequired}% friendship" }.toTypedArray()) { _, index ->
                    if (!MobilePetGame.chooseRoom(this, rooms[index])) Toast.makeText(this, "Care for your pet to grow your friendship and unlock this room.", Toast.LENGTH_LONG).show()
                    refreshGameScreen()
                }.show()
        }, gameButton("toys", "Toys", "🧸") {
            val toys = MobilePetGame.Toy.values(); val bond = MobilePetGame.state(this).bestBond
            AlertDialog.Builder(this).setTitle("Toy box")
                .setItems(toys.map { if (bond >= it.bondRequired) it.title else "🔒 ${it.title} · ${it.bondRequired}% friendship" }.toTypedArray()) { _, index -> reactTo(MobilePetGame.playWithToy(this, toys[index])) }.show()
        })
        actionRow(gameButton("tricks", "Tricks", "🎩") {
            val tricks = MobilePetGame.Trick.values(); val bond = MobilePetGame.state(this).bestBond
            AlertDialog.Builder(this).setTitle("Learned tricks")
                .setItems(tricks.map { if (bond >= it.bondRequired) it.title else "🔒 ${it.title} · ${it.bondRequired}% friendship" }.toTypedArray()) { _, index -> reactTo(MobilePetGame.performTrick(this, tricks[index])) }.show()
        }, gameButton("ai", "Ask AI", "💭") { askAi() })
        gamePage.addView(actionGrid)
        val journalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14); background = panelBackground()
        }
        journalCard.addView(TextView(this).apply {
            text = "Little moments"; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
        })
        gameJournal = TextView(this).apply {
            textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, 8, 0, 0)
        }.also(journalCard::addView)
        gamePage.addView(journalCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 12 })
        gamePage.addView(TextView(this).apply {
            text = "Your companion has its own little life, even while you're away. AI usage still earns XP and levels."
            textSize = 12f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(8, 12, 8, 2)
        })

        val shell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(17, 22, 32)) }
        original.firstOrNull()?.let(shell::addView)
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(16)) }
        val tabButtons = mutableListOf<Button>()
        val mainTabs = listOf("Play", "Requests", "Settings")
        var selectMain: (Int) -> Unit = {}
        mainTabs.forEachIndexed { index, title ->
            tabBar.addView(Button(this).apply {
                text = title; styleMenuButton(this); minWidth = 0; textSize = 14f
                setOnClickListener { selectMain(index) }
                tabButtons += this
            }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
        val pageHost = FrameLayout(this)
        shell.addView(tabBar)
        shell.addView(pageHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val settingsPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val settingsTabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(4), dp(12), dp(16)) }
        val settingsTabButtons = mutableListOf<Button>()
        val settingsHost = FrameLayout(this)
        val settingPages = listOf("Pet" to petPage, "Bubble" to bubblePage, "Care" to carePage, "History" to historyPage, "Relay" to connectionPage)
        val settingViews = settingPages.map { (_, page) -> ScrollView(this).apply { addView(page) } }
        settingsPage.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(settingsTabBar) })
        settingsPage.addView(settingsHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        fun selectSetting(index: Int) {
            settingsHost.removeAllViews()
            settingsHost.addView(settingViews[index])
            settingsTabButtons.forEachIndexed { i, button ->
                button.setTextColor(if (i == index) Color.rgb(17, 22, 32) else Color.WHITE)
                button.isSelected = i == index
                button.setBackground(if (i == index) tabSelectedBackground() else tabBackground())
            }
        }
        settingPages.forEachIndexed { index, pair ->
            settingsTabBar.addView(Button(this).apply {
                text = pair.first; styleMenuButton(this); minWidth = dp(76); textSize = 14f
                setOnClickListener { selectSetting(index) }; settingsTabButtons += this
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(10) })
        }
        val mainPages = listOf("Play" to ScrollView(this).apply { addView(gamePage) }, "Requests" to ScrollView(this).apply { addView(inputsPage) }, "Settings" to settingsPage)
        selectMain = { index ->
            activeMainTab = index
            pageHost.removeAllViews()
            pageHost.addView(mainPages[index].second)
            if (index == 0) refreshGameScreen()
            tabButtons.forEachIndexed { i, button ->
                button.setTextColor(if (i == index) Color.rgb(17, 22, 32) else Color.WHITE)
                button.isSelected = i == index
                button.setBackground(if (i == index) tabSelectedBackground() else tabBackground())
            }
        }
        openTab = { index -> selectMain(if (index == inputTabIndex) 1 else index.coerceIn(0, 2)) }
        selectSetting(0)
        connectionPage.addView(TextView(this).apply {
            text = "AgentPet ${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 20, 0, 4)
        })
        updateStatus = TextView(this).apply {
            text = "Checks for updates automatically when the app opens. Android will ask before installing."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 8)
        }
        connectionPage.addView(updateStatus)
        notificationStatus = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, 16, 0, 4)
        }
        connectionPage.addView(notificationStatus)
        connectionPage.addView(Button(this).apply {
            text = "Enable approval notifications"
            styleMenuButton(this)
            setOnClickListener { requestNotificationPermissionOrSettings() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8); bottomMargin = dp(12) })
        refreshNotificationPermissionStatus()
        connectionPage.addView(Button(this).apply {
            text = "Check for updates"
            styleMenuButton(this)
            setOnClickListener { checkForUpdates(force = true) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8); bottomMargin = dp(12) })
        setContentView(shell)
        window.decorView.post {
            val prefs = getSharedPreferences("relay", MODE_PRIVATE)
            if (Build.VERSION.SDK_INT >= 33 && !ApprovalNotifications.enabled(this) && !prefs.getBoolean("approval_notifications_prompted", false)) {
                prefs.edit().putBoolean("approval_notifications_prompted", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        refreshInputs()
        selectMain(when {
            intent.getBooleanExtra(EXTRA_OPEN_INPUTS, false) || intent.hasExtra(EXTRA_APPROVAL_ID) -> inputTabIndex
            intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) -> 2
            else -> 0
        })
        refreshGameScreen()
        showOverlayStatus()
        showApprovalIfRequested(intent)
        checkForUpdates(force = false, showPrompt = intent.getStringExtra(EXTRA_APPROVAL_ID).isNullOrBlank() && !intent.getBooleanExtra(EXTRA_OPEN_INPUTS, false) && !intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false))
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showApprovalIfRequested(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_INPUTS, false)) openTab?.invoke(inputTabIndex)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) openTab?.invoke(2)
        if (intent.getBooleanExtra(EXTRA_OPEN_INPUTS, false) || intent.hasExtra(EXTRA_APPROVAL_ID)) refreshApprovalRequests()
    }
    private fun showApprovalIfRequested(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_APPROVAL_ID)?.takeIf(String::isNotBlank) ?: return
        intent.removeExtra(EXTRA_APPROVAL_ID)
        openTab?.invoke(inputTabIndex)
        refreshApprovalRequests()
    }
    private fun refreshNotificationPermissionStatus() {
        notificationStatus?.text = if (ApprovalNotifications.enabled(this))
            "Approval notifications are enabled. New requests will appear in the phone’s notification shade."
        else
            "Approval notifications are disabled. Enable them so requests can alert you while AgentPet is in the background."
    }
    private fun requestNotificationPermissionOrSettings() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("relay", MODE_PRIVATE)
            val askedBefore = prefs.getBoolean("approval_notifications_prompted", false)
            if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else {
                prefs.edit().putBoolean("approval_notifications_prompted", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }
    private fun submitApprovalDecision(requestId: String, decision: String) {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val relay = RelayClient(prefs.getString("endpoint", "") ?: "", prefs.getString("token", "") ?: "", {})
        relay.submitApprovalDecision(requestId, decision) { result -> runOnUiThread {
            if (result == null) Toast.makeText(this, "Could not send approval — check relay connection", Toast.LENGTH_LONG).show()
            else if (result.optString("state") == "expired") Toast.makeText(this, "Approval window ended — answer in Codex", Toast.LENGTH_LONG).show()
            else if (!result.optBoolean("accepted", true)) Toast.makeText(this, "Already answered: ${result.optString("decision")}", Toast.LENGTH_LONG).show()
            else Toast.makeText(this, if (decision == "allow") "Allowed — Codex will continue" else "Denied — Codex will continue", Toast.LENGTH_SHORT).show()
            if (result != null && (result.optBoolean("accepted") || result.optString("state") == "expired")) {
                ApprovalInbox.remove(this, requestId)
                refreshApprovalRequests()
            }
        } }
    }
    private fun refreshApprovalRequests() {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (endpoint.isBlank() || token.isBlank()) {
            inputRelayClient?.close()
            inputRelayClient = null
            inputRelayEndpoint = endpoint
            inputRelayToken = token
            inputRequestStatus = "Relay URL or companion token is missing"
            liveApprovals = null
            refreshInputs()
            return
        }
        if (inputRelayClient == null || inputRelayEndpoint != endpoint || inputRelayToken != token) {
            inputRelayClient?.close()
            inputRelayEndpoint = endpoint
            inputRelayToken = token
            inputRelayClient = RelayClient(endpoint, token, {})
        }
        val relay = inputRelayClient ?: return
        inputRequestStatus = "Checking Cloudflare approval inbox…"
        refreshInputs()
        relay.fetchPendingApprovals { approvals, error -> runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            inputRequestStatus = error ?: "Inbox reachable · checked just now"
            if (approvals != null) {
                liveApprovals = approvals
                val activeIds = approvals.map { it.optString("requestId") }.filter(String::isNotBlank).toSet()
                ApprovalInbox.all(this).map { it.optString("requestId") }.filterNot(activeIds::contains).forEach {
                    ApprovalInbox.remove(this, it)
                    ApprovalNotifications.cancel(this, it)
                }
                approvals.forEach { ApprovalInbox.put(this, it); ApprovalNotifications.show(this, it) }
            }
            refreshInputs()
        } }
    }
    private fun refreshInputs() {
        val host = inputList ?: return
        host.removeAllViews()
        val connected = getSharedPreferences("relay", MODE_PRIVATE).getString("connection_status", "Not connected") ?: "Not connected"
        val status = inputRequestStatus ?: getSharedPreferences("relay", MODE_PRIVATE).getString("approval_poll_status", "Not checked yet")
        val pollFailed = status?.startsWith("HTTP ") == true || listOf("failed", "missing", "invalid").any { status?.contains(it, true) == true }
        inputConnection?.text = "Relay: $connected\nApprovals: $status"
        inputConnection?.setTextColor(if (pollFailed) Color.rgb(255, 130, 120) else Color.LTGRAY)
        val approvals = liveApprovals ?: ApprovalInbox.all(this)
        if (approvals.isEmpty()) {
            host.addView(TextView(this).apply {
                text = if (pollFailed)
                    "Could not load requests: $status\nCheck the companion token and relay URL above."
                else "No pending requests. This inbox polls Cloudflare while the tab is open; use Refresh requests now to check immediately."
                setTextColor(Color.LTGRAY); textSize = 15f; setPadding(16, 18, 16, 18); background = panelBackground()
            })
            return
        }
        approvals.forEach { approval ->
            val id = approval.optString("requestId")
            val session = approval.optString("sessionId").ifBlank { "Unknown session" }
            val project = approval.optString("project").ifBlank { "Project not supplied" }
            val expires = approval.optLong("expiresAt")
            val remaining = ((expires - System.currentTimeMillis()).coerceAtLeast(0) / 1000)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14); background = panelBackground()
            }
            card.addView(TextView(this).apply {
                text = "${approval.optString("agentKind", "Agent")} · ${approval.optString("toolName", "Action")}"
                textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            })
            card.addView(TextView(this).apply {
                text = "Project: $project\nSession: $session\nExpires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"
                setTextColor(Color.LTGRAY); setPadding(0, 8, 0, 8)
            })
            val summary = approval.optString("summary")
            if (summary.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "Request details"; setTextColor(Color.LTGRAY); textSize = 12f
                    setPadding(0, 4, 0, 2)
                })
                card.addView(TextView(this).apply {
                    text = summary; setTextColor(Color.WHITE); textSize = 14f
                    maxLines = 100; setTextIsSelectable(true); setPadding(0, 0, 0, 10)
                })
            }
            val execution = approval.optString("execution")
            if (execution.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = "Command / action to execute"; setTextColor(Color.rgb(255, 197, 91)); textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 6, 0, 4)
                })
                card.addView(TextView(this).apply {
                    text = execution; setTextColor(Color.WHITE); textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                    maxLines = 200; setTextIsSelectable(true); setPadding(10, 10, 10, 10)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.rgb(24, 31, 44)); cornerRadius = 10f
                    }
                })
            } else {
                card.addView(TextView(this).apply {
                    text = "The agent did not provide an exact command or action preview. Do not approve unless you can verify the requested action in the agent’s own prompt."
                    setTextColor(Color.rgb(255, 197, 91)); textSize = 14f; setPadding(0, 8, 0, 10)
                })
            }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                addView(Button(this@MainActivity).apply {
                    text = "Reject"; styleMenuButton(this)
                    background = buttonSurface(Color.rgb(68, 37, 45), Color.rgb(196, 102, 115))
                    setOnClickListener { submitApprovalDecision(id, "deny") }
                }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(6); topMargin = dp(12) })
                addView(Button(this@MainActivity).apply {
                    text = "Approve"; styleMenuButton(this)
                    setTextColor(Color.rgb(17, 22, 32)); background = tabSelectedBackground()
                    setOnClickListener { submitApprovalDecision(id, "allow") }
                }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6); topMargin = dp(12) })
            })
            host.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })
        }
    }
    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, statusReceiver, IntentFilter().apply { addAction(PetOverlayService.ACTION_RELAY_STATUS); addAction(PetOverlayService.ACTION_CARE_UPDATED); addAction(PetOverlayService.ACTION_APPROVALS_UPDATED); addAction(PetOverlayService.ACTION_AGENTS_UPDATED) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (::overlayStatus.isInitialized) showOverlayStatus()
        refreshInputs()
        inputRelayClient?.close()
        inputRelayClient = null
        inputRefreshHandler.removeCallbacks(inputRefreshTask)
        refreshApprovalRequests()
        inputRefreshHandler.postDelayed(inputRefreshTask, 3_000)
        refreshGameScreen()
        gameRefreshHandler.removeCallbacks(gameRefreshTask)
        gameRefreshHandler.postDelayed(gameRefreshTask, 10_000)
    }
    override fun onResume() {
        super.onResume()
        val overlayAllowed = Settings.canDrawOverlays(this)
        enableOverlayButton?.visibility = if (overlayAllowed) android.view.View.GONE else android.view.View.VISIBLE
        if (overlayAllowed) startPet(showToast = false)
        refreshGameScreen()
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val build = prefs.getInt("pending_update_build", 0)
        val url = prefs.getString("pending_update_url", null)
        if (build > 0 && !url.isNullOrBlank()) {
            prefs.edit().remove("pending_update_build").remove("pending_update_url").apply()
            if (AndroidUpdater.canInstallPackages(this)) {
                beginUpdate(AndroidUpdater.Update(build, "${BuildConfig.VERSION_NAME.substringBefore("-build")}-build$build", url))
            } else {
                updateStatus?.text = "Install permission wasn’t enabled. Tap Check for updates to try again."
            }
        }
    }
    override fun onStop() {
        gameRefreshHandler.removeCallbacks(gameRefreshTask)
        inputRefreshHandler.removeCallbacks(inputRefreshTask)
        inputRelayClient?.close()
        inputRelayClient = null
        unregisterReceiver(statusReceiver)
        super.onStop()
    }
    private fun checkForUpdates(force: Boolean, showPrompt: Boolean = true) {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("last_update_check", 0L) < 6 * 60 * 60 * 1000L) return
        updateStatus?.text = "Checking GitHub for updates…"
        AndroidUpdater.check { update, error -> runOnUiThread {
            if (error == null) prefs.edit().putLong("last_update_check", now).apply()
            if (error != null) {
                updateStatus?.text = "Update check failed: $error"
            } else if (update == null) {
                updateStatus?.text = "You’re up to date · build ${BuildConfig.VERSION_CODE}"
            } else {
                updateStatus?.text = "Update available · build ${update.build}"
                if (!showPrompt) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle("AgentPet update available")
                    .setMessage("${update.versionName} is ready to download. Android will ask you to confirm installation.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Download & install") { _, _ -> beginUpdate(update) }
                    .show()
            }
        } }
    }
    private fun beginUpdate(update: AndroidUpdater.Update) {
        if (!AndroidUpdater.canInstallPackages(this)) {
            getSharedPreferences("relay", MODE_PRIVATE).edit()
                .putInt("pending_update_build", update.build)
                .putString("pending_update_url", update.downloadUrl)
                .apply()
            updateStatus?.text = "Allow AgentPet to install updates, then return here."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateStatus?.text = "Downloading build ${update.build}…"
        AndroidUpdater.downloadAndInstall(this, update) { error -> runOnUiThread {
            updateStatus?.text = if (error == null) "Downloaded · opening Android installer…" else "Update failed: $error"
            if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        } }
    }
    private fun showOverlayStatus(liveStatus: String? = null) {
        val text = getSharedPreferences("overlay", MODE_PRIVATE).getString("last_error", "") ?: ""
        val connection = liveStatus ?: getSharedPreferences("relay", MODE_PRIVATE).getString("connection_status", "NOT CONNECTED — start the pet to connect")
        overlayStatus.text = if (text.isBlank()) "Cloudflare relay\n$connection" else "Overlay issue: $text"
    }
    private fun refreshCareStatus() {
        val s = MobilePetCare.state(this)
        careStatus?.text = "${s.stage} · Lv ${s.displayLevel} · ${s.hunger}\nXP ${s.xp} · ${s.progress}% · ${s.tokensToNextLevel} tokens to next level\nToday ${s.tokensToday} tokens · ${s.queriesToday} requests · ${s.mealsToday} sessions\nLifetime ${s.totalTokens} tokens · ${s.totalQueries} requests · ${s.totalMeals} sessions\nStreak ${s.streakDays} days\n\n${s.achievements.ifEmpty { listOf("No achievements yet") }.joinToString("\n")}" }
    private fun refreshGameScreen() {
        val game = MobilePetGame.state(this)
        val activeAgents = getSharedPreferences("relay", MODE_PRIVATE).getInt("active_agent_count", 0)
        gameAgentStatus?.text = when (activeAgents) {
            0 -> "No active agents · your pet is relaxing"
            1 -> "1 agent active"
            else -> "$activeAgents agents active together"
        }
        val needs = listOf(
            "hunger" to (game.hunger to game.hungerLabel),
            "happiness" to (game.happiness to game.happinessLabel),
            "cleanliness" to (game.cleanliness to game.cleanlinessLabel),
            "energy" to (game.energy to game.energyLabel),
            "bond" to (game.bond to "Friends"),
        )
        needs.forEach { (key, pair) ->
            gameBars[key]?.progress = pair.first
            gameBarLabels[key]?.text = "${pair.second} · ${pair.first}%"
        }
        val care = MobilePetCare.state(this)
        gameSummary?.text = "${care.stage}  ·  Level ${care.displayLevel}"
        gameXpSummary?.text = "AI care · ${care.xp} XP  ·  ${care.progress}% to next level  ·  ${care.tokensToNextLevel} tokens remaining"
        gameXpProgress?.progress = care.progress
        gameActionSummary?.text = "Fed ${game.feeds} times  ·  Played ${game.playSessions} times  ·  Groomed ${game.groomings} times"
        val sleepSeconds = (game.sleepRemainingMs + 999L) / 1_000L
        gameRuleStatus?.text = when {
            game.sleeping -> "💤 Napping · wakes in ${sleepSeconds / 60}m ${sleepSeconds % 60}s. Let your friend rest."
            game.overfull -> "🍓 Too full to play or explore · ${game.activity}"
            game.playBlockReason != null -> "⭐ ${game.playBlockReason}. Try a nap or a snack."
            game.hunger >= 90 -> "🍓 Already full. Another snack will cause a short tummy break!"
            else -> "Choose an activity together. Your pet will tell you when it needs a break."
        }
        fun enable(key: String, allowed: Boolean) {
            gameButtons[key]?.isEnabled = allowed
            gameButtons[key]?.alpha = if (allowed) 1f else 0.45f
        }
        enable("feed", !game.sleeping && !game.overfull)
        enable("play", game.playBlockReason == null)
        enable("groom", !game.sleeping && game.cleanliness < 95)
        enable("nap", !game.sleeping && game.energy < 85)
        enable("explore", game.exploreBlockReason == null)
        enable("talk", !game.sleeping)
        enable("toys", game.playBlockReason == null)
        enable("tricks", game.playBlockReason == null)
        enable("ai", !game.sleeping && !brainBusy)
        val goal = when (game.questAction) { "feed" -> "Share a snack"; "play" -> "Play a game or use a toy"; "groom" -> "Tidy your pet"; "nap" -> "Give your pet a nap"; "explore" -> "Go exploring"; else -> "Have a conversation" }
        gameRewards?.text = "⭐ ${game.stars} care stars\n${if (game.questComplete) "Today's request complete!" else "Today's request: $goal"}\nGrow friendship to unlock rooms, toys, and tricks."
        gameRoomCard?.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, game.room.colors).apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.rgb(92, 111, 138)) }
        gameDecor?.text = "${game.room.decor}\n${game.room.title}"
        if (activeMainTab == 0 && !brainBusy && System.currentTimeMillis() >= gameReactionUntilAt) {
            gamePreview?.setMood(game.idleMood)
            gamePreviewCaption?.text = game.activity
        }
        val petName = getSharedPreferences("relay", MODE_PRIVATE).getString("pet_name", "Your friend") ?: "Your friend"
        gamePersonality?.text = "$petName · ${game.personality.title}"
        gameThought?.text = "${game.personality.introduction}\n\n“${game.activity}”"
        gameFinds?.text = if (game.finds.isEmpty()) "Keepsakes: none yet · try Explore" else "Keepsakes: ${game.finds.sorted().joinToString(" · ")}"
        gameJournal?.text = game.journal.ifEmpty { listOf("Your first little adventure is waiting.") }.joinToString("\n\n")
        refreshCareStatus()
    }
    private fun startPet(showToast: Boolean = true) {
        try {
            startForegroundService(Intent(this, PetOverlayService::class.java))
            if (showToast) Toast.makeText(this, "Starting floating pet…", Toast.LENGTH_SHORT).show()
        } catch (error: SecurityException) {
            Toast.makeText(this, "Android blocked the overlay: allow Display over other apps and try again.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Couldn't start the floating pet: ${error.message ?: "unknown Android error"}", Toast.LENGTH_LONG).show()
        }
    }
    private fun panelBackground() = GradientDrawable().apply { setColor(Color.rgb(34, 43, 60)); cornerRadius = 22f; setStroke(1, Color.rgb(58, 72, 96)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun buttonSurface(fill: Int, border: Int): android.graphics.drawable.Drawable {
        val shape = GradientDrawable().apply { setColor(fill); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border) }
        return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(65, 255, 255, 255)), shape, null)
    }
    private fun styleMenuButton(button: Button) = button.apply {
        isAllCaps = false; textSize = 14f; minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        backgroundTintList = null
        setTextColor(Color.WHITE)
        val destructive = listOf("clear", "reset", "stop").any { text.toString().startsWith(it, ignoreCase = true) }
        background = if (destructive) buttonSurface(Color.rgb(62, 38, 48), Color.rgb(165, 93, 110))
            else buttonSurface(Color.rgb(38, 48, 66), Color.rgb(83, 102, 128))
    }
    private fun tabSelectedBackground() = buttonSurface(Color.rgb(143, 221, 104), Color.rgb(191, 244, 159))
    private fun tabBackground() = buttonSurface(Color.rgb(38, 48, 66), Color.rgb(83, 102, 128))
}
