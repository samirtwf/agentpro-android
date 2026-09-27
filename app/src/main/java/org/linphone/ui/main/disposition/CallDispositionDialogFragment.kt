package org.linphone.ui.main.disposition

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import org.linphone.ui.main.campaign.CampaignDialer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.linphone.R

/**
 * Bottom dialog shown after a call so the agent can record the call outcome
 * (Interested / Not Interested / ... / Sold) plus free-text notes.
 */
@UiThread
class CallDispositionDialogFragment : DialogFragment() {
    companion object {
        const val TAG = "CallDispositionDialog"
        private const val ARG_NUMBER = "number"
        private const val ARG_DIRECTION = "direction"

        fun newInstance(number: String, direction: String): CallDispositionDialogFragment {
            return CallDispositionDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NUMBER, number)
                    putString(ARG_DIRECTION, direction)
                }
            }
        }
    }

    private data class Opt(val status: String, val icon: Int, val color: String, val label: String)

    private var selectedStatus: String? = null
    private val optionViews = HashMap<String, View>()

    private var number = ""
    private var direction = "out"
    private var appCtx: Context? = null
    private var resolved = false

    private val options by lazy {
        listOf(
            Opt(DispositionStore.INTERESTED, R.drawable.thumbs_up, "#2ECC71", getString(R.string.ap_disp_interested)),
            Opt(DispositionStore.NOT_INTERESTED, R.drawable.thumbs_down, "#E74C3C", getString(R.string.ap_disp_not_interested)),
            Opt(DispositionStore.NO_ANSWER, R.drawable.phone_x, "#9AA0A8", getString(R.string.ap_disp_no_answer)),
            Opt(DispositionStore.WRONG_NUMBER, R.drawable.warning_circle, "#F39C12", getString(R.string.ap_disp_wrong_number)),
            Opt(DispositionStore.CALLBACK, R.drawable.clock_countdown, "#3498DB", getString(R.string.ap_disp_callback)),
            Opt(DispositionStore.SOLD, R.drawable.checks, "#9B59B6", getString(R.string.ap_disp_sold))
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.call_disposition_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        number = arguments?.getString(ARG_NUMBER).orEmpty()
        direction = arguments?.getString(ARG_DIRECTION) ?: "out"
        appCtx = requireContext().applicationContext

        view.findViewById<TextView>(R.id.disp_subtitle).text = getString(R.string.ap_call_with_fmt, number)

        val row1 = view.findViewById<LinearLayout>(R.id.disp_row1)
        val row2 = view.findViewById<LinearLayout>(R.id.disp_row2)
        options.forEachIndexed { index, opt ->
            val optionView = buildOption(opt)
            optionViews[opt.status] = optionView
            (if (index < 3) row1 else row2).addView(optionView)
        }

        view.findViewById<View>(R.id.disp_close).setOnClickListener { dismiss() }

        val notes = view.findViewById<EditText>(R.id.disp_notes)
        view.findViewById<View>(R.id.disp_save).setOnClickListener {
            val status = selectedStatus
            if (status == null) {
                Toast.makeText(requireContext(), getString(R.string.ap_toast_select_disposition_first), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DispositionStore.add(
                requireContext(),
                Disposition(
                    id = System.currentTimeMillis(),
                    number = number,
                    status = status,
                    notes = notes.text.toString().trim(),
                    timestamp = System.currentTimeMillis(),
                    direction = direction
                )
            )
            Toast.makeText(requireContext(), getString(R.string.ap_toast_disposition_saved), Toast.LENGTH_SHORT).show()
            resolved = true
            CampaignDialer.onDispositionResolved(
                appCtx ?: requireContext().applicationContext,
                number,
                status
            )
            dismiss()
        }

        startTimer(view.findViewById(R.id.disp_timer))
    }

    private fun buildOption(opt: Opt): View {
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val m = (4 * d).toInt()
            lp.setMargins(m, 0, m, 0)
            layoutParams = lp
            val ph = (10 * d).toInt()
            val pv = (14 * d).toInt()
            setPadding(ph, pv, ph, pv)
            background = ContextCompat.getDrawable(ctx, R.drawable.shape_disposition_button)
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(ctx).apply {
            val s = (28 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setImageResource(opt.icon)
            setColorFilter(Color.parseColor(opt.color))
        }
        val label = TextView(ctx).apply {
            text = opt.label
            setTextColor(Color.parseColor(opt.color))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (6 * d).toInt()
            layoutParams = lp
        }
        container.addView(icon)
        container.addView(label)
        container.setOnClickListener { selectStatus(opt.status) }
        return container
    }

    private fun selectStatus(status: String) {
        selectedStatus = status
        val d = resources.displayMetrics.density
        for ((s, v) in optionViews) {
            if (s == status) {
                val opt = options.first { it.status == s }
                val highlight = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(Color.parseColor("#2A3340"))
                    setStroke((2 * d).toInt(), Color.parseColor(opt.color))
                }
                v.background = highlight
            } else {
                v.background = ContextCompat.getDrawable(requireContext(), R.drawable.shape_disposition_button)
            }
        }
    }

    private fun startTimer(timerView: TextView) {
        val start = SystemClock.elapsedRealtime()
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val seconds = ((SystemClock.elapsedRealtime() - start) / 1000L).toInt()
                timerView.text = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
                delay(1000)
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // If the dialog was closed without saving (skip/back), still let the auto-dialer advance.
        if (!resolved) {
            resolved = true
            appCtx?.let { CampaignDialer.onDispositionResolved(it, number, null) }
        }
    }

    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
        dialog?.window?.apply {
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }
}
