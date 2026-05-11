package com.alaimtiaz.calendaralarm

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alaimtiaz.calendaralarm.alarm.AlarmScheduler
import com.alaimtiaz.calendaralarm.data.AppDatabase
import com.alaimtiaz.calendaralarm.data.EventEntity
import com.alaimtiaz.calendaralarm.databinding.ActivityAlarmOverlayBinding
import com.alaimtiaz.calendaralarm.util.DateUtils
import com.alaimtiaz.calendaralarm.util.PreferencesHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmOverlayActivity : AppCompatActivity() {

    // Original binding (used only when v1 layout is active)
    private var binding: ActivityAlarmOverlayBinding? = null

    // v2 views (used only when v2 layout is active)
    private var v2TvTitle: TextView? = null
    private var v2TvLocation: TextView? = null
    private var v2TvEventTime: TextView? = null
    private var v2BtnSnooze5: LinearLayout? = null
    private var v2BtnSnooze15: LinearLayout? = null
    private var v2BtnSnooze30: LinearLayout? = null
    private var v2BtnSnooze60: LinearLayout? = null
    private var v2BtnSnoozeMore: LinearLayout? = null
    private var v2BtnEdit: MaterialButton? = null
    private var v2BtnDismiss: MaterialButton? = null

    private lateinit var prefs: PreferencesHelper
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var event: EventEntity? = null
    private var eventId: Long = -1L
    private var externalIdHint: String? = null
    private var clockTickRunnable: Runnable? = null
    private var phase2Runnable: Runnable? = null
    private val clockFormat = SimpleDateFormat("h:mm:ss", Locale("ar"))
    private val ampmFormat = SimpleDateFormat("a", Locale("ar"))
    private var useNewLayout: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPhase1WindowFlags()

        prefs = PreferencesHelper(applicationContext)

        // Decide which layout to use based on feature flag
        useNewLayout = FeatureFlags.isNewAlarmOverlayEnabled(this)
        Log.d(TAG, "onCreate: useNewLayout=$useNewLayout")

        if (useNewLayout) {
            setContentView(R.layout.activity_alarm_overlay_v2)
            bindV2Views()
        } else {
            binding = ActivityAlarmOverlayBinding.inflate(layoutInflater)
            setContentView(binding!!.root)
        }

        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        externalIdHint = intent.getStringExtra(EXTRA_EXTERNAL_ID)

        Log.d(TAG, "onCreate: eventId=$eventId externalIdHint=$externalIdHint")

        if (eventId == -1L && externalIdHint.isNullOrBlank()) {
            Log.w(TAG, "Started without event id or externalId — finishing")
            finish()
            return
        }

        acquireWakeLockPhase1()
        if (!useNewLayout) {
            scheduleClockTicks()
        }
        loadEvent()
        playAlarmSoundOnce()
        startShortVibration()
        wireButtons()
        scheduleEnterPhase2()
    }

    private fun bindV2Views() {
        v2TvTitle = findViewById(R.id.tvTitle)
        v2TvLocation = findViewById(R.id.tvLocation)
        v2TvEventTime = findViewById(R.id.tvEventTime)
        v2BtnSnooze5 = findViewById(R.id.btnSnooze5)
        v2BtnSnooze15 = findViewById(R.id.btnSnooze15)
        v2BtnSnooze30 = findViewById(R.id.btnSnooze30)
        v2BtnSnooze60 = findViewById(R.id.btnSnooze60)
        v2BtnSnoozeMore = findViewById(R.id.btnSnoozeMore)
        v2BtnEdit = findViewById(R.id.btnEdit)
        v2BtnDismiss = findViewById(R.id.btnDismiss)
    }

    private fun applyPhase1WindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            try {
                val km = getSystemService(KeyguardManager::class.java)
                km?.requestDismissKeyguard(this, null)
            } catch (e: Exception) {
                Log.w(TAG, "requestDismissKeyguard failed", e)
            }
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun acquireWakeLockPhase1() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "CalendarAlarm:OverlayWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire((prefs.phase1DurationSeconds * 1000L) + 2_000L)
        }
    }

    private fun scheduleEnterPhase2() {
        phase2Runnable = Runnable { enterPhase2() }
        handler.postDelayed(phase2Runnable!!, prefs.phase1DurationSeconds * 1000L)
    }

    private fun enterPhase2() {
        Log.d(TAG, "Entering Phase 2 — releasing screen control to system")
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun loadEvent() {
        lifecycleScope.launch {
            val e = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(applicationContext).eventDao()
                var found: EventEntity? = null

                if (eventId > 0L) {
                    found = dao.getById(eventId)
                    if (found != null) {
                        Log.d(TAG, "Event loaded by id=$eventId")
                    } else {
                        Log.w(TAG, "Event id=$eventId not in DB (likely re-inserted by sync)")
                    }
                }

                if (found == null && !externalIdHint.isNullOrBlank()) {
                    found = dao.getByExternalIdAny(externalIdHint!!)
                    if (found != null) {
                        Log.d(TAG, "Event recovered via externalId=$externalIdHint id=${found.id}")
                        eventId = found.id
                    } else {
                        Log.w(TAG, "Event also not found by externalId=$externalIdHint")
                    }
                }
                found
            }
            event = e
            renderEvent(e)
            showPersistentNotification(e)
        }
    }

    private fun renderEvent(e: EventEntity?) {
        if (useNewLayout) {
            renderEventV2(e)
        } else {
            renderEventV1(e)
        }
    }

    private fun renderEventV1(e: EventEntity?) {
        val b = binding ?: return
        if (e == null) {
            b.tvTitle.text = "(تعذّر تحميل الحدث)"
            b.tvSourceName.text = ""
            b.tvDescription.visibility = View.GONE
            b.tvLocation.visibility = View.GONE
            return
        }
        b.tvTitle.text = e.title

        val sourceLabel = when (e.source) {
            EventEntity.SOURCE_GOOGLE -> "📅 Google"
            EventEntity.SOURCE_SAMSUNG -> "📱 Samsung"
            EventEntity.SOURCE_OUTLOOK -> "📧 Outlook"
            else -> "📋 ${e.source}"
        }
        b.tvSourceName.text = "$sourceLabel • ${e.accountName}"
        b.tvEventTime.text = DateUtils.formatFull(this, e.startTime)

        if (!e.description.isNullOrBlank()) {
            b.tvDescription.visibility = View.VISIBLE
            b.tvDescription.text = e.description
        } else b.tvDescription.visibility = View.GONE

        if (!e.location.isNullOrBlank()) {
            b.tvLocation.visibility = View.VISIBLE
            b.tvLocation.text = "📍 ${e.location}"
        } else b.tvLocation.visibility = View.GONE
    }

    private fun renderEventV2(e: EventEntity?) {
        if (e == null) {
            v2TvTitle?.text = "(تعذّر تحميل الحدث)"
            v2TvEventTime?.text = ""
            v2TvLocation?.visibility = View.GONE
            return
        }

        v2TvTitle?.text = e.title
        v2TvEventTime?.text = DateUtils.formatFull(this, e.startTime)

        if (!e.location.isNullOrBlank()) {
            v2TvLocation?.visibility = View.VISIBLE
            v2TvLocation?.text = "📍 ${e.location}"
        } else {
            v2TvLocation?.visibility = View.GONE
        }
    }

    private fun wireButtons() {
        if (useNewLayout) {
            wireButtonsV2()
        } else {
            wireButtonsV1()
        }
    }

    private fun wireButtonsV1() {
        val b = binding ?: return
        b.btnDismiss.setOnClickListener { dismissAlarm() }
        b.btnEdit.setOnClickListener { openInCalendar() }
        b.btnSnooze5.setOnClickListener { snooze(5L) }
        b.btnSnooze10.setOnClickListener { snooze(10L) }
        b.btnSnooze30.setOnClickListener { snooze(30L) }
        b.btnSnoozeMore.setOnClickListener { showSnoozeMoreDialog() }
    }

    private fun wireButtonsV2() {
        v2BtnDismiss?.setOnClickListener { dismissAlarm() }
        v2BtnEdit?.setOnClickListener { openInCalendar() }
        v2BtnSnooze5?.setOnClickListener { snooze(5L) }
        v2BtnSnooze15?.setOnClickListener { snooze(15L) }
        v2BtnSnooze30?.setOnClickListener { snooze(30L) }
        v2BtnSnooze60?.setOnClickListener { snooze(60L) }
        v2BtnSnoozeMore?.setOnClickListener { showSnoozeMoreDialog() }
    }

    private fun showSnoozeMoreDialog() {
        if (useNewLayout) {
            // v2: 8 options
            val labels = arrayOf(
                getString(R.string.alarm_snooze_2h),
                getString(R.string.alarm_snooze_4h),
                getString(R.string.alarm_snooze_8h),
                "12 ساعة",
                getString(R.string.alarm_snooze_1d),
                "يومان",
                "3 أيام",
                getString(R.string.alarm_snooze_1w)
            )
            val minutes = longArrayOf(120L, 240L, 480L, 720L, 1440L, 2880L, 4320L, 10080L)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.alarm_snooze_more)
                .setItems(labels) { _, which -> snooze(minutes[which]) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        } else {
            // v1: original 5 options
            val labels = arrayOf(
                getString(R.string.alarm_snooze_2h),
                getString(R.string.alarm_snooze_4h),
                getString(R.string.alarm_snooze_8h),
                getString(R.string.alarm_snooze_1d),
                getString(R.string.alarm_snooze_1w)
            )
            val minutes = longArrayOf(120L, 240L, 480L, 1440L, 10080L)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.alarm_snooze_more)
                .setItems(labels) { _, which -> snooze(minutes[which]) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun snooze(minutes: Long) {
        val e = event ?: run {
            Toast.makeText(this, "تعذّر التأجيل — بيانات الحدث غير متوفرة", Toast.LENGTH_SHORT).show()
            return
        }
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AlarmScheduler(applicationContext).scheduleEvent(e, triggerOverride = triggerAt)
            }
            dismissPersistentNotification()
            stopAlarmEffects()
            finishAndRemoveTask()
        }
    }

    private fun dismissAlarm() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (eventId > 0L) {
                    AlarmScheduler(applicationContext).cancelEvent(eventId, externalIdHint)
                }
            }
            dismissPersistentNotification()
            stopAlarmEffects()
            finishAndRemoveTask()
        }
    }

    private fun openInCalendar() {
        val e = event
        val externalEventIdLong = e?.externalId?.substringBefore("_")?.toLongOrNull()

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
                Log.d(TAG, "openInCalendar: launched ACTION_VIEW for event $externalEventIdLong")
                stopAlarmEffects()
                finishAndRemoveTask()
                return
            }
        } catch (ex: Exception) {
            Log.w(TAG, "openInCalendar: ACTION_VIEW failed", ex)
        }

        try {
            val launcher = packageManager.getLaunchIntentForPackage("com.google.android.calendar")
            if (launcher != null) {
                launcher.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(launcher)
                Log.d(TAG, "openInCalendar: launched Google Calendar via package launcher")
                stopAlarmEffects()
                finishAndRemoveTask()
                return
            }
        } catch (ex: Exception) {
            Log.w(TAG, "openInCalendar: launcher fallback failed", ex)
        }

        Toast.makeText(this, "تعذّر فتح تطبيق التقويم", Toast.LENGTH_SHORT).show()
    }

    private fun showPersistentNotification(e: EventEntity?) {
        val notificationId = if (eventId > 0L) eventId.toInt() else NOTIFICATION_ID_FALLBACK
        val title = e?.title?.takeIf { it.isNotBlank() } ?: "منبه نشط"

        val openIntent = Intent(this, AlarmOverlayActivity::class.java).apply {
            putExtra(EXTRA_EVENT_ID, eventId)
            externalIdHint?.let { putExtra(EXTRA_EXTERNAL_ID, it) }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val openPi = PendingIntent.getActivity(
            this,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(
            this,
            CalendarAlarmApplication.CHANNEL_MISSED_ALARMS
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(getString(R.string.active_alarm_text))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
            .setNumber(1)
            .build()

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, notification)
            Log.d(TAG, "Persistent notification posted for eventId=$eventId")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to post persistent notification", ex)
        }
    }

    private fun dismissPersistentNotification() {
        val notificationId = if (eventId > 0L) eventId.toInt() else NOTIFICATION_ID_FALLBACK
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationId)
            Log.d(TAG, "Persistent notification dismissed for eventId=$eventId")
        } catch (ex: Exception) {
            Log.w(TAG, "Failed to dismiss persistent notification", ex)
        }
    }

    private fun playAlarmSoundOnce() {
        try {
            val uri: Uri = prefs.defaultRingtoneUri
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmOverlayActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = false
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    Log.d(TAG, "Alarm sound finished playing")
                    try { it.release() } catch (_: Exception) {}
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm sound", e)
        }
    }

    private fun startShortVibration() {
        if (!prefs.vibrationEnabled) return
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(700L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(700L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    private fun stopAlarmEffects() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        clockTickRunnable?.let { handler.removeCallbacks(it) }
        phase2Runnable?.let { handler.removeCallbacks(it) }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun scheduleClockTicks() {
        // v1 only — v2 has no live clock
        val b = binding ?: return
        clockTickRunnable = object : Runnable {
            override fun run() {
                val now = Date()
                b.tvClock.text = clockFormat.format(now)
                b.tvClockAmpm.text = ampmFormat.format(now)
                handler.postDelayed(this, 1000L)
            }
        }
        handler.post(clockTickRunnable!!)
    }

    override fun onDestroy() {
        stopAlarmEffects()
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Disabled
    }

    companion object {
        private const val TAG = "AlarmOverlay"
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val EXTRA_EXTERNAL_ID = "extra_external_id"
        private const val NOTIFICATION_ID_FALLBACK = 99999
    }
}
