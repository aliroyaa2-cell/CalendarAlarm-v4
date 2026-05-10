package com.alaimtiaz.calendaralarm

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.databinding.ActivityMainBinding
import com.alaimtiaz.calendaralarm.permissions.PermissionsManager
import com.alaimtiaz.calendaralarm.service.CalendarSyncService
import com.alaimtiaz.calendaralarm.ui.EventsAdapter
import com.alaimtiaz.calendaralarm.util.PreferencesHelper
import com.alaimtiaz.calendaralarm.viewmodel.MainViewModel
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: EventsAdapter
    private lateinit var permsMgr: PermissionsManager
    private lateinit var prefs: PreferencesHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permsMgr = PermissionsManager(applicationContext)
        prefs = PreferencesHelper(applicationContext)

        adapter = EventsAdapter(this) { event ->
            openEventInCalendar(event)
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        // Permissions banner
        binding.bannerPermissions.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Tabs
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> viewModel.setTab(MainViewModel.Tab.UPCOMING)
                    1 -> viewModel.setTab(MainViewModel.Tab.PAST)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Search
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setSearchQuery(query ?: "")
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText ?: "")
                return true
            }
        })

        // Samsung Calendar search button
        binding.btnSearchSamsung.setOnClickListener {
            openSamsungCalendar()
        }

        // Menu button (3 dots)
        binding.btnMenu.setOnClickListener { v ->
            val popup = PopupMenu(this, v)
            popup.menuInflater.inflate(R.menu.menu_main, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_source_calendars -> {
                        startActivity(Intent(this, SourceCalendarsActivity::class.java))
                        true
                    }
                    R.id.action_settings -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        true
                    }
                    R.id.action_sync_now -> {
                        if (!permsMgr.isCalendarGranted()) {
                            Toast.makeText(this, R.string.perm_calendar, Toast.LENGTH_SHORT).show()
                        } else {
                            CalendarSyncService.syncNow(this)
                            Toast.makeText(this, R.string.toast_sync_started, Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.action_experimental_features -> {
                        startActivity(Intent(this, ExperimentalFeaturesActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // Subscribe to dynamic events flow (changes with tab + search)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { list ->
                    adapter.submit(list)
                    binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }

        // Mark first launch
        if (!prefs.hasOpenedBefore) prefs.hasOpenedBefore = true

        // Start sync service if calendar permission is granted
        if (permsMgr.isCalendarGranted()) {
            CalendarSyncService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBanner()
    }

    private fun refreshBanner() {
        val missing = permsMgr.missingCount()
        if (missing == 0) {
            binding.bannerPermissions.visibility = View.GONE
        } else {
            binding.bannerPermissions.visibility = View.VISIBLE
            binding.tvBannerSubtitle.text = getString(R.string.permissions_banner_subtitle) + " ($missing)"
        }
    }

    /**
     * Open an event in the device's native calendar app.
     * Uses the simple, trust-the-system approach proven to work in legacy versions.
     */
    private fun openEventInCalendar(event: EventEntity) {
        val externalEventIdLong = event.externalId.substringBefore("_").toLongOrNull()

        try {
            if (externalEventIdLong != null) {
                val uri = ContentUris.withAppendedId(
                    CalendarContract.Events.CONTENT_URI,
                    externalEventIdLong
                )
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                Log.d(TAG, "openEventInCalendar: launched ACTION_VIEW for event $externalEventIdLong")
                return
            }
        } catch (ex: Exception) {
            Log.w(TAG, "openEventInCalendar: ACTION_VIEW failed", ex)
        }

        // Fallback: open Google Calendar app's launcher
        try {
            val launcher = packageManager.getLaunchIntentForPackage("com.google.android.calendar")
            if (launcher != null) {
                launcher.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(launcher)
                return
            }
        } catch (ex: Exception) {
            Log.w(TAG, "openEventInCalendar: launcher fallback failed", ex)
        }

        Toast.makeText(this, "تعذّر فتح تطبيق التقويم", Toast.LENGTH_SHORT).show()
    }

    /**
     * Open calendar app at today's date.
     *
     * Behavior depends on FeatureFlags.isArchiveButtonEnabled:
     *   - OFF (default, Build #42 behavior): time URI → system chooser (Google/Samsung/Outlook)
     *   - ON: open CalendarArchive directly. If unavailable, fall back to default behavior.
     */
    private fun openSamsungCalendar() {
        // Check feature flag — if ON, try CalendarArchive first
        if (FeatureFlags.isArchiveButtonEnabled(this)) {
            if (tryOpenCalendarArchive()) {
                Log.d(TAG, "openSamsungCalendar: opened CalendarArchive (flag ON)")
                return
            }
            Log.d(TAG, "openSamsungCalendar: CalendarArchive unavailable, falling back to default")
            // Fall through to default behavior
        }

        // Default behavior (Build #42)
        val now = System.currentTimeMillis()
        val timeUri = Uri.parse("content://com.android.calendar/time/$now")

        try {
            val intent = Intent(Intent.ACTION_VIEW, timeUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.d(TAG, "openSamsungCalendar: launched ACTION_VIEW on time URI")
            return
        } catch (ex: Exception) {
            Log.w(TAG, "openSamsungCalendar: time URI failed", ex)
        }

        // Fallback: any launchable calendar app (try Samsung first, then Google)
        val candidates = listOf(
            "com.samsung.android.calendar",
            "com.google.android.calendar"
        )
        for (pkg in candidates) {
            try {
                val launcher = packageManager.getLaunchIntentForPackage(pkg)
                if (launcher != null) {
                    launcher.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(launcher)
                    Log.d(TAG, "openSamsungCalendar: launched $pkg via launcher")
                    return
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Launcher fallback for $pkg failed", ex)
            }
        }

        Toast.makeText(this, "تعذّر فتح تطبيق التقويم", Toast.LENGTH_SHORT).show()
    }

    /**
     * Try to launch CalendarArchive app.
     * @return true if launched successfully, false if app is missing/disabled or launch failed.
     */
    private fun tryOpenCalendarArchive(): Boolean {
        val archivePackage = "com.alaimtiaz.calendararchive"
        return try {
            val launcher = packageManager.getLaunchIntentForPackage(archivePackage)
            if (launcher != null) {
                launcher.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(launcher)
                true
            } else {
                Log.d(TAG, "tryOpenCalendarArchive: package $archivePackage not found")
                false
            }
        } catch (ex: Exception) {
            Log.w(TAG, "tryOpenCalendarArchive: launch failed", ex)
            false
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
