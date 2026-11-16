/*
 *
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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.yaacc.multiroom.client.SnapcastClientRestore.Point;
import de.yaacc.multiroom.client.SnapcastClientRestore.Skip;

/**
 * Task 12C run 1: the one switch of the phone's own Snapcast client. The state is ONE persisted boolean; the switch, the checkbox,
 * the dialog (and later the card button) set it through {@link SnapcastClientPower#setOn(boolean)}, and the startup route restores
 * from it. Pure: the preferences and the service are fakes here.
 */
public class SnapcastClientPowerTest {

    private static final String HOST = "192.168.1.77:1704";

    private static final class FakeStore implements SnapcastClientPower.Store {
        boolean on;
        boolean option = true;
        String host = HOST;
        int latency = 30;
        int writeAhead = 200;
        boolean flacGuard = true;
        String ending;
        boolean seen = true;
        final List<Boolean> writes = new ArrayList<>();
        final List<String> recorded = new ArrayList<>();
        final List<String> serverWrites = new ArrayList<>();
        int hostReads;

        @Override
        public void writeLastServer(String hostText) {
            serverWrites.add(hostText);
            host = hostText; // the real store resolves the written key among the known servers
        }

        @Override
        public boolean readSwitchedOn() {
            return on;
        }

        @Override
        public void writeSwitchedOn(boolean value) {
            on = value;
            writes.add(value);
        }

        @Override
        public boolean optionEnabled() {
            return option;
        }

        @Override
        public String host() {
            hostReads++;
            return host;
        }

        @Override
        public int latencyMs() {
            return latency;
        }

        @Override
        public int writeAheadMs() {
            return writeAhead;
        }

        @Override
        public boolean flacGuard() {
            return flacGuard;
        }

        @Override
        public String lastEnding() {
            return ending;
        }

        @Override
        public boolean endingSeen() {
            return seen;
        }

        @Override
        public void markEndingSeen() {
            seen = true;
        }

        @Override
        public void recordEnding(String endingName) {
            ending = endingName;
            seen = false;
            recorded.add(endingName);
        }
    }

    private static final class FakeSession implements SnapcastClientPower.Session {
        boolean active;
        SnapcastClientRuntime.Phase phase = SnapcastClientRuntime.Phase.IDLE;
        RuntimeException startFails;
        RuntimeException stopFails;
        final List<String> starts = new ArrayList<>();
        int startCalls;
        int stops;
        boolean pending;
        RuntimeException restartFails;
        final List<String> restarts = new ArrayList<>();
        java.util.function.Consumer<RuntimeException> restartFailure;

        @Override
        public boolean restartPending() {
            return pending;
        }

        @Override
        public void restart(String host, int latencyMs, int writeAheadMs, boolean flacGuard,
                            java.util.function.Consumer<RuntimeException> onFailure) {
            if (restartFails != null) {
                throw restartFails;
            }
            restarts.add(host + "|" + latencyMs + "|" + writeAheadMs + "|" + flacGuard);
            restartFailure = onFailure;
            pending = true;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public SnapcastClientRuntime.Phase phase() {
            return phase;
        }

        @Override
        public void start(String host, int latencyMs, int writeAheadMs, boolean flacGuard) {
            startCalls++;
            if (startFails != null) {
                throw startFails;
            }
            starts.add(host + "|" + latencyMs + "|" + writeAheadMs + "|" + flacGuard);
        }

        @Override
        public void stop() {
            stops++;
            pending = false; // a stop cancels a restart that waits
            if (stopFails != null) {
                throw stopFails;
            }
        }
    }

    private final FakeStore store = new FakeStore();
    private final FakeSession session = new FakeSession();
    private final List<String> log = new ArrayList<>();
    private long now = 1_000_000L;
    private final SnapcastClientPower power = new SnapcastClientPower(store, session, log::add, () -> now);

    /** Named like the class the platform throws (API 31): it is not on the JVM, the check goes by the name. */
    private static final class ForegroundServiceStartNotAllowedException extends IllegalStateException {
        ForegroundServiceStartNotAllowedException(String message) {
            super(message);
        }
    }

    // ---- the persisted state: one boolean, off by default, read safely ----------------------------------------------

