package com.knowyourcase.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.knowyourcase.app.data.api.ParseRequest
import com.knowyourcase.app.data.api.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Invisible WebView activity that:
 *   1. Loads eCourts CNR search page
 *   2. Detects CAPTCHA
 *   3. Extracts CAPTCHA image → sends to CaptchaSolver (ML Kit OCR)
 *   4. Auto-fills CAPTCHA + CNR → submits
 *   5. Retries up to MAX_CAPTCHA_ATTEMPTS times on wrong CAPTCHA
 *   6. Records every attempt outcome for Phase 2 training dataset
 *   7. Returns parsed case data to caller
 */
class ECourtWebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ECourtWebView"
        private const val MAX_CAPTCHA_ATTEMPTS = 3
        private const val ECOURTS_CNR_URL =
            "https://services.ecourts.gov.in/ecourtindia_v6/"

        const val EXTRA_CNR = "extra_cnr"
        const val EXTRA_RESULT_JSON = "extra_result_json"
        const val EXTRA_ERROR = "extra_error"
        const val REQUEST_CODE = 1001

        fun createIntent(context: Context, cnr: String): Intent =
            Intent(context, ECourtWebViewActivity::class.java).apply {
                putExtra(EXTRA_CNR, cnr)
            }
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: View
    private lateinit var captchaSolver: CaptchaSolver

    private var cnrNumber = ""
    private var resultExtracted = false
    private var captchaAttempt = 0          // current attempt count (1-based)
    private var lastCaptchaBase64 = ""
    private var captchaProcessing = false
    private var captchaDownloadInFlight = false
    private var submissionInFlight = false
    private var manualCaptchaMode = false

    private val scope = CoroutineScope(Dispatchers.Main)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cnrNumber = intent.getStringExtra(EXTRA_CNR) ?: run {
            finishWithError("No CNR provided"); return
        }

        captchaSolver = CaptchaSolver()
        createBackgroundLookupView()

        setupWebView()
        webView.loadUrl(ECOURTS_CNR_URL)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        if (::captchaSolver.isInitialized) captchaSolver.release()
        super.onDestroy()
    }

    // ── WebView setup ──────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(JsBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (resultExtracted) return
                // eCourts initializes a CAPTCHA session after the image appears;
                // submitting earlier makes a correct answer look invalid.
                Handler(Looper.getMainLooper()).postDelayed({ inspectPage() }, 1000)
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true)
                    finishWithError("Network error: ${error?.description}")
            }
        }
    }

    // ── Page inspection ────────────────────────────────────────────────────────

    /**
     * Injected after every page load. Detects what state we're in and reports back
     * via JsBridge. Possible states:
     *   - has_captcha   → CAPTCHA present, sends image as base64
     *   - has_result    → case details table visible, sends HTML
     *   - has_cnr_form  → plain CNR form with no CAPTCHA (rare)
     *   - captcha_wrong → server said CAPTCHA was wrong
     *   - error:<msg>   → server-side error
     *   - unknown       → retry
     */
    private fun inspectPage() {
        if (resultExtracted) return

        val js = """
        (function() {
            var body = document.body;
            if (!body) { AndroidBridge.onPageState('unknown'); return; }

            // ── Check for result table ─────────────────────────────────────
            var resultTable = document.querySelector('.case_details_table')
                || document.querySelector('#caseDetails')
                || document.querySelector('.history_table')
                || (function(){
                    var result = document.querySelector('#history_cnr');
                    return result && result.innerText.trim().length > 20 ? result : null;
                })()
                || (function(){
                    var result = document.querySelector('#caseBusinessDiv_cnr');
                    return result && result.innerText.trim().length > 20 ? result : null;
                })()
                || (function(){
                    var tables = document.querySelectorAll('table');
                    for (var t of tables) {
                        if (t.innerText.indexOf('CNR') > -1 || t.innerText.indexOf('Case No') > -1)
                            return t;
                    }
                    return null;
                })();

            if (resultTable) {
                AndroidBridge.onPageState('has_result:' + document.documentElement.innerHTML);
                return;
            }

            // Check errors before CAPTCHA: failed AJAX submissions usually keep
            // a refreshed CAPTCHA image on the page.
            var pageText = body.innerText.toLowerCase();
            var errorText = '';
            var errorCandidates = document.querySelectorAll(
                '.alert-danger, .error-msg, #validateError .modal-body'
            );
            for (var errorCandidate of errorCandidates) {
                var style = window.getComputedStyle(errorCandidate);
                var visible = style.display !== 'none'
                    && style.visibility !== 'hidden'
                    && errorCandidate.getClientRects().length > 0;
                var candidateText = errorCandidate.innerText.trim();
                if (visible && candidateText && candidateText !== '×') {
                    errorText = candidateText;
                    break;
                }
            }
            var combinedError = (pageText + ' ' + errorText.toLowerCase());
            if (combinedError.indexOf('invalid captcha') > -1
                || combinedError.indexOf('wrong captcha') > -1
                || combinedError.indexOf('captcha mismatch') > -1
                || combinedError.indexOf('captcha code does not match') > -1) {
                AndroidBridge.onPageState('captcha_wrong');
                return;
            }

            if (errorText.length > 0) {
                AndroidBridge.onPageState('error:' + errorText);
                return;
            }

            // ── Check for CAPTCHA ──────────────────────────────────────────
            var captchaImg = document.querySelector('#captcha_image')
                || document.querySelector('img[src*="captcha"]')
                || document.querySelector('img[id*="captcha"]')
                || document.querySelector('img[class*="captcha"]')
                || document.querySelector('.captcha img')
                || document.querySelector('#captcha img');

            if (captchaImg) {
                // Draw image onto canvas to extract pixel data as base64
                try {
                    if (!captchaImg.complete || !captchaImg.naturalWidth || !captchaImg.naturalHeight) {
                        AndroidBridge.onPageState('unknown');
                        return;
                    }
                    var canvas = document.createElement('canvas');
                    canvas.width = captchaImg.naturalWidth || captchaImg.width || 200;
                    canvas.height = captchaImg.naturalHeight || captchaImg.height || 60;
                    var ctx = canvas.getContext('2d');
                    ctx.drawImage(captchaImg, 0, 0, canvas.width, canvas.height);
                    var b64 = canvas.toDataURL('image/png').split(',')[1];
                    AndroidBridge.onPageState('has_captcha:' + b64);
                } catch(e) {
                    // Canvas tainted (cross-origin image) — fall back to src URL
                    AndroidBridge.onPageState('captcha_url:' + captchaImg.src);
                }
                return;
            }

            // ── Plain CNR form (no CAPTCHA) ────────────────────────────────
            var cnrInput = document.getElementById('cino')
                || document.querySelector('input[name="cino"]')
                || document.querySelector('input[placeholder*="CNR"]');
            if (cnrInput && !captchaImg) {
                AndroidBridge.onPageState('has_cnr_form');
                return;
            }

            AndroidBridge.onPageState('unknown');
        })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    // ── JS → Kotlin bridge ─────────────────────────────────────────────────────

    inner class JsBridge {

        @JavascriptInterface
        fun onPageState(state: String) {
            Log.d(TAG, "Page state: ${state.take(80)}")

            when {
                // ── Case result received ───────────────────────────────────
                state.startsWith("has_result:") -> {
                    if (resultExtracted) return
                    resultExtracted = true
                    submissionInFlight = false

                    val html = state.removePrefix("has_result:")
                    scope.launch {
                        val caseDataJson = parseCaseData(html)
                        setResult(RESULT_OK, Intent().apply {
                            putExtra(EXTRA_RESULT_JSON, caseDataJson)
                        })
                        finish()
                    }
                }

                // ── CAPTCHA image extracted as base64 ──────────────────────
                state.startsWith("has_captcha:") -> {
                    val b64 = state.removePrefix("has_captcha:")
                    handleCaptchaBase64(b64)
                }

                // ── CAPTCHA image is cross-origin, got URL instead ─────────
                state.startsWith("captcha_url:") -> {
                    val url = state.removePrefix("captcha_url:")
                    downloadCaptchaWithSession(url)
                }

                // ── Server confirmed CAPTCHA was wrong ─────────────────────
                state == "captcha_wrong" -> {
                    if (!submissionInFlight) return
                    submissionInFlight = false
                    runOnUiThread { retryCaptcha() }
                }

                // ── Plain form, no CAPTCHA — fill and submit directly ──────
                state == "has_cnr_form" -> {
                    runOnUiThread { submitCnrForm(captchaText = null) }
                }

                // ── Server error ───────────────────────────────────────────
                state.startsWith("error:") -> {
                    finishWithError(state.removePrefix("error:"))
                }

                // ── Unknown state — retry inspect after delay ──────────────
                else -> {
                    Handler(Looper.getMainLooper()).postDelayed({ inspectPage() }, 350)
                }
            }
        }
    }

    // ── CAPTCHA handling ───────────────────────────────────────────────────────

    private fun handleCaptchaBase64(b64: String) {
        if (manualCaptchaMode) {
            Handler(Looper.getMainLooper()).postDelayed({ inspectPage() }, 350)
            return
        }
        if (captchaProcessing) return
        if (submissionInFlight) {
            // The eCourts search runs through JavaScript/AJAX. Wait for either
            // the result or validation message instead of solving the same image twice.
            Handler(Looper.getMainLooper()).postDelayed({ inspectPage() }, 350)
            return
        }

        if (b64 == lastCaptchaBase64) return
        lastCaptchaBase64 = b64
        captchaProcessing = true

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { base64ToBitmap(b64) }
                ?: run {
                    captchaProcessing = false
                    finishWithError("Could not decode CAPTCHA image")
                    return@launch
                }

            if (captchaAttempt >= MAX_CAPTCHA_ATTEMPTS) {
                captchaProcessing = false
                enterManualCaptchaMode()
                return@launch
            }

            captchaAttempt++
            val solved = captchaSolver.solve(bitmap)

            if (solved.isNullOrEmpty()) {
                Log.w(TAG, "OCR returned empty — retrying")
                captchaProcessing = false
                retryCaptcha()
                return@launch
            }

            Log.d(TAG, "CAPTCHA attempt $captchaAttempt solved: $solved")
            captchaProcessing = false
            submissionInFlight = true
            submitCnrForm(captchaText = solved)
        }
    }

    /**
     * Fallback when CAPTCHA image is cross-origin (canvas tainted).
     * Takes a screenshot of the WebView, crops the CAPTCHA region, and runs OCR.
     */
    private fun downloadCaptchaWithSession(captchaUrl: String) {
        if (manualCaptchaMode || captchaDownloadInFlight || captchaProcessing || submissionInFlight) return
        captchaDownloadInFlight = true
        val cookie = CookieManager.getInstance().getCookie(captchaUrl)
        val userAgent = webView.settings.userAgentString

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(captchaUrl)
                        .header("User-Agent", userAgent)
                        .header("Referer", ECOURTS_CNR_URL)
                        .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                        .build()
                    OkHttpClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) null
                        else response.body?.bytes()?.let { bytes ->
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "CAPTCHA download failed", e)
                    null
                }
            }
            captchaDownloadInFlight = false
            if (bitmap == null) {
                Log.w(TAG, "Could not download CAPTCHA; refreshing it")
                retryCaptcha()
            } else {
                handleCaptchaBase64(bitmapToBase64(bitmap))
            }
        }
    }

    private fun retryCaptcha() {
        if (captchaAttempt >= MAX_CAPTCHA_ATTEMPTS) {
            enterManualCaptchaMode()
            return
        }
        // Reload page to get a fresh CAPTCHA image
        lastCaptchaBase64 = ""
        webView.loadUrl(ECOURTS_CNR_URL)
    }

    private fun createBackgroundLookupView() {
        val root = FrameLayout(this)
        loadingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(ProgressBar(this@ECourtWebViewActivity))
            addView(TextView(this@ECourtWebViewActivity).apply {
                text = "Fetching case details…"
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(0, 28, 0, 0)
            })
        }
        root.addView(
            loadingView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // The WebView must stay attached for eCourts JavaScript and CAPTCHA
        // rendering, but it is kept off-screen during automatic lookup.
        webView = WebView(this).apply { alpha = 0f }
        root.addView(webView, FrameLayout.LayoutParams(1, 1))
        setContentView(root)
    }

    private fun enterManualCaptchaMode() {
        if (manualCaptchaMode) return
        manualCaptchaMode = true
        captchaProcessing = false
        submissionInFlight = false
        loadingView.visibility = View.GONE
        webView.alpha = 1f
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        android.widget.Toast.makeText(
            this,
            "Automatic CAPTCHA attempts failed. Please enter the CAPTCHA and tap Search.",
            android.widget.Toast.LENGTH_LONG
        ).show()

        // Keep observing so a successful manual submission still returns data.
        Handler(Looper.getMainLooper()).postDelayed({ inspectPage() }, 1500)
    }

    // ── Form submission ────────────────────────────────────────────────────────

    /**
     * Fills the CNR field, optionally fills CAPTCHA, then submits.
     */
    private fun submitCnrForm(captchaText: String?) {
        val captchaJs = if (captchaText != null) """
            // Fill CAPTCHA field
            var captchaInput = document.getElementById('fcaptcha_code')
                || document.getElementById('captcha')
                || document.querySelector('input[name="captcha"]')
                || document.querySelector('input[id*="captcha"]')
                || document.querySelector('input[placeholder*="captcha"]')
                || document.querySelector('input[placeholder*="CAPTCHA"]');
            if (captchaInput) {
                captchaInput.value = '$captchaText';
                captchaInput.dispatchEvent(new Event('input', { bubbles: true }));
                captchaInput.dispatchEvent(new Event('change', { bubbles: true }));
            }
        """ else ""

        val js = """
        (function() {
            // Fill CNR
            var cnrInput = document.getElementById('cino')
                || document.querySelector('input[name="cino"]')
                || document.querySelector('input[placeholder*="CNR"]')
                || document.querySelector('input[placeholder*="cnr"]');
            if (cnrInput) {
                cnrInput.value = '$cnrNumber';
                cnrInput.dispatchEvent(new Event('input', { bubbles: true }));
                cnrInput.dispatchEvent(new Event('change', { bubbles: true }));
            }

            $captchaJs

            // Submit
            var btn = document.getElementById('searchbtn')
                || document.querySelector('button[type="submit"]')
                || document.querySelector('input[type="submit"]')
                || document.querySelector('.btn-primary')
                || document.querySelector('button');
            if (btn) {
                btn.click();
            } else {
                var form = document.querySelector('form');
                if (form) form.submit();
            }
        })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
        Handler(Looper.getMainLooper()).postDelayed({ inspectPage() }, 350)
        Handler(Looper.getMainLooper()).postDelayed({ inspectPage() }, 900)
        Handler(Looper.getMainLooper()).postDelayed({ inspectPage() }, 1600)
    }

    // ── HTML parsing (same as before) ─────────────────────────────────────────

    private suspend fun parseCaseData(html: String): String {
        val local = parseHtmlToCaseData(html)
        if (local.optString("status").isNotBlank()
            && local.optString("petitioner").isNotBlank()
            && local.optString("respondent").isNotBlank()
        ) return local.toString()

        return try {
            val response = withContext(Dispatchers.IO) {
                RetrofitClient.service.parseCase(ParseRequest(cnrNumber, html))
            }
            val body = response.body()
            if (response.isSuccessful && body != null) Gson().toJson(body)
            else local.toString()
        } catch (_: Exception) {
            local.toString()
        }
    }

    private fun parseHtmlToCaseData(html: String): JSONObject {
        val result = JSONObject()
        result.put("cnr", cnrNumber)

        val fields = mapOf(
            "case_number"         to listOf(
                """Case No\.\s*[:：]?\s*<[^>]*>([^<]+)<""",
                """<td[^>]*>\s*Case No\.?\s*</td>\s*<td[^>]*>\s*([^<\s][^<]*?)\s*</td>"""
            ),
            "case_type"           to listOf(
                """<th[^>]*>\s*Case Type\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
                """Case Type\s*[:：]?\s*<[^>]*>([^<]+)<""",
                """<td[^>]*>\s*Case Type\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>"""
            ),
            "status"              to listOf(
                """Case Status\s*[:：]?\s*<[^>]*>([^<]+)<""",
                """<td[^>]*>\s*Case Status\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
                """<th[^>]*>\s*Case Stage\s*</th>\s*<td[^>]*>\s*(?:<strong>)?([^<]+)""",
                """stage_of_case['"]\s*>\s*([^<]+)<"""
            ),
            "filing_date"         to listOf(
                """<th[^>]*>\s*Filing Date\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
                """Filing Date\s*[:：]?\s*<[^>]*>([^<]+)<""",
                """<td[^>]*>\s*Filing Date\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>"""
            ),
            "registration_date"   to listOf(
                """<th[^>]*>\s*Registration Date\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
                """Registration Date\s*[:：]?\s*<[^>]*>([^<]+)<""",
                """<td[^>]*>\s*Registration Date\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>"""
            ),
            "registration_number" to listOf(
                """<th[^>]*>\s*Registration Number\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
                """<td[^>]*>\s*Registration Number\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>"""
            ),
            "next_hearing_date"   to listOf(
                """<th[^>]*>\s*Next Hearing Date\s*</th>\s*<td[^>]*>\s*(?:<strong>)?([^<]+)""",
                """Next Hearing Date\s*[:：]?\s*<[^>]*>([^<]+)<""",
                """<td[^>]*>\s*Next Hearing\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
                """next_date['"]\s*>\s*([^<]+)<"""
            ),
            "court_name"          to listOf(
                """Court Name\s*[:：]?\s*<[^>]*>([^<]+)<""",
                """<td[^>]*>\s*Court Name\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>"""
            ),
            "judges"              to listOf(
                """<th[^>]*>\s*Court Number and Judge\s*</th>\s*<td[^>]*>\s*(?:<strong>)?([^<]+)""",
                """Judge\s*[:：]?\s*<[^>]*>([^<]+)<""",
                """<td[^>]*>\s*Judge\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>"""
            ),
        )

        var filled = 0
        fields.forEach { (key, patterns) ->
            extractPattern(html, patterns)?.let { value ->
                val normalized = if (key.endsWith("_date")) normalizeCourtDate(value) else value
                if (key == "judges") result.put(key, JSONArray().put(normalized))
                else result.put(key, normalized)
                filled++
            }
        }

        val petitioner = extractPartyBlock(html, "Petitioner_Advocate_table")
        val respondent = extractPartyBlock(html, "Respondent_Advocate_table")
        petitioner.first?.let { result.put("petitioner", it); filled++ }
        petitioner.second?.let { result.put("petitioner_advocate", it); filled++ }
        respondent.first?.let { result.put("respondent", it); filled++ }
        respondent.second?.let { result.put("respondent_advocate", it); filled++ }

        if (!result.has("petitioner")) {
            extractPattern(html, listOf(
                """<td[^>]*>\s*Petitioner\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
                """pet_name['"]\s*>\s*([^<]+)<"""
            ))?.let { result.put("petitioner", it); filled++ }
        }
        if (!result.has("respondent")) {
            extractPattern(html, listOf(
                """<td[^>]*>\s*Respondent\s*</td>\s*<td[^>]*>\s*([^<]+?)\s*</td>""",
                """res_name['"]\s*>\s*([^<]+)<"""
            ))?.let { result.put("respondent", it); filled++ }
        }

        val acts = extractActsAndSections(html)
        result.put("acts_and_sections", acts)
        if (acts.length() > 0) filled++

        val petitionerName = result.optString("petitioner").lineSequence().firstOrNull()
        val respondentName = result.optString("respondent").lineSequence().firstOrNull()
        if (!petitionerName.isNullOrBlank() && !respondentName.isNullOrBlank()) {
            result.put("case_title", "$petitionerName vs $respondentName")
        }

        if (!result.has("case_number")) {
            val type = result.optString("case_type")
            val registration = result.optString("registration_number")
            if (type.isNotBlank() && registration.isNotBlank()) {
                result.put("case_number", "$type/$registration")
            }
        }

        result.put("previous_hearings", JSONArray())
        val available = listOf(
            "case_title", "case_number", "case_type", "court_name",
            "registration_number", "registration_date", "filing_date",
            "petitioner", "respondent", "petitioner_advocate", "respondent_advocate",
            "acts_and_sections", "next_hearing_date", "status", "judges"
        ).filter { result.has(it) }
        result.put("available_fields", JSONArray(available))
        result.put(
            "data_completeness",
            when {
                filled >= 8 -> "full"
                filled >= 4 -> "partial"
                else -> "minimal"
            }
        )

        return result
    }

    private fun extractPattern(html: String, patterns: List<String>): String? {
        for (pattern in patterns) {
            val match = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)
            val value = match?.groupValues?.getOrNull(1)?.let(::htmlText)?.trim()
            if (!value.isNullOrBlank() && value.length < 200) return value
        }
        return null
    }

    private fun extractPartyBlock(html: String, className: String): Pair<String?, String?> {
        val block = Regex(
            """<(?:ul|div)[^>]*class=["'][^"']*\b${Regex.escape(className)}\b[^"']*["'][^>]*>(.*?)</(?:ul|div)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1) ?: return null to null

        val lines = htmlText(block).lineSequence().map { it.trim() }
            .filter { it.isNotBlank() }.toList()
        val names = lines.mapNotNull { line ->
            Regex("""^\d+[.)]\s*(.+)$""").find(line)?.groupValues?.getOrNull(1)?.trim()
        }
        val advocates = lines.mapNotNull { line ->
            Regex("""^advocate\s*[-–:]\s*(.+)$""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)?.trim()
        }
        return names.takeIf { it.isNotEmpty() }?.joinToString("\n") to
            advocates.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("\n")
    }

    private fun extractActsAndSections(html: String): JSONArray {
        val table = Regex(
            """<table[^>]*(?:id=["']act_table["']|class=["'][^"']*acts_table[^"']*["'])[^>]*>(.*?)</table>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)?.groupValues?.getOrNull(1) ?: return JSONArray()

        val result = JSONArray()
        Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(table).forEach { row ->
                if (row.value.contains(Regex("<th", RegexOption.IGNORE_CASE))) return@forEach
                val cells = Regex(
                    """<td[^>]*>(.*?)</td>""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).findAll(row.groupValues[1]).map { htmlText(it.groupValues[1]) }.toList()
                if (cells.any { it.isNotBlank() }) {
                    result.put(JSONObject().apply {
                        put("act", cells.getOrNull(0).orEmpty())
                        put("sections", cells.getOrNull(1).orEmpty())
                    })
                }
            }
        return result
    }

    private fun htmlText(fragment: String): String {
        val lineBreak = "KNC_LINE_BREAK_7F3A"
        val marked = fragment
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), lineBreak)
            .replace(Regex("""</li\s*>""", RegexOption.IGNORE_CASE), "$lineBreak</li>")
        return Html.fromHtml(marked, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(lineBreak, "\n")
            .replace('\u00a0', ' ')
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun normalizeCourtDate(value: String): String {
        val numeric = Regex("""^(\d{1,2})-(\d{1,2})-(\d{4})$""").find(value.trim())
        if (numeric != null) {
            val (day, month, year) = numeric.destructured
            return "%s-%02d-%02d".format(year, month.toInt(), day.toInt())
        }

        val named = Regex(
            """^(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]+)\s+(\d{4})$""",
            RegexOption.IGNORE_CASE
        ).find(value.trim())
        if (named != null) {
            val (day, monthName, year) = named.destructured
            val month = listOf(
                "january", "february", "march", "april", "may", "june",
                "july", "august", "september", "october", "november", "december"
            ).indexOf(monthName.lowercase()) + 1
            if (month > 0) return "%s-%02d-%02d".format(year, month, day.toInt())
        }

        val yearFirst = Regex(
            """^(\d{4})\s+([A-Za-z]+)\s+(\d{1,2})$""",
            RegexOption.IGNORE_CASE
        ).find(value.replace(Regex("""&\s*nbsp;?""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim())
        if (yearFirst != null) {
            val (year, monthName, day) = yearFirst.destructured
            val month = mapOf(
                "jan" to 1, "january" to 1, "feb" to 2, "february" to 2,
                "mar" to 3, "march" to 3, "apr" to 4, "april" to 4,
                "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7,
                "aug" to 8, "august" to 8, "sep" to 9, "september" to 9,
                "oct" to 10, "october" to 10, "nov" to 11, "november" to 11,
                "dec" to 12, "december" to 12
            )[monthName.lowercase()]
            if (month != null) return "%s-%02d-%02d".format(year, month, day.toInt())
        }
        return value
    }

    // ── Bitmap utilities ───────────────────────────────────────────────────────

    private fun base64ToBitmap(b64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "base64ToBitmap failed", e)
            null
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String =
        java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        }

    // ── Error handling ─────────────────────────────────────────────────────────

    private fun finishWithError(message: String) {
        Log.e(TAG, "Finishing with error: $message")
        runOnUiThread {
            setResult(RESULT_CANCELED, Intent().apply { putExtra(EXTRA_ERROR, message) })
            finish()
        }
    }
}
