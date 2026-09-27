package org.linphone.ui.main.disposition

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.tools.Log
import org.linphone.ui.main.AccountScope
import org.linphone.R

/**
 * A single call disposition (outcome) recorded by the agent after a call.
 */
data class Disposition(
    val id: Long,
    val number: String,
    val status: String,
    val notes: String,
    val timestamp: Long,
    val direction: String // "in" or "out"
)

/**
 * Simple JSON-backed local database for call dispositions, stored in SharedPreferences.
 * Good enough for an agent's history (search / sort / filter / export are done in memory).
 */
object DispositionStore {
    private const val TAG = "[Disposition Store]"
    private const val PREFS = "agentpro_dispositions"
    private const val KEY = "records"

    // Available disposition statuses
    const val INTERESTED = "Interested"
    const val NOT_INTERESTED = "Not Interested"
    const val NO_ANSWER = "No Answer"
    const val WRONG_NUMBER = "Wrong Number"
    const val CALLBACK = "Callback"
    const val SOLD = "Sold"

    val ALL_STATUSES = listOf(INTERESTED, NOT_INTERESTED, NO_ANSWER, WRONG_NUMBER, CALLBACK, SOLD)

    // A status is stored in English, so records and exports keep one vocabulary whatever the
    // app language; this is how it is shown. An unknown value is shown as stored.
    fun label(context: Context, status: String): String = when (status) {
        INTERESTED -> context.getString(R.string.ap_disp_interested)
        NOT_INTERESTED -> context.getString(R.string.ap_disp_not_interested)
        NO_ANSWER -> context.getString(R.string.ap_disp_no_answer)
        WRONG_NUMBER -> context.getString(R.string.ap_disp_wrong_number)
        CALLBACK -> context.getString(R.string.ap_disp_callback)
        SOLD -> context.getString(R.string.ap_disp_sold)
        else -> status
    }

    // Scoped per active SIP account (see AccountScope) so each account keeps its own dispositions.
    private fun prefs(context: Context) =
        context.getSharedPreferences(AccountScope.prefsName(PREFS), Context.MODE_PRIVATE)

    fun getAll(context: Context): MutableList<Disposition> {
        val list = mutableListOf<Disposition>()
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                list.add(
                    Disposition(
                        id = o.optLong("id"),
                        number = o.optString("number"),
                        status = o.optString("status"),
                        notes = o.optString("notes"),
                        timestamp = o.optLong("timestamp"),
                        direction = o.optString("direction", "out")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("$TAG Failed to read dispositions: $e")
        }
        return list
    }

    private fun saveAll(context: Context, list: List<Disposition>) {
        val array = JSONArray()
        for (d in list) {
            val o = JSONObject()
            o.put("id", d.id)
            o.put("number", d.number)
            o.put("status", d.status)
            o.put("notes", d.notes)
            o.put("timestamp", d.timestamp)
            o.put("direction", d.direction)
            array.put(o)
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    fun add(context: Context, disposition: Disposition) {
        val list = getAll(context)
        list.add(disposition)
        saveAll(context, list)
        Log.i("$TAG Saved disposition [${disposition.status}] for [${disposition.number}]")
    }

    fun delete(context: Context, id: Long) {
        val list = getAll(context)
        list.removeAll { it.id == id }
        saveAll(context, list)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
        Log.i("$TAG Cleared all dispositions")
    }

    fun count(context: Context): Int = getAll(context).size
}
