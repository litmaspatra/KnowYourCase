from pathlib import Path

p = Path('noticeapp/src/main/java/com/knowyourcase/notice/MainActivity.kt')
s = p.read_text()

# Empty-state scan buttons should lead into the server-first allotment workflow.
s = s.replace('''                    "Scan a notice"
                ) { scanner.launch(android.content.Intent(this@MainActivity, ModernScannerActivity::class.java)) },''','''                    "Open Allot"
                ) {
                    activeTab = TAB_SERVERS
                    bottomNav.selectedItemId = TAB_SERVERS
                },''')
s = s.replace('''                    "Scan a notice"
                ) { scanner.launch(android.content.Intent(this@MainActivity, ModernScannerActivity::class.java)) }
            )''','''                    "Open Allot"
                ) {
                    activeTab = TAB_SERVERS
                    bottomNav.selectedItemId = TAB_SERVERS
                }
            )''')
s = s.replace('''                    if (trackerFilter == "COMPLETED") null else "Scan a notice"
                ) {
                    scanner.launch(android.content.Intent(this@MainActivity, ModernScannerActivity::class.java))
                }''','''                    if (trackerFilter == "COMPLETED") null else "Open Allot"
                ) {
                    activeTab = TAB_SERVERS
                    bottomNav.selectedItemId = TAB_SERVERS
                }''')

# First-run CTA should set up the roster rather than bypassing it with a generic scan.
s = s.replace('''        body.addView(primaryButton("Scan first notice", R.drawable.ic_nt_scan) {
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
            sheet.dismiss()
            requestNotificationPermission()
            scanner.launch(android.content.Intent(this, ModernScannerActivity::class.java))
        }, lp(top = UiTokens.Space.LG))''','''        body.addView(primaryButton("Set up process servers", R.drawable.ic_nt_people) {
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
            sheet.dismiss()
            requestNotificationPermission()
            activeTab = TAB_SERVERS
            bottomNav.selectedItemId = TAB_SERVERS
        }, lp(top = UiTokens.Space.LG))''')

# Manual assignments should also get an allotment timestamp for reports.
s = s.replace('''                    n.copy(
                        processServer = servers[which],
                        updatedAt = System.currentTimeMillis()
                    ),''','''                    n.copy(
                        processServer = servers[which],
                        allottedAt = if (n.allottedAt > 0L) n.allottedAt else System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    ),''')

# Keep historical process-server names visible in Receive reports even after roster removal.
s = s.replace('''        val servers = processServers()
        if (servers.isEmpty()) {
            root.addView(statePanel(StateKind.EMPTY, "No process servers", "Add process servers from the Allot tab first.", R.drawable.ic_nt_people))
        } else {
            servers.forEach { server ->''','''        val servers = (processServers() + notices.map { it.processServer }.filter { it.isNotBlank() }).distinct()
        if (servers.isEmpty()) {
            root.addView(statePanel(StateKind.EMPTY, "No process servers", "Add process servers from the Allot tab first.", R.drawable.ic_nt_people))
        } else {
            servers.forEach { server ->''')

p.write_text(s)
print('Final process workflow hardening complete')
