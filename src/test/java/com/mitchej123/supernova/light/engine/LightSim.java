package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.ColoredLightSource;
import com.mitchej123.supernova.api.ColoredTranslucency;
import com.mitchej123.supernova.api.ExtendedSection;
import com.mitchej123.supernova.api.ExtendedWorld;
import com.mitchej123.supernova.api.FaceLightOcclusion;
import com.mitchej123.supernova.api.PackedColorLight;
import com.mitchej123.supernova.light.ChunkLightHelper;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.storage.StorageTestAccess;
import com.mitchej123.supernova.util.CoordinateUtils;
import com.mitchej123.supernova.util.SnapshotChunkMap;
import com.mitchej123.supernova.util.WorldUtil;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Drives the real engines over a small block of chunks the way the mixins do, and checks the light a reader would see against a brute-force flood fill.
 * Single-threaded: it exercises the algorithm, not the scheduling.
 */
public final class LightSim {

    /** Stands in for the World mixin; never constructed, only allocated. */
    static class SimWorld extends WorldServer implements ExtendedWorld {

        Long2ObjectOpenHashMap<Chunk> loaded;
        LightSim owner;

        private SimWorld() {
            super(null, null, null, 0, null, null);
        }

        @Override
        public Chunk supernova$getAnyChunkImmediately(final int chunkX, final int chunkZ) {
            return this.loaded.get(CoordinateUtils.getChunkKey(chunkX, chunkZ));
        }

        @Override
        public boolean supernova$hasChunkPendingLight(final int chunkX, final int chunkZ) {
            return false;
        }
    }

    /** A lit, ready chunk for tests that need the Chunk mixin's surface without an engine behind it. */
    public static Chunk readyChunk(final World world, final int x, final int z) {
        final SimChunk chunk = new SimChunk(world, x, z, false);
        chunk.ready = true;
        return chunk;
    }

    /** Stands in for the Chunk mixin. */
    static final class SimChunk extends Chunk implements SupernovaChunk {

        SWMRNibbleArray[] sky = SupernovaEngine.getFilledEmptyLight();
        SWMRNibbleArray[] skyG;
        SWMRNibbleArray[] skyB;
        SWMRNibbleArray[] blockR = SupernovaEngine.getFilledEmptyLight();
        SWMRNibbleArray[] blockG;
        SWMRNibbleArray[] blockB;
        boolean[] skyEmptiness;
        boolean[] blockEmptiness;
        boolean ready;
        boolean usable;

        /** Scalar chunks carry no green or blue arrays, as MixinChunk leaves them in scalar mode. */
        SimChunk(final World world, final int x, final int z, final boolean scalar) {
            super(world, x, z);
            if (!scalar) {
                this.skyG = SupernovaEngine.getFilledEmptyLight();
                this.skyB = SupernovaEngine.getFilledEmptyLight();
                this.blockG = SupernovaEngine.getFilledEmptyLight();
                this.blockB = SupernovaEngine.getFilledEmptyLight();
            }
        }

        @Override public void syncLightToVanilla() {
            ChunkLightHelper.syncSkyToVanilla(this.sky, this.skyG, this.skyB, this.getBlockStorageArray());
            ChunkLightHelper.syncBlockToVanilla(this.blockR, this.blockG, this.blockB, this.getBlockStorageArray());
        }
        @Override public void setLightReady(final boolean ready) {this.ready = ready;}
        @Override public void setLightUsable(final boolean usable) {this.usable = usable;}
        boolean skyUsable;
        boolean blockUsable;
        @Override public void setLaneLightUsable(final boolean sky, final boolean usable) {if (sky) this.skyUsable = usable; else this.blockUsable = usable;}
        @Override public boolean isSkyLightUsable() {return this.ready || this.usable || this.skyUsable;}
        @Override public boolean isBlockLightUsable() {return this.ready || this.usable || this.blockUsable;}
        @Override public void markLightDirty(final boolean sky) {}
        @Override public void clearLightDirty(final boolean sky) {}
        @Override public boolean isLightReady() {return this.ready;}
        @Override public boolean isLightUsable() {return this.ready || this.usable;}
        @Override public SWMRNibbleArray[] getSkyNibbles() {return this.sky;}
        @Override public void setSkyNibbles(final SWMRNibbleArray[] nibbles) {this.sky = nibbles;}
        @Override public SWMRNibbleArray[] getSkyNibblesR() {return this.sky;}
        @Override public SWMRNibbleArray[] getSkyNibblesG() {return this.skyG;}
        @Override public SWMRNibbleArray[] getSkyNibblesB() {return this.skyB;}
        @Override public void setSkyNibblesG(final SWMRNibbleArray[] nibbles) {this.skyG = nibbles;}
        @Override public void setSkyNibblesB(final SWMRNibbleArray[] nibbles) {this.skyB = nibbles;}
        @Override public boolean[] getSkyEmptinessMap() {return this.skyEmptiness;}
        @Override public void setSkyEmptinessMap(final boolean[] emptinessMap) {this.skyEmptiness = emptinessMap;}
        @Override public SWMRNibbleArray[] getBlockNibblesR() {return this.blockR;}
        @Override public SWMRNibbleArray[] getBlockNibblesG() {return this.blockG;}
        @Override public SWMRNibbleArray[] getBlockNibblesB() {return this.blockB;}
        @Override public void setBlockNibblesR(final SWMRNibbleArray[] nibbles) {this.blockR = nibbles;}
        @Override public void setBlockNibblesG(final SWMRNibbleArray[] nibbles) {this.blockG = nibbles;}
        @Override public void setBlockNibblesB(final SWMRNibbleArray[] nibbles) {this.blockB = nibbles;}
        @Override public boolean[] getBlockEmptinessMap() {return this.blockEmptiness;}
        @Override public void setBlockEmptinessMap(final boolean[] emptinessMap) {this.blockEmptiness = emptinessMap;}
    }

