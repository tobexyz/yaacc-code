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
package de.yaacc.upnp.server.share;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import de.yaacc.R;
import de.yaacc.Yaacc;
import de.yaacc.player.PlayableItem;
import de.yaacc.share.BoundedCall;
import de.yaacc.share.IncomingCache;
import de.yaacc.share.PendingShare;
import de.yaacc.share.PositionalSource;
import de.yaacc.share.ProviderCallGate;
import de.yaacc.share.ReadinessWait;
import de.yaacc.share.ShareLimits;
import de.yaacc.share.ShareProxyUrl;
import de.yaacc.share.ShareRegistrar;
import de.yaacc.share.ShareRegistry;
import de.yaacc.share.SharedContentPolicy;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.upnp.server.YaaccUpnpServerService;
import de.yaacc.util.InterfaceResolutionHelper;
import de.yaacc.util.YaaccLogger;

/**
 * The part of a confirmed file share that talks to the provider and the registry, on a worker
 * thread: it opens the file (or copies it), registers it for the CHOSEN receivers, waits for the
 * local HTTP server and builds the item to play. Every failure closes the source, deletes the copy
 * and removes the registry key before it returns null, so the caller has nothing to clean up.
 * <p>
 * Nothing here logs the URI, the name, the key or the proxy URL.
 */
public final class SharedFileService {

    private static final String TAG = SharedFileService.class.getName();
    private static final long SERVER_READY_MAX_MILLIS = 3_000L;
    private static final long SERVER_READY_POLL_MILLIS = 200L;

    /**
     * Provider calls of confirmed shares. A provider that hangs pins its thread (a Binder call
     * cannot be interrupted), so one provider (package) gets at most ONE call in flight and a
     * provider whose call outlived the wait gets none for a while ({@link ProviderCallGate}); the
     * pool has {@link ShareLimits#PROVIDER_POOL_THREADS} threads, more than the gate lets in.
     */
    private static final BoundedCall PROVIDER_CALLS = new BoundedCall(ShareLimits.PROVIDER_POOL_THREADS,
            "share-provider", ProviderCallGate.createDefault());

    private final Context appContext;
    private final UpnpClient upnpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public SharedFileService(Context appContext, UpnpClient upnpClient) {
        this.appContext = appContext.getApplicationContext();
        this.upnpClient = upnpClient;
    }

    /** A registered share and the item that plays it. */
    public static final class Prepared {
        private final String key;
        private final PlayableItem item;

        public Prepared(String key, PlayableItem item) {
            this.key = key;
            this.item = item;
        }

        /** @return the registry key (a secret: never log it) */
        public String getKey() {
            return key;
        }

        /** @return the item to play, its URI is the proxy URL (a secret too) */
        public PlayableItem getItem() {
            return item;
        }
    }

    /**
     * Worker thread only.
     *
     * @param data        the confirmed pending share
     * @param receiverIds the receivers the user picked; the allowed addresses come from these, not
     *                    from the stored selection
     * @return the registered share and its item (counted as unclaimed until a player takes it), or
     * null after the failure was reported to the user and everything was cleaned up
     */
    public Prepared prepare(PendingShare.Data data, Set<String> receiverIds) {
        Yaacc yaacc = (Yaacc) appContext;
        ShareRegistry registry = yaacc.getShareRegistry();
        PositionalSource source = null;
        String key = null;
        try {
            source = openSource(Uri.parse(data.getUri()));
            if (source == null) {
                return null;
            }
            if (!ReadinessWait.await(upnpClient::isHttpServerRunning, SERVER_READY_MAX_MILLIS,
                    SERVER_READY_POLL_MILLIS)) {
                YaaccLogger.w(TAG, "The local server is not ready, the shared file is dropped");
                toast(R.string.share_open_failed);
                return null;
            }
            String ip = InterfaceResolutionHelper.getIpAddress(appContext);
            if (ShareProxyUrl.build(ip, YaaccUpnpServerService.PORT, YaaccUpnpServerService.PROXY_PATH, "x") == null) {
                // offline: there is no address a receiver could fetch from
                YaaccLogger.w(TAG, "No network address, the shared file is dropped");
                toast(R.string.share_open_failed);
                return null;
            }
            ShareRegistry.Entry entry = new ShareRegistry.Entry(ShareRegistry.Kind.LOCAL_FILE, source,
                    data.getMimeType(), ShareAddressResolver.resolve(upnpClient, appContext, receiverIds),
                    ShareLimits.IDLE_GRACE_MILLIS, ShareLimits.ABSOLUTE_MAX_MILLIS);
            key = ShareRegistrar.register(registry, entry);
            if (key == null) {
                YaaccLogger.w(TAG, "The share registry is full, the shared file is dropped");
                toast(R.string.share_registry_full);
                return null;
            }
            // from here the registry owns the source: remove(key) closes it
            PositionalSource registered = source;
            source = null;
            yaacc.getUnclaimedShares().registered(key, Yaacc.shareClockMillis());
            Uri proxyUri = Uri.parse(ShareProxyUrl.build(ip, YaaccUpnpServerService.PORT,
                    YaaccUpnpServerService.PROXY_PATH, key));
            PlayableItem item = SharedItemFactory.create(data.getMimeType(), data.getDisplayName(), proxyUri,
                    registered.size(), 0L, 0L);
            if (item == null) {
                registry.remove(key);
                key = null;
                toast(R.string.share_open_failed);
                return null;
            }
            String result = key;
            // from here the caller owns the key
            key = null;
            return new Prepared(result, item);
        } catch (RuntimeException e) {
            YaaccLogger.w(TAG, "Could not prepare the shared file (" + e.getClass().getSimpleName() + ")");
            toast(R.string.share_open_failed);
            return null;
        } finally {
            if (key != null) {
                registry.remove(key);
            }
            if (source != null) {
                source.close();
            }
        }
    }

    /** Opens the provider's file (bounded), copying it if it cannot seek. Null after a reported failure. */
    private PositionalSource openSource(Uri uri) {
        final ContentResolver resolver = appContext.getContentResolver();
        final AtomicReference<SharedFileOpener.Reason> reason = new AtomicReference<>(SharedFileOpener.Reason.UNUSABLE);
        final String providerKey = ProviderKeys.of(appContext.getPackageManager(),
                SharedContentPolicy.authorityOf(uri.toString()));
        SharedFileOpener.Opened opened = PROVIDER_CALLS.call(providerKey, () -> {
            try {
                return SharedFileOpener.openDescriptor(resolver, uri);
            } catch (SharedFileOpener.OpenException e) {
                reason.set(e.getReason());
                return null;
            }
        }, ShareLimits.PROVIDER_OPEN_TIMEOUT_MILLIS, SharedFileOpener.Opened::close);
        if (opened == null) {
            // the reason is an enum name, no payload
            YaaccLogger.w(TAG, "The shared file was not opened (" + reason.get() + ")");
            toast(R.string.share_open_failed);
            return null;
        }
        if (opened.getSource() != null) {
            return opened.getSource();
        }
        AssetFileDescriptor afd = opened.getDescriptorToCopy();
        try {
            // closes afd in every case
            return SharedFileOpener.copyAndHold(afd, new File(appContext.getCacheDir(), IncomingCache.DIR_NAME));
        } catch (SharedFileOpener.OpenException e) {
            YaaccLogger.w(TAG, "The shared file was not copied (" + e.getReason() + ")");
            toast(R.string.share_open_failed);
            return null;
        }
    }

    private void toast(int resId) {
        mainHandler.post(() -> Toast.makeText(appContext, resId, Toast.LENGTH_LONG).show());
    }
}
