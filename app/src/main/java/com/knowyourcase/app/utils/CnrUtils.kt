package com.knowyourcase.app.utils

object CnrUtils {
    // CNR: 4 letters + 12 digits = 16 chars
    private val CNR_REGEX = Regex("^[A-Z]{4}\\d{12}$", RegexOption.IGNORE_CASE)

    fun normalise(raw: String): String =
        raw.replace(Regex("[\\s\\-\\.]"), "").uppercase()

    fun isValid(cnr: String): Boolean =
        CNR_REGEX.matches(normalise(cnr))

    /** Try to extract a CNR from any block of OCR text */
    fun extractFromText(text: String): String? {
        // Look for 16-char alphanumeric starting with 4 letters
        val pattern = Regex("[A-Z]{4}\\d{12}", RegexOption.IGNORE_CASE)
        return pattern.find(text.replace(Regex("[\\s\\-\\.]"), ""))?.value?.uppercase()
    }
}
