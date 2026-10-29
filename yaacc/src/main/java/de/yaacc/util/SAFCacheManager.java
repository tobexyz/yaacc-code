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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 */
package de.yaacc.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Looper;

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import de.yaacc.R;
import de.yaacc.upnp.server.contentdirectory.SafDirectoryLister;

/**
 * LRU cache for SAF (Storage Access Framework) file metadata with background preloading.
 * Caches duration, MIME type, and encoded IDs for fast browsing.
 * <p>
 * Persistence: the cache lives in its own preferences file ({@value SafCacheMigration#STORE_NAME}), not in the
 * default preferences (every write rewrites the whole XML file, and the cache was large). Entries of the old place are
 * moved once ({@link SafCacheMigration}). Reads are served from memory. Changes are collected in memory
 * ({@link SafCacheWriteBuffer}) and committed in one go by the cache's own I/O thread when
 * {@link SafCacheFlushPolicy} says so (every {@value #FLUSH_MAX_CHANGES} changes or {@value #FLUSH_MAX_AGE_MILLIS} ms)
 * and at the end of a preload, with {@code commit()} - never on the main thread. The stored state is loaded on that
 * thread too; the constructor does no I/O. A caller on the main thread that comes before the load ended gets
 * metadata computed without the stored state and nothing is persisted for it; other threads wait for the load.
 * <p>
 * The short id maps are not stored on their own: they are rebuilt from the stored metadata entries (the short id is
 * part of the serialized {@link SAFMetadata}), the counter is stored under {@value SafCacheMigration#ID_COUNTER_KEY}.
 * A short id created in the last {@value #FLUSH_MAX_AGE_MILLIS} ms before the process is killed is lost;
 * {@link #flushIfCreated(long)} writes the pending changes when the app exits.
 */
public class SAFCacheManager {
    private static final int MAX_CACHE_SIZE = 1000;
    private static final String CACHE_PREFIX = SafCacheMigration.CACHE_PREFIX;
    private static final String ID_COUNTER_KEY = SafCacheMigration.ID_COUNTER_KEY;
    static final int FLUSH_MAX_CHANGES = 250;
    static final long FLUSH_MAX_AGE_MILLIS = 3000;
    /** How long a thread other than the main thread waits for the stored state to be loaded. */
    private static final long LOAD_WAIT_MILLIS = 30000;

    private static final Map<String, String> MIME_TYPE_BY_EXT = new ConcurrentHashMap<>();
    
    static {
        // Audio formats
        MIME_TYPE_BY_EXT.put("mp3", "audio/mpeg");
        MIME_TYPE_BY_EXT.put("m4a", "audio/mp4");
        MIME_TYPE_BY_EXT.put("aac", "audio/aac");
        MIME_TYPE_BY_EXT.put("flac", "audio/flac");
        MIME_TYPE_BY_EXT.put("ogg", "audio/ogg");
        MIME_TYPE_BY_EXT.put("opus", "audio/opus");
        MIME_TYPE_BY_EXT.put("wav", "audio/wav");
        MIME_TYPE_BY_EXT.put("wma", "audio/x-ms-wma");
        
        // Video formats
        MIME_TYPE_BY_EXT.put("mp4", "video/mp4");
        MIME_TYPE_BY_EXT.put("mkv", "video/x-matroska");
        MIME_TYPE_BY_EXT.put("avi", "video/x-msvideo");
        MIME_TYPE_BY_EXT.put("mov", "video/quicktime");
        MIME_TYPE_BY_EXT.put("wmv", "video/x-ms-wmv");
        MIME_TYPE_BY_EXT.put("webm", "video/webm");
        
        // Image formats
        MIME_TYPE_BY_EXT.put("jpg", "image/jpeg");
        MIME_TYPE_BY_EXT.put("jpeg", "image/jpeg");
        MIME_TYPE_BY_EXT.put("png", "image/png");
        MIME_TYPE_BY_EXT.put("gif", "image/gif");
        MIME_TYPE_BY_EXT.put("webp", "image/webp");
    }
    
    private final Context context;
    /** The default preferences, where the cache used to be: read once for the migration. */
    private final SharedPreferences legacyPreferences;
    /** The cache's own preferences file. Written by the I/O thread only. */
    private final SharedPreferences store;
    private final ExecutorService preloadExecutor;
    private final ScheduledThreadPoolExecutor ioExecutor;
    private final BooleanSupplier onMainThread;
    private final SafCacheLoadGate loadGate = new SafCacheLoadGate();
    private final SafShortIds ids = new SafShortIds();

    /** Guards {@link #lruCache}, {@link #buffer}, {@link #flushPolicy} and the two flags below. */
    private final Object lock = new Object();
    private final LRUCache lruCache;
    private final SafCacheWriteBuffer buffer = new SafCacheWriteBuffer();
    private final SafCacheFlushPolicy flushPolicy;
    private boolean immediateFlushQueued;
    private boolean timedFlushQueued;

    private volatile int totalFilesIndexed = 0;
    private volatile boolean isPreloading = false;
    private long lastProgressNotifyTime = 0;
    private static final long PROGRESS_NOTIFY_INTERVAL = 500; // Notify every 500ms
    private String lastNotifiedFolder = "";
    private long lastFileIndexedTime = 0;
    private static final long STALL_TIMEOUT = 30000; // 30 seconds without file progress = stall
    
    private static SAFCacheManager instance;
    
    public static synchronized SAFCacheManager getInstance(Context context) {
        if (instance == null) {
            Context appContext = context.getApplicationContext();
            instance = new SAFCacheManager(appContext, PreferenceManager.getDefaultSharedPreferences(appContext),
                    appContext.getSharedPreferences(SafCacheMigration.STORE_NAME, Context.MODE_PRIVATE),
                    () -> System.nanoTime() / 1_000_000L, () -> Looper.getMainLooper() == Looper.myLooper());
        }
        return instance;
    }

