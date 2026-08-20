package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.migrations.Migration_24_25
import me.rerere.rikkahub.data.db.migrations.Migration_25_26
import me.rerere.rikkahub.data.db.migrations.Migration_26_27
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.db.migrations.Migration_28_29
import me.rerere.rikkahub.data.db.migrations.Migration_29_30
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.db.migrations.Migration_31_32
import me.rerere.rikkahub.data.db.migrations.Migration_32_33
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DbMigrationValidationTest {
    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate_24_to_33() {
        helper.createDatabase("mig24", 24).close()
        helper.runMigrationsAndValidate(
            "mig24",
            33,
            true,
            Migration_24_25,
            Migration_25_26,
            Migration_26_27,
            Migration_27_28,
            Migration_28_29,
            Migration_29_30,
            Migration_30_31,
            Migration_31_32,
            Migration_32_33,
        )
    }

    @Test
    fun migrate_29_to_33() {
        helper.createDatabase("mig29", 29).close()
        helper.runMigrationsAndValidate(
            "mig29",
            33,
            true,
            Migration_29_30,
            Migration_30_31,
            Migration_31_32,
            Migration_32_33,
        )
    }

    @Test
    fun migrate_31_to_33() {
        helper.createDatabase("mig31", 31).close()
        helper.runMigrationsAndValidate(
            "mig31",
            33,
            true,
            Migration_31_32,
            Migration_32_33,
        )
    }

    @Test
    fun migrate_32_to_33() {
        helper.createDatabase("mig32", 32).close()
        helper.runMigrationsAndValidate(
            "mig32",
            33,
            true,
            Migration_32_33,
        )
    }

    @Test
    fun fresh_install_builds_schema() {
        val context = RuntimeEnvironment.getApplication()
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking { db.groupDao().listGroups().first() }
        db.close()
    }
}
