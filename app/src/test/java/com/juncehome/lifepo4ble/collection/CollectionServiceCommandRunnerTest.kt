package com.juncehome.lifepo4ble.collection

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.DeviceStore
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionServiceCommandRunnerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * Device tab Connect with background collection on: the view model (Main) sends STOP and then saves the
     * remote address. DataStore runs that edit's transform back on Main and applies edits in order, so STOP's
     * own disable edit waits for Main. Blocking Main on STOP deadlocked (ANR in onStartCommand).
     */
    @Test
    fun stopFinishesWhileAMainThreadDataStoreEditIsPending() {
        val mainExecutor = Executors.newSingleThreadExecutor { task -> Thread(task, "test-main").apply { isDaemon = true } }
        val mainScope = CoroutineScope(SupervisorJob() + mainExecutor.asCoroutineDispatcher())
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = temporaryFolder.newFile("device.preferences_pb")
            val store = DeviceStore(PreferenceDataStoreFactory.create(scope = dataStoreScope) { file })
            runBlocking { store.enableAutonomousCollection(SNAPSHOT, associationId = 7) }
            val controller = RecordingController()
            val stopped = CompletableDeferred<Int>()
            val runner = CollectionServiceCommandRunner(
                scope = mainScope,
                controller = controller,
                router = CollectionServiceCommandRouter(controller, AutonomousCollectionDisabler(store, NoopGateway)),
                loadConfiguration = { store.collectionConfiguration.first() },
                stopService = { startId, _ -> stopped.complete(startId) },
            )
            val remoteSaved = CompletableDeferred<Unit>()

            mainScope.launch {
                // startService(STOP) reaches onStartCommand as a later main-thread message.
                mainExecutor.execute { runner.submit(CollectionServiceCommand(CollectionServiceAction.STOP), startId = 1) }
                store.setRemoteAddress(REMOTE_ADDRESS)
                remoteSaved.complete(Unit)
            }

            val finished = runBlocking {
                withTimeoutOrNull(DEADLOCK_TIMEOUT_MS) {
                    remoteSaved.await()
                    stopped.await()
                }
            }
            assertNotNull("STOP and the remote address save must both finish; the main thread deadlocked", finished)
            assertEquals(1, controller.userStops)
            runBlocking {
                assertNull(store.collectionConfiguration.first())
                assertEquals(REMOTE_ADDRESS, store.remoteAddress.first())
            }
        } finally {
            mainExecutor.shutdownNow()
            mainScope.cancel()
            dataStoreScope.cancel()
        }
    }

    @Test
    fun stopStopsTheControllerOnTheServiceThread() = runTest {
        val controller = RecordingController()
        val runner = runner(controller, configuration = MutableStateFlow(ENABLED), stops = mutableListOf())

        runner.submit(CollectionServiceCommand(CollectionServiceAction.STOP), startId = 1)
        runCurrent()

        assertEquals(1, controller.userStops)
        assertSame(Thread.currentThread(), controller.stopThread)
    }

    @Test
    fun startQueuedBehindStopDoesNotResumeCollection() = runTest {
        val controller = RecordingController()
        val configuration = MutableStateFlow<CollectionConfiguration?>(ENABLED)
        val stops = mutableListOf<Pair<Int, Boolean>>()
        val runner = runner(controller, configuration, stops)

        // Both arrive before the first is handled; each was already promoted to the foreground.
        runner.submit(CollectionServiceCommand(CollectionServiceAction.STOP), startId = 1)
        runner.submit(CollectionServiceCommand(CollectionServiceAction.START), startId = 2)
        runCurrent()

        assertEquals(listOf("disable", "stopByUser"), controller.calls)
        // Only the newest command decides about the notification: STOP leaves it for START, START removes it.
        assertEquals(listOf(1 to false, 2 to true), stops)
    }

    @Test
    fun enabledCommandsStartCollectionOnceAndRouteTheirAction() = runTest {
        val controller = RecordingController()
        val stops = mutableListOf<Pair<Int, Boolean>>()
        val runner = runner(controller, configuration = MutableStateFlow(ENABLED), stops = stops)

        runner.submit(CollectionServiceCommand(CollectionServiceAction.START), startId = 1)
        runner.submit(
            CollectionServiceCommand(CollectionServiceAction.VISIBILITY_CHANGED, visibility = CollectionVisibility.BACKGROUND),
            startId = 2,
        )
        runCurrent()

        assertEquals(listOf("start", "visibility=BACKGROUND"), controller.calls)
        assertEquals(emptyList<Pair<Int, Boolean>>(), stops)
    }

    @Test
    fun commandsWithoutBackgroundCollectionStopTheService() = runTest {
        val controller = RecordingController()
        val stops = mutableListOf<Pair<Int, Boolean>>()
        val runner = runner(controller, configuration = MutableStateFlow(null), stops = stops)

        runner.submit(CollectionServiceCommand(CollectionServiceAction.START), startId = 3)
        runCurrent()

        assertEquals(emptyList<String>(), controller.calls)
        assertEquals(listOf(3 to true), stops)
    }

    private fun kotlinx.coroutines.test.TestScope.runner(
        controller: RecordingController,
        configuration: MutableStateFlow<CollectionConfiguration?>,
        stops: MutableList<Pair<Int, Boolean>>,
    ) = CollectionServiceCommandRunner(
        scope = backgroundScope,
        controller = controller,
        router = CollectionServiceCommandRouter(
            controller = controller,
            autonomousCollectionStopper = object : AutonomousCollectionStopper {
                override suspend fun disable() {
                    controller.calls += "disable"
                    configuration.value = null
                }
            },
        ),
        loadConfiguration = { configuration.value },
        stopService = { startId, leaveForeground -> stops += startId to leaveForeground },
    )

    private class RecordingController : CollectionController {
        override val state: StateFlow<CollectionState> = MutableStateFlow(
            CollectionState(CollectionPhase.STOPPED, CollectionVisibility.FOREGROUND),
        )
        val calls = mutableListOf<String>()
        var userStops = 0
        var stopThread: Thread? = null

        override fun start(configuration: CollectionConfiguration) {
            calls += "start"
        }

        override fun setVisibility(visibility: CollectionVisibility) {
            calls += "visibility=$visibility"
        }

        override fun onDeviceAppeared(address: String) = Unit

        override fun onDeviceDisappeared(address: String) = Unit

        override fun onBluetoothStateChanged(enabled: Boolean) = Unit

        override fun stopByUser() {
            userStops += 1
            stopThread = Thread.currentThread()
            calls += "stopByUser"
        }

        override fun reconnectNow() = Unit

        override fun close() = Unit
    }

    private object NoopGateway : CompanionAssociationGateway {
        override fun isSupported(): Boolean = true

        override fun requestAssociation(
            address: String,
            onPending: (android.content.IntentSender) -> Unit,
            onAssociationCreated: (Int) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) = Unit

        override fun associationIdFromResult(successfulResultData: android.content.Intent): Int? = null

        override fun startPresenceObservation(address: String) = Unit

        override fun stopPresenceObservation(address: String) = Unit
    }

    private companion object {
        const val DEADLOCK_TIMEOUT_MS = 5_000L
        const val REMOTE_ADDRESS = "192.168.2.30:8765"
        val SNAPSHOT = DeviceSnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = "KMF",
            serviceUuid = null,
            notifyUuid = null,
            writeUuid = null,
        )
        val ENABLED = CollectionConfiguration(SNAPSHOT, associationId = 7, autonomousEnabled = true)
    }
}
