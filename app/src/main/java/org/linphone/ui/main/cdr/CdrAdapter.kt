package org.linphone.ui.main.cdr

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R

class CdrAdapter : RecyclerView.Adapter<CdrAdapter.VH>() {

    private val items = mutableListOf<CdrRecord>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CdrRecord>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.cdr_list_item, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.route.text = "${r.src.ifBlank { "?" }}  →  ${r.dst.ifBlank { "?" }}"
        holder.meta.text = "${r.start}   ·   ${formatDuration(r.billSec.takeIf { it > 0 } ?: r.durationSec)}"

        holder.disposition.text = r.disposition.ifBlank { "—" }
        holder.disposition.setTextColor(dispositionColor(r.disposition))
    }

    private fun formatDuration(sec: Int): String {
        if (sec <= 0) return "00:00"
        return "%02d:%02d".format(sec / 60, sec % 60)
    }

    private fun dispositionColor(d: String): Int = when (d.uppercase()) {
        "ANSWERED" -> Color.parseColor("#2ECC71")
        "NO ANSWER", "NOANSWER" -> Color.parseColor("#F39C12")
        "BUSY" -> Color.parseColor("#E8A33D")
        "FAILED", "CONGESTION" -> Color.parseColor("#E74C3C")
        else -> Color.parseColor("#8A8F98")
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val route: TextView = v.findViewById(R.id.cdr_route)
        val meta: TextView = v.findViewById(R.id.cdr_meta)
        val disposition: TextView = v.findViewById(R.id.cdr_disposition)
    }
}