    private static final class SimSky extends SupernovaSkyEngine {
        SimSky(final SimWorld world, final SnapshotChunkMap map) {super(world, map);}
        @Override protected void markRenderSection(final int originX, final int originY, final int originZ) {
            ((SimWorld) this.world).owner.markRange(originX, originY, originZ, originX + 15, originY + 15, originZ + 15);
        }
    }

    private static final class SimBlock extends SupernovaBlockEngine {
        SimBlock(final SimWorld world, final SnapshotChunkMap map) {super(world, map);}
        @Override protected void markRenderSection(final int originX, final int originY, final int originZ) {
            ((SimWorld) this.world).owner.markRange(originX, originY, originZ, originX + 15, originY + 15, originZ + 15);
        }
    }

    private static final class SimScalarSky extends ScalarSkyEngine {
        SimScalarSky(final SimWorld world) {super(world);}
        @Override protected void markRenderSection(final int originX, final int originY, final int originZ) {
            ((SimWorld) this.world).owner.markRange(originX, originY, originZ, originX + 15, originY + 15, originZ + 15);
        }
    }

    private static final class SimScalarBlock extends ScalarBlockEngine {
        SimScalarBlock(final SimWorld world) {super(world);}
        @Override protected void markRenderSection(final int originX, final int originY, final int originZ) {
            ((SimWorld) this.world).owner.markRange(originX, originY, originZ, originX + 15, originY + 15, originZ + 15);
        }
    }

    final SimWorld world;
    final SnapshotChunkMap chunkMap = new SnapshotChunkMap();
    final SupernovaEngine sky;
    final SupernovaEngine block;
    final boolean scalar;
    final int minCx, maxCx, minCz, maxCz;

    LightSim(final boolean remote, final int minCx, final int maxCx, final int minCz, final int maxCz) {
        this(remote, false, minCx, maxCx, minCz, maxCz);
    }

    LightSim(final boolean remote, final boolean scalar, final int minCx, final int maxCx, final int minCz, final int maxCz) {
        this.world = MCBootstrap.createStubWorld(SimWorld.class, remote);
        this.world.loaded = new Long2ObjectOpenHashMap<>();
        this.world.owner = this;
        this.scalar = scalar;
        this.sky = scalar ? new SimScalarSky(this.world) : new SimSky(this.world, this.chunkMap);
        this.block = scalar ? new SimScalarBlock(this.world) : new SimBlock(this.world, this.chunkMap);
        this.minCx = minCx;
        this.maxCx = maxCx;
        this.minCz = minCz;
        this.maxCz = maxCz;
    }

    /** Creates an unlit, unregistered chunk; the caller fills in blocks and then loads it. */
    SimChunk newChunk(final int cx, final int cz) {
        return new SimChunk(this.world, cx, cz, this.scalar);
    }

    void register(final SimChunk chunk) {
        final long key = CoordinateUtils.getChunkKey(chunk.xPosition, chunk.zPosition);
        this.world.loaded.put(key, chunk);
        this.chunkMap.put(key, chunk);
    }

    void unregister(final int cx, final int cz) {
        final long key = CoordinateUtils.getChunkKey(cx, cz);
        this.world.loaded.remove(key);
        this.chunkMap.remove(key);
    }

    SimChunk chunk(final int cx, final int cz) {
        return (SimChunk) this.world.loaded.get(CoordinateUtils.getChunkKey(cx, cz));
    }

