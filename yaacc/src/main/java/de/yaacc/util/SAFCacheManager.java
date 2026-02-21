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

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.yaacc.R;

/**
 * LRU cache for SAF (Storage Access Framework) file metadata with background preloading.
 * Caches duration, MIME type, and encoded IDs for fast browsing.
 */
public class SAFCacheManager {
    private static final int MAX_CACHE_SIZE = 1000;
    private static final String CACHE_PREFIX = "saf_cache_";
    private static final String ID_COUNTER_KEY = "saf_id_counter";
    
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
    private final SharedPreferences preferences;
    private final ExecutorService preloadExecutor;
    private final LRUCache lruCache;
    private final Map<String, String> shortIdToUri = new ConcurrentHashMap<>();
    private final Map<String, String> uriToShortId = new ConcurrentHashMap<>();
    private long idCounter = 1;
    
    private int totalFilesIndexed = 0;
    private boolean isPreloading = false;
    
    private static SAFCacheManager instance;
    
    public static synchronized SAFCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new SAFCacheManager(context.getApplicationContext());
        }
        return instance;
    }
    
    private SAFCacheManager(Context context) {
        this.context = context;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.preloadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SAFPreloader");
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.lruCache = new LRUCache(MAX_CACHE_SIZE);
        
        // Restore ID counter from preferences
        this.idCounter = preferences.getLong(ID_COUNTER_KEY, 1);
        
        loadCacheIndex();
    }
    
    /**
     * Get complete metadata for a SAF file (duration, MIME type, encoded ID).
     * Returns cached data if available, otherwise extracts synchronously.
     */
    public SAFMetadata getMetadata(DocumentFile file) {
        if (file == null) return null;
        
        long startTime = System.currentTimeMillis();
        String uri = file.getUri().toString();
        String key = CACHE_PREFIX + uri;
        
        // Check memory cache
        String cached = lruCache.get(key);
        if (cached != null) {
            SAFMetadata metadata = SAFMetadata.deserialize(cached);
            if (metadata != null) {
                // Migrate old entries without shortId
                if (metadata.shortId == null) {
                    String shortId = getOrCreateShortId(uri);
                    metadata = new SAFMetadata(metadata.duration, metadata.mimeType, shortId, metadata.fileSize);
                    String serialized = metadata.serialize();
                    lruCache.put(key, serialized);
                    preferences.edit().putString(key, serialized).apply();
                }
                long elapsed = System.currentTimeMillis() - startTime;
                YaaccLogger.d(getClass().getName(), "CACHE_HIT_MEMORY: " + file.getName() + " (" + elapsed + "ms)");
                return metadata;
            }
        }
        
        // Check disk cache
        cached = preferences.getString(key, null);
        if (cached != null) {
            SAFMetadata metadata = SAFMetadata.deserialize(cached);
            if (metadata != null) {
                // Migrate old entries without shortId
                if (metadata.shortId == null) {
                    String shortId = getOrCreateShortId(uri);
                    metadata = new SAFMetadata(metadata.duration, metadata.mimeType, shortId, metadata.fileSize);
                    String serialized = metadata.serialize();
                    lruCache.put(key, serialized);
                    preferences.edit().putString(key, serialized).apply();
                } else {
                    lruCache.put(key, cached);
                }
                long elapsed = System.currentTimeMillis() - startTime;
                YaaccLogger.d(getClass().getName(), "CACHE_HIT_DISK: " + file.getName() + " (" + elapsed + "ms)");
                return metadata;
            }
        }
        
        // Extract and cache
        YaaccLogger.w(getClass().getName(), "CACHE_MISS: " + file.getName() + " - extracting...");
        SAFMetadata metadata = extractMetadata(file);
        if (metadata != null) {
            String serialized = metadata.serialize();
            lruCache.put(key, serialized);
            preferences.edit().putString(key, serialized).apply();
            
            // Trim disk cache if memory cache evicted entries
            if (!lruCache.getEvictedKeys().isEmpty()) {
                trimCache();
            }
        }
        long elapsed = System.currentTimeMillis() - startTime;
        YaaccLogger.w(getClass().getName(), "EXTRACTION_COMPLETE: " + file.getName() + " (" + elapsed + "ms)");
        return metadata;
    }
    
    /**
     * Extract all metadata for a file (duration, MIME type, short ID).
     */
    private SAFMetadata extractMetadata(DocumentFile file) {
        String uri = file.getUri().toString();
        String duration = extractDuration(file.getUri());
        String mimeType = extractMimeType(file);
        String shortId = getOrCreateShortId(uri);
        long fileSize = file.length();
        return new SAFMetadata(duration, mimeType, shortId, fileSize);
    }
    
    /**
     * Get or create a short ID for a URI.
     */
    public synchronized String getOrCreateShortId(String uri) {
        String existing = uriToShortId.get(uri);
        if (existing != null) {
            return existing;
        }
        
        String id = String.valueOf(idCounter++);
        uriToShortId.put(uri, id);
        shortIdToUri.put(id, uri);
        
        // Persist counter
        preferences.edit().putLong(ID_COUNTER_KEY, idCounter).apply();
        
        YaaccLogger.d(getClass().getName(), "Created shortId mapping: " + id + " -> " + uri);
        return id;
    }
    
    /**
     * Get URI for a short ID.
     */
    public String getUriForShortId(String shortId) {
        return shortIdToUri.get(shortId);
    }
    
    private String extractMimeType(DocumentFile file) {
        // Try extension-based lookup first
        if (file.getName() != null) {
            int dotIndex = file.getName().lastIndexOf('.');
            if (dotIndex > 0) {
                String ext = file.getName().substring(dotIndex + 1).toLowerCase();
                String mimeType = MIME_TYPE_BY_EXT.get(ext);
                if (mimeType != null) return mimeType;
            }
        }
        // Fall back to system lookup
        return file.getType();
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
        
        // Check memory cache first
        String duration = lruCache.get(key);
        if (duration != null) {
            long elapsed = System.currentTimeMillis() - startTime;
            YaaccLogger.d(getClass().getName(), "CACHE_HIT_MEMORY: " + fileName + " -> " + duration + " (" + elapsed + "ms, cache_size=" + lruCache.size() + ")");
            return duration;
        }
        
        // Check SharedPreferences
        duration = preferences.getString(key, null);
        if (duration != null) {
            lruCache.put(key, duration);
            long elapsed = System.currentTimeMillis() - startTime;
            YaaccLogger.d(getClass().getName(), "CACHE_HIT_DISK: " + fileName + " -> " + duration + " (" + elapsed + "ms, cache_size=" + lruCache.size() + ")");
            return duration;
        }
        
        // Extract and cache
        YaaccLogger.w(getClass().getName(), "CACHE_MISS: " + fileName + " - extracting...");
        duration = extractAndCache(uri);
        long elapsed = System.currentTimeMillis() - startTime;
        YaaccLogger.w(getClass().getName(), "EXTRACTION_COMPLETE: " + fileName + " -> " + duration + " (" + elapsed + "ms, cache_size=" + lruCache.size() + ")");
        return duration;
    }
    
    /**
     * Preload durations for SAF files in background.
     */
    public void preloadSafDurations() {
        if (isPreloading) {
            YaaccLogger.w(getClass().getName(), "PRELOAD_ALREADY_RUNNING");
            return;
        }
        
        isPreloading = true;
        totalFilesIndexed = 0;
        long preloadStart = System.currentTimeMillis();
        YaaccLogger.i(getClass().getName(), "PRELOAD_START");
        
        preloadExecutor.execute(() -> {
            try {
                Set<String> safUris = de.yaacc.upnp.server.contentdirectory.MediaPathFilter.getSafPathes(context);
                YaaccLogger.i(getClass().getName(), "PRELOAD_SCANNING: " + safUris.size() + " SAF roots");
                
                for (String uriString : safUris) {
                    // Check timeout (max 5 minutes total)
                    if (System.currentTimeMillis() - preloadStart > 5 * 60 * 1000) {
                        YaaccLogger.w(getClass().getName(), "PRELOAD_TIMEOUT: Stopping after 5 minutes");
                        break;
                    }
                    
                    try {
                        Uri safUri = Uri.parse(uriString);
                        DocumentFile root = DocumentFile.fromTreeUri(context, safUri);
                        if (root != null) {
                            traverseAndCache(root, preloadStart);
                        }
                    } catch (Exception e) {
                        YaaccLogger.w(getClass().getName(), "PRELOAD_ERROR: Failed to preload SAF: " + uriString, e);
                    }
                }
                long elapsed = System.currentTimeMillis() - preloadStart;
                YaaccLogger.i(getClass().getName(), "PRELOAD_COMPLETE: " + totalFilesIndexed + " files indexed in " + elapsed + "ms (cache_size=" + lruCache.size() + ")");
            } finally {
                isPreloading = false;
                notifyPreloadComplete();
            }
        });
    }
    
    private void notifyPreloadProgress(int filesIndexed, String currentFolder) {
        Intent intent = new Intent("de.yaacc.CACHE_PRELOAD_PROGRESS");
        intent.putExtra("files_indexed", filesIndexed);
        intent.putExtra("current_folder", currentFolder);
        context.sendBroadcast(intent);
    }
    
    private void notifyPreloadComplete() {
        Intent intent = new Intent("de.yaacc.CACHE_PRELOAD_COMPLETE");
        intent.putExtra("files_indexed", totalFilesIndexed);
        context.sendBroadcast(intent);
    }
    
    private void traverseAndCache(DocumentFile dir, long startTime) {
        if (!dir.isDirectory()) return;
        
        // Check timeout
        if (System.currentTimeMillis() - startTime > 5 * 60 * 1000) {
            return;
        }
        
        // Check if we can read this directory
        if (!dir.canRead()) {
            YaaccLogger.d(getClass().getName(), "PRELOAD_SKIP: No read permission for " + dir.getName());
            return;
        }
        
        DocumentFile[] files = dir.listFiles();
        if (files == null) return;
        
        String folderName = dir.getName() != null ? dir.getName() : "Unknown";
        YaaccLogger.d(getClass().getName(), "PRELOAD_TRAVERSE: " + folderName + " (" + files.length + " items)");
        
        for (DocumentFile file : files) {
            if (file.isDirectory()) {
                traverseAndCache(file, startTime);
            } else if (isMediaFile(file)) {
                String key = CACHE_PREFIX + file.getUri().toString();
                if (!preferences.contains(key)) {
                    long extractStart = System.currentTimeMillis();
                    extractAndCache(file.getUri());
                    long elapsed = System.currentTimeMillis() - extractStart;
                    YaaccLogger.d(getClass().getName(), "PRELOAD_EXTRACTED: " + file.getName() + " (" + elapsed + "ms)");
                } else {
                    YaaccLogger.d(getClass().getName(), "PRELOAD_CACHED: " + file.getName());
                }
                
                totalFilesIndexed++;
                
                // Notify progress every 10 files
                if (totalFilesIndexed % 10 == 0) {
                    notifyPreloadProgress(totalFilesIndexed, folderName);
                }
            }
        }
    }
    
    private boolean isMediaFile(DocumentFile file) {
        String type = file.getType();
        return type != null && (type.startsWith("audio/") || type.startsWith("video/") || type.startsWith("image/"));
    }
    
    private String extractAndCache(Uri uri) {
        long extractStart = System.currentTimeMillis();
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
            
            YaaccLogger.d(getClass().getName(), "EXTRACT_BREAKDOWN: " + uri.getLastPathSegment() + " - create=" + createTime + "ms, setDataSource=" + setDataTime + "ms, extractMetadata=" + metadataTime + "ms");
            
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                String formatted = FormatHelper.parseMillisToTimeStringTo(durationMs);
                
                String key = CACHE_PREFIX + uri.toString();
                lruCache.put(key, formatted);
                preferences.edit().putString(key, formatted).apply();
                
                long elapsed = System.currentTimeMillis() - extractStart;
                YaaccLogger.d(getClass().getName(), "EXTRACT_SUCCESS: " + uri.getLastPathSegment() + " -> " + formatted + " (" + elapsed + "ms)");
                return formatted;
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - extractStart;
            YaaccLogger.w(getClass().getName(), "EXTRACT_FAILED: " + uri.getLastPathSegment() + " (" + elapsed + "ms)", e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
    
    private void loadCacheIndex() {
        long loadStart = System.currentTimeMillis();
        Map<String, ?> all = preferences.getAll();
        int count = 0;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(CACHE_PREFIX) && entry.getValue() instanceof String) {
                lruCache.put(key, (String) entry.getValue());
                
                // Restore ID mappings from cached metadata
                SAFMetadata metadata = SAFMetadata.deserialize((String) entry.getValue());
                if (metadata != null && metadata.shortId != null) {
                    String uri = key.substring(CACHE_PREFIX.length());
                    shortIdToUri.put(metadata.shortId, uri);
                    uriToShortId.put(uri, metadata.shortId);
                    
                    // Update counter to avoid ID collisions
                    try {
                        long id = Long.parseLong(metadata.shortId);
                        if (id >= idCounter) {
                            idCounter = id + 1;
                        }
                    } catch (NumberFormatException e) {
                        // Ignore non-numeric IDs
                    }
                }
                
                count++;
            }
        }
        long elapsed = System.currentTimeMillis() - loadStart;
        YaaccLogger.i(getClass().getName(), "CACHE_LOADED: " + count + " entries in " + elapsed + "ms (cache_size=" + lruCache.size() + ", id_mappings=" + shortIdToUri.size() + ")");
    }
    
    /**
     * Clear old entries beyond MAX_CACHE_SIZE.
     */
    public void trimCache() {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : lruCache.getEvictedKeys()) {
            editor.remove(key);
        }
        editor.apply();
    }
    
    /**
     * Clear all cached metadata and ID mappings.
     */
    public void clearCache() {
        lruCache.clear();
        shortIdToUri.clear();
        uriToShortId.clear();
        idCounter = 1;
        
        SharedPreferences.Editor editor = preferences.edit();
        Map<String, ?> all = preferences.getAll();
        for (String key : all.keySet()) {
            if (key.startsWith(CACHE_PREFIX) || key.equals(ID_COUNTER_KEY)) {
                editor.remove(key);
            }
        }
        editor.apply();
        
        YaaccLogger.i(getClass().getName(), "Cache cleared");
    }
    
    public void shutdown() {
        preloadExecutor.shutdown();
        trimCache();
    }
    
    public boolean isPreloading() {
        return isPreloading;
    }
    
    public int getTotalFilesIndexed() {
        return totalFilesIndexed;
    }
    
    public int getCacheSize() {
        return lruCache.size();
    }
    
    /**
     * Simple LRU cache using LinkedHashMap.
     */
    private static class LRUCache extends LinkedHashMap<String, String> {
        private final int maxSize;
        private final java.util.List<String> evictedKeys = new java.util.ArrayList<>();
        
        LRUCache(int maxSize) {
            super(16, 0.75f, true); // accessOrder = true
            this.maxSize = maxSize;
        }
        
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            if (size() > maxSize) {
                evictedKeys.add(eldest.getKey());
                return true;
            }
            return false;
        }
        
        java.util.List<String> getEvictedKeys() {
            java.util.List<String> keys = new java.util.ArrayList<>(evictedKeys);
            evictedKeys.clear();
            return keys;
        }
    }
}
