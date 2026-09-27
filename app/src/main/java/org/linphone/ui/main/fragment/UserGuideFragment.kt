package org.linphone.ui.main.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.UiThread
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController

/**
 * AgentPro's in-app **User Guide** — a self-contained, scrollable manual that documents every
 * screen and feature directly on the phone (no external website / GitHub links). Replaces the old
 * Help screen, which only opened linphone.org pages in the browser.
 */
@UiThread
class UserGuideFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(org.linphone.R.layout.user_guide_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<ImageView>(org.linphone.R.id.guide_back).setOnClickListener {
            findNavController().popBackStack()
        }
    }
}
