@file:Suppress("DEPRECATION", "AssignedValueIsNeverRead")

package com.bunaventure.simplemidirecorder

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.bunaventure.simplemidirecorder.ui.theme.ButtonGrey
import com.bunaventure.simplemidirecorder.ui.theme.DarkerLightOrange
import com.bunaventure.simplemidirecorder.ui.theme.DarkerRecordRed
import com.bunaventure.simplemidirecorder.ui.theme.LightOrange
import com.bunaventure.simplemidirecorder.ui.theme.RecordGreen
import com.bunaventure.simplemidirecorder.ui.theme.RecordRed
import com.bunaventure.simplemidirecorder.ui.theme.SaveBlue
import com.bunaventure.simplemidirecorder.ui.theme.SimpleMidiRecorderTheme
import com.bunaventure.simplemidirecorder.ui.theme.TextGrey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.net.toUri

data class MidiEvent(val data: ByteArray, val timestamp: Long) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MidiEvent
        if (!data.contentEquals(other.data)) return false
        if (timestamp != other.timestamp) return false
        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

class MainActivity : ComponentActivity() {

    private val isRecording = mutableStateOf(false)
    private val isPlaying = mutableStateOf(false)
    private val isDeviceConnected = mutableStateOf(false)
    private val hasDataToSave = mutableStateOf(false)
    private val hasMidiInput = mutableStateOf(false)

