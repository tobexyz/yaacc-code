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
package de.yaacc.browser;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.PlayableItem;
import de.yaacc.player.Player;
import de.yaacc.share.BoundedCall;
import de.yaacc.share.DisplayName;
import de.yaacc.share.LaunchNonces;
import de.yaacc.share.PendingShare;
import de.yaacc.share.ProviderCallGate;
import de.yaacc.share.ReadinessWait;
import de.yaacc.share.ShareFailureMessage;
import de.yaacc.share.ShareLimits;
import de.yaacc.share.SharedContentPolicy;
import de.yaacc.share.StreamIntake;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.share.OwnProviders;
import de.yaacc.upnp.server.share.ProviderKeys;
import de.yaacc.upnp.server.share.ProviderProbe;
import de.yaacc.upnp.server.share.SharedFileService;
import de.yaacc.util.YaaccLogger;

/**
 * Takes what another app shared to yaacc ({@code ACTION_SEND}): a link (text) or ONE file.
 * <p>
 * <b>Link.</b> The shared text is untrusted. It is only checked by {@link ShareUriPolicy} (length
 * cap, http or https only), parsed with {@link Uri#parse(String)} and handed to
 * {@link UpnpClient#createPlayableItem(Uri)}. It is never used to build an intent, a
 * PendingIntent or a file path, and it is never logged.
 * <p>
 * In the new layout (the host {@link BrowserHost#providesReceiverBar() owns the receiver bar}) a link
 * is not played at once either: it becomes the one pending share (named by a fixed text, never by
 * the link), the host opens the receiver picker, and only {@link #confirm} with the chosen receivers
 * stores them, builds the item and plays. It never falls back to this device. Classic and a
 * missing host keep the old behavior (wait for receivers, else this device).
 * <p>
 * <b>File.</b> An audio, video or image type selects the file path (exactly one of the two paths
 * runs per intent). The activity can be started directly by any app, so nothing the launcher
 * checked is trusted: {@link StreamIntake} repeats the checks (exactly ONE ClipData item whose URI
 * is the only one read, never {@code EXTRA_STREAM}; no {@code data}; the read grant in the intent's
 * own flags; the content policy; no provider of this app). The intent is consumed at once (rotation
 * and recents never replay it). A file is accepted only if the intent carries a valid launch token
 * ({@link LaunchNonces}): only {@code LauncherActivity}, after its checks, mints one, so a file sent
 * straight to an exported browser activity is dropped. On a worker thread, with a timeout, the provider is asked for its
 * type and display name; the MIME type is {@code SharedContentPolicy#chooseMimeType} output only.
 * Then ONE share becomes <i>pending</i> ({@link PendingShare}): it holds only the validated URI
 * text, the cleaned name and the type, no descriptor, no copy, no registry slot. The host is told
 * ({@link BrowserHost#onPendingShare}); only {@link #confirm} with the token (the user picked
 * receivers) opens the file, registers the share for exactly the chosen receivers and plays it.
 * {@link #cancel} or the timeout just drops the pending state. Nothing is ever played without a
 * confirmation, and a file never falls back to this device.
 * <p>
 * {@link #handle}, {@link #confirm}, {@link #cancel} and {@link #shutdown} are for the main
 * thread. Only the application context is kept.
 */
public final class ShareIntentHandler {

    private static final String TAG = ShareIntentHandler.class.getName();
    private static final long WAIT_FOR_RECEIVERS_MS = 3000L;
    private static final long WAIT_FOR_PLAYER_SERVICE_MS = 3000L;

    private static final long DEFAULT_WAIT_FOR_PLAYER_SERVICE_MS = 10_000L;
    private static final long DEFAULT_POLL_MS = 100L;

    /**
     * Provider questions at intake. A provider that hangs pins its thread (a Binder call cannot be
     * interrupted), so one provider (package) gets at most ONE call in flight and a provider whose
     * call outlived the wait gets none for a while ({@link ProviderCallGate}); the pool has
     * {@link ShareLimits#PROVIDER_POOL_THREADS} threads, more than the gate lets in.
     */
    private static final BoundedCall PROBE_CALLS = new BoundedCall(ShareLimits.PROVIDER_POOL_THREADS,
            "share-probe", ProviderCallGate.createDefault());