    /** Raw block write with no light work, for building terrain before the chunk is lit. */
    static void rawSet(final SimChunk chunk, final int x, final int y, final int z, final Block block) {
        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        ExtendedBlockStorage section = sections[y >> 4];
        if (section == null) {
            if (block == Blocks.air) return;
            section = sections[y >> 4] = new TestSection(y >> 4 << 4, true);
            // generateSkylightMap's scalar baseline, for whatever the engine leaves alone.
            Arrays.fill(section.getSkylightArray().data, (byte) 0xFF);
        }
        section.func_150818_a(x & 15, y & 15, z & 15, block);
    }

    /** Initial lighting as WorldLightManager sequences it: both lanes propagate, then both lanes reconcile edges. */
    void lightChunk(final SimChunk chunk) {
        chunk.ready = false;
        chunk.usable = false;
        final Boolean[] empty = SupernovaEngine.getEmptySectionsForChunk(chunk);
        this.sky.light(chunk, empty.clone(), false);
        this.block.light(chunk, empty.clone(), false);
        chunk.usable = true;
        this.sky.checkChunkEdges(chunk.xPosition, chunk.zPosition, allSections());
        this.block.checkChunkEdges(chunk.xPosition, chunk.zPosition, allSections());
        chunk.syncLightToVanilla();
        chunk.ready = true;
        checkOverflow();
    }

    /** Pieces of {@link #lightChunk} for tests that interleave the lanes the way two worker threads do. */
    void beginLight(final SimChunk chunk) {
        chunk.ready = false;
        chunk.usable = false;
        chunk.skyUsable = false;
        chunk.blockUsable = false;
    }

    /** One lane's propagation pass, followed by what the coordinator does when it completes. */
    void propagateLane(final SimChunk chunk, final boolean skyLane) {
        final Boolean[] empty = SupernovaEngine.getEmptySectionsForChunk(chunk);
        (skyLane ? this.sky : this.block).light(chunk, empty, false);
        chunk.setLaneLightUsable(skyLane, true);
        if (chunk.skyUsable && chunk.blockUsable) chunk.usable = true;
        checkOverflow();
    }

    void finishLight(final SimChunk chunk) {
        this.sky.checkChunkEdges(chunk.xPosition, chunk.zPosition, allSections());
        this.block.checkChunkEdges(chunk.xPosition, chunk.zPosition, allSections());
        chunk.syncLightToVanilla();
        chunk.ready = true;
        checkOverflow();
    }

    /** Runs the queued tasks on one lane only, leaving them queued for the other. */
    void flushLane(final boolean skyLane) {
        final SupernovaEngine engine = skyLane ? this.sky : this.block;
        final long[] ordered = this.pendingPositions.keySet().toLongArray();
        Arrays.sort(ordered);
        for (final long key : ordered) {
            final Boolean[] changed = this.pendingSections.get(key);
            engine.blocksChangedInChunk(CoordinateUtils.getChunkX(key), CoordinateUtils.getChunkZ(key), new IntOpenHashSet(this.pendingPositions.get(key)),
                changed == null ? null : changed.clone());
        }
        checkOverflow();
    }

    /** A chunk as it sits on disk: blocks plus the light the data managers wrote. */
    static final class Saved {

        final int cx, cz;
        final ExtendedBlockStorage[] sections;
        final NBTTagCompound sky, block;

        Saved(final SimChunk chunk) {
            this.cx = chunk.xPosition;
            this.cz = chunk.zPosition;
            this.sections = chunk.getBlockStorageArray().clone();
            this.sky = StorageTestAccess.save(chunk.sky, chunk.skyG, chunk.skyB);
            this.block = StorageTestAccess.save(chunk.blockR, chunk.blockG, chunk.blockB);
        }
    }

    /** Saves and unloads; the chunk object is gone afterwards, as on a real unload. */
    Saved unload(final int cx, final int cz) {
        final Saved saved = new Saved(chunk(cx, cz));
        unregister(cx, cz);
        return saved;
    }

    /** MixinChunk.onChunkLoad for a chunk that comes back with saved light. */
    SimChunk load(final Saved saved) {
        final SimChunk chunk = newChunk(saved.cx, saved.cz);
        System.arraycopy(saved.sections, 0, chunk.getBlockStorageArray(), 0, saved.sections.length);
        StorageTestAccess.load(saved.sky, chunk.sky, chunk.skyG, chunk.skyB);
        StorageTestAccess.load(saved.block, chunk.blockR, chunk.blockG, chunk.blockB);
        register(chunk);
        chunk.ready = true;
        if (this.edgeCheckOnLoad) edgeCheck(saved.cx, saved.cz);
        return chunk;
    }

