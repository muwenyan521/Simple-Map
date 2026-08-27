package com.velorise.simplemap.client.cave;

import com.mojang.blaze3d.systems.RenderSystem;
import com.velorise.simplemap.client.MapLodPolicy;
import com.velorise.simplemap.client.MapAtlasMemoryTracker;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy;
import com.velorise.simplemap.client.gpu.CaveAtlasPboUploader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayDeque;

/**
 * Shared exact cave-page atlas with compact page mip levels.
 *
 * Every LOD uses the same profile-sized slot index, so switching zoom levels only changes
 * the texture and source rectangle. Every atlas level is represented by a custom
 * GPU-only AbstractTexture, so resource reloads recreate storage without retaining
 * an atlas-sized NativeImage on the Java/native heap.
 */
final class CaveTextureAtlas {
    static final int PAGE_SIZE = 64;
    static final int SLOT_COLUMNS = MapMemoryBudgetPolicy.caveExactColumns();
    static final int SLOT_COUNT = SLOT_COLUMNS * SLOT_COLUMNS;
    static final int LOD_COUNT = 4;

    private static final int[] LOD_SIZES = { 64, 32, 16, 8 };

    private final CaveAtlasTexture[] textures = new CaveAtlasTexture[LOD_COUNT];
    private final ResourceLocation[] locations = new ResourceLocation[LOD_COUNT];
    private final int[] textureIds = new int[LOD_COUNT];
    private final boolean[] allocatedSlots = new boolean[SLOT_COUNT];
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>(SLOT_COUNT);
    /** Published slots removed from the back table but still visible in the front table. */
    private final ArrayDeque<Integer> quarantinedSlots = new ArrayDeque<>();
    private final CaveAtlasPboUploader uploader = new CaveAtlasPboUploader();
    private final int[][] gutteredUploads = new int[LOD_COUNT][];

    private boolean initialized;
    private long storageGeneration;

    CaveTextureAtlas() {
        for (int lod = 0; lod < LOD_COUNT; lod++) {
            int pitch = LOD_SIZES[lod] + 2;
            gutteredUploads[lod] = new int[pitch * pitch];
        }
        refillFreeSlots();
    }

    void ensureInitialized() {
        initialize();
    }

    long storageGeneration() {
        return storageGeneration;
    }

    boolean hasQuarantinedSlots() {
        return !quarantinedSlots.isEmpty();
    }

    int availableSlotCount() {
        return freeSlots.size();
    }

    int acquireSlot() {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        Integer slot = freeSlots.pollFirst();
        if (slot == null) return -1;
        allocatedSlots[slot] = true;
        return slot;
    }

