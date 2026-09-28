from pathlib import Path
import re

MAIN = Path('noticeapp/src/main/java/com/knowyourcase/notice/MainActivity.kt')
DB = Path('noticeapp/src/main/java/com/knowyourcase/notice/NoticeDatabase.kt')
GRADLE = Path('noticeapp/build.gradle')

s = MAIN.read_text()

if 'private const val TAB_RECEIVE' not in s:
    def rep(old, new, label):
        global s
        if old not in s:
            raise SystemExit('Missing expected block: ' + label)
        s = s.replace(old, new, 1)

    # Date formatting for allotment/receipt reporting.
    rep('import java.time.LocalDate\n', 'import java.time.LocalDate\nimport java.time.Instant\nimport java.time.ZoneId\nimport java.time.format.DateTimeFormatter\n', 'date imports')

    # Scanner now has explicit allot/receive modes.
    rep('''    private var activeTab = TAB_HOME
    private var trackerFilter = "PENDING"
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
''','''    private var activeTab = TAB_HOME
    private var trackerFilter = "PENDING"
    private var scanMode = SCAN_NONE
    private var scanServer = ""
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
''', 'scan state fields')

    rep('''    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra(ModernScannerActivity.EXTRA_SCAN_RESULT)?.let(::handleCnrInput)
        }
    }
''','''    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val raw = result.data?.getStringExtra(ModernScannerActivity.EXTRA_SCAN_RESULT).orEmpty()
            when (scanMode) {
                SCAN_ALLOT -> {
                    val server = scanServer
                    scanMode = SCAN_NONE
                    scanServer = ""
                    handleAllotScan(raw, server)
                }
                SCAN_RECEIVE -> {
                    scanMode = SCAN_NONE
                    scanServer = ""
                    handleReceiveScan(raw)
                }
                else -> handleCnrInput(raw)
            }
        } else {
            scanMode = SCAN_NONE
            scanServer = ""
        }
    }
''', 'scanner callback')

    # Five-tab navigation: third tab is Receive as requested.
    old_nav = '''        bottomNav = BottomNavigationView(this).apply {
            menu.add(0, TAB_HOME, 0, "Desk").setIcon(R.drawable.ic_nt_desk)
            menu.add(0, TAB_TRACK, 1, "Notices").setIcon(R.drawable.ic_nt_notices)
            menu.add(0, TAB_SERVERS, 2, "Servers").setIcon(R.drawable.ic_nt_people)
            menu.add(0, TAB_SETTINGS, 3, "Settings").setIcon(R.drawable.ic_nt_settings)
            selectedItemId = TAB_HOME
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener {
                activeTab = it.itemId
                renderCurrentTab()
                true
            }
        }'''
    new_nav = '''        bottomNav = BottomNavigationView(this).apply {
            menu.add(0, TAB_HOME, 0, "Desk").setIcon(R.drawable.ic_nt_desk)
            menu.add(0, TAB_SERVERS, 1, "Allot").setIcon(R.drawable.ic_nt_assign)
            menu.add(0, TAB_RECEIVE, 2, "Receive").setIcon(R.drawable.ic_nt_served)
            menu.add(0, TAB_TRACK, 3, "Notices").setIcon(R.drawable.ic_nt_notices)
            menu.add(0, TAB_SETTINGS, 4, "Settings").setIcon(R.drawable.ic_nt_settings)
            selectedItemId = TAB_HOME
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            setOnItemSelectedListener {
                activeTab = it.itemId
                renderCurrentTab()
                true
            }
        }'''
    rep(old_nav, new_nav, 'bottom navigation')

    # Route the new third tab.
    current_start = s.index('    private fun renderCurrentTab() {')
    current_end = s.index('    private fun navigateToDesk()', current_start)
    if current_start < 0 or current_end < 0:
        raise SystemExit('Missing renderCurrentTab region')
    s = s[:current_start] + '''    private fun renderCurrentTab() {
        if (!::content.isInitialized) return
        content.removeAllViews()
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)
        when (activeTab) {
            TAB_SERVERS -> {
                toolbar.title = "Allot notices"
                toolbar.subtitle = "Choose a process server, then scan"
                renderServers()
            }
            TAB_RECEIVE -> {
                toolbar.title = "Receive notices"
                toolbar.subtitle = "Scan returned notices and close service"
                renderReceive()
            }
            TAB_TRACK -> {
                toolbar.title = "Notices"
                toolbar.subtitle = "Pending and completed notice register"
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

''' + s[current_end:]

    # Desk becomes workflow-first rather than generic scanning.
    old_home_actions = '''        root.addView(
            primaryButton("Scan court notice", R.drawable.ic_nt_scan) {
                scanner.launch(android.content.Intent(this@MainActivity, ModernScannerActivity::class.java))
            }.apply { minHeight = dp(UiTokens.Size.PRIMARY_ACTION) },
            lp(bottom = UiTokens.Space.XS)
        )

        root.addView(
            outlineButton("Enter CNR manually", R.drawable.ic_nt_keyboard) { showManualEntry() },
            lp(bottom = UiTokens.Space.LG)
        )
'''
    new_home_actions = '''        root.addView(
            primaryButton("Allot notices", R.drawable.ic_nt_assign) {
                activeTab = TAB_SERVERS
                bottomNav.selectedItemId = TAB_SERVERS
            }.apply { minHeight = dp(UiTokens.Size.PRIMARY_ACTION) },
            lp(bottom = UiTokens.Space.XS)
        )

        root.addView(
            outlineButton("Receive returned notices", R.drawable.ic_nt_served) {
                activeTab = TAB_RECEIVE
                bottomNav.selectedItemId = TAB_RECEIVE
            },
            lp(bottom = UiTokens.Space.LG)
        )
'''
    rep(old_home_actions, new_home_actions, 'desk actions')

    # Replace server roster with allotment workflow and add Receive/report tab.
    server_start = s.index('    private fun renderServers() {')
    server_end = s.index('    private fun renderSettings() {', server_start)
    if server_start < 0 or server_end < 0:
        raise SystemExit('Missing renderServers region')

    server_receive_block = r'''    private fun renderServers() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = page()
        val servers = processServers()

        root.addView(
            heading(
                "Allot notices",
                if (servers.isEmpty()) "Add process servers first. Then open a name and scan notices directly into that person's allotment."
                else "Open a process server, then scan each notice you are handing over."
            ),
            lp(bottom = UiTokens.Space.MD)
        )

        root.addView(
            outlineButton("Manage process servers", R.drawable.ic_nt_people) { showProcessServerSettings() },
            lp(bottom = UiTokens.Space.LG)
        )

        if (servers.isEmpty()) {
            root.addView(
                statePanel(
                    StateKind.EMPTY,
                    "Add process servers first",
                    "Once names are added, each person gets their own allotment screen.",
                    R.drawable.ic_nt_people,
                    "Add process server"
                ) { showProcessServerSettings() }
            )
        } else {
            root.addView(sectionTitle("Choose process server"))
            servers.forEach { name ->
                val assigned = notices.filter { it.processServer.equals(name, ignoreCase = true) }
                val pending = assigned.count { it.serviceStatus == "PENDING" }
                root.addView(designCard().apply {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showServerAllotment(name) }
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(
                            dp(UiTokens.Space.MD), dp(UiTokens.Space.SM),
                            dp(UiTokens.Space.MD), dp(UiTokens.Space.SM)
                        )
                        addView(ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.ic_nt_people)
                            setColorFilter(themeColor(com.google.android.material.R.attr.colorPrimary))
                            background = roundedSurface(
                                com.google.android.material.R.attr.colorPrimaryContainer,
                                UiTokens.Radius.MEDIUM
                            )
                            setPadding(
                                dp(UiTokens.Space.SM), dp(UiTokens.Space.SM),
                                dp(UiTokens.Space.SM), dp(UiTokens.Space.SM)
                            )
                        }, LinearLayout.LayoutParams(dp(UiTokens.MIN_TOUCH), dp(UiTokens.MIN_TOUCH)).apply {
                            marginEnd = dp(UiTokens.Space.SM)
                        })
                        addView(LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(TextView(this@MainActivity).apply {
                                text = name
                                applyType(TextRole.BODY, true)
                                maxLines = 1
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            })
                            addView(TextView(this@MainActivity).apply {
                                text = assigned.size.toString() + " allotted  •  " + pending + " pending"
                                applyType(TextRole.LABEL)
                                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                                setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
                            })
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.ic_nt_scan)
                            setColorFilter(themeColor(com.google.android.material.R.attr.colorPrimary))
                            contentDescription = "Open and scan for " + name
                            setPadding(dp(UiTokens.Space.SM), dp(UiTokens.Space.SM), dp(UiTokens.Space.SM), dp(UiTokens.Space.SM))
                        }, LinearLayout.LayoutParams(dp(UiTokens.MIN_TOUCH), dp(UiTokens.MIN_TOUCH)))
                    })
                }, lp(bottom = UiTokens.Space.XS))
            }
        }

        scroll.addView(root)
        content.addView(scroll)
    }

    private fun showServerAllotment(server: String) {
        val sheet = BottomSheetDialog(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(UiTokens.Space.MD), dp(UiTokens.Space.SM),
                dp(UiTokens.Space.MD), dp(UiTokens.Space.LG)
            )
        }
        val assigned = notices.filter { it.processServer.equals(server, ignoreCase = true) }
        val pending = assigned.filter { it.serviceStatus == "PENDING" }

        box.addView(heading(server, "Scan notices here to allot them automatically to this process server."), lp(bottom = UiTokens.Space.MD))
        box.addView(primaryButton("Scan & allot notice", R.drawable.ic_nt_scan) {
            sheet.dismiss()
            launchAllotScan(server)
        }, lp(bottom = UiTokens.Space.SM))

        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(reportMetric(assigned.size, "Allotted") { sheet.dismiss(); showServerNoticeList(server, "ALL") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(UiTokens.Space.XS) })
            addView(reportMetric(pending.size, "Pending") { sheet.dismiss(); showServerNoticeList(server, "PENDING") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, lp(bottom = UiTokens.Space.LG))

        box.addView(sectionTitle("Current pending notices"))
        if (pending.isEmpty()) {
            box.addView(statePanel(StateKind.EMPTY, "No pending notices", "New allotments will appear here.", R.drawable.ic_nt_empty))
        } else {
            pending.take(8).forEach { box.addView(reportNoticeRow(it), lp(bottom = UiTokens.Space.XS)) }
        }

        scroll.addView(box)
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun renderReceive() {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = page()
        val allotted = notices.count { it.processServer.isNotBlank() || it.allottedAt > 0L }
        val received = notices.count { it.serviceStatus != "PENDING" && it.receivedAt > 0L }
        val pending = notices.count { it.serviceStatus == "PENDING" }

        root.addView(
            heading("Receive returned notices", "Scan every notice returned by a process server. Choose Served or Unserved after the scan."),
            lp(bottom = UiTokens.Space.MD)
        )
        root.addView(primaryButton("Scan received notice", R.drawable.ic_nt_scan) { launchReceiveScan() }.apply {
            minHeight = dp(UiTokens.Size.PRIMARY_ACTION)
        }, lp(bottom = UiTokens.Space.LG))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(reportMetric(allotted, "Allotted") { showServerNoticeList("", "ALLOTTED") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(UiTokens.Space.XS) })
            addView(reportMetric(received, "Received") { showServerNoticeList("", "RECEIVED") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(UiTokens.Space.XS) })
            addView(reportMetric(pending, "Pending") { showServerNoticeList("", "PENDING") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, lp(bottom = UiTokens.Space.LG))

        root.addView(sectionTitle("Process server report"))
        val servers = processServers()
        if (servers.isEmpty()) {
            root.addView(statePanel(StateKind.EMPTY, "No process servers", "Add process servers from the Allot tab first.", R.drawable.ic_nt_people))
        } else {
            servers.forEach { server ->
                val assigned = notices.filter { it.processServer.equals(server, ignoreCase = true) }
                val rec = assigned.count { it.serviceStatus != "PENDING" && it.receivedAt > 0L }
                val pend = assigned.count { it.serviceStatus == "PENDING" }
                root.addView(serverReportCard(server, assigned.size, rec, pend), lp(bottom = UiTokens.Space.SM))
            }
        }

        val unallotted = notices.filter { it.processServer.isBlank() && it.receivedAt > 0L }
        if (unallotted.isNotEmpty()) {
            root.addView(sectionTitle("Received without prior allotment"), lp(top = UiTokens.Space.MD))
            root.addView(serverReportCard("Unallotted", 0, unallotted.size, 0), lp(bottom = UiTokens.Space.SM))
        }

        scroll.addView(root)
        content.addView(scroll)
    }

    private fun serverReportCard(server: String, allotted: Int, received: Int, pending: Int) = designCard().apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(UiTokens.Space.MD), dp(UiTokens.Space.SM), dp(UiTokens.Space.MD), dp(UiTokens.Space.MD))
            addView(TextView(this@MainActivity).apply {
                text = server
                applyType(TextRole.BODY, true)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(UiTokens.Space.SM), 0, 0)
                addView(reportMetric(allotted, "Allotted") { showServerNoticeList(server, "ALL") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(UiTokens.Space.XS) })
                addView(reportMetric(received, "Received") { showServerNoticeList(server, "RECEIVED") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(UiTokens.Space.XS) })
                addView(reportMetric(pending, "Pending") { showServerNoticeList(server, "PENDING") }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        })
    }

    private fun reportMetric(count: Int, label: String, click: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        minimumHeight = dp(UiTokens.MIN_TOUCH)
        setPadding(dp(UiTokens.Space.XXS), dp(UiTokens.Space.XS), dp(UiTokens.Space.XXS), dp(UiTokens.Space.XS))
        background = roundedSurface(com.google.android.material.R.attr.colorSurfaceVariant, UiTokens.Radius.MEDIUM)
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        addView(TextView(this@MainActivity).apply {
            text = count.toString()
            applyType(TextRole.TITLE, true)
            gravity = Gravity.CENTER
        })
        addView(TextView(this@MainActivity).apply {
            text = label
            applyType(TextRole.CAPTION, true)
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER
            maxLines = 1
        })
    }

    private fun showServerNoticeList(server: String, filter: String) {
        val sheet = BottomSheetDialog(this)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(UiTokens.Space.MD), dp(UiTokens.Space.SM), dp(UiTokens.Space.MD), dp(UiTokens.Space.LG))
        }
        val base = when {
            server == "Unallotted" -> notices.filter { it.processServer.isBlank() && it.receivedAt > 0L }
            server.isBlank() -> notices
            else -> notices.filter { it.processServer.equals(server, ignoreCase = true) }
        }
        val shown = when (filter) {
            "PENDING" -> base.filter { it.serviceStatus == "PENDING" }
            "RECEIVED" -> base.filter { it.serviceStatus != "PENDING" && it.receivedAt > 0L }
            "ALLOTTED" -> base.filter { it.processServer.isNotBlank() || it.allottedAt > 0L }
            else -> base
        }
        val title = if (server.isBlank()) filter.lowercase().replaceFirstChar { it.uppercase() } + " notices" else server
        box.addView(heading(title, shown.size.toString() + " notice" + if (shown.size == 1) "" else "s"), lp(bottom = UiTokens.Space.MD))
        if (shown.isEmpty()) {
            box.addView(statePanel(StateKind.EMPTY, "No notices", "Nothing matches this report yet.", R.drawable.ic_nt_empty))
        } else {
            shown.sortedByDescending { maxOf(it.receivedAt, it.allottedAt, it.scannedAt) }.forEach {
                box.addView(reportNoticeRow(it), lp(bottom = UiTokens.Space.XS))
            }
        }
        scroll.addView(box)
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun reportNoticeRow(n: NoticeEntity) = designCard().apply {
        isClickable = true
        setOnClickListener { showNotice(n) }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(UiTokens.Space.MD), dp(UiTokens.Space.SM), dp(UiTokens.Space.MD), dp(UiTokens.Space.SM))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = n.caseTitle.ifBlank { n.caseNumber.ifBlank { n.cnr } }
                    applyType(TextRole.BODY, true)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(statusChip(n))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Allotted: " + formatStamp(n.allottedAt) + "  •  Received: " + formatStamp(n.receivedAt)
                applyType(TextRole.CAPTION)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(UiTokens.Space.XS), 0, 0)
            })
            if (n.processServer.isNotBlank()) {
                addView(TextView(this@MainActivity).apply {
                    text = n.processServer
                    applyType(TextRole.LABEL)
                    setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setPadding(0, dp(UiTokens.Space.XXS), 0, 0)
                })
            }
        })
    }

    private fun launchAllotScan(server: String) {
        scanMode = SCAN_ALLOT
        scanServer = server
        scanner.launch(android.content.Intent(this, ModernScannerActivity::class.java))
    }

    private fun launchReceiveScan() {
        scanMode = SCAN_RECEIVE
        scanServer = ""
        scanner.launch(android.content.Intent(this, ModernScannerActivity::class.java))
    }

    private fun formatStamp(value: Long): String {
        if (value <= 0L) return "—"
        return DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(value))
    }

'''
    s = s[:server_start] + server_receive_block + s[server_end:]

    # Replace duplicate/general CNR behavior with explicit allot and receive flows.
    handle_start = s.index('    private fun handleCnrInput(raw: String) {')
    create_start = s.index('    private fun createNotice(cnr: String) {', handle_start)
    if handle_start < 0 or create_start < 0:
        raise SystemExit('Missing handleCnrInput/createNotice region')
    new_handlers = r'''    private fun parseCnr(raw: String): String? {
        val cleaned = raw.uppercase().replace(Regex("[^A-Z0-9]"), "")
        return Regex("[A-Z]{4}[0-9]{12}").find(cleaned)?.value
    }

    private fun handleCnrInput(raw: String) {
        val cnr = parseCnr(raw)
        if (cnr == null) {
            notifyUser("CNR must contain 4 letters followed by 12 digits.")
            return
        }
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { db.notices().byCnr(cnr).firstOrNull() }
            if (existing != null) showNotice(existing)
            else notifyUser("Use Allot or Receive so the notice is recorded in the correct workflow.")
        }
    }

    private fun handleAllotScan(raw: String, server: String) {
        val cnr = parseCnr(raw)
        if (cnr == null || server.isBlank()) {
            notifyUser("Could not read a valid CNR from this notice.")
            return
        }
        val now = System.currentTimeMillis()
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { db.notices().byCnr(cnr).firstOrNull() }
            if (existing != null && existing.serviceStatus != "PENDING") {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Notice already completed")
                    .setMessage("This notice has already been received as " + if (existing.serviceStatus == "UNSERVED") "Unserved." else "Served.")
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Open") { _, _ -> showNotice(existing) }
                    .show()
                return@launch
            }

            val notice = if (existing == null) {
                NoticeEntity(
                    cnr = cnr,
                    processServer = server,
                    serviceStatus = "PENDING",
                    fetchedState = "QUEUED",
                    allottedAt = now,
                    scannedAt = now,
                    updatedAt = now
                )
            } else {
                existing.copy(
                    processServer = server,
                    allottedAt = if (existing.allottedAt > 0L) existing.allottedAt else now,
                    updatedAt = now
                )
            }

            if (existing == null) {
                withContext(Dispatchers.IO) { db.notices().insert(notice) }
            } else {
                withContext(Dispatchers.IO) { db.notices().update(notice) }
                ReminderWorker.reschedule(this@MainActivity, notice)
            }
            notifyUser("Notice allotted to " + server)
            reloadAndRender()
            pumpQueue()
        }
    }

    private fun handleReceiveScan(raw: String) {
        val cnr = parseCnr(raw)
        if (cnr == null) {
            notifyUser("Could not read a valid CNR from this returned notice.")
            return
        }
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { db.notices().byCnr(cnr).firstOrNull() }
            if (existing != null && existing.receivedAt > 0L && existing.serviceStatus != "PENDING") {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Already received")
                    .setMessage("This notice was already received on " + formatStamp(existing.receivedAt) + ".")
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Open") { _, _ -> showNotice(existing) }
                    .show()
                return@launch
            }
            showReceiveStatusDialog(cnr, existing)
        }
    }

    private fun showReceiveStatusDialog(cnr: String, existing: NoticeEntity?) {
        val serverLine = existing?.processServer?.takeIf { it.isNotBlank() }?.let { "\nAllotted to: " + it }.orEmpty()
        MaterialAlertDialogBuilder(this)
            .setTitle("Returned notice received")
            .setMessage("CNR: " + cnr + serverLine + "\n\nHow was service completed?")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Unserved") { _, _ -> completeReceivedNotice(cnr, existing, "UNSERVED") }
            .setPositiveButton("Served") { _, _ -> completeReceivedNotice(cnr, existing, "SERVED") }
            .show()
    }

    private fun completeReceivedNotice(cnr: String, existing: NoticeEntity?, status: String) {
        val now = System.currentTimeMillis()
        lifecycleScope.launch {
            val notice = if (existing == null) {
                NoticeEntity(
                    cnr = cnr,
                    serviceStatus = status,
                    fetchedState = "QUEUED",
                    receivedAt = now,
                    scannedAt = now,
                    updatedAt = now
                )
            } else {
                existing.copy(
                    serviceStatus = status,
                    receivedAt = now,
                    updatedAt = now
                )
            }
            if (existing == null) {
                withContext(Dispatchers.IO) { db.notices().insert(notice) }
            } else {
                withContext(Dispatchers.IO) { db.notices().update(notice) }
                ReminderWorker.cancel(this@MainActivity, notice.id)
            }
            notifyUser("Received notice marked " + if (status == "UNSERVED") "Unserved" else "Served")
            activeTab = TAB_RECEIVE
            bottomNav.selectedItemId = TAB_RECEIVE
            reloadAndRender()
            pumpQueue()
        }
    }

'''
    s = s[:handle_start] + new_handlers + s[create_start:]

    # When manually changing status, record receipt timestamp on completion.
    rep('''        val updated = n.copy(
            serviceStatus = status,
            updatedAt = System.currentTimeMillis()
        )''','''        val now = System.currentTimeMillis()
        val updated = n.copy(
            serviceStatus = status,
            receivedAt = if (status == "PENDING") 0L else if (n.receivedAt > 0L) n.receivedAt else now,
            updatedAt = now
        )''', 'manual service status receipt timestamp')

    # Export report dates too.
    rep('''        val header = "CNR,Case Number,Case Title,Court,Petitioner,Respondent,Advocates,Next Hearing,Stage,Process Server,Service,Fetch State"''','''        val header = "CNR,Case Number,Case Title,Court,Petitioner,Respondent,Advocates,Next Hearing,Stage,Process Server,Allotted On,Received On,Service,Fetch State"''', 'csv header')
    rep('''                n.nextHearing,n.caseStage,n.processServer,
                when (n.serviceStatus) {''','''                n.nextHearing,n.caseStage,n.processServer,formatStamp(n.allottedAt),formatStamp(n.receivedAt),
                when (n.serviceStatus) {''', 'csv dates')

    # Onboarding reflects actual new workflow.
    rep('''        step("1", "Scan", "Read the eCourts QR code or enter the CNR manually.")
        step("2", "Assign", "Choose the process server responsible for service.")
        step("3", "Scan return", "Scan the same Pending CNR again when the served notice comes back; confirm it to move the notice to Completed.")''','''        step("1", "Add servers", "Add your process-server names once from the Allot tab.")
        step("2", "Allot", "Open a process server and scan notices there so assignment is automatic.")
        step("3", "Receive", "Use the third tab to scan returned notices and mark each Served or Unserved.")''', 'onboarding')

    # Constants / modes.
    rep('''        private const val TAB_HOME = 1
        private const val TAB_TRACK = 2
        private const val TAB_SERVERS = 3
        private const val TAB_SETTINGS = 4''','''        private const val TAB_HOME = 1
        private const val TAB_SERVERS = 2
        private const val TAB_RECEIVE = 3
        private const val TAB_TRACK = 4
        private const val TAB_SETTINGS = 5
        private const val SCAN_NONE = 0
        private const val SCAN_ALLOT = 1
        private const val SCAN_RECEIVE = 2''', 'tab and scan constants')

    MAIN.write_text(s)