    /**
     * Writes the changes that wait in memory, if the manager exists; for the exit of the app, where the timer of the
     * I/O thread would not run any more. Blocks the caller for at most {@code waitMillis}.
     */
    public static void flushIfCreated(long waitMillis) {
        SAFCacheManager manager;
        synchronized (SAFCacheManager.class) {
            manager = instance;
        }
        if (manager != null) {
            manager.flushAndWait(waitMillis);
        }
    }

    /**
     * The constructor does no I/O: the stored state is loaded by the I/O thread.
     */
    SAFCacheManager(Context context, SharedPreferences legacyPreferences, SharedPreferences store,
                    LongSupplier clockMillis, BooleanSupplier onMainThread) {
        this.context = context;
        this.legacyPreferences = legacyPreferences;
        this.store = store;
        this.onMainThread = onMainThread;
        this.flushPolicy = new SafCacheFlushPolicy(FLUSH_MAX_CHANGES, FLUSH_MAX_AGE_MILLIS, clockMillis);
        this.preloadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SAFPreloader");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.ioExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "SAFCacheIO");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.lruCache = new LRUCache(MAX_CACHE_SIZE);
        submitIo(this::load);
    }

    /** True once the stored state is in memory. */
    boolean isLoaded() {
        return loadGate.isOpen();
    }

