package com.mitchej123.supernova.storage;

import com.mitchej123.supernova.config.SupernovaConfig;
import com.mitchej123.supernova.light.ChunkLightHelper;
import com.mitchej123.supernova.light.NibbleStates;
import com.mitchej123.supernova.light.SWMRNibbleArray;
import com.mitchej123.supernova.light.SupernovaChunk;
import com.mitchej123.supernova.util.WorldUtil;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataManagerTest {

    private static final class TestManager extends AbstractSupernovaDataManager {

        TestManager() {
            super("test", "msg", false);
        }

        @Override
        protected SWMRNibbleArray[] getNibblesR(SupernovaChunk chunk) {return null;}

        @Override
        protected SWMRNibbleArray[] getNibblesG(SupernovaChunk chunk) {return null;}

        @Override
        protected SWMRNibbleArray[] getNibblesB(SupernovaChunk chunk) {return null;}
    }

    private static final int IDX = 5;

    private final TestManager manager = new TestManager();

    @AfterEach
    void resetMode() {
        SupernovaConfig.lightingMode = null;
    }

    private static SWMRNibbleArray[] emptyArrays() {
        return new SWMRNibbleArray[WorldUtil.getTotalLightSections()];
    }

    private static final class Channels {

        final SWMRNibbleArray[] r = emptyArrays();
        final SWMRNibbleArray[] g = emptyArrays();
        final SWMRNibbleArray[] b = emptyArrays();
    }

    private NBTTagCompound save(SWMRNibbleArray[] r, SWMRNibbleArray[] g, SWMRNibbleArray[] b) {
        final NBTTagCompound nbt = new NBTTagCompound();
        manager.writeChunkLight(nbt, r, g, b, true);
        return nbt;
    }

    private static ByteBuffer packet(SWMRNibbleArray[] r, SWMRNibbleArray[] g, SWMRNibbleArray[] b, int sectionY) {
        final ByteBuffer buf = ByteBuffer.allocate(1 + 3 * SWMRNibbleArray.ARRAY_SIZE);
        AbstractSupernovaDataManager.writeSectionToBuffer(buf, r, g, b, sectionY);
        buf.flip();
        return buf;
    }

    private static Channels receive(ByteBuffer buf, int sectionY) {
        final Channels out = new Channels();
        AbstractSupernovaDataManager.readSectionFromBuffer(buf, out.r, out.g, out.b, sectionY);
        return out;
    }

    @Test
    void greenBlueOnlyLightSurvivesNbtAndPacket() {
        final int sectionY = 4;
        final int idx = AbstractSupernovaDataManager.nibbleIndex(sectionY);
        final Channels in = new Channels();
        in.r[idx] = NibbleStates.uninit();
        in.g[idx] = NibbleStates.lit(0, 12);
        in.b[idx] = NibbleStates.lit(0, 7);

        final Channels saved = new Channels();
        assertTrue(manager.readChunkLight(save(in.r, in.g, in.b), saved.r, saved.g, saved.b));
        assertTrue(saved.r[idx].isUninitialisedUpdating());
        assertEquals(12, saved.g[idx].getUpdating(0));
        assertEquals(7, saved.b[idx].getUpdating(0));

        final Channels received = receive(packet(in.r, in.g, in.b, sectionY), sectionY);
        assertTrue(received.r[idx].isUninitialisedUpdating());
        assertEquals(12, received.g[idx].getUpdating(0));
        assertEquals(7, received.b[idx].getUpdating(0));
    }

    @Nested
    class Nbt {

        @Test
        void notReadyChunkReadsBackAsNoData() {
            SWMRNibbleArray[] in = emptyArrays();
            in[IDX] = NibbleStates.lit(0, 10);
            NBTTagCompound nbt = new NBTTagCompound();
            manager.writeChunkLight(nbt, in, in, in, false);

            SWMRNibbleArray[] rOut = emptyArrays();
            assertFalse(manager.readChunkLight(nbt, rOut, emptyArrays(), emptyArrays()));
            assertNull(rOut[IDX], "gated save must read back as absent so the chunk relights");
        }

        @Test
        void markerIsWrittenWhenReady() {
            SWMRNibbleArray[] r = emptyArrays();
            r[IDX] = NibbleStates.lit(0, 10);

            assertEquals(AbstractSupernovaDataManager.LIGHT_VERSION, save(r, r, r).getInteger(AbstractSupernovaDataManager.NBT_VERSION));
        }

        @Test
        void staleMarkerIsIgnored() {
            SWMRNibbleArray[] in = emptyArrays();
            in[IDX] = NibbleStates.lit(0, 10);
            NBTTagCompound nbt = save(in, in, in);
            nbt.setInteger(AbstractSupernovaDataManager.NBT_VERSION, AbstractSupernovaDataManager.LIGHT_VERSION - 1);

            SWMRNibbleArray[] rOut = emptyArrays();
            assertFalse(manager.readChunkLight(nbt, rOut, emptyArrays(), emptyArrays()), "a mismatched marker must not be trusted");
            assertNull(rOut[IDX]);
        }

        @Test
        void readerLeavesNibblesAloneWhenMarkerAbsent() {
            SWMRNibbleArray[] rOut = emptyArrays();
            rOut[IDX] = NibbleStates.lit(0, 7);

            assertFalse(manager.readChunkLight(new NBTTagCompound(), rOut, emptyArrays(), emptyArrays()));
            assertEquals(7, rOut[IDX].getUpdating(0), "values already installed must survive a missing marker");
        }

        @Test
        void boundsChangeDiscardsRatherThanShifts() {
            SWMRNibbleArray[] in = emptyArrays();
            in[IDX] = NibbleStates.lit(0, 10);
            NBTTagCompound nbt = save(in, in, in);
            assertEquals(WorldUtil.getMinLightSection(), nbt.getInteger(AbstractSupernovaDataManager.NBT_MIN_SECTION));

            try {
                WorldUtil.setBounds(-32, 15);
                SWMRNibbleArray[] rOut = emptyArrays();
                assertFalse(manager.readChunkLight(nbt, rOut, null, null), "light saved under different bounds must not be trusted");
                assertNull(rOut[IDX], "and must not be installed at the wrong height");
            } finally {
                WorldUtil.setBounds(0, 15);
            }
        }

        @Test
        void litSectionsRoundTrip() {
            Channels in = new Channels();
            in.r[IDX] = NibbleStates.lit(0, 10);
            in.g[IDX] = NibbleStates.lit(0, 7);
            in.b[IDX] = NibbleStates.lit(0, 3);

            Channels out = new Channels();
            assertTrue(manager.readChunkLight(save(in.r, in.g, in.b), out.r, out.g, out.b));
            assertEquals(10, out.r[IDX].getUpdating(0));
            assertEquals(7, out.g[IDX].getUpdating(0));
            assertEquals(3, out.b[IDX].getUpdating(0));
        }

        @ParameterizedTest(name = "zeroInit={0}")
        @ValueSource(booleans = { false, true })
        void darkSectionRoundTripsAsUninitWithNoArray(boolean zeroInit) {
            SWMRNibbleArray[] rIn = emptyArrays();
            rIn[IDX] = zeroInit ? NibbleStates.zeroInit() : NibbleStates.uninit();

            NBTTagCompound nbt = save(rIn, emptyArrays(), emptyArrays());
            assertFalse(nbt.hasKey("R" + IDX), "a dark section stores its state only, never 2048 zero bytes");

            SWMRNibbleArray[] rOut = emptyArrays();
            assertTrue(manager.readChunkLight(nbt, rOut, emptyArrays(), emptyArrays()));
            assertNotNull(rOut[IDX]);
            assertTrue(rOut[IDX].isUninitialisedUpdating(), "dark must come back dark, not absent");
        }

        @Test
        void nullSectionStaysAbsent() {
            SWMRNibbleArray[] rIn = emptyArrays();
            rIn[IDX] = NibbleStates.nullNibble();

            SWMRNibbleArray[] rOut = emptyArrays();
            assertTrue(manager.readChunkLight(save(rIn, emptyArrays(), emptyArrays()), rOut, emptyArrays(), emptyArrays()));
            assertNull(rOut[IDX], "NULL means above-world, which must not be resurrected as dark");
        }

        @Test
        void sectionsOutsideTheBlockRangePersist() {
            SWMRNibbleArray[] rIn = emptyArrays();
            final int belowWorld = 0;
            final int aboveWorld = WorldUtil.getTotalLightSections() - 1;
            rIn[belowWorld] = NibbleStates.lit(0, 4);
            rIn[aboveWorld] = NibbleStates.lit(0, 15);

            SWMRNibbleArray[] rOut = emptyArrays();
            assertTrue(manager.readChunkLight(save(rIn, emptyArrays(), emptyArrays()), rOut, emptyArrays(), emptyArrays()));
            assertEquals(4, rOut[belowWorld].getUpdating(0), "section -1 has no ExtendedBlockStorage but must still persist");
            assertEquals(15, rOut[aboveWorld].getUpdating(0), "section 16 has no ExtendedBlockStorage but must still persist");
        }

        @Test
        void corruptStateWithoutDataDiscardsEverything() {
            SWMRNibbleArray[] rIn = emptyArrays();
            rIn[IDX] = NibbleStates.lit(0, 10);
            NBTTagCompound nbt = save(rIn, emptyArrays(), emptyArrays());
            nbt.setByteArray("R" + IDX, new byte[7]); // wrong length -> ctor throws

            SWMRNibbleArray[] rOut = emptyArrays();
            rOut[IDX] = NibbleStates.lit(0, 9);
            assertFalse(manager.readChunkLight(nbt, rOut, emptyArrays(), emptyArrays()), "corrupt data must not be reported as committed");
            assertTrue(rOut[IDX].isNullNibbleUpdating(), "corrupt data is discarded so the chunk relights from scratch");
        }

        @Test
        void rgbSaveCollapsesToMaxInScalarMode() {
            Channels in = new Channels();
            in.r[IDX] = NibbleStates.lit(0, 3);
            in.g[IDX] = NibbleStates.lit(0, 11);
            in.b[IDX] = NibbleStates.lit(0, 6);
            NBTTagCompound nbt = save(in.r, in.g, in.b);

            SupernovaConfig.lightingMode = SupernovaConfig.LightingMode.SCALAR;
            SWMRNibbleArray[] rOut = emptyArrays();
            assertTrue(manager.readChunkLight(nbt, rOut, null, null));
            assertEquals(11, rOut[IDX].getUpdating(0), "scalar mode keeps max(R,G,B)");
        }

        @Test
        void rgbSaveWithDarkRAndLitGCollapsesToG() {
            Channels in = new Channels();
            in.r[IDX] = NibbleStates.uninit();
            in.g[IDX] = NibbleStates.lit(0, 9);
            in.b[IDX] = NibbleStates.uninit();
            NBTTagCompound nbt = save(in.r, in.g, in.b);

            SupernovaConfig.lightingMode = SupernovaConfig.LightingMode.SCALAR;
            SWMRNibbleArray[] rOut = emptyArrays();
            assertTrue(manager.readChunkLight(nbt, rOut, null, null));
            assertEquals(9, rOut[IDX].getUpdating(0));
        }

        @Test
        void scalarSaveMirrorsIntoGAndBInRgbMode() {
            SupernovaConfig.lightingMode = SupernovaConfig.LightingMode.SCALAR;
            SWMRNibbleArray[] rIn = emptyArrays();
            rIn[IDX] = NibbleStates.lit(0, 12);
            NBTTagCompound nbt = save(rIn, null, null);
            assertTrue(nbt.getBoolean(AbstractSupernovaDataManager.NBT_SCALAR));

            SupernovaConfig.lightingMode = null;
            Channels out = new Channels();
            assertTrue(manager.readChunkLight(nbt, out.r, out.g, out.b));
            assertEquals(12, out.r[IDX].getUpdating(0));
            assertEquals(12, out.g[IDX].getUpdating(0), "a scalar save is genuinely white, so mirroring R is correct");
            assertEquals(12, out.b[IDX].getUpdating(0));
        }

        @Test
        void scalarSaveRoundTripsInScalarMode() {
            SupernovaConfig.lightingMode = SupernovaConfig.LightingMode.SCALAR;
            SWMRNibbleArray[] rIn = emptyArrays();
            rIn[IDX] = NibbleStates.lit(0, 8);

            SWMRNibbleArray[] rOut = emptyArrays();
            assertTrue(manager.readChunkLight(save(rIn, null, null), rOut, null, null));
            assertEquals(8, rOut[IDX].getUpdating(0));
        }
    }

    @Nested
    class Buffer {

        @Test
        void roundTripAllChannels() {
            Channels in = new Channels();
            int sectionY = 5;
            int idx = AbstractSupernovaDataManager.nibbleIndex(sectionY);
            in.r[idx] = NibbleStates.lit(0, 10);
            in.g[idx] = NibbleStates.lit(0, 7);
            in.b[idx] = NibbleStates.lit(0, 3);

            Channels out = receive(packet(in.r, in.g, in.b, sectionY), sectionY);

            assertNotNull(out.r[idx]);
            assertNotNull(out.g[idx]);
            assertNotNull(out.b[idx]);
            assertEquals(10, out.r[idx].getUpdating(0));
            assertEquals(7, out.g[idx].getUpdating(0));
            assertEquals(3, out.b[idx].getUpdating(0));
        }

        @Test
        void roundTripPartialChannels() {
            SWMRNibbleArray[] rIn = emptyArrays();
            int sectionY = 3;
            int idx = AbstractSupernovaDataManager.nibbleIndex(sectionY);
            rIn[idx] = NibbleStates.lit(42, 12);

            ByteBuffer buf = packet(rIn, null, null, sectionY);
            assertEquals(0x01, buf.get(0) & 0xFF);

            Channels out = receive(buf, sectionY);
            assertNotNull(out.r[idx]);
            // RGB mode clones a lone R into G/B.
            assertNotNull(out.g[idx]);
            assertNotNull(out.b[idx]);
            assertEquals(12, out.r[idx].getUpdating(42));
            assertEquals(12, out.g[idx].getUpdating(42));
            assertEquals(12, out.b[idx].getUpdating(42));
        }

        @Test
        void roundTripNoData() {
            int sectionY = 0;

            ByteBuffer buf = packet(null, null, null, sectionY);
            assertEquals(0, buf.get(0) & 0xFF);
            assertEquals(1, buf.limit());

            Channels out = receive(buf, sectionY);
            int idx = AbstractSupernovaDataManager.nibbleIndex(sectionY);
            assertNull(out.r[idx]);
            assertNull(out.g[idx]);
            assertNull(out.b[idx]);
        }

        @Test
        void nibbleValuesPreserved() {
            Channels in = new Channels();
            int sectionY = 10;
            int idx = AbstractSupernovaDataManager.nibbleIndex(sectionY);

            in.r[idx] = NibbleStates.uninit();
            in.g[idx] = NibbleStates.uninit();
            in.b[idx] = NibbleStates.uninit();

            for (int i = 0; i < 4096; i++) {
                in.r[idx].set(i, i % 16);
                in.g[idx].set(i, (i + 5) % 16);
                in.b[idx].set(i, (i + 10) % 16);
            }
            in.r[idx].updateVisible();
            in.g[idx].updateVisible();
            in.b[idx].updateVisible();

            Channels out = receive(packet(in.r, in.g, in.b, sectionY), sectionY);

            for (int i = 0; i < 4096; i++) {
                assertEquals(i % 16, out.r[idx].getUpdating(i), "R at " + i);
                assertEquals((i + 5) % 16, out.g[idx].getUpdating(i), "G at " + i);
                assertEquals((i + 10) % 16, out.b[idx].getUpdating(i), "B at " + i);
            }
        }

        @Test
        void multipleSectionsSequential() {
            Channels in = new Channels();

            for (int sy : new int[] { 2, 8 }) {
                int idx = AbstractSupernovaDataManager.nibbleIndex(sy);
                in.r[idx] = NibbleStates.lit(0, sy);
                in.g[idx] = NibbleStates.lit(0, sy + 1);
            }

            ByteBuffer buf = ByteBuffer.allocate(2 * (1 + 3 * SWMRNibbleArray.ARRAY_SIZE));
            AbstractSupernovaDataManager.writeSectionToBuffer(buf, in.r, in.g, in.b, 2);
            AbstractSupernovaDataManager.writeSectionToBuffer(buf, in.r, in.g, in.b, 8);
            buf.flip();

            Channels out = new Channels();
            AbstractSupernovaDataManager.readSectionFromBuffer(buf, out.r, out.g, out.b, 2);
            AbstractSupernovaDataManager.readSectionFromBuffer(buf, out.r, out.g, out.b, 8);

            assertEquals(2, out.r[AbstractSupernovaDataManager.nibbleIndex(2)].getUpdating(0));
            assertEquals(3, out.g[AbstractSupernovaDataManager.nibbleIndex(2)].getUpdating(0));
            assertEquals(8, out.r[AbstractSupernovaDataManager.nibbleIndex(8)].getUpdating(0));
            assertEquals(9, out.g[AbstractSupernovaDataManager.nibbleIndex(8)].getUpdating(0));
        }

        @ParameterizedTest(name = "zeroInit={0}")
        @ValueSource(booleans = { false, true })
        void darkSectionRoundTrip(boolean zeroInit) {
            SWMRNibbleArray[] rIn = emptyArrays();
            int sectionY = 6;
            int idx = AbstractSupernovaDataManager.nibbleIndex(sectionY);
            rIn[idx] = zeroInit ? NibbleStates.zeroInit() : NibbleStates.uninit();

            ByteBuffer buf = packet(rIn, null, null, sectionY);
            assertEquals(0x08, buf.get(0) & 0xFF, "FLAG_R_DARK only: an all-zero INIT section is dark, not data");
            assertEquals(1, buf.limit(), "no payload may be sent for a dark section");

            Channels out = receive(buf, sectionY);
            assertNotNull(out.r[idx]);
            assertTrue(out.r[idx].isUninitialisedUpdating(), "dark section must arrive as UNINIT, not NULL");
            assertNotNull(out.g[idx]);
            assertTrue(out.g[idx].isUninitialisedUpdating());
            assertNotNull(out.b[idx]);
            assertTrue(out.b[idx].isUninitialisedUpdating());
        }

        @Test
        void mixedDataAndDarkChannels() {
            Channels in = new Channels();
            int sectionY = 7;
            int idx = AbstractSupernovaDataManager.nibbleIndex(sectionY);

            in.r[idx] = NibbleStates.lit(0, 11);
            in.g[idx] = NibbleStates.uninit();
            in.b[idx] = NibbleStates.nullNibble();

            ByteBuffer buf = packet(in.r, in.g, in.b, sectionY);
            assertEquals(0x11, buf.get(0) & 0xFF);

            Channels out = receive(buf, sectionY);
            assertEquals(11, out.r[idx].getUpdating(0));
            assertNotNull(out.g[idx]);
            assertTrue(out.g[idx].isUninitialisedUpdating());
            assertNull(out.b[idx], "absent channel with dark sibling present must stay NULL, not cloned");
        }

        @Test
        void nibbleIndex() {
            // minLightSection is -1 under bounds (0,15).
            assertEquals(1, AbstractSupernovaDataManager.nibbleIndex(0));
            assertEquals(0, AbstractSupernovaDataManager.nibbleIndex(-1));
            assertEquals(16, AbstractSupernovaDataManager.nibbleIndex(15));
        }
    }

    /** The chunk packet names only sections that hold blocks; light in the others has to travel all the same. */
    @Nested
    class SectionsWithoutBlocks {

        private static final int MASK = 1 << 3;

        private Channels serverWithLightAboveItsBlocks() {
            final Channels in = new Channels();
            in.r[AbstractSupernovaDataManager.nibbleIndex(3)] = NibbleStates.lit(0, 9);
            in.r[AbstractSupernovaDataManager.nibbleIndex(4)] = NibbleStates.lit(0, 6);
            in.g[AbstractSupernovaDataManager.nibbleIndex(4)] = NibbleStates.lit(1, 4);
            in.b[AbstractSupernovaDataManager.nibbleIndex(4)] = NibbleStates.uninit();
            in.r[AbstractSupernovaDataManager.nibbleIndex(9)] = NibbleStates.full();
            return in;
        }

        private ByteBuffer chunkPacket(final Channels in, final int mask) {
            return chunkPacket(in, mask, true, false);
        }

        private ByteBuffer chunkPacket(final Channels in, final int mask, final boolean fullChunk, final boolean sky) {
            final ByteBuffer buf = ByteBuffer.allocate(manager.maxPacketSize());
            AbstractSupernovaDataManager.writeChunkToBuffer(buf, in.r, in.g, in.b, mask, fullChunk, sky);
            buf.flip();
            return buf;
        }

        @Test
        void lightOutsideTheBlockMaskReachesTheReceiver() {
            final Channels out = new Channels();
            AbstractSupernovaDataManager.readChunkFromBuffer(chunkPacket(serverWithLightAboveItsBlocks(), MASK), out.r, out.g, out.b, MASK, true);

            assertEquals(9, out.r[AbstractSupernovaDataManager.nibbleIndex(3)].getUpdating(0));
            assertEquals(6, out.r[AbstractSupernovaDataManager.nibbleIndex(4)].getUpdating(0), "light in the air over the top block section");
            assertEquals(4, out.g[AbstractSupernovaDataManager.nibbleIndex(4)].getUpdating(1));
            assertTrue(out.b[AbstractSupernovaDataManager.nibbleIndex(4)].isUninitialisedUpdating());
            assertEquals(15, out.r[AbstractSupernovaDataManager.nibbleIndex(9)].getUpdating(77), "a detached section high above");
            assertNull(out.r[AbstractSupernovaDataManager.nibbleIndex(6)], "a section the sender has no light for is not invented");
        }

        @Test
        void fullChunkDropsLightTheSenderNoLongerHas() {
            final Channels out = new Channels();
            out.r[AbstractSupernovaDataManager.nibbleIndex(7)] = NibbleStates.lit(0, 12);
            out.g[AbstractSupernovaDataManager.nibbleIndex(7)] = NibbleStates.lit(0, 12);

            AbstractSupernovaDataManager.readChunkFromBuffer(chunkPacket(serverWithLightAboveItsBlocks(), MASK), out.r, out.g, out.b, MASK, true);

            assertTrue(out.r[AbstractSupernovaDataManager.nibbleIndex(7)].isNullNibbleVisible(), "stale light from an earlier copy of the chunk must go");
            assertTrue(out.g[AbstractSupernovaDataManager.nibbleIndex(7)].isNullNibbleVisible());
        }

        @Test
        void partialUpdateKeepsWhatItDoesNotMention() {
            final Channels in = new Channels();
            in.r[AbstractSupernovaDataManager.nibbleIndex(3)] = NibbleStates.lit(0, 9);
            final Channels out = new Channels();
            out.r[AbstractSupernovaDataManager.nibbleIndex(7)] = NibbleStates.lit(0, 12);

            AbstractSupernovaDataManager.readChunkFromBuffer(chunkPacket(in, MASK, false, false), out.r, out.g, out.b, MASK, false);

            assertEquals(12, out.r[AbstractSupernovaDataManager.nibbleIndex(7)].getUpdating(0));
        }

        /** The receiver keeps its own storage and vanilla light for every section a partial update leaves out; replacing only the RGB would split them. */
        @Test
        void partialUpdateSendsOnlyTheNamedSections() {
            final Channels in = serverWithLightAboveItsBlocks();
            final ByteBuffer buf = chunkPacket(in, MASK, false, false);

            final Channels out = new Channels();
            out.r[AbstractSupernovaDataManager.nibbleIndex(4)] = NibbleStates.lit(0, 2);
            AbstractSupernovaDataManager.readChunkFromBuffer(buf, out.r, out.g, out.b, MASK, false);

            assertEquals(9, out.r[AbstractSupernovaDataManager.nibbleIndex(3)].getUpdating(0));
            assertEquals(2, out.r[AbstractSupernovaDataManager.nibbleIndex(4)].getUpdating(0), "a section outside the mask is not touched");
            assertFalse(buf.hasRemaining());
        }

        @Test
        void sectionsTheReceiverWouldInferAreNotSent() {
            final Channels sky = new Channels();
            sky.r[AbstractSupernovaDataManager.nibbleIndex(3)] = NibbleStates.lit(0, 9);
            for (final int sectionY : new int[] { 4, 5, 6 }) sky.r[AbstractSupernovaDataManager.nibbleIndex(sectionY)] = NibbleStates.full();
            final ByteBuffer skyPacket = chunkPacket(sky, MASK, true, true);
            assertEquals(1 + SWMRNibbleArray.ARRAY_SIZE + 3, skyPacket.limit(), "open sky above the blocks needs no bytes");

            final Channels skyOut = new Channels();
            AbstractSupernovaDataManager.readChunkFromBuffer(skyPacket, skyOut.r, skyOut.g, skyOut.b, MASK, true);
            assertEquals(15, ChunkLightHelper.getSkyLight(skyOut.r, skyOut.g, skyOut.b, 3, 90, 3), "and still reads as open sky");

            // Full sky under something that is not: the receiver would inherit the wrong layer, so it has to be sent.
            sky.r[AbstractSupernovaDataManager.nibbleIndex(9)] = NibbleStates.lit(0, 3);
            final Channels shadedOut = new Channels();
            AbstractSupernovaDataManager.readChunkFromBuffer(chunkPacket(sky, MASK, true, true), shadedOut.r, shadedOut.g, shadedOut.b, MASK, true);
            assertEquals(15, ChunkLightHelper.getSkyLight(shadedOut.r, shadedOut.g, shadedOut.b, 3, 90, 3));
            assertEquals(3, ChunkLightHelper.getSkyLight(shadedOut.r, shadedOut.g, shadedOut.b, 0, 144, 0));

            final Channels block = new Channels();
            block.r[AbstractSupernovaDataManager.nibbleIndex(3)] = NibbleStates.lit(0, 9);
            block.r[AbstractSupernovaDataManager.nibbleIndex(5)] = NibbleStates.uninit();
            assertEquals(1 + SWMRNibbleArray.ARRAY_SIZE + 3, chunkPacket(block, MASK, true, false).limit(), "dark block light needs no bytes");
        }

        @Test
        void packetFromASenderWithoutTheExtraBlockStillReads() {
            final Channels in = serverWithLightAboveItsBlocks();
            final ByteBuffer legacy = ByteBuffer.allocate(1 + 3 * SWMRNibbleArray.ARRAY_SIZE);
            AbstractSupernovaDataManager.writeSectionToBuffer(legacy, in.r, in.g, in.b, 3);
            legacy.flip();

            final Channels out = new Channels();
            out.r[AbstractSupernovaDataManager.nibbleIndex(7)] = NibbleStates.lit(0, 12);
            AbstractSupernovaDataManager.readChunkFromBuffer(legacy, out.r, out.g, out.b, MASK, true);

            assertEquals(9, out.r[AbstractSupernovaDataManager.nibbleIndex(3)].getUpdating(0));
            assertEquals(12, out.r[AbstractSupernovaDataManager.nibbleIndex(7)].getUpdating(0), "nothing says the sender had no light there");
            assertFalse(legacy.hasRemaining());
        }

        @Test
        void receiverWithoutTheExtraBlockReadsTheSameMaskRecords() {
            final Channels in = serverWithLightAboveItsBlocks();
            final ByteBuffer buf = chunkPacket(in, MASK);

            // What a peer built before the extra block does: one record per mask bit, nothing more.
            final Channels out = new Channels();
            AbstractSupernovaDataManager.readSectionFromBuffer(buf, out.r, out.g, out.b, 3);

            assertEquals(9, out.r[AbstractSupernovaDataManager.nibbleIndex(3)].getUpdating(0));
            assertTrue(buf.hasRemaining(), "and the rest of the slice is simply left unread");
        }

        @Test
        void worstCaseFitsTheDeclaredPacketSize() {
            final Channels in = new Channels();
            for (int sectionY = 0; sectionY < 16; ++sectionY) {
                final int idx = AbstractSupernovaDataManager.nibbleIndex(sectionY);
                in.r[idx] = NibbleStates.lit(0, 1);
                in.g[idx] = NibbleStates.lit(0, 2);
                in.b[idx] = NibbleStates.lit(0, 3);
            }
            for (final int mask : new int[] { 0, 0xFFFF, 0x00FF, 0x5555 }) {
                assertTrue(chunkPacket(in, mask).limit() <= manager.maxPacketSize(), "mask " + Integer.toHexString(mask));
            }
        }
    }

    @Test
    void packetAndNbtAgreeOnWhatIsDark() {
        SWMRNibbleArray uninit = NibbleStates.uninit();
        SWMRNibbleArray zeroInit = NibbleStates.zeroInit();
        SWMRNibbleArray lit = NibbleStates.lit(0, 12);
        SWMRNibbleArray nullNib = NibbleStates.nullNibble();

        for (SWMRNibbleArray nib : new SWMRNibbleArray[] { uninit, zeroInit, lit, nullNib }) {
            SWMRNibbleArray.SaveState state = nib.getSaveState();
            boolean nbtSaysDark = state != null && state.data == null;
            assertEquals(nbtSaysDark, nib.isDarkVisible(),
                "the packet and NBT writers must classify every nibble state identically");
        }
        assertFalse(nullNib.isDarkVisible(), "a NULL nibble is absent, not dark");
    }
}
