package dev.bezh.zamer

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.bezh.zamer.ble.RangefinderRepository
import dev.bezh.zamer.ble.RangefinderScanner
import dev.bezh.zamer.data.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = AppStore(app)
    val scanner = RangefinderScanner(app)
    val repository = RangefinderRepository(app, store, scanner, scope)

    init {
        scope.launch { repository.readings.collect { store.addReading(it) } }
    }
}

class ZamerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> container.repository.setForeground(true)
                    Lifecycle.Event.ON_STOP -> container.repository.setForeground(false)
                    else -> Unit
                }
            },
        )
    }
}
