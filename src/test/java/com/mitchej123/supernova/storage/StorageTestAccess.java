package com.mitchej123.supernova.storage;

import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.chunk.Chunk;

import java.nio.ByteBuffer;

/** Opens the package-private save and packet paths to tests in other packages. */
public final class StorageTestAccess {

    private static final class Manager extends AbstractSupernovaDataManager {

        Manager(final boolean sky) {
            super("test", "msg", sky);
        }

        @Override
        protected SWMRNibbleArray[] getNibblesR(final SupernovaChunk chunk) {return this.sky() ? chunk.getSkyNibblesR() : chunk.getBlockNibblesR();}

        @Override
        protected SWMRNibbleArray[] getNibblesG(final SupernovaChunk chunk) {return this.sky() ? chunk.getSkyNibblesG() : chunk.getBlockNibblesG();}

        @Override
        protected SWMRNibbleArray[] getNibblesB(final SupernovaChunk chunk) {return this.sky() ? chunk.getSkyNibblesB() : chunk.getBlockNibblesB();}

        private boolean sky() {return this == SKY;}
    }

    private static final Manager SKY = new Manager(true);
    private static final Manager BLOCK = new Manager(false);

    private StorageTestAccess() {}

    public static NBTTagCompound save(final SWMRNibbleArray[] r, final SWMRNibbleArray[] g, final SWMRNibbleArray[] b) {
        final NBTTagCompound nbt = new NBTTagCompound();
        BLOCK.writeChunkLight(nbt, r, g, b, true);
        return nbt;
    }

    public static boolean load(final NBTTagCompound nbt, final SWMRNibbleArray[] r, final SWMRNibbleArray[] g, final SWMRNibbleArray[] b) {
        return BLOCK.readChunkLight(nbt, r, g, b);
    }

    /** One lane's share of a chunk packet, as ChunkAPI hands it between the two sides. */
    public static ByteBuffer writePacket(final Chunk chunk, final boolean sky, final int subChunkMask, final boolean forceUpdate) {
        final Manager manager = sky ? SKY : BLOCK;
        final ByteBuffer buffer = ByteBuffer.allocate(manager.maxPacketSize());
        manager.writeToBuffer(chunk, subChunkMask, forceUpdate, buffer);
        buffer.flip();
        return buffer;
    }

    public static void readPacket(final Chunk chunk, final boolean sky, final int subChunkMask, final boolean forceUpdate, final ByteBuffer buffer) {
        (sky ? SKY : BLOCK).readFromBuffer(chunk, subChunkMask, forceUpdate, buffer);
    }
}
