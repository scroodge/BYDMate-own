package com.bydmate.app.data.remote.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NvFrameTest {

    @Test
    fun recognisesTheSurroundStripOnly() {
        assertTrue(NvFrame.isSurroundStrip(5120, 960)) // `way`, camera 1
        assertTrue(NvFrame.isSurroundStrip(32, 6)) // four 8×6 tiles
        assertFalse(NvFrame.isSurroundStrip(1920, 1080)) // `way`, camera 0 (front)
        assertFalse(NvFrame.isSurroundStrip(0, 0))
        assertFalse(NvFrame.isSurroundStrip(5120, 961))
    }

    @Test
    fun movesEachTileAndItsChromaIntoTheGrid() {
        // Four 4×3 tiles would make odd chroma rows, so use 8×6 tiles: strip 32×6.
        val width = 32
        val height = 6
        val tileWidth = width / 4
        val strip = ByteArray(NvFrame.nv21Size(width, height))
        // Luma byte = tile index; chroma byte = 10 + tile index.
        for (y in 0 until height) for (x in 0 until width) strip[y * width + x] = (x / tileWidth).toByte()
        for (y in 0 until height / 2) for (x in 0 until width) {
            strip[width * height + y * width + x] = (10 + x / tileWidth).toByte()
        }

        val grid = NvFrame.stripToGrid(strip, width, height)

        val outWidth = width / 2
        val outHeight = height * 2
        assertEquals(NvFrame.nv21Size(outWidth, outHeight), grid.size)
        fun luma(x: Int, y: Int) = grid[y * outWidth + x].toInt()
        fun chroma(x: Int, y: Int) = grid[outWidth * outHeight + y * outWidth + x].toInt()
        assertEquals(0, luma(0, 0))
        assertEquals(1, luma(outWidth - 1, 0))
        assertEquals(2, luma(0, outHeight - 1))
        assertEquals(3, luma(outWidth - 1, outHeight - 1))
        assertEquals(10, chroma(0, 0))
        assertEquals(11, chroma(outWidth - 1, 0))
        assertEquals(12, chroma(0, outHeight / 2 - 1))
        assertEquals(13, chroma(outWidth - 1, outHeight / 2 - 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsATruncatedFrame() {
        NvFrame.stripToGrid(ByteArray(10), 5120, 960)
    }
}
