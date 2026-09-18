package com.flowai.communication

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Replaces `Dispatchers.Main` for tests that touch a ViewModel.
 *
 * Engine calls are suspending and `viewModelScope` posts to Main, which does not exist in a plain
 * JVM test. Without this the scope throws during construction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    /**
     * Unconfined so a launched engine call completes inline. The ViewModel tests assert on state
     * right after `analyze()`, which a queued dispatcher would not have run yet.
     */
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
