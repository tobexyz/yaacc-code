/*
 * Copyright (C) 2026 Tobias Schoene www.yaacc.de
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package de.yaacc.upnp.server.avtransport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder;
import org.fourthline.cling.model.ServiceManager;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.ActionArgument;
import org.fourthline.cling.model.meta.LocalService;
import org.fourthline.cling.model.meta.StateVariable;
import org.fourthline.cling.model.state.StateVariableValue;
import org.fourthline.cling.model.types.UnsignedIntegerFourBytes;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.fourthline.cling.support.avtransport.lastchange.AVTransportVariable;
import org.fourthline.cling.support.lastchange.LastChange;
import org.fourthline.cling.support.model.PlayMode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.player.Player;
import de.yaacc.upnp.UpnpClient;

/**
 * What a control point such as Home Assistant (dlna_dmr, async_upnp_client) gets from the media renderer's
 * AVTransport service for the play mode: the {@code LastChange} event XML after {@code SetPlayMode} or a
 * button at the player, the full initial event of a new subscriber, and the answer of
 * {@code GetTransportSettings}. The service is bound from its annotations and run through its real service
 * manager, so the test sees what is handed to the GENA subscriptions. Plain JVM.
 */
public class AvTransportEventTest {

    private static final UnsignedIntegerFourBytes ID = new UnsignedIntegerFourBytes(0);
    private static final String AVT_NAMESPACE = "urn:schemas-upnp-org:metadata-1-0/AVT/";

