from pathlib import Path
import re

MAIN = Path('noticeapp/src/main/java/com/knowyourcase/notice/MainActivity.kt')
GRADLE = Path('noticeapp/build.gradle')
WORKFLOW = Path('.github/workflows/notice-tracker-debug.yml')

s = MAIN.read_text()
MARKER = '// BATCH_QUEUE_V1'
if MARKER not in s:
    s = s.replace('import kotlinx.coroutines.Dispatchers\n', 'import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.Job\n', 1)

    old_fields = '''    private var scanMode = SCAN_NONE
    private var scanServer = ""
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
'''
    new_fields = '''    private var scanMode = SCAN_NONE
    private var scanServer = ""
    private var scannerInFlight = false
    private var batchAllotServer = ""
    private var queuePumpBusy = false
    private var restartAfterLookupId: Long = -1L
    private var reloadJob: Job? = null
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    // BATCH_QUEUE_V1
'''
    if old_fields not in s:
        raise SystemExit('Missing scan state fields')
    s = s.replace(old_fields, new_fields, 1)

    scan_start = s.index('    private val scanner = registerForActivityResult')
    lookup_start = s.index('    private val lookup = registerForActivityResult', scan_start)
    if scan_start < 0 or lookup_start < 0:
        raise SystemExit('Missing scanner/lookup launchers')
    scanner_block = '''    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scannerInFlight = false
        if (result.resultCode == RESULT_OK) {
            val raw = result.data?.getStringExtra(ModernScannerActivity.EXTRA_SCAN_RESULT).orEmpty()
            when (scanMode) {
                SCAN_ALLOT -> {
                    val server = scanServer
                    val keepBatching = batchAllotServer.equals(server, ignoreCase = true)
                    scanMode = SCAN_NONE
                    scanServer = ""
                    handleAllotScan(raw, server, keepBatching)
                }
                SCAN_RECEIVE -> {
                    scanMode = SCAN_NONE
                    scanServer = ""
                    handleReceiveScan(raw)
                }
                else -> handleCnrInput(raw)
            }
        } else {
            if (scanMode == SCAN_ALLOT) batchAllotServer = ""
            scanMode = SCAN_NONE
            scanServer = ""
        }
    }

'''
    s = s[:scan_start] + scanner_block + s[lookup_start:]

    lookup_start = s.index('    private val lookup = registerForActivityResult')
    export_start = s.index('    private val exportCsv =', lookup_start)
    if export_start < 0:
        raise SystemExit('Missing lookup/export boundary')
    lookup_block = '''    private val lookup = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = lookupNoticeId
        lookupNoticeId = -1
        queuePumpBusy = false
        if (id < 0) {
            pumpQueue()
            return@registerForActivityResult
        }
        val json = result.data?.getStringExtra(ECourtWebViewActivity.EXTRA_RESULT_JSON)
        val error = result.data?.getStringExtra(ECourtWebViewActivity.EXTRA_ERROR)
        lifecycleScope.launch {
            val old = withContext(Dispatchers.IO) { db.notices().byId(id) }
            if (old != null) {
                if (restartAfterLookupId == id) {
                    restartAfterLookupId = -1L
                    withContext(Dispatchers.IO) {
                        db.notices().update(
                            old.copy(
                                fetchedState = "QUEUED",
                                fetchPriority = FETCH_PRIORITY_RETRY,
                                lastError = "",
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                } else {
                    val merged = if (!json.isNullOrBlank()) mergeCase(old, json) else old.copy(
                        fetchedState = "RETRY_REQUIRED",
                        fetchPriority = 0,
                        lastError = error ?: "Case details could not be fetched",
                        updatedAt = System.currentTimeMillis()
                    )
                    val updated = compactCompletedIfReady(merged)
                    withContext(Dispatchers.IO) { db.notices().update(updated) }
                    if (updated.serviceStatus == "PENDING") {
                        ReminderWorker.reschedule(this@MainActivity, updated)
                    } else {
                        ReminderWorker.cancel(this@MainActivity, updated.id)
                    }
                }
            }
            reloadAndRender()
            pumpQueue()
        }
    }

'''
    s = s[:lookup_start] + lookup_block + s[export_start:]

    old_reload = '''    private fun reloadAndRender() {
        lifecycleScope.launch {
            notices = withContext(Dispatchers.IO) { db.notices().all() }
            renderCurrentTab()
        }
    }
'''
    new_reload = '''    private fun reloadAndRender() {
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch {
            notices = withContext(Dispatchers.IO) { db.notices().all() }
            if (!isFinishing && !isDestroyed) renderCurrentTab()
        }
    }
'''
    if old_reload not in s:
        raise SystemExit('Missing reloadAndRender')
    s = s.replace(old_reload, new_reload, 1)

    old_startup = '''        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.notices().recoverInterruptedFetches() }
            reloadAndRender()
            pumpQueue()
        }
'''
    new_startup = '''        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.notices().recoverInterruptedFetches()
                db.notices().all()
                    .filter { it.serviceStatus != "PENDING" && it.archiveText.isBlank() && it.fetchedState == "READY" }
                    .forEach { db.notices().update(compactCompletedIfReady(it)) }
            }
            reloadAndRender()
            pumpQueue()
        }
'''
    if old_startup not in s:
        raise SystemExit('Missing startup recovery')
    s = s.replace(old_startup, new_startup, 1)

    allot_start = s.index('    private fun handleAllotScan(')
    receive_start = s.index('    private fun handleReceiveScan(', allot_start)
    if allot_start < 0 or receive_start < 0:
        raise SystemExit('Missing allot/receive functions')
    allot_block = '''    private fun handleAllotScan(raw: String, server: String, continueBatch: Boolean = false) {
        val cnr = parseCnr(raw)
        if (cnr == null || server.isBlank()) {
            notifyUser("Could not read a valid CNR from this notice.")
            if (continueBatch) continueAllotBatch(server)
            return
        }
        val now = System.currentTimeMillis()
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { db.notices().byCnr(cnr).firstOrNull() }
            if (existing != null && (existing.processServer.isNotBlank() || existing.allottedAt > 0L)) {
                val owner = existing.processServer.ifBlank { "another process server" }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Notice already allotted")
                    .setMessage("This notice is already allotted to " + owner + ". It was not changed.")
                    .setNegativeButton("Stop scanning") { _, _ -> batchAllotServer = "" }
                    .setNeutralButton("Open notice") { _, _ -> showNotice(existing) }
                    .setPositiveButton("Continue scanning") { _, _ ->
                        if (continueBatch) continueAllotBatch(server)
                    }
                    .show()
                return@launch
            }
            if (existing != null && existing.serviceStatus != "PENDING") {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Notice already completed")
                    .setMessage("This notice has already been received as " + if (existing.serviceStatus == "UNSERVED") "Unserved." else "Served.")
                    .setNegativeButton("Stop scanning") { _, _ -> batchAllotServer = "" }
                    .setPositiveButton("Continue scanning") { _, _ ->
                        if (continueBatch) continueAllotBatch(server)
                    }
                    .show()
                return@launch
            }

            val notice = if (existing == null) {
                NoticeEntity(
                    cnr = cnr,
                    processServer = server,
                    serviceStatus = "PENDING",
                    fetchedState = "QUEUED",
                    fetchPriority = FETCH_PRIORITY_NORMAL,
                    allottedAt = now,
                    scannedAt = now,
                    updatedAt = now
                )
            } else {
                existing.copy(
                    processServer = server,
                    serviceStatus = "PENDING",
                    fetchedState = if (existing.fetchedState == "READY") "READY" else "QUEUED",
                    fetchPriority = FETCH_PRIORITY_NORMAL,
                    allottedAt = now,
                    updatedAt = now
                )
            }

            if (existing == null) {
                withContext(Dispatchers.IO) { db.notices().insert(notice) }
            } else {
                withContext(Dispatchers.IO) { db.notices().update(notice) }
                ReminderWorker.reschedule(this@MainActivity, notice)
            }
            notifyUser("Queued and allotted to " + server)
            reloadAndRender()
            pumpQueue()
            if (continueBatch) continueAllotBatch(server)
        }
    }

    private fun continueAllotBatch(server: String) {
        if (!batchAllotServer.equals(server, ignoreCase = true) || isFinishing || isDestroyed) return
        appRoot.postDelayed({
            if (batchAllotServer.equals(server, ignoreCase = true) && !scannerInFlight) {
                launchScanner(SCAN_ALLOT, server)
            }
        }, 180L)
    }

'''
    s = s[:allot_start] + allot_block + s[receive_start:]

    launch_start = s.index('    private fun launchAllotScan(')
    format_start = s.index('    private fun formatStamp(', launch_start)
    if launch_start < 0 or format_start < 0:
        raise SystemExit('Missing scan launch functions')
    launch_block = '''    private fun launchAllotScan(server: String) {
        batchAllotServer = server
        launchScanner(SCAN_ALLOT, server)
    }

    private fun launchReceiveScan() {
        batchAllotServer = ""
        launchScanner(SCAN_RECEIVE, "")
    }

    private fun launchScanner(mode: Int, server: String) {
        if (scannerInFlight || isFinishing || isDestroyed) return
        scannerInFlight = true
        scanMode = mode
        scanServer = server
        runCatching {
            scanner.launch(android.content.Intent(this, ModernScannerActivity::class.java))
        }.onFailure {
            scannerInFlight = false
            if (mode == SCAN_ALLOT) batchAllotServer = ""
            scanMode = SCAN_NONE
            scanServer = ""
            notifyUser("Scanner could not open. Please try again.")
        }
    }

'''
    s = s[:launch_start] + launch_block + s[format_start:]

    pump_start = s.index('    private fun pumpQueue() {')
    show_notice_start = s.index('    private fun showNotice(', pump_start)
    if pump_start < 0 or show_notice_start < 0:
        raise SystemExit('Missing queue/showNotice boundary')
    queue_block = '''    private fun pumpQueue() {
        if (lookupNoticeId >= 0 || queuePumpBusy || isFinishing || isDestroyed) return
        queuePumpBusy = true
        lifecycleScope.launch {
            var selectedId = -1L
            try {
                if (lookupNoticeId >= 0) return@launch
                val next = withContext(Dispatchers.IO) {
                    db.notices().all()
                        .asSequence()
                        .filter { it.fetchedState == "QUEUED" }
                        .sortedWith(compareByDescending<NoticeEntity> { it.fetchPriority }.thenBy { it.scannedAt })
                        .firstOrNull()
                } ?: return@launch
                selectedId = next.id
                val fetching = next.copy(
                    fetchedState = "FETCHING",
                    fetchPriority = 0,
                    lastError = "",
                    updatedAt = System.currentTimeMillis()
                )
                withContext(Dispatchers.IO) { db.notices().update(fetching) }
                lookupNoticeId = next.id
                reloadAndRender()
                lookup.launch(ECourtWebViewActivity.createIntent(this@MainActivity, next.cnr))
            } catch (t: Throwable) {
                if (selectedId >= 0L) {
                    withContext(Dispatchers.IO) {
                        db.notices().byId(selectedId)?.let {
                            db.notices().update(
                                it.copy(
                                    fetchedState = "RETRY_REQUIRED",
                                    fetchPriority = 0,
                                    lastError = t.message ?: "Lookup could not start",
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
                lookupNoticeId = -1L
                notifyUser("Case lookup paused. Tap Refresh to retry.")
                reloadAndRender()
            } finally {
                queuePumpBusy = false
            }
        }
    }

    private fun retryNotice(n: NoticeEntity) {
        lifecycleScope.launch {
            if (n.id == lookupNoticeId) {
                restartAfterLookupId = n.id
                withContext(Dispatchers.IO) {
                    db.notices().update(
                        n.copy(
                            fetchedState = "RESTARTING",
                            fetchPriority = FETCH_PRIORITY_RETRY,
                            lastError = "",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                notifyUser("Restart requested. It will restart as soon as this attempt closes.")
                reloadAndRender()
            } else {
                withContext(Dispatchers.IO) {
                    db.notices().update(
                        n.copy(
                            fetchedState = "QUEUED",
                            fetchPriority = FETCH_PRIORITY_RETRY,
                            lastError = "",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
                notifyUser("Refresh prioritized")
                reloadAndRender()
                pumpQueue()
            }
        }
    }

    private fun compactCompletedIfReady(n: NoticeEntity): NoticeEntity {
        if (n.serviceStatus == "PENDING" || n.fetchedState != "READY") return n
        val archive = if (n.archiveText.isNotBlank()) n.archiveText else listOf(
            "# " + n.caseTitle.ifBlank { n.caseNumber.ifBlank { n.cnr } },
            "",
            "- CNR: " + n.cnr,
            "- Case number: " + n.caseNumber,
            "- Court: " + n.courtName,
            "- Judge: " + n.judge,
            "- Petitioner: " + n.petitioner,
            "- Respondent: " + n.respondent,
            "- Petitioner advocate: " + n.petitionerAdvocate,
            "- Respondent advocate: " + n.respondentAdvocate,
            "- Next hearing: " + n.nextHearing,
            "- Stage: " + n.caseStage,
            "- Process server: " + n.processServer,
            "- Status: " + n.serviceStatus,
            "- Allotted on: " + formatStamp(n.allottedAt),
            "- Received on: " + formatStamp(n.receivedAt)
        ).filterNot { it.endsWith(": ") }.joinToString("\\n")

        return n.copy(
            courtName = "",
            judge = "",
            petitioner = "",
            respondent = "",
            petitionerAdvocate = "",
            respondentAdvocate = "",
            nextHearing = "",
            caseStage = "",
            fetchedState = "ARCHIVED",
            lastError = "",
            archiveText = archive,
            fetchPriority = 0,
            updatedAt = System.currentTimeMillis()
        )
    }

'''
    s = s[:pump_start] + queue_block + s[show_notice_start:]

    # Show archived markdown details on completed notices.
    fields_anchor = '''        fields.forEach { pair ->
            if (pair.second.isNotBlank() && fieldEnabled(pair.first)) {
                box.addView(detailRow(pair.first, pair.second))
            }
        }
'''
    fields_new = fields_anchor + '''
        if (n.archiveText.isNotBlank()) {
            box.addView(sectionTitle("Archived details"), lp(top = UiTokens.Space.MD))
            box.addView(TextView(this).apply {
                text = n.archiveText
                applyType(TextRole.BODY)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextIsSelectable(true)
            }, lp(bottom = UiTokens.Space.SM))
        }
'''
    if fields_anchor not in s:
        raise SystemExit('Missing detail field loop')
    s = s.replace(fields_anchor, fields_new, 1)

    # Compact once a pending notice is marked complete; queued/fetching notices compact after fetch succeeds.
    mark_start = s.index('    private fun markServiceStatus(')
    save_start = s.index('    private fun saveNotice(', mark_start)
    if mark_start < 0 or save_start < 0:
        raise SystemExit('Missing mark/save boundary')
    mark_block = '''    private fun markServiceStatus(n: NoticeEntity, status: String) {
        val now = System.currentTimeMillis()
        var updated = n.copy(
            serviceStatus = status,
            receivedAt = if (status == "PENDING") 0L else if (n.receivedAt > 0L) n.receivedAt else now,
            updatedAt = now
        )
        if (status != "PENDING") updated = compactCompletedIfReady(updated)
        val message = when (status) {
            "SERVED" -> "Notice marked Served"
            "UNSERVED" -> "Notice marked Unserved"
            else -> "Notice moved back to Pending"
        }
        saveNotice(updated, cancelReminders = status != "PENDING", successMessage = message)
    }

'''
    s = s[:mark_start] + mark_block + s[save_start:]

    # Compact receive workflow if details are already ready.
    s = s.replace('''            val notice = if (existing == null) {
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
''','''            var notice = if (existing == null) {
                NoticeEntity(
                    cnr = cnr,
                    serviceStatus = status,
                    fetchedState = "QUEUED",
                    fetchPriority = FETCH_PRIORITY_NORMAL,
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
            notice = compactCompletedIfReady(notice)
''',1)

    # Merge successful detail fetches into archive when a notice has already been received.
    merge_return = '''        return old.copy(
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
'''
    merge_new = '''        return old.copy(
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
            fetchPriority = 0,
            lastError = "",
            updatedAt = System.currentTimeMillis()
        )
'''
    if merge_return not in s:
        raise SystemExit('Missing mergeCase return')
    s = s.replace(merge_return, merge_new, 1)

    # Status text/progress understands restart and archive states.
    s = s.replace('''                    "FETCHING" -> "Fetching case details…"
                    else -> "Queued…"
''','''                    "FETCHING" -> "Fetching case details…"
                    "RESTARTING" -> "Restarting case lookup…"
                    "ARCHIVED" -> "Archived"
                    else -> "Queued…"
''')
    s = s.replace('''                "FETCHING", "QUEUED" -> {''','''                "FETCHING", "QUEUED", "RESTARTING" -> {''')
    s = s.replace('''        "FETCHING" -> "Fetching"
        "QUEUED" -> "Queued"
        "RETRY_REQUIRED" -> "Refresh"
''','''        "FETCHING" -> "Fetching"
        "RESTARTING" -> "Restarting"
        "QUEUED" -> "Queued"
        "RETRY_REQUIRED" -> "Refresh"
        "ARCHIVED" -> "Archived"
''')

    # Queue priorities.
    s = s.replace('''        private const val SCAN_RECEIVE = 2
        private const val PREFS = "notice_tracker_settings"''','''        private const val SCAN_RECEIVE = 2
        private const val FETCH_PRIORITY_NORMAL = 0
        private const val FETCH_PRIORITY_RETRY = 100
        private const val PREFS = "notice_tracker_settings"''',1)

    MAIN.write_text(s)

# Always keep version/artifact aligned with this stability build.
g = GRADLE.read_text()
g = re.sub(r'versionCode\s+\d+', 'versionCode 11', g)
g = re.sub(r'versionName\s+"[^"]+"', 'versionName "0.8.1-debug"', g)
GRADLE.write_text(g)

w = WORKFLOW.read_text()
w = re.sub(r'name:\s*NoticeTracker-v[^\n]+', 'name: NoticeTracker-v0.8.1-batch-stability-debug', w)
WORKFLOW.write_text(w)
