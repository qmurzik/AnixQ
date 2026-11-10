package ru.qmods.anixq.tv.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.util.UUID

/**
 * "The phone is next to the TV" over Bluetooth LE, without Wi-Fi.
 * The TV sends a tiny beacon: the AnixQ service id plus 6 bytes derived from the paired channel, so a phone
 * recognises only its own TV and nobody nearby learns anything (the channel itself never leaves the device).
 */
object Nearby {
    /** A 16-bit id: a full 128-bit one plus the tag would not fit the 31 bytes of a classic BLE advertisement. */
    val SERVICE: ParcelUuid = ParcelUuid(UUID.fromString("0000a7e1-0000-1000-8000-00805f9b34fb"))

    /** The beacon payload for this account's TV. */
    fun tag(channel: String = Store.remoteChannel): ByteArray? {
        if (channel.isEmpty()) return null
        return MessageDigest.getInstance("SHA-256").digest("anixq-near:$channel".toByteArray()).copyOf(6)
    }

    // MARK: TV side

    enum class Beacon(val text: String) {
        OFF("выключен"), ON("работает: телефон узнает приставку рядом"),
        NO_HARDWARE("эта приставка не умеет передавать Bluetooth-метку"),
        BT_OFF("Bluetooth на приставке выключен"), NO_PERMISSION("нужно разрешение «Устройства поблизости»"),
        NOT_PAIRED("сначала подключите телефон"), FAILED("не удалось включить")
    }

    val beacon = MutableStateFlow(Beacon.OFF)
    private var advertising: AdvertiseCallback? = null

    fun advertisePermission(): String? = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_ADVERTISE else null

    @SuppressLint("MissingPermission")
    fun advertise(context: Context) {
        val tag = tag() ?: run { beacon.value = Beacon.NOT_PAIRED; return }
        val perm = advertisePermission()
        if (perm != null && context.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) { beacon.value = Beacon.NO_PERMISSION; return }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: run { beacon.value = Beacon.NO_HARDWARE; return }
        if (!adapter.isEnabled) { beacon.value = Beacon.BT_OFF; return }
        if (!adapter.isMultipleAdvertisementSupported) { beacon.value = Beacon.NO_HARDWARE; return }
        val adv = adapter.bluetoothLeAdvertiser ?: run { beacon.value = Beacon.NO_HARDWARE; return }
        runCatching { advertising?.let { adv.stopAdvertising(it) } }
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { beacon.value = Beacon.ON }
            override fun onStartFailure(errorCode: Int) {
                beacon.value = if (errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED) Beacon.NO_HARDWARE
                    else if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) Beacon.ON else Beacon.FAILED
            }
        }
        advertising = cb
        // Balanced (~4 times a second) at full power: the phone hears it within seconds, even through a wall.
        // The box is plugged in, so the cost doesn't matter.
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).setConnectable(false).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(SERVICE).addServiceData(SERVICE, tag).build()
        runCatching { adv.startAdvertising(settings, data, cb) }.onFailure { beacon.value = Beacon.FAILED }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising(context: Context) {
        val cb = advertising ?: return
        advertising = null
        runCatching { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
        beacon.value = Beacon.OFF
    }

    // MARK: phone side

    /** Background scanning that survives the app being closed exists from Android 12 (no location permission needed there). */
    val canScan get() = Build.VERSION.SDK_INT >= 31

    fun scanPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN) else emptyArray()

    fun hasScanPermission(context: Context) = scanPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Ask the system to watch for our TV with the screen off and the app closed: matches arrive at [intent]
     * (a broadcast). Low-power mode and a hardware filter on the service id — the battery cost is negligible.
     */
    @SuppressLint("MissingPermission")
    fun startScan(context: Context, intent: PendingIntent): Boolean {
        if (!canScan || !hasScanPermission(context)) return false
        val tag = tag() ?: return false
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder().setServiceData(SERVICE, tag).build()
        // Balanced: the system listens about 40% of the time instead of 10% — seconds instead of a minute.
        // Matching runs in the Bluetooth chip (hardware filter), so the phone isn't woken for other devices.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build()
        // Restarted each time: Android quietly downgrades a scan that has run for 30 minutes.
        runCatching { scanner.stopScan(intent) }
        return runCatching { scanner.startScan(listOf(filter), settings, intent) == 0 }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    fun stopScan(context: Context, intent: PendingIntent) {
        if (!canScan || !hasScanPermission(context)) return
        runCatching { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(intent) }
    }

    /** Is this an episode worth offering on the TV: paused recently, not finished. */
    fun candidate(): WatchEntry? {
        val e = Store.continueList.firstOrNull() ?: return null
        val ageMin = (System.currentTimeMillis() / 1000.0 - e.at) / 60
        if (ageMin > 6 * 60 || ageMin < 10.0 / 60) return null
        if (e.dur > 0 && (e.progress > 0.92 || e.pos < 30)) return null
        return e
    }
}
