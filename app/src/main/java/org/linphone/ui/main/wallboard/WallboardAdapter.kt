package org.linphone.ui.main.wallboard

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R
import org.linphone.ui.main.panel.QueueStat

/** One live card per call queue on the Wallboard. */
class WallboardAdapter : RecyclerView.Adapter<WallboardAdapter.VH>() {

    private val items = mutableListOf<QueueStat>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<QueueStat>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.wallboard_item, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.queue.text = s.queue
        holder.waiting.text = s.waiting.toString()
        holder.waiting.setTextColor(
            Color.parseColor(if (s.waiting > 0) "#E74C3C" else "#2ECC71")
        )
        holder.loggedIn.text = s.loggedIn.toString()
        holder.available.text = s.available.toString()
        holder.longest.text = mmss(s.longestHoldSec)
        holder.talk.text = mmss(s.talkTimeSec)
    }

    private fun mmss(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val queue: TextView = v.findViewById(R.id.wb_queue)
        val waiting: TextView = v.findViewById(R.id.wb_waiting)
        val loggedIn: TextView = v.findViewById(R.id.wb_loggedin)
        val available: TextView = v.findViewById(R.id.wb_available)
        val longest: TextView = v.findViewById(R.id.wb_longest)
        val talk: TextView = v.findViewById(R.id.wb_talk)
    }
}
