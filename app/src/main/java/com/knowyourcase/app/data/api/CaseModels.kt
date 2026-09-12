package com.knowyourcase.app.data.api

import com.google.gson.annotations.SerializedName

data class CaptchaRequest(
    @SerializedName("image_b64") val imageBase64: String
)

data class CaptchaResponse(
    @SerializedName("solved") val solved: String?,
    @SerializedName("confidence") val confidence: String
)
data class ParseRequest(
    @SerializedName("cnr") val cnr: String,
    @SerializedName("html") val html: String
)

data class HearingItem(
    @SerializedName("date") val date: String?,
    @SerializedName("judge") val judge: String?,
    @SerializedName("purpose") val purpose: String?,
    @SerializedName("next_date") val nextDate: String?
)

data class ActSection(
    @SerializedName("act") val act: String?,
    @SerializedName("sections") val sections: String?
)

data class CaseResponse(
    @SerializedName("cnr")                val cnr: String,
    @SerializedName("case_title")         val caseTitle: String?,
    @SerializedName("case_number")        val caseNumber: String?,
    @SerializedName("case_type")          val caseType: String?,
    @SerializedName("court_name")         val courtName: String?,
    @SerializedName("registration_number")val registrationNumber: String?,
    @SerializedName("registration_date")  val registrationDate: String?,
    @SerializedName("filing_date")        val filingDate: String?,
    @SerializedName("petitioner")         val petitioner: String?,
    @SerializedName("respondent")         val respondent: String?,
    @SerializedName("petitioner_advocate")val petitionerAdvocate: String?,
    @SerializedName("respondent_advocate")val respondentAdvocate: String?,
    @SerializedName("acts_and_sections")  val actsAndSections: List<ActSection> = emptyList(),
    @SerializedName("next_hearing_date")  val nextHearingDate: String?,
    @SerializedName(value = "hearings", alternate = ["previous_hearings"])
    val hearings: List<HearingItem> = emptyList(),
    @SerializedName("status")             val status: String?,
    @SerializedName("judges")             val judges: List<String> = emptyList(),
    @SerializedName("data_completeness")  val dataCompleteness: String,
    @SerializedName("available_fields")   val availableFields: List<String> = emptyList()
)
