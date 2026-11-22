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

import java.util.function.Consumer;

/**
 * Task 12C: the one switch of the phone's own Snapcast client, without Android. The state "switched on" is ONE persisted boolean
 * ({@link SnapcastClient#KEY_SWITCHED_ON}); this class is the only place that writes it. The switch on the first page, the
 * checkbox of the settings, the listen test dialog and (next run) the power button of the card all go through {@link #setOn}; the
 * application restores from it at the start ({@link #restore}).
 *
 * <ul>
 * <li><b>On</b>: the state is written first (it follows the intent and stays on whatever happens next), then the session is started
 * through the existing start path. The option and the saved server are checked on EVERY start; a refusal or a failure is returned as an
 * {@link Outcome} (the caller shows its reason) and the reason is kept as the last ending, so that the switch and the dialog show
 * "stopped" with it. No retry loop.</li>
 * <li><b>Off</b>: the state is cleared and the session is stopped through the existing stop path (the service records the end as a
 * stop by the user and releases its locks).</li>
 * <li>Nothing else ever clears the state: not the exit, the sleep timer, the idle timer, the end of the process, a failure of the
 * session or a refused start.</li>
 * </ul>
 *
 * <p>Nothing here is logged but class names and fixed words: never the address, never the message of an exception.
 */
public final class SnapcastClientPower {

    /**
     * The persisted settings and the state; `SharedPreferences` in the app.
     */
    public interface Store {
        boolean readSwitchedOn();

        void writeSwitchedOn(boolean on);

        /**
         * The experimental option of the client ({@link SnapcastClient#isEnabled}).
         */
        boolean optionEnabled();

        /**
         * The server the client uses ({@code host[:port]}): the last one it was connected to, else the first known one
         * ({@link de.yaacc.multiroom.ui.SnapcastClientServerChoice}); null or empty when no server is known.
         */
        String host();

        int latencyMs();

        int writeAheadMs();

        boolean flacGuard();

        /**
         * The name of the last {@link SnapcastClientRuntime.Ending}; null when none.
         */
        String lastEnding();

        /**
         * Whether the user has seen the last ending (true when there is none).
         */
        boolean endingSeen();

        void markEndingSeen();

        /**
         * Keeps the reason of a start that did not happen, as the last ending (unseen).
         */
        void recordEnding(String endingName);

        /**
         * The server the user chose (the text of one of the known servers): the one the next start uses.
         */
        void writeLastServer(String hostText);
    }

    /**
     * The service of the client; {@link SnapcastClientRuntime} and {@link SnapcastClientService} in the app.
     */
    public interface Session {
        /**
         * A session is starting or running.
         */
        boolean isActive();

        SnapcastClientRuntime.Phase phase();

        /**
         * Starts the foreground service; may throw when the system refuses a start from the background.
         */
        void start(String host, int latencyMs, int writeAheadMs, boolean flacGuard);

        void stop();

        /**
         * A restart waits for the end of the old session, to start the new one.
         */
        boolean restartPending();

        /**
         * Ends the session, waits until it is gone, then starts it on {@code host}; a start that is refused then is reported to
         * {@code onFailure}. A later {@link #stop} cancels the restart.
         */
        void restart(String host, int latencyMs, int writeAheadMs, boolean flacGuard, Consumer<RuntimeException> onFailure);
    }

    /**
     * What a call came to.
     */
    public enum Outcome {
        STARTED, ALREADY_ACTIVE, STOPPED, OPTION_OFF, NO_SERVER, BAD_SERVER, NOT_ALLOWED,
        /**
         * The server was set; the client is off or already on it: nothing was restarted.
         */
        SERVER_SET,
        /**
         * The server was set; the session ends and starts again on it.
         */
        RESTARTING;

        /**
         * The start did not happen (its reason is to be shown).
         */
        public boolean isRefused() {
            return this == OPTION_OFF || this == NO_SERVER || this == BAD_SERVER || this == NOT_ALLOWED;
        }
    }

    /**
     * What the switch and the dialog show.
     */
    public enum Shown {
        OFF, CONNECTING, PLAYING, RECONNECTING, STOPPED
    }

    /**
     * The state to show: where the client is, and how its last session ended. Immutable.
     */
    public static final class State {
        public final Shown shown;
        /**
         * The stored name of the last ending; null when none.
         */
        public final String endingName;
        /**
         * The last ending is a failure that the user has not seen yet.
         */
        public final boolean unseenFailure;

        public State(Shown shown, String endingName, boolean unseenFailure) {
            this.shown = shown;
            this.endingName = endingName;
            this.unseenFailure = unseenFailure;
        }
    }

    /**
     * What a restore came to: the decision, and the outcome of the start when one was made (null when skipped).
     */
    public static final class RestoreResult {
        public final SnapcastClientRestore.Decision decision;
        public final Outcome outcome;

        RestoreResult(SnapcastClientRestore.Decision decision, Outcome outcome) {
            this.decision = decision;
            this.outcome = outcome;
        }
    }

