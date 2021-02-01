/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.meet.test;

import org.jitsi.meet.test.base.*;
import org.jitsi.meet.test.util.*;
import org.jitsi.meet.test.web.*;
import org.testng.*;
import org.testng.annotations.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * @author Damian Minkov
 * @author Boris Grozev
 */
public class MalleusJitsificus
    extends WebTestBase
{
    /**
     * The video file to use as input for the first participant (the sender).
     */
    private static final String INPUT_VIDEO_FILE
        = "resources/FourPeople_1280x720_30.y4m";

    public static final String CONFERENCES_PNAME
        = "org.jitsi.malleus.conferences";
    public static final String PARTICIPANTS_PNAME
        = "org.jitsi.malleus.participants";
    public static final String SENDERS_PNAME
        = "org.jitsi.malleus.senders";
    public static final String AUDIO_SENDERS_PNAME
        = "org.jitsi.malleus.audio_senders";
    public static final String ENABLE_P2P_PNAME
        = "org.jitsi.malleus.enable_p2p";
    public static final String DURATION_PNAME
        = "org.jitsi.malleus.duration";
    public static final String ROOM_NAME_PREFIX_PNAME
        = "org.jitsi.malleus.room_name_prefix";
    public static final String REGIONS_PNAME
        = "org.jitsi.malleus.regions";
    public static final String USE_NODE_TYPES_PNAME
        = "org.jitsi.malleus.use_node_types";
    public static final String MAX_DISRUPTED_BRIDGES_PCT_PNAME
        = "org.jitsi.malleus.max_disrupted_bridges_pct";
    public static final String USE_LOAD_TEST_PNAME
        = "org.jitsi.malleus.use_load_test";

    private final Phaser allHungUp = new Phaser();

    @DataProvider(name = "dp", parallel = true)
    public Object[][] createData(ITestContext context)
    {
        // If the tests is not in the list of tests to be executed,
        // skip executing the DataProvider.
        if (isSkipped())
        {
            return new Object[0][0];
        }

        int numConferences = Integer.parseInt(System.getProperty(CONFERENCES_PNAME));
        int numParticipants = Integer.parseInt(System.getProperty(PARTICIPANTS_PNAME));
        String numSendersStr = System.getProperty(SENDERS_PNAME);
        int numSenders = numSendersStr == null
            ? numParticipants
            : Integer.parseInt(numSendersStr);

        String numAudioSendersStr = System.getProperty(AUDIO_SENDERS_PNAME);
        int numAudioSenders = numAudioSendersStr == null
                ? numParticipants
                : Integer.parseInt(numAudioSendersStr);

        int timeoutMs = 1000 * Integer.parseInt(System.getProperty(DURATION_PNAME));

        String[] regions = null;
        String regionsStr = System.getProperty(REGIONS_PNAME);
        if (regionsStr != null && !"".equals(regionsStr))
        {
            regions = regionsStr.split(",");
        }

        String roomNamePrefix = System.getProperty(ROOM_NAME_PREFIX_PNAME);
        if (roomNamePrefix == null)
        {
            roomNamePrefix = "anvil-";
        }

        String enableP2pStr = System.getProperty(ENABLE_P2P_PNAME);
        boolean enableP2p
            = enableP2pStr == null || Boolean.parseBoolean(enableP2pStr);

        float maxDisruptedBidges = 0;
        String maxDisruptedBidgesStr = System.getProperty(MAX_DISRUPTED_BRIDGES_PCT_PNAME);
        if (!"".equals(maxDisruptedBidgesStr))
        {
            maxDisruptedBidges = Float.parseFloat(maxDisruptedBidgesStr);
        }

        // Use one thread per conference.
        context.getCurrentXmlTest().getSuite()
            .setDataProviderThreadCount(numConferences);

        print("will run with:");
        print("conferences="+ numConferences);
        print("participants=" + numParticipants);
        print("senders=" + numSenders);
        print("audio senders=" + numAudioSenders);
        print("duration=" + timeoutMs + "ms");
        print("room_name_prefix=" + roomNamePrefix);
        print("enable_p2p=" + enableP2p);
        print("max_disrupted_bridges_pct=" + maxDisruptedBidges);
        print("regions=" + (regions == null ? "null" : Arrays.toString(regions)));

        Object[][] ret = new Object[numConferences][4];
        for (int i = 0; i < numConferences; i++)
        {
            String roomName = roomNamePrefix + i;
            JitsiMeetUrl url
                = participants.getJitsiMeetUrl()
                .setRoomName(roomName)
                // XXX I don't remember if/why these are needed.
                .appendConfig("config.p2p.useStunTurn=true")
                .appendConfig("config.disable1On1Mode=false")
                .appendConfig("config.testing.noAutoPlayVideo=true")
                .appendConfig("config.pcStatsInterval=10000")

                .appendConfig("config.p2p.enabled=" + (enableP2p ? "true" : "false"));
            ret[i] = new Object[] {
                url, numParticipants, timeoutMs, numSenders, numAudioSenders, regions, maxDisruptedBidges
            };
        }

        return ret;
    }

    private CountDownLatch bridgeSelectionCountDownLatch;

    @Test(dataProvider = "dp")
    public void testMain(
        JitsiMeetUrl url, int numberOfParticipants, long waitTimeMs, int numSenders,
        int numAudioSenders, String[] regions, float blipMaxDisruptedPct)
        throws Exception
    {
        ParticipantThread[] runThreads = new ParticipantThread[numberOfParticipants];

        bridgeSelectionCountDownLatch = new CountDownLatch(numberOfParticipants);

        boolean useLoadTest = Boolean.parseBoolean(System.getProperty(USE_LOAD_TEST_PNAME));

        for (int i = 0; i < numberOfParticipants; i++)
        {
            runThreads[i]
                = new ParticipantThread(
                i,
                url.copy(),
                waitTimeMs,
                i >= numSenders /* no video */,
                i >= numAudioSenders /* no audio */,
                regions == null ? null : regions[i % regions.length]);

            runThreads[i].start();
        }

        try
        {
            disruptBridges(blipMaxDisruptedPct, waitTimeMs / 1000, runThreads);
        }
        catch (Exception e)
        {
            throw e;
        }
        finally
        {
            int minFailureTolerance = Integer.MAX_VALUE;
            for (ParticipantThread t : runThreads)
            {
                if (t != null)
                {
                    t.join();

                    minFailureTolerance = Math.min(minFailureTolerance, t.failureTolerance);
                }
            }

            if (minFailureTolerance < 0)
            {
                throw new Exception("Minimum failure tolerance is less than 0");
            }
        }
    }

    private class ParticipantThread
        extends Thread
    {
        private final int i;
        private final JitsiMeetUrl _url;
        private final long waitTimeMs;
        private final boolean muteVideo;
        private final boolean muteAudio;
        private final String region;

        WebParticipant participant;
        public int failureTolerance;
        private String bridge;

        public ParticipantThread(
            int i, JitsiMeetUrl url, long waitTimeMs,
            boolean muteVideo, boolean muteAudio, String region)
        {
            this.i = i;
            this._url = url;
            this.waitTimeMs = waitTimeMs;
            this.muteVideo = muteVideo;
            this.muteAudio = muteAudio;
            this.region = region;
        }

        @Override
        public void run()
        {
            WebParticipantOptions ops
                = new WebParticipantOptions()
                        .setFakeStreamVideoFile(INPUT_VIDEO_FILE);

            if (muteVideo)
            {
                _url.appendConfig("config.startWithVideoMuted=true");
            }
            if (muteAudio)
            {
                _url.appendConfig("config.startWithAudioMuted=true");
            }

            boolean useNodeTypes = Boolean.parseBoolean(System.getProperty(USE_NODE_TYPES_PNAME));

            if (useNodeTypes)
            {
                if (muteVideo)
                {
                    /* TODO: is it okay to have an audio sender use a malleus receiver ep? */
                    ops.setApplicationName("malleusReceiver");
                }
                else
                {
                    ops.setApplicationName("malleusSender");
                }
            }

            if (region != null)
            {
                _url.appendConfig("config.deploymentInfo.userRegion=\"" + region + "\"");
            }

            participant = participants.createParticipant("web.participant" + (i + 1), ops);
            allHungUp.register();
            try
            {
                participant.joinConference(_url);
            }
            catch (Exception e)
            {
                /* If join failed, don't block other threads from hanging up. */
                allHungUp.arriveAndDeregister();
                throw e;
            }

            try
            {
                bridge = participant.getBridgeIp();
            }
            catch (Exception e)
            {
                /* If we fail to fetch the bridge ip, don't block other threads from hanging up. */
                allHungUp.arriveAndDeregister();
                throw e;
            }
            finally
            {
                bridgeSelectionCountDownLatch.countDown();
            }

            try
            {
                check();
            }
            catch (InterruptedException e)
            {
                allHungUp.arriveAndDeregister();
                throw new RuntimeException(e);
            }
            finally
            {
                try
                {
                    participant.hangUp();
                }
                catch (Exception e)
                {
                    TestUtils.print("Exception hanging up " + participant.getName());
                    e.printStackTrace();
                }
                try
                {
                    /* There seems to be a Selenium or chrome webdriver bug where closing one parallel
                     * Chrome session can cause another one to close too.  So wait for all sessions
                     * to hang up before we close any of them.
                     */
                    allHungUp.arriveAndAwaitAdvance();
                    MalleusJitsificus.this.closeParticipant(participant);
                }
                catch (Exception e)
                {
                    TestUtils.print("Exception closing " + participant.getName());
                    e.printStackTrace();
                }
            }
        }

        private void check()
            throws InterruptedException
        {
            long healthCheckIntervalMs = 5000;
            long remainingMs = waitTimeMs;
            while (remainingMs > 0)
            {
                long sleepTime = Math.min(healthCheckIntervalMs, remainingMs);

                long currentMillis = System.currentTimeMillis();

                Thread.sleep(sleepTime);

                try
                {
                    participant.waitForIceConnected(0 /* no timeout */);
                    TestUtils.print("Participant " + i + " is connected (tolerance=" + failureTolerance + ").");
                }
                catch (Exception ex)
                {
                    TestUtils.print("Participant " + i + " is NOT connected (tolerance=" + failureTolerance + ").");
                    failureTolerance--;
                    if (failureTolerance < 0)
                    {
                        throw ex;
                    }
                    else
                    {
                        // wait for reconnect
                        participant.waitForIceConnected(20);
                        TestUtils.print("Participant " + i + " reconnected (tolerance=" + failureTolerance + ").");
                    }
                }

                // we use the elapsedMillis because the healthcheck operation may
                // also take time that needs to be accounted for.
                long elapsedMillis = System.currentTimeMillis() - currentMillis;

                remainingMs -= elapsedMillis;
            }
        }
    }

    private void disruptBridges(float blipMaxDisruptedPct, long duration, ParticipantThread[] runThreads)
        throws Exception
    {
        if (blipMaxDisruptedPct > 0)
        {
            bridgeSelectionCountDownLatch.await();

            Set<String> bridges = Arrays.stream(runThreads)
                .map(t -> t.bridge).collect(Collectors.toSet());

            Set<String> bridgesToFail = bridges.stream()
                .limit((long) (Math.ceil(bridges.size() * blipMaxDisruptedPct / 100))).collect(Collectors.toSet());

            for (ParticipantThread runThread : runThreads)
            {
                if (bridgesToFail.contains(runThread.bridge))
                {
                    runThread.failureTolerance = 1;
                }
            }

            Blip.failFor(duration).theseBridges(bridgesToFail).call();
        }
    }

    private Thread runLoadTestAsync(int i,
        JitsiMeetUrl url,
        long waitTime,
        boolean muteVideo,
        boolean muteAudio,
        String region)
    {
        StringBuilder urlBuilder = new StringBuilder(url.getServerUrl())
            .append("/static/load-test/load-test-participant.html#roomName=")
            .append(URLEncoder.encode("\"" + url.getRoomName() + "\""));

        if (!muteVideo)
        {
            urlBuilder.append("&localVideo=true");
        }
        if (!muteAudio)
        {
            urlBuilder.append("&localAudio=true");
        }
        if (region != null)
        {
            urlBuilder.append("config.deploymentInfo.userRegion=\"")
                .append (region).append("\"");
        }

        final String _url = urlBuilder.toString();

        Thread joinThread = new Thread(() -> {

            WebParticipantOptions ops
                = new WebParticipantOptions()
                .setFakeStreamVideoFile(INPUT_VIDEO_FILE);

            boolean useNodeTypes = Boolean.parseBoolean(System.getProperty(USE_NODE_TYPES_PNAME));

            if (useNodeTypes)
            {
                if (muteVideo)
                {
                    /* TODO: is it okay to have an audio sender use a malleus receiver ep? */
                    ops.setApplicationName("malleusReceiver");
                }
                else
                {
                    ops.setApplicationName("malleusSender");
                }
            }

            WebParticipant participant = participants.createParticipant("web.participant" + (i + 1), ops);

            participant.getDriver().get(_url);

            try
            {
                Thread.sleep(waitTime);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
            finally
            {
                try
                {
                    participant.closeSafely();
                }
                catch (Exception e)
                {
                    TestUtils.print("Exception closing " + participant.getName());
                    e.printStackTrace();
                }
            }
        });

        joinThread.start();

        return joinThread;
    }

    private void print(String s)
    {
        System.err.println(s);
    }

    /**
     * {@inheritDoc}
     */
    public boolean skipTestByDefault()
    {
        // Skip by default.
        return true;
    }
}
