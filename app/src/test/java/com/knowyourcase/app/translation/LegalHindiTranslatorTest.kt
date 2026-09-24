package com.knowyourcase.app.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class LegalHindiTranslatorTest {
    private val translator = LegalHindiTranslator()

    @Test
    fun translatesCoreCourtTerms() {
        assertEquals("याचिकाकर्ता", translator.translateText("Petitioner"))
        assertEquals("प्रतिवादी", translator.translateText("Respondent"))
        assertEquals("नोटिस की तामील की प्रतीक्षा में", translator.translateText("Awaiting service of notice"))
        assertEquals("अतिरिक्त जिला एवं सत्र न्यायाधीश", translator.translateText("ADDL DIST & SESS JUDGE"))
    }

    @Test
    fun keepsNamesAsNames() {
        assertEquals("कार्तिक नामा", translator.transliterateName("Kartik Nama"))
        assertEquals("कैलाश चौधरी", translator.transliterateName("Kailash Choudhary"))
        assertEquals("किशन लाल बैरवा", translator.transliterateName("Kishan Lal Bairwa"))
        assertEquals("टाटावत", translator.transliterateName("Tatawat"))
    }

    @Test
    fun translatesCaseTitleWithoutSemanticNameTranslation() {
        assertEquals(
            "कार्तिक नामा बनाम राजस्थान राज्य",
            translator.translateCaseTitle("Kartik Nama vs Rajasthan State")
        )
    }

    @Test
    fun preservesPartyNumbering() {
        assertEquals(
            "1. कैलाश चौधरी\n2. मोहम्मद असलम ख़ान",
            translator.transliteratePartyList("1. Kailash Choudhary\n2. Mohd Aslam Khan")
        )
    }

    @Test
    fun translatesSectionLabels() {
        assertEquals("धाराएँ: 420, 406", translator.translateText("Sections: 420, 406"))
    }
}
