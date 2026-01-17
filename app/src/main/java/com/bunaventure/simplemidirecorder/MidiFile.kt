package com.bunaventure.simplemidirecorder

import java.io.OutputStream

private const val PPQ = 480 // Use a higher PPQ for better timing resolution

fun writeMidiFile(outputStream: OutputStream, midiEvents: List<MidiEvent>) {
    // MIDI File Header
    outputStream.write("MThd".toByteArray())
    outputStream.write(byteArrayOf(0, 0, 0, 6)) // Header length
    outputStream.write(byteArrayOf(0, 0)) // Format 0 (single track)
    outputStream.write(byteArrayOf(0, 1)) // Number of tracks
    outputStream.write(byteArrayOf((PPQ shr 8).toByte(), PPQ.toByte())) // Ticks per quarter note (PPQ)

    // Track Header
    outputStream.write("MTrk".toByteArray())

    val trackData = createTrackData(midiEvents)
    outputStream.write(intToByteArray(trackData.size))
    outputStream.write(trackData)
}

fun createTrackData(midiEvents: List<MidiEvent>): ByteArray {
    val trackData = mutableListOf<Byte>()
    var lastTimestamp: Long = 0
    // Using a standard tempo of 120 bpm
    val nsPerQuarterNote = 500_000_000L

    // Add Tempo meta-event (0xFF 0x51 0x03 tttttt) for 120 BPM
    // tttttt is microseconds per quarter-note. 500,000 us for 120 bpm.
    val tempo = 500000
    trackData.addAll(writeVariableLengthQuantity(0).toList()) // delta-time 0
    trackData.add(0xFF.toByte())
    trackData.add(0x51.toByte())
    trackData.add(0x03.toByte())
    trackData.add((tempo shr 16 and 0xFF).toByte())
    trackData.add((tempo shr 8 and 0xFF).toByte())
    trackData.add((tempo and 0xFF).toByte())

    val sortedEvents = midiEvents.sortedBy { it.timestamp }

    sortedEvents.forEach { event ->
        val deltaTime = event.timestamp - lastTimestamp
        // More accurate tick calculation by performing multiplication before division
        val deltaTimeInTicks = (deltaTime * PPQ) / nsPerQuarterNote
        trackData.addAll(writeVariableLengthQuantity(deltaTimeInTicks).toList())
        trackData.addAll(event.data.toList())
        lastTimestamp = event.timestamp
    }

    // Add End of Track meta event (0xFF 0x2F 0x00) with a delta-time of 0.
    trackData.add(0x00.toByte())
    trackData.add(0xFF.toByte())
    trackData.add(0x2F.toByte())
    trackData.add(0x00.toByte())

    return trackData.toByteArray()
}

private fun writeVariableLengthQuantity(value: Long): ByteArray {
    if (value == 0L) return byteArrayOf(0)
    var v = value
    val byteList = mutableListOf<Byte>()
    do {
        var byte = (v and 0x7F).toByte()
        v = v shr 7
        if (byteList.isNotEmpty()) {
            byte = (byte.toInt() or 0x80).toByte()
        }
        byteList.add(byte)
    } while (v > 0)

    return byteList.reversed().toByteArray()
}

private fun intToByteArray(value: Int): ByteArray {
    return byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )
}

fun ByteArray.toHexDebugString(): String {
    return joinToString(" ") { String.format("%02X", it) }
}
