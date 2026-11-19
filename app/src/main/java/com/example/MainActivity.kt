package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.ui.ai.AiMentorFragment
import com.example.ui.auth.LoginActivity
import com.example.ui.home.HomeFragment
import com.example.ui.practice.PracticeFragment
import com.example.ui.profile.ProfileFragment
import com.example.ui.progress.ProgressFragment
import com.example.utils.WindowInsetsHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var pendingTopicFilter: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val app = application as CodeQuestApp
            val userId = app.sessionPreferences.loggedInUserId
            if (userId > 0L) {
                lifecycleScope.launch(Dispatchers.IO) {
                    app.notificationService.flushUnpostedNotificationsToTray(applicationContext, userId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)

        // If user session is not active, route to LoginActivity
        if (!app.sessionPreferences.isUserLoggedIn()) {
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            val existingUser = kotlinx.coroutines.withContext(Dispatchers.IO) {
                app.authRepository.getLoggedInUser()
            }
            if (existingUser == null) {
                app.sessionPreferences.clearSession()
                val intent = Intent(this@MainActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
                return@launch
            }
            if (savedInstanceState == null && app.supabaseRepository.isConfigured()) {
                val activeUserId = existingUser.id
                if (activeUserId > 0L) {
                    launch(Dispatchers.IO) {
                        try {
                            app.supabaseRepository.syncUserData(activeUserId, preferRemote = false)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            requestNotificationPermissionIfNeeded(app)
        }

        WindowInsetsHelper.applyMainActivityInsets(
            activity = this,
            rootView = binding.mainRoot,
            topContentContainer = binding.fragmentContainer,
            bottomNavigationView = binding.bottomNavigation
        )

        binding.bottomNavigation.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment(), TAG_HOME)
                    true
                }
                R.id.nav_practice -> {
                    val topicFilter = pendingTopicFilter
                    pendingTopicFilter = null
                    loadFragment(PracticeFragment.newInstance(topicFilter), TAG_PRACTICE, forceNew = topicFilter != null)
                    true
                }
                R.id.nav_ai -> {
                    loadFragment(AiMentorFragment(), TAG_AI)
                    true
                }
                R.id.nav_progress -> {
                    loadFragment(ProgressFragment(), TAG_PROGRESS)
                    true
                }
                R.id.nav_profile -> {
                    loadFragment(ProfileFragment(), TAG_PROFILE)
                    true
                }
                else -> false
            }
        }

        if (savedInstanceState == null) {
            if (!handleNavigationIntent(intent)) {
                loadFragment(HomeFragment(), TAG_HOME)
                binding.bottomNavigation.selectedItemId = R.id.nav_home
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.bottomNavigation.selectedItemId != R.id.nav_home) {
                    binding.bottomNavigation.selectedItemId = R.id.nav_home
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val topicFilter = intent.getStringExtra(EXTRA_OPEN_TOPIC_FILTER)
        if (!topicFilter.isNullOrBlank()) {
            intent.removeExtra(EXTRA_OPEN_TOPIC_FILTER)
            pendingTopicFilter = topicFilter
            binding.bottomNavigation.selectedItemId = R.id.nav_practice
            return true
        }
        val tabId = intent.getIntExtra(EXTRA_OPEN_TAB_ID, 0)
        if (tabId != 0) {
            intent.removeExtra(EXTRA_OPEN_TAB_ID)
            binding.bottomNavigation.selectedItemId = tabId
            return true
        }
        val openNotifications = intent.getBooleanExtra(EXTRA_OPEN_NOTIFICATIONS, false)
        if (openNotifications) {
            intent.removeExtra(EXTRA_OPEN_NOTIFICATIONS)
            loadFragment(HomeFragment(), TAG_HOME)
            binding.bottomNavigation.selectedItemId = R.id.nav_home
            startActivity(Intent(this, com.example.ui.settings.NotificationsActivity::class.java))
            return true
        }
        return false
    }

    fun navigateToTab(@IdRes tabId: Int) {
        binding.bottomNavigation.selectedItemId = tabId
    }

    private fun requestNotificationPermissionIfNeeded(app: CodeQuestApp) {
        val userId = app.sessionPreferences.loggedInUserId
        if (app.notificationService.areDeviceNotificationsPermitted(this)) {
            if (userId > 0L) {
                lifecycleScope.launch(Dispatchers.IO) {
                    app.notificationService.flushUnpostedNotificationsToTray(applicationContext, userId)
                }
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !app.sessionPreferences.hasRequestedNotificationPermission
        ) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                app.sessionPreferences.hasRequestedNotificationPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadFragment(fragment: Fragment, tag: String, forceNew: Boolean = false) {
        val target = if (forceNew) fragment else (supportFragmentManager.findFragmentByTag(tag) ?: fragment)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.fragment_container, target, tag)
            .commit()
    }

    companion object {
        const val EXTRA_OPEN_TOPIC_FILTER = "extra_open_topic_filter"
        const val EXTRA_OPEN_TAB_ID = "extra_open_tab_id"
        const val EXTRA_OPEN_NOTIFICATIONS = "extra_open_notifications"

        private const val TAG_HOME = "tag_home"
        private const val TAG_PRACTICE = "tag_practice"
        private const val TAG_AI = "tag_ai"
        private const val TAG_PROGRESS = "tag_progress"
        private const val TAG_PROFILE = "tag_profile"
    }
}