    void releaseSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT || !allocatedSlots[slot]) return;
        allocatedSlots[slot] = false;
        freeSlots.addLast(slot);
    }

    /** Release a slot that was visible through the front page table. */
    void releasePublishedSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT || !allocatedSlots[slot]) return;
        if (!quarantinedSlots.contains(slot)) quarantinedSlots.addLast(slot);
    }

    void onPageTableFrameBoundary() {
        RenderSystem.assertOnRenderThreadOrInit();
        while (!quarantinedSlots.isEmpty()) {
            int slot = quarantinedSlots.removeFirst();
            if (slot < 0 || slot >= SLOT_COUNT || !allocatedSlots[slot]) continue;
            allocatedSlots[slot] = false;
            freeSlots.addLast(slot);
        }
    }

    void resetSlots() {
        RenderSystem.assertOnRenderThreadOrInit();
        for (int slot = 0; slot < allocatedSlots.length; slot++) {
            allocatedSlots[slot] = false;
        }
        quarantinedSlots.clear();
        refillFreeSlots();
    }

    CaveAtlasRegion region(int slot, float scale) {
        return regionForLod(slot, lodForScale(scale));
    }

    CaveAtlasRegion regionForLod(int slot, int lod) {
        if (!initialized || slot < 0 || slot >= SLOT_COUNT
                || !allocatedSlots[slot] || lod < 0 || lod >= LOD_COUNT) return null;
        int pageSize = LOD_SIZES[lod];
        int pitch = pageSize + 2;
        int atlasSize = pitch * SLOT_COLUMNS;
        int sourceX = (slot % SLOT_COLUMNS) * pitch + 1;
        int sourceY = (slot / SLOT_COLUMNS) * pitch + 1;
        return new CaveAtlasRegion(locations[lod], sourceX, sourceY,
                pageSize, atlasSize);
    }

    void upload(int slot, int lod, int[] pixels, DirtyRect dirty) {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        if (slot < 0 || slot >= SLOT_COUNT || !allocatedSlots[slot]) {
            throw new IllegalStateException("Attempted to upload an unallocated cave atlas slot");
        }
        if (lod < 0 || lod >= LOD_COUNT || dirty == null || dirty.isEmpty()) return;

        int pageSize = LOD_SIZES[lod];
        int pitch = pageSize + 2;
        int atlasX = (slot % SLOT_COLUMNS) * pitch;
        int atlasY = (slot / SLOT_COLUMNS) * pitch;

        /*
         * PASS139: DirtyPlan already computes sub-rectangles, but the old atlas
         * method ignored them and uploaded the complete guttered page once for
         * every dirty rectangle. A fragmented 8-rect update across four LODs could
         * therefore issue dozens of full-page glTexSubImage2D transfers. The
         * current log captured a 172 ms exact-upload outlier.
         *
         * Initial/full publication still uses one contiguous guttered transfer.
         * Partial updates upload only the changed interior plus the affected
         * one-pixel gutters when an edge changed.
         */
        if (dirty.minX() == 0 && dirty.minY() == 0
                && dirty.maxX() == pageSize - 1
                && dirty.maxY() == pageSize - 1) {
            int[] guttered = gutteredUploads[lod];
            AtlasGutter.copyOnePixelBorder(pixels, pageSize, guttered);
            uploader.upload(textureIds[lod], atlasX, atlasY,
                    pitch, pitch, guttered, pitch, 0, 0);
            return;
        }

        /*
         * PASS140: an edge update needs gutter maintenance. Multiple 1-pixel
         * subuploads cost far more GL/PBO state changes than copying this tiny page
         * (~17 KiB with gutter), so collapse edge-touching updates to one contiguous
         * guttered transfer. Interior changes remain one sub-rectangle upload.
         */
        boolean touchesEdge = dirty.minX() == 0 || dirty.minY() == 0
                || dirty.maxX() == pageSize - 1
                || dirty.maxY() == pageSize - 1;
        if (touchesEdge) {
            int[] guttered = gutteredUploads[lod];
            AtlasGutter.copyOnePixelBorder(pixels, pageSize, guttered);
            uploader.upload(textureIds[lod], atlasX, atlasY,
                    pitch, pitch, guttered, pitch, 0, 0);
            return;
        }

        int width = dirty.maxX() - dirty.minX() + 1;
        int height = dirty.maxY() - dirty.minY() + 1;
        uploader.upload(textureIds[lod],
                atlasX + 1 + dirty.minX(),
                atlasY + 1 + dirty.minY(),
                width, height, pixels, pageSize,
                dirty.minX(), dirty.minY());
    }

    static int lodSize(int lod) {
        return LOD_SIZES[lod];
    }

    static int lodForScale(float scale) {
        return MapLodPolicy.leafMipLevel(scale, LOD_COUNT - 1);
    }

    private void initialize() {
        if (initialized) return;
        RenderSystem.assertOnRenderThreadOrInit();
        Minecraft minecraft = Minecraft.getInstance();
        for (int lod = 0; lod < LOD_COUNT; lod++) {
            int pageSize = LOD_SIZES[lod];
            int atlasSize = (pageSize + 2) * SLOT_COLUMNS;
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                    "simplemap", "cave_atlas/lod_" + pageSize);
            CaveAtlasTexture texture = new CaveAtlasTexture(atlasSize, this::markStorageAllocated);
            minecraft.getTextureManager().register(location, texture);
            texture.allocateStorage();
            MapAtlasMemoryTracker.getInstance().register(
                    "cave_exact_lod_" + pageSize,
                    (long) atlasSize * atlasSize * Integer.BYTES);

            int textureId = texture.getId();
            textures[lod] = texture;
            locations[lod] = location;
            textureIds[lod] = textureId;
        }
        initialized = true;
    }

    private void markStorageAllocated() {
        storageGeneration++;
    }

    private void refillFreeSlots() {
        freeSlots.clear();
        for (int slot = 0; slot < SLOT_COUNT; slot++) freeSlots.addLast(slot);
    }

    record DirtyRect(int minX, int minY, int maxX, int maxY) {
        static DirtyRect full(int size) {
            return new DirtyRect(0, 0, size - 1, size - 1);
        }

        boolean isEmpty() {
            return maxX < minX || maxY < minY;
        }

        int width() {
            return isEmpty() ? 0 : maxX - minX + 1;
        }

        int height() {
            return isEmpty() ? 0 : maxY - minY + 1;
        }
    }
}
