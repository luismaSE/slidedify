package com.slidedify.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.slidedify.core.domain.provider.AuthState
import com.slidedify.core.domain.provider.ProviderRegistry
import com.slidedify.core.domain.repository.SortProgressRepository
import com.slidedify.core.domain.repository.TrackSource
import com.slidedify.core.domain.repository.key
import com.slidedify.core.domain.session.NewLikes
import com.slidedify.core.domain.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules the weekly check while the setting is on, and cancels it when it is turned off. */
@Singleton
class NewLikesReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch {
            settings.settings.map { it.newLikesReminder }.distinctUntilChanged().collect { enabled ->
                val work = WorkManager.getInstance(context)
                if (enabled) {
                    val request = PeriodicWorkRequestBuilder<NewLikesReminderWorker>(7, TimeUnit.DAYS)
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setInitialDelay(1, TimeUnit.DAYS)
                        .build()
                    work.enqueueUniquePeriodicWork(NewLikesReminderWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
                } else {
                    work.cancelUniqueWork(NewLikesReminderWorker.UNIQUE_NAME)
                }
            }
        }
    }
}

/** Counts the new liked songs and, if there are some (and more than last time), posts a notification. */
class NewLikesReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun providerRegistry(): ProviderRegistry

        fun settingsRepository(): SettingsRepository

        fun sortProgress(): SortProgressRepository
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val settings = deps.settingsRepository().settings.first()
        if (!settings.newLikesReminder || !canNotify(applicationContext)) return Result.success()
        val registry = deps.providerRegistry()
        val provider = settings.lastProvider?.let(registry::find) ?: registry.active.value
        // Logins restore in the background right after start; give them a moment.
        val signedIn = withTimeoutOrNull(10_000) { provider.auth.state.first { it is AuthState.SignedIn } } != null
        if (!signedIn) return Result.success()
        val count = try {
            val sorted = deps.sortProgress().sortedTracks(provider.id, TrackSource.LikedSongs.key)
            NewLikes.load(provider.tracks, sorted, settings.newLikesSince[provider.id]).size
        } catch (e: Exception) {
            return Result.retry()
        }
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (count > 0 && count != prefs.getInt(KEY_LAST_COUNT, 0)) notify(applicationContext, count)
        prefs.edit().putInt(KEY_LAST_COUNT, count).apply()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "new-likes-reminder"
        private const val CHANNEL = "new_likes"
        private const val PREFS = "reminders"
        private const val KEY_LAST_COUNT = "new_likes_last_count"
        private const val NOTIFICATION_ID = 7

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun notify(context: Context, count: Int) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_NEW_LIKES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.resources.getQuantityString(R.plurals.reminder_title, count, count))
                .setContentText(context.getString(R.string.reminder_text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
