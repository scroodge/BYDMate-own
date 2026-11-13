package com.bydmate.app.data.remote.camera

/**
 * Pure NV21 layout helpers for frames from the BYD `bmmcamera` preview callback.
 *
 * The surround-view camera delivers its four fisheye cameras side by side as one
 * 4:1 strip (5120×960 on `way`, 2026-11-06). [stripToGrid] rearranges that strip
 * into a 2×2 grid, the same layout as di+'s `pano` view, without decoding to RGB.
 */
object NvFrame {
    const val STRIP_TILES = 4
    private const val GRID_COLUMNS = 2

    /** Bytes in one NV21 frame: a full-size Y plane plus a half-height interleaved VU plane. */
    fun nv21Size(width: Int, height: Int): Int = width * height * 3 / 2

    /**
     * True when the frame is four 4:3 tiles side by side, e.g. 5120×960. Anything else
     * (a single 16:9 camera, an unknown layout) is not treated as the surround strip.
     */
    fun isSurroundStrip(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || width % STRIP_TILES != 0 || height % 2 != 0) return false
        val tileWidth = width / STRIP_TILES
        return tileWidth % 2 == 0 && tileWidth * 3 == height * 4
    }

    /**
     * Rearranges an NV21 strip of [STRIP_TILES] tiles into a 2×2 grid. Tile order is kept:
     * tile 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right.
     *
     * @return the grid frame, `(width / 2) × (height * 2)`.
     */
    fun stripToGrid(nv21: ByteArray, width: Int, height: Int): ByteArray {
        require(isSurroundStrip(width, height)) { "not a $STRIP_TILES-tile strip: ${width}x$height" }
        require(nv21.size >= nv21Size(width, height)) { "frame too short: ${nv21.size}" }
        val tileWidth = width / STRIP_TILES
        val outWidth = tileWidth * GRID_COLUMNS
        val outHeight = height * (STRIP_TILES / GRID_COLUMNS)
        val out = ByteArray(nv21Size(outWidth, outHeight))
        val srcUv = width * height
        val dstUv = outWidth * outHeight
        val uvRows = height / 2
        for (tile in 0 until STRIP_TILES) {
            val column = tile % GRID_COLUMNS
            val row = tile / GRID_COLUMNS
            val srcX = tile * tileWidth
            val dstX = column * tileWidth
            for (y in 0 until height) {
                System.arraycopy(nv21, y * width + srcX, out, (row * height + y) * outWidth + dstX, tileWidth)
            }
            // VU pairs: a tile of width W covers W bytes of each chroma row.
            for (y in 0 until uvRows) {
                System.arraycopy(
                    nv21, srcUv + y * width + srcX,
                    out, dstUv + (row * uvRows + y) * outWidth + dstX,
                    tileWidth,
                )
            }
        }
        return out
    }
}
