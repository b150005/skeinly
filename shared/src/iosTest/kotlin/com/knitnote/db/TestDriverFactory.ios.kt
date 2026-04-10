package com.knitnote.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual fun createTestDriver(): SqlDriver {
    return NativeSqliteDriver(KnitNoteDatabase.Schema, "test.db")
}
