package com.mitchej123.supernova.light;

import com.mitchej123.supernova.light.engine.LightSim;
import com.mitchej123.supernova.light.engine.MCBootstrap;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLightManagerTest {

    @Test
    void neighboringEdgeWorkBlocksSaveButDistantWorkDoesNot() {
        final WorldLightManager manager = new WorldLightManager(MCBootstrap.createStubWorld(true), true, true);
        try {
            manager.queueEdgeChecks(1, 1);
            assertTrue(manager.hasUnsettledLightValues(0, 0));
            assertFalse(manager.hasUnsettledLightValues(-1, -1));
            assertFalse(manager.awaitPendingWork(0, 0));
        } finally {
            manager.shutdown();
        }
    }

    /** A block changed before the chunk is sent never reaches the client as a block change, so its light has to be in the packet. */
    @Test
    void sendWaitsForPendingLightWorkButNotForever() {
        final World world = MCBootstrap.createStubWorld(true);
        // No workers on a client-side manager, so queued work stays pending for as long as the test needs.
        final WorldLightManager manager = new WorldLightManager(world, true, true);
        try {
            manager.registerChunk(LightSim.readyChunk(world, 0, 0));
            final long start = 5_000L;
            assertTrue(manager.isReadyToSend(0, 0, start), "nothing pending");

            manager.queueBlockChange(5, 70, 5);
            assertFalse(manager.isReadyToSend(0, 0, start), "the change's light is not computed yet");
            assertFalse(manager.isReadyToSend(0, 0, start + 900_000_000L));
            assertTrue(manager.isReadyToSend(0, 0, start + 1_000_000_001L), "an area that never settles must still be sent");
            assertFalse(manager.isReadyToSend(0, 0, start + 1_000_000_002L), "and the next send gets its own wait");

            manager.queueBlockChange(40, 70, 5);
            assertTrue(manager.hasChunkPendingLight(2, 0));
            manager.unregisterChunk(0, 0);
            assertFalse(manager.isReadyToSend(0, 0, start), "an unloaded chunk is never ready");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void clientBudgetStopsBetweenTasksAndResumesNextTick() {
        final WorldLightManager manager = new WorldLightManager(MCBootstrap.createStubWorld(true), true, true);
        try {
            manager.queueEdgeChecks(0, 0);
            final AtomicLong clock = new AtomicLong();
            manager.drainClientLight(() -> clock.getAndAdd(3_000_000L));
            assertTrue(manager.hasChunkPendingLight(0, 0), "one of the two lanes must remain after the shared deadline");
            manager.drainClientLight(() -> clock.getAndAdd(3_000_000L));
            assertFalse(manager.hasChunkPendingLight(0, 0));
        } finally {
            manager.shutdown();
        }
    }
}
