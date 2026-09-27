package org.linphone.ui.main.campaign

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.linphone.R
import org.linphone.ui.main.disposition.DispositionsAdapter

class CampaignAdapter(
    private val onCall: (CampaignContact) -> Unit,
    private val onDelete: (CampaignContact) -> Unit,
    private val onEdit: (CampaignContact) -> Unit
) : RecyclerView.Adapter<CampaignAdapter.ViewHolder>() {

    private val items = mutableListOf<CampaignContact>()
    private val dateFormat = SimpleDateFormat("MMM dd HH:mm", Locale.US)
    private var highlightId = -1L

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CampaignContact>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setHighlight(id: Long) {
        if (highlightId == id) return
        highlightId = id
        notifyDataSetChanged()
    }

    fun positionOf(id: Long): Int = items.indexOfFirst { it.id == id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.campaign_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val c = items[position]
        holder.index.text = (position + 1).toString()
        holder.name.text = if (c.name.isNotEmpty()) c.name else "—"
        holder.phone.text = c.phone

        if (c.disposition.isNotEmpty()) {
            holder.disposition.text = c.disposition
            holder.disposition.setTextColor(DispositionsAdapter.colorFor(c.disposition))
        } else {
            holder.disposition.text = "—"
            holder.disposition.setTextColor(Color.parseColor("#8A8F98"))
        }

        holder.status.text = c.status
        holder.status.setTextColor(
            if (c.status == CampaignContact.STATUS_CALLED) {
                Color.parseColor("#2ECC71")
            } else {
                Color.parseColor("#B6BDC7")
            }
        )

        holder.lastCall.text = if (c.lastCall > 0) dateFormat.format(Date(c.lastCall)) else "—"

        holder.select.setOnCheckedChangeListener(null)
        holder.select.isChecked = c.selected
        holder.select.setOnCheckedChangeListener { _, checked -> c.selected = checked }

        holder.call.setOnClickListener { onCall(c) }
        holder.delete.setOnClickListener { onDelete(c) }
        holder.edit.setOnClickListener { onEdit(c) }

        if (c.id == highlightId) {
            holder.itemView.setBackgroundColor(Color.parseColor("#22314D"))
        } else {
            holder.itemView.setBackgroundResource(R.drawable.campaign_row_divider)
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val select: CheckBox = view.findViewById(R.id.item_select)
        val edit: ImageView = view.findViewById(R.id.item_edit)
        val index: TextView = view.findViewById(R.id.item_index)
        val name: TextView = view.findViewById(R.id.item_name)
        val phone: TextView = view.findViewById(R.id.item_phone)
        val disposition: TextView = view.findViewById(R.id.item_disposition)
        val status: TextView = view.findViewById(R.id.item_status)
        val lastCall: TextView = view.findViewById(R.id.item_lastcall)
        val call: ImageView = view.findViewById(R.id.item_call)
        val delete: ImageView = view.findViewById(R.id.item_delete)
    }
}
