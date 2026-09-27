package org.linphone.ui.main.disposition

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.linphone.R

class DispositionsAdapter(
    private val onCall: (String) -> Unit,
    private val onDelete: (Disposition) -> Unit
) : RecyclerView.Adapter<DispositionsAdapter.ViewHolder>() {

    private val items = mutableListOf<Disposition>()
    private val dateFormat = SimpleDateFormat("MMM dd  HH:mm", Locale.US)

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Disposition>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.disposition_list_item, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val d = items[position]
        holder.index.text = (position + 1).toString()
        holder.number.text = d.number
        holder.status.text = DispositionStore.label(holder.itemView.context, d.status)
        holder.status.setTextColor(colorFor(d.status))
        holder.date.text = dateFormat.format(Date(d.timestamp))
        if (d.notes.isNotEmpty()) {
            holder.notes.visibility = View.VISIBLE
            holder.notes.text = d.notes
        } else {
            holder.notes.visibility = View.GONE
        }
        holder.call.setOnClickListener { onCall(d.number) }
        holder.itemView.setOnClickListener { onCall(d.number) }
        holder.itemView.setOnLongClickListener {
            onDelete(d)
            true
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val index: TextView = view.findViewById(R.id.item_index)
        val number: TextView = view.findViewById(R.id.item_number)
        val status: TextView = view.findViewById(R.id.item_status)
        val date: TextView = view.findViewById(R.id.item_date)
        val notes: TextView = view.findViewById(R.id.item_notes)
        val call: ImageView = view.findViewById(R.id.item_call)
    }

    companion object {
        fun colorFor(status: String): Int = Color.parseColor(
            when (status) {
                DispositionStore.INTERESTED -> "#2ECC71"
                DispositionStore.NOT_INTERESTED -> "#E74C3C"
                DispositionStore.NO_ANSWER -> "#9AA0A8"
                DispositionStore.WRONG_NUMBER -> "#F39C12"
                DispositionStore.CALLBACK -> "#3498DB"
                DispositionStore.SOLD -> "#9B59B6"
                else -> "#9AA0A8"
            }
        )
    }
}
