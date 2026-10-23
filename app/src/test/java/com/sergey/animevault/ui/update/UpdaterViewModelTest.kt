package com.sergey.animevault.ui.update

import com.sergey.animevault.data.update.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeUpdates

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeUpdates()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun manualCheckShowsReleaseDetails() = runTest(dispatcher) {
        val vm = UpdaterViewModel(repository)
        vm.check()
        assertEquals(UpdateOperation.CHECKING, vm.state.value.operation)
        advanceUntilIdle()
        assertTrue(vm.state.value.showDialog)
        assertEquals(64L, vm.state.value.update?.versionCode)
    }

    @Test fun noReleaseIsNotAnError() = runTest(dispatcher) {
        repository.result = null
        val vm = UpdaterViewModel(repository)
        vm.check()
        advanceUntilIdle()
        assertFalse(vm.state.value.isError)
        assertFalse(vm.state.value.showDialog)
        assertEquals("Обновлений пока нет.", vm.state.value.message)
    }

    @Test fun automaticCheckHonorsOptOutAndThrottle() = runTest(dispatcher) {
        repository.automaticChecks = false
        val vm = UpdaterViewModel(repository)
        vm.checkAutomatically()
        advanceUntilIdle()
        assertEquals(0, repository.checks)
        vm.setAutomaticChecks(true)
        vm.checkAutomatically()
        advanceUntilIdle()
        vm.checkAutomatically()
        advanceUntilIdle()
        assertEquals(1, repository.checks)
    }

    @Test fun skippedVersionIsAvailableFromManualCheck() = runTest(dispatcher) {
        val vm = UpdaterViewModel(repository)
        vm.check()
        advanceUntilIdle()
        vm.skipVersion()
        assertFalse(vm.state.value.showDialog)
        repository.throttled = false
        vm.checkAutomatically()
        advanceUntilIdle()
        assertFalse(vm.state.value.showDialog)
        vm.check()
        advanceUntilIdle()
        assertTrue(vm.state.value.showDialog)
    }

    @Test fun networkFailureAllowsRetry() = runTest(dispatcher) {
        repository.failure = IOException("offline")
        val vm = UpdaterViewModel(repository)
        vm.check()
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.isError)
        repository.failure = null
        vm.check()
        advanceUntilIdle()
        assertTrue(vm.state.value.showDialog)
        assertFalse(vm.state.value.isError)
    }

    @Test fun repeatedCheckDoesNotStartParallelRequests() = runTest(dispatcher) {
        val vm = UpdaterViewModel(repository)
        vm.check()
        vm.check()
        advanceUntilIdle()
        assertEquals(1, repository.checks)
    }

    @Test fun dismissDoesNotCancelDownloadAndProgressIsRetained() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        repository.downloadAction = { progress ->
            progress(512, 1024)
            gate.await()
            File("verified.apk")
        }
        val vm = UpdaterViewModel(repository)
        vm.check()
        advanceUntilIdle()
        vm.download()
        runCurrent()
        vm.dismiss()
        assertEquals(512L, vm.state.value.receivedBytes)
        assertEquals(UpdateOperation.DOWNLOADING, vm.state.value.operation)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNotNull(vm.state.value.readyApk)
        assertFalse(vm.state.value.showDialog)
    }

    @Test fun cancelWaitsForPartialFileCleanup() = runTest(dispatcher) {
        var cleaned = false
        repository.downloadAction = {
            try { awaitCancellation() } finally { cleaned = true }
        }
        val vm = UpdaterViewModel(repository)
        vm.check()
        advanceUntilIdle()
        vm.download()
        runCurrent()
        vm.cancelDownload()
        advanceUntilIdle()
        assertTrue(cleaned)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.readyApk)
    }

    @Test fun integrityFailureKeepsReleaseAvailableForRetry() = runTest(dispatcher) {
        repository.downloadAction = { throw UpdateException("Контрольная сумма не совпадает.") }
        val vm = UpdaterViewModel(repository)
        vm.check()
        advanceUntilIdle()
        vm.download()
        advanceUntilIdle()
        assertTrue(vm.state.value.isError)
        assertNull(vm.state.value.readyApk)
        assertNotNull(vm.state.value.update)
        assertFalse(vm.state.value.busy)
    }

    @Test fun installationReverifiesApkAndDoesNotInstallOnFailure() = runTest(dispatcher) {
        repository.cached = File("cached.apk")
        repository.verifyFailure = UpdateException("Файл повреждён.")
        val vm = UpdaterViewModel(repository)
        vm.check()
        advanceUntilIdle()
        var installerOpened = false
        vm.prepareInstall { installerOpened = true }
        advanceUntilIdle()
        assertFalse(installerOpened)
        assertEquals(1, repository.verifications)
        assertNull(vm.state.value.readyApk)
        assertTrue(vm.state.value.isError)
    }

    @Test fun permissionReturnRequiresAnotherExplicitInstallAction() = runTest(dispatcher) {
        repository.cached = File("cached.apk")
        val vm = UpdaterViewModel(repository)
        vm.check()
        advanceUntilIdle()
        vm.requireInstallPermission(true)
        assertTrue(vm.state.value.permissionRequired)
        vm.requireInstallPermission(false)
        assertEquals(0, repository.verifications)
        var installerOpened = false
        vm.prepareInstall { installerOpened = true }
        advanceUntilIdle()
        assertTrue(installerOpened)
        assertEquals(1, repository.verifications)
    }

    private class FakeUpdates : UpdateRepository {
        override var automaticChecks = true
        var throttled = false
        var skipped = -1L
        var checks = 0
        var verifications = 0
        var failure: Exception? = null
        var verifyFailure: Exception? = null
        var cached: File? = null
        var result: AppUpdate? = AppUpdate(
            64, "3.0.4", 24, "ab".repeat(32),
            ReleaseAsset("AnimeVault.apk", "https://github.com/example.apk", 1024),
            GitHubRelease("v3.0.4", "3.0.4", "Изменения", "", emptyList()),
        )
        var downloadAction: suspend ((Long, Long) -> Unit) -> File = { File("verified.apk") }
        override fun shouldCheckAutomatically(now: Long) = automaticChecks && !throttled
        override fun recordAutomaticCheck(now: Long) { throttled = true }
        override fun skip(versionCode: Long) { skipped = versionCode }
        override fun isSkipped(versionCode: Long) = skipped == versionCode
        override suspend fun latest(): AppUpdate? {
            checks++
            failure?.let { throw it }
            return result
        }
        override suspend fun cachedApk(update: AppUpdate) = cached
        override suspend fun download(update: AppUpdate, onProgress: (Long, Long) -> Unit) = downloadAction(onProgress)
        override suspend fun verify(file: File, update: AppUpdate) {
            verifications++
            verifyFailure?.let { throw it }
        }
    }
}
