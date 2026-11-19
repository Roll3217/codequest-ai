package com.example.utils

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Centralized WindowInsets helper for Android 15+ (SDK 35/36) edge-to-edge compliance.
 *
 * Ensures:
 * 1. Top headers/titles/content always start cleanly below the Android status bar & display cutout.
 * 2. Bottom navigation and bottom content always sit above the system navigation/gesture area.
 * 3. Initial view padding is cached per View instance in a WeakHashMap so insets are NEVER
 *    double-applied across fragment transactions, lifecycle restarts, or repeated inset dispatches.
 */
object WindowInsetsHelper {

    private data class InitialPadding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private val initialPaddingCache = WeakHashMap<View, InitialPadding>()

    private fun getOrCaptureInitialPadding(view: View): InitialPadding {
        return initialPaddingCache.getOrPut(view) {
            InitialPadding(
                left = view.paddingLeft,
                top = view.paddingTop,
                right = view.paddingRight,
                bottom = view.paddingBottom
            )
        }
    }

    /**
     * Applies edge-to-edge window configuration and attaches a single non-accumulating
     * WindowInsets listener to MainActivity's root container:
     * - Top & horizontal system bar / cutout insets are applied to [topContentContainer] (FragmentContainerView).
     * - Bottom & horizontal system bar insets are applied to [bottomNavigationView] (BottomNavigationView).
     * - Consumes insets so child fragments and BottomNavigationView's internal listener never double-pad.
     */
    fun applyMainActivityInsets(
        activity: Activity,
        rootView: View,
        topContentContainer: View,
        bottomNavigationView: View
    ) {
        configureEdgeToEdgeWindow(activity)

        val topInitial = getOrCaptureInitialPadding(topContentContainer)
        val bottomNavInitial = getOrCaptureInitialPadding(bottomNavigationView)

        val updateLayoutInsets = { windowInsets: WindowInsetsCompat ->
            val systemBarInsets: Insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val isImeOpen = windowInsets.isVisible(WindowInsetsCompat.Type.ime()) || imeInsets.bottom > systemBarInsets.bottom
            val effectiveImeBottom = if (isImeOpen) max(imeInsets.bottom, 0) else 0

            val divider = rootView.findViewById<View?>(com.example.R.id.divider_bottom_nav)

            if (isImeOpen && effectiveImeBottom > 0) {
                // Soft keyboard is open: hide bottom nav to dedicate screen space and lift container above keyboard
                bottomNavigationView.visibility = View.GONE
                divider?.visibility = View.GONE

                topContentContainer.updatePadding(
                    left = topInitial.left + systemBarInsets.left,
                    top = topInitial.top + systemBarInsets.top,
                    right = topInitial.right + systemBarInsets.right,
                    bottom = topInitial.bottom + effectiveImeBottom
                )
            } else {
                // Soft keyboard is closed: restore bottom navigation and system bar insets
                bottomNavigationView.visibility = View.VISIBLE
                divider?.visibility = View.VISIBLE

                topContentContainer.updatePadding(
                    left = topInitial.left + systemBarInsets.left,
                    top = topInitial.top + systemBarInsets.top,
                    right = topInitial.right + systemBarInsets.right,
                    bottom = topInitial.bottom
                )

                bottomNavigationView.updatePadding(
                    left = bottomNavInitial.left + systemBarInsets.left,
                    top = bottomNavInitial.top,
                    right = bottomNavInitial.right + systemBarInsets.right,
                    bottom = bottomNavInitial.bottom + systemBarInsets.bottom
                )
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, windowInsets ->
            updateLayoutInsets(windowInsets)
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            rootView,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    updateLayoutInsets(insets)
                    return insets
                }
            }
        )

        if (rootView.isAttachedToWindow) {
            ViewCompat.requestApplyInsets(rootView)
        } else {
            rootView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    ViewCompat.requestApplyInsets(v)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    /**
     * Applies edge-to-edge status bar, cutout, navigation bar, and IME insets to any standard Activity's
     * content root view without accumulating padding across multiple dispatches.
     */
    fun applyStandardActivityInsets(activity: Activity) {
        configureEdgeToEdgeWindow(activity)

        val contentParent = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val contentRoot = contentParent.getChildAt(0) ?: return

        val initial = getOrCaptureInitialPadding(contentRoot)

        val applyStandard = { view: View, windowInsets: WindowInsetsCompat ->
            val systemBars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime()) || ime.bottom > systemBars.bottom
            val effectiveIme = if (imeVisible) max(ime.bottom, 0) else 0

            view.updatePadding(
                left = initial.left + systemBars.left,
                top = initial.top + systemBars.top,
                right = initial.right + systemBars.right,
                bottom = initial.bottom + max(systemBars.bottom, effectiveIme)
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { view, windowInsets ->
            applyStandard(view, windowInsets)
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            contentRoot,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyStandard(contentRoot, insets)
                    return insets
                }
            }
        )

        if (contentRoot.isAttachedToWindow) {
            ViewCompat.requestApplyInsets(contentRoot)
        } else {
            contentRoot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    ViewCompat.requestApplyInsets(v)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    /**
     * Enables edge-to-edge decor fitting and synchronizes status/navigation bar icon contrast
     * with the active Dark or Light theme.
     */
    fun configureEdgeToEdgeWindow(activity: Activity) {
        val window = activity.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val nightModeFlags =
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isDarkTheme = nightModeFlags == Configuration.UI_MODE_NIGHT_YES

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDarkTheme
        controller.isAppearanceLightNavigationBars = !isDarkTheme
    }
}
