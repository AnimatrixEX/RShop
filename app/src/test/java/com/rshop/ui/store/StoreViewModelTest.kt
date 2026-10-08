package com.rshop.ui.store

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import androidx.test.core.app.ApplicationProvider
import com.rshop.data.source.SourceManager
import com.rshop.data.source.SourceRepository
import com.rshop.data.preferences.DataStoreSettingsRepository
import com.rshop.data.sync.DownloadCountScheduler
import com.rshop.data.sync.SyncScheduler
import com.rshop.data.sync.SyncStatusStore
import com.rshop.testing.fixedClock
import okhttp3.OkHttpClient
import com.rshop.data.database.AppDatabase
import com.rshop.domain.model.SortOrder
import com.rshop.testing.inMemoryDatabase
import com.rshop.testing.repository
import com.rshop.testing.testGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for Room and for SavedStateHandle.toRoute, which decodes arguments through Bundles.
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StoreViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = inMemoryDatabase(queryContext = dispatcher)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun TestScope.viewModel(genre: String? = null): StoreViewModel {
        db.repository().saveGames(
            listOf(
                testGame("neon", title = "Neon Drift", genre = "Course", sizeBytes = 3),
                testGame("kart", title = "Turbo Kart", genre = "Course", sizeBytes = 12),
                testGame("quest", title = "Pixel Quest", genre = "Aventure", sizeBytes = 16),
            ),
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clock = fixedClock()
        val status = SyncStatusStore(context, clock)
        val sources = SourceRepository(context, OkHttpClient(), backgroundScope)
        val sourceManager = SourceManager(
            sources = sources,
            games = db.repository(),
            scheduler = SyncScheduler(context, status, sources, clock, DataStoreSettingsRepository(context), DownloadCountScheduler(context)),
            status = status,
            clock = clock,
        )
        val vm = StoreViewModel(
            savedStateHandle = SavedStateHandle(mapOf("genre" to genre, "platform" to null)),
            repository = db.repository(),
            sourceManager = sourceManager,
        )
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `route genre preselects the filter`() = runTest(dispatcher) {
        val vm = viewModel(genre = "Course")

        val state = vm.uiState.value
        assertEquals("Course", state.genre)
        assertEquals(2, state.resultCount)
        assertEquals(listOf("Neon Drift", "Turbo Kart"), vm.games.asSnapshot().map { it.title })
    }

    @Test
    fun `search is applied after the debounce`() = runTest(dispatcher) {
        val vm = viewModel()
        assertEquals(3, vm.uiState.value.resultCount)

        vm.onQueryChange("quest")
        advanceTimeBy(100)
        assertEquals(3, vm.uiState.value.resultCount)

        advanceTimeBy(300)
        assertEquals(1, vm.uiState.value.resultCount)
        assertEquals(listOf("Pixel Quest"), vm.games.asSnapshot().map { it.title })
    }

    @Test
    fun `sort cycles through every order and wraps`() = runTest(dispatcher) {
        val vm = viewModel()

        val seen = mutableListOf(vm.uiState.value.sort)
        repeat(SortOrder.entries.size) {
            vm.onCycleSort()
            advanceUntilIdle()
            seen += vm.uiState.value.sort
        }
        assertEquals(SortOrder.entries + SortOrder.entries.first(), seen)
    }

    @Test
    fun `size sort lists biggest first`() = runTest(dispatcher) {
        val vm = viewModel()
        while (vm.uiState.value.sort != SortOrder.Size) {
            vm.onCycleSort()
            advanceUntilIdle()
        }
        assertEquals(listOf("Pixel Quest", "Turbo Kart", "Neon Drift"), vm.games.asSnapshot().map { it.title })
    }
}