    /** What opens, registers and builds the item of a confirmed file (replaceable in tests). */
    interface FilePreparer {
        SharedFileService.Prepared prepare(PendingShare.Data data, Set<String> receiverIds);
    }

    private final Context appContext;
    private final UpnpClient upnpClient;
    private final Executor mainThread;
    private final FilePreparer filePreparer;
    private final PendingShare pendingShare;
    private final LaunchNonces launchNonces;
    private final BoundedCall probeCalls;
    private final long playerServiceWaitMillis;
    private final long pollMillis;
    private final long providerTimeoutMillis;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    // files: intake probes and confirmed shares run here, apart from the link executor, so a slow
    // provider never holds up a link
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    // one intake (provider questions) at a time: a loop of intents queues nothing
    private final AtomicBoolean intakeBusy = new AtomicBoolean();
    /** More queued shares than this are ignored: other apps can send intents in a loop. */
    private static final int MAX_PENDING_SHARES = 3;

    private final AtomicInteger pendingShares = new AtomicInteger();
    // Not captured by the queued tasks: shutdown() clears it, so a destroyed activity is not
    // kept alive (or told to switch tabs) by a share that is still waiting for receivers.
    private volatile Runnable playersStartedCallback;
    // cleared by shutdown() for the same reason
    private volatile BrowserHost host;
    private volatile boolean shutDown = false;

    /**
     * @param appContext any context; only the application context is kept
     * @param upnpClient the client used to find receivers and create the playable item
     */
    public ShareIntentHandler(Context appContext, UpnpClient upnpClient) {
        this(appContext, upnpClient, null, null, null);
    }

    /** For tests: the main thread, the file preparation and the pending share can be replaced. */
    ShareIntentHandler(Context appContext, UpnpClient upnpClient, Executor mainThread,
                       FilePreparer filePreparer, PendingShare pendingShare) {
        this(appContext, upnpClient, mainThread, filePreparer, pendingShare, null, null,
                DEFAULT_WAIT_FOR_PLAYER_SERVICE_MS, DEFAULT_POLL_MS, ShareLimits.PROVIDER_CALL_TIMEOUT_MILLIS);
    }

    /**
     * For tests: also the launch tokens, the pool for the provider questions (a test must not share
     * the process-wide gate) and the waits. The defaults are 10 s for the player service (polled
     * every 100 ms) and {@link ShareLimits#PROVIDER_CALL_TIMEOUT_MILLIS} for the provider's type
     * and name.
     */
    ShareIntentHandler(Context appContext, UpnpClient upnpClient, Executor mainThread,
                       FilePreparer filePreparer, PendingShare pendingShare, LaunchNonces launchNonces,
                       BoundedCall probeCalls, long playerServiceWaitMillis, long pollMillis,
                       long providerTimeoutMillis) {
        this.playerServiceWaitMillis = playerServiceWaitMillis;
        this.pollMillis = pollMillis;
        this.providerTimeoutMillis = providerTimeoutMillis;
        this.appContext = appContext.getApplicationContext();
        this.upnpClient = upnpClient;
        this.mainThread = mainThread != null ? mainThread : mainHandler::post;
        this.filePreparer = filePreparer != null ? filePreparer
                : new SharedFileService(this.appContext, upnpClient)::prepare;
        this.pendingShare = pendingShare != null ? pendingShare
                : ((Yaacc) this.appContext).getPendingShare();
        this.launchNonces = launchNonces != null ? launchNonces
                : ((Yaacc) this.appContext).getLaunchNonces();
        this.probeCalls = probeCalls != null ? probeCalls : PROBE_CALLS;
    }