    private var midiManager: MidiManager? = null
    private var recordDevice: MidiDevice? = null
    private var synthDevice: MidiDevice? = null
    private var recordOutputPort: MidiOutputPort? = null
    private var synthInputPort: MidiInputPort? = null
    private val midiEvents = mutableListOf<MidiEvent>()
    private var midiReceiver: MidiRecorderReceiver? = null
    private var playbackJob: Job? = null

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo?) {
            updateDeviceState()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo?) {
            updateDeviceState()
        }
    }

    private val createMidiFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/midi")) { uri ->
        uri?.let {
            saveMidiData(it)
            hasDataToSave.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        setContent {
            SimpleMidiRecorderTheme {
                SimpleMidiRecorderApp(
                    isRecording = isRecording.value,
                    isPlaying = isPlaying.value,
                    isDeviceConnected = isDeviceConnected.value,
                    hasDataToSave = hasDataToSave.value,
                    hasMidiInput = hasMidiInput.value,
                    onToggleRecording = ::toggleRecording,
                    onTogglePlayback = ::togglePlayback,
                    onSaveMidiFile = ::saveMidiFile
                )
            }
        }

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            midiManager = getSystemService("midi") as MidiManager
            midiManager?.registerDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            updateDeviceState()
            requestMidiPermission()
        } else {
            Toast.makeText(this, "MIDI not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        midiManager?.unregisterDeviceCallback(deviceCallback)
        stopRecording()
        stopPlayback()
    }

    private fun updateDeviceState() {
        val devices = midiManager?.devices
        isDeviceConnected.value = devices?.any { it.outputPortCount > 0 } == true
        if (!isDeviceConnected.value && isRecording.value) {
            stopRecording()
        }
    }

    private fun toggleRecording() {
        if (isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun togglePlayback() {
        if (isPlaying.value) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    private fun startRecording() {
        if (!isDeviceConnected.value) {
            Toast.makeText(this, getString(R.string.toast_connect_midi_device), Toast.LENGTH_SHORT).show()
            return
        }
        if (isPlaying.value) stopPlayback()
        hasDataToSave.value = false
        hasMidiInput.value = false
        midiEvents.clear()

        val deviceInfo = midiManager?.devices?.firstOrNull { it.outputPortCount > 0 }
        if (deviceInfo != null) {
            midiManager?.openDevice(deviceInfo, { device ->
                recordDevice = device
                recordOutputPort = device.openOutputPort(0)
                midiReceiver = MidiRecorderReceiver(midiEvents) { hasMidiInput.value = true }
                recordOutputPort?.connect(midiReceiver!!)
                isRecording.value = true
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun stopRecording() {
        midiReceiver?.let {
            recordOutputPort?.disconnect(it)
        }
        recordOutputPort?.close()
        recordDevice?.close()
        isRecording.value = false
        if (midiEvents.isNotEmpty()) {
            hasDataToSave.value = true
        }
    }

    private fun startPlayback() {
        if (!hasDataToSave.value) {
            Toast.makeText(this, getString(R.string.toast_nothing_to_playback), Toast.LENGTH_SHORT).show()
            return
        }

        val synthInfo = midiManager?.devices?.firstOrNull { it.inputPortCount > 0 } // Find a synthesizer
        if (synthInfo == null) {
            Toast.makeText(this, getString(R.string.toast_no_synthesizer_found), Toast.LENGTH_SHORT).show()
            return
        }

        midiManager?.openDevice(synthInfo, { device ->
            synthDevice = device
            synthInputPort = device.openInputPort(0)
            if (synthInputPort == null) {
                Toast.makeText(this, getString(R.string.toast_could_not_open_synthesizer), Toast.LENGTH_SHORT).show()
                return@openDevice
            }

            playbackJob = lifecycleScope.launch {
                try {
                    isPlaying.value = true

                    // Send Program Change to select Acoustic Grand Piano (Program 0)
                    val programChange = byteArrayOf(0xC0.toByte(), 0)
                    synthInputPort?.send(programChange, 0, programChange.size)

                    val playbackStartTime = System.nanoTime()
                    for (event in midiEvents) {
                        val eventTime = playbackStartTime + event.timestamp
                        val delayMs = (eventTime - System.nanoTime()) / 1_000_000
                        if (delayMs > 0) {
                            delay(delayMs)
                        }
                        synthInputPort?.send(event.data, 0, event.data.size)
                    }
                    delay(500) // Wait a moment for last notes to fade
                } finally {
                    stopPlayback()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun stopPlayback() {
        if (!isPlaying.value) return
        isPlaying.value = false

        playbackJob?.cancel()
        playbackJob = null

        // Send "All Notes Off" to all channels to prevent stuck notes
        for (i in 0..15) {
            val allNotesOff = byteArrayOf((0xB0 + i).toByte(), 123.toByte(), 0)
            synthInputPort?.send(allNotesOff, 0, 3)
        }

        synthInputPort?.close()
        synthDevice?.close()
    }

    private fun saveMidiFile() {
        if (hasDataToSave.value) {
            val sdf = SimpleDateFormat("HHmmss", Locale.getDefault())
            val currentTime = sdf.format(Date())
            val fileName = "rec-$currentTime.mid"
            createMidiFileLauncher.launch(fileName)
        } else if (!isRecording.value) {
            Toast.makeText(this, getString(R.string.toast_nothing_to_save), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveMidiData(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use {
                writeMidiFile(it, midiEvents)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestMidiPermission() {
        if (ActivityCompat.checkSelfPermission(
                this,
                "android.permission.MIDI"
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf("android.permission.MIDI"), 1)
        }
    }
}

private fun getMidiMessageLength(status: Byte): Int {
    return when (status.toInt() and 0xF0) {
        0x80, 0x90, 0xA0, 0xB0, 0xE0 -> 3
        0xC0, 0xD0 -> 2
        else -> when (status.toInt() and 0xFF) {
            0xF1, 0xF3 -> 2
            0xF2 -> 3
            else -> 1 // Includes system real-time messages
        }
    }
}

class MidiRecorderReceiver(private val midiEvents: MutableList<MidiEvent>, private val onMidiInput: () -> Unit) : MidiReceiver() {
    private var startTime: Long = -1
    private var hasFiredCallback = false

    override fun onSend(msg: ByteArray?, offset: Int, count: Int, timestamp: Long) {
        val buffer = msg ?: return
        var i = offset
        val end = offset + count

        while (i < end) {
            val status = buffer[i]

            if ((status.toInt() and 0xFF) == 0xFE) {
                i++
                continue
            }

            val messageLength = getMidiMessageLength(status)
            if (i + messageLength > end) {
                break
            }

            if (startTime < 0) {
                startTime = timestamp
            }

            if (!hasFiredCallback) {
                onMidiInput()
                hasFiredCallback = true
            }

            val eventData = buffer.sliceArray(i until i + messageLength)
            midiEvents.add(MidiEvent(eventData, timestamp - startTime))

            i += messageLength
        }
    }
}

@Composable
private fun HelpDialog(onDismissRequest: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.content_description_help)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Circle, contentDescription = null, tint = RecordGreen, modifier = Modifier.size(24.dp).padding(end = 8.dp))
                    Text(stringResource(R.string.help_ready_to_record))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Circle, contentDescription = null, tint = RecordRed, modifier = Modifier.size(24.dp).padding(end = 8.dp))
                    Text(stringResource(R.string.help_recording_in_progress))
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = LightOrange, modifier = Modifier.size(24.dp).padding(end = 8.dp))
                    Text(stringResource(R.string.help_playback_recording))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Stop, contentDescription = null, tint = LightOrange, modifier = Modifier.size(24.dp).padding(end = 8.dp))
                    Text(stringResource(R.string.help_stop_playback))
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Download, contentDescription = null, tint = SaveBlue, modifier = Modifier.size(24.dp).padding(end = 8.dp))
                    Text(stringResource(R.string.help_export_recording))
                }
                Spacer(modifier = Modifier.height(30.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val context = LocalContext.current
                    val annotatedString = buildAnnotatedString {
                        withStyle(style = SpanStyle(fontStyle = FontStyle.Italic, color = TextGrey, fontSize = 10.sp)) {
                            append(stringResource(R.string.developed_by) + " ")
                            pushStringAnnotation(tag = "URL", annotation = stringResource(R.string.developer_link))
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(stringResource(R.string.developer_name))
                            }
                            pop()
                        }
                    }
                    ClickableText(
                        text = annotatedString,
                        onClick = {
                                offset ->
                            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()?.let {
                                    annotation ->
                                val intent = Intent(Intent.ACTION_VIEW, annotation.item.toUri())
                                startActivity(context, intent, null)
                            }
                        }
                    )
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun SimpleMidiRecorderApp(
    isRecording: Boolean,
    isPlaying: Boolean,
    isDeviceConnected: Boolean,
    hasDataToSave: Boolean,
    hasMidiInput: Boolean,
    onToggleRecording: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSaveMidiFile: () -> Unit
) {
    val configuration = LocalConfiguration.current
    var showHelpDialog by remember { mutableStateOf(false) }

    if (showHelpDialog) {
        HelpDialog(onDismissRequest = { showHelpDialog = false })
    }

    val recordButtonColor = if (isRecording && hasMidiInput) {
        var targetColor by remember { mutableStateOf(RecordRed) }
        val breathingColor by animateColorAsState(
            targetValue = targetColor,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            )
        )
        LaunchedEffect(Unit) {
            targetColor = DarkerRecordRed
        }
        breathingColor
    } else {
        when {
            isRecording -> RecordRed
            isDeviceConnected -> RecordGreen
            else -> ButtonGrey
        }
    }

    val playButtonColor = if (isPlaying) {
        var targetColor by remember { mutableStateOf(LightOrange) }
        val breathingColor by animateColorAsState(
            targetValue = targetColor,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            )
        )
        LaunchedEffect(Unit) {
            targetColor = DarkerLightOrange
        }
        breathingColor
    } else {
        if (hasDataToSave && !isRecording) LightOrange else ButtonGrey
    }

    val saveButtonEnabled = hasDataToSave && !isRecording

    val buttonModifier = Modifier
        .size(150.dp)
        .aspectRatio(1f)

    Scaffold {
            innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            IconButton(onClick = { showHelpDialog = true }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Help, contentDescription = stringResource(R.string.content_description_help), tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(29.dp))
            }
            if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RecordButton(onClick = onToggleRecording, modifier = buttonModifier, color = recordButtonColor)
                    Spacer(modifier = Modifier.width(32.dp))
                    PlayButton(onClick = onTogglePlayback, modifier = buttonModifier, color = playButtonColor, isPlaying = isPlaying)
                    Spacer(modifier = Modifier.width(32.dp))
                    SaveButton(onClick = onSaveMidiFile, modifier = buttonModifier, enabled = saveButtonEnabled)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RecordButton(onClick = onToggleRecording, modifier = buttonModifier, color = recordButtonColor)
                    Spacer(modifier = Modifier.height(32.dp))
                    PlayButton(onClick = onTogglePlayback, modifier = buttonModifier, color = playButtonColor, isPlaying = isPlaying)
                    Spacer(modifier = Modifier.height(32.dp))
                    SaveButton(onClick = onSaveMidiFile, modifier = buttonModifier, enabled = saveButtonEnabled)
                }
            }
        }
    }
}

@Composable
private fun RecordButton(
    onClick: () -> Unit,
    modifier: Modifier,
    color: Color
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(24.dp)
    ) {
        Icon(
            Icons.Rounded.Circle,
            contentDescription = "Record",
            modifier = Modifier.size(72.dp),
            tint = Color.White
        )
    }
}

@Composable
private fun PlayButton(
    onClick: () -> Unit,
    modifier: Modifier,
    color: Color,
    isPlaying: Boolean
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = color
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Icon(
            if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = "Play",
            modifier = Modifier.size(96.dp),
            tint = Color.White
        )
    }
}


@Composable
private fun SaveButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (enabled) SaveBlue else ButtonGrey
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Icon(
            Icons.Rounded.Download,
            contentDescription = "Save",
            modifier = Modifier.size(72.dp),
            tint = Color.White
        )
    }
}