    @Test
    public void theStateIsOffByDefaultAndReadSafely() {
        assertEquals("settings_snapcast_client_switched_on", SnapcastClientSpike.KEY_SWITCHED_ON);
        assertFalse(SnapcastClientSpike.SWITCHED_ON_DEFAULT);
        assertFalse(SnapcastClientSpike.isSwitchedOn(null));
        assertFalse(SnapcastClientSpike.isSwitchedOn((key, def) -> def));
        assertFalse("a damaged value is off", SnapcastClientSpike.isSwitchedOn((key, def) -> {
            throw new ClassCastException("not a boolean");
        }));
        assertTrue(SnapcastClientSpike.isSwitchedOn((key, def) -> SnapcastClientSpike.KEY_SWITCHED_ON.equals(key)));
        assertEquals("snapcast_client_spike_last_ending_seen", SnapcastClientSpike.KEY_LAST_ENDING_SEEN);
    }

    // ---- switched on --------------------------------------------------------------------------------------------------

    @Test
    public void switchingOnStartsTheSessionWithTheSavedSettingsAndKeepsTheState() {
        assertEquals(SnapcastClientPower.Outcome.STARTED, power.setOn(true));

        assertTrue(store.on);
        assertEquals("the host as saved by the test screen, its latency, write-ahead and guard", java.util.Collections.singletonList(HOST + "|30|200|true"),
                session.starts);
    }

    @Test
    public void theStartIsCheckedAgainEveryTimeTheOptionMustBeOn() {
        store.option = false;

        assertEquals(SnapcastClientPower.Outcome.OPTION_OFF, power.setOn(true));

        assertTrue("the state follows the intent: it stays on", store.on);
        assertTrue(session.starts.isEmpty());
        assertEquals("the reason is kept for the 'stopped' line", java.util.Collections.singletonList("DISABLED"), store.recorded);
    }

    @Test
    public void withoutAServerTheStartIsRefusedWithAReason() {
        for (String missing : new String[]{null, "", "   "}) {
            store.host = missing;
            store.recorded.clear();

            assertEquals(String.valueOf(missing), SnapcastClientPower.Outcome.NO_SERVER, power.setOn(true));

            assertEquals(java.util.Collections.singletonList("BAD_ADDRESS"), store.recorded);
        }
        assertTrue(session.starts.isEmpty());
        assertTrue(store.on);
    }

    @Test
    public void aSavedAddressThatIsNotValidIsRefusedBeforeTheService() {
        for (String bad : new String[]{"http://host", "user:pw@host", "host/path", "ho st", "0.0.0.0", "host:0"}) {
            store.host = bad;
            assertEquals(bad, SnapcastClientPower.Outcome.BAD_SERVER, power.setOn(true));
        }
        assertTrue(session.starts.isEmpty());
    }

    @Test
    public void aSessionThatRunsIsNotStartedAgain() {
        session.active = true;

        assertEquals(SnapcastClientPower.Outcome.ALREADY_ACTIVE, power.setOn(true));

        assertTrue(session.starts.isEmpty());
        assertTrue(store.on);
    }

    @Test
    public void aStartTheSystemRefusesIsReportedNotThrownAndTheStateStaysOn() {
        session.startFails = new ForegroundServiceStartNotAllowedException("secret detail of " + HOST);

        assertEquals(SnapcastClientPower.Outcome.NOT_ALLOWED, power.setOn(true));

        assertTrue(store.on);
        assertEquals("not a failure of the session: START_NOT_ALLOWED, so a screen start tries once more",
                java.util.Collections.singletonList("START_NOT_ALLOWED"), store.recorded);
        assertFalse(SnapcastClientRestore.isFailure(store.ending));
        assertEquals("by class name only", 1, log.size());
        assertTrue(log.get(0), log.get(0).contains("ForegroundServiceStartNotAllowedException"));
    }

    @Test
    public void anyOtherRuntimeErrorOfTheStartIsAlsoCaught() {
        session.startFails = new SecurityException("no");

        assertEquals(SnapcastClientPower.Outcome.NOT_ALLOWED, power.setOn(true));
    }

    @Test
    public void noLogLineHasTheAddressOrTheMessageOfAnError() {
        session.startFails = new IllegalStateException("connect to " + HOST + " message-of-the-error");
        power.setOn(true);
        store.option = false;
        power.setOn(true);
        store.option = true;
        store.host = "bad host";
        power.setOn(true);
        store.host = HOST;
        power.restore(Point.PROCESS_START, false);
        for (String line : log) {
            assertFalse(line, line.contains("192.168"));
            assertFalse(line, line.contains("message-of-the-error"));
        }
    }

    // ---- switched off -------------------------------------------------------------------------------------------------