    /**
     * Delivers one chunk to {@code client} as a full chunk packet does: blocks for the sections the vanilla mask names, light through the data managers,
     * then the fillChunk hook.
     */
    void sendTo(final LightSim client, final int cx, final int cz) {
        final SimChunk source = chunk(cx, cz);
        final ExtendedBlockStorage[] sections = source.getBlockStorageArray();
        int mask = 0;
        for (int i = 0; i < sections.length; ++i) {
            if (sections[i] != null && !sections[i].isEmpty()) mask |= 1 << i;
        }

        // A full chunk always lands in a fresh Chunk object on the client.
        final SimChunk target = client.newChunk(cx, cz);
        final ExtendedBlockStorage[] received = target.getBlockStorageArray();
        for (int i = 0; i < sections.length; ++i) {
            if ((mask & (1 << i)) != 0) received[i] = copySection(sections[i], i);
        }
        for (final boolean skyLane : new boolean[] { true, false }) {
            StorageTestAccess.readPacket(target, skyLane, mask, true,
                StorageTestAccess.writePacket(source, skyLane, mask, true));
        }
        client.register(target);
        target.ready = true;
        client.markRange(cx << 4, 0, cz << 4, (cx << 4) + 15, 256, (cz << 4) + 15);
        client.frame();
        if (client.edgeCheckOnSend) client.edgeCheck(cx, cz);
        client.frame();
    }

    /** Blocks and vanilla light of one section, as ChunkAPI's vanilla managers carry them. */
    private static TestSection copySection(final ExtendedBlockStorage from, final int sectionY) {
        final TestSection copy = new TestSection(sectionY << 4, true);
        copyInto(from, copy);
        return copy;
    }

    private static void copyInto(final ExtendedBlockStorage from, final ExtendedBlockStorage to) {
        for (int local = 0; local < 4096; ++local) {
            final int x = local & 15, y = local >> 8, z = (local >> 4) & 15;
            final Block block = from.getBlockByExtId(x, y, z);
            if (to.getBlockByExtId(x, y, z) != block) to.func_150818_a(x, y, z, block);
        }
        System.arraycopy(from.getSkylightArray().data, 0, to.getSkylightArray().data, 0, SWMRNibbleArray.ARRAY_SIZE);
        System.arraycopy(from.getBlocklightArray().data, 0, to.getBlocklightArray().data, 0, SWMRNibbleArray.ARRAY_SIZE);
    }

    /**
     * A partial chunk packet, as PlayerInstance sends once a tick's changes in one chunk pass Forge's clumping threshold: only the named sections, light
     * as the server has it at that moment, and the client keeps its Chunk object.
     */
    void sendPartialTo(final LightSim client, final int cx, final int cz, final int mask) {
        final SimChunk source = chunk(cx, cz);
        final SimChunk target = client.chunk(cx, cz);
        final ExtendedBlockStorage[] sections = source.getBlockStorageArray();
        final ExtendedBlockStorage[] received = target.getBlockStorageArray();
        final IntOpenHashSet changed = new IntOpenHashSet();
        for (int i = 0; i < sections.length; ++i) {
            if ((mask & (1 << i)) == 0) continue;
            if (sections[i] == null) continue;
            // MixinChunk_FillChunkClient compares the sections before and after, and keeps this side's light: vanilla's arrays are put back, and a
            // section the update creates is seeded from this side's nibbles.
            final boolean created = received[i] == null;
            if (created) received[i] = new TestSection(i << 4, true);
            for (int local = 0; local < 4096; ++local) {
                final int x = local & 15, y = local >> 8, z = (local >> 4) & 15;
                final Block block = sections[i].getBlockByExtId(x, y, z);
                if (received[i].getBlockByExtId(x, y, z) == block) continue;
                changed.add(x | (z << 4) | (((i << 4) | y) << 8));
                received[i].func_150818_a(x, y, z, block);
            }
            if (created) {
                ChunkLightHelper.seedVanillaSection(received[i], target.sky, target.skyG, target.skyB, target.blockR, target.blockG, target.blockB, i);
            }
        }
        for (final boolean skyLane : new boolean[] { true, false }) {
            StorageTestAccess.readPacket(target, skyLane, mask, false,
                StorageTestAccess.writePacket(source, skyLane, mask, false));
        }
        client.markRange(cx << 4, 0, cz << 4, (cx << 4) + 15, 256, (cz << 4) + 15);
        client.frame();
        // The changed positions go in as block changes, and the seams get checked, in one task.
        if (!changed.isEmpty()) {
            final long key = CoordinateUtils.getChunkKey(cx, cz);
            IntOpenHashSet positions = client.pendingPositions.get(key);
            if (positions == null) client.pendingPositions.put(key, positions = new IntOpenHashSet());
            positions.addAll(changed);
            client.flush();
        }
        client.edgeCheck(cx, cz);
        client.frame();
    }

    // ---- client render model ---------------------------------------------------------------------------------------

    /** A section's mesh is the light it saw when last rebuilt; marks only queue a rebuild, which happens on the next frame. */
    private final Long2ObjectOpenHashMap<int[]> meshes = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet pendingRebuilds = new LongOpenHashSet();

