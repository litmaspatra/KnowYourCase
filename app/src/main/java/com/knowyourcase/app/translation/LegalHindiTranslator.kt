package com.knowyourcase.app.translation

/**
 * Small, deterministic English -> Hindi engine tuned for Indian court case data.
 *
 * It deliberately treats names differently from legal vocabulary:
 * - known legal phrases are translated with fixed Hindi
 * - party/advocate names are transliterated, never semantically translated
 * - unknown English words are transliterated instead of being guessed by a general MT model
 *
 * [platformTransliterator] lets Android use ICU transliteration on API 29+ while unit tests and
 * older Android versions use the bundled lightweight fallback.
 */
class LegalHindiTranslator(
    private val platformTransliterator: ((String) -> String)? = null
) {

    private val nameOverrides = mapOf(
        "MOHD" to "मोहम्मद",
        "MOHAMMAD" to "मोहम्मद",
        "MOHAMMED" to "मोहम्मद",
        "MUHAMMAD" to "मोहम्मद",
        "ABDUL" to "अब्दुल",
        "AHMAD" to "अहमद",
        "AHMED" to "अहमद",
        "ASLAM" to "असलम",
        "ANJUM" to "अंजुम",
        "BANO" to "बानो",
        "BEGUM" to "बेगम",
        "KHAN" to "ख़ान",
        "SHAFIQ" to "शफ़ीक़",
        "TASLIM" to "तस्लीम",
        "TASNIM" to "तस्नीम",
        "TABASSUM" to "तबस्सुम",
        "VASIM" to "वसीम",
        "WASIM" to "वसीम",
        "HUMERA" to "हुमैरा",
        "RAEES" to "रईस",
        "RAIS" to "रईस",
        "LOKESH" to "लोकेश",
        "KARTIK" to "कार्तिक",
        "KARTHIK" to "कार्तिक",
        "KUMAR" to "कुमार",
        "JAIN" to "जैन",
        "VIKRAM" to "विक्रम",
        "KAILASH" to "कैलाश",
        "KELASH" to "कैलाश",
        "KAILAS" to "कैलाश",
        "GORDHAN" to "गोरधन",
        "CHOUDHARY" to "चौधरी",
        "CHAUDHARY" to "चौधरी",
        "BAIRWA" to "बैरवा",
        "TATAWAT" to "टाटावत",
        "GUNSARIYA" to "गुनसरिया",
        "GUNSARIA" to "गुनसरिया",
        "TONK" to "टोंक",
        "NIWAI" to "निवाई",
        "NEWAI" to "निवाई",
        "UNIARA" to "उनियारा",
        "JAIPUR" to "जयपुर",
        "RAJASTHAN" to "राजस्थान"
    )

    private val exact = mapOf(
        "CASE DETAILS" to "मामले का विवरण",
        "CASE STAGE" to "मामले की स्थिति",
        "CASE STATUS" to "मामले की स्थिति",
        "NEXT HEARING" to "अगली सुनवाई",
        "NEXT HEARING DATE" to "अगली सुनवाई की तारीख",
        "COURT" to "न्यायालय",
        "COURT NAME" to "न्यायालय",
        "CASE NUMBER" to "मामला संख्या",
        "CASE NO" to "मामला संख्या",
        "CASE TYPE" to "मामले का प्रकार",
        "REGISTRATION NUMBER" to "पंजीकरण संख्या",
        "REGISTRATION NO" to "पंजीकरण संख्या",
        "REGISTRATION DATE" to "पंजीकरण दिनांक",
        "FILING DATE" to "दाखिल दिनांक",
        "JUDGE" to "न्यायाधीश",
        "JUDGES" to "न्यायाधीश",
        "ACTS AND SECTIONS" to "अधिनियम एवं धाराएँ",
        "ACT AND SECTION" to "अधिनियम एवं धारा",
        "SECTIONS" to "धाराएँ",
        "SECTION" to "धारा",
        "PETITIONER" to "याचिकाकर्ता",
        "PETITIONER ADVOCATE" to "याचिकाकर्ता के अधिवक्ता",
        "RESPONDENT" to "प्रतिवादी",
        "RESPONDENT ADVOCATE" to "प्रतिवादी के अधिवक्ता",
        "APPELLANT" to "अपीलार्थी",
        "APPLICANT" to "आवेदक",
        "COMPLAINANT" to "परिवादी",
        "ACCUSED" to "अभियुक्त",
        "STATE" to "राज्य",
        "NOT AVAILABLE" to "उपलब्ध नहीं है",
        "NOT SCHEDULED" to "निर्धारित नहीं है",
        "UNKNOWN" to "अज्ञात",
        "PENDING" to "लंबित",
        "DISPOSED" to "निस्तारित",
        "DISMISSED" to "खारिज",
        "ALLOWED" to "स्वीकृत",
        "LISTED" to "सूचीबद्ध",
        "NOTICE ISSUED" to "नोटिस जारी",
        "SUMMONS ISSUED" to "समन जारी",
        "AWAITING SERVICE OF NOTICE" to "नोटिस की तामील की प्रतीक्षा में",
        "AWAITING SERVICE" to "तामील की प्रतीक्षा में",
        "ARGUMENTS" to "बहस",
        "FINAL ARGUMENTS" to "अंतिम बहस",
        "EVIDENCE" to "साक्ष्य",
        "PLAINTIFF EVIDENCE" to "वादी साक्ष्य",
        "DEFENCE EVIDENCE" to "बचाव साक्ष्य",
        "PROSECUTION EVIDENCE" to "अभियोजन साक्ष्य",
        "APPEARANCE" to "उपस्थिति",
        "WRITTEN STATEMENT" to "लिखित बयान",
        "JUDGMENT" to "निर्णय",
        "ORDER" to "आदेश",
        "BAIL APPLICATION" to "जमानत आवेदन",
        "MISC APPLICATION" to "विविध आवेदन",
        "CIVIL MISC" to "सिविल विविध",
        "CRIMINAL MISC" to "आपराधिक विविध",
        "CIVIL SUIT" to "दीवानी वाद",
        "CRIMINAL CASE" to "आपराधिक मामला",
        "SPECIAL JUDGE" to "विशेष न्यायाधीश",
        "CHIEF JUDICIAL MAGISTRATE" to "मुख्य न्यायिक मजिस्ट्रेट",
        "JUDICIAL MAGISTRATE" to "न्यायिक मजिस्ट्रेट",
        "SENIOR CIVIL JUDGE" to "वरिष्ठ सिविल न्यायाधीश",
        "CIVIL JUDGE" to "सिविल न्यायाधीश",
        "DISTRICT AND SESSIONS JUDGE" to "जिला एवं सत्र न्यायाधीश",
        "DISTRICT & SESSIONS JUDGE" to "जिला एवं सत्र न्यायाधीश",
        "ADDITIONAL DISTRICT AND SESSIONS JUDGE" to "अतिरिक्त जिला एवं सत्र न्यायाधीश",
        "ADDITIONAL DISTRICT & SESSIONS JUDGE" to "अतिरिक्त जिला एवं सत्र न्यायाधीश",
        "ADDL DISTRICT AND SESSIONS JUDGE" to "अतिरिक्त जिला एवं सत्र न्यायाधीश",
        "ADDL DIST & SESS JUDGE" to "अतिरिक्त जिला एवं सत्र न्यायाधीश"
    )

    private val phraseRules = listOf(
        "ADDL. DISTRICT & SESSIONS JUDGE" to "अतिरिक्त जिला एवं सत्र न्यायाधीश",
        "ADDL DISTRICT SESSIONS JUDGE" to "अतिरिक्त जिला एवं सत्र न्यायाधीश",
        "ADDITIONAL DISTRICT SESSIONS JUDGE" to "अतिरिक्त जिला एवं सत्र न्यायाधीश",
        "DISTRICT SESSIONS JUDGE" to "जिला एवं सत्र न्यायाधीश",
        "CHIEF JUDICIAL MAGISTRATE" to "मुख्य न्यायिक मजिस्ट्रेट",
        "JUDICIAL MAGISTRATE" to "न्यायिक मजिस्ट्रेट",
        "SENIOR CIVIL JUDGE" to "वरिष्ठ सिविल न्यायाधीश",
        "SPECIAL JUDGE" to "विशेष न्यायाधीश",
        "CIVIL JUDGE" to "सिविल न्यायाधीश",
        "AWAITING SERVICE OF NOTICE" to "नोटिस की तामील की प्रतीक्षा में",
        "AWAITING SERVICE" to "तामील की प्रतीक्षा में",
        "FINAL ARGUMENTS" to "अंतिम बहस",
        "PROSECUTION EVIDENCE" to "अभियोजन साक्ष्य",
        "DEFENCE EVIDENCE" to "बचाव साक्ष्य",
        "PLAINTIFF EVIDENCE" to "वादी साक्ष्य",
        "WRITTEN STATEMENT" to "लिखित बयान",
        "NOTICE ISSUED" to "नोटिस जारी",
        "SUMMONS ISSUED" to "समन जारी",
        "BAIL APPLICATION" to "जमानत आवेदन",
        "MISC APPLICATION" to "विविध आवेदन",
        "CRIMINAL MISC" to "आपराधिक विविध",
        "CIVIL MISC" to "सिविल विविध",
        "CASE DETAILS" to "मामले का विवरण",
        "CASE STAGE" to "मामले की स्थिति",
        "NEXT HEARING" to "अगली सुनवाई",
        "REGISTRATION NUMBER" to "पंजीकरण संख्या",
        "REGISTRATION DATE" to "पंजीकरण दिनांक",
        "FILING DATE" to "दाखिल दिनांक",
        "ACTS AND SECTIONS" to "अधिनियम एवं धाराएँ"
    )

    private val wordMap = mapOf(
        "court" to "न्यायालय",
        "district" to "जिला",
        "sessions" to "सत्र",
        "session" to "सत्र",
        "additional" to "अतिरिक्त",
        "addl" to "अतिरिक्त",
        "judge" to "न्यायाधीश",
        "civil" to "सिविल",
        "criminal" to "आपराधिक",
        "misc" to "विविध",
        "connected" to "संबद्ध",
        "application" to "आवेदन",
        "petition" to "याचिका",
        "appeal" to "अपील",
        "revision" to "पुनरीक्षण",
        "bail" to "जमानत",
        "notice" to "नोटिस",
        "summons" to "समन",
        "service" to "तामील",
        "hearing" to "सुनवाई",
        "evidence" to "साक्ष्य",
        "argument" to "बहस",
        "arguments" to "बहस",
        "judgment" to "निर्णय",
        "order" to "आदेश",
        "stage" to "स्थिति",
        "status" to "स्थिति",
        "pending" to "लंबित",
        "disposed" to "निस्तारित",
        "dismissed" to "खारिज",
        "allowed" to "स्वीकृत",
        "listed" to "सूचीबद्ध",
        "issued" to "जारी",
        "appearance" to "उपस्थिति",
        "petitioner" to "याचिकाकर्ता",
        "respondent" to "प्रतिवादी",
        "appellant" to "अपीलार्थी",
        "applicant" to "आवेदक",
        "complainant" to "परिवादी",
        "accused" to "अभियुक्त",
        "advocate" to "अधिवक्ता",
        "counsel" to "अधिवक्ता",
        "state" to "राज्य",
        "section" to "धारा",
        "sections" to "धाराएँ",
        "act" to "अधिनियम",
        "acts" to "अधिनियम",
        "police" to "पुलिस",
        "station" to "थाना",
        "versus" to "बनाम",
        "vs" to "बनाम",
        "and" to "और",
        "other" to "अन्य",
        "others" to "अन्य",
        "aka" to "उर्फ़",
        "registrar" to "रजिस्ट्रार",
        "tehsildar" to "तहसीलदार",
        "sub" to "उप"
    )

    fun translateText(input: String): String {
        if (input.isBlank()) return input
        val trimmed = input.trim()
        val normalized = normalize(trimmed)

        exact[normalized]?.let { return it }
        translateCourtName(normalized)?.let { return it }

        var result = trimmed
        phraseRules.forEach { (english, hindi) ->
            result = result.replace(Regex(Regex.escape(english), RegexOption.IGNORE_CASE), hindi)
        }

        result = translateSectionLabels(result)
        result = translateCommonWords(result)
        return cleanHindi(result)
    }

    fun translateCaseTitle(input: String): String {
        val sides = input.split(Regex("\\s+v(?:s\\.?|ersus)\\s+", RegexOption.IGNORE_CASE), 2)
        return if (sides.size == 2) {
            "${transliterateName(sides[0])} बनाम ${transliterateName(sides[1])}"
        } else {
            transliterateName(input)
        }
    }

    fun transliteratePartyList(input: String): String {
        return input.lineSequence().map { raw ->
            val line = raw.trim()
            if (line.isBlank()) return@map line
            val number = Regex("^(\\d+[.)])\\s*").find(line)?.groupValues?.get(1)
            val body = line.replace(Regex("^\\d+[.)]\\s*"), "")
            val translated = transliterateName(body)
            if (number != null) "$number $translated" else translated
        }.joinToString("\n")
    }

    fun transliterateName(input: String): String {
        if (input.isBlank()) return input
        return Regex("[A-Za-z]+|[^A-Za-z]+").findAll(input).joinToString("") { match ->
            val token = match.value
            if (token.firstOrNull()?.isLetter() == true) {
                val upper = token.uppercase()
                nameOverrides[upper]
                    ?: wordMap[token.lowercase()]
                    ?: platformTransliterator?.invoke(token.lowercase())
                    ?: FallbackNameTransliterator.transliterate(token)
            } else {
                token
            }
        }.let(::cleanHindi)
    }

    private fun translateCourtName(normalized: String): String? {
        return when {
            normalized.contains("ADDL") &&
                normalized.contains("DIST") &&
                (normalized.contains("SESSION") || normalized.contains("SESS")) &&
                normalized.contains("JUDGE") ->
                "अतिरिक्त जिला एवं सत्र न्यायाधीश"

            normalized.contains("ADDITIONAL") &&
                normalized.contains("DISTRICT") &&
                (normalized.contains("SESSION") || normalized.contains("SESS")) &&
                normalized.contains("JUDGE") ->
                "अतिरिक्त जिला एवं सत्र न्यायाधीश"

            normalized.contains("DISTRICT") &&
                (normalized.contains("SESSION") || normalized.contains("SESS")) &&
                normalized.contains("JUDGE") ->
                "जिला एवं सत्र न्यायाधीश"

            normalized.contains("CHIEF JUDICIAL MAGISTRATE") -> "मुख्य न्यायिक मजिस्ट्रेट"
            normalized.contains("JUDICIAL MAGISTRATE") -> "न्यायिक मजिस्ट्रेट"
            normalized.contains("SENIOR CIVIL JUDGE") -> "वरिष्ठ सिविल न्यायाधीश"
            normalized.contains("SPECIAL JUDGE") -> "विशेष न्यायाधीश"
            normalized.contains("CIVIL JUDGE") -> "सिविल न्यायाधीश"
            else -> null
        }
    }

    private fun translateSectionLabels(value: String): String {
        return value
            .replace(Regex("\\bSections\\s*:", RegexOption.IGNORE_CASE), "धाराएँ:")
            .replace(Regex("\\bSection\\s*:", RegexOption.IGNORE_CASE), "धारा:")
            .replace(Regex("\\bAct\\s*:", RegexOption.IGNORE_CASE), "अधिनियम:")
    }

    private fun translateCommonWords(value: String): String {
        return Regex("[A-Za-z]+").replace(value) { match ->
            val key = match.value.lowercase()
            wordMap[key]
                ?: nameOverrides[match.value.uppercase()]
                ?: platformTransliterator?.invoke(key)
                ?: FallbackNameTransliterator.transliterate(match.value)
        }
    }

    private fun normalize(value: String): String =
        value.uppercase()
            .replace("&", " AND ")
            .replace(Regex("[.\\-/,()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun cleanHindi(value: String): String {
        return value
            .replace("रियास", "रईस")
            .replace("रियाज़", "रईस")
            .replace("शाफीक", "शफ़ीक़")
            .replace(Regex("्(?=\\s|$|[.,()/])"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }
}

private object FallbackNameTransliterator {
    private val independentVowels = mapOf(
        "a" to "अ", "aa" to "आ", "i" to "इ", "ee" to "ई", "ii" to "ई",
        "u" to "उ", "oo" to "ऊ", "uu" to "ऊ", "e" to "ए", "ai" to "ऐ",
        "o" to "ओ", "au" to "औ"
    )

    private val matras = mapOf(
        "a" to "", "aa" to "ा", "i" to "ि", "ee" to "ी", "ii" to "ी",
        "u" to "ु", "oo" to "ू", "uu" to "ू", "e" to "े", "ai" to "ै",
        "o" to "ो", "au" to "ौ"
    )

    private val consonants = linkedMapOf(
        "ksh" to "क्ष", "gy" to "ज्ञ", "chh" to "छ", "sh" to "श",
        "kh" to "ख", "gh" to "घ", "ch" to "च", "jh" to "झ",
        "th" to "थ", "dh" to "ध", "ph" to "फ", "bh" to "भ",
        "tr" to "त्र",
        "k" to "क", "g" to "ग", "c" to "क", "j" to "ज",
        "t" to "त", "d" to "द", "n" to "न", "p" to "प",
        "b" to "ब", "m" to "म", "y" to "य", "r" to "र",
        "l" to "ल", "v" to "व", "w" to "व", "s" to "स",
        "h" to "ह", "f" to "फ", "q" to "क", "x" to "क्स",
        "z" to "ज़"
    )

    fun transliterate(word: String): String {
        val source = word.lowercase()
        if (source.isBlank()) return word

        val out = StringBuilder()
        var index = 0
        var previousWasConsonant = false

        while (index < source.length) {
            val vowel = longestMatch(source, index, independentVowels.keys)
            if (vowel != null) {
                if (previousWasConsonant) {
                    out.append(matras[vowel].orEmpty())
                } else {
                    out.append(independentVowels[vowel].orEmpty())
                }
                previousWasConsonant = false
                index += vowel.length
                continue
            }

            val consonant = longestMatch(source, index, consonants.keys)
            if (consonant != null) {
                if (previousWasConsonant) out.append("्")
                out.append(consonants[consonant].orEmpty())
                previousWasConsonant = true
                index += consonant.length
                continue
            }

            out.append(source[index])
            previousWasConsonant = false
            index++
        }

        return out.toString().replace(Regex("्$"), "")
    }

    private fun longestMatch(source: String, start: Int, candidates: Set<String>): String? {
        return candidates
            .filter { source.startsWith(it, start) }
            .maxByOrNull { it.length }
    }
}
