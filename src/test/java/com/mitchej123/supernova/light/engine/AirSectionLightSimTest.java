package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.api.ColoredLightSource;
import com.mitchej123.supernova.light.engine.LightSim.SimChunk;
import com.mitchej123.supernova.light.engine.MCBootstrap.TestIds;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static com.mitchej123.supernova.api.PackedColorLight.pack;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Light in sections that hold no blocks: above the terrain, under overhangs and around builds in the air. */
class AirSectionLightSimTest {

    public static class RedLamp extends Block implements ColoredLightSource {

        public RedLamp() {super(Material.rock);}

        @Override
        public int getColoredLightEmission(final int meta) {return pack(15, 3, 0);}
    }

    private static Block lamp;
    private static Block redLamp;
    private static Block glass;

    @BeforeAll
    static void registerBlocks() {
        lamp = MCBootstrap.registerTestBlock(TestIds.SIM_LAMP, "sim_lamp", Block.class, 0, false, 15);
        redLamp = MCBootstrap.registerTestBlock(TestIds.SIM_RED_LAMP, "sim_red_lamp", RedLamp.class, 0, false, 0);
        glass = MCBootstrap.registerTestBlock(TestIds.SIM_GLASS, "sim_glass", Block.class, 0, false, 0);
    }

    /** Stone up to {@code groundY} in every chunk of a (2r+1)^2 square, lit in row order. */
    private static LightSim flatWorld(final int radius, final int groundY) {
        final LightSim sim = new LightSim(false, -radius, radius, -radius, radius);
        for (int cx = -radius; cx <= radius; ++cx) {
            for (int cz = -radius; cz <= radius; ++cz) {
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
    void flatWorldLightsCorrectly() {
        assertMatches(flatWorld(1, 59), "initial light");
    }

    @Test
    void blockPlacedHighInOpenAir() {
        final LightSim sim = flatWorld(1, 59);
        sim.setBlock(8, 150, 8, Blocks.stone);
        assertMatches(sim, "floating block");
        sim.setBlock(8, 150, 8, Blocks.air);
        assertMatches(sim, "floating block removed");
    }

    @Test
    void lampPlacedHighInOpenAir() {
        final LightSim sim = flatWorld(1, 59);
        sim.setBlock(8, 150, 8, lamp);
        assertMatches(sim, "floating lamp");
        sim.setBlock(8, 150, 8, Blocks.air);
        assertMatches(sim, "floating lamp removed");
    }

    @Test
    void lampNearChunkCornerHighInAir() {
        final LightSim sim = flatWorld(1, 59);
        sim.setBlock(15, 130, 15, redLamp);
        assertMatches(sim, "lamp at chunk corner");
        sim.setBlock(15, 143, 15, lamp);
        assertMatches(sim, "second lamp near a section boundary");
        sim.setBlock(15, 130, 15, Blocks.air);
        assertMatches(sim, "first lamp removed");
    }

    @Test
    void platformInAirCastsShadowDownToGround() {
        final LightSim sim = flatWorld(1, 59);
        for (int x = 2; x <= 13; ++x) {
            for (int z = 2; z <= 13; ++z) {
                sim.setBlock(x, 140, z, Blocks.stone);
            }
        }
        assertMatches(sim, "platform built");
        sim.setBlock(8, 139, 8, lamp);
        assertMatches(sim, "lamp under platform");
        sim.setBlock(8, 100, 8, Blocks.stone);
        assertMatches(sim, "block in the shadow, in a section that held nothing");
        for (int x = 2; x <= 13; ++x) {
            for (int z = 2; z <= 13; ++z) {
                sim.setBlock(x, 140, z, Blocks.air);
            }
        }
        assertMatches(sim, "platform removed");
    }

    @Test
    void platformSpanningChunksThenPillarDown() {
        final LightSim sim = flatWorld(1, 59);
        for (int x = -6; x <= 21; ++x) {
            for (int z = -6; z <= 21; ++z) {
                sim.setBlock(x, 200, z, Blocks.stone);
            }
        }
        assertMatches(sim, "wide platform built");
        for (int y = 199; y >= 150; --y) {
            sim.setBlock(8, y, 8, Blocks.stone);
        }
        assertMatches(sim, "pillar hanging from platform");
        sim.setBlock(9, 160, 8, redLamp);
        assertMatches(sim, "lamp on the pillar");
    }

    /** A section whose storage outlived its blocks: nothing is allocated when it is built in again, so nothing announces it. */
    @Test
    void buildingInASectionWhoseStorageAlreadyExists() {
        final LightSim sim = new LightSim(false, -1, 1, -1, 1);
        for (int cx = -1; cx <= 1; ++cx) {
            for (int cz = -1; cz <= 1; ++cz) {
                final SimChunk chunk = sim.newChunk(cx, cz);
                for (int x = 0; x < 16; ++x) {
                    for (int z = 0; z < 16; ++z) {
                        for (int y = 0; y <= 59; ++y) LightSim.rawSet(chunk, x, y, z, Blocks.stone);
                    }
                }
                // Empty storage high above the terrain, as a world converted from vanilla or a torn-down build leaves behind.
                chunk.getBlockStorageArray()[9] = new TestSection(9 << 4, true);
                sim.register(chunk);
                sim.lightChunk(chunk);
            }
        }
        assertMatches(sim, "initial light");

        for (int x = 3; x <= 12; ++x) {
            for (int z = 3; z <= 12; ++z) {
                sim.setBlock(x, 150, z, Blocks.stone);
            }
        }
        assertMatches(sim, "roof built in the pre-existing section");
        sim.setBlock(8, 147, 8, lamp);
        assertMatches(sim, "lamp under the roof");
        for (int x = 3; x <= 12; ++x) {
            for (int z = 3; z <= 12; ++z) {
                sim.setBlock(x, 150, z, Blocks.air);
            }
        }
        sim.setBlock(8, 147, 8, Blocks.air);
        assertMatches(sim, "everything removed again");
        sim.setBlock(8, 150, 8, Blocks.stone);
        assertMatches(sim, "built in once more");
    }

    /** Tearing a build down leaves its sections empty; whatever goes up there next must be lit like the first time. */
    @Test
    void rebuildingWhereABuildWasRemoved() {
        final LightSim sim = flatWorld(1, 59);
        for (int round = 0; round < 3; ++round) {
            for (int x = 4; x <= 11; ++x) {
                for (int z = 4; z <= 11; ++z) {
                    sim.setBlock(x, 130 + round, z, Blocks.stone);
                }
            }
            assertMatches(sim, "round " + round + ": platform up");
            sim.setBlock(7, 120, 7, redLamp);
            assertMatches(sim, "round " + round + ": lamp below");
            for (int x = 4; x <= 11; ++x) {
                for (int z = 4; z <= 11; ++z) {
                    sim.setBlock(x, 130 + round, z, Blocks.air);
                }
            }
            sim.setBlock(7, 120, 7, Blocks.air);
            assertMatches(sim, "round " + round + ": all removed");
        }
    }

    /**
     * The block lane lights a fresh chunk long before the sky lane gets to it. Population keeps placing blocks in between; the block lane has already read
     * the chunk and never will again, so it has to act on those changes instead of waiting for the sky lane.
     */
    @Test
    void blockChangeBetweenTheTwoLanesOfInitialLightIsNotLost() {
        final LightSim sim = flatWorld(1, 59);
        sim.unregister(0, 0);
        final SimChunk chunk = sim.newChunk(0, 0);
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                for (int y = 0; y <= 59; ++y) LightSim.rawSet(chunk, x, y, z, Blocks.stone);
            }
        }
        sim.register(chunk);
        sim.beginLight(chunk);

        sim.propagateLane(chunk, false);
        // Population reaches the chunk now: a lava-lake stand-in, then a tree-trunk stand-in.
        sim.writeBlock(8, 60, 8, lamp);
        sim.writeBlock(3, 60, 3, Blocks.stone);
        sim.writeBlock(3, 61, 3, Blocks.stone);
        sim.flushLane(false);

        // The sky lane's copy of the same task rides along with its own initial pass; the block lane's was consumed above and never comes back.
        sim.propagateLane(chunk, true);
        sim.flushLane(true);
        sim.discardPending();
        sim.finishLight(chunk);

        assertMatches(sim, "chunk populated while only the block lane had lit it");
    }

    /** Light cannot enter a chunk that is not loaded; when it comes back with its saved light, the seam has to be reconciled. */
    @Test
    void lightPlacedAtTheBorderOfAnUnloadedChunkReachesItAfterLoad() {
        final LightSim sim = flatWorld(1, 59);
        final LightSim.Saved saved = sim.unload(1, 0);

        sim.setBlock(15, 62, 8, lamp);
        sim.setBlock(15, 130, 8, redLamp);
        for (int z = 2; z <= 13; ++z) {
            for (int x = 10; x <= 15; ++x) sim.setBlock(x, 100, z, Blocks.stone);
        }
        assertMatches(sim, "edits next to the unloaded chunk");

        sim.load(saved);
        assertMatches(sim, "chunk loaded back with its saved light");
    }

    @Test
    void savedLightSurvivesAReloadUnchanged() {
        final LightSim sim = flatWorld(1, 59);
        for (int x = 2; x <= 13; ++x) {
            for (int z = 2; z <= 13; ++z) sim.setBlock(x, 140, z, Blocks.stone);
        }
        sim.setBlock(8, 139, 8, redLamp);
        sim.setBlock(20, 61, 8, lamp);
        assertMatches(sim, "before reload");

        for (int cx = -1; cx <= 1; ++cx) {
            for (int cz = -1; cz <= 1; ++cz) {
                sim.load(sim.unload(cx, cz));
            }
        }
        assertMatches(sim, "every chunk saved and loaded again");

        sim.setBlock(8, 120, 8, Blocks.stone);
        sim.setBlock(8, 139, 8, Blocks.air);
        sim.setBlock(9, 100, 9, lamp);
        assertMatches(sim, "edits after the reload");
    }

    /** What the player sees: the server's light after it has been through the chunk packet. */
    @Test
    void clientReceivesLightOfSectionsThatHoldNoBlocks() {
        final LightSim server = flatWorld(1, 63);
        // Lamps set flush into the top block layer of a section: their light lives in the storage-less section above.
        server.setBlock(8, 63, 8, lamp);
        server.setBlock(24, 63, 8, redLamp);
        // A slab of stone whose shadow falls through sections that hold nothing.
        for (int x = 0; x <= 15; ++x) {
            for (int z = 0; z <= 15; ++z) server.setBlock(x, 160, z, Blocks.stone);
        }
        server.setBlock(8, 144, 8, lamp);
        assertMatches(server, "server");

        final LightSim client = new LightSim(true, -1, 1, -1, 1);
        for (int cx = 1; cx >= -1; --cx) {
            for (int cz = -1; cz <= 1; ++cz) server.sendTo(client, cx, cz);
        }
        assertMatches(client, "client after receiving every chunk");

        // Block changes reach the client one by one and it lights them itself, from the light it was sent.
        for (final LightSim side : new LightSim[] { server, client }) {
            side.setBlock(8, 100, 8, Blocks.stone);
            side.setBlock(8, 63, 8, Blocks.stone);
            side.setBlock(9, 143, 8, redLamp);
        }
        assertMatches(client, "client after mirrored edits");

        // A relight ends in a full resend; light the server no longer has must not linger on the client, in this chunk or spilled into the next.
        server.setBlock(8, 144, 8, Blocks.air);
        server.setBlock(9, 143, 8, Blocks.air);
        server.sendTo(client, 0, 0);
        assertMatches(client, "client after a resend of the chunk");
    }

    /** Several edits to one column in one tick arrive as one task, in no particular order. */
    @Test
    void blocksStackedInOneColumnWithinOneTask() {
        for (final int[] column : new int[][] { { 8, 8 }, { 3, 12 }, { 15, 0 }, { 20, 5 }, { -3, -9 } }) {
            final LightSim sim = flatWorld(1, 59);
            final int x = column[0], z = column[1];
            sim.writeBlock(x, 90, z, Blocks.stone);
            sim.writeBlock(x, 123, z, Blocks.stone);
            sim.writeBlock(x, 130, z, Blocks.stone);
            sim.writeBlock(x, 150, z, glass);
            sim.writeBlock(x, 170, z, Blocks.stone);
            sim.flush();
            assertMatches(sim, "stacked at " + x + "," + z);

            sim.writeBlock(x, 170, z, Blocks.air);
            sim.writeBlock(x, 123, z, Blocks.air);
            sim.writeBlock(x, 100, z, Blocks.stone);
            sim.flush();
            assertMatches(sim, "partly removed at " + x + "," + z);
        }
    }

    @Test
    void randomEditsInTheAir() {
        final Random rng = new Random(12345);
        final LightSim sim = flatWorld(1, 59);
        final Block[] palette = { Blocks.stone, Blocks.stone, Blocks.stone, lamp, redLamp, glass, Blocks.air, Blocks.air };
        for (int step = 0; step < 300; ++step) {
            final int x = rng.nextInt(28) - 6;
            final int z = rng.nextInt(28) - 6;
            final int y = 90 + rng.nextInt(60);
            final Block b = palette[rng.nextInt(palette.length)];
            sim.setBlock(x, y, z, b);
            if (step % 10 == 9) {
                assertMatches(sim, "after step " + step + " (" + x + "," + y + "," + z + ")");
            }
        }
    }
}
