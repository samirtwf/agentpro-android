package org.linphone.ui.main.campaign

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.core.tools.Log
import org.linphone.ui.main.AccountScope

/**
 * A single campaign contact to be (auto) dialed.
 */
data class CampaignContact(
    val id: Long,
    var name: String,
    var phone: String,
    var status: String = STATUS_PENDING,
    var disposition: String = "",
    var lastCall: Long = 0L
) {
    // Transient UI selection state (not persisted)
    var selected: Boolean = false

    companion object {
        const val STATUS_PENDING = "Pending"
        const val STATUS_CALLED = "Called"
    }
}

/**
 * JSON-file backed store for the dialing campaign. A file (not SharedPreferences) is used
 * because a campaign can hold thousands of contacts. The fragment keeps the list in memory
 * and calls save() after mutations.
 */
object CampaignStore {
    private const val TAG = "[Campaign Store]"
    private const val FILE_NAME = "campaign.json"
    private val lock = Any() // serialize file reads/writes (fragment IO thread vs dialer thread)

    // Scoped per active SIP account (see AccountScope) so each account keeps its own list. Both the
    // Campaign (campaign.json) and Contacts (contacts.json) lists flow through here.
    private fun file(context: Context, fileName: String = FILE_NAME) =
        File(context.filesDir, AccountScope.fileName(fileName))

    fun load(context: Context, fileName: String = FILE_NAME): MutableList<CampaignContact> {
        val list = mutableListOf<CampaignContact>()
        val f = file(context, fileName)
        if (!f.exists()) return list
        synchronized(lock) {
            try {
                val array = JSONArray(f.readText(Charsets.UTF_8))
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    list.add(
                        CampaignContact(
                            id = o.optLong("id"),
                            name = o.optString("name"),
                            phone = o.optString("phone"),
                            status = o.optString("status", CampaignContact.STATUS_PENDING),
                            disposition = o.optString("disposition"),
                            lastCall = o.optLong("lastCall")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("$TAG Failed to load campaign: $e")
            }
        }
        return list
    }

    fun save(context: Context, list: List<CampaignContact>, fileName: String = FILE_NAME) {
        synchronized(lock) {
            try {
                val array = JSONArray()
                for (c in list) {
                    val o = JSONObject()
                    o.put("id", c.id)
                    o.put("name", c.name)
                    o.put("phone", c.phone)
                    o.put("status", c.status)
                    o.put("disposition", c.disposition)
                    o.put("lastCall", c.lastCall)
                    array.put(o)
                }
                file(context, fileName).writeText(array.toString(), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e("$TAG Failed to save campaign: $e")
            }
        }
    }

    fun clear(context: Context, fileName: String = FILE_NAME) {
        try {
            file(context, fileName).delete()
        } catch (e: Exception) {
            Log.e("$TAG Failed to clear campaign: $e")
        }
    }

    /**
     * Mirrors a disposition onto a matching campaign contact (matched by phone digits) and marks
     * it Called. Used when a manual call to a campaign number gets a disposition while the
     * auto-dialer is NOT running. No-op if there is no campaign file or no matching contact.
     */
    fun updateDispositionByPhone(context: Context, phone: String, disposition: String): Boolean {
        val list = load(context)
        if (list.isEmpty()) return false
        var matched = false
        for (c in list) {
            if (phonesMatch(c.phone, phone)) {
                c.disposition = disposition
                c.status = CampaignContact.STATUS_CALLED
                if (c.lastCall == 0L) c.lastCall = System.currentTimeMillis()
                matched = true
            }
        }
        if (matched) save(context, list)
        return matched
    }

    private fun phonesMatch(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.isEmpty() || db.isEmpty()) return false
        if (da == db) return true
        val min = minOf(da.length, db.length)
        if (min < 6) return false
        return da.takeLast(min) == db.takeLast(min)
    }

    /**
     * Parses an imported file (auto-detecting the format) into contacts. Excel `.xlsx` files
     * (ZIP, magic bytes "PK") are read via [XlsxUtils]; anything else is treated as CSV/plain
     * text. The phone and name columns are auto-detected, so column order does not matter.
     */
    fun parseImport(bytes: ByteArray): List<CampaignContact> {
        val rows: List<List<String>> = if (isZip(bytes)) {
            XlsxUtils.read(bytes.inputStream())
        } else {
            String(bytes, Charsets.UTF_8).lineSequence()
                .map { it.trim().trim('﻿') }
                .filter { it.isNotEmpty() }
                .map { line -> line.split(',', ';', '\t').map { it.trim().trim('"') } }
                .toList()
        }
        return rowsToContacts(rows)
    }

    /** Kept for callers that already have plain CSV/text. */
    fun parseCsv(content: String): List<CampaignContact> =
        parseImport(content.toByteArray(Charsets.UTF_8))

    private fun isZip(b: ByteArray): Boolean =
        b.size >= 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()

    private val PHONE_HEADER_KEYS = listOf(
        "phone", "number", "mobile", "tel", "msisdn", "هاتف", "جوال", "موبايل", "رقم"
    )
    private val NAME_HEADER_KEYS = listOf(
        "name", "contact", "client", "customer", "الاسم", "اسم", "العميل"
    )

    /**
     * Turns parsed rows (from CSV or xlsx) into contacts.
     *
     * If the first row looks like a header (it names a phone column, English or Arabic), the
     * phone/name columns are taken by position for every data row — this correctly imports short
     * internal extensions (e.g. "5559") that the digit heuristic would otherwise drop. Without a
     * recognizable header it falls back to picking, per row, the field with the most digits as the
     * phone and the first other field as the name. Phone values keep their leading zeros and any
     * leading '+'; they are NOT reformatted (the auto-dialer dials them verbatim).
     */
    fun rowsToContacts(rows: List<List<String>>): List<CampaignContact> {
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { it.trim().lowercase() }
        val phoneCol = header.indexOfFirst { h -> h.isNotEmpty() && PHONE_HEADER_KEYS.any { h.contains(it) } }
        val nameCol = header.indexOfFirst { h -> h.isNotEmpty() && NAME_HEADER_KEYS.any { h.contains(it) } }
        val hasHeader = phoneCol >= 0
        val dataRows = if (hasHeader) rows.drop(1) else rows

        val result = mutableListOf<CampaignContact>()
        var idSeed = System.currentTimeMillis()
        for (raw in dataRows) {
            val fields = raw.map { it.trim() }
            if (fields.all { it.isEmpty() }) continue

            val phoneRaw: String? = if (hasHeader && phoneCol < fields.size && fields[phoneCol].isNotEmpty()) {
                fields[phoneCol]
            } else {
                fields.filter { f -> f.count { it.isDigit() } >= 3 }
                    .maxByOrNull { f -> f.count { it.isDigit() } }
            }
            val phone = phoneRaw?.filter { it.isDigit() || it == '+' }.orEmpty()
            if (phone.none { it.isDigit() }) continue

            val name = when {
                hasHeader && nameCol in fields.indices -> fields[nameCol]
                else -> fields.firstOrNull { it != phoneRaw && it.isNotEmpty() } ?: ""
            }
            result.add(CampaignContact(id = idSeed++, name = name.trim(), phone = phone))
        }
        return result
    }
}
