package org.linphone.ui.main.agent

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R

/**
 * One row per call queue in the Agent tab: queue name, the agent's membership status, a
 * Login/Logout button (independent per queue) and a remove button.
 */
class AgentQueueAdapter(
    private val onToggle: (queue: String, join: Boolean) -> Unit,
    private val onDelete: (queue: String) -> Unit
) : RecyclerView.Adapter<AgentQueueAdapter.VH>() {

    /** queue = the queue number/name; member = agent is in it; paused = agent is paused in it. */
    data class Row(val queue: String, val member: Boolean, val paused: Boolean)

    private val items = mutableListOf<Row>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Row>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.agent_queue_item, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        holder.name.text = r.queue
        when {
            r.member && r.paused -> {
                holder.status.text = holder.status.context.getString(R.string.ap_logged_in_paused)
                holder.status.setTextColor(Color.parseColor("#E8A33D"))
            }
            r.member -> {
                holder.status.text = holder.status.context.getString(R.string.ap_logged_in)
                holder.status.setTextColor(Color.parseColor("#2ECC71"))
            }
            else -> {
                holder.status.text = holder.status.context.getString(R.string.ap_logged_out)
                holder.status.setTextColor(Color.parseColor("#8A8F98"))
            }
        }
        holder.toggle.text = if (r.member) holder.toggle.context.getString(R.string.ap_logout) else holder.toggle.context.getString(R.string.ap_login)
        holder.toggle.setBackgroundResource(
            if (r.member) R.drawable.shape_btn_gray else R.drawable.shape_btn_blue
        )
        holder.toggle.setOnClickListener { onToggle(r.queue, !r.member) }
        holder.delete.setOnClickListener { onDelete(r.queue) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.queue_name)
        val status: TextView = v.findViewById(R.id.queue_status)
        val toggle: TextView = v.findViewById(R.id.queue_toggle)
        val delete: ImageView = v.findViewById(R.id.queue_delete)
    }
}