# Room schema migration for historical allotment and receipt reporting.
d = DB.read_text()
if 'val allottedAt:' not in d:
    d = d.replace('''    val lastError: String = "",
    val scannedAt: Long = System.currentTimeMillis(),''','''    val lastError: String = "",
    val allottedAt: Long = 0,
    val receivedAt: Long = 0,
    val scannedAt: Long = System.currentTimeMillis(),''')
    d = d.replace('@Database(entities = [NoticeEntity::class], version = 4, exportSchema = false)', '@Database(entities = [NoticeEntity::class], version = 5, exportSchema = false)')
    migration = '''
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notices ADD COLUMN allottedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notices ADD COLUMN receivedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE notices SET allottedAt = scannedAt WHERE processServer != ''")
                db.execSQL("UPDATE notices SET receivedAt = updatedAt WHERE serviceStatus IN ('SERVED','UNSERVED')")
            }
        }
'''
    d = d.replace('''        @Volatile private var INSTANCE: NoticeDatabase? = null''', migration + '''
        @Volatile private var INSTANCE: NoticeDatabase? = null''')
    d = d.replace('.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)', '.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)')
    DB.write_text(d)

g = GRADLE.read_text()
g = re.sub(r'versionCode\s+\d+', 'versionCode 10', g)
g = re.sub(r'versionName\s+"[^"]+"', 'versionName "0.8.0-debug"', g)
GRADLE.write_text(g)

print('Process-server allotment/receive workflow ready')
