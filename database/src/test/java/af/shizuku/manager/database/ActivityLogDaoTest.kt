package af.shizuku.manager.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActivityLogDaoTest {

    private lateinit var database: ActivityLogDatabase
    private lateinit var dao: ActivityLogDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ActivityLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.activityLogDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_and_getCount() = runTest {
        val log = ActivityLogRoom(
            timestamp = 1000L,
            appName = "Test App",
            packageName = "com.test.app",
            action = "START"
        )
        dao.insert(log)
        dao.getCount() shouldBe 1
    }

    @Test
    fun insertAll_and_getAll() = runTest {
        val logs = listOf(
            ActivityLogRoom(timestamp = 1000L, appName = "App 1", packageName = "pkg.1", action = "START"),
            ActivityLogRoom(timestamp = 2000L, appName = "App 2", packageName = "pkg.2", action = "STOP")
        )
        dao.insertAll(logs)

        val allLogs = dao.getAll().first()
        allLogs shouldHaveSize 2
        // Sorted by timestamp DESC
        allLogs[0].timestamp shouldBe 2000L
        allLogs[1].timestamp shouldBe 1000L
    }

    @Test
    fun getLimited_returns_correct_number_of_items() = runTest {
        val logs = (1..5).map {
            ActivityLogRoom(timestamp = it * 1000L, appName = "App \$it", packageName = "pkg.\$it", action = "ACTION")
        }
        dao.insertAll(logs)

        val limited = dao.getLimited(3).first()
        limited shouldHaveSize 3
        limited[0].timestamp shouldBe 5000L
    }

    @Test
    fun clear_deletes_all_logs() = runTest {
        dao.insert(ActivityLogRoom(timestamp = 1000L, appName = "App", packageName = "pkg", action = "ACTION"))
        dao.getCount() shouldBe 1

        dao.clear()
        dao.getCount() shouldBe 0
    }

    @Test
    fun deleteOlderThan_deletes_correctly() = runTest {
        dao.insertAll(listOf(
            ActivityLogRoom(timestamp = 100L, appName = "Old", packageName = "old", action = "ACTION"),
            ActivityLogRoom(timestamp = 200L, appName = "New", packageName = "new", action = "ACTION")
        ))

        val deletedCount = dao.deleteOlderThan(150L)
        deletedCount shouldBe 1
        dao.getCount() shouldBe 1

        val remaining = dao.getAll().first()
        remaining[0].appName shouldBe "New"
    }

    @Test
    fun delete_specific_log() = runTest {
        val log = ActivityLogRoom(id = 1, timestamp = 1000L, appName = "App", packageName = "pkg", action = "ACTION")
        dao.insert(log)
        dao.getCount() shouldBe 1

        dao.delete(log)
        dao.getCount() shouldBe 0
    }

    @Test
    fun getOldest_returns_oldest_log() = runTest {
        dao.insertAll(listOf(
            ActivityLogRoom(timestamp = 3000L, appName = "Newest", packageName = "new", action = "ACTION"),
            ActivityLogRoom(timestamp = 1000L, appName = "Oldest", packageName = "old", action = "ACTION"),
            ActivityLogRoom(timestamp = 2000L, appName = "Middle", packageName = "mid", action = "ACTION")
        ))

        val oldest = dao.getOldest()
        oldest?.timestamp shouldBe 1000L
        oldest?.appName shouldBe "Oldest"
    }

    @Test
    fun getOldest_returns_null_when_empty() = runTest {
        dao.getOldest() shouldBe null
    }
}
