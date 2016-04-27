/*
 * Copyright 2015-2016 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.replication;

import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ReceiveChannelEndpointSupplier;
import io.aeron.driver.SendChannelEndpointSupplier;
import io.aeron.driver.ext.DebugReceiveChannelEndpoint;
import io.aeron.driver.ext.DebugSendChannelEndpoint;
import org.agrona.CloseHelper;
import org.agrona.collections.IntHashSet;
import org.agrona.concurrent.YieldingIdleStrategy;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.fix_gateway.DebugLogger;
import uk.co.real_logic.fix_gateway.engine.logger.ArchiveMetaData;
import uk.co.real_logic.fix_gateway.engine.logger.ArchiveReader;
import uk.co.real_logic.fix_gateway.engine.logger.Archiver;

import static io.aeron.CommonContext.AERON_DIR_PROP_DEFAULT;
import static io.aeron.driver.ThreadingMode.SHARED;
import static org.mockito.Mockito.mock;
import static uk.co.real_logic.fix_gateway.TestFixtures.cleanupDirectory;
import static uk.co.real_logic.fix_gateway.engine.EngineConfiguration.DEFAULT_LOGGER_CACHE_NUM_SETS;
import static uk.co.real_logic.fix_gateway.engine.EngineConfiguration.DEFAULT_LOGGER_CACHE_SET_SIZE;
import static uk.co.real_logic.fix_gateway.replication.RaftNodeConfiguration.DEFAULT_DATA_STREAM_ID;

class NodeRunner implements AutoCloseable
{
    private static final long TIMEOUT_IN_MS = 1000;
    private static final String AERON_CHANNEL = "aeron:udp?group=224.0.1.1:40456";

    private final SwitchableLossGenerator lossGenerator = new SwitchableLossGenerator();

    private final MediaDriver mediaDriver;
    private final Aeron aeron;
    private final RaftNode raftNode;
    private final int nodeId;

    private long replicatedPosition = -1;

    NodeRunner(final int nodeId, final int... otherNodes)
    {
        this.nodeId = nodeId;

        final int termBufferLength = 1024 * 1024;
        final MediaDriver.Context context = new MediaDriver.Context();
        context
            .threadingMode(SHARED)
            .sharedIdleStrategy(new YieldingIdleStrategy())
            .receiveChannelEndpointSupplier(newReceiveChannelEndpointSupplier())
            .sendChannelEndpointSupplier(newSendChannelEndpointSupplier())
            .dirsDeleteOnStart(true)
            .aeronDirectoryName(AERON_DIR_PROP_DEFAULT + nodeId)
            .publicationTermBufferLength(termBufferLength)
            .ipcTermBufferLength(termBufferLength);

        final IntHashSet otherNodeIds = new IntHashSet(40, -1);
        for (final int node : otherNodes)
        {
            otherNodeIds.add(node);
        }

        mediaDriver = MediaDriver.launch(context);
        final Aeron.Context clientContext = new Aeron.Context();
        clientContext.aeronDirectoryName(context.aeronDirectoryName());
        aeron = Aeron.connect(clientContext);

        final StreamIdentifier dataStream = new StreamIdentifier(AERON_CHANNEL, DEFAULT_DATA_STREAM_ID);
        final ArchiveMetaData metaData = AbstractReplicationTest.archiveMetaData((short) nodeId);
        final ArchiveReader archiveReader = new ArchiveReader(
            metaData, DEFAULT_LOGGER_CACHE_NUM_SETS, DEFAULT_LOGGER_CACHE_SET_SIZE, dataStream);
        final Archiver archiver = new Archiver(
            metaData, DEFAULT_LOGGER_CACHE_NUM_SETS, DEFAULT_LOGGER_CACHE_SET_SIZE, dataStream);

        final RaftNodeConfiguration configuration = new RaftNodeConfiguration()
            .nodeId((short) nodeId)
            .aeron(aeron)
            .otherNodes(otherNodeIds)
            .timeoutIntervalInMs(TIMEOUT_IN_MS)
            .fragmentHandler((buffer, offset, length, header) ->
            {
                replicatedPosition = offset + length;
                DebugLogger.log("%d: position %d\n", nodeId, replicatedPosition);
            })
            .failCounter(mock(AtomicCounter.class))
            .aeronChannel(AERON_CHANNEL)
            .archiver(archiver)
            .archiveReader(archiveReader);

        raftNode = new RaftNode(configuration, System.currentTimeMillis());
    }

    private SendChannelEndpointSupplier newSendChannelEndpointSupplier()
    {
        return (udpChannel, context) ->
            new DebugSendChannelEndpoint(udpChannel, context, lossGenerator, lossGenerator);
    }

    private ReceiveChannelEndpointSupplier newReceiveChannelEndpointSupplier()
    {
        return (udpChannel, dispatcher, context) ->
            new DebugReceiveChannelEndpoint(udpChannel, dispatcher, context, lossGenerator, lossGenerator);
    }

    public int poll(final int fragmentLimit, final long timeInMs)
    {
        return raftNode.poll(fragmentLimit, timeInMs);
    }

    public void dropFrames(final boolean dropFrames)
    {
        DebugLogger.log("Dropping frames to %d: %b\n", nodeId, dropFrames);
        lossGenerator.dropFrames(dropFrames);
    }

    public boolean isLeader()
    {
        return raftNode.isLeader();
    }

    public RaftNode raftNode()
    {
        return raftNode;
    }

    public long replicatedPosition()
    {
        return replicatedPosition;
    }

    public void close()
    {
        CloseHelper.close(aeron);
        CloseHelper.close(mediaDriver);
        cleanupDirectory(mediaDriver);
    }
}