    private LocalService<YaaccAVTransportService> localService;
    private AvTransportServiceManager manager;
    private ScheduledExecutorService executor;
    private final List<String> events = new ArrayList<>();
    private final BlockingQueue<String> asyncEvents = new LinkedBlockingQueue<>();
    private UpnpClient client;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        client = mock(UpnpClient.class);
        AvTransport.setUpnpClient(client);
        // nothing runs by itself in most tests: the test decides when the event is sent
        executor = mock(ScheduledExecutorService.class);
        startManager(executor);
    }

    @After
    public void tearDown() {
        AvTransport.setUpnpClient(null);
    }

    private void startManager(ScheduledExecutorService withExecutor) {
        events.clear();
        asyncEvents.clear();
        // a service takes its manager once
        localService = new AnnotationLocalServiceBinder().read(YaaccAVTransportService.class);
        manager = new AvTransportServiceManager(localService, 1000, withExecutor);
        localService.setManager(manager);
        // a subscription: what Cling's LocalGENASubscription registers
        manager.getPropertyChangeSupport().addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            @SuppressWarnings("unchecked")
            public void propertyChange(PropertyChangeEvent e) {
                if (!ServiceManager.EVENTED_STATE_VARIABLES.equals(e.getPropertyName())) {
                    return;
                }
                for (StateVariableValue value : (Collection<StateVariableValue>) e.getNewValue()) {
                    if ("LastChange".equals(value.getStateVariable().getName())) {
                        events.add(value.toString());
                        asyncEvents.add(value.toString());
                    }
                }
            }
        });
    }

    private YaaccAVTransportService service() {
        return manager.getImplementation();
    }

    private AvTransport transport() throws Exception {
        return (AvTransport) service().findStateMachine(ID).getCurrentState().getTransport();
    }

    private String lastEvent() {
        assertFalse("no event was sent", events.isEmpty());
        return events.get(events.size() - 1);
    }

    private static void assertInstance0Carries(String xml, String variable, String value) {
        assertTrue("namespace in " + xml, xml.contains("<Event xmlns=\"" + AVT_NAMESPACE + "\""));
        int instance = xml.indexOf("<InstanceID val=\"0\">");
        assertTrue("InstanceID 0 in " + xml, instance >= 0);
        String element = "<" + variable + " val=\"" + value + "\"/>";
        int at = xml.indexOf(element, instance);
        assertTrue(element + " inside InstanceID 0 in " + xml, at > instance);
        assertTrue("element closed after the variable in " + xml, xml.indexOf("</InstanceID>", at) > at);
    }

    // --- the event XML after SetPlayMode

    @Test
    public void everyModeIsSentAsCurrentPlayModeOfInstance0() throws Exception {
        for (PlayMode mode : PlayMode.values()) {
            service().setPlayMode(ID, mode.name());
            manager.fireLastChange();

            assertInstance0Carries(lastEvent(), "CurrentPlayMode", mode.name());
        }
        assertEquals(PlayMode.values().length, events.size());
    }

    @Test
    public void theEventXmlParsesBackLikeAControlPointDoes() throws Exception {
        service().setPlayMode(ID, "REPEAT_ONE");
        manager.fireLastChange();

        LastChange received = new LastChange(new AVTransportLastChangeParser(), lastEvent());

        assertEquals(PlayMode.REPEAT_ONE, received.getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class).getValue());
    }

    @Test
    public void theEventIsTheExactElementHomeAssistantParses() throws Exception {
        service().setPlayMode(ID, "REPEAT_ONE");
        manager.fireLastChange();

        // async_upnp_client: <Event xmlns=".../AVT/"><InstanceID val="0"><CurrentPlayMode val="REPEAT_ONE"/>
        assertTrue(lastEvent(), lastEvent().contains("<CurrentPlayMode val=\"REPEAT_ONE\"/>"));
    }

    @Test
    public void aSentEventIsClearedSoTheNextOnesCarriesOnlyWhatChanged() throws Exception {
        service().setPlayMode(ID, "SHUFFLE");
        manager.fireLastChange();
        service().setPlayMode(ID, "NORMAL");
        manager.fireLastChange();

        assertInstance0Carries(lastEvent(), "CurrentPlayMode", "NORMAL");
        assertFalse(lastEvent(), lastEvent().contains("SHUFFLE"));
    }

    @Test
    public void nothingIsSentWhenNothingChanged() throws Exception {
        service().setPlayMode(ID, "RANDOM");
        manager.fireLastChange();
        manager.fireLastChange();
        service().setPlayMode(ID, "RANDOM");
        manager.fireLastChange();

        assertEquals(1, events.size());
    }

    // --- a change at the player (its repeat or shuffle button)

    @Test
    public void aButtonAtThePlayerIsSentAsCurrentPlayMode() throws Exception {
        Player player = mock(Player.class);
        when(player.getPlayMode()).thenReturn(de.yaacc.player.PlayMode.NORMAL);
        transport().attachPlayers(Collections.singletonList(player));
        manager.fireLastChange();
        events.clear();
        ArgumentCaptor<PropertyChangeListener> listener = ArgumentCaptor.forClass(PropertyChangeListener.class);
        verify(player).addPropertyChangeListener(listener.capture());

        listener.getValue().propertyChange(new PropertyChangeEvent(player, Player.PROPERTY_PLAY_MODE,
                de.yaacc.player.PlayMode.NORMAL, de.yaacc.player.PlayMode.of(de.yaacc.player.PlayMode.RepeatMode.ONE, false)));
        manager.fireLastChange();

        assertInstance0Carries(lastEvent(), "CurrentPlayMode", "REPEAT_ONE");
    }

    @Test
    public void everyModeOfTheButtonsMapsToAnUpnpModeInTheEvent() throws Exception {
        Player player = mock(Player.class);
        when(player.getPlayMode()).thenReturn(de.yaacc.player.PlayMode.NORMAL);
        transport().attachPlayers(Collections.singletonList(player));
        ArgumentCaptor<PropertyChangeListener> listener = ArgumentCaptor.forClass(PropertyChangeListener.class);
        verify(player).addPropertyChangeListener(listener.capture());
        String[][] expected = {
                {"OFF", "false", "NORMAL"}, {"OFF", "true", "SHUFFLE"}, {"ALL", "false", "REPEAT_ALL"},
                {"ALL", "true", "RANDOM"}, {"ONE", "false", "REPEAT_ONE"}, {"OFF", "false", "NORMAL"}};

        for (String[] step : expected) {
            listener.getValue().propertyChange(new PropertyChangeEvent(player, Player.PROPERTY_PLAY_MODE, null,
                    de.yaacc.player.PlayMode.of(de.yaacc.player.PlayMode.RepeatMode.valueOf(step[0]), Boolean.parseBoolean(step[1]))));
            manager.fireLastChange();

            assertInstance0Carries(lastEvent(), "CurrentPlayMode", step[2]);
        }
    }

    // --- the initial event of a new subscriber

    @Test
    public void theInitialEventHasTheFullStateIncludingCurrentPlayMode() throws Exception {
        Collection<StateVariableValue> initial = manager.getCurrentState();

        assertEquals(1, initial.size());
        String xml = initial.iterator().next().toString();
        assertInstance0Carries(xml, "CurrentPlayMode", "NORMAL");
        for (String variable : new String[]{"TransportState", "TransportStatus", "CurrentTransportActions",
                "AVTransportURI", "NumberOfTracks", "CurrentRecordQualityMode"}) {
            assertTrue(variable + " in " + xml, xml.contains("<" + variable + " val="));
        }
    }

    @Test
    public void theInitialEventCarriesTheModeThatWasSet() throws Exception {
        service().setPlayMode(ID, "REPEAT_ALL");

        String xml = manager.getCurrentState().iterator().next().toString();

        assertInstance0Carries(xml, "CurrentPlayMode", "REPEAT_ALL");
    }

    @Test
    public void theInitialEventDoesNotNeedAnythingToHaveHappenedBefore() throws Exception {
        // a subscriber that comes before the first action: no instance exists yet
        assertTrue(service().getCurrentInstanceIds().length == 0);

        String xml = manager.getCurrentState().iterator().next().toString();

        assertInstance0Carries(xml, "CurrentPlayMode", "NORMAL");
    }

    @Test
    public void theInitialEventAndLaterEventsDoNotDisturbEachOther() throws Exception {
        service().setPlayMode(ID, "SHUFFLE");
        manager.getCurrentState(); // does not touch the changes that are still to be sent
        manager.fireLastChange();

        assertInstance0Carries(lastEvent(), "CurrentPlayMode", "SHUFFLE");
    }

    // --- GetTransportSettings

    private ActionInvocation<LocalService> invoke(String action, Object... inputs) {
        Action<LocalService> a = localService.getAction(action);
        ActionInvocation<LocalService> invocation = new ActionInvocation<>(a);
        for (int i = 0; i < inputs.length; i += 2) {
            invocation.setInput((String) inputs[i], inputs[i + 1]);
        }
        localService.getExecutor(a).execute(invocation);
        return invocation;
    }

    @Test
    public void getTransportSettingsAnswersPlayModeAsTheUpnpName() throws Exception {
        for (PlayMode mode : PlayMode.values()) {
            ActionInvocation<LocalService> set = invoke("SetPlayMode", "InstanceID", ID, "NewPlayMode", mode.name());
            assertNull(String.valueOf(set.getFailure()), set.getFailure());

            ActionInvocation<LocalService> get = invoke("GetTransportSettings", "InstanceID", ID);

            assertNull(String.valueOf(get.getFailure()), get.getFailure());
            assertEquals(mode.name(), get.getOutput("PlayMode").toString());
            assertEquals("NOT_IMPLEMENTED", get.getOutput("RecQualityMode").toString());
        }
    }

    @Test
    public void setPlayModeOverTheActionExecutorFailsWith712ForAnUnknownMode() throws Exception {
        ActionInvocation<LocalService> set = invoke("SetPlayMode", "InstanceID", ID, "NewPlayMode", "BACKWARDS");

        assertNotNull(set.getFailure());
        assertEquals(712, set.getFailure().getErrorCode());
    }

    @Test
    public void caseAndSpacesOfTheModeAreForgiven() throws Exception {
        service().setPlayMode(ID, " repeat_one ");

        assertEquals(PlayMode.REPEAT_ONE, service().getTransportSettings(ID).getPlayMode());
    }

    // --- the declaration in the service description

    @Test
    public void currentPlayModeIsNotEventedItselfButOnlyThroughLastChange() {
        StateVariable<LocalService> playMode = localService.getStateVariable("CurrentPlayMode");
        StateVariable<LocalService> lastChange = localService.getStateVariable("LastChange");

        assertFalse(playMode.getEventDetails().isSendEvents());
        assertTrue(lastChange.getEventDetails().isSendEvents());
        assertEquals("a dropped event could not be sent again", 0, lastChange.getEventDetails().getEventMaximumRateMilliseconds());
    }

    @Test
    public void currentPlayModeAllowsTheValuesAControlPointOffersRepeatAndShuffleFor() {
        String[] allowed = localService.getStateVariable("CurrentPlayMode").getTypeDetails().getAllowedValues();

        assertArrayEquals(new String[]{"NORMAL", "SHUFFLE", "REPEAT_ONE", "REPEAT_ALL", "RANDOM", "DIRECT_1", "INTRO"}, allowed);
    }

    @Test
    public void getTransportSettingsNamesItsOutputsForTheStateVariablesAControlPointPolls() {
        Action<LocalService> action = localService.getAction("GetTransportSettings");

        ActionArgument<LocalService> playMode = action.getOutputArgument("PlayMode");
        ActionArgument<LocalService> quality = action.getOutputArgument("RecQualityMode");

        assertEquals("CurrentPlayMode", playMode.getRelatedStateVariableName());
        assertEquals("CurrentRecordQualityMode", quality.getRelatedStateVariableName());
        assertEquals("CurrentPlayMode", localService.getAction("SetPlayMode").getInputArgument("NewPlayMode").getRelatedStateVariableName());
    }

    // --- the event goes out by itself, in order, none lost

    private void startManagerWithRealExecutor() {
        ScheduledThreadPoolExecutor real = new ScheduledThreadPoolExecutor(1);
        real.setRemoveOnCancelPolicy(true);
        executor = real;
        startManager(real);
    }

    private String nextAsyncEvent() throws InterruptedException {
        String event = asyncEvents.poll(5, TimeUnit.SECONDS);
        assertNotNull("no event within 5 s", event);
        return event;
    }

    @Test
    public void aSetPlayModeSendsTheEventWithoutAnyoneFiringIt() throws Exception {
        startManagerWithRealExecutor();
        try {
            invoke("SetPlayMode", "InstanceID", ID, "NewPlayMode", "REPEAT_ONE");

            assertInstance0Carries(nextAsyncEvent(), "CurrentPlayMode", "REPEAT_ONE");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void aChangeAtThePlayerSendsTheEventWithoutAnyoneFiringIt() throws Exception {
        startManagerWithRealExecutor();
        try {
            Player player = mock(Player.class);
            when(player.getPlayMode()).thenReturn(de.yaacc.player.PlayMode.NORMAL);
            transport().attachPlayers(Collections.singletonList(player));
            nextAsyncEvent(); // attaching reports the mode of the player
            ArgumentCaptor<PropertyChangeListener> listener = ArgumentCaptor.forClass(PropertyChangeListener.class);
            verify(player).addPropertyChangeListener(listener.capture());

            listener.getValue().propertyChange(new PropertyChangeEvent(player, Player.PROPERTY_PLAY_MODE, null,
                    de.yaacc.player.PlayMode.of(de.yaacc.player.PlayMode.RepeatMode.ALL, true)));

            assertInstance0Carries(nextAsyncEvent(), "CurrentPlayMode", "RANDOM");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void quickChangesInARowAreNotLostAndTheLastOneWins() throws Exception {
        startManagerWithRealExecutor();
        try {
            // Home Assistant sets repeat and shuffle with two calls right after each other
            invoke("SetPlayMode", "InstanceID", ID, "NewPlayMode", "REPEAT_ALL");
            long first = System.nanoTime();
            nextAsyncEvent();
            invoke("SetPlayMode", "InstanceID", ID, "NewPlayMode", "RANDOM");
            invoke("SetPlayMode", "InstanceID", ID, "NewPlayMode", "REPEAT_ONE");

            String second = nextAsyncEvent();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - first);

            assertInstance0Carries(second, "CurrentPlayMode", "REPEAT_ONE");
            assertTrue("events are spaced, " + elapsedMillis + " ms", elapsedMillis >= AvTransportServiceManager.MIN_EVENT_INTERVAL_MILLIS - 20);
            assertNull("the two quick changes were sent as one", asyncEvents.poll(600, TimeUnit.MILLISECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void aFailingExecutorDoesNotBreakTheAction() throws Exception {
        ScheduledExecutorService broken = mock(ScheduledExecutorService.class);
        when(broken.schedule(org.mockito.ArgumentMatchers.any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class))).thenThrow(new java.util.concurrent.RejectedExecutionException());
        startManager(broken);

        ActionInvocation<LocalService> set = invoke("SetPlayMode", "InstanceID", ID, "NewPlayMode", "SHUFFLE");

        assertNull(String.valueOf(set.getFailure()), set.getFailure());
        assertEquals(PlayMode.SHUFFLE, service().getTransportSettings(ID).getPlayMode());
    }

    // --- ObservableLastChange

    @Test
    public void theLastChangeTellsItsListenerAboutEveryValueButNotAboutNothing() {
        ObservableLastChange lastChange = new ObservableLastChange(new AVTransportLastChangeParser());
        AtomicInteger calls = new AtomicInteger();
        lastChange.setChangeListener(calls::incrementAndGet);

        lastChange.setEventedValue(ID, new AVTransportVariable.CurrentPlayMode(PlayMode.NORMAL));
        lastChange.setEventedValue(0, new AVTransportVariable.CurrentPlayMode(PlayMode.SHUFFLE));
        lastChange.setEventedValue(ID, (org.fourthline.cling.support.lastchange.EventedValue) null);
        lastChange.setEventedValue(ID);

        assertEquals(2, calls.get());
        assertEquals(PlayMode.SHUFFLE, lastChange.getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class).getValue());
    }

    @Test
    public void aListenerThatThrowsDoesNotBreakTheChange() {
        ObservableLastChange lastChange = new ObservableLastChange(new AVTransportLastChangeParser());
        lastChange.setChangeListener(() -> {
            throw new IllegalStateException("boom");
        });

        lastChange.setEventedValue(ID, new AVTransportVariable.CurrentPlayMode(PlayMode.RANDOM));

        assertEquals(PlayMode.RANDOM, lastChange.getEventedValue(ID, AVTransportVariable.CurrentPlayMode.class).getValue());
    }

    @Test
    public void theStateMachineStatesEventTooSoTheTransportStateReachesTheControlPoint() throws Exception {
        service().setAVTransportURI(ID, "http://192.0.2.1/a.mp3", "");
        manager.fireLastChange();

        assertInstance0Carries(lastEvent(), "AVTransportURI", "http://192.0.2.1/a.mp3");
        assertInstance0Carries(lastEvent(), "TransportState", "PLAYING");
    }
}
