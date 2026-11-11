package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.ColoredLightSource;
import com.mitchej123.supernova.api.FaceLightOcclusion;
import com.mitchej123.supernova.api.PositionalColoredTranslucency;
import com.mitchej123.supernova.light.engine.LightSim.SimChunk;
import com.mitchej123.supernova.light.engine.MCBootstrap.TestIds;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static com.mitchej123.supernova.api.PackedColorLight.pack;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Incremental light after arbitrary edits must equal a from-scratch flood fill of the same blocks. */
class LightSimRandomTest {

    public static class OrangeLamp extends Block implements ColoredLightSource {

        public OrangeLamp() {super(Material.rock);}

        @Override
        public int getColoredLightEmission(final int meta) {return pack(15, 9, 2);}
    }

    public static class BlueLamp extends Block implements ColoredLightSource {

        public BlueLamp() {super(Material.rock);}

        @Override
        public int getColoredLightEmission(final int meta) {return pack(1, 4, 12);}
    }

    /**
     * Stained glass; positional so the engine asks the block rather than a registry cache these tests leave alone. No channel passes entirely: the engine
     * does not carry level-1 light any further, which a block that takes nothing would otherwise let the flood fill do.
     */
    public static class TintedGlass extends Block implements PositionalColoredTranslucency {

        public TintedGlass() {super(Material.glass);}

        @Override
        public int getColoredTransmittance(final int meta) {return pack(6, 14, 9);}

        @Override
        public int getColoredTransmittance(final IBlockAccess world, final int meta, final int x, final int y, final int z) {return pack(6, 14, 9);}
    }

    /** Closed underneath, open everywhere else. */
    public static class BottomSlab extends Block implements FaceLightOcclusion {

        public BottomSlab() {super(Material.rock);}

        @Override
        public int getDirectionalLightOpacity(final int meta, final ForgeDirection direction) {
            return direction == ForgeDirection.DOWN ? 255 : 1;
        }
    }

    /** Closed on top, open everywhere else. */
    public static class TopSlab extends Block implements FaceLightOcclusion {

        public TopSlab() {super(Material.rock);}

        @Override
        public int getDirectionalLightOpacity(final int meta, final ForgeDirection direction) {
            return direction == ForgeDirection.UP ? 255 : 1;
        }
    }

    static Block[] palette;

    @BeforeAll
    static void registerBlocks() {
        final Block lamp = MCBootstrap.registerTestBlock(TestIds.RND_LAMP, "rnd_lamp", Block.class, 0, false, 15);
        final Block dim = MCBootstrap.registerTestBlock(TestIds.RND_DIM_LAMP, "rnd_dim_lamp", Block.class, 0, false, 7);
        final Block orange = MCBootstrap.registerTestBlock(TestIds.RND_ORANGE_LAMP, "rnd_orange_lamp", OrangeLamp.class, 0, false, 0);
        final Block blue = MCBootstrap.registerTestBlock(TestIds.RND_BLUE_LAMP, "rnd_blue_lamp", BlueLamp.class, 0, false, 0);
        final Block glass = MCBootstrap.registerTestBlock(TestIds.RND_GLASS, "rnd_glass", Block.class, 0, false, 0);
        final Block water = MCBootstrap.registerTestBlock(TestIds.RND_WATER, "rnd_water", Block.class, 3, false, 0);
        final Block glowstone = MCBootstrap.registerTestBlock(TestIds.RND_SOLID_LAMP, "rnd_solid_lamp", Block.class, 255, true, 15);
        final Block bottomSlab = MCBootstrap.registerTestBlock(TestIds.RND_BOTTOM_SLAB, "rnd_bottom_slab", BottomSlab.class, 255, false, 0);
        final Block topSlab = MCBootstrap.registerTestBlock(TestIds.RND_TOP_SLAB, "rnd_top_slab", TopSlab.class, 255, false, 0);
        final Block tinted = MCBootstrap.registerTestBlock(TestIds.RND_TINTED_GLASS, "rnd_tinted_glass", TintedGlass.class, 0, false, 0);
        FaceOcclusion.registerDefaults();
        palette = new Block[] {
            Blocks.air, Blocks.air, Blocks.air, Blocks.air, Blocks.stone, Blocks.stone, Blocks.stone, Blocks.stone,
            lamp, dim, orange, blue, glass, water, glowstone, bottomSlab, topSlab, bottomSlab, topSlab, tinted, tinted };
    }

    private static final int RADIUS = 1;