    @Test
    public void switchingOffStopsTheSessionAndClearsTheState() {
        power.setOn(true);
        session.active = true;
        store.ending = "NO_AUDIO";
        store.seen = false;

        assertEquals(SnapcastClientPower.Outcome.STOPPED, power.setOn(false));

        assertFalse(store.on);
        assertEquals(1, session.stops);
        assertTrue("the user has dealt with the last failure", store.seen);
    }

    @Test
    public void switchingOffAlsoSendsTheStopWhenTheSessionIsStillStarting() {
        power.setOn(true); // the intent is on its way: the runtime does not show a session yet
        assertFalse(session.active);

        power.setOn(false);

        assertEquals(1, session.stops);
    }

    @Test
    public void switchingOffWhenNothingWasOnDoesNotTouchTheService() {
        assertEquals(SnapcastClientPower.Outcome.STOPPED, power.setOn(false));

        assertEquals(0, session.stops);
        assertFalse(store.on);
    }

    @Test
    public void aStopThatThrowsStillClearsTheState() {
        power.setOn(true);
        session.stopFails = new IllegalStateException("not running");

        assertEquals(SnapcastClientPower.Outcome.STOPPED, power.setOn(false));

        assertFalse(store.on);
    }

    @Test
    public void onlyTheUsersSwitchOffClearsTheState() {
        power.setOn(true);
        store.writes.clear();
        // everything else that can happen: restores, refusals, failures, the app ending, a second switch on
        session.startFails = new IllegalStateException();
        power.restore(Point.PROCESS_START, false);
        power.restore(Point.SCREEN_START, false);
        store.option = false;
        power.setOn(true);
        power.state();
        power.offered();
        power.markEndingSeen();

        assertFalse("nothing wrote false", store.writes.contains(Boolean.FALSE));
        assertTrue(store.on);
        power.setOn(false);
        assertEquals(Boolean.FALSE, store.writes.get(store.writes.size() - 1));
    }

    // ---- the state to show --------------------------------------------------------------------------------------------

    @Test
    public void theShownStateFollowsTheSwitchAndTheSession() {
        assertEquals(SnapcastClientPower.Shown.OFF, power.state().shown);

        store.on = true;
        assertEquals("on but no session: stopped", SnapcastClientPower.Shown.STOPPED, power.state().shown);

        session.active = true;
        session.phase = SnapcastClientRuntime.Phase.CONNECTING;
        assertEquals(SnapcastClientPower.Shown.CONNECTING, power.state().shown);
        session.phase = SnapcastClientRuntime.Phase.RUNNING;
        assertEquals(SnapcastClientPower.Shown.PLAYING, power.state().shown);
        session.phase = SnapcastClientRuntime.Phase.RECONNECTING;
        assertEquals(SnapcastClientPower.Shown.RECONNECTING, power.state().shown);

        store.on = false;
        assertEquals("off is off", SnapcastClientPower.Shown.OFF, power.state().shown);
    }

    @Test
    public void aStoppedStateCarriesTheLastEndingAndWhetherItIsAnUnseenFailure() {
        store.on = true;
        store.ending = "NO_AUDIO";
        store.seen = false;

        SnapcastClientPower.State state = power.state();
        assertEquals("NO_AUDIO", state.endingName);
        assertTrue(state.unseenFailure);

        power.markEndingSeen();
        assertFalse("viewing it clears 'unseen'", power.state().unseenFailure);
        assertEquals("NO_AUDIO", power.state().endingName);

        store.ending = "APP_EXIT";
        store.seen = false;
        assertFalse("no failure, nothing unseen", power.state().unseenFailure);
    }

    @Test
    public void theSwitchIsOfferedOnlyWithTheOptionOnAndAValidServer() {
        assertTrue(power.offered());
        store.option = false;
        assertFalse(power.offered());
        store.option = true;
        store.host = "";
        assertFalse(power.offered());
        store.host = "http://x";
        assertFalse(power.offered());
        assertTrue("even when the state is off", !store.on);
    }

    // ---- restore -------------------------------------------------------------------------------------------------------

    @Test
    public void aStateThatIsOnStartsTheClientAtTheStartOfTheProcess() {
        store.on = true;

        SnapcastClientPower.RestoreResult result = power.restore(Point.PROCESS_START, false);

        assertTrue(result.decision.restore());
        assertEquals(SnapcastClientPower.Outcome.STARTED, result.outcome);
        assertEquals(1, session.starts.size());
    }

