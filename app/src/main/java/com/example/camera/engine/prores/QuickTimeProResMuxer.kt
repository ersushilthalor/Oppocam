package com.example.camera.engine.prores

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * High-performance, compliant QuickTime MOV container muxer for Apple ProRes 422.
 *
 * Writes:
 * - 'ftyp' with major brand 'qt  '
 * - 'mdat' with frame samples and stereo PCM audio
 * - 'moov' atom containing:
 *   - 'trak' for video with 'apcn' (Apple ProRes 422) sample description
 *   - 'trak' for audio with 'sowt' (16-bit little-endian uncompressed PCM 48kHz stereo)
 */
class QuickTimeProResMuxer(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val isAudioEnabled: Boolean = true
) {
    private var raf: RandomAccessFile? = null
    private var mdatStartOffset = 0L
    private var currentMdatSize = 0L

    private val videoSampleSizes = mutableListOf<Int>()
    private val videoSampleOffsets = mutableListOf<Long>()

    private val audioSampleSizes = mutableListOf<Int>()
    private val audioSampleOffsets = mutableListOf<Long>()
    private var totalAudioFrames = 0L

    private val timeScale = 24000 // Standard high-precision cinema time scale
    private val frameDuration = timeScale / fps

    fun start() {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        outputFile.createNewFile()

        val r = RandomAccessFile(outputFile, "rw")
        raf = r

        // 1. Write 'ftyp' atom (24 bytes)
        val ftyp = ByteBuffer.allocate(24)
        ftyp.putInt(24) // size
        ftyp.putInt(0x66747970) // 'ftyp'
        ftyp.putInt(0x71742020) // 'qt  '
        ftyp.putInt(0x00000200) // minor version 512
        ftyp.putInt(0x71742020) // compatible brands: 'qt  '
        ftyp.putInt(0)
        r.write(ftyp.array())

        // 2. Write placeholder 64-bit 'mdat' header (16 bytes)
        mdatStartOffset = r.filePointer
        val mdatHdr = ByteBuffer.allocate(16)
        mdatHdr.putInt(1) // 1 means 64-bit large size follows in next 8 bytes
        mdatHdr.putInt(0x6D646174) // 'mdat'
        mdatHdr.putLong(16L) // placeholder size
        r.write(mdatHdr.array())
        currentMdatSize = 16L
    }

    @Synchronized
    fun writeVideoFrame(proresFrame: ByteArray) {
        val r = raf ?: return
        val offset = r.filePointer
        r.write(proresFrame)
        videoSampleOffsets.add(offset)
        videoSampleSizes.add(proresFrame.size)
        currentMdatSize += proresFrame.size
    }

    @Synchronized
    fun writeAudioChunk(pcmData: ByteArray, length: Int) {
        if (!isAudioEnabled || length <= 0) return
        val r = raf ?: return
        val offset = r.filePointer
        r.write(pcmData, 0, length)
        audioSampleOffsets.add(offset)
        audioSampleSizes.add(length)
        currentMdatSize += length
        totalAudioFrames += (length / 4) // 4 bytes per stereo 16-bit frame (2 ch * 2 bytes)
    }

    @Synchronized
    fun finish() {
        val r = raf ?: return
        try {
            // 1. Update 64-bit 'mdat' size at mdatStartOffset
            val mdatPayloadSize = currentMdatSize
            r.seek(mdatStartOffset + 8)
            r.writeLong(mdatPayloadSize)

            // 2. Seek to end of mdat to append 'moov' atom
            r.seek(mdatStartOffset + currentMdatSize)

            val videoDuration = (videoSampleSizes.size * frameDuration).toLong()
            val totalDuration = maxOf(videoDuration, (totalAudioFrames * timeScale) / 48000L)

            // Build 'moov' atom in memory
            val moovBytes = buildMoovAtom(totalDuration, videoDuration)
            r.write(moovBytes)
        } finally {
            try { r.close() } catch (ignored: Exception) {}
            raf = null
        }
    }

    private fun buildMoovAtom(totalDuration: Long, videoDuration: Long): ByteArray {
        val moovStream = java.io.ByteArrayOutputStream(65536)

        // 1. mvhd (Movie Header Atom, 108 bytes)
        val mvhd = ByteBuffer.allocate(108)
        mvhd.putInt(108)
        mvhd.putInt(0x6D766864) // 'mvhd'
        mvhd.put(0) // version
        mvhd.put(0); mvhd.put(0); mvhd.put(0) // flags
        mvhd.putInt(0) // creation time
        mvhd.putInt(0) // modification time
        mvhd.putInt(timeScale) // time scale
        mvhd.putInt(totalDuration.toInt()) // duration
        mvhd.putInt(0x00010000) // rate 1.0 (fixed 16.16)
        mvhd.putShort(0x0100) // volume 1.0 (fixed 8.8)
        mvhd.putShort(0) // reserved
        mvhd.putInt(0); mvhd.putInt(0) // reserved
        // Identity matrix (36 bytes)
        val matrix = intArrayOf(
            0x00010000, 0, 0,
            0, 0x00010000, 0,
            0, 0, 0x40000000
        )
        for (m in matrix) mvhd.putInt(m)
        // Pre-defined (24 bytes)
        for (i in 0 until 6) mvhd.putInt(0)
        mvhd.putInt(if (isAudioEnabled) 3 else 2) // next track ID
        moovStream.write(mvhd.array())

        // 2. Video 'trak'
        val videoTrakBytes = buildVideoTrak(videoDuration)
        moovStream.write(videoTrakBytes)

        // 3. Audio 'trak' if audio is present
        if (isAudioEnabled && audioSampleSizes.isNotEmpty()) {
            val audioTrakBytes = buildAudioTrak()
            moovStream.write(audioTrakBytes)
        }

        val fullPayload = moovStream.toByteArray()
        val totalMoovSize = fullPayload.size + 8
        val outBuf = ByteBuffer.allocate(totalMoovSize)
        outBuf.putInt(totalMoovSize)
        outBuf.putInt(0x6D6F6F76) // 'moov'
        outBuf.put(fullPayload)
        return outBuf.array()
    }

    private fun buildVideoTrak(videoDuration: Long): ByteArray {
        val trakStream = java.io.ByteArrayOutputStream(32768)

        // tkhd (Track Header Atom, 92 bytes)
        val tkhd = ByteBuffer.allocate(92)
        tkhd.putInt(92)
        tkhd.putInt(0x746B6864) // 'tkhd'
        tkhd.put(0) // version
        tkhd.put(0); tkhd.put(0); tkhd.put(0x0F) // flags: TrackEnabled | TrackInMovie | TrackInPreview
        tkhd.putInt(0) // creation time
        tkhd.putInt(0) // mod time
        tkhd.putInt(1) // track id = 1
        tkhd.putInt(0) // reserved
        tkhd.putInt(videoDuration.toInt()) // duration
        tkhd.putInt(0); tkhd.putInt(0) // reserved
        tkhd.putShort(0); tkhd.putShort(0) // layer, alt group
        tkhd.putShort(0); tkhd.putShort(0) // volume (0 for video), reserved
        // Identity matrix
        val matrix = intArrayOf(
            0x00010000, 0, 0,
            0, 0x00010000, 0,
            0, 0, 0x40000000
        )
        for (m in matrix) tkhd.putInt(m)
        tkhd.putInt(width shl 16) // width (fixed 16.16)
        tkhd.putInt(height shl 16) // height (fixed 16.16)
        trakStream.write(tkhd.array())

        // mdia (Media Atom)
        val mdiaBytes = buildVideoMdia(videoDuration)
        trakStream.write(mdiaBytes)

        val trakPayload = trakStream.toByteArray()
        val totalTrakSize = trakPayload.size + 8
        val out = ByteBuffer.allocate(totalTrakSize)
        out.putInt(totalTrakSize)
        out.putInt(0x7472616B) // 'trak'
        out.put(trakPayload)
        return out.array()
    }

    private fun buildVideoMdia(videoDuration: Long): ByteArray {
        val mdiaStream = java.io.ByteArrayOutputStream(16384)

        // mdhd (Media Header Atom, 32 bytes)
        val mdhd = ByteBuffer.allocate(32)
        mdhd.putInt(32)
        mdhd.putInt(0x6D646864) // 'mdhd'
        mdhd.putInt(0) // version + flags
        mdhd.putInt(0); mdhd.putInt(0) // creation, mod
        mdhd.putInt(timeScale) // time scale
        mdhd.putInt(videoDuration.toInt()) // duration
        mdhd.putShort(0x55C4) // language: English
        mdhd.putShort(0) // quality
        mdiaStream.write(mdhd.array())

        // hdlr (Handler Reference Atom, 33 bytes)
        val hdlr = ByteBuffer.allocate(33)
        hdlr.putInt(33)
        hdlr.putInt(0x68646C72) // 'hdlr'
        hdlr.putInt(0) // version + flags
        hdlr.putInt(0) // component type
        hdlr.putInt(0x76696465) // subtype: 'vide'
        hdlr.putInt(0); hdlr.putInt(0); hdlr.putInt(0) // reserved
        hdlr.put(5) // Pascal string len
        hdlr.put("Video".toByteArray())
        mdiaStream.write(hdlr.array())

        // minf (Media Information Atom)
        val minfBytes = buildVideoMinf()
        mdiaStream.write(minfBytes)

        val mdiaPayload = mdiaStream.toByteArray()
        val totalMdiaSize = mdiaPayload.size + 8
        val out = ByteBuffer.allocate(totalMdiaSize)
        out.putInt(totalMdiaSize)
        out.putInt(0x6D646961) // 'mdia'
        out.put(mdiaPayload)
        return out.array()
    }

    private fun buildVideoMinf(): ByteArray {
        val minfStream = java.io.ByteArrayOutputStream(16384)

        // vmhd (Video Media Header, 20 bytes)
        val vmhd = ByteBuffer.allocate(20)
        vmhd.putInt(20)
        vmhd.putInt(0x766D6864) // 'vmhd'
        vmhd.putInt(1) // version 0, flag 1
        vmhd.putShort(0) // graphics mode
        vmhd.putShort(0); vmhd.putShort(0); vmhd.putShort(0) // opcolor
        minfStream.write(vmhd.array())

        // dinf & dref (Data Info, 36 bytes)
        val dinf = ByteBuffer.allocate(36)
        dinf.putInt(36)
        dinf.putInt(0x64696E66) // 'dinf'
        dinf.putInt(28)
        dinf.putInt(0x64726566) // 'dref'
        dinf.putInt(0) // version + flags
        dinf.putInt(1) // entry count = 1
        dinf.putInt(12)
        dinf.putInt(0x616C6973) // 'alis'
        dinf.putInt(1) // self-contained flag
        minfStream.write(dinf.array())

        // stbl (Sample Table Atom)
        val stblBytes = buildVideoStbl()
        minfStream.write(stblBytes)

        val minfPayload = minfStream.toByteArray()
        val totalMinfSize = minfPayload.size + 8
        val out = ByteBuffer.allocate(totalMinfSize)
        out.putInt(totalMinfSize)
        out.putInt(0x6D696E66) // 'minf'
        out.put(minfPayload)
        return out.array()
    }

    private fun buildVideoStbl(): ByteArray {
        val stblStream = java.io.ByteArrayOutputStream(16384)

        // 1. stsd (Sample Description Atom for 'apcn' - Apple ProRes 422 Standard)
        val compressorName = "Apple ProRes 422"
        val stsdLen = 8 + 8 + 78
        val stsd = ByteBuffer.allocate(stsdLen)
        stsd.putInt(stsdLen)
        stsd.putInt(0x73747364) // 'stsd'
        stsd.putInt(0) // version + flags
        stsd.putInt(1) // entry count = 1

        // 'apcn' entry (78 bytes + 8 header = 86 bytes)
        stsd.putInt(86)
        stsd.putInt(0x6170636E) // 'apcn'
        stsd.putInt(0); stsd.putShort(0) // reserved
        stsd.putShort(1) // data ref index = 1
        stsd.putShort(0); stsd.putShort(0) // version, revision
        stsd.putInt(0x6170706C) // vendor 'appl'
        stsd.putInt(0x00000200) // temporal quality
        stsd.putInt(0x00000200) // spatial quality
        stsd.putShort(width.toShort())
        stsd.putShort(height.toShort())
        stsd.putInt(0x00480000) // horiz res 72 dpi
        stsd.putInt(0x00480000) // vert res 72 dpi
        stsd.putInt(0) // data size = 0
        stsd.putShort(1) // frame count = 1
        // Compressor name (32 bytes: 1 byte len + chars + zero pad)
        stsd.put(compressorName.length.toByte())
        stsd.put(compressorName.toByteArray())
        for (i in (compressorName.length + 1) until 32) stsd.put(0)
        stsd.putShort(24) // depth: 24-bit color (standard for ProRes 422)
        stsd.putShort(-1) // color table ID
        stblStream.write(stsd.array())

        // 2. stts (Time-to-Sample Atom: 1 entry for all frames with frameDuration)
        val frameCount = videoSampleSizes.size
        val stts = ByteBuffer.allocate(24)
        stts.putInt(24)
        stts.putInt(0x73747473) // 'stts'
        stts.putInt(0) // version + flags
        stts.putInt(1) // entry count = 1
        stts.putInt(frameCount)
        stts.putInt(frameDuration)
        stblStream.write(stts.array())

        // 3. stss (Sync Sample Table: all intra-frames are keyframes!)
        val stssLen = 16 + frameCount * 4
        val stss = ByteBuffer.allocate(stssLen)
        stss.putInt(stssLen)
        stss.putInt(0x73747373) // 'stss'
        stss.putInt(0)
        stss.putInt(frameCount)
        for (i in 1..frameCount) {
            stss.putInt(i)
        }
        stblStream.write(stss.array())

        // 4. stsc (Sample-to-Chunk Atom: 1 frame per chunk)
        val stsc = ByteBuffer.allocate(28)
        stsc.putInt(28)
        stsc.putInt(0x73747363) // 'stsc'
        stsc.putInt(0)
        stsc.putInt(1) // 1 entry
        stsc.putInt(1) // first chunk
        stsc.putInt(1) // samples per chunk
        stsc.putInt(1) // sample description index
        stblStream.write(stsc.array())

        // 5. stsz (Sample Size Table)
        val stszLen = 20 + frameCount * 4
        val stsz = ByteBuffer.allocate(stszLen)
        stsz.putInt(stszLen)
        stsz.putInt(0x7374737A) // 'stsz'
        stsz.putInt(0)
        stsz.putInt(0) // sample size (0 = variable)
        stsz.putInt(frameCount)
        for (sz in videoSampleSizes) {
            stsz.putInt(sz)
        }
        stblStream.write(stsz.array())

        // 6. stco (Chunk Offset Table: 32-bit chunk offsets)
        val stcoLen = 16 + frameCount * 4
        val stco = ByteBuffer.allocate(stcoLen)
        stco.putInt(stcoLen)
        stco.putInt(0x7374636F) // 'stco'
        stco.putInt(0)
        stco.putInt(frameCount)
        for (off in videoSampleOffsets) {
            stco.putInt(off.toInt())
        }
        stblStream.write(stco.array())

        val stblPayload = stblStream.toByteArray()
        val totalStblSize = stblPayload.size + 8
        val out = ByteBuffer.allocate(totalStblSize)
        out.putInt(totalStblSize)
        out.putInt(0x7374626C) // 'stbl'
        out.put(stblPayload)
        return out.array()
    }

    private fun buildAudioTrak(): ByteArray {
        val trakStream = java.io.ByteArrayOutputStream(16384)
        val audioDuration = (totalAudioFrames * timeScale / 48000L).toInt()

        // tkhd (Audio Track Header)
        val tkhd = ByteBuffer.allocate(92)
        tkhd.putInt(92)
        tkhd.putInt(0x746B6864) // 'tkhd'
        tkhd.put(0) // version
        tkhd.put(0); tkhd.put(0); tkhd.put(0x0F) // flags
        tkhd.putInt(0); tkhd.putInt(0)
        tkhd.putInt(2) // track id = 2
        tkhd.putInt(0)
        tkhd.putInt(audioDuration)
        tkhd.putInt(0); tkhd.putInt(0)
        tkhd.putShort(0); tkhd.putShort(0)
        tkhd.putShort(0x0100) // volume 1.0
        tkhd.putShort(0)
        val matrix = intArrayOf(
            0x00010000, 0, 0,
            0, 0x00010000, 0,
            0, 0, 0x40000000
        )
        for (m in matrix) tkhd.putInt(m)
        tkhd.putInt(0); tkhd.putInt(0)
        trakStream.write(tkhd.array())

        // mdia for audio
        val mdia = ByteBuffer.allocate(32)
        mdia.putInt(32)
        mdia.putInt(0x6D646864) // 'mdhd'
        mdia.putInt(0)
        mdia.putInt(0); mdia.putInt(0)
        mdia.putInt(48000) // timescale = 48000 Hz
        mdia.putInt(totalAudioFrames.toInt())
        mdia.putShort(0x55C4); mdia.putShort(0)
        trakStream.write(mdia.array())

        val hdlr = ByteBuffer.allocate(33)
        hdlr.putInt(33)
        hdlr.putInt(0x68646C72)
        hdlr.putInt(0); hdlr.putInt(0)
        hdlr.putInt(0x736F756E) // 'soun'
        hdlr.putInt(0); hdlr.putInt(0); hdlr.putInt(0)
        hdlr.put(5)
        hdlr.put("Sound".toByteArray())
        trakStream.write(hdlr.array())

        // minf with smhd (Sound Media Header)
        val smhd = ByteBuffer.allocate(16)
        smhd.putInt(16)
        smhd.putInt(0x736D6864) // 'smhd'
        smhd.putInt(0); smhd.putShort(0); smhd.putShort(0)
        trakStream.write(smhd.array())

        val trakPayload = trakStream.toByteArray()
        val totalTrakSize = trakPayload.size + 8
        val out = ByteBuffer.allocate(totalTrakSize)
        out.putInt(totalTrakSize)
        out.putInt(0x7472616B)
        out.put(trakPayload)
        return out.array()
    }
}
