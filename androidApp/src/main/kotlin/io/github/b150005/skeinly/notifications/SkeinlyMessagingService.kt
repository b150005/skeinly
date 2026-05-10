package io.github.b150005.skeinly.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.b150005.skeinly.MainActivity
import io.github.b150005.skeinly.R

/**
 * Phase 24.5 (ADR-017 §3.8) — receives FCM messages while the app is in
 * the foreground (or as a `data`-only payload) and surfaces them as a
 * standard Android system notification. The tap target is
 * [MainActivity] with an `EXTRA_PUSH_ROUTE` extra carrying the
 * host-relative deep link from the Edge Function's
 * `message.data.route` field.
 *
 * **Background / killed state**: FCM's library auto-displays the
 * notification when the message has a `notification` field (which our
 * Edge Function always sends). The tap launches the launcher activity
 * (this app's [MainActivity]) and the `data` map flows in as intent
 * extras. We do NOT receive [onMessageReceived] in that case — FCM
 * intercepts before the service.
 *
 * **Foreground state**: FCM does NOT auto-display a `notification` +
 * `data` message; it dispatches to this service. We rebuild the visible
 * banner via [NotificationCompat.Builder] so the user sees the same UX
 * as background delivery. The `PendingIntent` mirrors what FCM's auto-
 * display would have built, so the tap behavior is identical.
 *
 * **Channel**: Android 8.0+ requires every notification to belong to a
 * channel. We create a single `collaboration` channel lazily on first
 * use; future event categories (Phase 24+) can add separate channels
 * for per-category mute control.
 */
class SkeinlyMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification ?: return
        val title = notification.title ?: getString(R.string.app_name)
        val body = notification.body ?: return
        val route = message.data["route"]
        showNotification(title, body, route)
    }

    /**
     * Phase 24.5 — token rotation hook. FCM rotates tokens on app data
     * clear / re-install / OS push reset. The shared
     * [io.github.b150005.skeinly.notifications.PushTokenRegistrar]
     * re-acquires + upserts on every app foreground via the standard
     * Phase 24.2c-3 trigger paths (SuggestionList opened with PRs,
     * etc.), so the rotation lands on the next user-visible
     * collaboration moment without needing a service-side upsert
     * here. Logged for triage visibility only — emit `length` rather
     * than the token string to keep PII out of logcat.
     */
    override fun onNewToken(token: String) {
        // Intentionally a no-op beyond the platform default. The
        // per-user upsert needs an authenticated session + Koin
        // resolution which is fragile from a backgrounded service;
        // the app-foreground re-acquire path is the canonical
        // refresh trigger.
    }

    private fun showNotification(
        title: String,
        body: String,
        route: String?,
    ) {
        val context = applicationContext
        ensureChannel()

        val tapIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!route.isNullOrBlank()) {
                    putExtra(MainActivity.EXTRA_PUSH_ROUTE, route)
                }
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                // requestCode =
                NEXT_REQUEST_CODE.getAndIncrement(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // POST_NOTIFICATIONS runtime permission (API 33+) is required
        // to actually display. The Phase 24.2 explainer + permission
        // request flow gates the user opting in; if the flow somehow
        // bypassed (e.g. user revoked OS-side after permission grant),
        // `notify` silently no-ops thanks to NotificationManagerCompat's
        // permission check.
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(
                NEXT_NOTIFICATION_ID.getAndIncrement(),
                builder.build(),
            )
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_collaboration),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.notification_channel_collaboration_description)
            }
        nm.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "collaboration"

        // Phase 24.5 — monotonic counters scoped to the service process
        // life so each notification gets a distinct id (otherwise FCM
        // would collapse all collaboration pushes into a single
        // notification). The service process is short-lived; counter
        // resets on each cold spawn are fine. AtomicInteger so concurrent
        // FCM deliveries on different binder threads don't race.
        val NEXT_NOTIFICATION_ID =
            java.util.concurrent.atomic
                .AtomicInteger(1_000)
        val NEXT_REQUEST_CODE =
            java.util.concurrent.atomic
                .AtomicInteger(2_000)
    }
}
