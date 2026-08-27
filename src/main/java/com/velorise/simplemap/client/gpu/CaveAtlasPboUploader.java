package com.velorise.simplemap.client.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.velorise.simplemap.client.cave.CaveTelemetry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

import java.nio.ByteBuffer;

/**
 * Render-thread-only PBO transfer engine for exact cave-atlas rectangles.
 *
 * <p>A three-entry orphaned PBO ring prevents reuse of storage that the driver may
 * still be consuming. {@code glTexSubImage2D} queues the GPU-side DMA immediately;
 * exact-page publication therefore remains transactionally correct while the CPU
 * avoids waiting for the texture copy. Any capability or driver failure switches
 * permanently to the direct upload path for the current process.</p>
 */
public final class CaveAtlasPboUploader implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int PBO_RING_SIZE = 3;
    /*
     * PASS140: every exact Cave rectangle is at most 66x66 RGBA (~17 KiB).
     * Orphaning/buffering a PBO for transfers this small adds more driver state
     * transitions than payload and the latest run still recorded a 342 ms exact
     * upload outlier. Keep the PBO implementation for future larger transfers, but
     * use the direct pooled native buffer path for these tiny exact-page updates.
     */
    private static final int PBO_MIN_UPLOAD_BYTES = 64 * 1024;
    private static final boolean VALIDATE_GL =
            Boolean.getBoolean("simplemap.validateCaveGl");

    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();
    private final CaveDirectBufferPool buffers = new CaveDirectBufferPool(PBO_RING_SIZE + 1);
    private final int[] unpackPbos = new int[PBO_RING_SIZE];

    private int pboCursor;
    private boolean pboInitialized;
    private boolean pboEnabled = !Boolean.getBoolean("simplemap.disableCavePbo");
    private boolean closed;

    /** Uploads one tightly packed rectangle from a larger ABGR source image. */
    public void upload(int textureId, int destinationX, int destinationY,
            int width, int height, int[] source, int sourceStride,
            int sourceX, int sourceY) {
        RenderSystem.assertOnRenderThreadOrInit();
        if (closed || width <= 0 || height <= 0 || source == null) return;
        validateSourceBounds(source, sourceStride, sourceX, sourceY, width, height);
        int byteCount = Math.multiplyExact(Math.multiplyExact(width, height),
                Integer.BYTES);
        ByteBuffer staging = buffers.acquire(byteCount);
        try {
            fillStaging(staging, source, sourceStride, sourceX, sourceY,
                    width, height);
            boolean pbo = pboEnabled && byteCount >= PBO_MIN_UPLOAD_BYTES
                    && uploadWithPbo(textureId, destinationX,
                            destinationY, width, height, staging);
            if (!pbo) {
                uploadDirect(textureId, destinationX, destinationY,
                        width, height, staging);
            }
            telemetry.recordGpuSubUpload(pbo, width * height);
        } finally {
            buffers.release(staging);
        }
    }

    private static void validateSourceBounds(int[] source, int sourceStride,
            int sourceX, int sourceY, int width, int height) {
        if (width > CaveDirectBufferPool.MAX_UPLOAD_EDGE
                || height > CaveDirectBufferPool.MAX_UPLOAD_EDGE) {
            throw new IllegalArgumentException(
                    "Cave upload rectangle exceeds staging capacity");
        }
        if (sourceStride <= 0 || sourceX < 0 || sourceY < 0
                || sourceX + width > sourceStride) {
            throw new IllegalArgumentException("Invalid cave upload source rectangle");
        }
        long lastExclusive = (long) (sourceY + height - 1) * sourceStride
                + sourceX + width;
        if (lastExclusive > source.length) {
            throw new IllegalArgumentException(
                    "Cave upload source rectangle exceeds pixel buffer");
        }
    }

    private static void fillStaging(ByteBuffer staging, int[] source,
            int sourceStride, int sourceX, int sourceY, int width, int height) {
        for (int row = 0; row < height; row++) {
            int sourceIndex = (sourceY + row) * sourceStride + sourceX;
            for (int column = 0; column < width; column++) {
                // Cave pixels are NativeImage ABGR integers. Explicit RGBA bytes
                // make transfer semantics independent of host byte order.
                int pixel = source[sourceIndex + column];
                staging.put((byte) (pixel & 0xFF));
                staging.put((byte) ((pixel >>> 8) & 0xFF));
                staging.put((byte) ((pixel >>> 16) & 0xFF));
                staging.put((byte) ((pixel >>> 24) & 0xFF));
            }
        }
        staging.flip();
    }

    private boolean uploadWithPbo(int textureId, int destinationX,
            int destinationY, int width, int height, ByteBuffer staging) {
        try {
            ensurePbos();
            if (!pboEnabled) return false;
            int pbo = unpackPbos[pboCursor];
            pboCursor = (pboCursor + 1) % unpackPbos.length;
            if (VALIDATE_GL) drainGlErrors();

            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
            // Orphan before writing. The old storage can remain owned by the GPU
            // while the CPU fills a fresh backing allocation without a fence wait.
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER,
                    staging.remaining(), GL15.GL_STREAM_DRAW);
            GL15.glBufferSubData(GL21.GL_PIXEL_UNPACK_BUFFER, 0L, staging);
            GlStateManager._bindTexture(textureId);
            preparePixelStore();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0,
                    destinationX, destinationY, width, height,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

            if (!VALIDATE_GL) return true;
            int error = GL11.glGetError();
            if (error == GL11.GL_NO_ERROR) return true;
            disablePbo("OpenGL error " + error + " during cave PBO upload", null);
        } catch (Throwable throwable) {
            disablePbo("Cave PBO upload failed", throwable);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }
        return false;
    }

    private static void uploadDirect(int textureId, int destinationX,
            int destinationY, int width, int height, ByteBuffer staging) {
        staging.rewind();
        GlStateManager._bindTexture(textureId);
        preparePixelStore();
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0,
                destinationX, destinationY, width, height,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, staging);
    }

    private static void preparePixelStore() {
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
    }

    private void ensurePbos() {
        if (pboInitialized || !pboEnabled) return;
        if (!GL.getCapabilities().OpenGL21) {
            disablePbo("OpenGL 2.1 pixel buffer objects are unavailable", null);
            return;
        }
        for (int index = 0; index < unpackPbos.length; index++) {
            unpackPbos[index] = GL15.glGenBuffers();
            if (unpackPbos[index] == 0) {
                disablePbo("Driver returned buffer id 0 for cave PBO", null);
                return;
            }
        }
        pboInitialized = true;
    }

    private void disablePbo(String reason, Throwable throwable) {
        if (!pboEnabled) return;
        pboEnabled = false;
        if (throwable == null) {
            LOGGER.warn("{}. Falling back to direct texture uploads.", reason);
        } else {
            LOGGER.warn("{}. Falling back to direct texture uploads.", reason,
                    throwable);
        }
        deletePbos();
    }

    private void deletePbos() {
        for (int index = 0; index < unpackPbos.length; index++) {
            int pbo = unpackPbos[index];
            if (pbo != 0) GL15.glDeleteBuffers(pbo);
            unpackPbos[index] = 0;
        }
        pboInitialized = false;
    }

    private static void drainGlErrors() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Drain stale errors so a failure can be attributed to this transfer.
        }
    }

    @Override
    public void close() {
        if (closed) return;
        RenderSystem.assertOnRenderThreadOrInit();
        closed = true;
        deletePbos();
        buffers.close();
    }
}