    /**
     * After a start was handed to the service the card counts as on for this long, until the session shows its first phase.
     */
    static final long START_GRACE_MS = 5_000L;

    private static final String REFUSAL_CLASS = "ForegroundServiceStartNotAllowedException";
    private static final int MAX_CAUSE_DEPTH = 8;

    private final Store store;
    private final Session session;
    private final Consumer<String> log;
    private final java.util.function.LongSupplier clock;
    // when the last start was handed to the service (0: none); the service needs a moment to show its first phase
    private volatile long startRequestedAt;
    // the restore attempts of this process (the object lives as long as the process: one per application)
    private int attempts;
    private boolean lastAttemptRefused;

    public SnapcastClientPower(Store store, Session session, Consumer<String> log) {
        this(store, session, log, System::currentTimeMillis);
    }

    /**
     * @param clock milliseconds, only differences are used (a fake in the tests)
     */
    public SnapcastClientPower(Store store, Session session, Consumer<String> log, java.util.function.LongSupplier clock) {
        this.store = store;
        this.session = session;
        this.log = log;
        this.clock = clock;
    }

    /**
     * @return the persisted state: the user wants the client on
     */
    public boolean isOn() {
        return store.readSwitchedOn();
    }

    /**
     * @return whether the switch is offered at all: the option is on and a valid server is known ({@link SnapcastClientServerChoice})
     */
    public boolean offered() {
        return store.optionEnabled() && SnapcastClient.check(store.host(), "").getOutcome() == SnapcastClient.Outcome.OK;
    }

    /**
     * The switch, the checkbox, the dialog: sets the state and starts or stops the session. Call from the main thread.
     *
     * @return {@link Outcome#STOPPED} for off; {@link Outcome#STARTED} or {@link Outcome#ALREADY_ACTIVE} for on, or the refusal
     */
    public Outcome setOn(boolean on) {
        boolean was = store.readSwitchedOn();
        store.writeSwitchedOn(on); // the intent is kept, whatever the start or the stop comes to; the only write of the state
        if (!on) {
            startRequestedAt = 0;
            // also while the start is still on its way (the runtime shows no session yet): the stop reaches the service after it
            if (was || session.isActive()) {
                try {
                    session.stop();
                } catch (RuntimeException notRunning) {
                    // the state is cleared; a service that can not be reached has nothing to stop
                }
            }
            store.markEndingSeen(); // the user dealt with it
            return Outcome.STOPPED;
        }
        return startSession();
    }

    /**
     * The application starts the client again from the state ({@link SnapcastClientRestore}). Never clears the state.
     *
     * @param exitHold the process was created right after an explicit exit and waits for the user
     */
    public synchronized RestoreResult restore(SnapcastClientRestore.Point point, boolean exitHold) {
        boolean switchedOn = store.readSwitchedOn();
        boolean option = store.optionEnabled();
        SnapcastClientRestore.Facts facts = new SnapcastClientRestore.Facts(point)
                .switchedOn(switchedOn)
                .optionEnabled(option)
                // the server list is looked at only when the answer can matter (the decision stops at the first reason it finds)
                .serverSet(switchedOn && option && serverSet())
                .active(session.isActive())
                .lastEnding(store.lastEnding())
                .endingSeen(store.endingSeen())
                .attempts(attempts)
                .lastAttemptRefused(lastAttemptRefused)
                .exitHold(exitHold);
        SnapcastClientRestore.Decision decision = SnapcastClientRestore.decide(facts);
        if (!decision.restore()) {
            log.accept("Snapcast client restore skipped: " + decision.skip().name());
            return new RestoreResult(decision, null);
        }
        attempts++;
        Outcome outcome = startSession();
        lastAttemptRefused = outcome == Outcome.NOT_ALLOWED;
        log.accept("Snapcast client restore at " + point.name() + ": " + outcome.name());
        return new RestoreResult(decision, outcome);
    }

    /**
     * What to show now.
     */
    public State state() {
        String ending = store.lastEnding();
        boolean unseen = SnapcastClientRestore.isFailure(ending) && !store.endingSeen();
        if (!store.readSwitchedOn()) {
            return new State(Shown.OFF, ending, unseen);
        }
        if (!session.isActive()) {
            return new State(Shown.STOPPED, ending, unseen);
        }
        SnapcastClientRuntime.Phase phase = session.phase();
        Shown shown = phase == SnapcastClientRuntime.Phase.RUNNING ? Shown.PLAYING
                : phase == SnapcastClientRuntime.Phase.RECONNECTING ? Shown.RECONNECTING : Shown.CONNECTING;
        return new State(shown, ending, unseen);
    }

    /**
     * What the power button of the card shows (tasks 12.4 part 2 and 12.16): on while a session starts, runs or reconnects, while a
     * restart on another server waits, and for a moment after a start was handed to the service (the session shows its first phase a
     * moment later); off when the client is switched off, and also when it is switched on but has no session (it gave up, or a start
     * was refused: a tap starts it again).
     */
    public boolean isPoweredOn() {
        if (!store.readSwitchedOn()) {
            return false;
        }
        if (session.isActive() || session.restartPending()) {
            return true;
        }
        long at = startRequestedAt;
        return at != 0 && clock.getAsLong() - at < START_GRACE_MS;
    }

