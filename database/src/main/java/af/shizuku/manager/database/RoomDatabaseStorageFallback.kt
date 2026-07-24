package af.shizuku.manager.database

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.room.RoomDatabase
import timber.log.Timber

/**
 * Builds a Room database, preferring device-protected storage so it survives direct boot; falls
 * back to regular app storage if that context isn't usable (some devices map
 * createDeviceProtectedStorageContext() back to credential-encrypted storage that isn't yet
 * accessible), then to an in-memory database as a last resort so storage issues never crash the
 * app outright. Shared by [ActivityLogDatabase] and [ScriptSnippetDatabase].
 */
internal fun <T : RoomDatabase> buildRoomDatabaseWithStorageFallback(
    context: Context,
    databaseName: String,
    klass: Class<T>,
    logTag: String,
): T {
    val candidates = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            add(context.createDeviceProtectedStorageContext())
        }
        add(context.applicationContext)
    }

    for (ctx in candidates) {
        val dbFile = ctx.getDatabasePath(databaseName)
        val parent = dbFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        if (parent == null || parent.exists()) {
            return Room.databaseBuilder(ctx, klass, databaseName)
                .fallbackToDestructiveMigration()
                .build()
        }
        Timber.tag(logTag).w("mkdirs failed for ${parent.absolutePath}, trying next context")
    }

    Timber.tag(logTag).e("All storage contexts failed; falling back to in-memory database")
    return Room.inMemoryDatabaseBuilder(context.applicationContext, klass)
        .fallbackToDestructiveMigration()
        .build()
}
