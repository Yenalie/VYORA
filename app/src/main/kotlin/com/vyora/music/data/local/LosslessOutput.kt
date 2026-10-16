/*
 * Ported from BitChord (https://github.com/kushagrasinghx/BitChord),
 * app/src/main/java/com/music/bitchord/playback/audio/LosslessOutput.kt — GPL-3.0.
 * Changes for VYORA: no Bluetooth codec tracker (the codec is reported as unknown instead of read through
 * hidden A2DP APIs), plus the current output device's name for display.
 */
package com.vyora.music.data.local

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone has an output connected right now that can carry lossless audio — the file's samples reach
 * the listener's converter without being re-encoded into a lossy format first.
 *
 *  - Wired: 3.5mm headphones or headset, analog/digital line out, aux.
 *  - USB: USB-C headphones, USB DACs and USB audio accessories.
 *  - HDMI: HDMI, ARC and eARC.
 *  - Dock: digital and analog docks.
 *  - Bluetooth A2DP only on LDAC, LHDC, aptX Lossless or Samsung SSC UHQ; SBC, AAC, aptX (HD), LC3 and Opus are lossy.
 *
 * The phone speaker, earpiece, hearing aids, casting and BLE Audio do not count.
 */
object LosslessOutput {

    enum class Kind { WIRED, USB, HDMI, DOCK, BLUETOOTH, SPEAKER }

    data class State(
        val capable: Boolean = false,
        /** What qualified, when something did. */
        val via: Kind? = null,
        /** The Bluetooth codec in use, when known. */
        val bluetoothCodec: String? = null,
        /** A Bluetooth output is connected but its codec could not be read. */
        val bluetoothCodecUnknown: Boolean = false,
        /** Product name of the active output, for display ("USB-C DAC", "WH-1000XM5"…). */
        val deviceName: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val capable: Boolean get() = _state.value.capable

    private var audioManager: AudioManager? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun init(context: Context) {
        if (audioManager != null) return
        val manager = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().filter { it.isSink }
        val types = devices.map { it.type }.toSet()
        val decided = decide(types, null)
        val active = devices.firstOrNull { d ->
            when (decided.via) {
                Kind.WIRED -> d.type in WIRED
                Kind.USB -> d.type in USB
                Kind.HDMI -> d.type in HDMI
                Kind.DOCK -> d.type in DOCK
                else -> d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        }
        val name = active?.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
        _state.value = decided.copy(deviceName = name)
    }

    /** The decision from the connected output types and a Bluetooth codec name (null if unknown). Pure, for tests. */
    internal fun decide(outputTypes: Set<Int>, bluetoothCodec: String?, bluetoothSampleRateHz: Int? = null): State {
        val cabled = when {
            outputTypes.any { it in WIRED } -> Kind.WIRED
            outputTypes.any { it in USB } -> Kind.USB
            outputTypes.any { it in HDMI } -> Kind.HDMI
            outputTypes.any { it in DOCK } -> Kind.DOCK
            else -> null
        }
        if (cabled != null) return State(capable = true, via = cabled, bluetoothCodec = bluetoothCodec)
        if (AudioDeviceInfo.TYPE_BLUETOOTH_A2DP !in outputTypes) return State(via = Kind.SPEAKER)
        if (bluetoothCodec == null) return State(bluetoothCodecUnknown = true)
        val lossless = isLosslessBluetoothCodec(bluetoothCodec, bluetoothSampleRateHz)
        return State(capable = lossless, via = Kind.BLUETOOTH.takeIf { lossless }, bluetoothCodec = bluetoothCodec)
    }

    /** LDAC, any LHDC generation, aptX Lossless or SSC UHQ, by the name Android reports. */
    internal fun isLosslessBluetoothCodec(name: String, sampleRateHz: Int? = null): Boolean {
        val codec = name.uppercase(Locale.ROOT)
        if ("LDAC" in codec || "LHDC" in codec || ("APTX" in codec && "LOSSLESS" in codec)) return true
        if ("UHQ" in codec) return true
        val samsung = SAMSUNG_NAMES.any { it in codec }
        return samsung && sampleRateHz != null && sampleRateHz >= 88_200
    }

    private val SAMSUNG_NAMES = listOf("SSC", "SAMSUNG", "SCALABLE", "SEAMLESS")

    private val WIRED = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE,
    )
    private val USB = setOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )
    private val HDMI = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        29, // TYPE_HDMI_EARC, API 31
    )
    private val DOCK = setOf(
        AudioDeviceInfo.TYPE_DOCK,
        31, // TYPE_DOCK_ANALOG, API 34
    )
}