    /**
     * The user chose a server on the card of the client (task 12.10): it is remembered as the one the next start uses. While the client
     * is on and connected to another server, the session ends and starts again on it (one restart; the state stays on; the same server
     * restarts nothing); while it is off, nothing starts. The option is checked again and the address is validated like on every
     * start. No automatic failover: this is the only way the server changes.
     *
     * @param hostText the text of one of the known servers ({@code host} or {@code host:port})
     */
    public Outcome chooseServer(String hostText) {
        SnapcastClient.Request request = SnapcastClient.check(hostText, String.valueOf(store.latencyMs()));
        if (request.getOutcome() != SnapcastClient.Outcome.OK) {
            return Outcome.BAD_SERVER;
        }
        String chosen = hostText.trim();
        String current = store.host();
        store.writeLastServer(chosen);
        switch (SnapcastClientServerSwitch.decide(store.readSwitchedOn(), session.isActive(), session.restartPending(), current, chosen)) {
            case START:
                return startSession();
            case RESTART:
                return restartOn(chosen, request.getLatencyMs());
            case STORE_ONLY:
            default:
                return Outcome.SERVER_SET;
        }
    }

    private Outcome restartOn(String host, int latencyMs) {
        if (!store.optionEnabled()) {
            return refuse(Outcome.OPTION_OFF, SnapcastClientRuntime.Ending.DISABLED);
        }
        try {
            session.restart(host, latencyMs, SnapcastClient.clampWriteAhead(store.writeAheadMs()), store.flacGuard(), failure -> {
                // the start after the wait was refused (Android 12 and newer): class name only, the state stays on
                log.accept("Snapcast client restart was refused: " + failure.getClass().getSimpleName());
                store.recordEnding(SnapcastClientRuntime.Ending.START_NOT_ALLOWED.name());
            });
            startRequestedAt = clock.getAsLong();
            return Outcome.RESTARTING;
        } catch (RuntimeException refused) {
            log.accept("Snapcast client restart was refused: " + refused.getClass().getSimpleName());
            return refuse(Outcome.NOT_ALLOWED, SnapcastClientRuntime.Ending.START_NOT_ALLOWED);
        }
    }

    /**
     * The switch or the dialog showed the last ending: it is not unseen any more.
     */
    public void markEndingSeen() {
        store.markEndingSeen();
    }

    /**
     * The start path, checked again each time: the option, the saved server, a session that runs; then the existing start. A refusal
     * keeps its reason as the last ending (the 'stopped' line shows it); a start the system does not allow is caught here.
     */
    private Outcome startSession() {
        if (!store.optionEnabled()) {
            return refuse(Outcome.OPTION_OFF, SnapcastClientRuntime.Ending.DISABLED);
        }
        String host = store.host();
        if (host == null || host.trim().isEmpty()) {
            return refuse(Outcome.NO_SERVER, SnapcastClientRuntime.Ending.BAD_ADDRESS);
        }
        SnapcastClient.Request request = SnapcastClient.check(host, String.valueOf(store.latencyMs()));
        if (request.getOutcome() != SnapcastClient.Outcome.OK) {
            return refuse(Outcome.BAD_SERVER, SnapcastClientRuntime.Ending.BAD_ADDRESS);
        }
        if (session.isActive() || session.restartPending()) {
            return Outcome.ALREADY_ACTIVE; // a restart that waits starts the one session; a second start would be a double start
        }
        try {
            startRequestedAt = clock.getAsLong();
            session.start(host.trim(), request.getLatencyMs(), SnapcastClient.clampWriteAhead(store.writeAheadMs()), store.flacGuard());
            return Outcome.STARTED;
        } catch (RuntimeException refused) {
            // Android 12 and newer may refuse a foreground service start from the background (IllegalStateException): class name only
            startRequestedAt = 0;
            log.accept("Snapcast client start was refused: " + refused.getClass().getSimpleName());
            return refuse(Outcome.NOT_ALLOWED, SnapcastClientRuntime.Ending.START_NOT_ALLOWED);
        }
    }

    private Outcome refuse(Outcome outcome, SnapcastClientRuntime.Ending ending) {
        store.recordEnding(ending.name());
        return outcome;
    }

    private boolean serverSet() {
        return SnapcastClient.check(store.host(), "").getOutcome() == SnapcastClient.Outcome.OK;
    }

    /**
     * Whether the exception is the platform's refusal of a foreground service start from the background
     * ({@code ForegroundServiceStartNotAllowedException}, API 31): by the class name, also in the chain of causes, because the class
     * does not exist on older platforms or on the JVM.
     */
    public static boolean isBackgroundStartRefusal(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (REFUSAL_CLASS.equals(current.getClass().getSimpleName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
