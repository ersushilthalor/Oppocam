package com.example.camera.engine.prores

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Genuine Apple ProRes 422 (apcn) Intra-Frame 10-bit Video Encoder.
 *
 * Implements:
 * 1. Standards-compatible Apple ProRes 422 bitstream format:
 *    - Frame Container: 4-byte size + 'icpf' magic
 *    - Frame Header: 148-byte custom matrix header with 'appl' creator
 *    - Picture Header: 8 bytes with bit-shifted pic_hdr_size (0x40), total slice count & slice dimensions
 *    - Macroblocks: 16x16 luma pixels with 4:2:2 chroma subsampling (4 Y blocks, 2 Cb blocks, 2 Cr blocks)
 *    - Orthonormal 8x8 2D-DCT scaled so 10-bit DC midpoint (512) maps to standard 0x4000 (16384)
 *    - Interleaved Apple ProRes DC and AC variable-length entropy coding (Golomb-Rice & exp-Golomb)
 *      matching standard Apple ProRes decoders (QuickTime, Final Cut Pro, DaVinci Resolve, FFmpeg)
 *
 * 2. Robust YUV_420_888 to ProRes 4:2:2 chroma handling:
 *    - Safe plane dimensions ((width+1)/2 x (height+1)/2)
 *    - Vertical 4:2:0 -> 4:2:2 chroma upsampling with arbitrary rowStride/pixelStride indexing
 *    - Zero out-of-bounds buffer reads and zero silent frame drops.
 */