    @Test
    public void aStateThatIsOffStartsNothing() {
        SnapcastClientPower.RestoreResult result = power.restore(Point.PROCESS_START, false);

        assertEquals(Skip.NOT_SWITCHED_ON, result.decision.skip());
        assertNull(result.outcome);
        assertTrue(session.starts.isEmpty());
    }

    @Test
    public void thereIsOneAttemptPerProcess() {
        store.on = true;
        power.restore(Point.PROCESS_START, false);
        session.active = false; // the session ended again meanwhile

        assertEquals(Skip.ALREADY_ATTEMPTED, power.restore(Point.PROCESS_START, false).decision.skip());
        assertEquals(Skip.ALREADY_ATTEMPTED, power.restore(Point.SCREEN_START, false).decision.skip());
        assertEquals(1, session.starts.size());
    }

    @Test
    public void aRefusedBackgroundStartIsTriedOnceMoreWhenAScreenStartsAndNeverAThirdTime() {
        store.on = true;
        session.startFails = new ForegroundServiceStartNotAllowedException("background");

        SnapcastClientPower.RestoreResult first = power.restore(Point.PROCESS_START, false);
        assertEquals(SnapcastClientPower.Outcome.NOT_ALLOWED, first.outcome);

        session.startFails = null; // a screen is up now: the start is allowed
        SnapcastClientPower.RestoreResult second = power.restore(Point.SCREEN_START, false);
        assertEquals(SnapcastClientPower.Outcome.STARTED, second.outcome);

        assertEquals(Skip.ALREADY_ATTEMPTED, power.restore(Point.SCREEN_START, false).decision.skip());
        assertEquals(1, session.starts.size());
        assertTrue("the state was never cleared by the refusals", store.on);
    }

    @Test
    public void aRefusedScreenStartIsNotRetriedInALoop() {
        store.on = true;
        session.startFails = new IllegalStateException();

        power.restore(Point.PROCESS_START, false);
        power.restore(Point.SCREEN_START, false);
        power.restore(Point.SCREEN_START, false);
        power.restore(Point.SCREEN_START, false);

        assertEquals("one try at the start and one at the first screen", 2, session.startCalls);
        assertTrue(store.on);
    }

    @Test
    public void nothingStartsInTheHoldBackWindowAndTheHoldDoesNotUseUpTheAttempt() {
        store.on = true;

        SnapcastClientPower.RestoreResult held = power.restore(Point.PROCESS_START, true);
        assertEquals(Skip.EXIT_HOLD, held.decision.skip());
        assertTrue(session.starts.isEmpty());

        // the user starts the app: the screen start restores
        assertEquals(SnapcastClientPower.Outcome.STARTED, power.restore(Point.SCREEN_START, false).outcome);
    }

    @Test
    public void anUnseenFailureHoldsTheRestoreBackUntilItWasShown() {
        store.on = true;
        store.ending = "SERVER_REFUSED";
        store.seen = false;

        assertEquals(Skip.UNSEEN_FAILURE, power.restore(Point.PROCESS_START, false).decision.skip());
        assertTrue(session.starts.isEmpty());

        power.markEndingSeen(); // the switch or the dialog showed it
        assertEquals(SnapcastClientPower.Outcome.STARTED, power.restore(Point.SCREEN_START, false).outcome);
    }

    @Test
    public void aRestoreChecksTheOptionAndTheServerAgain() {
        store.on = true;
        store.option = false;
        assertEquals(Skip.OPTION_OFF, power.restore(Point.PROCESS_START, false).decision.skip());
        store.option = true;
        store.host = "";
        assertEquals(Skip.NO_SERVER, power.restore(Point.PROCESS_START, false).decision.skip());
        store.host = "http://x";
        assertEquals("an invalid address is no server", Skip.NO_SERVER, power.restore(Point.PROCESS_START, false).decision.skip());
        assertTrue(session.starts.isEmpty());
    }

    @Test
    public void aSessionThatRunsIsLeftAloneByTheRestore() {
        store.on = true;
        session.active = true;

        assertEquals(Skip.ALREADY_ACTIVE, power.restore(Point.PROCESS_START, false).decision.skip());
        assertTrue(session.starts.isEmpty());
    }

    // ---- the platform's refusal of a background start ------------------------------------------------------------------