    private static long sectionKey(final int cx, final int sy, final int cz) {
        return ((long) cx & 0xFFFFFFL) | (((long) cz & 0xFFFFFFL) << 24) | ((long) sy << 48);
    }

    /** RenderGlobal.markBlockRangeForRenderUpdate: one block of slack on every side. */
    void markRange(final int x1, final int y1, final int z1, final int x2, final int y2, final int z2) {
        for (int cx = (x1 - 1) >> 4; cx <= (x2 + 1) >> 4; ++cx) {
            for (int cz = (z1 - 1) >> 4; cz <= (z2 + 1) >> 4; ++cz) {
                for (int sy = Math.max(0, (y1 - 1) >> 4); sy <= Math.min(15, (y2 + 1) >> 4); ++sy) {
                    this.pendingRebuilds.add(sectionKey(cx, sy, cz));
                }
            }
        }
    }

    /** One rendered frame: every section marked since the last one is rebuilt from the light as it stands. */
    void frame() {
        for (final long key : this.pendingRebuilds.toLongArray()) {
            final int cx = (int) (key << 40 >> 40), cz = (int) (key << 16 >> 40), sy = (int) (key >> 48);
            final int[] mesh = meshOf(cx, sy, cz);
            if (mesh == null) this.meshes.remove(key);
            else this.meshes.put(key, mesh);
        }
        this.pendingRebuilds.clear();
    }

    /**
     * Light a section's mesh is built from: its cells and the one-block shell smooth lighting samples. Per cell, Angelica's brightness (the section's
     * vanilla arrays, open sky and no block light where it has no storage) and the RGB both renderers tint with. Null when there is nothing to render.
     */
    private int[] meshOf(final int cx, final int sy, final int cz) {
        final SimChunk chunk = chunk(cx, cz);
        if (chunk == null) return null;
        final ExtendedBlockStorage own = chunk.getBlockStorageArray()[sy];
        if (own == null || ((ExtendedSection) own).supernova$hasNoBlocks()) return null;
        final int[] mesh = new int[18 * 18 * 18];
        int i = 0;
        for (int y = (sy << 4) - 1; y <= (sy << 4) + 16; ++y) {
            for (int z = (cz << 4) - 1; z <= (cz << 4) + 16; ++z) {
                for (int x = (cx << 4) - 1; x <= (cx << 4) + 16; ++x) {
                    mesh[i++] = renderedLight(x, y, z);
                }
            }
        }
        return mesh;
    }

    private int renderedLight(final int x, final int y, final int z) {
        final SimChunk chunk = chunk(x >> 4, z >> 4);
        if (chunk == null || y < 0 || y > 255) return -1;
        final ExtendedBlockStorage section = chunk.getBlockStorageArray()[y >> 4];
        final int vanillaSky = section == null ? 15 : section.getSkylightArray().get(x & 15, y & 15, z & 15);
        final int vanillaBlock = section == null ? 0 : section.getBlocklightArray().get(x & 15, y & 15, z & 15);
        return (vanillaSky << 28) | (vanillaBlock << 24) | (squeeze(readSky(x, y, z)) << 12) | squeeze(readBlock(x, y, z));
    }

    private static int squeeze(final int packed) {
        return (PackedColorLight.red(packed) << 8) | (PackedColorLight.green(packed) << 4) | PackedColorLight.blue(packed);
    }

    /** Sections whose mesh no longer matches the light: what stays wrong on screen until something nearby makes them rebuild. */
    String staleMeshes() {
        final StringBuilder out = new StringBuilder();
        int stale = 0;
        for (int cx = this.minCx; cx <= this.maxCx; ++cx) {
            for (int cz = this.minCz; cz <= this.maxCz; ++cz) {
                for (int sy = 0; sy < 16; ++sy) {
                    final int[] now = meshOf(cx, sy, cz);
                    if (now == null) continue;
                    final int[] built = this.meshes.get(sectionKey(cx, sy, cz));
                    if (built != null && Arrays.equals(built, now)) continue;
                    if (stale++ < 8) out.append(String.format("%n  section (%d,%d,%d): %s", cx, sy, cz, built == null ? "never built" : firstDifference(built, now, cx, sy, cz)));
                }
            }
        }
        return stale == 0 ? null : stale + " stale section meshes:" + out;
    }

    private static String firstDifference(final int[] built, final int[] now, final int cx, final int sy, final int cz) {
        for (int i = 0; i < now.length; ++i) {
            if (built[i] == now[i]) continue;
            final int x = (cx << 4) - 1 + i % 18, z = (cz << 4) - 1 + (i / 18) % 18, y = (sy << 4) - 1 + i / (18 * 18);
            return String.format("at (%d,%d,%d) built %08x, now %08x", x, y, z, built[i], now[i]);
        }
        return "?";
    }

