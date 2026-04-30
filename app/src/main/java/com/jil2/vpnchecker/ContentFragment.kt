package com.jil2.vpnchecker

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

class ContentFragment : Fragment() {
    companion object {
        fun newInstance(data: String) = ContentFragment().apply {
            arguments = Bundle().apply { putString("content", data) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val scroll = ScrollView(context)
        val tv = TextView(context).apply {
            text = arguments?.getString("content")
            setPadding(40, 40, 40, 40)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
        }
        scroll.addView(tv)
        return scroll
    }
}