    @Test
    public void theRefusalIsRecognisedByTheNameOfTheExceptionClassAlsoInTheCauseChain() {
        assertTrue(SnapcastClientPower.isBackgroundStartRefusal(new ForegroundServiceStartNotAllowedException("x")));
        assertTrue(SnapcastClientPower.isBackgroundStartRefusal(new RuntimeException("wrapped", new ForegroundServiceStartNotAllowedException("x"))));
        assertFalse(SnapcastClientPower.isBackgroundStartRefusal(new IllegalStateException("other")));
        assertFalse(SnapcastClientPower.isBackgroundStartRefusal(new SecurityException()));
        assertFalse(SnapcastClientPower.isBackgroundStartRefusal(null));
    }

    @Test
    public void aCauseChainThatLoopsEndsTheSearch() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertFalse(SnapcastClientPower.isBackgroundStartRefusal(a));
    }

    // ---- on or off for the card: what the power button shows --------------------------------------------------------------

    @Test
    public void theCardIsOnWhileASessionStartsRunsOrReconnects() {
        store.on = true;
        session.active = true;
        for (SnapcastClientRuntime.Phase phase : new SnapcastClientRuntime.Phase[]{SnapcastClientRuntime.Phase.CONNECTING,
                SnapcastClientRuntime.Phase.RUNNING, SnapcastClientRuntime.Phase.RECONNECTING}) {
            session.phase = phase;
            assertTrue(phase.name(), power.isPoweredOn());
        }
    }

    @Test
    public void theCardIsOffWhenTheSwitchIsOffWhateverTheSessionStillDoes() {
        session.active = true;
        session.phase = SnapcastClientRuntime.Phase.RUNNING;

        assertFalse("off is off, also while the session is still ending", power.isPoweredOn());
        assertFalse(new SnapcastClientPower(store, new FakeSession(), log::add).isPoweredOn());
    }

    @Test
    public void theCardIsOffWhenTheSwitchIsOnButNoSessionRunsAndNoStartWasRequested() {
        store.on = true;
        store.ending = "CONNECTION_GAVE_UP";

        assertFalse("the session is over: the card shows off, a tap starts it again", power.isPoweredOn());
        store.ending = null;
        assertFalse("at the start of the process, before the restore: nothing started yet", power.isPoweredOn());
    }

    @Test
    public void theCardIsOnForAMomentWhileTheStartIsOnItsWayToTheService() {
        assertEquals(SnapcastClientPower.Outcome.STARTED, power.setOn(true)); // the runtime shows no session yet

        assertTrue("on and starting", power.isPoweredOn());
        now += 4_999L;
        assertTrue(power.isPoweredOn());
        now += 2L;
        assertFalse("a start that never showed a phase is not 'on' for ever", power.isPoweredOn());
    }

    @Test
    public void aRefusedStartIsNotShownAsStarting() {
        store.option = false;
        power.setOn(true);

        assertFalse(power.isPoweredOn());
    }

    @Test
    public void theCardIsOnWhileARestartWaits() {
        store.on = true;
        session.pending = true;

        assertTrue(power.isPoweredOn());
    }

    // ---- choosing the server of the client ---------------------------------------------------------------------------------

    @Test
    public void whileOffTheChosenServerIsOnlyRememberedForTheNextStart() {
        assertEquals(SnapcastClientPower.Outcome.SERVER_SET, power.chooseServer("192.168.1.9"));

        assertEquals(java.util.Collections.singletonList("192.168.1.9"), store.serverWrites);
        assertTrue(session.starts.isEmpty());
        assertTrue(session.restarts.isEmpty());
        assertEquals(0, session.stops);
        assertFalse("choosing a server does not switch the client on", store.on);
        assertTrue("no write of the state at all", store.writes.isEmpty());
    }

    @Test
    public void whileOnAndConnectedAnotherServerRestartsTheSessionOnIt() {
        power.setOn(true);
        session.active = true;
        session.phase = SnapcastClientRuntime.Phase.RUNNING;
        store.writes.clear();

        assertEquals(SnapcastClientPower.Outcome.RESTARTING, power.chooseServer("192.168.1.9"));

        assertEquals("one restart with the saved settings, on the chosen server", java.util.Collections.singletonList("192.168.1.9|30|200|true"), session.restarts);
        assertEquals("the restart is the only start: no second one next to it", 1, session.starts.size());
        assertTrue("the state stays on", store.on);
        assertTrue("and nothing wrote it", store.writes.isEmpty());
        assertTrue(power.isPoweredOn());
    }

    @Test
    public void theSameServerIsNotRestarted() {
        power.setOn(true);
        session.active = true;
        session.phase = SnapcastClientRuntime.Phase.RUNNING;

        assertEquals(SnapcastClientPower.Outcome.SERVER_SET, power.chooseServer(HOST));

        assertTrue(session.restarts.isEmpty());
        assertEquals(0, session.stops);
    }

