package com.rshop.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rshop.data.database.AppDatabase
import com.rshop.data.repository.RoomGameRepository
import com.rshop.domain.model.Game
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

val TEST_NOW: Instant = Instant.parse("2026-10-01T12:00:00Z")

fun fixedClock(at: Instant = TEST_NOW): Clock = Clock.fixed(at, ZoneOffset.UTC)

/** Pass a test dispatcher as [queryContext] to make Room queries deterministic under runTest. */
fun inMemoryDatabase(queryContext: CoroutineContext? = null): AppDatabase = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext<Context>(),
    AppDatabase::class.java,
).apply {
    if (queryContext != null) setQueryCoroutineContext(queryContext)
}.build()

fun AppDatabase.repository(clock: Clock = fixedClock()) =
    RoomGameRepository(gameDao(), favoriteDao(), historyDao(), clock)

fun testGame(
    id: String,
    title: String = id,
    platform: String? = "SNES",
    genre: String? = "RPG",
    sizeBytes: Long? = 1_000,
    addedDaysAgo: Long? = 1,
    updatedDaysAgo: Long? = 1,
    popularity: Int = 0,
    screenshots: List<String> = emptyList(),
    version: String? = "1.0",
) = Game(
    id = "test:$id",
    title = title,
    description = "Description of $title",
    coverUrl = null,
    screenshots = screenshots,
    downloadUrl = null,
    version = version,
    sizeBytes = sizeBytes,
    platform = platform,
    genre = genre,
    sourceUrl = null,
    addedAt = addedDaysAgo?.let { TEST_NOW.minusSeconds(it * 86_400) },
    updatedAt = updatedDaysAgo?.let { TEST_NOW.minusSeconds(it * 86_400) },
    popularity = popularity,
)
