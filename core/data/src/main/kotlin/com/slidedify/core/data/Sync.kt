package com.slidedify.core.data

import android.content.Context
import androidx.room.Room
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.slidedify.core.domain.outbox.OutboxLibraryRepository
import com.slidedify.core.domain.outbox.OutboxProcessor
import com.slidedify.core.domain.outbox.OutboxStore
import com.slidedify.core.domain.outbox.SyncScheduler
import com.slidedify.core.domain.outbox.SyncStatus
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.LibraryRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Runs the outbox when there is network, retrying with exponential backoff. */
class OutboxSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun outboxStore(): OutboxStore

        fun providerRegistry(): ProviderRegistry
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val registry = deps.providerRegistry()
        val processor = OutboxProcessor(
            store = deps.outboxStore(),
            writerFor = { id -> registry.find(id)?.writer },
            maxPerBatch = { id -> registry.find(id)?.capabilities?.maxTracksPerWrite ?: DEFAULT_BATCH },
        )
        return when (processor.process()) {
            is OutboxProcessor.Result.Done -> Result.success()
            is OutboxProcessor.Result.Failed -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "outbox-sync"
        private const val DEFAULT_BATCH = 50
        private const val MAX_ATTEMPTS = 10
    }
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            // Short delay so a burst of swipes goes out in a single batch.
            .setInitialDelay(2, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(OutboxSyncWorker.UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun outboxStore(impl: RoomOutboxStore): OutboxStore

    @Binds
    abstract fun syncStatus(impl: RoomOutboxStore): SyncStatus

    @Binds
    abstract fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    companion object {
        @Provides
        @Singleton
        fun database(@ApplicationContext context: Context): SlidedifyDatabase =
            Room.databaseBuilder(context, SlidedifyDatabase::class.java, "slidedify.db").build()

        @Provides
        fun outboxDao(db: SlidedifyDatabase): OutboxDao = db.outboxDao()

        /** Features write through the outbox: instant UI, works offline, synced in batches. */
        @Provides
        @Singleton
        fun libraryRepository(store: OutboxStore, scheduler: SyncScheduler): LibraryRepository =
            OutboxLibraryRepository(store, scheduler)
    }
}
