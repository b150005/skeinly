package io.github.b150005.knitnote.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(KnitNoteDatabase.Schema, "knitnote.db")
}
