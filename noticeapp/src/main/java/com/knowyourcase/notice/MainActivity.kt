package com.knowyourcase.notice

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.knowyourcase.notice.data.api.BackendConfig
import com.knowyourcase.notice.data.api.RetrofitClient
import com.knowyourcase.notice.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : AppCompatActivity() {
    private val db by lazy { NoticeDatabase.get(this) }
    private lateinit var appRoot: LinearLayout
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
        installSplashScreen()
        applySavedTheme()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildShell()
        requestNotificationPermission()
        appRoot.post { showFirstRunIfNeeded() }
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
        appRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }

        toolbar = MaterialToolbar(this).apply {
            title = "Notice Tracker"
            subtitle = "Court process desk"
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
            elevation = 0f
        }
        appRoot.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content = FrameLayout(this).apply {
            setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        }
        appRoot.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bottomNav = BottomNavigationView(this).apply {
            menu.add(0, TAB_HOME, 0, "Desk").setIcon(R.drawable.ic_nt_desk)
            menu.add(0, TAB_TRACK, 1, "Notices").setIcon(R.drawable.ic_nt_notices)
            menu.add(0, TAB_SETTINGS, 2, "Settings").setIcon(R.drawable.ic_nt_settings)
            selectedItemId = TAB_HOME
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener {
                activeTab = it.itemId
                renderCurrentTab()
                true
            }
        }
        appRoot.addView(bottomNav)
        setContentView(appRoot)

        ViewCompat.setOnApplyWindowInsetsListener(appRoot) { _, insets ->
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
            TAB_TRACK -> {
                toolbar.title = "Notices"
                toolbar.subtitle = "Pending work and completed service"
                renderTracker()
            }
            TAB_SETTINGS -> {
                toolbar.title = "Settings"
                toolbar.subtitle = "Desk preferences"
                renderSettings()
            }
            else -> {
                toolbar.title = "Notice Tracker"
                toolbar.subtitle = "Court process desk"
                renderHome()
            }
        }
        animateContentIn()
    }

    private fun page() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(UiTokens.Space.MD),
            dp(UiTokens.Space.MD),
            dp(UiTokens.Space.MD),
            dp(UiTokens.Space.LG)
        )
    }

    private fun heading(value: String, supporting: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = value
            applyType(TextRole.HEADLINE, true)
        })
        if (!supporting.isNullOrBlank()) {
            addView(TextView(this@MainActivity).apply {
                text = supporting
                applyType(TextRole.BODY)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
            })
        }
    }

    private fun renderHome() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = page()
        val pending = notices.filter { it.serviceStatus == "PENDING" }
        val unassigned = pending.count { it.processServer.isBlank() }
        val upcoming = pending.count {
            runCatching {
                val d = LocalDate.parse(it.nextHearing)
                !d.isBefore(LocalDate.now()) && !d.isAfter(LocalDate.now().plusDays(7))
            }.getOrDefault(false)
        }

        root.addView(
            heading(
                "Today’s desk",
                if (pending.isEmpty()) "No pending notices. Your desk is clear."
                else pending.size.toString() + " notices still need action."
            ),
            lp(bottom = UiTokens.Space.MD)
        )

        root.addView(
            primaryButton("Scan court notice", R.drawable.ic_nt_scan) {
                scanner.launch(android.content.Intent(this@MainActivity, ModernScannerActivity::class.java))
            }.apply { minHeight = dp(UiTokens.Size.PRIMARY_ACTION) },
            lp(bottom = UiTokens.Space.XS)
        )

        root.addView(
            outlineButton("Enter CNR manually", R.drawable.ic_nt_keyboard) { showManualEntry() },
            lp(bottom = UiTokens.Space.LG)
        )

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(statBlock(pending.size.toString(), "Pending"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(UiTokens.Space.XS)
            })
            addView(statBlock(upcoming.toString(), "Due ≤ 7d"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(UiTokens.Space.XS)
            })
            addView(statBlock(unassigned.toString(), "Unassigned"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, lp(bottom = UiTokens.Space.LG))

        root.addView(sectionTitle("Needs attention"))
        val attention = pending
            .sortedBy { runCatching { LocalDate.parse(it.nextHearing) }.getOrDefault(LocalDate.MAX) }
            .filter { it.processServer.isBlank() || it.nextHearing.isNotBlank() }
            .take(3)

        if (attention.isEmpty()) {
            root.addView(
                statePanel(
                    StateKind.EMPTY,
                    "Nothing needs attention",
                    "New or unassigned notices will appear here.",
                    R.drawable.ic_nt_empty,
                    "Scan a notice"
                ) { scanner.launch(android.content.Intent(this@MainActivity, ModernScannerActivity::class.java)) },
                lp(bottom = UiTokens.Space.LG)
            )
        } else {
            attention.forEach { root.addView(compactNoticeRow(it), lp(bottom = UiTokens.Space.XS)) }
            root.addView(
                outlineButton("View all notices") {
                    activeTab = TAB_TRACK
                    bottomNav.selectedItemId = TAB_TRACK
                },
                lp(bottom = UiTokens.Space.LG)
            )
        }

        root.addView(sectionTitle("Recent scans"))
        if (notices.isEmpty()) {
            root.addView(
                statePanel(
                    StateKind.EMPTY,
                    "No notices yet",
                    "Scan your first court notice to begin.",
                    R.drawable.ic_nt_empty,
                    "Scan a notice"
                ) { scanner.launch(android.content.Intent(this@MainActivity, ModernScannerActivity::class.java)) }
            )
        } else {
            notices.take(4).forEach { root.addView(recentRow(it), lp(bottom = UiTokens.Space.XS)) }
        }

        scroll.addView(root)
        content.addView(scroll)
    }

    private fun statBlock(value: String, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(
            dp(UiTokens.Space.XS),
            dp(UiTokens.Space.SM),
            dp(UiTokens.Space.XS),
            dp(UiTokens.Space.SM)
        )
        background = roundedSurface(
            com.google.android.material.R.attr.colorSurfaceVariant,
            UiTokens.Radius.MEDIUM
        )
        addView(TextView(this@MainActivity).apply {
            text = value
            applyType(TextRole.TITLE, true)
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            applyType(TextRole.CAPTION)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
        })
    }

    private fun compactNoticeRow(n: NoticeEntity) = designCard().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { showNotice(n) }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.SM)
            )
            addView(TextView(this@MainActivity).apply {
                text = n.caseNumber.ifBlank { n.cnr }
                applyType(TextRole.BODY, true)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@MainActivity).apply {
                text = when {
                    n.processServer.isBlank() && n.nextHearing.isNotBlank() -> "Unassigned • Hearing " + n.nextHearing
                    n.processServer.isBlank() -> "Unassigned"
                    n.nextHearing.isNotBlank() -> n.processServer + " • Hearing " + n.nextHearing
                    else -> n.processServer
                }
                applyType(TextRole.LABEL)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
            })
        })
    }

    private fun recentRow(n: NoticeEntity) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(UiTokens.MIN_TOUCH)
        setPadding(0, dp(UiTokens.Space.XS), 0, dp(UiTokens.Space.XS))
        isClickable = true
        isFocusable = true
        setOnClickListener { showNotice(n) }

        addView(ImageView(this@MainActivity).apply {
            setImageResource(if (n.fetchedState == "READY") R.drawable.ic_nt_check else R.drawable.ic_nt_sync)
            setColorFilter(
                themeColor(
                    if (n.fetchedState == "READY") com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            contentDescription = if (n.fetchedState == "READY") "Ready" else "Processing"
        }, LinearLayout.LayoutParams(dp(UiTokens.Icon.SUPPORT), dp(UiTokens.Icon.SUPPORT)).apply {
            marginEnd = dp(UiTokens.Space.SM)
        })

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = n.caseNumber.ifBlank { n.cnr }
                applyType(TextRole.LABEL, true)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(this@MainActivity).apply {
                text = when (n.fetchedState) {
                    "READY" -> n.caseTitle.ifBlank { "Ready" }
                    "RETRY_REQUIRED" -> "Needs refresh"
                    "FETCHING" -> "Fetching case details…"
                    else -> "Queued…"
                }
                applyType(TextRole.CAPTION)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_nt_notices)
            setColorFilter(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            contentDescription = "Open notice"
            setPadding(dp(UiTokens.Space.SM), dp(UiTokens.Space.SM), dp(UiTokens.Space.SM), dp(UiTokens.Space.SM))
        }, LinearLayout.LayoutParams(dp(UiTokens.MIN_TOUCH), dp(UiTokens.MIN_TOUCH)))
    }

    private fun renderTracker() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = page()
        val pending = notices.filter { it.serviceStatus == "PENDING" }
        val completed = notices.filter { it.serviceStatus == "SERVED" || it.serviceStatus == "UNSERVED" }

        root.addView(
            heading(
                when (trackerFilter) {
                    "COMPLETED" -> "Completed service"
                    "ALL" -> "All notices"
                    else -> "Pending work"
                },
                when (trackerFilter) {
                    "COMPLETED" -> completed.size.toString() + " completed notices"
                    "ALL" -> notices.size.toString() + " total notices"
                    else -> pending.size.toString() + " notices need action"
                }
            ),
            lp(bottom = UiTokens.Space.SM)
        )

        val filters = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val pendingButton = filterButton("Pending", "PENDING")
        val completedButton = filterButton("Completed", "COMPLETED")
        val allButton = filterButton("All", "ALL")
        filters.addView(pendingButton, LinearLayout.LayoutParams(0, dp(UiTokens.MIN_TOUCH), 1f))
        filters.addView(completedButton, LinearLayout.LayoutParams(0, dp(UiTokens.MIN_TOUCH), 1f))
        filters.addView(allButton, LinearLayout.LayoutParams(0, dp(UiTokens.MIN_TOUCH), 1f))
        when (trackerFilter) {
            "COMPLETED" -> completedButton.isChecked = true
            "ALL" -> allButton.isChecked = true
            else -> pendingButton.isChecked = true
        }
        root.addView(filters, lp(bottom = UiTokens.Space.MD))

        val shown = when (trackerFilter) {
            "COMPLETED" -> completed
            "ALL" -> notices
            else -> pending
        }

        if (shown.isEmpty()) {
            val titleText = if (trackerFilter == "COMPLETED") "No completed notices" else "Nothing here"
            val messageText = if (trackerFilter == "PENDING") {
                "Scanned notices stay here until you mark them served or unserved."
            } else {
                "Completed service will appear here."
            }
            root.addView(
                statePanel(
                    StateKind.EMPTY,
                    titleText,
                    messageText,
                    R.drawable.ic_nt_empty,
                    if (trackerFilter == "COMPLETED") null else "Scan a notice"
                ) {
                    scanner.launch(android.content.Intent(this@MainActivity, ModernScannerActivity::class.java))
                }
            )
        } else {
            shown.forEach { root.addView(noticeListRow(it), lp(bottom = UiTokens.Space.SM)) }
        }

        scroll.addView(root)
        content.addView(scroll)
    }

    private fun filterButton(label: String, value: String) = outlineButton(label) {
        trackerFilter = value
        renderTracker()
        animateContentIn()
    }.apply {
        isCheckable = true
        applyType(TextRole.LABEL, true)
    }

    private fun noticeListRow(n: NoticeEntity) = designCard().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { showNotice(n) }

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.MD)
            )

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = n.caseNumber.ifBlank { n.cnr }
                        applyType(TextRole.BODY, true)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = n.cnr
                        applyType(TextRole.CAPTION)
                        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                        setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(statusChip(n))
            })

            addView(TextView(this@MainActivity).apply {
                text = n.caseTitle.ifBlank {
                    when (n.fetchedState) {
                        "FETCHING" -> "Fetching case details…"
                        "RETRY_REQUIRED" -> "Case details need refresh"
                        else -> "Waiting for case details…"
                    }
                }
                applyType(TextRole.BODY)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(UiTokens.Space.XS), 0, dp(UiTokens.Space.SM))
            })

            if (n.courtName.isNotBlank()) addView(metaLine("Court", n.courtName))
            addView(metaLine("Next hearing", n.nextHearing.ifBlank { "Not scheduled" }))
            addView(metaLine("Process server", n.processServer.ifBlank { "Unassigned" }))

            when (n.fetchedState) {
                "FETCHING", "QUEUED" -> {
                    addView(com.google.android.material.progressindicator.LinearProgressIndicator(this@MainActivity).apply {
                        isIndeterminate = true
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(UiTokens.Space.SM)
                    })
                }
                "RETRY_REQUIRED" -> {
                    addView(
                        statePanel(
                            StateKind.ERROR,
                            "Case details unavailable",
                            n.lastError.ifBlank { "The eCourts lookup did not complete." },
                            R.drawable.ic_nt_error,
                            "Retry"
                        ) { retryNotice(n) },
                        lp(top = UiTokens.Space.SM)
                    )
                }
            }

            addView(
                outlineButton(
                    if (n.processServer.isBlank()) "Assign process server" else "Change process server",
                    R.drawable.ic_nt_assign
                ) { assignProcessServer(n) },
                lp(top = UiTokens.Space.SM, bottom = UiTokens.Space.XS)
            )

            if (n.serviceStatus == "PENDING") {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        primaryButton("Served", R.drawable.ic_nt_served) { markServiceStatus(n, "SERVED") },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginEnd = dp(UiTokens.Space.XS)
                        }
                    )
                    addView(
                        outlineButton("Unserved", R.drawable.ic_nt_unserved) { markServiceStatus(n, "UNSERVED") },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                })
            } else {
                addView(outlineButton("Move to pending") { markServiceStatus(n, "PENDING") })
            }
        })
    }

    private fun statusChip(n: NoticeEntity) = TextView(this).apply {
        text = when (n.serviceStatus) {
            "SERVED" -> "Served"
            "UNSERVED" -> "Unserved"
            else -> statusLabel(n)
        }
        applyType(TextRole.CAPTION, true)
        isSingleLine = true
        setPadding(
            dp(UiTokens.Space.XS),
            dp(UiTokens.Space.XXS),
            dp(UiTokens.Space.XS),
            dp(UiTokens.Space.XXS)
        )
        when (n.serviceStatus) {
            "SERVED" -> {
                background = roundedColor(R.color.nt_success_container, UiTokens.Radius.PILL)
                setTextColor(getColor(R.color.nt_on_success_container))
            }
            "UNSERVED" -> {
                background = roundedSurface(com.google.android.material.R.attr.colorSecondaryContainer, UiTokens.Radius.PILL)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
            }
            else -> {
                background = roundedColor(R.color.nt_warning_container, UiTokens.Radius.PILL)
                setTextColor(getColor(R.color.nt_on_warning_container))
            }
        }
    }

    private fun metaLine(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(0, dp(UiTokens.Space.XXS), 0, dp(UiTokens.Space.XXS))
        addView(TextView(this@MainActivity).apply {
            text = label
            applyType(TextRole.LABEL)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }, LinearLayout.LayoutParams(dp(UiTokens.Size.META_LABEL), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = value
            applyType(TextRole.LABEL)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun statusLabel(n: NoticeEntity) = when (n.fetchedState) {
        "FETCHING" -> "Fetching"
        "QUEUED" -> "Queued"
        "RETRY_REQUIRED" -> "Refresh"
        else -> "Pending"
    }

    private fun renderSettings() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = page()

        root.addView(
            heading("Desk setup", "Keep the app aligned with how your process desk actually works."),
            lp(bottom = UiTokens.Space.MD)
        )

        root.addView(sectionTitle("Connection"))
        root.addView(backendHealthPanel(), lp(bottom = UiTokens.Space.SM))
        root.addView(settingsRow(R.drawable.ic_nt_backend, "Backend", BackendConfig.url(this)) { showBackendDialog() })

        root.addView(sectionTitle("Workflow"), lp(top = UiTokens.Space.LG))
        root.addView(settingsRow(R.drawable.ic_nt_people, "Process servers", processServerSummary()) { showProcessServerSettings() })
        root.addView(settingsRow(R.drawable.ic_nt_reminder, "Reminders", "10, 7, 3, 1 days and hearing morning") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Reminders")
                .setMessage("Only Pending notices are reminded. Served and Unserved notices are complete and stop future reminders.")
                .setPositiveButton("Done", null)
                .show()
        })

        root.addView(sectionTitle("Display & data"), lp(top = UiTokens.Space.LG))
        root.addView(settingsRow(R.drawable.ic_nt_fields, "Visible fields", "Choose what appears in notice details and exports") { showFieldsDialog() })
        root.addView(settingsRow(R.drawable.ic_nt_appearance, "Appearance", themeSummary()) { showThemeDialog() })
        root.addView(settingsRow(R.drawable.ic_nt_export, "Export", "CSV spreadsheet or JSON backup") { showExportDialog() })

        root.addView(sectionTitle("About"), lp(top = UiTokens.Space.LG))
        root.addView(settingsRow(R.drawable.ic_nt_info, "Notice Tracker", "Debug build • Personal court-process utility") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Notice Tracker")
                .setMessage("Local-first notice tracking with eCourts case lookup. Default backend: " + BackendConfig.DEFAULT_URL)
                .setPositiveButton("Done", null)
                .show()
        })

        scroll.addView(root)
        content.addView(scroll)
    }

    private fun backendHealthPanel() = designCard().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.SM)
            )

            if (backendHealth == "CHECKING") {
                addView(com.google.android.material.progressindicator.CircularProgressIndicator(this@MainActivity).apply {
                    isIndeterminate = true
                    trackThickness = dp(UiTokens.Space.XXS)
                    indicatorSize = dp(UiTokens.Icon.SUPPORT)
                }, LinearLayout.LayoutParams(dp(UiTokens.MIN_TOUCH), dp(UiTokens.MIN_TOUCH)).apply {
                    marginEnd = dp(UiTokens.Space.XS)
                })
            } else {
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(
                        when (backendHealth) {
                            "ONLINE" -> R.drawable.ic_nt_check
                            "OFFLINE" -> R.drawable.ic_nt_error
                            else -> R.drawable.ic_nt_backend
                        }
                    )
                    setColorFilter(
                        when (backendHealth) {
                            "ONLINE" -> getColor(R.color.nt_success)
                            "OFFLINE" -> getColor(R.color.nt_error)
                            else -> themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
                        }
                    )
                    contentDescription = null
                    setPadding(
                        dp(UiTokens.Space.SM),
                        dp(UiTokens.Space.SM),
                        dp(UiTokens.Space.SM),
                        dp(UiTokens.Space.SM)
                    )
                }, LinearLayout.LayoutParams(dp(UiTokens.MIN_TOUCH), dp(UiTokens.MIN_TOUCH)).apply {
                    marginEnd = dp(UiTokens.Space.XS)
                })
            }

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = when (backendHealth) {
                        "ONLINE" -> "Backend online"
                        "OFFLINE" -> "Backend offline"
                        "CHECKING" -> "Checking backend…"
                        else -> "Backend not checked"
                    }
                    applyType(TextRole.BODY, true)
                })
                addView(TextView(this@MainActivity).apply {
                    text = BackendConfig.url(this@MainActivity)
                    applyType(TextRole.CAPTION)
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(outlineButton(if (backendHealth == "CHECKING") "Checking" else "Check") { testBackend() }.apply {
                isEnabled = backendHealth != "CHECKING"
            })
        })
    }

    private fun settingsRow(iconRes: Int, title: String, subtitle: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(UiTokens.MIN_TOUCH)
        setPadding(
            0,
            dp(UiTokens.Space.XS),
            0,
            dp(UiTokens.Space.XS)
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }

        addView(ImageView(this@MainActivity).apply {
            setImageResource(iconRes)
            setColorFilter(themeColor(com.google.android.material.R.attr.colorPrimary))
            contentDescription = null
            setPadding(
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.SM)
            )
            background = roundedSurface(
                com.google.android.material.R.attr.colorSecondaryContainer,
                UiTokens.Radius.MEDIUM
            )
        }, LinearLayout.LayoutParams(dp(UiTokens.MIN_TOUCH), dp(UiTokens.MIN_TOUCH)).apply {
            marginEnd = dp(UiTokens.Space.SM)
        })

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                applyType(TextRole.BODY, true)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                applyType(TextRole.LABEL)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_nt_notices)
            setColorFilter(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            contentDescription = "Open " + title
            setPadding(
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.SM)
            )
        }, LinearLayout.LayoutParams(dp(UiTokens.MIN_TOUCH), dp(UiTokens.MIN_TOUCH)))
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
        val sheet = BottomSheetDialog(this)
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }

        box.addView(View(this).apply {
            background = roundedSurface(com.google.android.material.R.attr.colorOutlineVariant, 999)
        }, LinearLayout.LayoutParams(dp(42), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18)
        })

        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = n.caseNumber.ifBlank { n.cnr }
                    textSize = 21f
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@MainActivity).apply {
                    text = n.caseTitle.ifBlank { n.cnr }
                    textSize = 14f
                    alpha = .66f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusChip(n))
        }, lp(bottom = 18))

        val details = listOf(
            "CNR" to n.cnr,
            "Court" to n.courtName,
            "Petitioner" to n.petitioner,
            "Respondent" to n.respondent,
            "Petitioner advocate" to n.petitionerAdvocate,
            "Respondent advocate" to n.respondentAdvocate,
            "Next hearing" to n.nextHearing,
            "Stage" to n.caseStage,
            "Judge" to n.judge,
            "Process server" to n.processServer.ifBlank { "Unassigned" }
        )
        details.forEach { (label, value) ->
            if (value.isNotBlank() && fieldEnabled(label)) box.addView(detailRow(label, value))
        }

        box.addView(MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = if (n.processServer.isBlank()) "Assign process server" else "Change process server"
            setIconResource(R.drawable.ic_action_assign)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            isAllCaps = false
            minHeight = dp(50)
            setOnClickListener {
                sheet.dismiss()
                assignProcessServer(n)
            }
        }, lp(top = 18, bottom = 8))

        if (n.serviceStatus == "PENDING") {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(MaterialButton(this@MainActivity).apply {
                    text = "Served"
                    setIconResource(R.drawable.ic_action_served)
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    isAllCaps = false
                    minHeight = dp(50)
                    setOnClickListener {
                        sheet.dismiss()
                        markServiceStatus(n, "SERVED")
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
                addView(MaterialButton(
                    this@MainActivity,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = "Unserved"
                    setIconResource(R.drawable.ic_action_unserved)
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    isAllCaps = false
                    minHeight = dp(50)
                    setOnClickListener {
                        sheet.dismiss()
                        markServiceStatus(n, "UNSERVED")
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        } else {
            box.addView(MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "Move to pending"
                isAllCaps = false
                minHeight = dp(48)
                setOnClickListener {
                    sheet.dismiss()
                    markServiceStatus(n, "PENDING")
                }
            })
        }

        if (n.fetchedState == "RETRY_REQUIRED") {
            box.addView(outlineButton("Refresh case details") {
                sheet.dismiss()
                retryNotice(n)
            }, lp(top = 10))
        }

        scroll.addView(box)
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun detailRow(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 11f
            alpha = .52f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = 14f
            setPadding(0, dp(2), 0, 0)
        })
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

    private fun roundedSurface(attr: Int, radiusDp: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(themeColor(attr))
        }

    private fun emptyPanel(title: String, supporting: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(26), dp(18), dp(26))
        background = roundedSurface(com.google.android.material.R.attr.colorSurfaceVariant, 18)
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = supporting
            textSize = 13f
            alpha = .62f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), 0)
        })
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