    /** Cells where Angelica's brightness (vanilla arrays) disagrees with the RGB light it tints with. */
    String vanillaMismatches() {
        final StringBuilder out = new StringBuilder();
        int bad = 0;
        for (int x = this.minCx << 4; x < (this.maxCx + 1) << 4; ++x) {
            for (int z = this.minCz << 4; z < (this.maxCz + 1) << 4; ++z) {
                final SimChunk chunk = chunk(x >> 4, z >> 4);
                if (chunk == null) continue;
                for (int y = 0; y < 256; ++y) {
                    final ExtendedBlockStorage section = chunk.getBlockStorageArray()[y >> 4];
                    if (section == null) continue;
                    final int sky = PackedColorLight.maxComponent(readSky(x, y, z));
                    final int block = PackedColorLight.maxComponent(readBlock(x, y, z));
                    final int vSky = section.getSkylightArray().get(x & 15, y & 15, z & 15);
                    final int vBlock = section.getBlocklightArray().get(x & 15, y & 15, z & 15);
                    if (vSky != sky || vBlock != block) {
                        if (bad++ < 8) out.append(String.format("%n  (%d,%d,%d) vanilla sky %d block %d, rgb max sky %d block %d", x, y, z, vSky, vBlock, sky, block));
                    }
                }
            }
        }
        return bad == 0 ? null : bad + " cells where vanilla light disagrees:" + out;
    }


    boolean edgeCheckOnSend = true;

    /** Off only to show what a load without seam reconciliation leaves behind. */
    boolean edgeCheckOnLoad = true;

    /** Drops queued work once each lane has had its turn through {@link #flushLane}. */
    void discardPending() {
        this.pendingPositions.clear();
        this.pendingSections.clear();
    }

    void edgeCheck(final int cx, final int cz) {
        this.sky.checkChunkEdges(cx, cz, allSections());
        this.block.checkChunkEdges(cx, cz, allSections());
        checkOverflow();
    }

    /** A queue overflow drops light silently; in a world this small it can only mean a wave that never stops. */
    private void checkOverflow() {
        if (this.sky.wasQueueOverflowed() || this.block.wasQueueOverflowed()) throw new IllegalStateException("light queue overflowed");
    }

    static IntOpenHashSet allSections() {
        final IntOpenHashSet set = new IntOpenHashSet();
        for (int s = WorldUtil.getMinLightSection(); s <= WorldUtil.getMaxLightSection(); ++s) set.add(s);
        return set;
    }

    /** World.setBlock as the mixins see it: section-creation hook, then one block-change task per lane. */
    void setBlock(final int x, final int y, final int z, final Block block) {
        if (writeBlock(x, y, z, block)) flush();
    }

    /** A block change as the client applies it: render mark for the block, light work on the next drain, then a frame. */
    void setBlockOnClient(final int x, final int y, final int z, final Block block) {
        if (!writeBlock(x, y, z, block)) return;
        markRange(x, y, z, x, y, z);
        frame();
        flush();
        frame();
    }

    private final Long2ObjectOpenHashMap<IntOpenHashSet> pendingPositions = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Boolean[]> pendingSections = new Long2ObjectOpenHashMap<>();

    /** Changes the block and queues the light work without running it, the way a tick batches many edits into one task per chunk. */
    boolean writeBlock(final int x, final int y, final int z, final Block block) {
        final SimChunk chunk = chunk(x >> 4, z >> 4);
        final long key = CoordinateUtils.getChunkKey(x >> 4, z >> 4);
        final ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        ExtendedBlockStorage section = sections[y >> 4];
        if (section == null) {
            if (block == Blocks.air) return false;
            section = sections[y >> 4] = new TestSection(y >> 4 << 4, true);
            ChunkLightHelper.seedVanillaSection(section, chunk.sky, chunk.skyG, chunk.skyB, chunk.blockR, chunk.blockG, chunk.blockB, y >> 4);
            if (this.sectionHook) queueSection(key, y >> 4, false);
        }
        if (section.getBlockByExtId(x & 15, y & 15, z & 15) == block) return false;
        section.func_150818_a(x & 15, y & 15, z & 15, block);

        IntOpenHashSet positions = this.pendingPositions.get(key);
        if (positions == null) this.pendingPositions.put(key, positions = new IntOpenHashSet());
        positions.add((x & 15) | ((z & 15) << 4) | (y << 8));
        return true;
    }

    /** Off only to model a caller that bypasses the section-creation hook. */
    boolean sectionHook = true;

    void queueSection(final long key, final int sectionY, final boolean empty) {
        Boolean[] changed = this.pendingSections.get(key);
        if (changed == null) this.pendingSections.put(key, changed = new Boolean[WorldUtil.getTotalSections()]);
        changed[sectionY - WorldUtil.getMinSection()] = empty;
    }

