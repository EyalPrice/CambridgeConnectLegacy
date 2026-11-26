package io.github.eyalprice.ccl

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One shared stereo connection for the screens, the lock-screen service and the widget. */
class CclApp : Application() {

    val stereo: StereoViewModel by lazy { StereoViewModel(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        scope.launch {
            stereo.state
                .map { StereoWidget.Snapshot.of(it) }
                .distinctUntilChanged()
                .collect { StereoWidget.render(this@CclApp, it) }
        }
    }
}

val android.content.Context.stereo: StereoViewModel get() = (applicationContext as CclApp).stereo
