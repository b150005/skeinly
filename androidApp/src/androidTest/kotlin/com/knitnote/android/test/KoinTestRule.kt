package com.knitnote.android.test

import com.knitnote.android.di.testSharedModules
import org.junit.rules.ExternalResource
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * JUnit rule that manages Koin lifecycle for Compose UI tests.
 * Starts Koin with test modules before each test and stops it after.
 */
class KoinTestRule : ExternalResource() {
    override fun before() {
        if (GlobalContext.getKoinApplicationOrNull() != null) stopKoin()
        startKoin {
            modules(testSharedModules)
        }
    }

    override fun after() {
        stopKoin()
    }
}