    /** Runs every queued task: sky lane first, then block lane, as two independent workers would. */
    void flush() {
        final LongOpenHashSet keys = new LongOpenHashSet();
        keys.addAll(this.pendingPositions.keySet());
        keys.addAll(this.pendingSections.keySet());
        final long[] ordered = keys.toLongArray();
        Arrays.sort(ordered);
        for (final SupernovaEngine engine : new SupernovaEngine[] { this.sky, this.block }) {
            if (engine == null) continue;
            for (final long key : ordered) {
                final IntOpenHashSet positions = this.pendingPositions.get(key);
                final Boolean[] changed = this.pendingSections.get(key);
                engine.blocksChangedInChunk(CoordinateUtils.getChunkX(key), CoordinateUtils.getChunkZ(key), positions == null ? null : new IntOpenHashSet(positions),
                    changed == null ? null : changed.clone());
            }
        }
        this.pendingPositions.clear();
        this.pendingSections.clear();
        checkOverflow();
    }

    Block getBlock(final int x, final int y, final int z) {
        final SimChunk chunk = chunk(x >> 4, z >> 4);
        if (chunk == null || y < 0 || y > 255) return null;
        final ExtendedBlockStorage section = chunk.getBlockStorageArray()[y >> 4];
        return section == null ? Blocks.air : section.getBlockByExtId(x & 15, y & 15, z & 15);
    }

    /** Sky light as Chunk.getSavedLightValue and the renderers read it. */
    int readSky(final int x, final int y, final int z) {
        final SimChunk chunk = chunk(x >> 4, z >> 4);
        return ChunkLightHelper.readSkyRGB(chunk.sky, chunk.skyG, chunk.skyB, (y >> 4) - WorldUtil.getMinLightSection(), x, y, z);
    }

    int readBlock(final int x, final int y, final int z) {
        final SimChunk chunk = chunk(x >> 4, z >> 4);
        final int idx = (y >> 4) - WorldUtil.getMinLightSection();
        final int r = channel(chunk.blockR, idx, x, y, z);
        if (this.scalar) return PackedColorLight.pack(r, r, r);
        return PackedColorLight.pack(r, channel(chunk.blockG, idx, x, y, z), channel(chunk.blockB, idx, x, y, z));
    }

    private static int channel(final SWMRNibbleArray[] nibbles, final int idx, final int x, final int y, final int z) {
        final SWMRNibbleArray nib = nibbles[idx];
        return nib == null ? 0 : nib.getVisible(x, y, z);
    }

    // ---- reference -------------------------------------------------------------------------------------------------

    private int sizeX() {return (this.maxCx - this.minCx + 1) << 4;}

    private int sizeZ() {return (this.maxCz - this.minCz + 1) << 4;}

    private int cell(final int x, final int y, final int z) {
        return ((y * sizeZ()) + (z - (this.minCz << 4))) * sizeX() + (x - (this.minCx << 4));
    }

    private boolean inWorld(final int x, final int y, final int z) {
        return y >= 0 && y <= 255 && chunk(x >> 4, z >> 4) != null;
    }