    @Test
    public void aSecondChoiceWhileTheRestartWaitsReplacesTheTargetWithoutAnotherStart() {
        power.setOn(true);
        session.active = true;
        power.chooseServer("192.168.1.9");
        session.active = false; // the old session is over, the service is still going down

        assertEquals(SnapcastClientPower.Outcome.RESTARTING, power.chooseServer("192.168.1.20"));

        assertEquals(2, session.restarts.size());
        assertTrue(session.restarts.get(1).startsWith("192.168.1.20|"));
        assertEquals("the one start of switching on; the restarts start nothing by themselves here", 1, session.starts.size());
    }

    @Test
    public void switchingOnWhileARestartWaitsDoesNotStartASecondSession() {
        power.setOn(true);
        session.active = true;
        power.chooseServer("192.168.1.9");
        session.active = false;

        assertEquals(SnapcastClientPower.Outcome.ALREADY_ACTIVE, power.setOn(true));

        assertEquals(1, session.starts.size());
    }

    @Test
    public void switchingOffWhileARestartWaitsCancelsIt() {
        power.setOn(true);
        session.active = true;
        power.chooseServer("192.168.1.9");

        power.setOn(false);

        assertFalse(session.pending);
        assertFalse(store.on);
        assertFalse(power.isPoweredOn());
    }

    @Test
    public void switchedOnWithoutASessionTheChoiceStartsOnTheChosenServer() {
        store.on = true; // on, but the session gave up earlier

        assertEquals(SnapcastClientPower.Outcome.STARTED, power.chooseServer("192.168.1.9"));

        assertEquals(java.util.Collections.singletonList("192.168.1.9|30|200|true"), session.starts);
        assertTrue(session.restarts.isEmpty());
    }

    @Test
    public void aServerThatIsNoValidAddressIsRefusedAndNothingIsStored() {
        power.setOn(true);
        session.active = true;
        for (String bad : new String[]{null, "", "  ", "http://x", "user:pw@x", "x/y", "ho st", "0.0.0.0", "h:0"}) {
            assertEquals(String.valueOf(bad), SnapcastClientPower.Outcome.BAD_SERVER, power.chooseServer(bad));
        }
        assertTrue(store.serverWrites.isEmpty());
        assertTrue(session.restarts.isEmpty());
    }

    @Test
    public void theOptionIsCheckedAgainWhenAServerIsChosenWhileOn() {
        power.setOn(true);
        session.active = true;
        store.option = false;

        assertEquals(SnapcastClientPower.Outcome.OPTION_OFF, power.chooseServer("192.168.1.9"));

        assertTrue(session.restarts.isEmpty());
        assertEquals("the choice itself is kept for later", java.util.Collections.singletonList("192.168.1.9"), store.serverWrites);
    }

    @Test
    public void aRestartTheSystemRefusesIsReportedNotThrownAndKeepsTheStateOn() {
        power.setOn(true);
        session.active = true;
        session.restartFails = new ForegroundServiceStartNotAllowedException("secret " + HOST);

        assertEquals(SnapcastClientPower.Outcome.NOT_ALLOWED, power.chooseServer("192.168.1.9"));

        assertTrue(store.on);
        assertEquals("START_NOT_ALLOWED", store.ending);
    }

    @Test
    public void aStartThatTheRestartCouldNotMakeLaterIsRecordedAsTheLastEnding() {
        power.setOn(true);
        session.active = true;
        power.chooseServer("192.168.1.9");

        session.restartFailure.accept(new IllegalStateException("connect to " + HOST + " message-of-the-error"));

        assertEquals("START_NOT_ALLOWED", store.ending);
        assertTrue(store.on);
        for (String line : log) {
            assertFalse(line, line.contains("192.168"));
            assertFalse(line, line.contains("message-of-the-error"));
        }
    }

    // ---- the restore asks the server only when it has to ---------------------------------------------------------------------

    @Test
    public void theRestoreDoesNotLookUpTheServerWhenTheClientIsNotSwitchedOn() {
        power.restore(Point.PROCESS_START, false);

        assertEquals("not switched on: the server list is not even read", 0, store.hostReads);
    }

    @Test
    public void theRestoreStillSaysWhyWhenNoServerIsKnown() {
        store.on = true;
        store.host = "";

        assertEquals(Skip.NO_SERVER, power.restore(Point.PROCESS_START, false).decision.skip());
    }
}
