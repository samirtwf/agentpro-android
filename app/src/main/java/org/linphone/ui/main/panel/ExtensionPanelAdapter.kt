package org.linphone.ui.main.panel

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R

data class PanelItem(val ext: String, val state: ExtState)

class ExtensionPanelAdapter(
    private val onCard: (String) -> Unit,
    private val onListen: (String) -> Unit,
    private val onWhisper: (String) -> Unit,
    private val onBarge: (String) -> Unit
) : RecyclerView.Adapter<ExtensionPanelAdapter.VH>() {

    private val items = mutableListOf<PanelItem>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<PanelItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.extension_card_item, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.number.text = item.ext
        holder.ext.text = item.ext

        val (label, colorStr, cardBg) = when (item.state) {
            ExtState.AVAILABLE -> Triple(R.string.ap_state_available, "#2ECC71", R.drawable.shape_card_available)
            ExtState.RINGING -> Triple(R.string.ap_state_ringing, "#F39C12", R.drawable.shape_card_incall)
            ExtState.IN_CALL -> Triple(R.string.ap_state_in_call, "#E74C3C", R.drawable.shape_card_incall)
            ExtState.OFFLINE -> Triple(R.string.ap_offline, "#6E7681", R.drawable.shape_card_offline)
            ExtState.UNKNOWN -> Triple(R.string.ap_state_unknown, "#6E7681", R.drawable.shape_card_offline)
        }
        val color = Color.parseColor(colorStr)
        holder.dot.setColorFilter(color)
        holder.root.setBackgroundResource(cardBg)

        val d = holder.itemView.resources.displayMetrics.density
        holder.status.background = GradientDrawable().apply {
            cornerRadius = 20 * d
            setColor(Color.TRANSPARENT)
            setStroke((1.5 * d).toInt(), color)
        }
        holder.status.setText(label)
        holder.status.setTextColor(color)

        val active = item.state == ExtState.IN_CALL || item.state == ExtState.RINGING
        holder.calling.visibility = if (active) View.VISIBLE else View.GONE
        holder.calling.setText(if (item.state == ExtState.RINGING) R.string.ap_ringing else R.string.ap_on_a_call)
        holder.actions.visibility = if (active) View.VISIBLE else View.GONE

        holder.root.setOnClickListener { onCard(item.ext) }
        holder.listen.setOnClickListener { onListen(item.ext) }
        holder.whisper.setOnClickListener { onWhisper(item.ext) }
        holder.barge.setOnClickListener { onBarge(item.ext) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: LinearLayout = v.findViewById(R.id.card_root)
        val dot: ImageView = v.findViewById(R.id.card_dot)
        val number: TextView = v.findViewById(R.id.card_number)
        val ext: TextView = v.findViewById(R.id.card_ext)
        val status: TextView = v.findViewById(R.id.card_status)
        val calling: TextView = v.findViewById(R.id.card_calling)
        val actions: LinearLayout = v.findViewById(R.id.card_actions)
        val listen: ImageView = v.findViewById(R.id.btn_listen)
        val whisper: ImageView = v.findViewById(R.id.btn_whisper)
        val barge: ImageView = v.findViewById(R.id.btn_barge)
    }
}
