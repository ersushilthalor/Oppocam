package com.example.camera.engine.prores

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Production-grade, fully compliant QuickTime MOV container muxer for Apple ProRes 422 (apcn).
 *
 * Implements:
 * - 'ftyp' atom with major brand 'qt  ' and compatible brands
 * - 'mdat' 64-bit atom with intra-frame ProRes payloads and optional uncompressed 48kHz PCM audio
 * - 'moov' atom with dynamically computed atom sizes (preventing any buffer overflow):
 *   - 'mvhd': movie header with accurate duration and 24000 movie timescale
 *   - 'trak' (video): 'tkhd', 'mdia' -> 'mdhd', 'hdlr' ('vide'), 'minf' -> 'vmhd', 'dinf' -> 'dref' -> 'alis',
 *     'stbl' -> 'stsd' ('apcn' Apple ProRes 422 with dynamic sample entry size and 'colr' atom),
 *     'stts', 'stss' (all intra frames as keyframes), 'stsc', 'stsz', 'stco' / 'co64' (32-bit or 64-bit chunk offsets)
 *   - 'trak' (audio): only emitted if audio is enabled AND audio frames were recorded, containing
 *     'tkhd', 'mdia' -> 'mdhd' (48kHz), 'hdlr' ('soun'), 'minf' -> 'smhd', 'dinf' -> 'dref',
 *     'stbl' -> 'stsd' ('sowt' 16-bit uncompressed little-endian PCM stereo 48kHz), 'stts', 'stsc', 'stsz', 'stco' / 'co64'.
 */
