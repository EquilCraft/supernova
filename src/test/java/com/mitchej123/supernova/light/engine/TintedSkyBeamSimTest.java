package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.PositionalColoredTranslucency;
import com.mitchej123.supernova.light.engine.LightSim.SimChunk;
import com.mitchej123.supernova.light.engine.MCBootstrap.TestIds;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.IBlockAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.mitchej123.supernova.api.PackedColorLight.pack;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Daylight through coloured glass: the beam keeps its tint all the way down, and has to come and go with the glass. */
class TintedSkyBeamSimTest {

    /** Positional so the engine asks the block itself: the metadata-only interface is served from a registry cache these tests leave alone. */
    public static class RedGlass extends Block implements PositionalColoredTranslucency {

        public RedGlass() {super(Material.glass);}

        @Override
        public int getColoredTransmittance(final int meta) {return pack(15, 6, 4);}

        @Override
        public int getColoredTransmittance(final IBlockAccess world, final int meta, final int x, final int y, final int z) {return pack(15, 6, 4);}
    }

    private static Block redGlass;

    @BeforeAll
    static void registerBlocks() {
        redGlass = MCBootstrap.registerTestBlock(TestIds.TINTED_GLASS, "tinted_glass", RedGlass.class, 0, false, 0);
    }

    private static LightSim flatWorld(final int groundY) {
        final LightSim sim = new LightSim(false, -1, 1, -1, 1);
        for (int cx = -1; cx <= 1; ++cx) {
            for (int cz = -1; cz <= 1; ++cz) {
                final SimChunk chunk = sim.newChunk(cx, cz);
                for (int x = 0; x < 16; ++x) {
                    for (int z = 0; z < 16; ++z) {
                        for (int y = 0; y <= groundY; ++y) LightSim.rawSet(chunk, x, y, z, Blocks.stone);
                    }
                }
                sim.register(chunk);
                sim.lightChunk(chunk);
            }
        }
        return sim;
    }

    private static void assertMatches(final LightSim sim, final String phase) {
        assertNull(sim.diff(0, 255), phase);
    }

    @Test
    void glassRoofBuiltAndRemoved() {
        final LightSim sim = flatWorld(59);
        for (int x = 4; x <= 11; ++x) {
            for (int z = 4; z <= 11; ++z) sim.setBlock(x, 70, z, redGlass);
        }
        assertMatches(sim, "glass roof");
        sim.setBlock(7, 65, 7, Blocks.stone);
        assertMatches(sim, "block placed in the tinted light");
        sim.setBlock(7, 65, 7, Blocks.air);
        assertMatches(sim, "and removed");
        sim.setBlock(8, 80, 8, Blocks.stone);
        assertMatches(sim, "block placed above the glass");
        sim.setBlock(8, 80, 8, Blocks.air);
        assertMatches(sim, "and removed");
        for (int x = 4; x <= 11; ++x) {
            for (int z = 4; z <= 11; ++z) sim.setBlock(x, 70, z, Blocks.air);
        }
        assertMatches(sim, "roof taken down");
    }

    /** One pane high up: the column under it is tinted only as far as the daylight next to it does not already fill the other channels in. */
    @Test
    void singlePaneInTheAir() {
        final LightSim sim = flatWorld(59);
        sim.setBlock(8, 150, 8, redGlass);
        assertMatches(sim, "pane placed");
        sim.setBlock(8, 100, 8, Blocks.stone);
        assertMatches(sim, "block placed in the column under it");
        sim.setBlock(8, 160, 8, Blocks.stone);
        assertMatches(sim, "block placed over the pane");
        sim.setBlock(8, 160, 8, Blocks.air);
        sim.setBlock(8, 100, 8, Blocks.air);
        assertMatches(sim, "both removed");
        sim.setBlock(8, 150, 8, Blocks.air);
        assertMatches(sim, "pane removed");
    }

    @Test
    void generatedUnderGlass() {
        final LightSim sim = new LightSim(false, -1, 1, -1, 1);
        for (int cx = -1; cx <= 1; ++cx) {
            for (int cz = -1; cz <= 1; ++cz) {
                final SimChunk chunk = sim.newChunk(cx, cz);
                for (int x = 0; x < 16; ++x) {
                    for (int z = 0; z < 16; ++z) {
                        for (int y = 0; y <= 59; ++y) LightSim.rawSet(chunk, x, y, z, Blocks.stone);
                        if (((x + z) & 3) != 0) LightSim.rawSet(chunk, x, 90, z, redGlass);
                    }
                }
                sim.register(chunk);
                sim.lightChunk(chunk);
            }
        }
        assertMatches(sim, "initial light under a glass canopy");
        sim.setBlock(8, 75, 8, Blocks.stone);
        sim.setBlock(1, 90, 0, Blocks.air);
        sim.setBlock(0, 90, 0, redGlass);
        assertMatches(sim, "edits under the canopy");
    }
}