    /** For tests: everything given to the I/O thread so far has run. */
    boolean awaitIdle(long timeoutMillis) {
        try {
            ioExecutor.submit(() -> { }).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** For tests: the preload and background duration tasks given so far have run. */
    boolean awaitBackgroundIdle(long timeoutMillis) {
        try {
            preloadExecutor.submit(() -> { }).get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Waits for the load unless on the main thread; false = work without the stored state. */
    private boolean awaitLoaded() {
        return loadGate.await(onMainThread.getAsBoolean(), LOAD_WAIT_MILLIS);
    }

    /**
     * Get complete metadata for a SAF file (duration, MIME type, encoded ID).
     * Returns cached data if available, otherwise builds it from the given values - without a
     * further query to the document provider. The values come from the directory listing.
     *
     * @param uri              the file's document uri (the cache key)
     * @param name             its display name, may be null
     * @param providerMimeType the mime type the provider reports for it, may be null
     * @param fileSize         its size in bytes as the provider reports it
     */
    public SAFMetadata getMetadata(Uri uri, String name, String providerMimeType, long fileSize) {
        if (uri == null) return null;
        if (!awaitLoaded()) {
            // main thread (or a load that does not end): nothing stored is known, so nothing is persisted either
            YaaccLogger.d(getClass().getName(), "SAF cache not loaded yet, metadata of " + name + " is not cached");
            return new SAFMetadata("", extractMimeType(name, providerMimeType), ids.getOrCreateTransient(uri.toString()), fileSize);
        }
        synchronized (lock) {
            return getMetadataInternal(uri, name, providerMimeType, fileSize);
        }
    }

    /** Called with {@link #lock} held. */
    private SAFMetadata getMetadataInternal(Uri uri, String name, String providerMimeType, long fileSize) {
        if (uri == null) return null;

        long startTime = System.currentTimeMillis();
        String uriString = uri.toString();
        String key = CACHE_PREFIX + uriString;

        // Check memory cache
        String cached = lruCache.get(key);
        if (cached != null) {
            SAFMetadata metadata = SafCacheEntries.parseMetadata(cached);
            if (metadata == null && SafCacheEntries.isBareDuration(cached)) {
                return upgradeBareDurationLocked(key, uriString, cached, name, providerMimeType, fileSize);
            }
            if (metadata != null) {
                // Migrate old entries without shortId
                if (metadata.shortId == null) {
                    String shortId = createShortIdLocked(uriString);
                    metadata = new SAFMetadata(metadata.duration, metadata.mimeType, shortId, metadata.fileSize);
                    putEntryLocked(key, metadata.serialize());
                }
                long elapsed = System.currentTimeMillis() - startTime;
                YaaccLogger.v(getClass().getName(), "CACHE_HIT_MEMORY: " + name + " (" + elapsed + "ms)");
                return metadata;
            }
        }

        // Check the stored state (served from the memory of the preferences, plus what waits to be written)
        cached = readStoredLocked(key);
        if (cached != null) {
            SAFMetadata metadata = SafCacheEntries.parseMetadata(cached);
            if (metadata == null && SafCacheEntries.isBareDuration(cached)) {
                return upgradeBareDurationLocked(key, uriString, cached, name, providerMimeType, fileSize);
            }
            if (metadata != null) {
                // Migrate old entries without shortId
                if (metadata.shortId == null) {
                    String shortId = createShortIdLocked(uriString);
                    metadata = new SAFMetadata(metadata.duration, metadata.mimeType, shortId, metadata.fileSize);
                    putEntryLocked(key, metadata.serialize());
                } else {
                    lruCache.put(key, cached);
                }
                long elapsed = System.currentTimeMillis() - startTime;
                YaaccLogger.v(getClass().getName(), "CACHE_HIT_DISK: " + name + " (" + elapsed + "ms)");
                return metadata;
            }
        }

        // Extract and cache
        YaaccLogger.v(getClass().getName(), "CACHE_MISS: " + name + " - extracting...");
        SAFMetadata metadata = extractMetadata(uri, name, providerMimeType, fileSize);
        if (metadata != null) {
            putEntryLocked(key, metadata.serialize());
        }
        long elapsed = System.currentTimeMillis() - startTime;
        YaaccLogger.v(getClass().getName(), "EXTRACTION_COMPLETE: " + name + " (" + elapsed + "ms)");
        return metadata;
    }

    /**
     * An entry that holds a bare duration (written by the former preload) becomes a serialized {@link SAFMetadata}
     * with that duration: no extraction, and the short id is the one the uri has (a new one only if it has none).
     * Called with {@link #lock} held.
     */
    private SAFMetadata upgradeBareDurationLocked(String key, String uriString, String duration, String name,
                                                  String providerMimeType, long fileSize) {
        SAFMetadata upgraded = new SAFMetadata(duration, extractMimeType(name, providerMimeType),
                createShortIdLocked(uriString), fileSize);
        putEntryLocked(key, upgraded.serialize());
        YaaccLogger.d(getClass().getName(), "CACHE_UPGRADED: " + name + " - bare duration " + duration + " -> metadata, shortId=" + upgraded.shortId);
        return upgraded;
    }

    /**
     * Stores the duration in the metadata entry of a file: the entry's mime type, size and short id stay. Without a
     * readable entry (none yet, a bare duration of the former preload, corrupt) the entry is built from the given
     * values when {@code createIfMissing}, with the short id the uri has or a new one. Called with {@link #lock} held.
     *
     * @return true if an entry was written
     */
    private boolean storeDurationLocked(Uri uri, String duration, boolean createIfMissing, String name,
                                        String providerMimeType, long fileSize) {
        String uriString = uri.toString();
        String key = CACHE_PREFIX + uriString;
        String stored = lruCache.get(key);
        if (stored == null) {
            stored = readStoredLocked(key);
        }
        SAFMetadata existing = SafCacheEntries.parseMetadata(stored);
        SAFMetadata updated;
        if (existing != null) {
            String shortId = existing.shortId != null ? existing.shortId : createShortIdLocked(uriString);
            updated = new SAFMetadata(duration, existing.mimeType, shortId, existing.fileSize);
        } else if (createIfMissing) {
            updated = new SAFMetadata(duration, extractMimeType(name, providerMimeType), createShortIdLocked(uriString), fileSize);
        } else {
            return false;
        }
        putEntryLocked(key, updated.serialize());
        return true;
    }

    /** True if the file has no duration stored yet. Called with {@link #lock} held. */
    private boolean needsDurationLocked(String key) {
        String stored = lruCache.get(key);
        if (stored == null) {
            stored = readStoredLocked(key);
        }
        return stored == null || SafCacheEntries.durationOf(stored).isEmpty();
    }

    /** The value of a key as the stored state will have it: waiting writes first. Called with {@link #lock} held. */
    private String readStoredLocked(String key) {
        return buffer.read(key, k -> store.getString(k, null));
    }

    /** Remembers an entry in memory and for the next write. Called with {@link #lock} held. */
    private void putEntryLocked(String key, String value) {
        lruCache.put(key, value);
        buffer.put(key, value);
        flushPolicy.recordChange();
        scheduleFlushLocked();
    }

    /** Called with {@link #lock} held. */
    private String createShortIdLocked(String uri) {
        long before = ids.counter();
        String id = ids.getOrCreate(uri);
        if (ids.counter() != before) {
            buffer.putCounter(ids.counter());
            flushPolicy.recordChange();
            scheduleFlushLocked();
            YaaccLogger.v(getClass().getName(), "Created shortId mapping: " + id + " -> " + uri);
        }
        return id;
    }

    /**
     * Extract all metadata for a file (duration, MIME type, short ID).
     * NEVER extracts duration on current thread - always queues for background.
     */
    private SAFMetadata extractMetadata(Uri uri, String name, String providerMimeType, long fileSize) {
        String mimeType = extractMimeType(name, providerMimeType);
        String shortId = createShortIdLocked(uri.toString());

        // Queue duration extraction for background (don't block HTTP thread!)
        if (mimeType != null && (mimeType.startsWith("audio/") || mimeType.startsWith("video/"))) {
            queueForDurationExtraction(uri, mimeType);
        }

        // Return immediately with empty duration
        return new SAFMetadata("", mimeType, shortId, fileSize);
    }
    
    /**
     * Queue file for background duration extraction.
     */
    private void queueForDurationExtraction(Uri uri, String mimeType) {
        preloadExecutor.execute(() -> {
            try {
                String key = CACHE_PREFIX + uri.toString();
                synchronized (lock) {
                    if (!needsDurationLocked(key)) {
                        // a preload (or an earlier task) got it in the meantime: do not extract it a second time
                        return;
                    }
                }
                String duration = durationExtractor.extractInBackground(uri, mimeType);

                if (duration != null && !duration.isEmpty()) {
                    // Update the cached metadata (without duration), if there is one
                    synchronized (lock) {
                        if (storeDurationLocked(uri, duration, false, null, mimeType, 0)) {
                            YaaccLogger.d(getClass().getName(), "DURATION_EXTRACTED: " + uri.getLastPathSegment() + " -> " + duration);
                        }
                    }
                }
            } catch (Exception e) {
                YaaccLogger.w(getClass().getName(), "DURATION_EXTRACT_FAILED: " + uri.getLastPathSegment() + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });
    }

    private String extractInBackground(Uri uri, String mimeType) {
        String duration;
        if (mimeType.startsWith("video/")) {
            // Try quick extraction first
            duration = extractVideoDurationQuick(uri);
            if (duration == null || duration.isEmpty()) {
                // Full extraction as fallback
                duration = extractAudioDuration(uri);
            }
        } else {
            // Audio: full extraction
            duration = extractAudioDuration(uri);
        }
        return duration;
    }

    /**
     * Get or create a short ID for a URI. On the main thread before the stored state is loaded the id is a
     * transient one that only this process knows ("t1", ...), see {@link SafShortIds}.
     */
    public String getOrCreateShortId(String uri) {
        String existing = ids.existing(uri);
        if (existing != null) {
            return existing;
        }
        if (!awaitLoaded()) {
            return ids.getOrCreateTransient(uri);
        }
        synchronized (lock) {
            return createShortIdLocked(uri);
        }
    }
    
    /**
     * Get URI for a short ID. Before the stored state is loaded, threads other than the main thread wait for it
     * (a stored id would not be known yet), the main thread gets null.
     */
    public String getUriForShortId(String shortId) {
        String uri = ids.uriFor(shortId);
        if (uri == null && awaitLoaded()) {
            uri = ids.uriFor(shortId);
        }
        return uri;
    }
    
    private String extractMimeType(String name, String providerMimeType) {
        // Try extension-based lookup first
        if (name != null) {
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex > 0) {
                String ext = name.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
                String mimeType = MIME_TYPE_BY_EXT.get(ext);
                if (mimeType != null) return mimeType;
            }
        }

        // Fall back to the provider's mime type
        if (providerMimeType != null && !providerMimeType.equals("null") && providerMimeType.contains("/")) {
            return providerMimeType;
        }

        // Heuristic: guess from extension (returns null if unknown)
        return name != null ? guessMimeTypeFromExtension(name) : null;
    }

    public String guessMimeTypeFromExtension(String filename) {
        if (filename == null) return null;
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0) return null;
        
        String ext = filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "mp4": case "m4v": case "mov": return "video/mp4";
            case "avi": case "divx": return "video/x-msvideo";
            case "mkv": case "webm": return "video/x-matroska";
            case "mp3": return "audio/mpeg";
            case "aac": case "m4a": return "audio/aac";
            case "wav": return "audio/wav";
            case "ogg": case "oga": return "audio/ogg";
            case "flac": return "audio/flac";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            default: return null;
        }
    }
    
    private String extractAudioDuration(Uri uri) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(context, uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                return FormatHelper.parseMillisToTimeStringTo(durationMs);
            }
        } catch (Exception e) {
            YaaccLogger.d(getClass().getName(), "Duration not available: " + uri.getLastPathSegment());
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }
        return "";
    }

    /**
     * Quick video duration extraction from first 1MB (MOOV atom).
     */
    private String extractVideoDurationQuick(Uri uri) {
        try (java.io.InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            
            byte[] buffer = new byte[1024 * 1024]; // 1MB
            int bytesRead = is.read(buffer);
            
            // Try to find duration from MP4 MOOV atom
            String duration = parseMp4Duration(buffer, bytesRead);
            if (duration != null && !duration.isEmpty()) {
                YaaccLogger.d(getClass().getName(), "VIDEO_QUICK_EXTRACT: " + uri.getLastPathSegment() + " -> " + duration);
                return duration;
            }
        } catch (Exception e) {
            YaaccLogger.d(getClass().getName(), "VIDEO_QUICK_EXTRACT_FAILED: " + uri.getLastPathSegment());
        }
        return null;
    }

    /**
     * Parse MP4 MOOV atom to extract duration.
     */
    private String parseMp4Duration(byte[] buffer, int bytesRead) {
        // Look for 'mvhd' (movie header) atom which contains duration
        String mvhd = "mvhd";
        for (int i = 0; i < bytesRead - 20; i++) {
            if (buffer[i] == 'm' && buffer[i + 1] == 'v' && 
                buffer[i + 2] == 'h' && buffer[i + 3] == 'd') {
                
                // Found mvhd atom, duration is at specific offset
                try {
                    // Version at i+4, flags at i+5-7
                    int version = buffer[i + 4] & 0xFF;
                    
                    if (version == 0) {
                        // 32-bit duration at offset 16
                        int durationSecs = ((buffer[i + 16] & 0xFF) << 24) |
                                         ((buffer[i + 17] & 0xFF) << 16) |
                                         ((buffer[i + 18] & 0xFF) << 8) |
                                         (buffer[i + 19] & 0xFF);
                        
                        if (durationSecs > 0 && durationSecs < 1000000) {
                            return FormatHelper.parseMillisToTimeStringTo(durationSecs * 1000L);
                        }
                    } else if (version == 1) {
                        // 64-bit duration at offset 28 (different offset in version 1)
                        long durationSecs = ((long)(buffer[i + 28] & 0xFF) << 56) |
                                          ((long)(buffer[i + 29] & 0xFF) << 48) |
                                          ((long)(buffer[i + 30] & 0xFF) << 40) |
                                          ((long)(buffer[i + 31] & 0xFF) << 32) |
                                          ((long)(buffer[i + 32] & 0xFF) << 24) |
                                          ((long)(buffer[i + 33] & 0xFF) << 16) |
                                          ((long)(buffer[i + 34] & 0xFF) << 8) |
                                          (buffer[i + 35] & 0xFF);
                        
                        if (durationSecs > 0 && durationSecs < 1000000) {
                            return FormatHelper.parseMillisToTimeStringTo(durationSecs * 1000L);
                        }
                    }
                } catch (Exception e) {
                    YaaccLogger.d(getClass().getName(), "Error parsing mvhd atom");
                }
            }
        }
        return null;
    }

    private String extractDuration(Uri uri) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(context, uri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                return FormatHelper.parseMillisToTimeStringTo(durationMs);
            }
        } catch (IllegalArgumentException e) {
            // File not accessible (status 0x80000000) - expected for some files
            YaaccLogger.d(getClass().getName(), "Duration not available: " + uri.getLastPathSegment());
        } catch (Exception e) {
            YaaccLogger.w(getClass().getName(), "Failed to extract duration: " + uri.getLastPathSegment(), e);
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception ignored) {}
            }
        }
        return null;
    }
    
    /**
     * Get duration from cache or extract if not cached.
     * @deprecated Use getMetadata() instead
     */
    @Deprecated
    public String getDuration(Uri uri) {
        long startTime = System.currentTimeMillis();
        String key = CACHE_PREFIX + uri.toString();
        String fileName = uri.getLastPathSegment();
        
        if (awaitLoaded()) {
            // Check memory cache first, then the stored state; the value is the serialized metadata (or, written by
            // the former preload, a bare duration), not the duration itself
            String stored;
            synchronized (lock) {
                stored = lruCache.get(key);
                if (stored == null) {
                    stored = readStoredLocked(key);
                    if (stored != null) {
                        lruCache.put(key, stored);
                    }
                }
            }
            String duration = stored == null ? "" : SafCacheEntries.durationOf(stored);
            if (!duration.isEmpty()) {
                long elapsed = System.currentTimeMillis() - startTime;
                YaaccLogger.d(getClass().getName(), "CACHE_HIT: " + fileName + " -> " + duration + " (" + elapsed + "ms, cache_size=" + getCacheSize() + ")");
                return duration;
            }
        }
        
        // Extract and cache
        YaaccLogger.w(getClass().getName(), "CACHE_MISS: " + fileName + " - extracting...");
        String duration = extractAndCache(uri, false, fileName, null, 0);
        long elapsed = System.currentTimeMillis() - startTime;
        YaaccLogger.w(getClass().getName(), "EXTRACTION_COMPLETE: " + fileName + " -> " + duration + " (" + elapsed + "ms, cache_size=" + getCacheSize() + ")");
        return duration;
    }
    
    /**
     * Preload durations for SAF files in background.
     */
    public void preloadSafDurations() {
        if (isPreloading) {
            YaaccLogger.d(getClass().getName(), "PRELOAD_ALREADY_RUNNING");
            return;
        }
        
        YaaccLogger.d(getClass().getName(), "PRELOAD_STARTING - setting isPreloading=true");
        isPreloading = true;
        totalFilesIndexed = 0;
        lastProgressNotifyTime = 0;
        lastNotifiedFolder = "";
        lastFileIndexedTime = System.currentTimeMillis();
        long preloadStart = System.currentTimeMillis();
        YaaccLogger.d(getClass().getName(), "PRELOAD_START");
        
        preloadExecutor.execute(() -> {
            try {
                YaaccLogger.d(getClass().getName(), "PRELOAD_THREAD_STARTED");
                if (!awaitLoaded()) {
                    YaaccLogger.w(getClass().getName(), "PRELOAD_SKIPPED: the stored SAF cache did not load");
                    isPreloading = false;
                    return;
                }
                Set<String> safUris = de.yaacc.upnp.server.contentdirectory.MediaPathFilter.getSafPathes(context);
                YaaccLogger.d(getClass().getName(), "PRELOAD_SCANNING: " + safUris.size() + " SAF roots");
                
                if (safUris.isEmpty()) {
                    YaaccLogger.d(getClass().getName(), "PRELOAD_NO_PATHS: No SAF paths configured");
                    isPreloading = false;
                    notifyPreloadComplete();
                    return;
                }
                
                for (String uriString : safUris) {
                    YaaccLogger.d(getClass().getName(), "PRELOAD_PROCESSING_URI: " + uriString);
                    
                    // Check timeout (max 5 minutes total)
                    if (System.currentTimeMillis() - preloadStart > 5 * 60 * 1000) {
                        YaaccLogger.d(getClass().getName(), "PRELOAD_TIMEOUT: Stopping after 5 minutes");
                        break;
                    }
                    
                    try {
                        Uri safUri = Uri.parse(uriString);
                        DocumentFile root = DocumentFile.fromTreeUri(context, safUri);
                        if (root != null) {
                            YaaccLogger.d(getClass().getName(), "PRELOAD_TRAVERSING: " + root.getName());
                            traverseAndCache(root, preloadStart);
                        } else {
                            YaaccLogger.d(getClass().getName(), "PRELOAD_ROOT_NULL: Could not open " + uriString);
                        }
                    } catch (Exception e) {
                        YaaccLogger.d(getClass().getName(), "PRELOAD_ERROR: Exception for URI: " + uriString + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                long elapsed = System.currentTimeMillis() - preloadStart;
                YaaccLogger.d(getClass().getName(), "PRELOAD_COMPLETE: " + totalFilesIndexed + " files indexed in " + elapsed + "ms (cache_size=" + getCacheSize() + ")");
                isPreloading = false;
                YaaccLogger.d(getClass().getName(), "PRELOAD_STATE_SET_FALSE: isPreloading now = " + isPreloading);
                notifyPreloadComplete();
            } catch (Exception e) {
                YaaccLogger.d(getClass().getName(), "PRELOAD_EXCEPTION", e);
                isPreloading = false;
            } finally {
                isPreloading = false;
                YaaccLogger.d(getClass().getName(), "PRELOAD_FINALLY: isPreloading = " + isPreloading);
                // the end of a preload: write what is still collected (the commit runs on the I/O thread)
                requestFlush();
            }
        });
    }
    
    private void notifyPreloadProgress(int filesIndexed, String currentFolder) {
        Intent intent = new Intent("de.yaacc.CACHE_PRELOAD_PROGRESS");
        intent.putExtra("files_indexed", filesIndexed);
        intent.putExtra("current_folder", currentFolder);
        // The receiver is registered non-exported in this app, so address the broadcast to this package.
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
    
    private void notifyPreloadComplete() {
        Intent intent = new Intent("de.yaacc.CACHE_PRELOAD_COMPLETE");
        intent.putExtra("files_indexed", totalFilesIndexed);
        // The receiver is registered non-exported in this app, so address the broadcast to this package.
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
    
    void traverseAndCache(DocumentFile dir, long startTime) {
        traverseAndCache(dir, startTime, 0);
    }
    
    private void traverseAndCache(DocumentFile dir, long startTime, int depth) {
        if (!dir.isDirectory()) return;
        
        // Prevent infinite recursion
        if (depth > 100) {
            YaaccLogger.d(getClass().getName(), "PRELOAD_MAX_DEPTH: Stopping at depth " + depth);
            return;
        }
        
        // Check overall timeout
        if (System.currentTimeMillis() - startTime > 5 * 60 * 1000) {
            return;
        }
        
        // Check if stuck (no progress for 30 seconds)
        long currentTime = System.currentTimeMillis();
        if (lastFileIndexedTime > 0 && currentTime - lastFileIndexedTime > STALL_TIMEOUT) {
            YaaccLogger.d(getClass().getName(), "PRELOAD_STALL_DETECTED: No progress for 30 seconds at depth " + depth + ", stopping");
            return;
        }
        
        // Check if we can read this directory (with timeout)
        boolean canRead = false;
        try {
            long checkStart = System.currentTimeMillis();
            canRead = dir.canRead();
            long checkTime = System.currentTimeMillis() - checkStart;
            if (checkTime > 1000) {
                YaaccLogger.d(getClass().getName(), "PRELOAD_CANREAD_SLOW: " + dir.getName() + " took " + checkTime + "ms");
            }
        } catch (Exception e) {
            YaaccLogger.d(getClass().getName(), "PRELOAD_CANREAD_ERROR: " + dir.getName() + " - " + e.getClass().getSimpleName());
            return;
        }
        
        if (!canRead) {
            YaaccLogger.d(getClass().getName(), "PRELOAD_SKIP: No read permission for " + dir.getName());
            return;
        }
        
        DocumentFile[] files = null;
        try {
            long listStart = System.currentTimeMillis();
            files = dir.listFiles();
            long listTime = System.currentTimeMillis() - listStart;
            if (listTime > 1000) {
                YaaccLogger.d(getClass().getName(), "PRELOAD_LISTFILES_SLOW: " + dir.getName() + " took " + listTime + "ms");
            }
        } catch (Exception e) {
            YaaccLogger.d(getClass().getName(), "PRELOAD_LISTFILES_ERROR: " + dir.getName() + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }
        
        if (files == null) {
            YaaccLogger.d(getClass().getName(), "PRELOAD_LISTFILES_NULL: " + dir.getName());
            return;
        }
        
        String folderName = dir.getName() != null ? dir.getName() : "Unknown";
        YaaccLogger.v(getClass().getName(), "PRELOAD_TRAVERSE: " + folderName + " (" + files.length + " items, depth=" + depth + ")");
        
        for (int i = 0; i < files.length; i++) {
            DocumentFile file = files[i];
            long fileStart = System.currentTimeMillis();
            YaaccLogger.v(getClass().getName(), "PRELOAD_FILE_START: [" + i + "/" + files.length + "] " + file.getName());
            
            try {
                // Check file operation timeout (5 seconds per file)
                if (System.currentTimeMillis() - fileStart > 5000) {
                    YaaccLogger.d(getClass().getName(), "PRELOAD_FILE_TIMEOUT: [" + i + "/" + files.length + "] " + file.getName() + " taking too long, skipping");
                    continue;
                }
                
                if (file.isDirectory()) {
                    traverseAndCache(file, startTime, depth + 1);
                } else if (hasDuration(file)) {
                    String key = CACHE_PREFIX + file.getUri().toString();

                    boolean needsExtraction;
                    synchronized (lock) {
                        needsExtraction = needsDurationLocked(key);
                    }

                    if (needsExtraction) {
                        long extractStart = System.currentTimeMillis();
                        extractAndCache(file.getUri(), true, file.getName(), file.getType(), file.length());
                        long elapsed = System.currentTimeMillis() - extractStart;
                        YaaccLogger.v(getClass().getName(), "PRELOAD_EXTRACTED: " + file.getName() + " (" + elapsed + "ms)");
                    } else {
                        YaaccLogger.v(getClass().getName(), "PRELOAD_CACHED: " + file.getName());
                    }
                    
                    totalFilesIndexed++;
                    lastFileIndexedTime = System.currentTimeMillis();
                    
                    // Notify progress based on time interval (every 500ms)
                    long progressCheckTime = System.currentTimeMillis();
                    if (progressCheckTime - lastProgressNotifyTime > PROGRESS_NOTIFY_INTERVAL) {
                        notifyPreloadProgress(totalFilesIndexed, folderName);
                        lastProgressNotifyTime = progressCheckTime;
                        lastNotifiedFolder = folderName;
                    }
                }
            } catch (Exception e) {
                long fileElapsed = System.currentTimeMillis() - fileStart;
                YaaccLogger.d(getClass().getName(), "PRELOAD_FILE_ERROR: [" + i + "/" + files.length + "] " + file.getName() + " after " + fileElapsed + "ms - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            
            long fileElapsed = System.currentTimeMillis() - fileStart;
            if (fileElapsed > 5000) {
                YaaccLogger.d(getClass().getName(), "PRELOAD_FILE_SLOW: [" + i + "/" + files.length + "] " + file.getName() + " took " + fileElapsed + "ms");
            }
        }
    }
    
    /** Only audio and video have a duration; the retriever fails on images (and logged a stack trace for each). */
    private boolean hasDuration(DocumentFile file) {
        String type = file.getType();
        return type != null && (type.startsWith("audio/") || type.startsWith("video/"));
    }
    
    /** Files the retriever could not read in this process: they are not tried again on every preload. */
    private final java.util.Set<String> unreadable = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The two ways a duration is read from a file. A seam so that tests can count the extractions; the real one
     * uses the platform retriever and the MOOV shortcut for video.
     */
    interface DurationExtractor {
        /** Preload and {@link #getDuration}: null if the file has no duration, throws if it cannot be read. */
        String extractForPreload(Uri uri) throws Exception;

        /** The background task queued by {@link #getMetadata}: null or empty if there is no duration. */
        String extractInBackground(Uri uri, String mimeType);
    }

    private volatile DurationExtractor durationExtractor = new DurationExtractor() {
        @Override
        public String extractForPreload(Uri uri) throws Exception {
            return retrieveDuration(uri);
        }

        @Override
        public String extractInBackground(Uri uri, String mimeType) {
            return SAFCacheManager.this.extractInBackground(uri, mimeType);
        }
    };

    /** For tests. */
    void setDurationExtractor(DurationExtractor durationExtractor) {
        this.durationExtractor = durationExtractor;
    }

    /**
     * Reads the duration of a file and stores it in the file's metadata entry (the short id and the other values of
     * an existing entry stay; without one the entry is built from the given values if {@code createIfMissing}, else the
     * duration is not stored: the caller does not know the size, and a size of 0 must not get into the cache).
     *
     * @return the duration, null if the file has none or cannot be read
     */
    private String extractAndCache(Uri uri, boolean createIfMissing, String name, String providerMimeType, long fileSize) {
        if (unreadable.contains(uri.toString())) {
            return null;
        }
        long extractStart = System.currentTimeMillis();
        try {
            String formatted = durationExtractor.extractForPreload(uri);
            if (formatted != null && !formatted.isEmpty()) {
                synchronized (lock) {
                    storeDurationLocked(uri, formatted, createIfMissing, name, providerMimeType, fileSize);
                }
                long elapsed = System.currentTimeMillis() - extractStart;
                YaaccLogger.v(getClass().getName(), "EXTRACT_SUCCESS: " + uri.getLastPathSegment() + " -> " + formatted + " (" + elapsed + "ms)");
                return formatted;
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - extractStart;
            unreadable.add(uri.toString());
            // one line, no stack trace: a file the retriever cannot read is not an error of the app
            YaaccLogger.w(getClass().getName(), "EXTRACT_FAILED: " + uri.getLastPathSegment() + " (" + elapsed + "ms) - "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /** The platform retriever; null if the file has no duration, throws if it cannot be read. */
    private String retrieveDuration(Uri uri) throws Exception {
        MediaMetadataRetriever retriever = null;
        try {
            long createStart = System.currentTimeMillis();
            retriever = new MediaMetadataRetriever();
            long createTime = System.currentTimeMillis() - createStart;

            long setDataStart = System.currentTimeMillis();
            retriever.setDataSource(context, uri);
            long setDataTime = System.currentTimeMillis() - setDataStart;

            long metadataStart = System.currentTimeMillis();
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long metadataTime = System.currentTimeMillis() - metadataStart;

            YaaccLogger.v(getClass().getName(), "EXTRACT_BREAKDOWN: " + uri.getLastPathSegment() + " - create=" + createTime + "ms, setDataSource=" + setDataTime + "ms, extractMetadata=" + metadataTime + "ms");

            if (durationStr != null) {
                return FormatHelper.parseMillisToTimeStringTo(Long.parseLong(durationStr));
            }
            return null;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Runs on the I/O thread, first: moves the old entries out of the default preferences, then loads the stored
     * state into the memory structures and opens the gate. Whatever happens, the gate opens (an empty cache is
     * better than callers that wait forever).
     */
    private void load() {
        long loadStart = System.currentTimeMillis();
        int count = 0;
        int migrated = 0;
        try {
            migrated = migrateFromDefaultPreferences();
            Map<String, ?> all = store.getAll();
            synchronized (lock) {
                for (Map.Entry<String, ?> entry : all.entrySet()) {
                    String key = entry.getKey();
                    if (key.startsWith(CACHE_PREFIX) && entry.getValue() instanceof String) {
                        lruCache.put(key, (String) entry.getValue());

                        // Restore ID mappings from cached metadata
                        SAFMetadata metadata = SAFMetadata.deserialize((String) entry.getValue());
                        if (metadata != null && metadata.shortId != null) {
                            ids.restore(metadata.shortId, key.substring(CACHE_PREFIX.length()));
                        }
                        count++;
                    }
                }
                // the counter; never behind a restored id
                Object counter = all.get(ID_COUNTER_KEY);
                if (counter instanceof Number) {
                    ids.restoreCounter(((Number) counter).longValue());
                }
            }
        } catch (RuntimeException e) {
            YaaccLogger.w(getClass().getName(), "CACHE_LOAD_FAILED, starting with what was read: " + e, e);
        } finally {
            loadGate.open();
        }
        long elapsed = System.currentTimeMillis() - loadStart;
        YaaccLogger.i(getClass().getName(), "CACHE_LOADED: " + count + " entries in " + elapsed + "ms (cache_size=" + getCacheSize()
                + ", id_mappings=" + ids.size() + ", migrated=" + migrated + ")");
    }

    /**
     * Moves the SAF cache entries of the default preferences into {@link #store}, see {@link SafCacheMigration}.
     * I/O thread only. First the new file is committed, then the old keys are removed: if the process dies in
     * between, the next start finds the old keys again and the plan only adds what the new file lacks.
     *
     * @return the number of old keys that were handled
     */
    private int migrateFromDefaultPreferences() {
        SafCacheMigration.Plan plan = SafCacheMigration.plan(legacyPreferences.getAll(), store.getAll());
        if (plan.isEmpty()) {
            return 0;
        }
        if (plan.needsWrite()) {
            SharedPreferences.Editor write = store.edit();
            for (Map.Entry<String, String> entry : plan.entriesToWrite.entrySet()) {
                write.putString(entry.getKey(), entry.getValue());
            }
            if (plan.counterToWrite != null) {
                write.putLong(ID_COUNTER_KEY, plan.counterToWrite);
            }
            if (!write.commit()) {
                YaaccLogger.w(getClass().getName(), "CACHE_MIGRATION_FAILED: the new file could not be written, the old entries stay");
                return 0;
            }
        }
        SharedPreferences.Editor remove = legacyPreferences.edit();
        for (String key : plan.legacyKeysToRemove) {
            remove.remove(key);
        }
        if (!remove.commit()) {
            YaaccLogger.w(getClass().getName(), "CACHE_MIGRATION: the old entries could not be removed, they are checked again at the next start");
        }
        YaaccLogger.d(getClass().getName(), "CACHE_MIGRATION: " + plan.entriesToWrite.size() + " entries copied, "
                + plan.legacyKeysToRemove.size() + " old keys removed from the default preferences");
        return plan.legacyKeysToRemove.size();
    }

    // ---- writing ----

    /** Called with {@link #lock} held: queue a flush now if one is due, else one for the time it becomes due. */
    private void scheduleFlushLocked() {
        if (flushPolicy.isDue()) {
            if (!immediateFlushQueued) {
                immediateFlushQueued = true;
                submitIo(this::flush);
            }
        } else if (!timedFlushQueued) {
            long delay = flushPolicy.millisUntilDue();
            if (delay >= 0) {
                timedFlushQueued = true;
                try {
                    ioExecutor.schedule(this::flush, delay, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    timedFlushQueued = false;
                }
            }
        }
    }

    private void submitIo(Runnable task) {
        try {
            ioExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            YaaccLogger.d(getClass().getName(), "SAF cache I/O thread is shut down, " + e.getMessage());
        }
    }

    /** Writes what waits in memory soon, on the I/O thread. */
    private void requestFlush() {
        synchronized (lock) {
            if (!immediateFlushQueued) {
                immediateFlushQueued = true;
                submitIo(this::flush);
            }
        }
    }

    /**
     * Commits the collected changes in one go. I/O thread only (never the main thread: {@code commit()} writes the
     * file in the calling thread). A failed commit puts the changes back for the next round.
     */
    void flush() {
        SafCacheWriteBuffer.Batch batch;
        synchronized (lock) {
            immediateFlushQueued = false;
            timedFlushQueued = false;
            batch = buffer.drain();
            flushPolicy.flushed();
        }
        if (batch.isEmpty()) {
            return;
        }
        long start = System.currentTimeMillis();
        SharedPreferences.Editor editor = store.edit();
        if (batch.clearAll) {
            editor.clear();
        }
        for (String key : batch.removes) {
            editor.remove(key);
        }
        for (Map.Entry<String, String> entry : batch.puts.entrySet()) {
            editor.putString(entry.getKey(), entry.getValue());
        }
        if (batch.counter != null) {
            editor.putLong(ID_COUNTER_KEY, batch.counter);
        }
        boolean written = editor.commit();
        if (written) {
            YaaccLogger.d(getClass().getName(), "SAF cache written: " + batch.puts.size() + " puts, " + batch.removes.size()
                    + " removes" + (batch.clearAll ? ", cleared" : "") + " in " + (System.currentTimeMillis() - start) + " ms");
        } else {
            YaaccLogger.w(getClass().getName(), "SAF cache write failed, " + batch.puts.size() + " puts kept for the next round");
            synchronized (lock) {
                buffer.restore(batch);
                flushPolicy.recordChange();
                scheduleFlushLocked();
            }
        }
    }

    /**
     * Queues a flush and waits for it, at most {@code waitMillis}. For the exit of the app only.
     */
    void flushAndWait(long waitMillis) {
        try {
            Future<?> done = ioExecutor.submit(this::flush);
            done.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            YaaccLogger.w(getClass().getName(), "SAF cache write not finished in " + waitMillis + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            YaaccLogger.w(getClass().getName(), "SAF cache write at exit failed: " + e);
        }
    }

    /**
     * Writes what waits in memory (on the I/O thread, so this returns at once). The LRU of the memory is not
     * mirrored in the stored state: an entry the LRU dropped stays stored, so the short id of a file beyond the
     * {@value #MAX_CACHE_SIZE}th still resolves after a restart (the former trim never removed anything in practice).
     */
    public void trimCache() {
        requestFlush();
    }
    
    /**
     * @return true if the preference key is a cache entry of a file below the given SAF tree
     */
    static boolean isCacheKeyUnderTree(String key, String treeUri) {
        return key != null && treeUri != null && key.startsWith(CACHE_PREFIX + treeUri + "/");
    }

    /**
     * Forgets the cached metadata (durations) of every file below a SAF tree whose permission was
     * given up, in memory at once and in the stored state on the I/O thread.
     */
    public void removeCachedUnder(String treeUri) {
        synchronized (lock) {
            List<String> keys = new ArrayList<>(lruCache.keySet());
            keys.addAll(buffer.pendingPuts().keySet());
            removeUnderTreeLocked(keys, treeUri);
        }
        submitIo(() -> {
            // after the load; the keys that are only stored (the LRU holds MAX_CACHE_SIZE of them)
            List<String> storedKeys = new ArrayList<>(store.getAll().keySet());
            synchronized (lock) {
                removeUnderTreeLocked(storedKeys, treeUri);
            }
            flush();
        });
        // a folder listing below the tree must not outlive the permission
        SafDirectoryLister.clearCache();
    }

    private void removeUnderTreeLocked(Iterable<String> keys, String treeUri) {
        for (String key : keys) {
            if (isCacheKeyUnderTree(key, treeUri)) {
                lruCache.remove(key);
                buffer.remove(key);
                flushPolicy.recordChange();
            }
        }
    }

    /**
     * Clear all cached metadata and ID mappings.
     */
    public void clearCache() {
        SafDirectoryLister.clearCache();
        synchronized (lock) {
            clearMemoryLocked();
        }
        // behind the load, if it is still running: the stored state must not come back
        submitIo(() -> {
            synchronized (lock) {
                clearMemoryLocked();
            }
            flush();
        });
        YaaccLogger.i(getClass().getName(), "Cache cleared");
    }

    private void clearMemoryLocked() {
        lruCache.clear();
        ids.reset();
        buffer.clearAll();
        flushPolicy.recordChange();
    }
    
    public void shutdown() {
        preloadExecutor.shutdown();
        flushAndWait(1000);
        ioExecutor.shutdown();
    }
    
    public boolean isPreloading() {
        return isPreloading;
    }
    
    public int getTotalFilesIndexed() {
        return totalFilesIndexed;
    }
    
    public int getCacheSize() {
        synchronized (lock) {
            return lruCache.size();
        }
    }
    
    /**
     * Simple LRU cache using LinkedHashMap. Not thread safe: the manager's lock guards it.
     */
    private static class LRUCache extends LinkedHashMap<String, String> {
        private final int maxSize;
        
        LRUCache(int maxSize) {
            super(16, 0.75f, true); // accessOrder = true
            this.maxSize = maxSize;
        }
        
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > maxSize;
        }
    }
}
