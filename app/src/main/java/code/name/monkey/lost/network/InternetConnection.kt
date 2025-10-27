package code.name.monkey.lost.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import code.name.monkey.lost.helper.ENHANCEMENT_NOTIFICATION_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object InternetConnection {
    // Helper function to check for internet connection
    fun hasInternetConnection(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    suspend fun waitForConnection(
        context: Context,
        notificationBuilder: NotificationCompat.Builder,
        notificationManager: android.app.NotificationManager
    ) = withContext(Dispatchers.IO) {
        if (hasInternetConnection(context)) return@withContext

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Initial "waiting" notification
        notificationBuilder
            .setContentText("Waiting for internet connection...")
            .setProgress(0, 0, true)
            .setOngoing(true)
        notificationManager.notify(ENHANCEMENT_NOTIFICATION_ID, notificationBuilder.build())

        suspendCancellableCoroutine { continuation ->
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val caps = connectivityManager.getNetworkCapabilities(network)
                    if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
                        if (continuation.isActive) continuation.resume(Unit) { cause, _, _ -> }
                    }
                }

                override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        if (continuation.isActive) continuation.resume(Unit) { cause, _, _ -> }
                    }
                }

                override fun onLost(network: android.net.Network) {
                    // Optional: could notify that internet was lost again
                }
            }

            connectivityManager.registerDefaultNetworkCallback(networkCallback)

            continuation.invokeOnCancellation {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback)
                } catch (_: Exception) {
                }
            }
        }

        // Internet is back, update notification
        notificationBuilder
            .setContentText("Internet connection restored. Resuming...")
            .setProgress(0, 0, false)
            .setOngoing(false)
        notificationManager.notify(ENHANCEMENT_NOTIFICATION_ID, notificationBuilder.build())
    }
}