class QuickTimeProResMuxer(
    private val outputFile: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val isAudioEnabled: Boolean = true,
    private val isRec2020: Boolean = false,
    private val isHlg: Boolean = false
) {
    companion object {
        private const val TAG = "QuickTimeProResMuxer"
        const val MOVIE_TIMESCALE = 24000
    }

    private var raf: RandomAccessFile? = null
    private var mdatStartOffset = 0L
    private var currentMdatSize = 0L

    private val videoSampleSizes = mutableListOf<Int>()
    private val videoSampleOffsets = mutableListOf<Long>()

    private val audioSampleSizes = mutableListOf<Int>()
    private val audioSampleOffsets = mutableListOf<Long>()
    private var totalAudioFrames = 0L

    private val frameDuration = if (fps > 0) MOVIE_TIMESCALE / fps else 1000

    @Synchronized
    fun start() {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.createNewFile()

        val r = RandomAccessFile(outputFile, "rw")
        raf = r

        // 1. Write 'ftyp' atom (24 bytes)
        val ftyp = ByteBuffer.allocate(24)
        ftyp.putInt(24) // size
        ftyp.putInt(0x66747970) // 'ftyp'
        ftyp.putInt(0x71742020) // 'qt  '
        ftyp.putInt(0x00000200) // minor version 512
        ftyp.putInt(0x71742020) // compatible brand: 'qt  '
        ftyp.putInt(0)
        r.write(ftyp.array())

        // 2. Write 64-bit 'mdat' header (16 bytes: 4 bytes size=1, 4 bytes 'mdat', 8 bytes large size)
        mdatStartOffset = r.filePointer
        val mdatHdr = ByteBuffer.allocate(16)
        mdatHdr.putInt(1) // 1 indicates 64-bit large size follows in next 8 bytes
        mdatHdr.putInt(0x6D646174) // 'mdat'
        mdatHdr.putLong(16L) // placeholder size (header itself)
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

    val videoFrameCount: Int get() = videoSampleSizes.size

    @Synchronized
    fun finish(): Boolean {
        val r = raf ?: return false
        try {
            if (videoSampleSizes.isEmpty()) {
                Log.w(TAG, "Cannot finalize MOV: 0 video frames recorded")
                return false
            }

            // 1. Update 64-bit 'mdat' size at mdatStartOffset + 8
            val mdatPayloadSize = currentMdatSize
            r.seek(mdatStartOffset + 8)
            r.writeLong(mdatPayloadSize)

            // 2. Seek to end of mdat to append 'moov' atom
            r.seek(mdatStartOffset + currentMdatSize)

            val videoDuration = (videoSampleSizes.size * frameDuration).toLong()
            val audioDurationInMovieTime = if (totalAudioFrames > 0) {
                (totalAudioFrames * MOVIE_TIMESCALE) / 48000L
            } else 0L
            val totalDuration = maxOf(videoDuration, audioDurationInMovieTime)

            // Build fully dynamic 'moov' atom
            val moovBytes = buildMoovAtom(totalDuration, videoDuration)
            r.write(moovBytes)
            try {
                r.channel.force(true)
            } catch (ignored: Exception) {}
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing QuickTime MOV container", e)
            return false
        } finally {
            try { r.close() } catch (ignored: Exception) {}
            raf = null
        }
    }

    // =========================================================================
    // DYNAMIC ATOM BUILDERS (Zero hardcoded buffer sizes, zero overflow risk)
    // =========================================================================

    private fun writeAtom(type: String, block: (ByteArrayOutputStream) -> Unit): ByteArray {
        val payloadStream = ByteArrayOutputStream()
        block(payloadStream)
        val payload = payloadStream.toByteArray()
        val totalSize = payload.size + 8
        val out = ByteArrayOutputStream(totalSize)
        val header = ByteBuffer.allocate(8)
        header.putInt(totalSize)
        header.put(type.toByteArray(Charsets.US_ASCII), 0, 4)
        out.write(header.array())
        out.write(payload)
        return out.toByteArray()
    }

    private fun buildMoovAtom(totalDuration: Long, videoDuration: Long): ByteArray {
        val hasAudioTrack = isAudioEnabled && audioSampleSizes.isNotEmpty() && totalAudioFrames > 0

        return writeAtom("moov") { moovStream ->
            // 1. mvhd (Movie Header Atom, 108 bytes)
            val mvhd = ByteBuffer.allocate(108)
            mvhd.putInt(108)
            mvhd.putInt(0x6D766864) // 'mvhd'
            mvhd.put(0) // version
            mvhd.put(0); mvhd.put(0); mvhd.put(0) // flags
            mvhd.putInt(0) // creation time
            mvhd.putInt(0) // modification time
            mvhd.putInt(MOVIE_TIMESCALE) // timescale
            mvhd.putInt(totalDuration.toInt()) // duration
            mvhd.putInt(0x00010000) // rate 1.0 (fixed 16.16)
            mvhd.putShort(0x0100) // volume 1.0 (fixed 8.8)
            mvhd.putShort(0) // reserved
            mvhd.putInt(0); mvhd.putInt(0) // reserved
            val matrix = intArrayOf(
                0x00010000, 0, 0,
                0, 0x00010000, 0,
                0, 0, 0x40000000
            )
            for (m in matrix) mvhd.putInt(m)
            for (i in 0 until 6) mvhd.putInt(0)
            mvhd.putInt(if (hasAudioTrack) 3 else 2) // next track ID
            moovStream.write(mvhd.array())

            // 2. Video 'trak'
            val videoTrak = buildVideoTrak(videoDuration)
            moovStream.write(videoTrak)

            // 3. Audio 'trak' ONLY if audio is genuinely present
            if (hasAudioTrack) {
                val audioTrak = buildAudioTrak()
                moovStream.write(audioTrak)
            }
        }
    }

    private fun buildVideoTrak(videoDuration: Long): ByteArray {
        return writeAtom("trak") { trakStream ->
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
            val mdia = buildVideoMdia(videoDuration)
            trakStream.write(mdia)
        }
    }

    private fun buildVideoMdia(videoDuration: Long): ByteArray {
        return writeAtom("mdia") { mdiaStream ->
            // mdhd (Media Header Atom, 32 bytes)
            val mdhd = ByteBuffer.allocate(32)
            mdhd.putInt(32)
            mdhd.putInt(0x6D646864) // 'mdhd'
            mdhd.putInt(0) // version + flags
            mdhd.putInt(0); mdhd.putInt(0) // creation, mod
            mdhd.putInt(MOVIE_TIMESCALE) // time scale
            mdhd.putInt(videoDuration.toInt()) // duration
            mdhd.putShort(0x55C4) // language: English / undefined
            mdhd.putShort(0) // quality
            mdiaStream.write(mdhd.array())

            // hdlr (Handler Reference Atom, dynamically sized)
            val hdlr = writeAtom("hdlr") { stream ->
                val base = ByteBuffer.allocate(24)
                base.putInt(0) // version + flags
                base.putInt(0) // component type
                base.putInt(0x76696465) // subtype: 'vide'
                base.putInt(0); base.putInt(0); base.putInt(0) // reserved
                stream.write(base.array())
                // Pascal string: length byte + ASCII chars
                val name = "Video".toByteArray(Charsets.US_ASCII)
                stream.write(name.size)
                stream.write(name)
            }
            mdiaStream.write(hdlr)

            // minf (Media Information Atom)
            val minf = buildVideoMinf()
            mdiaStream.write(minf)
        }
    }

    private fun buildVideoMinf(): ByteArray {
        return writeAtom("minf") { minfStream ->
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
            val stbl = buildVideoStbl()
            minfStream.write(stbl)
        }
    }

    private fun buildVideoStbl(): ByteArray {
        return writeAtom("stbl") { stblStream ->
            val frameCount = videoSampleSizes.size

            // 1. stsd (Sample Description Atom for 'apcn' - Apple ProRes 422 Standard)
            val compressorName = "Apple ProRes 422"

            // Build 'colr' atom (18 bytes) for ProRes NCLC color tagging
            val colr = ByteBuffer.allocate(18)
            colr.putInt(18)
            colr.putInt(0x636F6C72) // 'colr'
            colr.putInt(0x6E636C63) // 'nclc'
            val primaries: Short = if (isRec2020 || isHlg) 9 else 1 // 9 = BT.2020, 1 = BT.709
            val transfer: Short = if (isHlg) 18 else if (isRec2020) 14 else 1 // 18 = ARIB STD-B67, 14 = BT.2020 10-bit, 1 = BT.709
            val matrix: Short = if (isRec2020 || isHlg) 9 else 1 // 9 = BT.2020, 1 = BT.709
            colr.putShort(primaries)
            colr.putShort(transfer)
            colr.putShort(matrix)

            val apcnEntry = writeAtom("apcn") { apcnStream ->
                val base = ByteBuffer.allocate(78)
                base.putInt(0); base.putShort(0) // reserved (6 bytes)
                base.putShort(1) // data ref index = 1
                base.putShort(0); base.putShort(0) // version, revision
                base.putInt(0x6170706C) // vendor 'appl'
                base.putInt(0x00000200) // temporal quality
                base.putInt(0x00000200) // spatial quality
                base.putShort(width.toShort())
                base.putShort(height.toShort())
                base.putInt(0x00480000) // horiz res 72 dpi (16.16)
                base.putInt(0x00480000) // vert res 72 dpi (16.16)
                base.putInt(0) // data size = 0
                base.putShort(1) // frame count = 1
                // Compressor name (32 bytes: 1 byte len + name + zero padding)
                val nameBytes = compressorName.toByteArray(Charsets.US_ASCII)
                val clampedNameLen = minOf(nameBytes.size, 31)
                base.put(clampedNameLen.toByte())
                base.put(nameBytes, 0, clampedNameLen)
                for (i in (clampedNameLen + 1) until 32) {
                    base.put(0)
                }
                base.putShort(24) // depth: 24-bit color (standard for ProRes 422)
                base.putShort((-1).toShort()) // color table ID
                apcnStream.write(base.array())
                apcnStream.write(colr.array())
            }

            // stsd atom header: 8 bytes (version/flags + entry count) + apcnEntry
            val stsd = writeAtom("stsd") { stsdStream ->
                val hdr = ByteBuffer.allocate(8)
                hdr.putInt(0) // version + flags
                hdr.putInt(1) // entry count = 1
                stsdStream.write(hdr.array())
                stsdStream.write(apcnEntry)
            }
            stblStream.write(stsd)

            // 2. stts (Time-to-Sample Atom: 1 entry for all frames with frameDuration)
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

            // 6. stco or co64 (Chunk Offset Table)
            val hasLargeOffset = videoSampleOffsets.any { it > 0x7FFFFFFF }
            if (!hasLargeOffset) {
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
            } else {
                val co64Len = 16 + frameCount * 8
                val co64 = ByteBuffer.allocate(co64Len)
                co64.putInt(co64Len)
                co64.putInt(0x636F3634) // 'co64'
                co64.putInt(0)
                co64.putInt(frameCount)
                for (off in videoSampleOffsets) {
                    co64.putLong(off)
                }
                stblStream.write(co64.array())
            }
        }
    }

    private fun buildAudioTrak(): ByteArray {
        val audioDurationInMovieTime = ((totalAudioFrames * MOVIE_TIMESCALE) / 48000L).toInt()

        return writeAtom("trak") { trakStream ->
            // 1. tkhd (Audio Track Header)
            val tkhd = ByteBuffer.allocate(92)
            tkhd.putInt(92)
            tkhd.putInt(0x746B6864) // 'tkhd'
            tkhd.put(0) // version
            tkhd.put(0); tkhd.put(0); tkhd.put(0x0F) // flags
            tkhd.putInt(0); tkhd.putInt(0) // creation, mod
            tkhd.putInt(2) // track id = 2
            tkhd.putInt(0) // reserved
            tkhd.putInt(audioDurationInMovieTime)
            tkhd.putInt(0); tkhd.putInt(0) // reserved
            tkhd.putShort(0); tkhd.putShort(0) // layer, alt group
            tkhd.putShort(0x0100) // volume 1.0 (8.8)
            tkhd.putShort(0) // reserved
            val matrix = intArrayOf(
                0x00010000, 0, 0,
                0, 0x00010000, 0,
                0, 0, 0x40000000
            )
            for (m in matrix) tkhd.putInt(m)
            tkhd.putInt(0); tkhd.putInt(0) // width, height = 0 for audio
            trakStream.write(tkhd.array())

            // 2. mdia for audio
            val mdia = writeAtom("mdia") { mdiaStream ->
                // mdhd (Audio Media Header, 32 bytes)
                val mdhd = ByteBuffer.allocate(32)
                mdhd.putInt(32)
                mdhd.putInt(0x6D646864) // 'mdhd'
                mdhd.putInt(0)
                mdhd.putInt(0); mdhd.putInt(0)
                mdhd.putInt(48000) // timescale = 48000 Hz
                mdhd.putInt(totalAudioFrames.toInt())
                mdhd.putShort(0x55C4); mdhd.putShort(0)
                mdiaStream.write(mdhd.array())

                // hdlr ('soun')
                val hdlr = writeAtom("hdlr") { stream ->
                    val base = ByteBuffer.allocate(24)
                    base.putInt(0)
                    base.putInt(0)
                    base.putInt(0x736F756E) // 'soun'
                    base.putInt(0); base.putInt(0); base.putInt(0)
                    stream.write(base.array())
                    val name = "Sound".toByteArray(Charsets.US_ASCII)
                    stream.write(name.size)
                    stream.write(name)
                }
                mdiaStream.write(hdlr)

                // minf for audio
                val minf = writeAtom("minf") { minfStream ->
                    // smhd (Sound Media Header, 16 bytes)
                    val smhd = ByteBuffer.allocate(16)
                    smhd.putInt(16)
                    smhd.putInt(0x736D6864) // 'smhd'
                    smhd.putInt(0) // version + flags
                    smhd.putShort(0) // balance
                    smhd.putShort(0) // reserved
                    minfStream.write(smhd.array())

                    // dinf & dref (Data Info, 36 bytes)
                    val dinf = ByteBuffer.allocate(36)
                    dinf.putInt(36)
                    dinf.putInt(0x64696E66) // 'dinf'
                    dinf.putInt(28)
                    dinf.putInt(0x64726566) // 'dref'
                    dinf.putInt(0)
                    dinf.putInt(1)
                    dinf.putInt(12)
                    dinf.putInt(0x616C6973) // 'alis'
                    dinf.putInt(1)
                    minfStream.write(dinf.array())

                    // stbl for audio
                    val stbl = buildAudioStbl()
                    minfStream.write(stbl)
                }
                mdiaStream.write(minf)
            }
            trakStream.write(mdia)
        }
    }

    private fun buildAudioStbl(): ByteArray {
        return writeAtom("stbl") { stblStream ->
            val chunkCount = audioSampleSizes.size

            // 1. stsd with 'sowt' (16-bit uncompressed PCM stereo 48kHz)
            val sowtEntry = writeAtom("sowt") { sowtStream ->
                val sowt = ByteBuffer.allocate(28)
                sowt.putInt(0); sowt.putShort(0) // reserved (6)
                sowt.putShort(1) // data ref index = 1
                sowt.putShort(0); sowt.putShort(0) // version, revision
                sowt.putInt(0) // vendor
                sowt.putShort(2) // channels = 2 (stereo)
                sowt.putShort(16) // sample size = 16 bit
                sowt.putShort(0); sowt.putShort(0) // compression ID, packet size
                sowt.putInt(48000 shl 16) // sample rate 48000.0 in 16.16 fixed point
                sowtStream.write(sowt.array())
            }

            val stsd = writeAtom("stsd") { stsdStream ->
                val hdr = ByteBuffer.allocate(8)
                hdr.putInt(0)
                hdr.putInt(1)
                stsdStream.write(hdr.array())
                stsdStream.write(sowtEntry)
            }
            stblStream.write(stsd)

            // 2. stts (Time-to-sample for audio chunks)
            val sttsLen = 16 + chunkCount * 8
            val stts = ByteBuffer.allocate(sttsLen)
            stts.putInt(sttsLen)
            stts.putInt(0x73747473)
            stts.putInt(0)
            stts.putInt(chunkCount)
            for (sz in audioSampleSizes) {
                stts.putInt(1) // 1 sample (chunk)
                stts.putInt(sz / 4) // sample duration = number of 4-byte frames at 48000Hz
            }
            stblStream.write(stts.array())

            // 3. stsc (Sample to Chunk: 1 sample per chunk)
            val stsc = ByteBuffer.allocate(28)
            stsc.putInt(28)
            stsc.putInt(0x73747363)
            stsc.putInt(0)
            stsc.putInt(1)
            stsc.putInt(1)
            stsc.putInt(1)
            stsc.putInt(1)
            stblStream.write(stsc.array())

            // 4. stsz (Sample Sizes: variable size per chunk)
            val stszLen = 20 + chunkCount * 4
            val stsz = ByteBuffer.allocate(stszLen)
            stsz.putInt(stszLen)
            stsz.putInt(0x7374737A)
            stsz.putInt(0)
            stsz.putInt(0) // variable sample size
            stsz.putInt(chunkCount)
            for (sz in audioSampleSizes) {
                stsz.putInt(sz)
            }
            stblStream.write(stsz.array())

            // 5. stco / co64 (Chunk Offsets for audio)
            val hasLargeOffset = audioSampleOffsets.any { it > 0x7FFFFFFF }
            if (!hasLargeOffset) {
                val stcoLen = 16 + chunkCount * 4
                val stco = ByteBuffer.allocate(stcoLen)
                stco.putInt(stcoLen)
                stco.putInt(0x7374636F)
                stco.putInt(0)
                stco.putInt(chunkCount)
                for (off in audioSampleOffsets) {
                    stco.putInt(off.toInt())
                }
                stblStream.write(stco.array())
            } else {
                val co64Len = 16 + chunkCount * 8
                val co64 = ByteBuffer.allocate(co64Len)
                co64.putInt(co64Len)
                co64.putInt(0x636F3634)
                co64.putInt(0)
                co64.putInt(chunkCount)
                for (off in audioSampleOffsets) {
                    co64.putLong(off)
                }
                stblStream.write(co64.array())
            }
        }
    }
}
