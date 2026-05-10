package com.alaimtiaz.calendaralarm

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

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
        // Hide empty state — we have at least one toggle now
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
     */
    private fun addToggle(
        title: String,
        description: String,
        isChecked: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        val row = LayoutInflater.from(this).inflate(
            android.R.layout.simple_list_item_2,
            togglesContainer,
            false
        ) as LinearLayout

        // Build a custom row programmatically (simpler than another XML file)
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(16))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF1F1F1F.toInt())
        }

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
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val descView = TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(0xFFBDBDBD.toInt())
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            layoutParams = lp
        }

        val switchView = SwitchCompat(this).apply {
            this.isChecked = isChecked
            setOnCheckedChangeListener { _, checked ->
                onChange(checked)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                marginStart = dp(12)
            }
            layoutParams = lp
        }

        textsContainer.addView(titleView)
        textsContainer.addView(descView)
        customRow.addView(textsContainer)
        customRow.addView(switchView)

        togglesContainer.addView(customRow)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
