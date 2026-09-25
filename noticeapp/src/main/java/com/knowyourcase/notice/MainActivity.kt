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
import androidx.core.graphics.ColorUtils
import com.google.android.material.chip.ChipGroup
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private var loadingSnackbar: Snackbar? = null

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
        updateSystemBars()
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
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Enter CNR")
            .setMessage("Enter the 16-character CNR printed on the notice.")
            .setView(field.first)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Add", null)
            .create()

        dialog.setOnShowListener {
            val add = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            add.isEnabled = false
            field.second.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                    val raw = value?.toString().orEmpty().uppercase().replace(Regex("[^A-Z0-9]"), "")
                    val valid = Regex("[A-Z]{4}[0-9]{12}").matches(raw)
                    add.isEnabled = valid
                    field.first.error = when {
                        raw.isBlank() -> null
                        valid -> null
                        else -> "Use 4 letters followed by 12 digits."
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            add.setOnClickListener {
                val raw = field.second.text?.toString().orEmpty()
                val cleaned = raw.uppercase().replace(Regex("[^A-Z0-9]"), "")
                if (Regex("[A-Z]{4}[0-9]{12}").matches(cleaned)) {
                    field.first.error = null
                    dialog.dismiss()
                    handleCnrInput(cleaned)
                } else {
                    field.first.error = "Use 4 letters followed by 12 digits."
                }
            }
        }
        dialog.show()
    }

    private fun handleCnrInput(raw: String) {
        val cleaned = raw.uppercase().replace(Regex("[^A-Z0-9]"), "")
        val cnr = Regex("[A-Z]{4}[0-9]{12}").find(cleaned)?.value
        if (cnr == null) {
            notifyUser("CNR must contain 4 letters followed by 12 digits.")
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
            notifyUser("Notice added and queued for case lookup")
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
            notifyUser("Retrying case lookup")
            reloadAndRender()
            pumpQueue()
        }
    }

    private fun showNotice(n: NoticeEntity) {
        val sheet = BottomSheetDialog(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.SM),
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.LG)
            )
        }

        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = n.caseNumber.ifBlank { n.cnr }
                    applyType(TextRole.TITLE, true)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@MainActivity).apply {
                    text = n.caseTitle.ifBlank { n.cnr }
                    applyType(TextRole.LABEL)
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_nt_close)
                setColorFilter(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                background = roundedSurface(
                    com.google.android.material.R.attr.colorSurfaceVariant,
                    UiTokens.Radius.PILL
                )
                contentDescription = "Close notice details"
                setPadding(
                    dp(UiTokens.Space.SM),
                    dp(UiTokens.Space.SM),
                    dp(UiTokens.Space.SM),
                    dp(UiTokens.Space.SM)
                )
                setOnClickListener { sheet.dismiss() }
            }, LinearLayout.LayoutParams(dp(UiTokens.MIN_TOUCH), dp(UiTokens.MIN_TOUCH)))
        }, lp(bottom = UiTokens.Space.MD))

        box.addView(statusChip(n), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(UiTokens.Space.MD) })

        val fields = listOf(
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
        fields.forEach { pair ->
            if (pair.second.isNotBlank() && fieldEnabled(pair.first)) {
                box.addView(detailRow(pair.first, pair.second))
            }
        }

        if (n.fetchedState == "RETRY_REQUIRED") {
            box.addView(
                statePanel(
                    StateKind.ERROR,
                    "Case details unavailable",
                    n.lastError.ifBlank { "The eCourts lookup did not complete." },
                    R.drawable.ic_nt_error,
                    "Retry"
                ) {
                    sheet.dismiss()
                    retryNotice(n)
                },
                lp(top = UiTokens.Space.MD)
            )
        }

        box.addView(
            outlineButton(
                if (n.processServer.isBlank()) "Assign process server" else "Change process server",
                R.drawable.ic_nt_assign
            ) {
                sheet.dismiss()
                assignProcessServer(n)
            },
            lp(top = UiTokens.Space.MD, bottom = UiTokens.Space.XS)
        )

        if (n.serviceStatus == "PENDING") {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    primaryButton("Served", R.drawable.ic_nt_served) {
                        sheet.dismiss()
                        markServiceStatus(n, "SERVED")
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(UiTokens.Space.XS)
                    }
                )
                addView(
                    outlineButton("Unserved", R.drawable.ic_nt_unserved) {
                        sheet.dismiss()
                        markServiceStatus(n, "UNSERVED")
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            })
        } else {
            box.addView(outlineButton("Move to pending") {
                sheet.dismiss()
                markServiceStatus(n, "PENDING")
            })
        }

        scroll.addView(box)
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun detailRow(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(UiTokens.Space.XS), 0, dp(UiTokens.Space.XS))
        addView(TextView(this@MainActivity).apply {
            text = label
            applyType(TextRole.CAPTION, true)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        addView(TextView(this@MainActivity).apply {
            text = value
            applyType(TextRole.BODY)
            setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
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
                saveNotice(
                    n.copy(
                        processServer = servers[which],
                        updatedAt = System.currentTimeMillis()
                    ),
                    successMessage = "Assigned to " + servers[which]
                )
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
        val message = when (status) {
            "SERVED" -> "Notice marked Served"
            "UNSERVED" -> "Notice marked Unserved"
            else -> "Notice moved back to Pending"
        }
        saveNotice(updated, cancelReminders = status != "PENDING", successMessage = message)
    }

    private fun saveNotice(
        n: NoticeEntity,
        cancelReminders: Boolean = false,
        successMessage: String? = null
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.notices().update(n) }
            if (cancelReminders) ReminderWorker.cancel(this@MainActivity, n.id)
            else ReminderWorker.reschedule(this@MainActivity, n)
            reloadAndRender()
            if (!successMessage.isNullOrBlank()) notifyUser(successMessage)
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
            setPadding(
                dp(UiTokens.Space.XXS),
                dp(UiTokens.Space.XXS),
                dp(UiTokens.Space.XXS),
                0
            )
            addView(field.first)
            addView(TextView(this@MainActivity).apply {
                text = "Default: " + BackendConfig.DEFAULT_URL
                applyType(TextRole.CAPTION)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(UiTokens.Space.XS), 0, 0)
            })
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Backend setup")
            .setView(wrap)
            .setNeutralButton("Use default", null)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        fun validUrl(raw: String): Boolean {
            val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return false
            return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()
        }

        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val reset = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)

            fun validate(raw: String) {
                val valid = validUrl(raw)
                save.isEnabled = valid
                field.first.error = when {
                    raw.isBlank() -> "Enter a backend URL."
                    valid -> null
                    else -> "Use a complete http:// or https:// URL."
                }
            }

            validate(field.second.text?.toString().orEmpty())
            field.second.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                    validate(value?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })

            save.setOnClickListener {
                val raw = field.second.text?.toString().orEmpty().trim()
                if (validUrl(raw)) {
                    BackendConfig.save(this, raw)
                    dialog.dismiss()
                    notifyUser("Backend saved")
                    testBackend()
                }
            }
            reset.setOnClickListener {
                BackendConfig.reset(this)
                dialog.dismiss()
                notifyUser("Default backend restored")
                testBackend()
            }
        }
        dialog.show()
    }

    private fun testBackend() {
        backendHealth = "CHECKING"
        if (activeTab == TAB_SETTINGS) renderCurrentTab()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { RetrofitClient.service(this@MainActivity).health().isSuccessful }.getOrDefault(false)
            }
            backendHealth = if (ok) "ONLINE" else "OFFLINE"
            notifyUser(if (ok) "Backend is online" else "Backend check failed")
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
                notifyUser("Visible fields updated")
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
        showLoadingFeedback("Exporting " + format.uppercase() + "…")
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { db.notices().all() }
            val output = if (format == "json") Gson().toJson(data) else buildCsv(data)
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(output) }
            }
            loadingSnackbar?.dismiss()
            loadingSnackbar = null
            notifyUser(format.uppercase() + " export complete")
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
            setPadding(
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.XS),
                dp(UiTokens.Space.MD),
                dp(UiTokens.Space.MD)
            )
        }

        lateinit var dialog: AlertDialog

        fun showAddDialog() {
            val field = modernTextField(
                label = "Process server name",
                value = "",
                hint = "Enter full name",
                inputTypeValue = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            )
            val addDialog = MaterialAlertDialogBuilder(this)
                .setTitle("Add process server")
                .setView(field.first)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", null)
                .create()

            addDialog.setOnShowListener {
                val add = addDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                add.isEnabled = false
                field.second.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) {
                        val name = value?.toString().orEmpty().trim()
                        val duplicate = servers.any { it.equals(name, ignoreCase = true) }
                        add.isEnabled = name.length >= 2 && !duplicate
                        field.first.error = when {
                            name.isBlank() -> null
                            name.length < 2 -> "Enter at least 2 characters."
                            duplicate -> "This process server is already listed."
                            else -> null
                        }
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
                add.setOnClickListener {
                    val name = field.second.text?.toString().orEmpty().trim()
                    if (name.length >= 2 && servers.none { it.equals(name, ignoreCase = true) }) {
                        servers.add(name)
                        saveProcessServers(servers)
                        addDialog.dismiss()
                        notifyUser("Process server added")
                        renderList()
                    }
                }
            }
            addDialog.show()
        }

        fun confirmRemove(index: Int, name: String) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove process server?")
                .setMessage("Remove " + name + " from the assignment list? Existing notices keep their current assignment.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    servers.removeAt(index)
                    saveProcessServers(servers)
                    notifyUser("Process server removed")
                    renderList()
                }
                .show()
        }

        fun renderList() {
            container.removeAllViews()

            if (servers.isEmpty()) {
                container.addView(
                    statePanel(
                        StateKind.EMPTY,
                        "No process servers",
                        "Add the people who can be assigned notice service.",
                        R.drawable.ic_nt_people,
                        "Add process server"
                    ) { showAddDialog() },
                    lp(bottom = UiTokens.Space.SM)
                )
            } else {
                servers.forEachIndexed { index, name ->
                    container.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(UiTokens.MIN_TOUCH)
                        setPadding(0, dp(UiTokens.Space.XS), 0, dp(UiTokens.Space.XS))
                        addView(TextView(this@MainActivity).apply {
                            text = name
                            applyType(TextRole.BODY, true)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                        addView(outlineButton("Remove") {
                            confirmRemove(index, name)
                        })
                    })
                }

                container.addView(
                    primaryButton("Add process server", R.drawable.ic_nt_assign) { showAddDialog() },
                    lp(top = UiTokens.Space.SM)
                )
            }
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
            applyType(TextRole.BODY)
            minHeight = dp(UiTokens.MIN_TOUCH)
            if (value.isNotBlank()) setSelection(text?.length ?: 0)
        }
        val layout = TextInputLayout(this).apply {
            this.hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(
                dp(UiTokens.Radius.MEDIUM).toFloat(),
                dp(UiTokens.Radius.MEDIUM).toFloat(),
                dp(UiTokens.Radius.MEDIUM).toFloat(),
                dp(UiTokens.Radius.MEDIUM).toFloat()
            )
            setPadding(0, dp(UiTokens.Space.XS), 0, 0)
            addView(input)
        }
        return layout to input
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr))
        val c = a.getColor(0, 0)
        a.recycle()
        return c
    }

    private fun notifyUser(message: String) {
        if (!::appRoot.isInitialized) return
        Snackbar.make(appRoot, message, Snackbar.LENGTH_SHORT)
            .setAnchorView(bottomNav)
            .show()
    }

    private fun showLoadingFeedback(message: String) {
        if (!::appRoot.isInitialized) return
        loadingSnackbar?.dismiss()
        val bar = Snackbar.make(appRoot, message, Snackbar.LENGTH_INDEFINITE)
            .setAnchorView(bottomNav)
        val layout = bar.view as? com.google.android.material.snackbar.Snackbar.SnackbarLayout
        if (layout != null) {
            val progress = com.google.android.material.progressindicator.CircularProgressIndicator(this).apply {
                isIndeterminate = true
                indicatorSize = dp(UiTokens.Icon.SUPPORT)
                trackThickness = dp(UiTokens.Space.XXS)
            }
            layout.addView(progress, 0, FrameLayout.LayoutParams(
                dp(UiTokens.Icon.SUPPORT),
                dp(UiTokens.Icon.SUPPORT),
                Gravity.CENTER_VERTICAL or Gravity.START
            ).apply {
                marginStart = dp(UiTokens.Space.SM)
            })
            layout.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
                setPadding(dp(UiTokens.Space.XL), paddingTop, paddingRight, paddingBottom)
            }
        }
        bar.show()
        loadingSnackbar = bar
    }

    private fun animateContentIn() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            content.alpha = 1f
            return
        }
        content.animate().cancel()
        content.alpha = 0f
        content.animate()
            .alpha(1f)
            .setDuration(UiTokens.Motion.FAST)
            .start()
    }

    private fun updateSystemBars() {
        val surface = themeColor(com.google.android.material.R.attr.colorSurface)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = surface
        val light = ColorUtils.calculateLuminance(surface) > 0.5
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun showFirstRunIfNeeded() {
        if (prefs.getBoolean(KEY_ONBOARDED, false) || isFinishing || isDestroyed) return

        val sheet = BottomSheetDialog(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(UiTokens.Space.LG),
                dp(UiTokens.Space.LG),
                dp(UiTokens.Space.LG),
                dp(UiTokens.Space.LG)
            )
        }

        body.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_nt_notices)
            setColorFilter(themeColor(com.google.android.material.R.attr.colorPrimary))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(UiTokens.Icon.EMPTY), dp(UiTokens.Icon.EMPTY)).apply {
            bottomMargin = dp(UiTokens.Space.MD)
        })

        body.addView(TextView(this).apply {
            text = "Your notice desk, in three steps"
            applyType(TextRole.HEADLINE, true)
        })
        body.addView(TextView(this).apply {
            text = "Scan the notice, assign a process server, then close the work as Served or Unserved."
            applyType(TextRole.BODY)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(UiTokens.Space.XS), 0, dp(UiTokens.Space.LG))
        })

        fun step(number: String, title: String, copy: String) {
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(UiTokens.Space.XS), 0, dp(UiTokens.Space.XS))
                addView(TextView(this@MainActivity).apply {
                    text = number
                    applyType(TextRole.LABEL, true)
                    gravity = Gravity.CENTER
                    background = roundedSurface(
                        com.google.android.material.R.attr.colorPrimaryContainer,
                        UiTokens.Radius.PILL
                    )
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
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
                        text = copy
                        applyType(TextRole.LABEL)
                        setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                        setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }

        step("1", "Scan", "Read the eCourts QR code or enter the CNR manually.")
        step("2", "Assign", "Choose the process server responsible for service.")
        step("3", "Complete", "Served and Unserved both complete the notice; Pending means no action yet.")

        body.addView(primaryButton("Scan first notice", R.drawable.ic_nt_scan) {
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
            sheet.dismiss()
            scanner.launch(android.content.Intent(this, ModernScannerActivity::class.java))
        }, lp(top = UiTokens.Space.LG))

        body.addView(outlineButton("Not now") {
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
            sheet.dismiss()
        }, lp(top = UiTokens.Space.XS))

        sheet.setContentView(body)
        sheet.setOnDismissListener {
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        }
        sheet.show()
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAB_HOME = 1
        private const val TAB_TRACK = 2
        private const val TAB_SETTINGS = 3
        private const val PREFS = "notice_tracker_settings"
        private const val KEY_THEME = "theme"
        private const val KEY_PROCESS_SERVERS = "process_servers"
        private const val KEY_ONBOARDED = "onboarded_v1"
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
