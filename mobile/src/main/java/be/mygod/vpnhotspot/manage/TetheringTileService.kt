package be.mygod.vpnhotspot.manage

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.net.TetheringManager
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.TetheringService
import be.mygod.vpnhotspot.net.TetherType
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import be.mygod.vpnhotspot.net.TetheringManagerCompat.tetheredIfaces
import be.mygod.vpnhotspot.util.broadcastReceiver
import be.mygod.vpnhotspot.util.readableMessage
import be.mygod.vpnhotspot.util.stopAndUnbind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import timber.log.Timber

sealed class TetheringTileService : IpNeighbourMonitoringTileService(), TetheringManagerCompat.StartTetheringCallback,
    TetheringManagerCompat.StopTetheringCallback {
    protected val tileOff by lazy { Icon.createWithResource(application, icon) }
    protected val tileOn by lazy { Icon.createWithResource(application, R.drawable.ic_quick_settings_tile_on) }

    protected abstract val labelString: Int
    protected abstract val tetherType: TetherType
    protected open val icon get() = tetherType.icon
    private var tethered: List<String>? = null
    protected val interested get() = tethered?.filter { TetherType.ofInterface(it).isA(tetherType) }
    protected var binder: TetheringService.Binder? = null

    private val receiver = broadcastReceiver { _, intent ->
        tethered = intent.tetheredIfaces ?: return@broadcastReceiver
        updateTile()
    }

    protected abstract fun start()
    protected abstract fun stop()

    override fun onStartListening() {
        super.onStartListening()
        bindService(Intent(this, TetheringService::class.java), this, Context.BIND_AUTO_CREATE)
        // we need to initialize tethered ASAP for onClick, which is not achievable using registerTetheringEventCallback
        tethered = registerReceiver(receiver, IntentFilter(TetheringManagerCompat.ACTION_TETHER_STATE_CHANGED))
                ?.tetheredIfaces
        if (Build.VERSION.SDK_INT >= 30) TetherType.listener[this] = this::updateTile
        updateTile()
    }

    override fun onStopListening() {
        if (Build.VERSION.SDK_INT >= 30) TetherType.listener -= this
        unregisterReceiver(receiver)
        stopAndUnbind(this)
        super.onStopListening()
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        binder = service as TetheringService.Binder
        service.routingsChanged[this] = this::updateTile
        super.onServiceConnected(name, service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        binder?.routingsChanged?.remove(this)
        binder = null
    }

    override fun updateTile() {
        qsTile?.run {
            subtitle(null)
            val interested = interested
            when {
                interested == null -> {
                    state = Tile.STATE_UNAVAILABLE
                    icon = tileOff
                }
                interested.isEmpty() -> {
                    state = Tile.STATE_INACTIVE
                    icon = tileOff
                }
                else -> {
                    val binder = binder ?: return
                    state = Tile.STATE_ACTIVE
                    icon = if (interested.all(binder::isActive)) tileOn else tileOff
                    subtitleDevices(interested::contains)
                }
            }
            label = getText(labelString)
            updateTile()
        }
    }

    override fun onClick() {
        val interested = interested ?: return
        if (interested.isEmpty()) start() else {
            val binder = binder
            if (binder == null) tapPending = true else {
                val inactive = interested.filterNot(binder::isActive)
                if (inactive.isEmpty()) try {
                    stop()
                } catch (e: Exception) {
                    onException(e)
                    dismiss()
                } else {
                    TetheringService.dismissHandle = dismissHandle
                    startForegroundServiceCompat(Intent(this, TetheringService::class.java)
                        .putExtra(TetheringService.EXTRA_ADD_INTERFACES, inactive.toTypedArray()))
                }
            }
        }
    }

    override fun onTetheringStarted() = updateTile()
    override fun onTetheringFailed(error: Int?) {
        Timber.d("onTetheringFailed: $error")
        if (error != null) GlobalScope.launch(Dispatchers.Main.immediate) {
            dismiss()
            Toast.makeText(this@TetheringTileService, TetheringManagerCompat.tetherErrorLookup(error),
                Toast.LENGTH_LONG).show()
        }
        updateTile()
    }
    override fun onStopTetheringSucceeded() = updateTile()
    override fun onStopTetheringFailed(error: Int) {
        Timber.d("onStopTetheringFailed: $error")
        GlobalScope.launch(Dispatchers.Main.immediate) {
            dismiss()
            Toast.makeText(this@TetheringTileService, TetheringManagerCompat.tetherErrorLookup(error),
                Toast.LENGTH_LONG).show()
        }
        updateTile()
    }
    override fun onException(e: Exception) {
        super<TetheringManagerCompat.StartTetheringCallback>.onException(e)
        GlobalScope.launch(Dispatchers.Main.immediate) {
            dismiss()
            Toast.makeText(this@TetheringTileService, e.readableMessage, Toast.LENGTH_LONG).show()
        }
    }

    class Wifi : TetheringTileService() {
        override val labelString get() = R.string.tethering_manage_wifi
        override val tetherType get() = TetherType.WIFI
        override val icon get() = R.drawable.ic_device_wifi_tethering

        override fun start() = TetheringManagerCompat.startTethering(TetheringManager.TETHERING_WIFI, true, this)
        override fun stop() = TetheringManagerCompat.stopTethering(TetheringManager.TETHERING_WIFI, this)
    }
    class Usb : TetheringTileService() {
        override val labelString get() = R.string.tethering_manage_usb
        override val tetherType get() = TetherType.USB

        override fun start() = TetheringManagerCompat.startTethering(TetheringManagerCompat.TETHERING_USB, true, this)
        override fun stop() = TetheringManagerCompat.stopTethering(TetheringManagerCompat.TETHERING_USB, this)
    }
    class Bluetooth : TetheringTileService() {
        private var tethering: BluetoothTethering? = null

        override val labelString get() = R.string.tethering_manage_bluetooth
        override val tetherType get() = TetherType.BLUETOOTH

        override fun start() = tethering!!.start(this, this)
        override fun stop() {
            tethering!!.stop(this)
            onTetheringStarted()    // force flush state
        }

        override fun onStartListening() {
            tethering = getSystemService<BluetoothManager>()?.adapter?.let {
                BluetoothTethering(this, it) { updateTile() }
            }
            super.onStartListening()
        }
        override fun onStopListening() {
            super.onStopListening()
            tethering?.close()
            tethering = null
        }

        override fun updateTile() {
            qsTile?.run {
                subtitle(null)
                val interested = interested
                if (interested == null) {
                    state = Tile.STATE_UNAVAILABLE
                    icon = tileOff
                } else when (tethering?.active) {
                    true -> {
                        val binder = binder ?: return
                        state = Tile.STATE_ACTIVE
                        icon = if (interested.isNotEmpty() && interested.all(binder::isActive)) tileOn else tileOff
                        subtitleDevices(interested::contains)
                    }
                    false -> {
                        state = Tile.STATE_INACTIVE
                        icon = tileOff
                    }
                    null -> {
                        state = Tile.STATE_INACTIVE
                        icon = tileOff
                        subtitle(tethering?.activeFailureCause?.readableMessage)
                    }
                }
                label = getText(labelString)
                updateTile()
            }
        }

        override fun onClick() {
            val tethering = tethering
            if (tethering == null) tapPending = true else when (tethering.active) {
                true -> {
                    val binder = binder
                    if (binder == null) tapPending = true else {
                        val inactive = (interested ?: return).filterNot(binder::isActive)
                        if (inactive.isEmpty()) try {
                            stop()
                        } catch (e: Exception) {
                            onException(e)
                            dismiss()
                        } else {
                            TetheringService.dismissHandle = dismissHandle
                            startForegroundServiceCompat(Intent(this, TetheringService::class.java)
                                .putExtra(TetheringService.EXTRA_ADD_INTERFACES, inactive.toTypedArray()))
                        }
                    }
                }
                false -> start()
                else -> ManageBar.start(this::runActivity)
            }
        }
    }
    @RequiresApi(30)
    class Ethernet : TetheringTileService() {
        override val labelString get() = R.string.tethering_manage_ethernet
        override val tetherType get() = TetherType.ETHERNET

        override fun start() = TetheringManagerCompat.startTethering(TetheringManagerCompat.TETHERING_ETHERNET, true,
            this)
        override fun stop() = TetheringManagerCompat.stopTethering(TetheringManagerCompat.TETHERING_ETHERNET, this)
    }
}
