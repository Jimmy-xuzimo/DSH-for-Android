package com.dshmobile.shell

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer

/** Configure window creation, rather than pinning an Activity to one Recomposer.
 * AndroidX can replace a detached window on resize and owns lifecycle cleanup.
 * No Activity is retained; preferences are cached by Android's SharedPreferences.
 * Instrumentation may supply its own official test recomposer and frame clock.
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class DshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val preferences=getSharedPreferences("appearance",MODE_PRIVATE)
        val scale=object : MotionDurationScale {
            override val scaleFactor: Float get() = if(preferences.getBoolean("animations",true)) Settings.Global.getFloat(contentResolver,Settings.Global.ANIMATOR_DURATION_SCALE,1f) else 0f
        }
        WindowRecomposerPolicy.setFactory(WindowRecomposerFactory { root ->
            root.createLifecycleAwareWindowRecomposer(coroutineContext=scale)
        })
    }
}
