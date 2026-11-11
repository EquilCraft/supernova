package com.mitchej123.supernova.mixin.early.engine;

import com.mitchej123.supernova.core.SupernovaCore;
import com.mitchej123.supernova.light.ChunkLightHelper;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.light.WorldLightManager;
import com.mitchej123.supernova.world.SupernovaWorld;
import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Chunk.class)
public abstract class MixinChunk_FillChunkClient implements SupernovaChunk {

    @Shadow public World worldObj;
    @Final @Shadow public int xPosition;
    @Final @Shadow public int zPosition;
    @Shadow private ExtendedBlockStorage[] storageArrays;

    /** Blocks of the sections a partial update is about to overwrite, as {@code id << 32 | meta}; null between packets. */
    @Unique private long[][] supernova$blocksBeforeUpdate;
    /** Vanilla sky and block light of those sections, which the packet overwrites with the server's. */
    @Unique private byte[][] supernova$skyBeforeUpdate;
    @Unique private byte[][] supernova$blockBeforeUpdate;

    @Inject(method = "fillChunk", at = @At("HEAD"))
    private void supernova$beforeFillChunk(byte[] data, int sectionMask, int extendedMask, boolean forceUpdate, CallbackInfo ci) {
        this.supernova$blocksBeforeUpdate = null;
        this.supernova$skyBeforeUpdate = null;
        this.supernova$blockBeforeUpdate = null;
        if (forceUpdate || this.worldObj == null || !this.worldObj.isRemote) return;
        final int count = this.storageArrays.length;
        final long[][] blocks = new long[count][];
        final byte[][] sky = new byte[count][];
        final byte[][] block = new byte[count][];
        for (int sectionY = 0; sectionY < count; ++sectionY) {
            if ((sectionMask & (1 << sectionY)) == 0) continue;
            final ExtendedBlockStorage section = this.storageArrays[sectionY];
            blocks[sectionY] = supernova$snapshot(section);
            if (section == null) continue;
            if (section.getSkylightArray() != null) sky[sectionY] = section.getSkylightArray().data.clone();
            if (section.getBlocklightArray() != null) block[sectionY] = section.getBlocklightArray().data.clone();
        }
        this.supernova$blocksBeforeUpdate = blocks;
        this.supernova$skyBeforeUpdate = sky;
        this.supernova$blockBeforeUpdate = block;
    }

    @Inject(method = "fillChunk", at = @At("RETURN"))
    private void supernova$onFillChunk(byte[] data, int sectionMask, int extendedMask, boolean forceUpdate, CallbackInfo ci) {
        final long[][] before = this.supernova$blocksBeforeUpdate;
        final byte[][] skyBefore = this.supernova$skyBeforeUpdate;
        final byte[][] blockBefore = this.supernova$blockBeforeUpdate;
        this.supernova$blocksBeforeUpdate = null;
        this.supernova$skyBeforeUpdate = null;
        this.supernova$blockBeforeUpdate = null;
        if (this.worldObj == null || !this.worldObj.isRemote) return;
        final WorldLightManager iface = ((SupernovaWorld) this.worldObj).supernova$getLightManager();
        if (iface == null) return;

        if (before != null) {
            // A partial update is a burst of block changes sent as whole sections. The light that comes with it covers only those sections, at
            // whatever point the server's workers had reached, while the changes reach further: into the sections above and below and into the next
            // chunk. So this side keeps its own light, as for single block changes, and lights the changes itself. The RGB data managers leave
            // partial updates alone; only vanilla's arrays have to be put back.
            for (int sectionY = 0; sectionY < before.length; ++sectionY) {
                if ((sectionMask & (1 << sectionY)) == 0) continue;
                supernova$restoreLight(sectionY, skyBefore[sectionY], blockBefore[sectionY]);
                supernova$queueChangedBlocks(iface, sectionY, before[sectionY], this.storageArrays[sectionY]);
            }
            // Tile entities may have come and gone with the blocks.
            iface.registerChunk((Chunk) (Object) this);
            iface.queueEdgeChecks(this.xPosition, this.zPosition);
            return;
        }

        // With ChunkAPI the packet already installed authoritative R/G/B; importing vanilla's scalar nibbles here would overwrite it.
        if (!SupernovaCore.CHUNKAPI_PRESENT) {
            ChunkLightHelper.importVanillaSky(this.getSkyNibbles(), this.getSkyNibblesG(), this.getSkyNibblesB(), this.storageArrays, false);
            ChunkLightHelper.importVanillaBlock(this.getBlockNibblesR(), this.getBlockNibblesG(), this.getBlockNibblesB(), this.storageArrays);
        }

        iface.registerChunk((Chunk) (Object) this);
        this.setLightReady(true);

        // The server only sends final light; edge checks just fix a seam with a neighbor that arrived out of order.
        iface.queueEdgeChecks(this.xPosition, this.zPosition);
    }

    /** A section the update created starts from this side's nibbles, as one a block placement creates does. */
    @Unique
    private void supernova$restoreLight(final int sectionY, final byte[] sky, final byte[] block) {
        final ExtendedBlockStorage section = this.storageArrays[sectionY];
        if (section == null) return;
        if (sky == null && block == null) {
            ChunkLightHelper.seedVanillaSection(section, this.getSkyNibblesR(), this.getSkyNibblesG(), this.getSkyNibblesB(), this.getBlockNibblesR(),
                this.getBlockNibblesG(), this.getBlockNibblesB(), sectionY);
            return;
        }
        synchronized (section) {
            if (sky != null && section.getSkylightArray() != null) System.arraycopy(sky, 0, section.getSkylightArray().data, 0, sky.length);
            if (block != null && section.getBlocklightArray() != null) System.arraycopy(block, 0, section.getBlocklightArray().data, 0, block.length);
        }
    }

    /** Null for a section that does not exist, which reads as all air. */
    @Unique
    private static long[] supernova$snapshot(final ExtendedBlockStorage section) {
        if (section == null) return null;
        final long[] blocks = new long[16 * 16 * 16];
        for (int index = 0; index < blocks.length; ++index) {
            blocks[index] = supernova$blockAt(section, index & 15, index >> 8, (index >> 4) & 15);
        }
        return blocks;
    }

    @Unique
    private static long supernova$blockAt(final ExtendedBlockStorage section, final int x, final int y, final int z) {
        if (section == null) return 0L;
        final long id = Block.getIdFromBlock(section.getBlockByExtId(x, y, z));
        return (id << 32) | (section.getExtBlockMetadata(x, y, z) & 0xFFFFFFFFL);
    }

    @Unique
    private void supernova$queueChangedBlocks(final WorldLightManager iface, final int sectionY, final long[] before, final ExtendedBlockStorage after) {
        if (before == null && after == null) return;
        final int baseX = this.xPosition << 4;
        final int baseY = sectionY << 4;
        final int baseZ = this.zPosition << 4;
        for (int index = 0; index < 16 * 16 * 16; ++index) {
            final int x = index & 15, y = index >> 8, z = (index >> 4) & 15;
            final long was = before == null ? 0L : before[index];
            if (was == supernova$blockAt(after, x, y, z)) continue;
            iface.queueBlockChange(baseX | x, baseY | y, baseZ | z);
        }
    }
}
