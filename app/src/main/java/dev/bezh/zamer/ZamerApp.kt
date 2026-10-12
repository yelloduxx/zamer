package dev.bezh.zamer

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dev.bezh.zamer.ble.RangefinderRepository
import dev.bezh.zamer.ble.RangefinderScanner
import dev.bezh.zamer.data.AppStore
import dev.bezh.zamer.data.PhotoStore
import dev.bezh.zamer.domain.Reading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

enum class ReadingTarget { MEASURE, SKETCH }

class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = AppStore(app)
    val photos = PhotoStore(app)
    val scanner = RangefinderScanner(app)
    val repository = RangefinderRepository(app, store, scanner, scope)

    val readingTarget = MutableStateFlow(ReadingTarget.MEASURE)

    private val routed = repository.readings.map { readingTarget.value to it }.shareIn(scope, SharingStarted.Eagerly)

    fun readings(to: ReadingTarget): Flow<Reading> = routed.filter { it.first == to }.map { it.second }

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
