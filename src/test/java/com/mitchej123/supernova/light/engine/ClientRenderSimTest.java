package com.mitchej123.supernova.light.engine;

import com.mitchej123.supernova.light.engine.LightSim.SimChunk;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the player sees on new chunks: the server generates and lights them in whatever order the player's movement asks for, sends each once it is lit,
 * then population drops trees across chunk borders into chunks the client already has. Afterwards every mesh on the client must have been rebuilt from
 * the light it now holds; one that was not is exactly the "light on blocks only looks right after clicking nearby" the player sees.
 */
class ClientRenderSimTest {

    private static final int RADIUS = 1;
    /** Forge's clumpingThreshold: past this many changes in one chunk in one tick the server resends its sections. */
    private static final int CLUMPING_THRESHOLD = 64;

    private static Block lamp, orange, glass, water, slab, leaves;

    @BeforeAll
    static void registerBlocks() {
        LightSimRandomTest.registerBlocks();
        final Block[] p = LightSimRandomTest.palette;
        lamp = p[8];
        orange = p[10];
        glass = p[12];
        water = p[13];
        slab = p[15];
        leaves = p[21];
    }

    private static void terrain(final SimChunk chunk, final Random rng) {
        for (int x = 0; x < 16; ++x) {
            for (int z = 0; z < 16; ++z) {
                final int wx = (chunk.xPosition << 4) + x, wz = (chunk.zPosition << 4) + z;
                // Rolling ground across the y=64 section boundary.
                final int h = 62 + (int) Math.round(3 * Math.sin(wx * 0.31) + 2 * Math.cos(wz * 0.27));
                for (int y = 0; y <= h; ++y) {
                    if (y > 40 && y < h - 3 && rng.nextInt(12) == 0) continue;
                    LightSim.rawSet(chunk, x, y, z, Blocks.stone);
                }
                if (h < 61) LightSim.rawSet(chunk, x, h + 1, z, water);
                if (rng.nextInt(90) == 0) LightSim.rawSet(chunk, x, h + 1, z, lamp);
                if (rng.nextInt(70) == 0) LightSim.rawSet(chunk, x, h + 1, z, slab);
            }
        }
    }

    /** One populate() worth of trees around a chunk corner, landing in up to four chunks. */
    private static List<int[]> trees(final LightSim server, final int cx, final int cz, final Random rng) {
        final List<int[]> edits = new ArrayList<>();
        for (int t = 0; t < 3 + rng.nextInt(3); ++t) {
            final int x = (cx << 4) + 8 + rng.nextInt(16), z = (cz << 4) + 8 + rng.nextInt(16);
            if (server.chunk(x >> 4, z >> 4) == null) continue;
            int ground = 90;
            while (ground > 0 && server.getBlock(x, ground, z) == Blocks.air) --ground;
            final int top = ground + 4 + rng.nextInt(3);
            for (int y = ground + 1; y <= top; ++y) edits.add(new int[] { x, y, z, 0 });
            for (int dx = -2; dx <= 2; ++dx) {
                for (int dz = -2; dz <= 2; ++dz) {
                    for (int dy = -1; dy <= 1; ++dy) {
                        if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy) > 3 || (dx == 0 && dz == 0 && dy <= 0)) continue;
                        edits.add(new int[] { x + dx, top + dy, z + dz, 1 });
                    }
                }
            }
            if (rng.nextInt(3) == 0) edits.add(new int[] { x + 1, ground + 2, z, 2 });
            if (rng.nextInt(2) == 0) edits.add(new int[] { x - 1, top + 2, z, 3 });
        }
        return edits;
    }

    @ParameterizedTest
    @ValueSource(longs = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 })
    void newChunksRenderWithTheLightTheyHold(final long seed) {
        final Random rng = new Random(seed);
        final LightSim server = new LightSim(false, -RADIUS, RADIUS, -RADIUS, RADIUS);
        final LightSim client = new LightSim(true, -RADIUS, RADIUS, -RADIUS, RADIUS);

        final List<int[]> order = new ArrayList<>();
        for (int cx = -RADIUS; cx <= RADIUS; ++cx) {
            for (int cz = -RADIUS; cz <= RADIUS; ++cz) order.add(new int[] { cx, cz });
        }
        Collections.shuffle(order, rng);

        for (final int[] c : order) {
            final SimChunk chunk = server.newChunk(c[0], c[1]);
            terrain(chunk, rng);
            server.register(chunk);
            server.lightChunk(chunk);
            server.sendTo(client, c[0], c[1]);
        }
        assertNull(client.diff(0, 255), "seed " + seed + ": client light after receiving every chunk");
        assertNull(server.vanillaMismatches(), "seed " + seed + ": server vanilla arrays after lighting every chunk");
        assertNull(client.vanillaMismatches(), "seed " + seed + ": client vanilla arrays after receiving every chunk");
        assertNull(client.staleMeshes(), "seed " + seed + ": meshes after receiving every chunk");

        // Population, one chunk corner per tick.
        for (final int[] c : order) {
            final List<int[]> edits = trees(server, c[0], c[1], rng);
            final Long2IntOpenHashMap perChunk = new Long2IntOpenHashMap();
            final Long2IntOpenHashMap masks = new Long2IntOpenHashMap();
            edits.removeIf(e -> server.chunk(e[0] >> 4, e[2] >> 4) == null);
            for (final int[] e : edits) {
                final Block block = e[3] == 0 ? Blocks.stone : e[3] == 1 ? leaves : e[3] == 3 ? glass : orange;
                if (server.getBlock(e[0], e[1], e[2]) == block) continue;
                server.writeBlock(e[0], e[1], e[2], block);
                final long key = ((long) (e[0] >> 4) << 32) | ((e[2] >> 4) & 0xFFFFFFFFL);
                perChunk.addTo(key, 1);
                masks.put(key, masks.get(key) | (1 << (e[1] >> 4)));
            }
            // The tick ends: PlayerInstance sends what changed, whether or not the server's workers have got to the light yet.
            if (rng.nextBoolean()) server.flush();
            for (final long key : perChunk.keySet().toLongArray()) {
                final int kx = (int) (key >> 32), kz = (int) key;
                if (perChunk.get(key) >= CLUMPING_THRESHOLD) {
                    server.sendPartialTo(client, kx, kz, masks.get(key));
                }
            }
            for (final int[] e : edits) {
                final long key = ((long) (e[0] >> 4) << 32) | ((e[2] >> 4) & 0xFFFFFFFFL);
                if (perChunk.get(key) >= CLUMPING_THRESHOLD) continue;
                client.setBlockOnClient(e[0], e[1], e[2], e[3] == 0 ? Blocks.stone : e[3] == 1 ? leaves : e[3] == 3 ? glass : orange);
            }
            server.flush();
        }

        client.frame();
        assertNull(server.diff(0, 255), "seed " + seed + ": server light after population");
        assertNull(client.diff(0, 255), "seed " + seed + ": client light after population");
        assertNull(server.vanillaMismatches(), "seed " + seed + ": server vanilla arrays after population");
        assertNull(client.vanillaMismatches(), "seed " + seed + ": client vanilla arrays after population");
        assertNull(client.staleMeshes(), "seed " + seed + ": client meshes after population");
    }
}
