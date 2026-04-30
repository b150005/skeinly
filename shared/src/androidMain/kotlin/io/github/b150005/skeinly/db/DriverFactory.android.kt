package io.github.b150005.skeinly.db

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(
    private val context: Context,
) {
    actual fun createDriver(): SqlDriver = AndroidSqliteDriver(SkeinlyDatabase.Schema, context, "skeinly.db")
}
