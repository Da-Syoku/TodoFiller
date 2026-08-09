package dev.togar.dynasched.ui

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * まだ実装していない画面（定期・目標）のプレースホルダ。
 * newInstance(title, note) でタイトルと説明を渡す。
 */
class PlaceholderFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val title = arguments?.getString(ARG_TITLE) ?: ""
        val note = arguments?.getString(ARG_NOTE) ?: "この画面は次の段階で実装します"

        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F0F23"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(48, 48, 48, 48)
        }

        val titleView = TextView(requireContext()).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val noteView = TextView(requireContext()).apply {
            text = note
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }

        layout.addView(titleView)
        layout.addView(noteView)
        return layout
    }

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_NOTE = "note"

        fun newInstance(title: String, note: String): PlaceholderFragment {
            val f = PlaceholderFragment()
            f.arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_NOTE, note)
            }
            return f
        }
    }
}
