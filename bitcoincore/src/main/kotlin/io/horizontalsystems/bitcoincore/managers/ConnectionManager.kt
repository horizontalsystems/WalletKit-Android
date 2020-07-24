package io.horizontalsystems.bitcoincore.managers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.horizontalsystems.bitcoincore.core.IConnectionManager
import io.horizontalsystems.bitcoincore.core.IConnectionManagerListener
import java.util.concurrent.Executors

class ConnectionManager(context: Context) : IConnectionManager {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override var listener: IConnectionManagerListener? = null
    override var isConnected = getInitialConnectionStatus()

    private var hasValidInternet = false
    private var hasConnection = false
    private var callback = ConnectionStatusCallback()

    init {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            //was not registered, or already unregistered
        }
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
    }

    private fun getInitialConnectionStatus(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false

        hasConnection = true
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        hasValidInternet = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false

        return hasValidInternet
    }

    inner class ConnectionStatusCallback : ConnectivityManager.NetworkCallback() {

        private val activeNetworks: MutableList<Network> = mutableListOf()

        override fun onLost(network: Network) {
            super.onLost(network)
            activeNetworks.removeAll { activeNetwork -> activeNetwork == network }
            hasConnection = activeNetworks.isNotEmpty()
            updatedConnectionState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            hasValidInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            updatedConnectionState()
        }

        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (activeNetworks.none { activeNetwork -> activeNetwork == network }) {
                activeNetworks.add(network)
            }
            hasConnection = activeNetworks.isNotEmpty()
            updatedConnectionState()
        }
    }

    private fun updatedConnectionState() {
        val oldValue = isConnected
        isConnected = hasConnection && hasValidInternet
        if (oldValue != isConnected) {
            listener?.onConnectionChange(isConnected)
        }
    }
}