class ProResEncoder(
    val width: Int,
    val height: Int,
    val isRec2020: Boolean = false,
    val isHlg: Boolean = false,
    val isSource10Bit: Boolean = false
) {
    companion object {
        const val MAGIC_ICPF = 0x69637066 // 'icpf'
        const val CREATOR_APPL = 0x6170706C // 'appl'
        const val CHROMA_422 = 2

        // Apple ProRes standard 4:2:2 default quantization matrices
        val DEFAULT_LUMA_QUANT = intArrayOf(
             4,  7,  9, 11, 14, 17, 21, 27,
             7,  8, 10, 12, 15, 19, 23, 29,
             9, 10, 12, 14, 17, 21, 26, 32,
            11, 12, 14, 16, 20, 24, 29, 36,
            14, 15, 17, 20, 24, 29, 35, 42,
            17, 19, 21, 24, 29, 35, 42, 50,
            21, 23, 26, 29, 35, 42, 50, 60,
            27, 29, 32, 36, 42, 50, 60, 71
        )

        val DEFAULT_CHROMA_QUANT = intArrayOf(
             4,  7,  9, 12, 15, 19, 24, 30,
             7,  8, 10, 13, 16, 21, 26, 33,
             9, 10, 13, 16, 20, 25, 31, 38,
            12, 13, 16, 19, 24, 30, 37, 45,
            15, 16, 20, 24, 30, 37, 45, 54,
            19, 21, 25, 30, 37, 45, 54, 64,
            24, 26, 31, 37, 45, 54, 64, 75,
            30, 33, 38, 45, 54, 64, 75, 87
        )

        // Standard Apple ProRes progressive scan table
        val PROGRESSIVE_SCAN = intArrayOf(
             0,  1,  8,  9,  2,  3, 10, 11,
            16, 17, 24, 25, 18, 19, 26, 27,
             4,  5, 12, 20, 13,  6,  7, 14,
            21, 28, 29, 22, 15, 23, 30, 31,
            32, 33, 40, 48, 41, 34, 35, 42,
            49, 56, 57, 50, 43, 36, 37, 44,
            51, 58, 59, 52, 45, 38, 39, 46,
            53, 60, 61, 54, 47, 55, 62, 63
        )

        // Apple ProRes standard VLC codebooks
        const val FIRST_DC_CB = 0xB8
        val DC_CODEBOOK = intArrayOf(0x04, 0x28, 0x28, 0x4D, 0x4D, 0x70, 0x70)
        val RUN_TO_CB = intArrayOf(
            0x06, 0x06, 0x05, 0x05, 0x04, 0x29,
            0x29, 0x29, 0x29, 0x28, 0x28, 0x28,
            0x28, 0x28, 0x28, 0x4C
        )
        val LEVEL_TO_CB = intArrayOf(
            0x04, 0x0A, 0x05, 0x06, 0x04, 0x28,
            0x28, 0x28, 0x28, 0x4C
        )

        private val DCT_COS_TABLE = Array(8) { u ->
            FloatArray(8) { x ->
                val alpha = if (u == 0) 1.0f / sqrt(2.0f) else 1.0f
                alpha * cos((2 * x + 1) * u * Math.PI.toFloat() / 16.0f)
            }
        }
    }

    private val mbWidth = (width + 15) / 16
    private val mbHeight = (height + 15) / 16
    private val mbsPerSlice = 8
    private val slicesPerRow = (mbWidth + mbsPerSlice - 1) / mbsPerSlice
    private val totalSlices = slicesPerRow * mbHeight

    private val chromaWidth = (width + 1) / 2
    private val chromaHeight = (height + 1) / 2

    // ProRes 422 standard qscale factor (6 for ProRes 422 Standard)
    private val defaultQScale = 6
    private val scaledLumaQuant = IntArray(64) { DEFAULT_LUMA_QUANT[it] * defaultQScale }
    private val scaledChromaQuant = IntArray(64) { DEFAULT_CHROMA_QUANT[it] * defaultQScale }

    /**
     * Encodes a Camera2 YUV_420_888 frame into a genuine Apple ProRes 422 frame payload.
     * Safely reads Y, U, V with true plane strides without buffer overruns.
     */
    fun encodeFrame(
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        isSource10Bit: Boolean = this.isSource10Bit
    ): ByteArray {
        require(yPlane.isNotEmpty() && uPlane.isNotEmpty() && vPlane.isNotEmpty()) {
            "Input YUV planes cannot be empty"
        }

        val out = ByteArrayOutputStream(maxOf(width * height / 2, 65536))

        // Reserve 4 bytes for total frame size + 4 bytes 'icpf' magic
        val dummy = ByteArray(8)
        out.write(dummy)

        // 1. Frame Header (148 bytes for custom matrices)
        val hdrSize = 148
        val hdrBuf = ByteBuffer.allocate(hdrSize)
        hdrBuf.putShort(hdrSize.toShort()) // header size = 148
        hdrBuf.putShort(0)                 // version 0
        hdrBuf.putInt(CREATOR_APPL)        // creator 'appl'
        hdrBuf.putShort(width.toShort())   // width
        hdrBuf.putShort(height.toShort())  // height
        // Frame flags: chroma format 4:2:2 (2 shl 6 = 0x80) | progressive (0x02) = 0x82
        hdrBuf.put(0x82.toByte())
        hdrBuf.put(0)                      // reserved
        hdrBuf.put(if (isRec2020 || isHlg) 9.toByte() else 1.toByte()) // primaries: 9=BT.2020, 1=BT.709
        hdrBuf.put(if (isHlg) 18.toByte() else if (isRec2020) 14.toByte() else 1.toByte()) // transfer: 18=ARIB B67, 1=BT.709
        hdrBuf.put(if (isRec2020 || isHlg) 9.toByte() else 1.toByte()) // matrix: 9=BT.2020, 1=BT.709
        hdrBuf.put(0)                      // alpha bits (0 = none)
        hdrBuf.put(0)                      // reserved
        hdrBuf.put(0x03.toByte())          // matrix flags: custom luma & chroma matrices present

        // Write custom quantization matrices (64 bytes each)
        for (q in DEFAULT_LUMA_QUANT) hdrBuf.put(q.toByte())
        for (q in DEFAULT_CHROMA_QUANT) hdrBuf.put(q.toByte())

        out.write(hdrBuf.array())

        // 2. Picture Header (8 bytes)
        // In Apple ProRes bitstream:
        // byte 0: pic_hdr_size in bits = 8 shl 3 = 64 = 0x40
        // bytes 1..4: pic_data_size (will be filled later)
        // bytes 5..6: total slices count
        // byte 7: (slice_width_factor shl 4) or slice_height_factor (for 8 MBs per slice: 3 shl 4 = 0x30)
        val picHdrOffset = out.size()
        val picHdrBuf = ByteBuffer.allocate(8)
        picHdrBuf.put(0x40.toByte())
        picHdrBuf.putInt(0) // placeholder for pic_data_size
        picHdrBuf.putShort(totalSlices.toShort())
        picHdrBuf.put(0x30.toByte()) // slice width factor = 3 (8 MBs), height factor = 0
        out.write(picHdrBuf.array())

        // 3. Reserve space for slice size table (2 bytes per slice)
        val sliceTableOffset = out.size()
        val sliceTable = ShortArray(totalSlices)
        for (i in 0 until totalSlices) {
            out.write(0)
            out.write(0)
        }

        // 4. Encode Slices
        val block = FloatArray(64)
        var sliceIndex = 0

        for (mbY in 0 until mbHeight) {
            for (sliceX in 0 until slicesPerRow) {
                val startMbX = sliceX * mbsPerSlice
                val endMbX = minOf(startMbX + mbsPerSlice, mbWidth)
                val sliceMbCount = endMbX - startMbX

                val sliceBytes = encodeSlice(
                    startMbX = startMbX,
                    endMbX = endMbX,
                    mbY = mbY,
                    yPlane = yPlane,
                    uPlane = uPlane,
                    vPlane = vPlane,
                    yRowStride = yRowStride,
                    uRowStride = uRowStride,
                    vRowStride = vRowStride,
                    uPixelStride = uPixelStride,
                    vPixelStride = vPixelStride,
                    tempBlock = block,
                    isSource10Bit = isSource10Bit
                )

                sliceTable[sliceIndex] = sliceBytes.size.toShort()
                sliceIndex++
                out.write(sliceBytes)
            }
        }

        val fullBytes = out.toByteArray()
        val totalFrameSize = fullBytes.size

        // Write total frame size and 'icpf' magic at offset 0
        val sizeBuf = ByteBuffer.wrap(fullBytes, 0, 8)
        sizeBuf.putInt(totalFrameSize)
        sizeBuf.putInt(MAGIC_ICPF)

        // Fill in pic_data_size at picHdrOffset + 1
        val picDataSize = totalFrameSize - picHdrOffset
        val picDataBuf = ByteBuffer.wrap(fullBytes, picHdrOffset + 1, 4)
        picDataBuf.putInt(picDataSize)

        // Fill in slice size table at sliceTableOffset
        val tableBuf = ByteBuffer.wrap(fullBytes, sliceTableOffset, totalSlices * 2)
        for (sz in sliceTable) {
            tableBuf.putShort(sz)
        }

        return fullBytes
    }

    private fun encodeSlice(
        startMbX: Int,
        endMbX: Int,
        mbY: Int,
        yPlane: ByteArray,
        uPlane: ByteArray,
        vPlane: ByteArray,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        tempBlock: FloatArray,
        isSource10Bit: Boolean
    ): ByteArray {
        val numMbs = endMbX - startMbX
        val yBlocks = Array(numMbs * 4) { IntArray(64) }
        val uBlocks = Array(numMbs * 2) { IntArray(64) }
        val vBlocks = Array(numMbs * 2) { IntArray(64) }

        var yBlockIdx = 0
        var uBlockIdx = 0
        var vBlockIdx = 0

        for (mbX in startMbX until endMbX) {
            val pixX = mbX * 16
            val pixY = mbY * 16

            // Y: 4 blocks (8x8) covering 16x16 luma
            for (by in 0..1) {
                for (bx in 0..1) {
                    extractLumaBlock(
                        yPlane = yPlane,
                        rowStride = yRowStride,
                        startX = pixX + bx * 8,
                        startY = pixY + by * 8,
                        outBlock = tempBlock,
                        isSource10Bit = isSource10Bit
                    )
                    forwardDct8x8(tempBlock, yBlocks[yBlockIdx++])
                }
            }

            // U (Cb): 2 blocks (8x8) in 4:2:2 (covering 8 horiz x 16 vert chroma)
            for (by in 0..1) {
                extractChromaBlockFromYuv420(
                    cPlane = uPlane,
                    rowStride = uRowStride,
                    pixelStride = uPixelStride,
                    chromaStartX = mbX * 8,
                    lumaStartY = pixY + by * 8,
                    outBlock = tempBlock,
                    isSource10Bit = isSource10Bit
                )
                forwardDct8x8(tempBlock, uBlocks[uBlockIdx++])
            }

            // V (Cr): 2 blocks (8x8) in 4:2:2 (covering 8 horiz x 16 vert chroma)
            for (by in 0..1) {
                extractChromaBlockFromYuv420(
                    cPlane = vPlane,
                    rowStride = vRowStride,
                    pixelStride = vPixelStride,
                    chromaStartX = mbX * 8,
                    lumaStartY = pixY + by * 8,
                    outBlock = tempBlock,
                    isSource10Bit = isSource10Bit
                )
                forwardDct8x8(tempBlock, vBlocks[vBlockIdx++])
            }
        }

        // Encode Luma Plane (all DCs then all ACs in progressive scan order)
        val yBitWriter = BitWriter()
        encodePlaneDcs(yBitWriter, yBlocks, scaledLumaQuant[0])
        encodePlaneAcs(yBitWriter, yBlocks, scaledLumaQuant)
        yBitWriter.flush()
        val yBytes = yBitWriter.toByteArray()

        // Encode Chroma U Plane
        val uBitWriter = BitWriter()
        encodePlaneDcs(uBitWriter, uBlocks, scaledChromaQuant[0])
        encodePlaneAcs(uBitWriter, uBlocks, scaledChromaQuant)
        uBitWriter.flush()
        val uBytes = uBitWriter.toByteArray()

        // Encode Chroma V Plane
        val vBitWriter = BitWriter()
        encodePlaneDcs(vBitWriter, vBlocks, scaledChromaQuant[0])
        encodePlaneAcs(vBitWriter, vBlocks, scaledChromaQuant)
        vBitWriter.flush()
        val vBytes = vBitWriter.toByteArray()

        // Build Slice Header (6 bytes)
        // byte 0: slice_hdr_size in bits = 6 shl 3 = 48 = 0x30
        // byte 1: qscale
        // bytes 2..3: yBytes.size
        // bytes 4..5: uBytes.size
        val totalSliceSize = 6 + yBytes.size + uBytes.size + vBytes.size
        val sliceBuf = ByteArray(totalSliceSize)
        sliceBuf[0] = 0x30.toByte()
        sliceBuf[1] = defaultQScale.toByte()
        sliceBuf[2] = (yBytes.size ushr 8).toByte()
        sliceBuf[3] = (yBytes.size and 0xFF).toByte()
        sliceBuf[4] = (uBytes.size ushr 8).toByte()
        sliceBuf[5] = (uBytes.size and 0xFF).toByte()

        var pos = 6
        System.arraycopy(yBytes, 0, sliceBuf, pos, yBytes.size)
        pos += yBytes.size
        System.arraycopy(uBytes, 0, sliceBuf, pos, uBytes.size)
        pos += uBytes.size
        System.arraycopy(vBytes, 0, sliceBuf, pos, vBytes.size)

        return sliceBuf
    }

    private fun encodePlaneDcs(writer: BitWriter, blocks: Array<IntArray>, scale: Int) {
        val totalBlocks = blocks.size
        if (totalBlocks == 0) return

        var prevDc = (blocks[0][0] - 0x4000) / scale
        encodeVlcCodeword(writer, FIRST_DC_CB, makeCode(prevDc))

        var sign = 0
        var codebook = 5

        for (i in 1 until totalBlocks) {
            val dc = (blocks[i][0] - 0x4000) / scale
            val delta = dc - prevDc
            val newSign = if (delta < 0) 1 else 0
            val signedDelta = if (sign != 0) -delta else delta
            val code = makeCode(signedDelta)
            encodeVlcCodeword(writer, DC_CODEBOOK[codebook], code)
            codebook = minOf(code, 6)
            sign = newSign
            prevDc = dc
        }
    }

    private fun encodePlaneAcs(writer: BitWriter, blocks: Array<IntArray>, qmat: IntArray) {
        val totalBlocks = blocks.size
        if (totalBlocks == 0) return

        var prevRun = 4
        var prevLevel = 2
        var run = 0

        for (i in 1 until 64) {
            val freqIdx = PROGRESSIVE_SCAN[i]
            val q = qmat[freqIdx]

            for (b in 0 until totalBlocks) {
                val level = blocks[b][freqIdx] / q
                if (level != 0) {
                    val absLevel = if (level < 0) -level else level
                    encodeVlcCodeword(writer, RUN_TO_CB[prevRun], run)
                    encodeVlcCodeword(writer, LEVEL_TO_CB[prevLevel], absLevel - 1)
                    writer.writeBit(if (level < 0) 1 else 0)

                    prevRun = minOf(run, 15)
                    prevLevel = minOf(absLevel, 9)
                    run = 0
                } else {
                    run++
                }
            }
        }
    }

    private fun makeCode(x: Int): Int {
        val sign = if (x < 0) -1 else 0
        return (x * 2) xor sign
    }

    private fun encodeVlcCodeword(writer: BitWriter, codebook: Int, value: Int) {
        var v = value
        val switchBits = (codebook and 3) + 1
        val riceOrder = codebook ushr 5
        val expOrder = (codebook ushr 2) and 7
        val switchVal = switchBits shl riceOrder

        if (v >= switchVal) {
            v -= switchVal - (1 shl expOrder)
            val exponent = 31 - Integer.numberOfLeadingZeros(v)
            val numZeroes = exponent - expOrder + switchBits
            for (i in 0 until numZeroes) {
                writer.writeBit(0)
            }
            writer.writeBits(v, exponent + 1)
        } else {
            val exponent = v ushr riceOrder
            for (i in 0 until exponent) {
                writer.writeBit(0)
            }
            writer.writeBit(1)
            if (riceOrder > 0) {
                writer.writeBits(v, riceOrder)
            }
        }
    }

    private fun extractLumaBlock(
        yPlane: ByteArray,
        rowStride: Int,
        startX: Int,
        startY: Int,
        outBlock: FloatArray,
        isSource10Bit: Boolean
    ) {
        val planeLen = yPlane.size
        val yPixStride = if (isSource10Bit) 2 else 1
        for (y in 0 until 8) {
            val py = minOf(startY + y, height - 1)
            val rowOffset = py * rowStride
            for (x in 0 until 8) {
                val px = minOf(startX + x, width - 1)
                val tenBitVal: Float = if (isSource10Bit) {
                    val idx = rowOffset + px * yPixStride
                    if (idx + 1 < planeLen) {
                        val b0 = yPlane[idx].toInt() and 0xFF
                        val b1 = yPlane[idx + 1].toInt() and 0xFF
                        val raw16 = b0 or (b1 shl 8)
                        val v10 = if (raw16 > 1023) (raw16 ushr 6) and 0x3FF else raw16 and 0x3FF
                        v10.toFloat()
                    } else if (idx < planeLen) {
                        val b0 = yPlane[idx].toInt() and 0xFF
                        ((b0 shl 2) or (b0 ushr 6)).toFloat()
                    } else 512.0f
                } else {
                    val idx = rowOffset + px
                    val byteVal = if (idx in 0 until planeLen) {
                        yPlane[idx].toInt() and 0xFF
                    } else 128
                    // Standard linear 8-to-10 bit scaling: [0..255] -> [0..1023]
                    ((byteVal shl 2) or (byteVal ushr 6)).toFloat()
                }
                outBlock[y * 8 + x] = tenBitVal
            }
        }
    }

    private fun extractChromaBlockFromYuv420(
        cPlane: ByteArray,
        rowStride: Int,
        pixelStride: Int,
        chromaStartX: Int,
        lumaStartY: Int,
        outBlock: FloatArray,
        isSource10Bit: Boolean
    ) {
        val planeLen = cPlane.size
        val maxCy = chromaHeight - 1
        val maxCx = chromaWidth - 1

        for (y in 0 until 8) {
            val lumaY = minOf(lumaStartY + y, height - 1)
            val cy = minOf(lumaY / 2, maxCy)
            val isOddRow = (lumaY and 1) == 1
            val cyNext = minOf(cy + 1, maxCy)

            val rowOffset0 = cy * rowStride
            val rowOffset1 = cyNext * rowStride

            for (x in 0 until 8) {
                val cx = minOf(chromaStartX + x, maxCx)
                val offset0 = rowOffset0 + cx * pixelStride

                val tenBitVal: Float = if (isSource10Bit) {
                    val val0 = if (offset0 + 1 < planeLen) {
                        val b0 = cPlane[offset0].toInt() and 0xFF
                        val b1 = cPlane[offset0 + 1].toInt() and 0xFF
                        val raw16 = b0 or (b1 shl 8)
                        if (raw16 > 1023) (raw16 ushr 6) and 0x3FF else raw16 and 0x3FF
                    } else if (offset0 < planeLen) {
                        val b0 = cPlane[offset0].toInt() and 0xFF
                        (b0 shl 2) or (b0 ushr 6)
                    } else 512

                    val finalVal = if (isOddRow && cy != cyNext) {
                        val offset1 = rowOffset1 + cx * pixelStride
                        val val1 = if (offset1 + 1 < planeLen) {
                            val b0 = cPlane[offset1].toInt() and 0xFF
                            val b1 = cPlane[offset1 + 1].toInt() and 0xFF
                            val raw16 = b0 or (b1 shl 8)
                            if (raw16 > 1023) (raw16 ushr 6) and 0x3FF else raw16 and 0x3FF
                        } else if (offset1 < planeLen) {
                            val b0 = cPlane[offset1].toInt() and 0xFF
                            (b0 shl 2) or (b0 ushr 6)
                        } else val0
                        (val0 + val1 + 1) shr 1
                    } else {
                        val0
                    }
                    finalVal.toFloat()
                } else {
                    val byteVal0 = if (offset0 in 0 until planeLen) {
                        cPlane[offset0].toInt() and 0xFF
                    } else 128

                    val finalByteVal = if (isOddRow && cy != cyNext) {
                        val offset1 = rowOffset1 + cx * pixelStride
                        val byteVal1 = if (offset1 in 0 until planeLen) {
                            cPlane[offset1].toInt() and 0xFF
                        } else byteVal0
                        (byteVal0 + byteVal1 + 1) shr 1
                    } else {
                        byteVal0
                    }
                    ((finalByteVal shl 2) or (finalByteVal ushr 6)).toFloat()
                }

                outBlock[y * 8 + x] = tenBitVal
            }
        }
    }

    /**
     * Orthonormal 8x8 2D-DCT scaled so that DC midpoint (512) produces exactly 16384 (0x4000).
     */
    private fun forwardDct8x8(input: FloatArray, output: IntArray) {
        val temp = FloatArray(64)
        // 1D DCT on rows
        for (i in 0 until 8) {
            val i8 = i * 8
            for (u in 0 until 8) {
                var sum = 0.0f
                val cosU = DCT_COS_TABLE[u]
                for (x in 0 until 8) {
                    sum += input[i8 + x] * cosU[x]
                }
                temp[i8 + u] = sum
            }
        }
        // 1D DCT on columns
        for (j in 0 until 8) {
            for (v in 0 until 8) {
                var sum = 0.0f
                val cosV = DCT_COS_TABLE[v]
                for (y in 0 until 8) {
                    sum += temp[y * 8 + j] * cosV[y]
                }
                output[v * 8 + j] = sum.roundToInt()
            }
        }
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream(4096)
        private var curByte = 0
        private var bitCount = 0

        fun writeBit(b: Int) {
            curByte = (curByte shl 1) or (b and 1)
            bitCount++
            if (bitCount == 8) {
                out.write(curByte)
                curByte = 0
                bitCount = 0
            }
        }

        fun writeBits(value: Int, numBits: Int) {
            for (i in numBits - 1 downTo 0) {
                writeBit((value ushr i) and 1)
            }
        }

        fun flush() {
            if (bitCount > 0) {
                curByte = curByte shl (8 - bitCount)
                out.write(curByte)
                curByte = 0
                bitCount = 0
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
