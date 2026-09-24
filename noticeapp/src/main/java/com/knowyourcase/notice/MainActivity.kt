package com.knowyourcase.notice

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.JsonParser
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class MainActivity : AppCompatActivity() {
    private val db by lazy { NoticeDatabase.get(this) }
    private lateinit var summary: TextView
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private var notices: List<NoticeEntity> = emptyList()
    private var lookupNoticeId: Long = -1

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::handleScan)
    }

    private val lookup = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = lookupNoticeId
        lookupNoticeId = -1
        if (id < 0) return@registerForActivityResult
        val json = result.data?.getStringExtra(ECourtWebViewActivity.EXTRA_RESULT_JSON)
        val error = result.data?.getStringExtra(ECourtWebViewActivity.EXTRA_ERROR)
        lifecycleScope.launch {
            val old = withContext(Dispatchers.IO) { db.notices().byId(id) } ?: return@launch
            val updated = if (!json.isNullOrBlank()) mergeCase(old, json)
            else old.copy(
                fetchedState = "RETRY_REQUIRED",
                lastError = error ?: "Case details could not be fetched",
                updatedAt = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) { db.notices().update(updated) }
            ReminderWorker.reschedule(this@MainActivity, updated)
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
        refresh()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
        }

        root.addView(TextView(this).apply {
            text = "NOTICE TRACKER"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Scan → fetch → assign → serve"
            textSize = 13f
            alpha = .7f
            setPadding(0, 2, 0, 18)
        })

        summary = TextView(this).apply {
            textSize = 16f
            setPadding(18, 18, 18, 18)
        }
        root.addView(summary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        list = ListView(this).apply {
            dividerHeight = 1
            setOnItemClickListener { _, _, position, _ ->
                notices.getOrNull(position)?.let(::showNotice)
            }
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val scan = Button(this).apply {
            text = "SCAN NOTICE"
            textSize = 17f
            setOnClickListener {
                scanner.launch(ScanOptions().apply {
                    setPrompt("Scan the eCourts QR code")
                    setBeepEnabled(false)
                    setOrientationLocked(false)
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                })
            }
        }
        root.addView(scan, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
    }

    private fun handleScan(raw: String) {
        val cleaned = raw.uppercase().replace(" ", "").replace("-", "")
        val cnr = Regex("[A-Z]{4}[0-9]{12}").find(cleaned)?.value
        if (cnr == null) {
            toast("No valid 16-character CNR found in this QR code.")
            return
        }
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) { db.notices().byCnr(cnr) }
            if (existing.isNotEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Case already scanned")
                    .setMessage("A notice for " + cnr + " already exists. Add another notice for the same case?")
                    .setNegativeButton("Open existing") { _, _ -> showNotice(existing.first()) }
                    .setPositiveButton("Add another") { _, _ -> createNotice(cnr) }
                    .show()
            } else createNotice(cnr)
        }
    }

    private fun createNotice(cnr: String) {
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                db.notices().insert(NoticeEntity(cnr = cnr))
            }
            refresh()
            launchLookup(id, cnr)
        }
    }

    private fun launchLookup(id: Long, cnr: String) {
        lookupNoticeId = id
        lookup.launch(ECourtWebViewActivity.createIntent(this, cnr))
    }

    private fun refresh() {
        lifecycleScope.launch {
            notices = withContext(Dispatchers.IO) { db.notices().all() }
            val notServed = notices.count { it.serviceStatus == "NOT_SERVED" }
            val unassigned = notices.count {
                it.serviceStatus == "NOT_SERVED" && it.processServer.isBlank()
            }
            val due7 = notices.count {
                it.serviceStatus == "NOT_SERVED" && daysUntil(it.nextHearing)?.let { d -> d in 0..7 } == true
            }
            summary.text = notServed.toString() + " not served   •   " +
                unassigned + " unassigned   •   " + due7 + " due ≤ 7 days"

            adapter.clear()
            adapter.addAll(notices.map { n ->
                val due = daysUntil(n.nextHearing)
                val urgency = when {
                    n.serviceStatus == "SERVED" -> "✓ SERVED"
                    due != null && due <= 2 -> "!! NOT SERVED"
                    due != null && due <= 7 -> "! NOT SERVED"
                    else -> "NOT SERVED"
                }
                val assignment = if (n.processServer.isBlank()) "Process server: unassigned"
                    else "Process server: " + n.processServer
                val date = n.nextHearing.ifBlank {
                    if (n.fetchedState == "FETCHING") "Fetching case details…" else "Next date unavailable"
                }
                n.caseNumber.ifBlank { n.cnr } + "\n" +
                    n.caseTitle.ifBlank { "Case details pending" } + "\n" +
                    date + "  •  " + urgency + "\n" + assignment
            })
            adapter.notifyDataSetChanged()
        }
    }

    private fun showNotice(notice: NoticeEntity) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 18, 42, 12)
        }

        fun addLine(value: String, bold: Boolean = false) {
            box.addView(TextView(this).apply {
                text = value
                textSize = if (bold) 18f else 15f
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 8, 0, 8)
            })
        }

        addLine(notice.caseTitle.ifBlank { notice.caseNumber.ifBlank { notice.cnr } }, true)
        addLine("CNR: " + notice.cnr)
        if (notice.caseNumber.isNotBlank()) addLine("Case: " + notice.caseNumber)
        if (notice.courtName.isNotBlank()) addLine("Court: " + notice.courtName)
        if (notice.petitioner.isNotBlank()) addLine("Petitioner: " + notice.petitioner)
        if (notice.respondent.isNotBlank()) addLine("Respondent: " + notice.respondent)
        if (notice.petitionerAdvocate.isNotBlank()) addLine("Petitioner advocate: " + notice.petitionerAdvocate)
        if (notice.respondentAdvocate.isNotBlank()) addLine("Respondent advocate: " + notice.respondentAdvocate)
        addLine("Next hearing: " + notice.nextHearing.ifBlank { "Not available" })
        addLine("Process server: " + notice.processServer.ifBlank { "Not assigned" })
        addLine("Service: " + if (notice.serviceStatus == "SERVED") "Served" else "Not Served")
        if (notice.lastError.isNotBlank()) addLine("Fetch: " + notice.lastError)

        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Close", null)
            .create()

        fun action(label: String, run: () -> Unit) {
            box.addView(Button(this).apply {
                text = label
                setOnClickListener { run() }
            })
        }

        action("ASSIGN PROCESS SERVER") {
            dialog.dismiss()
            assignProcessServer(notice)
        }
        action("MARK SERVED") {
            dialog.dismiss()
            saveNotice(notice.copy(
                serviceStatus = "SERVED",
                updatedAt = System.currentTimeMillis()
            ), cancelReminders = true)
        }
        action("MARK NOT SERVED") {
            dialog.dismiss()
            saveNotice(notice.copy(
                serviceStatus = "NOT_SERVED",
                updatedAt = System.currentTimeMillis()
            ))
        }
        action("REFRESH CASE DETAILS") {
            dialog.dismiss()
            launchLookup(notice.id, notice.cnr)
        }
        dialog.show()
    }

    private fun assignProcessServer(notice: NoticeEntity) {
        val input = EditText(this).apply {
            hint = "Process server name"
            setText(notice.processServer)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Assign process server")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Assign") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) saveNotice(notice.copy(
                    processServer = name,
                    updatedAt = System.currentTimeMillis()
                ))
            }.show()
    }

    private fun saveNotice(notice: NoticeEntity, cancelReminders: Boolean = false) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.notices().update(notice) }
            if (cancelReminders) ReminderWorker.cancel(this@MainActivity, notice.id)
            else ReminderWorker.reschedule(this@MainActivity, notice)
            refresh()
        }
    }

    private fun mergeCase(old: NoticeEntity, rawJson: String): NoticeEntity {
        val j = runCatching { JsonParser.parseString(rawJson).asJsonObject }.getOrNull()
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

    private fun daysUntil(date: String): Long? {
        if (date.isBlank()) return null
        return runCatching {
            ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(date))
        }.getOrNull()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
