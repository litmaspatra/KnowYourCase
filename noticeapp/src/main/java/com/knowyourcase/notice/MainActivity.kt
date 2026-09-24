package com.knowyourcase.notice

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.knowyourcase.notice.data.api.BackendConfig
import com.knowyourcase.notice.data.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : AppCompatActivity() {
    private val db by lazy { NoticeDatabase.get(this) }
    private lateinit var content: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private var notices: List<NoticeEntity> = emptyList()
    private var lookupNoticeId: Long = -1
    private var activeTab = TAB_HOME
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::handleCnrInput)
    }

    private val lookup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = lookupNoticeId
        lookupNoticeId = -1
        if (id < 0) return@registerForActivityResult
        val json = result.data?.getStringExtra(ECourtWebViewActivity.EXTRA_RESULT_JSON)
        val error = result.data?.getStringExtra(ECourtWebViewActivity.EXTRA_ERROR)
        lifecycleScope.launch {
            val old = withContext(Dispatchers.IO) { db.notices().byId(id) }
            if (old != null) {
                val updated = if (!json.isNullOrBlank()) mergeCase(old, json) else old.copy(
                    fetchedState = "RETRY_REQUIRED",
                    lastError = error ?: "Case details could not be fetched",
                    updatedAt = System.currentTimeMillis()
                )
                withContext(Dispatchers.IO) { db.notices().update(updated) }
                ReminderWorker.reschedule(this@MainActivity, updated)
            }
            reloadAndRender()
            pumpQueue()
        }
    }

    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) {
        it?.let { uri -> writeExport(uri, "csv") }
    }
    private val exportJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        it?.let { uri -> writeExport(uri, "json") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        buildShell()
        requestNotificationPermission()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.notices().recoverInterruptedFetches() }
            reloadAndRender()
            pumpQueue()
        }
    }

    override fun onResume() {
        super.onResume()
        if (lookupNoticeId < 0) pumpQueue()
    }

    private fun applySavedTheme() {
        when (getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, "system")) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bottomNav = BottomNavigationView(this).apply {
            menu.add(0, TAB_HOME, 0, "Home").setIcon(R.drawable.ic_pixel_home)
            menu.add(0, TAB_TRACK, 1, "Track").setIcon(R.drawable.ic_pixel_board)
            menu.add(0, TAB_SETTINGS, 2, "Settings").setIcon(R.drawable.ic_pixel_settings)
            selectedItemId = TAB_HOME
            setOnItemSelectedListener {
                activeTab = it.itemId
                renderCurrentTab()
                true
            }
        }
        root.addView(bottomNav)
        setContentView(root)
    }

    private fun reloadAndRender() {
        lifecycleScope.launch {
            notices = withContext(Dispatchers.IO) { db.notices().all() }
            renderCurrentTab()
        }
    }

    private fun renderCurrentTab() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        when (activeTab) {
            TAB_TRACK -> renderTracker()
            TAB_SETTINGS -> renderSettings()
            else -> renderHome()
        }
    }

    private fun page(title: String, subtitle: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(22), dp(20), dp(16))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (!subtitle.isNullOrBlank()) addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 14f
            alpha = .65f
            setPadding(0, dp(3), 0, dp(16))
        })
    }

    private fun renderHome() {
        val scroll = ScrollView(this)
        val root = page("Notice Tracker", "Scan. Track. Serve. Never miss a date.")

        root.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(20), dp(24), dp(20), dp(22))
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_notice_pixel)
                }, LinearLayout.LayoutParams(dp(78), dp(78)))
                addView(TextView(this@MainActivity).apply {
                    text = "Scan Court Notice"
                    textSize = 21f
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(4))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Scan the QR code. The notice is saved instantly and case details are fetched automatically."
                    textSize = 14f
                    gravity = Gravity.CENTER
                    alpha = .7f
                    setPadding(dp(8), 0, dp(8), dp(18))
                })
                addView(primaryButton("▦  Scan QR Code") {
                    scanner.launch(ScanOptions().apply {
                        setPrompt("Scan the eCourts QR code")
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    })
                })
                addView(TextView(this@MainActivity).apply {
                    text = "or"
                    gravity = Gravity.CENTER
                    alpha = .55f
                    setPadding(0, dp(8), 0, dp(8))
                })
                addView(outlineButton("⌨  Enter CNR Manually") { showManualEntry() })
            })
        }, lp(bottom = 18))

        val active = notices.count { it.fetchedState == "QUEUED" || it.fetchedState == "FETCHING" }
        val failed = notices.count { it.fetchedState == "RETRY_REQUIRED" }
        if (active > 0 || failed > 0) {
            root.addView(sectionTitle("Background queue"))
            root.addView(card().apply {
                addView(TextView(this@MainActivity).apply {
                    text = active.toString() + " queued/fetching • " + failed + " need refresh"
                    textSize = 15f
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                })
            }, lp(bottom = 18))
        }

        root.addView(sectionTitle("Recent scans"))
        if (notices.isEmpty()) root.addView(emptyState("No notices scanned yet"))
        else notices.take(4).forEach { root.addView(recentRow(it), lp(bottom = 8)) }

        scroll.addView(root)
        content.addView(scroll)
    }

    private fun recentRow(n: NoticeEntity) = card().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(if (n.fetchedState == "READY") R.drawable.ic_pixel_check else R.drawable.ic_pixel_sync)
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = n.cnr
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 14f
                })
                addView(TextView(this@MainActivity).apply {
                    text = when (n.fetchedState) {
                        "READY" -> "Added successfully"
                        "RETRY_REQUIRED" -> "Needs refresh"
                        "FETCHING" -> "Fetching case details…"
                        else -> "Queued for processing…"
                    }
                    alpha = .65f
                })
            })
        })
    }

    private fun renderTracker() {
        val root = page("Tracker", "Kanban board for pending and completed notices")
        val pending = notices.filter { it.serviceStatus != "SERVED" }
        val completed = notices.filter { it.serviceStatus == "SERVED" }
        root.addView(TextView(this).apply {
            text = pending.size.toString() + " pending    •    " + completed.size + " completed"
            alpha = .7f
            setPadding(0, 0, 0, dp(12))
        })

        val horizontal = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val board = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        board.addView(kanbanColumn("Pending", pending, false), LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(14) })
        board.addView(kanbanColumn("Completed", completed, true), LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT))
        horizontal.addView(board)
        root.addView(horizontal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(root)
    }

    private fun kanbanColumn(title: String, items: List<NoticeEntity>, completed: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(12))
        background = ContextCompat.getDrawable(this@MainActivity, if (completed) R.drawable.bg_column_complete else R.drawable.bg_column_pending)

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = items.size.toString()
                gravity = Gravity.CENTER
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_count)
                setPadding(dp(10), dp(4), dp(10), dp(4))
            })
        }, lp(bottom = 10))

        if (items.isEmpty()) addView(emptyState(if (completed) "No completed notices" else "Nothing pending"))
        items.forEach { addView(noticeCard(it), lp(bottom = 10)) }
    }

    private fun noticeCard(n: NoticeEntity) = card().apply {
        isClickable = true
        setOnClickListener { showNotice(n) }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = n.caseNumber.ifBlank { n.cnr }
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = if (n.serviceStatus == "SERVED") "Served" else statusLabel(n)
                    textSize = 12f
                    setPadding(dp(9), dp(4), dp(9), dp(4))
                    background = ContextCompat.getDrawable(this@MainActivity,
                        if (n.serviceStatus == "SERVED") R.drawable.bg_chip_complete else R.drawable.bg_chip_pending)
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = n.caseTitle.ifBlank {
                    when (n.fetchedState) {
                        "FETCHING" -> "Fetching case details…"
                        "RETRY_REQUIRED" -> "Fetch failed"
                        else -> "Queued for processing…"
                    }
                }
                textSize = 14f
                alpha = .75f
                setPadding(0, dp(5), 0, dp(7))
            })
            if (n.nextHearing.isNotBlank()) addView(TextView(this@MainActivity).apply {
                text = "▣  " + n.nextHearing
                textSize = 13f
            })
            addView(TextView(this@MainActivity).apply {
                text = if (n.processServer.isBlank()) "Process server: unassigned" else "Process server: " + n.processServer
                textSize = 12f
                alpha = .65f
                setPadding(0, dp(7), 0, 0)
            })
            if (n.fetchedState == "FETCHING" || n.fetchedState == "QUEUED") {
                addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(9) })
            } else if (n.fetchedState == "RETRY_REQUIRED") {
                addView(outlineButton("↻  Refresh") { retryNotice(n) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
            }
        })
    }

    private fun statusLabel(n: NoticeEntity) = when (n.fetchedState) {
        "FETCHING" -> "Fetching"
        "QUEUED" -> "Queued"
        "RETRY_REQUIRED" -> "Refresh"
        else -> "Pending"
    }

    private fun renderSettings() {
        val scroll = ScrollView(this)
        val root = page("Settings", "Backend, appearance, fields and exports")
        root.addView(settingCard("▤", "Backend Setup", BackendConfig.url(this)) { showBackendDialog() }, lp(bottom = 10))
        root.addView(settingCard("☷", "Data & Fields", "Choose which fields appear and export") { showFieldsDialog() }, lp(bottom = 10))
        root.addView(settingCard("◈", "Appearance", themeSummary()) { showThemeDialog() }, lp(bottom = 10))
        root.addView(settingCard("⇩", "Export Data", "CSV spreadsheet or JSON backup") { showExportDialog() }, lp(bottom = 10))
        root.addView(settingCard("◉", "Reminders", "10, 7, 3, 1 days and hearing morning") {
            AlertDialog.Builder(this).setTitle("Reminders")
                .setMessage("Not Served notices are reminded before the next hearing. Marking Served cancels pending reminders.")
                .setPositiveButton("OK", null).show()
        }, lp(bottom = 10))
        root.addView(settingCard("♟", "Process Servers", "See current assignments") { showProcessServerSummary() }, lp(bottom = 10))
        root.addView(settingCard("ⓘ", "App Info", "Notice Tracker • Debug") {
            AlertDialog.Builder(this).setTitle("Notice Tracker")
                .setMessage("Standalone personal app. Default backend: " + BackendConfig.DEFAULT_URL)
                .setPositiveButton("OK", null).show()
        }, lp(bottom = 10))
        scroll.addView(root)
        content.addView(scroll)
    }

    private fun settingCard(icon: String, title: String, subtitle: String, click: () -> Unit) = card().apply {
        isClickable = true
        setOnClickListener { click() }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 23f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 13f
                    alpha = .62f
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply { text = "›"; textSize = 28f; alpha = .55f })
        })
    }

    private fun showManualEntry() {
        val input = TextInputEditText(this).apply {
            hint = "e.g. RJTO010012342026"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val wrapper = TextInputLayout(this).apply {
            hint = "16-character CNR"
            setPadding(dp(18), 0, dp(18), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Enter CNR manually")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ -> handleCnrInput(input.text?.toString().orEmpty()) }
            .show()
    }

    private fun handleCnrInput(raw: String) {
        val cleaned = raw.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val cnr = Regex("[A-Z]{4}[0-9]{12}").find(cleaned)?.value
        if (cnr == null) {
            toast("No valid 16-character CNR found.")
            return
        }
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { db.notices().byCnr(cnr) }
            if (existing.isNotEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Case already tracked")
                    .setMessage("A notice for $cnr already exists. Add another notice for the same case?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Add another") { _, _ -> createNotice(cnr) }
                    .show()
            } else createNotice(cnr)
        }
    }

    private fun createNotice(cnr: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.notices().insert(NoticeEntity(cnr = cnr, fetchedState = "QUEUED")) }
            toast("Notice queued")
            reloadAndRender()
            pumpQueue()
        }
    }

    private fun pumpQueue() {
        if (lookupNoticeId >= 0 || isFinishing || isDestroyed) return
        lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) { db.notices().all().firstOrNull { it.fetchedState == "QUEUED" } }
                ?: return@launch
            val fetching = next.copy(fetchedState = "FETCHING", lastError = "", updatedAt = System.currentTimeMillis())
            withContext(Dispatchers.IO) { db.notices().update(fetching) }
            lookupNoticeId = next.id
            reloadAndRender()
            lookup.launch(ECourtWebViewActivity.createIntent(this@MainActivity, next.cnr))
        }
    }

    private fun retryNotice(n: NoticeEntity) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.notices().update(n.copy(fetchedState = "QUEUED", lastError = "", updatedAt = System.currentTimeMillis()))
            }
            reloadAndRender()
            pumpQueue()
        }
    }

    private fun showNotice(n: NoticeEntity) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        fun line(label: String, value: String) {
            if (value.isBlank() || !fieldEnabled(label)) return
            box.addView(TextView(this).apply {
                text = label
                textSize = 11f
                alpha = .6f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(9), 0, dp(2))
            })
            box.addView(TextView(this).apply { text = value; textSize = 15f })
        }
        box.addView(TextView(this).apply {
            text = n.caseNumber.ifBlank { n.cnr }
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (n.caseTitle.isNotBlank()) box.addView(TextView(this).apply {
            text = n.caseTitle
            textSize = 15f
            alpha = .75f
            setPadding(0, dp(3), 0, dp(6))
        })
        line("CNR", n.cnr)
        line("Court", n.courtName)
        line("Petitioner", n.petitioner)
        line("Respondent", n.respondent)
        line("Petitioner advocate", n.petitionerAdvocate)
        line("Respondent advocate", n.respondentAdvocate)
        line("Next hearing", n.nextHearing)
        line("Stage", n.caseStage)
        line("Judge", n.judge)
        line("Process server", n.processServer.ifBlank { "Not assigned" })
        line("Service", if (n.serviceStatus == "SERVED") "Served" else "Not Served")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Notice Details")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null)
            .create()

        box.addView(primaryButton("Assign Process Server") {
            dialog.dismiss()
            assignProcessServer(n)
        }, lp(top = 14))
        box.addView(outlineButton(if (n.serviceStatus == "SERVED") "Mark Not Served" else "Mark Served") {
            dialog.dismiss()
            toggleServed(n)
        }, lp(top = 8))
        if (n.fetchedState != "READY") box.addView(outlineButton("Refresh case details") {
            dialog.dismiss()
            retryNotice(n)
        }, lp(top = 8))
        dialog.show()
    }

    private fun assignProcessServer(n: NoticeEntity) {
        val input = EditText(this).apply {
            hint = "Process server name"
            setText(n.processServer)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle("Assign Process Server").setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Assign") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) saveNotice(n.copy(processServer = name, updatedAt = System.currentTimeMillis()))
            }.show()
    }

    private fun toggleServed(n: NoticeEntity) {
        val served = n.serviceStatus != "SERVED"
        saveNotice(n.copy(serviceStatus = if (served) "SERVED" else "NOT_SERVED", updatedAt = System.currentTimeMillis()), served)
    }

    private fun saveNotice(n: NoticeEntity, cancelReminders: Boolean = false) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.notices().update(n) }
            if (cancelReminders) ReminderWorker.cancel(this@MainActivity, n.id) else ReminderWorker.reschedule(this@MainActivity, n)
            reloadAndRender()
        }
    }

    private fun showBackendDialog() {
        val input = EditText(this).apply {
            setText(BackendConfig.url(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelection(text.length)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, dp(22), 0)
            addView(input)
            addView(TextView(this@MainActivity).apply {
                text = "Default: " + BackendConfig.DEFAULT_URL
                textSize = 12f
                alpha = .6f
                setPadding(0, dp(8), 0, 0)
            })
        }
        AlertDialog.Builder(this).setTitle("Backend Setup").setView(wrap)
            .setNeutralButton("Use Default") { _, _ -> BackendConfig.reset(this); renderCurrentTab() }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> BackendConfig.save(this, input.text.toString()); testBackend() }
            .show()
    }

    private fun testBackend() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { RetrofitClient.service(this@MainActivity).health().isSuccessful }.getOrDefault(false)
            }
            toast(if (ok) "Backend connected" else "Backend saved, but connection test failed")
            renderCurrentTab()
        }
    }

    private fun showFieldsDialog() {
        val labels = FIELD_KEYS.map { it.second }.toTypedArray()
        val checked = FIELD_KEYS.map { prefs.getBoolean("field_" + it.first, true) }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Data & Fields")
            .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().apply {
                    FIELD_KEYS.forEachIndexed { i, pair -> putBoolean("field_" + pair.first, checked[i]) }
                }.apply()
                toast("Field preferences saved")
            }.show()
    }

    private fun fieldEnabled(label: String): Boolean {
        val key = when (label.lowercase()) {
            "cnr" -> "cnr"
            "court" -> "court"
            "petitioner" -> "petitioner"
            "respondent" -> "respondent"
            "petitioner advocate", "respondent advocate" -> "advocates"
            "next hearing" -> "hearing"
            "stage" -> "stage"
            "judge" -> "judge"
            "process server" -> "process_server"
            "service" -> "service"
            else -> return true
        }
        return prefs.getBoolean("field_" + key, true)
    }

    private fun showThemeDialog() {
        val labels = arrayOf("System default", "Light", "Dark")
        val keys = arrayOf("system", "light", "dark")
        val current = keys.indexOf(prefs.getString(KEY_THEME, "system")).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Appearance")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.edit().putString(KEY_THEME, keys[which]).apply()
                dialog.dismiss()
                applySavedTheme()
                recreate()
            }.show()
    }

    private fun themeSummary() = when (prefs.getString(KEY_THEME, "system")) {
        "light" -> "Light theme"
        "dark" -> "Dark theme"
        else -> "Follow system"
    }

    private fun showExportDialog() {
        AlertDialog.Builder(this).setTitle("Export Data")
            .setItems(arrayOf("CSV spreadsheet", "JSON backup")) { _, which ->
                val date = LocalDate.now().toString()
                if (which == 0) exportCsv.launch("notice-tracker-" + date + ".csv")
                else exportJson.launch("notice-tracker-" + date + ".json")
            }.show()
    }

    private fun writeExport(uri: Uri, format: String) {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { db.notices().all() }
            val output = if (format == "json") Gson().toJson(data) else buildCsv(data)
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(output) }
            }
            toast(format.uppercase() + " exported")
        }
    }

    private fun buildCsv(data: List<NoticeEntity>): String {
        fun q(v: String) = """ + v.replace(""", """").replace("\n", " ") + """
        val header = "CNR,Case Number,Case Title,Court,Petitioner,Respondent,Advocates,Next Hearing,Stage,Process Server,Service,Fetch State"
        val rows = data.map { n ->
            listOf(n.cnr,n.caseNumber,n.caseTitle,n.courtName,n.petitioner,n.respondent,
                listOf(n.petitionerAdvocate,n.respondentAdvocate).filter { it.isNotBlank() }.joinToString(" | "),
                n.nextHearing,n.caseStage,n.processServer,
                if (n.serviceStatus == "SERVED") "Served" else "Not Served",n.fetchedState
            ).joinToString(",") { q(it) }
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun showProcessServerSummary() {
        val names = notices.map { it.processServer }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val body = if (names.isEmpty()) "No process servers assigned yet."
        else names.entries.sortedByDescending { it.value }.joinToString("\n") { it.key + " — " + it.value + " notice(s)" }
        AlertDialog.Builder(this).setTitle("Process Servers").setMessage(body).setPositiveButton("OK", null).show()
    }

    private fun mergeCase(old: NoticeEntity, rawJson: String): NoticeEntity {
        val j = runCatching { Gson().fromJson(rawJson, com.google.gson.JsonObject::class.java) }.getOrNull()
            ?: return old.copy(fetchedState = "RETRY_REQUIRED", lastError = "Invalid case response")
        fun s(vararg keys: String): String {
            keys.forEach { key ->
                val e = j.get(key)
                if (e != null && !e.isJsonNull) {
                    if (e.isJsonPrimitive) return e.asString.trim()
                    if (e.isJsonArray) return e.asJsonArray.joinToString("\n") {
                        if (it.isJsonPrimitive) it.asString else it.toString()
                    }
                }
            }
            return ""
        }
        val petitioner = s("petitioner", "petitioner_name")
        val respondent = s("respondent", "respondent_name")
        val title = s("case_title").ifBlank {
            if (petitioner.isNotBlank() && respondent.isNotBlank())
                petitioner.lineSequence().first() + " vs " + respondent.lineSequence().first()
            else ""
        }
        return old.copy(
            caseNumber = s("case_number"),
            caseType = s("case_type"),
            caseTitle = title,
            courtName = s("court_name", "court"),
            judge = s("judges", "judge"),
            petitioner = petitioner,
            respondent = respondent,
            petitionerAdvocate = s("petitioner_advocate"),
            respondentAdvocate = s("respondent_advocate"),
            nextHearing = s("next_hearing_date", "next_hearing"),
            caseStage = s("stage", "case_stage", "status"),
            fetchedState = "READY",
            lastError = "",
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = dp(1).toFloat()
        strokeWidth = dp(1)
        setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        strokeColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
    }

    private fun primaryButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        minHeight = dp(52)
        setOnClickListener { click() }
    }

    private fun outlineButton(label: String, click: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            minHeight = dp(50)
            setOnClickListener { click() }
        }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(9))
    }

    private fun emptyState(value: String) = TextView(this).apply {
        text = value
        gravity = Gravity.CENTER
        alpha = .55f
        setPadding(dp(12), dp(30), dp(12), dp(30))
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, 0)
        a.recycle()
        return c
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_HOME = 1
        private const val TAB_TRACK = 2
        private const val TAB_SETTINGS = 3
        private const val PREFS = "notice_tracker_settings"
        private const val KEY_THEME = "theme"
        private val FIELD_KEYS = listOf(
            "cnr" to "CNR",
            "court" to "Court",
            "petitioner" to "Petitioner",
            "respondent" to "Respondent",
            "advocates" to "Advocates",
            "hearing" to "Next hearing",
            "stage" to "Stage",
            "judge" to "Judge",
            "process_server" to "Process server",
            "service" to "Service status"
        )
    }
}