    /**
     * Reads a shared link from the intent and plays it. Does nothing for other actions. The text
     * is removed from the intent so that a recreated activity does not play it twice.
     *
     * @param intent           the intent received by the activity
     * @param onPlayersStarted runs on the main thread after the players have been started, may be
     *                         null
     */
    public void handle(Intent intent, Runnable onPlayersStarted) {
        handle(intent, onPlayersStarted, null);
    }

    /**
     * Same as {@link #handle(Intent, Runnable)}, and for a shared file tells {@code pickHost}
     * ({@link BrowserHost#onPendingShare}) that the user has to pick receivers. The handler holds
     * the host only until {@link #shutdown()}.
     *
     * @param pickHost the activity that shows the picker, may be null (the pending share then
     *                 waits for its timeout)
     */
    public void handle(Intent intent, Runnable onPlayersStarted, BrowserHost pickHost) {
        if (shutDown || intent == null) {
            return;
        }
        if (LauncherForwardPolicy.isStreamType(readType(intent))) {
            host = pickHost;
            playersStartedCallback = onPlayersStarted;
            handleSharedFile(intent);
            return;
        }
        String text = readAndClearSharedText(intent);
        if (text == null) {
            return;
        }
        if (!ShareUriPolicy.isAllowed(text)) {
            YaaccLogger.w(TAG, "Ignoring shared text: not an http(s) link or longer than "
                    + ShareUriPolicy.MAX_TEXT_LENGTH + " characters");
            return;
        }
        if (pickHost != null && pickHost.providesReceiverBar()) {
            // new layout: the receivers are picked per action; a link is never played by itself
            // and never falls back to this device
            offerLinkForPick(text, pickHost);
            return;
        }
        if (pendingShares.get() >= MAX_PENDING_SHARES) {
            YaaccLogger.w(TAG, "Ignoring shared link: too many shares are waiting");
            return;
        }
        final Uri uri = Uri.parse(text);
        playersStartedCallback = onPlayersStarted;

        long delayedExecution = 0;
        if (upnpClient.getReceiverDevicesReadyCount() == 0) {
            //schedule execution to be sure the receiver devices come up
            delayedExecution = WAIT_FOR_RECEIVERS_MS;
        }
        if (!upnpClient.isPlayerServiceInitialized()) {
            //schedule execution to be sure the player devices come up
            upnpClient.startService();
            delayedExecution += WAIT_FOR_PLAYER_SERVICE_MS;
        }

        pendingShares.incrementAndGet();
        Runnable execution = () -> {
            try {
                prepareAndPlay(uri);
            } finally {
                pendingShares.decrementAndGet();
            }
        };
        try {
            if (delayedExecution > 0) {
                executor.schedule(execution, delayedExecution, TimeUnit.MILLISECONDS);
                Toast.makeText(appContext, String.format("start in %s seconds", delayedExecution / 1000), Toast.LENGTH_LONG).show();
            } else {
                executor.execute(execution);
            }
        } catch (RejectedExecutionException e) {
            pendingShares.decrementAndGet();
            YaaccLogger.w(TAG, "Share handler already shut down, ignoring shared link");
        }
    }

    /**
     * Releases the worker thread. Call from {@code onDestroy}. A share that is already
     * waiting for receivers still plays (a rotation must not drop it): a graceful
     * {@code shutdown()} lets scheduled tasks run and ends the thread afterwards. The handler
     * ignores later calls to {@link #handle(Intent, Runnable)}.
     */
    public void shutdown() {
        shutDown = true;
        playersStartedCallback = null;
        host = null;
        executor.shutdown();
        // a confirmed file that is already being prepared still plays, like a waiting link
        fileExecutor.shutdown();
    }

    // ---- shared link in the new layout

    /**
     * Main thread. The link (already checked by {@link ShareUriPolicy}) becomes the one pending
     * share, with a fixed name: the link text itself is never shown or logged. A second share while
     * one is pending is dropped, never replacing it.
     */
    private void offerLinkForPick(String text, BrowserHost pickHost) {
        if (pendingShare.isPending()) {
            YaaccLogger.w(TAG, "Ignoring shared link: another share is waiting for its receivers");
            return;
        }
        host = pickHost;
        String token = pendingShare.offer(text, "", "");
        if (token == null) {
            YaaccLogger.w(TAG, "Ignoring shared link: another share is waiting for its receivers");
            return;
        }
        announce(token, appContext.getString(R.string.share_unnamed_link));
    }

