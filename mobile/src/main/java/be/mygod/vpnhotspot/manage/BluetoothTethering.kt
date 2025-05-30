package be.mygod.vpnhotspot.manage

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import be.mygod.vpnhotspot.util.broadcastReceiver
import be.mygod.vpnhotspot.util.readableMessage
import be.mygod.vpnhotspot.widget.SmartSnackbar
import timber.log.Timber
import java.lang.reflect.InvocationTargetException

class BluetoothTethering(context: Context, private val adapter: BluetoothAdapter, val stateListener: () -> Unit) :
        BluetoothProfile.ServiceListener, AutoCloseable {
    companion object : BroadcastReceiver() {
        /**
         * PAN Profile
         */
        private const val PAN = 5
        private val clazz by lazy { Class.forName("android.bluetooth.BluetoothPan") }
        private val isTetheringOn by lazy { clazz.getDeclaredMethod("isTetheringOn") }

        private val BluetoothProfile.isTetheringOn get() = isTetheringOn(this) as Boolean

        private fun registerBluetoothStateListener(receiver: BroadcastReceiver) =
                app.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        private var pendingCallback: TetheringManagerCompat.StartTetheringCallback? = null

        /**
         * https://android.googlesource.com/platform/packages/apps/Settings/+/b1af85d/src/com/android/settings/TetherSettings.java#215
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> TetheringManagerCompat.startTethering(
                    TetheringManagerCompat.TETHERING_BLUETOOTH, true, pendingCallback!!)
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.ERROR -> { }
                else -> return  // ignore transition states
            }
            pendingCallback = null
            app.unregisterReceiver(this)
        }
    }

    private var proxyCreated = false
    private var connected = false
    private var pan: BluetoothProfile? = null
    private var stoppedByUser = false
    var activeFailureCause: Throwable? = null
    /**
     * Based on: https://android.googlesource.com/platform/packages/apps/Settings/+/78d5efd/src/com/android/settings/TetherSettings.java
     */
    val active: Boolean? get() {
        val pan = pan ?: return null
        if (!connected) return null
        activeFailureCause = null
        val on = adapter.state == BluetoothAdapter.STATE_ON && try {
            pan.isTetheringOn
        } catch (e: InvocationTargetException) {
            activeFailureCause = e.cause ?: e
            if (e.cause is SecurityException && Build.VERSION.SDK_INT >= 30) Timber.d(e.readableMessage)
            else Timber.w(e)
            return null
        }
        return if (stoppedByUser) {
            if (!on) stoppedByUser = false
            false
        } else on
    }

    private val receiver = broadcastReceiver { _, _ -> stateListener() }

    fun ensureInit(context: Context) {
        activeFailureCause = null
        if (!proxyCreated) try {
            if (adapter.getProfileProxy(context, this, PAN)) proxyCreated = true
            else activeFailureCause = Exception("getProfileProxy failed")
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 31) Timber.d(e.readableMessage) else Timber.w(e)
            activeFailureCause = e
        }
    }
    init {
        ensureInit(context)
        registerBluetoothStateListener(receiver)
    }

    override fun onServiceDisconnected(profile: Int) {
        connected = false
        stoppedByUser = false
    }
    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
        pan = proxy
        connected = true
        stateListener()
    }

    /**
     * https://android.googlesource.com/platform/packages/apps/Settings/+/b1af85d/src/com/android/settings/TetherSettings.java#384
     */
    @SuppressLint("MissingPermission")
    fun start(callback: TetheringManagerCompat.StartTetheringCallback, context: Context) {
        if (pendingCallback == null) try {
            if (adapter.state == BluetoothAdapter.STATE_OFF) {
                registerBluetoothStateListener(BluetoothTethering)
                pendingCallback = callback
                @Suppress("DEPRECATION")
                if (!adapter.enable()) context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else TetheringManagerCompat.startTethering(TetheringManagerCompat.TETHERING_BLUETOOTH, true, callback)
        } catch (e: SecurityException) {
            SmartSnackbar.make(e.readableMessage).shortToast().show()
            pendingCallback = null
        }
    }
    fun stop(callback: TetheringManagerCompat.StopTetheringCallback) {
        TetheringManagerCompat.stopTethering(TetheringManagerCompat.TETHERING_BLUETOOTH, callback)
        stoppedByUser = true
    }

    override fun close() {
        app.unregisterReceiver(receiver)
        adapter.closeProfileProxy(PAN, pan)
    }
}