    /** Rolling terrain that straddles the y=64 section boundary, a few overhangs, and a slab of stone hanging in the air. */
    static LightSim buildWorld(final Random rng, final boolean scalar) {
        final LightSim sim = new LightSim(false, scalar, -RADIUS, RADIUS, -RADIUS, RADIUS);
        final int size = (2 * RADIUS + 1) * 16;
        final int[][] height = new int[size][size];
        for (int i = 0; i < size; ++i) {
            for (int j = 0; j < size; ++j) {
                final int left = i > 0 ? height[i - 1][j] : 60;
                final int up = j > 0 ? height[i][j - 1] : left;
                height[i][j] = Math.max(52, Math.min(74, (left + up) / 2 + rng.nextInt(5) - 2));
            }
        }
        final int islandY = 100 + rng.nextInt(40);
        final int ix = rng.nextInt(20) - 10, iz = rng.nextInt(20) - 10, iw = 6 + rng.nextInt(20), id = 6 + rng.nextInt(20);

        final List<SimChunk> chunks = new ArrayList<>();
        for (int cx = -RADIUS; cx <= RADIUS; ++cx) {
            for (int cz = -RADIUS; cz <= RADIUS; ++cz) {
                final SimChunk chunk = sim.newChunk(cx, cz);
                for (int x = 0; x < 16; ++x) {
                    for (int z = 0; z < 16; ++z) {
                        final int wx = (cx << 4) + x, wz = (cz << 4) + z;
                        final int h = height[wx + RADIUS * 16][wz + RADIUS * 16];
                        for (int y = 0; y <= h; ++y) {
                            // A band of caves so light has somewhere to go underground.
                            if (y > 40 && y < h - 2 && rng.nextInt(9) == 0) continue;
                            LightSim.rawSet(chunk, x, y, z, Blocks.stone);
                        }
                        if (rng.nextInt(40) == 0) {
                            final int top = h + 2 + rng.nextInt(12);
                            for (int y = h + 3; y <= top; ++y) LightSim.rawSet(chunk, x, y, z, Blocks.stone);
                        }
                        if (wx >= ix && wx < ix + iw && wz >= iz && wz < iz + id) {
                            LightSim.rawSet(chunk, x, islandY, z, Blocks.stone);
                            if (rng.nextInt(3) == 0) LightSim.rawSet(chunk, x, islandY - 1, z, Blocks.stone);
                        }
                        if (rng.nextInt(120) == 0) {
                            LightSim.rawSet(chunk, x, 30 + rng.nextInt(100), z, palette[8 + rng.nextInt(4)]);
                        }
                    }
                }
                chunks.add(chunk);
            }
        }
        // Generation order is whatever the player's path makes it.
        Collections.shuffle(chunks, rng);
        for (final SimChunk chunk : chunks) {
            sim.register(chunk);
            sim.lightChunk(chunk);
        }
        return sim;
    }

    static int surface(final LightSim sim, final int x, final int z) {
        for (int y = 90; y > 0; --y) {
            if (sim.getBlock(x, y, z) != Blocks.air) return y;
        }
        return 0;
    }

    private static void randomEdit(final LightSim sim, final Random rng) {
        final int span = (2 * RADIUS + 1) * 16;
        final int x = rng.nextInt(span) - RADIUS * 16;
        final int z = rng.nextInt(span) - RADIUS * 16;
        final int y;
        switch (rng.nextInt(4)) {
            case 0 -> y = Math.max(1, Math.min(250, surface(sim, x, z) + rng.nextInt(9) - 3));
            case 1 -> y = 40 + rng.nextInt(40);
            default -> y = 80 + rng.nextInt(90);
        }
        sim.writeBlock(x, y, z, palette[rng.nextInt(palette.length)]);
    }

    @ParameterizedTest
    @ValueSource(longs = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 })
    void incrementalLightMatchesFloodFill(final long seed) {
        runEdits(seed, false);
    }

    @ParameterizedTest
    @ValueSource(longs = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 })
    void scalarIncrementalLightMatchesFloodFill(final long seed) {
        runEdits(seed, true);
    }

    private static void runEdits(final long seed, final boolean scalar) {
        final Random rng = new Random(seed);
        final LightSim sim = buildWorld(rng, scalar);
        assertNull(sim.diff(0, 255), "seed " + seed + ": initial light");

        for (int step = 0; step < 240; ++step) {
            // A tick's worth of edits lands in one task per chunk.
            for (int n = 1 + rng.nextInt(4); n > 0; --n) randomEdit(sim, rng);
            sim.flush();
            if (step % 8 == 7) {
                assertNull(sim.diff(0, 255), "seed " + seed + ": after step " + step);
            }
        }
    }
}
