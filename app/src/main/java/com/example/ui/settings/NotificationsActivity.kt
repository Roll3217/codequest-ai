package com.example.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.CodeQuestApp
import com.example.databinding.ActivityNotificationsBinding
import com.example.databinding.ItemNotificationCardBinding
import com.example.model.AppNotificationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding
    private lateinit var notifAdapter: NotificationsAdapter
    private var sendTestAfterPermissionGrant = false

    private val requestNotifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val app = application as CodeQuestApp
        val userId = app.sessionPreferences.loggedInUserId
        updatePermissionBanner(app)
        if (isGranted) {
            lifecycleScope.launch {
                app.notificationService.flushUnpostedNotificationsToTray(applicationContext, userId)
                if (sendTestAfterPermissionGrant) {
                    sendTestAfterPermissionGrant = false
                    val sent = app.notificationService.sendTestDeviceNotification(applicationContext, userId)
                    Toast.makeText(
                        this@NotificationsActivity,
                        if (sent) "Notification sent to your device notification bar!" else "Unable to post notification.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } else {
            sendTestAfterPermissionGrant = false
            Toast.makeText(
                this,
                "Please enable notifications in App Settings to see device status bar alerts.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userId = app.sessionPreferences.loggedInUserId

        binding.btnNotifBack.setOnClickListener {
            finish()
        }

        binding.btnNotifEnableDevice.setOnClickListener {
            sendTestAfterPermissionGrant = false
            requestOrOpenDeviceNotificationSettings(app)
        }

        binding.btnNotifTestDevice.setOnClickListener {
            if (!app.notificationService.areDeviceNotificationsPermitted(this)) {
                sendTestAfterPermissionGrant = true
                requestOrOpenDeviceNotificationSettings(app)
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val sent = app.notificationService.sendTestDeviceNotification(applicationContext, userId)
                Toast.makeText(
                    this@NotificationsActivity,
                    if (sent) "Notification sent to your device notification bar!" else "Please enable notifications in device Settings.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnNotifMarkAllRead.setOnClickListener {
            if (userId > 0L) {
                lifecycleScope.launch {
                    app.notificationService.markAllAsRead(userId)
                }
            }
        }

        notifAdapter = NotificationsAdapter { notif ->
            if (!notif.isRead) {
                lifecycleScope.launch {
                    app.notificationService.markAsRead(notif.id)
                }
            }
        }

        binding.rvNotificationsList.layoutManager = LinearLayoutManager(this)
        binding.rvNotificationsList.adapter = notifAdapter

        updatePermissionBanner(app)

        if (userId > 0L) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    app.notificationService.observeNotifications(userId).collect { list ->
                        notifAdapter.submitList(list)
                        val unreadCount = list.count { !it.isRead }
                        binding.tvNotifSubtitle.text =
                            "$unreadCount Unread • ${list.size} Total Alerts"
                        binding.layoutNotifEmpty.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                        binding.rvNotificationsList.visibility =
                            if (list.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as CodeQuestApp
        updatePermissionBanner(app)
        val userId = app.sessionPreferences.loggedInUserId
        if (userId > 0L && app.notificationService.areDeviceNotificationsPermitted(this)) {
            lifecycleScope.launch(Dispatchers.IO) {
                app.notificationService.flushUnpostedNotificationsToTray(applicationContext, userId)
            }
        }
    }

    private fun updatePermissionBanner(app: CodeQuestApp) {
        val permitted = app.notificationService.areDeviceNotificationsPermitted(this)
        binding.cardNotifPermissionBanner.visibility = if (permitted) View.GONE else View.VISIBLE
    }

    private fun requestOrOpenDeviceNotificationSettings(app: CodeQuestApp) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                val shouldAsk = !app.sessionPreferences.hasRequestedNotificationPermission ||
                    ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)
                if (shouldAsk) {
                    app.sessionPreferences.hasRequestedNotificationPermission = true
                    requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
            }
        }
        // Fallback to OS App Notification Settings if permanently denied or disabled in system settings
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(fallback)
        }
    }

    private class NotificationsAdapter(
        private val onItemClicked: (AppNotificationEntity) -> Unit
    ) : RecyclerView.Adapter<NotificationsAdapter.NotifViewHolder>() {

        private var items: List<AppNotificationEntity> = emptyList()

        fun submitList(newItems: List<AppNotificationEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class NotifViewHolder(val itemBinding: ItemNotificationCardBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotifViewHolder {
            val b = ItemNotificationCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return NotifViewHolder(b)
        }

        override fun onBindViewHolder(holder: NotifViewHolder, position: Int) {
            val item = items[position]
            val dateFmt = SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.US)
            val ctx = holder.itemView.context

            holder.itemBinding.tvNotifCategory.text = when (item.category) {
                "ROAST" -> "🔥 SAVAGE ROAST"
                "AVATAR" -> "✨ NEW AVATAR"
                "DAILY_GOAL" -> "🎯 GOAL ALERT"
                "DAILY_CHALLENGE" -> "⚡ CHALLENGE"
                "LEVEL_UP" -> "🚀 LEVEL UP"
                "ACHIEVEMENT" -> "🏆 ACHIEVEMENT"
                "BADGE" -> "🎖️ BADGE"
                "STREAK" -> "🔥 STREAK"
                else -> item.category
            }

            when (item.category) {
                "ROAST" -> {
                    holder.itemBinding.tvNotifCategory.setBackgroundResource(com.example.R.drawable.bg_pill_roast)
                    holder.itemBinding.tvNotifCategory.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, com.example.R.color.cq_error))
                }
                "AVATAR" -> {
                    holder.itemBinding.tvNotifCategory.setBackgroundResource(com.example.R.drawable.bg_pill_cyan)
                    holder.itemBinding.tvNotifCategory.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, com.example.R.color.cq_on_secondary_container))
                }
                "DAILY_GOAL", "DAILY_CHALLENGE" -> {
                    holder.itemBinding.tvNotifCategory.setBackgroundResource(com.example.R.drawable.bg_pill_gold)
                    holder.itemBinding.tvNotifCategory.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, com.example.R.color.cq_warning))
                }
                else -> {
                    holder.itemBinding.tvNotifCategory.setBackgroundResource(com.example.R.drawable.bg_pill_purple)
                    holder.itemBinding.tvNotifCategory.setTextColor(androidx.core.content.ContextCompat.getColor(ctx, com.example.R.color.cq_on_primary_container))
                }
            }

            holder.itemBinding.tvNotifTitle.text = item.title
            holder.itemBinding.tvNotifMessage.text = item.message
            holder.itemBinding.tvNotifTime.text = dateFmt.format(Date(item.createdAt))
            holder.itemBinding.tvNotifUnreadDot.visibility =
                if (item.isRead) View.GONE else View.VISIBLE
            holder.itemBinding.cardNotificationItem.alpha = if (item.isRead) 0.78f else 1.0f

            holder.itemBinding.cardNotificationItem.setOnClickListener {
                onItemClicked(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
