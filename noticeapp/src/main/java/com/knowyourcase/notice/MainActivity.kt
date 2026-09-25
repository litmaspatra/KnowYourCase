package com.knowyourcase.notice

import android.Manifest
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.chip.ChipGroup
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
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
    private lateinit var toolbar: MaterialToolbar
    private var notices: List<NoticeEntity> = emptyList()
    private var lookupNoticeId: Long = -1
    private var activeTab = TAB_HOME
    private var trackerFilter = "PENDING"
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var backendHealth = "UNKNOWN"

    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(ModernScannerActivity.EXTRA_SCAN_RESULT)?.let(::handleCnrInput)
        }
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
            "blue" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setTheme(R.style.Theme_NoticeTracker_Blue)
            }
            "mono" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setTheme(R.style.Theme_NoticeTracker_Mono)
            }
            "bw" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setTheme(R.style.Theme_NoticeTracker_BlackWhite)
            }
            "midnight" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                setTheme(R.style.Theme_NoticeTracker_Midnight)
            }
            "ember" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setTheme(R.style.Theme_NoticeTracker_Ember)
            }
            "light" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setTheme(R.style.Theme_NoticeTracker)
            }
            "dark" -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                setTheme(R.style.Theme_NoticeTracker)
            }
            else -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                setTheme(R.style.Theme_NoticeTracker)
            }
        }
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }

        toolbar = MaterialToolbar(this).apply {
            title = "Notice Tracker"
            subtitle = "Court process desk"
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
            elevation = 0f
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this).apply {
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomNav = BottomNavigationView(this).apply {
            menu.add(0, TAB_HOME, 0, "Desk").setIcon(R.drawable.ic_pixel_home)
            menu.add(0, TAB_TRACK, 1, "Notices").setIcon(R.drawable.ic_pixel_board)
            menu.add(0, TAB_SETTINGS, 2, "Settings").setIcon(R.drawable.ic_pixel_settings)
            selectedItemId = TAB_HOME
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener {
                activeTab = it.itemId
                renderCurrentTab()
                true
            }
        }
        root.addView(bottomNav)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(toolbar.paddingLeft, bars.top, toolbar.paddingRight, toolbar.paddingBottom)
            bottomNav.setPadding(bottomNav.paddingLeft, bottomNav.paddingTop, bottomNav.paddingRight, bars.bottom)
            insets
        }
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
                addView(primaryButton("Scan QR code") {
                    scanner.launch(android.content.Intent(this@MainActivity, ModernScannerActivity::class.java))
                }.apply {
                    setIconResource(R.drawable.ic_action_scan)
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = dp(8)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "or"
                    gravity = Gravity.CENTER
                    alpha = .55f
                    setPadding(0, dp(8), 0, dp(8))
                })
                addView(outlineButton("Enter CNR manually") { showManualEntry() }.apply {
                    setIconResource(R.drawable.ic_action_keyboard)
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = dp(8)
                })
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
        val scroll = ScrollView(this)
        val root = page("Tracker", "Pending and completed notices")
        val pending = notices.filter { it.serviceStatus == "PENDING" }
        val completed = notices.filter { it.serviceStatus == "SERVED" || it.serviceStatus == "UNSERVED" }

        root.addView(TextView(this).apply {
            text = pending.size.toString() + " pending    •    " + completed.size + " completed"
            alpha = .7f
            setPadding(0, 0, 0, dp(14))
        })

        root.addView(sectionHeader("Pending", pending.size))
        if (pending.isEmpty()) root.addView(emptyState("No pending notices"))
        else pending.forEach { root.addView(noticeListRow(it), lp(bottom = 8)) }

        root.addView(sectionHeader("Completed", completed.size), lp(top = 18))
        if (completed.isEmpty()) root.addView(emptyState("No completed notices"))
        else completed.forEach { root.addView(noticeListRow(it), lp(bottom = 8)) }

        scroll.addView(root)
        content.addView(scroll)
    }

    private fun sectionHeader(title: String, count: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(10))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = count.toString()
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_count)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
    }

    private fun noticeListRow(n: NoticeEntity) = MaterialCardView(this).apply {
        radius = dp(22).toFloat()
        cardElevation = dp(3).toFloat()
        strokeWidth = dp(1)
        strokeColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
        setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        isClickable = true
        isFocusable = true
        setOnClickListener { showNotice(n) }

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = n.caseNumber.ifBlank { n.cnr }
                        textSize = 17f
                        setTypeface(typeface, Typeface.BOLD)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = n.cnr
                        textSize = 11f
                        alpha = .55f
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                addView(TextView(this@MainActivity).apply {
                    text = when (n.serviceStatus) {
                        "SERVED" -> "Served"
                        "UNSERVED" -> "Unserved"
                        else -> statusLabel(n)
                    }
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    isSingleLine = true
                    setPadding(dp(11), dp(5), dp(11), dp(5))
                    background = ContextCompat.getDrawable(
                        this@MainActivity,
                        if (n.serviceStatus == "PENDING") R.drawable.bg_chip_pending
                        else R.drawable.bg_chip_complete
                    )
                })
            })

            addView(TextView(this@MainActivity).apply {
                text = n.caseTitle.ifBlank {
                    when (n.fetchedState) {
                        "FETCHING" -> "Fetching case details…"
                        "RETRY_REQUIRED" -> "Case details need refresh"
                        else -> "Waiting for case details…"
                    }
                }
                textSize = 14f
                alpha = .82f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(10), 0, dp(12))
            })

            val info = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_notice_info)
            }

            info.addView(TextView(this@MainActivity).apply {
                text = if (n.nextHearing.isBlank()) "Next hearing  —" else "Next hearing  •  " + n.nextHearing
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                isSingleLine = true
            })
            info.addView(TextView(this@MainActivity).apply {
                text = if (n.processServer.isBlank()) "Process server  •  Unassigned" else "Process server  •  " + n.processServer
                textSize = 13f
                alpha = .72f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            })
            addView(info, lp(bottom = 12))

            addView(MaterialButton(
                this@MainActivity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = if (n.processServer.isBlank()) "Assign process server" else "Change process server"
                setIconResource(R.drawable.ic_action_assign)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = dp(7)
                isAllCaps = false
                isSingleLine = true
                maxLines = 1
                textSize = 13f
                minHeight = dp(44)
                setOnClickListener {
                    assignProcessServer(n)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(9)
            })

            if (n.serviceStatus == "PENDING") {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL

                    addView(MaterialButton(this@MainActivity).apply {
                        text = "Served"
                        setIconResource(R.drawable.ic_action_served)
                        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                        iconPadding = dp(6)
                        isAllCaps = false
                        isSingleLine = true
                        maxLines = 1
                        textSize = 13f
                        minHeight = dp(44)
                        setOnClickListener { markServiceStatus(n, "SERVED") }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(8)
                    })

                    addView(MaterialButton(
                        this@MainActivity,
                        null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle
                    ).apply {
                        text = "Unserved"
                        setIconResource(R.drawable.ic_action_unserved)
                        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                        iconPadding = dp(6)
                        isAllCaps = false
                        isSingleLine = true
                        maxLines = 1
                        textSize = 13f
                        minHeight = dp(44)
                        setOnClickListener { markServiceStatus(n, "UNSERVED") }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            } else {
                addView(MaterialButton(
                    this@MainActivity,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "Move to pending"
                    isAllCaps = false
                    isSingleLine = true
                    maxLines = 1
                    textSize = 13f
                    minHeight = dp(44)
                    setOnClickListener { markServiceStatus(n, "PENDING") }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            if (n.fetchedState == "FETCHING" || n.fetchedState == "QUEUED") {
                addView(ProgressBar(
                    this@MainActivity,
                    null,
                    android.R.attr.progressBarStyleHorizontal
                ).apply { isIndeterminate = true },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
                        topMargin = dp(12)
                    })
            } else if (n.fetchedState == "RETRY_REQUIRED") {
                addView(outlineButton("Refresh case details") { retryNotice(n) }, lp(top = 10))
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
        root.addView(backendHealthCard(), lp(bottom = 10))
        root.addView(settingCard(R.drawable.ic_ui_backend, "Backend setup", BackendConfig.url(this)) { showBackendDialog() }, lp(bottom = 12))
        root.addView(settingCard(R.drawable.ic_ui_fields, "Data & fields", "Choose what appears in notice details and exports") { showFieldsDialog() }, lp(bottom = 12))
        root.addView(settingCard(R.drawable.ic_ui_appearance, "Appearance", themeSummary()) { showThemeDialog() }, lp(bottom = 12))
        root.addView(settingCard(R.drawable.ic_ui_export, "Export data", "CSV spreadsheet or JSON backup") { showExportDialog() }, lp(bottom = 12))
        root.addView(settingCard(R.drawable.ic_ui_reminder, "Reminders", "10, 7, 3, 1 days and hearing morning") {
            MaterialAlertDialogBuilder(this).setTitle("Reminders")
                .setMessage("Pending notices are reminded before the next hearing. Marking Served or Unserved completes the notice and cancels pending reminders.")
                .setPositiveButton("Done", null).show()
        }, lp(bottom = 12))
        root.addView(settingCard(R.drawable.ic_ui_people, "Process servers", processServerSummary()) { showProcessServerSettings() }, lp(bottom = 12))
        root.addView(settingCard(R.drawable.ic_ui_info, "App info", "Notice Tracker • Debug") {
            MaterialAlertDialogBuilder(this).setTitle("Notice Tracker")
                .setMessage("Standalone personal app. Default backend: " + BackendConfig.DEFAULT_URL)
                .setPositiveButton("Done", null).show()
        }, lp(bottom = 12))
        scroll.addView(root)
        content.addView(scroll)
    }

    private fun backendHealthCard() = card().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))

            addView(TextView(this@MainActivity).apply {
                text = "●"
                textSize = 24f
                setTextColor(when (backendHealth) {
                    "ONLINE" -> 0xFF2E7D32.toInt()
                    "OFFLINE" -> 0xFFC62828.toInt()
                    "CHECKING" -> 0xFFF9A825.toInt()
                    else -> 0xFF757575.toInt()
                })
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(42), dp(42)))

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Backend Health"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = when (backendHealth) {
                        "ONLINE" -> "Online • " + BackendConfig.url(this@MainActivity)
                        "OFFLINE" -> "Offline • " + BackendConfig.url(this@MainActivity)
                        "CHECKING" -> "Checking backend…"
                        else -> "Not checked • " + BackendConfig.url(this@MainActivity)
                    }
                    textSize = 12f
                    alpha = .65f
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (backendHealth == "CHECKING") "Checking" else "Check now"
                isEnabled = backendHealth != "CHECKING"
                isAllCaps = false
                setOnClickListener { testBackend() }
            })
        })
    }

    private fun settingCard(iconRes: Int, title: String, subtitle: String, click: () -> Unit) = card().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(14), dp(15))

            addView(FrameLayout(this@MainActivity).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_notice_info)
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(iconRes)
                    setColorFilter(themeColor(com.google.android.material.R.attr.colorOnSurface))
                    contentDescription = null
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginEnd = dp(14)
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 13f
                    alpha = .65f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(TextView(this@MainActivity).apply {
                text = "›"
                textSize = 26f
                alpha = .45f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), dp(44)))
        })
    }

    private fun showManualEntry() {
        val field = modernTextField(
            label = "CNR number",
            value = "",
            hint = "RJTO010012342026",
            inputTypeValue = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Enter CNR")
            .setMessage("Enter the 16-character CNR printed on the notice.")
            .setView(field.first)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add") { _, _ -> handleCnrInput(field.second.text?.toString().orEmpty()) }
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
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Case already tracked")
                    .setMessage("A notice for $cnr already exists. Add another notice for the same case?")
                    .setNegativeButton("Open existing") { _, _ -> showNotice(existing.first()) }
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
        line("Service", when (n.serviceStatus) {
            "SERVED" -> "Served"
            "UNSERVED" -> "Unserved"
            else -> "Pending"
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Notice Details")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null)
            .create()

        box.addView(primaryButton("Assign process server") {
            dialog.dismiss()
            assignProcessServer(n)
        }, lp(top = 14))
        if (n.serviceStatus == "PENDING") {
            box.addView(primaryButton("Mark served") {
                dialog.dismiss()
                markServiceStatus(n, "SERVED")
            }, lp(top = 12))
            box.addView(outlineButton("Mark unserved") {
                dialog.dismiss()
                markServiceStatus(n, "UNSERVED")
            }, lp(top = 10))
        } else {
            box.addView(outlineButton("Move to pending") {
                dialog.dismiss()
                markServiceStatus(n, "PENDING")
            }, lp(top = 12))
        }
        dialog.show()
    }

    private fun assignProcessServer(n: NoticeEntity) {
        val servers = processServers()
        if (servers.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("No Process Servers")
                .setMessage("Add process-server names in Settings first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    activeTab = TAB_SETTINGS
                    bottomNav.selectedItemId = TAB_SETTINGS
                    renderCurrentTab()
                }.show()
            return
        }

        val items = servers.toTypedArray()
        val current = servers.indexOf(n.processServer)
        MaterialAlertDialogBuilder(this)
            .setTitle("Assign process server")
            .setSingleChoiceItems(items, current) { dialog, which ->
                saveNotice(n.copy(
                    processServer = servers[which],
                    updatedAt = System.currentTimeMillis()
                ))
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markServiceStatus(n: NoticeEntity, status: String) {
        val updated = n.copy(
            serviceStatus = status,
            updatedAt = System.currentTimeMillis()
        )
        saveNotice(updated, cancelReminders = status != "PENDING")
    }

    private fun saveNotice(n: NoticeEntity, cancelReminders: Boolean = false) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.notices().update(n) }
            if (cancelReminders) ReminderWorker.cancel(this@MainActivity, n.id) else ReminderWorker.reschedule(this@MainActivity, n)
            reloadAndRender()
        }
    }

    private fun showBackendDialog() {
        val field = modernTextField(
            label = "Backend URL",
            value = BackendConfig.url(this),
            hint = BackendConfig.DEFAULT_URL,
            inputTypeValue = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
            addView(field.first)
            addView(TextView(this@MainActivity).apply {
                text = "Default: " + BackendConfig.DEFAULT_URL
                textSize = 12f
                alpha = .58f
                setPadding(dp(4), dp(10), dp(4), 0)
            })
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Backend setup")
            .setView(wrap)
            .setNeutralButton("Use default") { _, _ ->
                BackendConfig.reset(this)
                renderCurrentTab()
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                BackendConfig.save(this, field.second.text?.toString().orEmpty())
                testBackend()
            }
            .show()
    }

    private fun testBackend() {
        backendHealth = "CHECKING"
        if (activeTab == TAB_SETTINGS) renderCurrentTab()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { RetrofitClient.service(this@MainActivity).health().isSuccessful }.getOrDefault(false)
            }
            backendHealth = if (ok) "ONLINE" else "OFFLINE"
            toast(if (ok) "Backend is online" else "Backend is offline")
            if (activeTab == TAB_SETTINGS) renderCurrentTab()
        }
    }

    private fun showFieldsDialog() {
        val labels = FIELD_KEYS.map { it.second }.toTypedArray()
        val checked = FIELD_KEYS.map { prefs.getBoolean("field_" + it.first, true) }.toBooleanArray()
        MaterialAlertDialogBuilder(this).setTitle("Data & fields")
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
        val labels = arrayOf(
            "System default",
            "Blue & White",
            "Monochrome",
            "Black & White",
            "Midnight",
            "Ember",
            "Light",
            "Dark"
        )
        val keys = arrayOf("system", "blue", "mono", "bw", "midnight", "ember", "light", "dark")
        val current = keys.indexOf(prefs.getString(KEY_THEME, "system")).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this).setTitle("Appearance")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs.edit().putString(KEY_THEME, keys[which]).apply()
                dialog.dismiss()
                applySavedTheme()
                recreate()
            }.show()
    }

    private fun themeSummary() = when (prefs.getString(KEY_THEME, "system")) {
        "blue" -> "Blue & White"
        "mono" -> "Monochrome"
        "bw" -> "Black & White"
        "midnight" -> "Midnight"
        "ember" -> "Ember"
        "light" -> "Light theme"
        "dark" -> "Dark theme"
        else -> "Follow system"
    }

    private fun showExportDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Export data")
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
        fun q(v: String) = "\\\"" + v.replace("\\\"", "\\\"\\\"").replace("\\n", " ") + "\\\""
        val header = "CNR,Case Number,Case Title,Court,Petitioner,Respondent,Advocates,Next Hearing,Stage,Process Server,Service,Fetch State"
        val rows = data.map { n ->
            listOf(n.cnr,n.caseNumber,n.caseTitle,n.courtName,n.petitioner,n.respondent,
                listOf(n.petitionerAdvocate,n.respondentAdvocate).filter { it.isNotBlank() }.joinToString(" | "),
                n.nextHearing,n.caseStage,n.processServer,
                when (n.serviceStatus) {
                    "SERVED" -> "Served"
                    "UNSERVED" -> "Unserved"
                    else -> "Pending"
                },n.fetchedState
            ).joinToString(",") { q(it) }
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun processServers(): List<String> {
        val raw = prefs.getString(KEY_PROCESS_SERVERS, "").orEmpty()
        return raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun saveProcessServers(values: List<String>) {
        prefs.edit().putString(KEY_PROCESS_SERVERS, values.joinToString("\n")).apply()
    }

    private fun processServerSummary(): String {
        val servers = processServers()
        return if (servers.isEmpty()) "Add your process servers"
        else servers.joinToString(", ")
    }

    private fun showProcessServerSettings() {
        val servers = processServers().toMutableList()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(8))
        }

        lateinit var dialog: AlertDialog

        fun renderList() {
            container.removeAllViews()

            if (servers.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "No process servers added yet."
                    alpha = .65f
                    setPadding(0, dp(8), 0, dp(12))
                })
            } else {
                servers.forEachIndexed { index, name ->
                    container.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(5), 0, dp(5))
                        addView(TextView(this@MainActivity).apply {
                            text = name
                            textSize = 15f
                            setTypeface(typeface, Typeface.BOLD)
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(MaterialButton(
                            this@MainActivity,
                            null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle
                        ).apply {
                            text = "Remove"
                            isAllCaps = false
                            isSingleLine = true
                            maxLines = 1
                            setOnClickListener {
                                servers.removeAt(index)
                                saveProcessServers(servers)
                                renderList()
                                if (activeTab == TAB_SETTINGS) renderCurrentTab()
                            }
                        })
                    })
                }
            }

            container.addView(primaryButton("Add process server") {
                val field = modernTextField(
                    label = "Process server name",
                    value = "",
                    hint = "Enter name",
                    inputTypeValue = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                )
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Add process server")
                    .setView(field.first)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Add") { _, _ ->
                        val name = field.second.text?.toString().orEmpty().trim()
                        if (name.isNotBlank() && name !in servers) {
                            servers.add(name)
                            saveProcessServers(servers)
                            renderList()
                            if (activeTab == TAB_SETTINGS) renderCurrentTab()
                        }
                    }.show()
            }, lp(top = 12))
        }

        renderList()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Process servers")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Done", null)
            .create()
        dialog.setOnDismissListener {
            if (activeTab == TAB_SETTINGS) renderCurrentTab()
        }
        dialog.show()
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

    private fun modernTextField(
        label: String,
        value: String,
        hint: String,
        inputTypeValue: Int
    ): Pair<TextInputLayout, TextInputEditText> {
        val input = TextInputEditText(this).apply {
            setText(value)
            this.hint = hint
            inputType = inputTypeValue
            textSize = 16f
            setPadding(dp(14), dp(4), dp(14), dp(4))
            if (value.isNotBlank()) setSelection(text?.length ?: 0)
        }
        val layout = TextInputLayout(this).apply {
            this.hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(
                dp(16).toFloat(),
                dp(16).toFloat(),
                dp(16).toFloat(),
                dp(16).toFloat()
            )
            setPadding(dp(4), dp(8), dp(4), dp(2))
            addView(input)
        }
        return layout to input
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(20).toFloat()
        cardElevation = dp(2).toFloat()
        strokeWidth = dp(1)
        setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        strokeColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
    }

    private fun primaryButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        isSingleLine = true
        maxLines = 1
        minHeight = dp(52)
        setOnClickListener { click() }
    }

    private fun outlineButton(label: String, click: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            textSize = 14f
            isAllCaps = false
            isSingleLine = true
            maxLines = 1
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
        private const val KEY_PROCESS_SERVERS = "process_servers"
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
