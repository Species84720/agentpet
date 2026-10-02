package online.thenightwatcher.agentpet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog

/** Small pairing screen. The floating companion itself is owned by the service. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 72, 48, 48) }
        root.addView(TextView(this).apply { text = "AgentPet companion"; textSize = 24f })
        root.addView(TextView(this).apply { text = "Pair this Android pet with your Cloudflare relay." })
        val endpoint = EditText(this).apply { hint = "https://relay.example.com"; setText(prefs.getString("endpoint", "")) }
        val token = EditText(this).apply { hint = "Companion device token"; setText(prefs.getString("token", "")) }
        root.addView(endpoint); root.addView(token)
        val petLabel = TextView(this).apply { text = "Pet: loading shared library…" }
        val pets = Spinner(this)
        root.addView(petLabel); root.addView(pets)
        PetCatalog.load { library ->
            if (library.isEmpty()) { petLabel.text = "Pet library unavailable — the companion will retry."; return@load }
            val labels = library.map { it.name }
            pets.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            val selected = prefs.getString("pet_sheet", "")
            pets.setSelection(library.indexOfFirst { it.spritesheetUrl == selected }.coerceAtLeast(0))
            pets.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    prefs.edit().putString("pet_sheet", library[position].spritesheetUrl).apply()
                }
            }
            petLabel.text = "Choose your animated pet (${library.size} available)"
        }
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
        setContentView(root)
    }
    private fun startPet() {
        try {
            startForegroundService(Intent(this, PetOverlayService::class.java))
            Toast.makeText(this, "Pet started — it is floating above this screen.", Toast.LENGTH_SHORT).show()
        } catch (error: SecurityException) {
            Toast.makeText(this, "Android blocked the overlay: allow Display over other apps and try again.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Couldn't start the floating pet: ${error.message ?: "unknown Android error"}", Toast.LENGTH_LONG).show()
        }
    }
}
