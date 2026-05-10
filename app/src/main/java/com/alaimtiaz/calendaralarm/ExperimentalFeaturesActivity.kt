package com.alaimtiaz.calendaralarm

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Experimental Features Screen
 *
 * Displays toggles for experimental features.
 * All flags default to OFF — app behaves like stable Build #42.
 *
 * In Phase A: this screen is empty (placeholder only).
 * Real toggles will be added in Phase B+.
 */
class ExperimentalFeaturesActivity : AppCompatActivity() {

    private lateinit var togglesContainer: LinearLayout
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_experimental_features)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        togglesContainer = findViewById(R.id.togglesContainer)
        tvEmpty = findViewById(R.id.tvEmpty)

        renderToggles()
    }

    /**
     * Render feature toggles.
     * In Phase A: no toggles → show empty state.
     * In Phase B+: dynamically add toggle rows here.
     */
    private fun renderToggles() {
        val hasAnyToggles = false

        if (hasAnyToggles) {
            tvEmpty.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.VISIBLE
        }
    }
}