    private static final int[][] DIRS = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, 1, 0 }, { 0, -1, 0 } };

    /** Expected packed sky light for every cell of the loaded area, 0 outside it. */
    int[] referenceSky() {
        final int[] light = new int[sizeX() * sizeZ() * 256];
        final ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = this.minCx << 4; x < (this.maxCx + 1) << 4; ++x) {
            for (int z = this.minCz << 4; z < (this.maxCz + 1) << 4; ++z) {
                if (chunk(x >> 4, z >> 4) == null) continue;
                // The direct beam: whole until something opaque, tinted by whatever translucent block it passes, and never attenuated by distance.
                int beam = PackedColorLight.pack(15, 15, 15);
                for (int y = 255; y >= 0; --y) {
                    final Block block = getBlock(x, y, z);
                    if (block.getLightOpacity() > 0) break;
                    if (!this.scalar && block instanceof ColoredTranslucency) {
                        beam = PackedColorLight.packedSubRGB(beam, absorptionOf(block));
                        if (PackedColorLight.maxComponent(beam) == 0) break;
                    }
                    light[cell(x, y, z)] = beam;
                    queue.add(new int[] { x, y, z });
                }
            }
        }
        flood(light, queue);
        return light;
    }

    int[] referenceBlock() {
        final int[] light = new int[sizeX() * sizeZ() * 256];
        final ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = this.minCx << 4; x < (this.maxCx + 1) << 4; ++x) {
            for (int z = this.minCz << 4; z < (this.maxCz + 1) << 4; ++z) {
                final SimChunk chunk = chunk(x >> 4, z >> 4);
                if (chunk == null) continue;
                for (int sy = 0; sy < 16; ++sy) {
                    final ExtendedBlockStorage section = chunk.getBlockStorageArray()[sy];
                    if (section == null || ((ExtendedSection) section).supernova$hasNoBlocks()) continue;
                    for (int ly = 0; ly < 16; ++ly) {
                        final Block b = section.getBlockByExtId(x & 15, ly, z & 15);
                        final int emission = this.scalar ? scalarEmissionOf(b) : emissionOf(b);
                        if (emission == 0) continue;
                        light[cell(x, (sy << 4) | ly, z)] = emission;
                        queue.add(new int[] { x, (sy << 4) | ly, z });
                    }
                }
            }
        }
        flood(light, queue);
        return light;
    }

    /** The scalar engine knows nothing of ColoredLightSource: vanilla's light value is all there is. */
    static int scalarEmissionOf(final Block block) {
        final int v = block.getLightValue() & 15;
        return PackedColorLight.pack(v, v, v);
    }

    static int emissionOf(final Block block) {
        if (block instanceof ColoredLightSource) {
            return ((ColoredLightSource) block).getColoredLightEmission(0);
        }
        final int v = block.getLightValue() & 15;
        return PackedColorLight.pack(v, v, v);
    }

    private static final ForgeDirection[] FORGE = {
        ForgeDirection.EAST, ForgeDirection.WEST,
        ForgeDirection.SOUTH, ForgeDirection.NORTH,
        ForgeDirection.UP, ForgeDirection.DOWN };

    /** Only FaceLightOcclusion implementors gate per face here; everything else is uniformly open or closed. */
    private static boolean faceSolid(final Block block, final int dir) {
        return block instanceof FaceLightOcclusion
            && ((FaceLightOcclusion) block).getDirectionalLightOpacity(0, FORGE[dir]) > 1;
    }

    /** What a block takes from light entering it; scalar light sees vanilla opacity only. */
    private int absorptionOf(final Block block) {
        if (!this.scalar && block instanceof ColoredTranslucency) {
            return PackedColorLight.transmittanceToAbsorption(((ColoredTranslucency) block).getColoredTransmittance(0));
        }
        final int opacity = Math.min(15, Math.max(1, block.getLightOpacity()));
        return PackedColorLight.pack(opacity, opacity, opacity);
    }

    private void flood(final int[] light, final ArrayDeque<int[]> queue) {
        while (!queue.isEmpty()) {
            final int[] p = queue.poll();
            final int from = light[cell(p[0], p[1], p[2])];
            final Block source = getBlock(p[0], p[1], p[2]);
            for (int dir = 0; dir < 6; ++dir) {
                final int[] d = DIRS[dir];
                final int nx = p[0] + d[0], ny = p[1] + d[1], nz = p[2] + d[2];
                if (!inWorld(nx, ny, nz)) continue;
                if (faceSolid(source, dir)) continue;
                final Block dest = getBlock(nx, ny, nz);
                final int absorption;
                if (dest instanceof FaceLightOcclusion) {
                    final int opacity = faceSolid(dest, dir ^ 1) ? 15 : 1;
                    absorption = PackedColorLight.pack(opacity, opacity, opacity);
                } else {
                    absorption = absorptionOf(dest);
                }
                final int target = PackedColorLight.packedSubRGB(from, absorption);
                final int idx = cell(nx, ny, nz);
                final int current = light[idx];
                if (!PackedColorLight.anyComponentGreater(target, current)) continue;
                light[idx] = PackedColorLight.packedMax(target, current);
                queue.add(new int[] { nx, ny, nz });
            }
        }
    }

    /** First mismatches against the reference, or null when the engines agree with it everywhere in the Y range. */
    String diff(final int minY, final int maxY) {
        final int[] sky = referenceSky();
        final int[] block = referenceBlock();
        final StringBuilder out = new StringBuilder();
        int mismatches = 0;
        for (int y = minY; y <= maxY; ++y) {
            for (int x = this.minCx << 4; x < (this.maxCx + 1) << 4; ++x) {
                for (int z = this.minCz << 4; z < (this.maxCz + 1) << 4; ++z) {
                    if (chunk(x >> 4, z >> 4) == null) continue;
                    final int idx = cell(x, y, z);
                    final int gotSky = readSky(x, y, z);
                    final int gotBlock = readBlock(x, y, z);
                    if (gotSky != sky[idx]) {
                        if (mismatches++ < 12) out.append(String.format("%n  sky   (%d,%d,%d) expected %06x got %06x", x, y, z, sky[idx], gotSky));
                    }
                    if (gotBlock != block[idx]) {
                        if (mismatches++ < 12) out.append(String.format("%n  block (%d,%d,%d) expected %06x got %06x", x, y, z, block[idx], gotBlock));
                    }
                }
            }
        }
        return mismatches == 0 ? null : mismatches + " mismatching cells:" + out;
    }
}