    /** A pending share is a link when its text passes the link policy; files are always {@code content:} URIs. */
    private static boolean isLink(PendingShare.Data data) {
        return ShareUriPolicy.isAllowed(data.getUri());
    }

    /**
     * Confirmed link, main thread. The chosen receivers are stored first on the worker, because the
     * addresses a proxied link is served to are read from the selection when the item is built.
     */
    private void confirmLink(PendingShare.Data data, Set<String> chosen, Runnable afterStarted) {
        if (!upnpClient.isPlayerServiceInitialized()) {
            upnpClient.startService();
        }
        try {
            executor.execute(() -> prepareAndPlayLink(data.getUri(), chosen, afterStarted));
        } catch (RejectedExecutionException e) {
            // nothing was created yet
            YaaccLogger.w(TAG, "Share handler already shut down, dropping the shared link");
        }
    }

    /** Worker thread. Nothing here may throw or log the shared link. */
    private void prepareAndPlayLink(String link, Set<String> chosen, Runnable afterStarted) {
        PlayableItem item = null;
        try {
            upnpClient.setReceiverDeviceIds(chosen);
            if (ReadinessWait.await(upnpClient::isPlayerServiceInitialized, playerServiceWaitMillis, pollMillis)) {
                item = upnpClient.createPlayableItem(Uri.parse(link));
            } else {
                YaaccLogger.w(TAG, "The player service did not come up, dropping the shared link");
            }
        } catch (Exception e) {
            // not e.getMessage() or the exception itself: they may contain the shared link
            YaaccLogger.w(TAG, "Could not prepare shared content (" + e.getClass().getSimpleName() + ")");
        }
        if (item == null) {
            toast(R.string.share_link_failed);
        }
        final PlayableItem ready = item;
        // always: the selection was changed, so the caller must get the chance to clear or restore it
        mainThread.execute(() -> playLink(ready, afterStarted));
    }

