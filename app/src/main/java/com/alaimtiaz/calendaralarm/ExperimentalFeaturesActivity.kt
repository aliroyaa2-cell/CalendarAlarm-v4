package com.alaimtiaz.calendaralarm

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Experimental Features Screen
 *
 * Displays toggles for experimental features.
 * All flags default to OFF — app behaves like stable Build #42.
 *
 * Phase B: First real toggle — CalendarArchive button override.
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

    private fun renderToggles() {
        // Hide empty state — we have at least one toggle
        tvEmpty.visibility = View.GONE

        // Toggle 1: Archive button override
        addToggle(
            title = "زر البحث يفتح CalendarArchive",
            description = "بدل اختيار التقويم في كل مرة، يفتح CalendarArchive مباشرة.\n" +
                    "لو CalendarArchive غير مثبت، يرجع للسلوك الأصلي تلقائياً.",
            isChecked = FeatureFlags.isArchiveButtonEnabled(this),
            onChange = { enabled ->
                FeatureFlags.setArchiveButtonEnabled(this, enabled)
            }
        )
    }

    /**
     * Add a single toggle row to the container.
     * Uses plain android.widget.Switch (not SwitchCompat) for theme stability.
     */
    private fun addToggle(
        title: String,
        description: String,
        isChecked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        // Container row
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(12))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF1F1F1F.toInt())
        }

        // Texts container (left side, takes remaining space)
        val textsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
        }

        val descView = TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(0xFFBDBDBD.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }

        textsContainer.addView(titleView)
        textsContainer.addView(descView)

        // Plain Switch (right side) — uses platform theme, no AppCompat dependency
        val switchView = Switch(this).apply {
            this.isChecked = isChecked
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
            }
        }

        row.addView(textsContainer)
        row.addView(switchView)

        togglesContainer.addView(row)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
