package com.revertron.mimir

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager
import uniffi.mimir.AndroidNetworkInterface
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong


private const val TAG = "NetState"

class NetState(val context: Context) : ConnectivityManager.NetworkCallback() {

    companion object {
        lateinit var state: NetState
        var connected: Boolean = false

        fun networkChangedRecently(): Boolean {
            return System.currentTimeMillis() - state.networkChangedTime.get() < 15000
        }

        fun haveNetwork(): Boolean {
            return connected
        }
    }

    init {
        state = this
    }

    private var networkChangedTime = AtomicLong(0L)

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.d(TAG, "onAvailable")
        if (connected) {
            Log.i(TAG, "Already connected, ignoring")
            return
        }
        networkChanged()
        connected = haveNetwork(context)

        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (preferences.getBoolean("enabled", true)) {
            Thread {
                // The message often arrives before the connection is fully established
                //Thread.sleep(1000)
                val intent = Intent(context, ConnectionService::class.java)
                intent.putExtra("command", "online")
                try {
                    context.startService(intent)
                } catch (e: IllegalStateException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                }
            }.start()
        }
    }

    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        // This fires on ConnectivityThread (an Android system thread). Any
        // uncaught exception here kills the process, so wrap the whole handler.
        try {
            val interfaces = getInterfaces(linkProperties)
            val serialized = interfaces.map { "${it.name},${it.index},${it.addrs.joinToString("|")}" }.toTypedArray()
            val intent = Intent(context, ConnectionService::class.java)
            intent.putExtra("command", "interfaces")
            intent.putExtra("interfaces", serialized)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onLinkPropertiesChanged: swallowed ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        Log.d(TAG, "onLost")
        networkChanged()
        connected = false

        Thread {
            //Thread.sleep(1000)
            if (!haveNetwork(context)) {
                val intent = Intent(context, ConnectionService::class.java)
                intent.putExtra("command", "offline")
                try {
                    context.startService(intent)
                } catch (e: IllegalStateException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                }
            }
        }.start()
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerNetworkCallback(request, this)
    }

    fun unregister() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.unregisterNetworkCallback(this)
    }

    fun networkChanged() {
        networkChangedTime.set(System.currentTimeMillis())
    }

    fun getInterfaces(linkProps: LinkProperties): List<AndroidNetworkInterface> {
        val name = linkProps.interfaceName ?: return emptyList()
        // NetworkInterface.getByName can crash deep inside Android's libcore
        // ("Attempt to read from field 'java.util.List java.net.NetworkInterface.childs'
        // on a null object reference") when the interface is in a transient state
        // during rapid network churn. That exception fires on ConnectivityThread,
        // so an uncaught throw tears down the whole process. Swallow it —
        // returning an empty list just means this interface update is skipped
        // and the next onLinkPropertiesChanged will try again.
        val iface = try {
            NetworkInterface.getByName(name)
        } catch (t: Throwable) {
            Log.w(TAG, "NetworkInterface.getByName($name) threw ${t.javaClass.simpleName}: ${t.message}")
            return emptyList()
        } ?: return emptyList()
        val addrs = linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet6Address>()
            .filter { it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }

        val index = try { iface.index.toUInt() } catch (t: Throwable) { return emptyList() }
        return listOf(
            AndroidNetworkInterface(
                name = name,
                index = index,
                addrs = addrs
            )
        )
    }
}