    /** Main thread: plays the item of a confirmed link, if there is one, then runs the hook. */
    private void playLink(PlayableItem item, Runnable afterStarted) {
        boolean started = false;
        try {
            if (item != null) {
                List<PlayableItem> items = new ArrayList<>();
                items.add(item);
                List<Player> players = upnpClient.initializePlayersWithPlayableItems(items);
                if (players.isEmpty()) {
                    YaaccLogger.w(TAG, "No player could be created for the shared link");
                    toast(R.string.share_link_failed);
                } else {
                    for (Player player : players) {
                        player.play();
                    }
                    started = true;
                    Runnable callback = playersStartedCallback;
                    if (callback != null) {
                        callback.run();
                    }
                }
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not start shared content (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (item != null && !started) {
                // no player took the share: give its registry slot back at once
                upnpClient.discardShareOf(item);
            }
            runHook(afterStarted);
        }
    }

    private static void runHook(Runnable afterStarted) {
        if (afterStarted == null) {
            return;
        }
        try {
            afterStarted.run();
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "The hook after the shared item failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---- shared file

    private static String readType(Intent intent) {
        try {
            return intent.getType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Main thread, cheap: reads the fields, consumes the intent, repeats the intake checks. The
     * slow part (the provider's answers) goes to a worker.
     */
    private void handleSharedFile(Intent intent) {
        String action;
        String type;
        String uriText = null;
        String nonce;
        int clipItemCount = 0;
        boolean hasData;
        int flags;
        try {
            action = intent.getAction();
            if (!Intent.ACTION_SEND.equals(action)) {
                return;
            }
            nonce = intent.getStringExtra(LaunchNonces.EXTRA_NAME);
            type = intent.getType();
            hasData = intent.getData() != null;
            flags = intent.getFlags();
            ClipData clipData = intent.getClipData();
            clipItemCount = clipData == null ? 0 : clipData.getItemCount();
            // item 0 only, and never EXTRA_STREAM: the system checks the grant for ClipData
            ClipData.Item first = clipItemCount > 0 ? clipData.getItemAt(0) : null;
            Uri uri = first == null ? null : first.getUri();
            uriText = uri == null ? null : uri.toString();
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not read the shared file intent (" + e.getClass().getSimpleName() + ")");
            consume(intent);
            return;
        }
        // whatever happens next, this intent is spent: a recreated activity must not replay it
        consume(intent);

        // Only the launcher, after its checks (including the Android 15 sender check), mints a
        // token. These activities are exported, so an app that starts one directly has none: its
        // file is dropped here, silently.
        if (!launchNonces.consume(nonce)) {
            YaaccLogger.w(TAG, "Ignoring shared file: it did not come through the launcher");
            return;
        }
        final PackageManager packageManager = appContext.getPackageManager();
        final String ownPackage = appContext.getPackageName();
        String validated = StreamIntake.validate(action, clipItemCount, uriText, hasData, flags,
                OwnProviders.fileProviderAuthority(ownPackage),
                authority -> OwnProviders.isOwnAuthority(packageManager, ownPackage, authority));
        if (validated == null) {
            YaaccLogger.w(TAG, "Ignoring shared file: it did not pass the intake checks");
            return;
        }
        if (pendingShare.isPending()) {
            YaaccLogger.w(TAG, "Ignoring shared file: another one is waiting for its receivers");
            return;
        }
        if (!intakeBusy.compareAndSet(false, true)) {
            YaaccLogger.w(TAG, "Ignoring shared file: another one is being checked");
            return;
        }
        final String uriToProbe = validated;
        final String declaredType = type;
        try {
            fileExecutor.execute(() -> {
                try {
                    probeAndOffer(uriToProbe, declaredType);
                } finally {
                    intakeBusy.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            intakeBusy.set(false);
            YaaccLogger.w(TAG, "Share handler already shut down, ignoring shared file");
        }
    }

    /** Removes what makes the intent a share, so that nothing can play it a second time. */
    private static void consume(Intent intent) {
        try {
            intent.setClipData(null);
            intent.removeExtra(Intent.EXTRA_STREAM);
            intent.removeExtra(LaunchNonces.EXTRA_NAME);
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not clear the shared file intent (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Worker thread. Asks the provider (bounded) for the type and the name, then makes the share
     * pending and tells the host. Nothing is opened.
     */
    private void probeAndOffer(String uriText, String declaredType) {
        try {
            final ContentResolver resolver = appContext.getContentResolver();
            final Uri uri = Uri.parse(uriText);
            // one call per provider (package) at a time: a provider that hangs cannot take the pool
            final String providerKey = ProviderKeys.of(appContext.getPackageManager(),
                    SharedContentPolicy.authorityOf(uriText));
            BoundedCall.Result<ProviderProbe.Info> outcome = probeCalls.callDetailed(providerKey,
                    () -> ProviderProbe.query(resolver, uri), providerTimeoutMillis, null);
            ProviderProbe.Info info = outcome.value();
            if (info == null) {
                YaaccLogger.w(TAG, "Ignoring shared file: the provider did not answer in time or is still busy");
                if (ShareFailureMessage.of(outcome.isRefusedBusy(), false) == ShareFailureMessage.Kind.BUSY) {
                    // payload-free and fixed: the user learns the share was turned away, not why a provider is slow
                    toast(R.string.share_busy);
                }
                return;
            }
            String mimeType = SharedContentPolicy.chooseMimeType(info.getProviderType(), declaredType);
            if (mimeType == null) {
                YaaccLogger.w(TAG, "Ignoring shared file: it is not audio, video or an image");
                return;
            }
            String name = DisplayName.forShare(info.getDisplayName(), appContext.getString(R.string.share_unnamed_file));
            String token = pendingShare.offer(uriText, name, mimeType);
            if (token == null) {
                YaaccLogger.w(TAG, "Ignoring shared file: another one is waiting for its receivers");
                return;
            }
            mainThread.execute(() -> announce(token, name));
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not check the shared file (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Main thread: tells the host. Without a host nobody could ever confirm, so the share is dropped at once. */
    private void announce(String token, String name) {
        BrowserHost pickHost = host;
        if (pickHost == null) {
            pendingShare.cancel(token);
            YaaccLogger.w(TAG, "Dropped a shared file: there is no screen to pick receivers on");
            return;
        }
        try {
            YaaccLogger.i(TAG, "A shared file is waiting for the receiver pick");
            pickHost.onPendingShare(token, name);
        } catch (RuntimeException e) {
            pendingShare.cancel(token);
            YaaccLogger.w(TAG, "Dropped a shared file: the picker failed (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * The user picked receivers for the pending share. Takes the share (once: a wrong, stale or
     * second token does nothing), then on a worker opens the file, registers it for exactly these
     * receivers, waits for the local server and plays it. Every failure closes what was opened,
     * deletes a copy and removes the registry key, and tells the user.
     *
     * @param token           the token {@link BrowserHost#onPendingShare} delivered
     * @param chosenReceiverIds the ids of the receivers the user ticked; empty means nothing is played
     *                        and the share is released (a file never plays on this device by default)
     * @param afterStarted    runs on the main thread, once, after the receivers were set and the
     *                        playback was attempted (started or not), so the caller can clear or
     *                        restore the receiver selection; not called if nothing was changed (wrong
     *                        token, empty selection, file not opened); for a link it always runs once
     *                        the receivers were stored; may be null
     */
    public void confirm(String token, Set<String> chosenReceiverIds, Runnable afterStarted) {
        PendingShare.Data data = pendingShare.take(token);
        if (data == null) {
            return;
        }
        final Set<String> chosen = chosenReceiverIds == null ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new HashSet<>(chosenReceiverIds));
        if (chosen.isEmpty()) {
            YaaccLogger.w(TAG, "Shared file released: no receiver was chosen");
            return;
        }
        if (isLink(data)) {
            confirmLink(data, chosen, afterStarted);
            return;
        }
        if (!upnpClient.isPlayerServiceInitialized()) {
            upnpClient.startService();
        }
        try {
            fileExecutor.execute(() -> prepareAndPlayFile(data, chosen, afterStarted));
        } catch (RejectedExecutionException e) {
            // nothing was opened yet
            YaaccLogger.w(TAG, "Share handler already shut down, dropping the shared file");
        }
    }

    /** Same without a hook. */
    public void confirm(String token, Set<String> chosenReceiverIds) {
        confirm(token, chosenReceiverIds, null);
    }

    /** The user dismissed the picker: releases the pending share (nothing was opened). A wrong token does nothing. */
    public void cancel(String token) {
        pendingShare.cancel(token);
    }

    /** Worker thread. */
    private void prepareAndPlayFile(PendingShare.Data data, Set<String> chosen, Runnable afterStarted) {
        SharedFileService.Prepared prepared = null;
        // true once the share is cleaned up or handed to the main thread: the catch must not touch it again
        boolean settled = false;
        try {
            prepared = filePreparer.prepare(data, chosen);
            if (prepared == null) {
                return;
            }
            // the player service is awaited here, not busy-waited on the main thread
            if (!ReadinessWait.await(upnpClient::isPlayerServiceInitialized, playerServiceWaitMillis, pollMillis)) {
                YaaccLogger.w(TAG, "The player service did not come up, dropping the shared file");
                settled = true;
                upnpClient.discardShareOf(prepared.getItem());
                toast(R.string.share_open_failed);
                return;
            }
            final SharedFileService.Prepared ready = prepared;
            settled = true;
            mainThread.execute(() -> playFile(ready.getItem(), chosen, afterStarted));
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not prepare the shared file (" + e.getClass().getSimpleName() + ")");
            if (prepared != null && !settled) {
                upnpClient.discardShareOf(prepared.getItem());
            }
        }
    }

    /** Main thread. */
    private void playFile(PlayableItem item, Set<String> chosen, Runnable afterStarted) {
        boolean selectionChanged = false;
        boolean started = false;
        try {
            upnpClient.setReceiverDeviceIds(chosen);
            selectionChanged = true;
            List<PlayableItem> items = new ArrayList<>();
            items.add(item);
            List<Player> players = upnpClient.initializePlayersWithPlayableItems(items);
            if (players.isEmpty()) {
                YaaccLogger.w(TAG, "No player could be created for the shared file");
                toast(R.string.share_open_failed);
                return;
            }
            for (Player player : players) {
                player.play();
            }
            started = true;
            Runnable callback = playersStartedCallback;
            if (callback != null) {
                callback.run();
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not start the shared file (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (!started) {
                // no player took the share: give its descriptor and slot back at once
                upnpClient.discardShareOf(item);
            }
            if (selectionChanged) {
                runHook(afterStarted);
            }
        }
    }

    private void toast(int resId) {
        mainThread.execute(() -> {
            try {
                Toast.makeText(appContext, resId, Toast.LENGTH_LONG).show();
            } catch (RuntimeException e) {
                YaaccLogger.w(TAG, "Could not show a message (" + e.getClass().getSimpleName() + ")");
            }
        });
    }

    /** Worker thread. Nothing here may throw or log the shared link. */
    private void prepareAndPlay(Uri uri) {
        try {
            if (upnpClient.getReceiverDevicesReadyCount() == 0) {
                mainHandler.post(() -> Toast.makeText(appContext, "no receiver found using local device", Toast.LENGTH_LONG).show());
                upnpClient.setReceiverDeviceIds(Set.of(UpnpClient.LOCAL_UID));
            }
            PlayableItem item = upnpClient.createPlayableItem(uri);
            if (item == null) {
                // refused (for example a link the media proxy must not serve): play nothing
                YaaccLogger.w(TAG, "Shared link not played: it was refused");
                return;
            }
            mainHandler.post(() -> play(item));
        } catch (Exception e) {
            // not e.getMessage() or the exception itself: they may contain the shared link
            YaaccLogger.w(TAG, "Could not prepare shared content (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Main thread. */
    private void play(PlayableItem item) {
        try {
            List<PlayableItem> items = new ArrayList<>();
            items.add(item);
            List<Player> players = upnpClient.initializePlayersWithPlayableItems(items);
            if (players.isEmpty()) {
                // no player took the share: do not keep its registry slot until the claim window ends
                upnpClient.discardShareOf(item);
            }
            for (Player player : players) {
                player.play();
            }
            Runnable callback = playersStartedCallback;
            if (callback != null) {
                callback.run();
            }
        } catch (RuntimeException e) {
            upnpClient.discardShareOf(item);
            YaaccLogger.w(TAG, "Could not start shared content (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Returns the shared text and removes it from the intent, or null. The text comes from
     * {@code EXTRA_TEXT}, else from the text of the last {@code ClipData} item that has one. A
     * clip item's URI is never used.
     */
    private static String readAndClearSharedText(Intent intent) {
        try {
            if (!Intent.ACTION_SEND.equals(intent.getAction())) {
                return null;
            }
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            ClipData clipData = intent.getClipData();
            if (text == null && clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    ClipData.Item item = clipData.getItemAt(i);
                    CharSequence itemText = item == null ? null : item.getText();
                    // check the length first so that a huge text is not copied into a String
                    if (itemText != null && itemText.length() <= ShareUriPolicy.MAX_TEXT_LENGTH) {
                        text = itemText.toString();
                    }
                }
            }
            //clear intent
            intent.removeExtra(Intent.EXTRA_TEXT);
            if (clipData != null) {
                intent.setClipData(null);
            }
            return text;
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not read the shared intent (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }
}
