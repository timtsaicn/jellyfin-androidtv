package org.jellyfin.androidtv.ui.browsing

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.ui.validateAuthentication
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.ScreensaverViewModel
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.screensaver.InAppScreensaver
import org.jellyfin.androidtv.util.applyTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : FragmentActivity(R.layout.activity_main) {
	companion object {
		private const val FRAGMENT_TAG_CONTENT = "content"
	}

	private val backgroundService by inject<BackgroundService>()
	private val navigationRepository by inject<NavigationRepository>()
	private val screensaverViewModel by viewModel<ScreensaverViewModel>()

	private val backPressedCallback = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() {
			if (navigationRepository.canGoBack) navigationRepository.goBack()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()

		super.onCreate(savedInstanceState)

		if (!validateAuthentication()) return

		backgroundService.attach(this)
		onBackPressedDispatcher.addCallback(this, backPressedCallback)

		supportFragmentManager.addOnBackStackChangedListener {
			if (supportFragmentManager.backStackEntryCount == 0)
				navigationRepository.reset()
		}

		if (savedInstanceState == null && navigationRepository.canGoBack) navigationRepository.reset()

		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				navigationRepository.currentAction.collect { action ->
					handleNavigationAction(action)
					backPressedCallback.isEnabled = navigationRepository.canGoBack
					screensaverViewModel.notifyInteraction(false)
				}
			}
		}

		findViewById<ComposeView>(R.id.screensaver).setContent {
			InAppScreensaver()
		}
	}

	override fun onResume() {
		super.onResume()

		if (!validateAuthentication()) return

		applyTheme()

		screensaverViewModel.activityPaused = false

		if (screensaverViewModel.enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
		else  window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
	}

	override fun onPause() {
		super.onPause()

		screensaverViewModel.activityPaused = true
	}

	private fun handleNavigationAction(action: NavigationAction) = when (action) {
		is NavigationAction.NavigateFragment -> supportFragmentManager.commit {
			val destination = action.destination
			val currentFragment = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT)
			val isSameFragment = currentFragment != null &&
				destination.fragment.isInstance(currentFragment) &&
				currentFragment.arguments == destination.arguments

			if (!isSameFragment) {
				setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)

				if (currentFragment != null) remove(currentFragment)
				add(R.id.content_view, destination.fragment.java, destination.arguments, FRAGMENT_TAG_CONTENT)
			}

			if (action.addToBackStack) addToBackStack(null)
		}

		is NavigationAction.NavigateActivity -> {
			val destination = action.destination
			val intent = Intent(this@MainActivity, destination.activity.java)
			intent.putExtras(destination.extras)
			startActivity(intent)
			action.onOpened()
		}

		NavigationAction.GoBack -> supportFragmentManager.popBackStack()

		NavigationAction.Nothing -> Unit
	}

	// Forward key events to fragments
	private fun Fragment.onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
		var result = childFragmentManager.fragments.any { it.onKeyEvent(keyCode, event) }
		if (!result && this is View.OnKeyListener) result = onKey(currentFocus, keyCode, event)
		return result
	}

	private fun onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
		// Ignore the key event that closes the screensaver
		if (screensaverViewModel.visible.value) {
			screensaverViewModel.notifyInteraction(canCancel = event?.action == KeyEvent.ACTION_UP)
			return true
		}

		return supportFragmentManager.fragments
			.any { it.onKeyEvent(keyCode, event) }
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyDown(keyCode, event)

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)

	override fun onUserInteraction() {
		super.onUserInteraction()

		screensaverViewModel.notifyInteraction(false)
	}
}
