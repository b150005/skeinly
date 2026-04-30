package io.github.b150005.skeinly.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual class DriverFactory {
    actual fun createDriver(): SqlDriver = NativeSqliteDriver(SkeinlyDatabase.Schema, "skeinly.db")
}
