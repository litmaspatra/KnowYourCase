package com.knowyourcase.app.ui.result

import android.content.Intent
import android.icu.text.Transliterator
import android.os.Build
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import com.google.gson.Gson
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.knowyourcase.app.ECourtWebViewActivity
import com.knowyourcase.app.databinding.FragmentResultBinding
import com.knowyourcase.app.data.api.CaseResponse
import com.knowyourcase.app.data.local.CaseCache
import java.util.concurrent.atomic.AtomicInteger

class ResultFragment : Fragment() {

    private var _binding: FragmentResultBinding? = null
    private val binding get() = _binding!!
    private val args: ResultFragmentArgs by navArgs()
    private val viewModel: ResultViewModel by viewModels()
    private var isExpanded = false
    private var isHindi = false
    private var backgroundRefreshInFlight = false
    private val originalTexts = LinkedHashMap<TextView, CharSequence>()
    private val hindiTranslatorDelegate = lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.HINDI)
                .build()
        )
    }
    private val hindiTranslator by hindiTranslatorDelegate
    private val devanagariTransliterator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { Transliterator.getInstance("Latin-Devanagari") }.getOrNull()
        } else null
    }
    // Person-name spellings seen in eCourts data (including common desk-entry variants).
    // These are deliberately separate from the court-terminology glossary below.
    private val hindiNameOverrides = mapOf(
        "RAEES" to "रईस",
        "LOKESH" to "लोकेश",
        "KUMAR" to "कुमार",
        "JAIN" to "जैन",
        "VIKRAM" to "विक्रम",
        "ANJUM" to "अंजुम",
        "BEGUM" to "बेगम",
        "ASLAM" to "असलम",
        "AHMAD" to "अहमद",
        "TASLIM" to "तस्लीम",
        "TASNIM" to "तस्नीम",
        "BANO" to "बानो",
        "NOSHIN" to "नौशीन",
        "SHAFIQ" to "शफ़ीक़",
        "TABASSUM" to "तबस्सुम",
        "VASIM" to "वसीम",
        "KHAN" to "ख़ान",
        "SHUMAELA" to "शुमाइला",
        "SHALIQ" to "शालिक़",
        "HUMERA" to "हुमैरा",
        "GORDHAN" to "गोरधन",
        "CHOUDHARY" to "चौधरी",
        "GUNSARIYA" to "गुनसरिया",
        "GUNSARIA" to "गुनसरिया",
        "KAILASH" to "कैलाश",
        "KELASH" to "कैलाश",
        "KAILAS" to "कैलाश",
        "SUB" to "उप",
        "REGISTRAR" to "रजिस्ट्रार",
        "TEHSILDAR" to "तहसीलदार",
        "AKA" to "उर्फ़",
        "AND" to "और",
        "OTHER" to "अन्य",
        "OTHERS" to "अन्य",
        "STATE" to "राज्य"
    )

    private val eCourtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(ECourtWebViewActivity.EXTRA_RESULT_JSON)
            val case = try {
                json?.let { Gson().fromJson(it, CaseResponse::class.java) }
            } catch (_: Exception) {
                null
            }
            if (case != null) {
                backgroundRefreshInFlight = false
                viewModel.accept(case, requireContext().applicationContext)
            } else {
                val wasBackgroundRefresh = backgroundRefreshInFlight
                backgroundRefreshInFlight = false
                if (!wasBackgroundRefresh) {
                    viewModel.fail("eCourts returned case data in an unexpected format.")
                }
            }
        } else {
            if (!backgroundRefreshInFlight) {
                viewModel.fail(
                    result.data?.getStringExtra(ECourtWebViewActivity.EXTRA_ERROR)
                        ?: "Failed to fetch case details from eCourts."
                )
            }
            backgroundRefreshInFlight = false
            _binding?.btnRefresh?.isEnabled = true
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, b: Bundle?): View {
        _binding = FragmentResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ResultState.Loading -> showLoading()
                is ResultState.Error   -> showError(state.message)
                is ResultState.Success -> showResult(state.data)
            }
        }

        binding.btnRetry.setOnClickListener {
            launchLookup()
        }
        binding.btnRefresh.setOnClickListener {
            launchLookup(background = true)
        }

        if (viewModel.state.value is ResultState.Loading) {
            val appContext = requireContext().applicationContext
            if (!viewModel.loadCached(args.cnr, appContext)) {
                launchLookup()
            } else if (CaseCache.isStale(appContext, args.cnr)) {
                launchLookup(background = true)
            }
        }
    }

    private fun launchLookup(background: Boolean = false) {
        backgroundRefreshInFlight = background
        if (!background) viewModel.startLoading()
        _binding?.btnRefresh?.isEnabled = false
        eCourtLauncher.launch(ECourtWebViewActivity.createIntent(requireContext(), args.cnr))
    }

    private fun showLoading() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.layoutError.visibility   = View.GONE
        binding.layoutResult.visibility  = View.GONE
        binding.fabTranslate.visibility  = View.GONE
    }

    private fun showError(message: String) {
        binding.layoutLoading.visibility  = View.GONE
        binding.layoutError.visibility    = View.VISIBLE
        binding.layoutResult.visibility   = View.GONE
        binding.fabTranslate.visibility   = View.GONE
        binding.tvErrorMessage.text       = message
    }

    private fun showResult(case: CaseResponse) {
        restoreEnglishText()
        originalTexts.clear()
        isExpanded = false
        isHindi = false
        binding.layoutLoading.visibility = View.GONE
        binding.layoutError.visibility   = View.GONE
        binding.layoutResult.visibility  = View.VISIBLE
        binding.fabTranslate.visibility  = View.VISIBLE
        binding.btnRefresh.isEnabled = true
        binding.fabTranslate.text = "हिंदी"

        // Header
        binding.tvCaseTitle.text = case.caseTitle ?: case.cnr
        binding.tvCnr.text       = case.cnr

        // Next hearing — most prominent field
        binding.tvNextHearing.text = case.nextHearingDate?.let { formatDate(it) }
            ?: "Not scheduled"

        // Details — only show rows with data
        setOrHide(binding.rowStatus, binding.tvStatus, case.status)
        setOrHide(binding.rowCourt, binding.tvCourt,
            case.courtName ?: case.judges.firstOrNull())
        setOrHide(binding.rowCaseNumber, binding.tvCaseNumber, case.caseNumber)
        setOrHide(binding.rowCaseType, binding.tvCaseType, case.caseType)
        setOrHide(binding.rowRegistrationNumber, binding.tvRegistrationNumber, case.registrationNumber)
        setOrHide(binding.rowRegistrationDate, binding.tvRegistrationDate,
            case.registrationDate?.let { formatDate(it) })
        setOrHide(binding.rowFilingDate, binding.tvFilingDate, case.filingDate?.let { formatDate(it) })
        setOrHide(binding.rowJudges, binding.tvJudges,
            case.judges.joinToString("\n").takeIf { it.isNotBlank() })
        setOrHide(binding.rowActsSections, binding.tvActsSections,
            case.actsAndSections.joinToString("\n\n") { item ->
                listOfNotNull(
                    item.act?.takeIf { it.isNotBlank() },
                    item.sections?.takeIf { it.isNotBlank() }?.let { "Sections: $it" }
                ).joinToString("\n")
            }.takeIf { it.isNotBlank() })

        // Parties
        binding.tvPetitioner.text = firstParty(case.petitioner)
        binding.tvPetitionerAll.text = numberParties(case.petitioner)
        binding.tvRespondent.text = firstParty(case.respondent)
        binding.tvRespondentAll.text = numberParties(case.respondent)
        binding.tvPetitionerAdvocate.text = case.petitionerAdvocate ?: "Not available"
        binding.tvRespondentAdvocate.text = case.respondentAdvocate ?: "Not available"
        binding.layoutExpandedDetails.visibility = View.GONE
        binding.tvPetitioner.visibility = View.VISIBLE
        binding.tvPetitionerAll.visibility = View.GONE
        binding.tvRespondent.visibility = View.VISIBLE
        binding.tvRespondentAll.visibility = View.GONE
        updateExpandButton()

        // Partial data notice
        binding.tvPartialNotice.visibility =
            if (case.dataCompleteness != "full") View.VISIBLE else View.GONE

        // Share
        binding.btnShare.setOnClickListener { shareCase(case) }
        binding.btnExpand.setOnClickListener { toggleExpanded() }
        binding.fabTranslate.setOnClickListener { toggleTranslation() }

        captureOriginalTexts(binding.layoutResult)
    }

    private fun numberParties(value: String?): String {
        val parties = value?.lineSequence()?.map { it.trim() }
            ?.filter { it.isNotBlank() }?.toList().orEmpty()
        return if (parties.isEmpty()) "Not available"
        else parties.mapIndexed { index, party -> "${index + 1}. $party" }.joinToString("\n")
    }

    private fun firstParty(value: String?): String =
        numberParties(value).lineSequence().first()

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        TransitionManager.beginDelayedTransition(
            binding.layoutResult,
            AutoTransition().setDuration(300)
        )
        binding.layoutExpandedDetails.visibility = if (isExpanded) View.VISIBLE else View.GONE
        binding.tvPetitioner.visibility = if (isExpanded) View.GONE else View.VISIBLE
        binding.tvPetitionerAll.visibility = if (isExpanded) View.VISIBLE else View.GONE
        binding.tvRespondent.visibility = if (isExpanded) View.GONE else View.VISIBLE
        binding.tvRespondentAll.visibility = if (isExpanded) View.VISIBLE else View.GONE
        updateExpandButton()
        binding.btnExpand.animate()
            .scaleX(0.96f).scaleY(0.96f).setDuration(100)
            .withEndAction {
                _binding?.btnExpand?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start()
            }.start()
    }

    private fun updateExpandButton() {
        binding.btnExpand.text = when {
            isHindi && isExpanded -> "कम विवरण दिखाएँ  ↑"
            isHindi -> "सभी विवरण दिखाएँ  ↓"
            isExpanded -> "Show fewer details  ↑"
            else -> "Show all details  ↓"
        }
    }

    private fun captureOriginalTexts(view: View) {
        if (view is TextView && view.id != binding.tvCnr.id && view.id != binding.btnExpand.id) {
            val value = view.text
            if (!value.isNullOrBlank() && value.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                originalTexts[view] = value
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) captureOriginalTexts(view.getChildAt(index))
        }
    }

    private fun restoreEnglishText() {
        originalTexts.forEach { (view, text) -> view.text = text }
    }

    private fun toggleTranslation() {
        binding.fabTranslate.animate().rotationYBy(360f).setDuration(450).start()
        if (isHindi) {
            restoreEnglishText()
            isHindi = false
            binding.fabTranslate.text = "हिंदी"
            updateExpandButton()
            binding.layoutResult.alpha = 0.65f
            binding.layoutResult.animate().alpha(1f).setDuration(280).start()
            return
        }

        binding.fabTranslate.isEnabled = false
        binding.fabTranslate.text = "अनुवाद…"
        binding.layoutResult.animate().alpha(0.72f).setDuration(180).start()

        val hindiModel = TranslateRemoteModel.Builder(TranslateLanguage.HINDI).build()
        RemoteModelManager.getInstance().isModelDownloaded(hindiModel)
            .addOnSuccessListener { isDownloaded ->
                downloadHindiModel(showDownloadNotice = !isDownloaded)
            }
            .addOnFailureListener { downloadHindiModel(showDownloadNotice = false) }
    }

    private fun downloadHindiModel(showDownloadNotice: Boolean) {
        if (showDownloadNotice) {
            Toast.makeText(
                requireContext(),
                "पहली बार लगभग 30 MB का हिंदी भाषा मॉडल डाउनलोड होगा।",
                Toast.LENGTH_LONG
            ).show()
        }

        hindiTranslator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { translateAllTexts() }
            .addOnFailureListener {
                _binding?.let { current ->
                    current.layoutResult.animate().alpha(1f).setDuration(180).start()
                    current.fabTranslate.isEnabled = true
                    current.fabTranslate.text = "हिंदी"
                    Toast.makeText(
                        requireContext(),
                        "हिंदी अनुवाद डाउनलोड नहीं हो सका। इंटरनेट जाँचकर फिर प्रयास करें।",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun translateAllTexts() {
        val entries = originalTexts.entries.toList()
        if (entries.isEmpty()) {
            finishHindiTranslation()
            return
        }
        val remaining = AtomicInteger(entries.size)
        entries.forEach { (view, english) ->
            hindiTranslator.translate(english.toString()).addOnCompleteListener { task ->
                if (_binding == null) return@addOnCompleteListener
                if (task.isSuccessful && !task.result.isNullOrBlank()) {
                    view.alpha = 0f
                    view.translationY = 8f
                    view.text = courtHindiOverride(english.toString()) ?: when {
                        isPartyText(view) -> transliteratePartyList(english.toString(), task.result)
                        isAdvocateText(view) -> transliterateIndianName(english.toString(), task.result)
                        view.id == binding.tvCaseTitle.id -> transliterateCaseTitle(
                            english.toString(), task.result
                        )
                        else -> task.result
                    }
                    view.animate().alpha(1f).translationY(0f).setDuration(240).start()
                }
                if (remaining.decrementAndGet() == 0) finishHindiTranslation()
            }
        }
    }

    private fun isPartyText(view: TextView): Boolean = view.id in setOf(
        binding.tvPetitioner.id,
        binding.tvPetitionerAll.id,
        binding.tvRespondent.id,
        binding.tvRespondentAll.id
    )

    private fun isAdvocateText(view: TextView): Boolean = view.id in setOf(
        binding.tvPetitionerAdvocate.id,
        binding.tvRespondentAdvocate.id
    )

    /** Court terminology is translated with fixed legal Hindi instead of generic ML output. */
    private fun courtHindiOverride(english: String): String? {
        val normalized = english.uppercase()
            .replace("&", " AND ")
            .replace(Regex("[.\\-/,()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            normalized.contains("ADDL") && normalized.contains("DIST") &&
                (normalized.contains("SESSION") || normalized.contains("SESS")) &&
                normalized.contains("JUDGE") ->
                "अतिरिक्त जिला एवं सत्र न्यायाधीश"
            normalized.contains("ADDITIONAL") && normalized.contains("DISTRICT") &&
                (normalized.contains("SESSION") || normalized.contains("SESS")) &&
                normalized.contains("JUDGE") ->
                "अतिरिक्त जिला एवं सत्र न्यायाधीश"
            normalized.contains("DISTRICT") &&
                (normalized.contains("SESSION") || normalized.contains("SESS")) &&
                normalized.contains("JUDGE") -> "जिला एवं सत्र न्यायाधीश"
            normalized.contains("CIVIL MISC") && normalized.contains("CONNECTED") ->
                "सिविल विविध कनेक्टेड"
            normalized == "CIVIL MISC" -> "सिविल विविध"
            normalized.contains("CRIMINAL MISC") && normalized.contains("CONNECTED") ->
                "आपराधिक विविध कनेक्टेड"
            normalized == "CRIMINAL MISC" -> "आपराधिक विविध"
            normalized.contains("MISC APPLICATION") -> "विविध आवेदन"
            normalized.contains("SPECIAL JUDGE") -> "विशेष न्यायाधीश"
            normalized.contains("CHIEF JUDICIAL MAGISTRATE") -> "मुख्य न्यायिक मजिस्ट्रेट"
            normalized.contains("JUDICIAL MAGISTRATE") -> "न्यायिक मजिस्ट्रेट"
            normalized.contains("SENIOR CIVIL JUDGE") -> "वरिष्ठ सिविल न्यायाधीश"
            normalized.contains("CIVIL JUDGE") -> "सिविल न्यायाधीश"
            normalized == "PETITIONER" -> "याचिकाकर्ता"
            normalized == "RESPONDENT" -> "प्रतिवादी"
            normalized == "CASE DETAILS" -> "मामले का विवरण"
            normalized == "CASE STAGE" -> "मामले की स्थिति"
            normalized == "NEXT HEARING" -> "अगली सुनवाई"
            normalized == "REGISTRATION NUMBER" -> "पंजीकरण संख्या"
            normalized == "REGISTRATION DATE" -> "पंजीकरण दिनांक"
            normalized == "FILING DATE" -> "दाखिल दिनांक"
            normalized == "ACTS AND SECTIONS" -> "अधिनियम एवं धाराएँ"
            normalized == "NOT AVAILABLE" -> "उपलब्ध नहीं है"
            normalized == "NOT SCHEDULED" -> "निर्धारित नहीं है"
            normalized == "UNKNOWN" -> "अज्ञात"
            normalized.contains("AWAITING SERVICE") && normalized.contains("NOTICE") ->
                "नोटिस की तामील की प्रतीक्षा में"
            normalized == "DISPOSED" -> "निस्तारित"
            normalized == "PENDING" -> "लंबित"
            normalized == "NOTICE ISSUED" -> "नोटिस जारी"
            normalized == "LISTED" -> "सूचीबद्ध"
            else -> null
        }
    }

    private fun transliteratePartyList(english: String, translatedFallback: String): String {
        val parties = english.lineSequence().map { line ->
            line.trim().replace(Regex("^\\d+[.)]\\s*"), "")
        }.filter { it.isNotBlank() }.toList()
        if (parties.isEmpty()) return translatedFallback

        val translatedParties = Regex(
            """(?:^|\s)\d+[.)]\s*(.*?)(?=\s+\d+[.)]\s*|$)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).findAll(translatedFallback.trim()).map { it.groupValues[1].trim() }.toList()

        val hindiParties = when {
            translatedParties.size == parties.size -> translatedParties.mapIndexed { index, translated ->
                transliterateIndianName(parties[index], translated)
            }
            parties.size == 1 && translatedFallback.isNotBlank() -> listOf(
                transliterateIndianName(
                    parties.first(),
                    translatedFallback.replace(Regex("^\\d+[.)]\\s*"), "")
                )
            )
            else -> parties.map { transliterateIndianName(it, it) }
        }
        return hindiParties.mapIndexed { index, party ->
            "${index + 1}. $party"
        }.joinToString("\n")
    }

    private fun transliterateCaseTitle(english: String, translatedFallback: String): String {
        val sides = english.split(Regex("\\s+v(?:s\\.?|ersus)\\s+", RegexOption.IGNORE_CASE), 2)
        return if (sides.size == 2) {
            "${transliterateIndianName(sides[0], "")} बनाम " +
                transliterateIndianName(sides[1], "")
        } else if (english.any { it.isLetter() }) {
            transliterateIndianName(english, "")
        } else correctHindiNames(translatedFallback)
    }

    private fun transliterateIndianName(english: String, translatedFallback: String): String {
        if (english.isBlank() || english.none { it.isLetter() }) {
            return correctHindiNames(translatedFallback)
        }
        val transliterator = devanagariTransliterator
            ?: return correctHindiNames(translatedFallback)
        // Names are transliterated from the recorded English spelling. Google
        // Translate is useful for labels, but it can semantically rewrite names.
        return Regex("[A-Za-z]+|[^A-Za-z]+").findAll(english).joinToString("") { match ->
            val token = match.value
            if (token.firstOrNull()?.isLetter() == true) {
                hindiNameOverrides[token.uppercase()]
                    ?: transliterator.transliterate(token.lowercase())
            } else token
        }.let(::correctHindiNames)
    }

    private fun correctHindiNames(value: String): String {
        var corrected = value
            .replace("रियास", "रईस")
            .replace("रियाज़", "रईस")
            .replace("शाफीक", "शफ़ीक़")
        devanagariTransliterator?.let { transliterator ->
            corrected = Regex("[A-Za-z]+").replace(corrected) { match ->
                hindiNameOverrides[match.value.uppercase()]
                    ?: transliterator.transliterate(match.value.lowercase())
            }
        }
        // Generic ICU transliteration uses Sanskrit-style word-final viramas.
        return corrected.replace(Regex("्(?=\\s|$|[.,()/])"), "")
    }

    private fun finishHindiTranslation() {
        _binding?.let { current ->
            isHindi = true
            current.layoutResult.animate().alpha(1f).setDuration(220).start()
            current.fabTranslate.isEnabled = true
            current.fabTranslate.text = "English"
            current.tvPartiesLabel.text = "दल"
            updateExpandButton()
        }
    }

    private fun setOrHide(row: View, textView: android.widget.TextView, value: String?) {
        if (value.isNullOrBlank()) {
            row.visibility = View.GONE
        } else {
            row.visibility = View.VISIBLE
            textView.text  = value
        }
    }

    private fun formatDate(iso: String): String {
        // Convert "2024-09-15" → "15 Sep 2024"
        return try {
            val parts = iso.split("-")
            val months = listOf("","Jan","Feb","Mar","Apr","May","Jun",
                                "Jul","Aug","Sep","Oct","Nov","Dec")
            "${parts[2]} ${months[parts[1].toInt()]} ${parts[0]}"
        } catch (e: Exception) { iso }
    }

    private fun shareCase(case: CaseResponse) {
        val sb = StringBuilder()
        sb.appendLine("KnowYourCase — Case Details")
        sb.appendLine("CNR: ${case.cnr}")
        case.caseTitle?.let       { sb.appendLine("Title: $it") }
        case.courtName?.let       { sb.appendLine("Court: $it") }
        case.caseNumber?.let      { sb.appendLine("Case No: $it") }
        case.status?.let          { sb.appendLine("Case Stage: $it") }
        case.registrationNumber?.let { sb.appendLine("Registration No: $it") }
        case.registrationDate?.let { sb.appendLine("Registration Date: ${formatDate(it)}") }
        case.nextHearingDate?.let { sb.appendLine("Next Hearing: ${formatDate(it)}") }
        case.petitioner?.let      { sb.appendLine("Petitioner: $it") }
        case.petitionerAdvocate?.let { sb.appendLine("Petitioner Advocate: $it") }
        case.respondent?.let      { sb.appendLine("Respondent: $it") }
        case.respondentAdvocate?.let { sb.appendLine("Respondent Advocate: $it") }
        case.actsAndSections.forEach { item ->
            item.act?.let { sb.append("Act: $it") }
            item.sections?.let { sb.append(" | Sections: $it") }
            sb.appendLine()
        }

        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sb.toString())
            }, "Share case details"
        ))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        if (hindiTranslatorDelegate.isInitialized()) hindiTranslator.close()
        super.onDestroy()
    }
}
