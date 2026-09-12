package com.knowyourcase.app.data.local

import android.content.Context
import com.google.gson.Gson
import com.knowyourcase.app.data.api.CaseResponse
import org.json.JSONArray
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Keeps the 50 most recently fetched complete case responses on this device. */
object CaseCache {
    private const val PREFS = "case_result_cache"
    private const val INDEX = "recent_cnrs"
    private const val PREFIX = "case_"
    private const val FETCHED_PREFIX = "fetched_"
    private const val MAX_CASES = 50
    private const val STALE_AFTER_MS = 12 * 60 * 60 * 1000L
    private val gson = Gson()

    @Synchronized
    fun get(context: Context, cnr: String): CaseResponse? {
        val key = cnr.uppercase()
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREFIX + key, null) ?: return null
        return runCatching { gson.fromJson(json, CaseResponse::class.java) }.getOrNull()
    }

    @Synchronized
    fun put(context: Context, case: CaseResponse) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cnr = case.cnr.uppercase()
        val recent = runCatching {
            val array = JSONArray(prefs.getString(INDEX, "[]"))
            (0 until array.length()).map { array.getString(it) }.toMutableList()
        }.getOrDefault(mutableListOf())

        recent.remove(cnr)
        recent.add(0, cnr)
        val removed = if (recent.size > MAX_CASES) recent.subList(MAX_CASES, recent.size).toList()
            else emptyList()
        if (recent.size > MAX_CASES) recent.subList(MAX_CASES, recent.size).clear()

        prefs.edit().apply {
            putString(PREFIX + cnr, gson.toJson(case))
            putLong(FETCHED_PREFIX + cnr, System.currentTimeMillis())
            putString(INDEX, JSONArray(recent).toString())
            removed.forEach {
                remove(PREFIX + it)
                remove(FETCHED_PREFIX + it)
            }
        }.apply()
    }

    /** True when the cached record should be refreshed in the background. */
    @Synchronized
    fun isStale(context: Context, cnr: String): Boolean {
        val key = cnr.uppercase()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fetchedAt = prefs.getLong(FETCHED_PREFIX + key, 0L)
        if (fetchedAt == 0L || System.currentTimeMillis() - fetchedAt >= STALE_AFTER_MS) {
            return true
        }

        val nextHearing = get(context, key)?.nextHearingDate ?: return false
        return try {
            LocalDate.parse(nextHearing).isBefore(LocalDate.now())
        } catch (_: DateTimeParseException) {
            false
        }
    }
}
