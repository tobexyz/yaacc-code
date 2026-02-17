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
                lruCache.put(key, cached);
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
     * Extract all metadata for a file (duration, MIME type, encoded ID).
     */
    private SAFMetadata extractMetadata(DocumentFile file) {
        String duration = extractDuration(file.getUri());
        String mimeType = extractMimeType(file);
        String encodedId = encodeUri(file.getUri().toString());
        long fileSize = file.length();
        return new SAFMetadata(duration, mimeType, encodedId, fileSize);
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
    
    private String encodeUri(String uri) {
        return new String(android.util.Base64.encode(uri.getBytes(), android.util.Base64.NO_WRAP));
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
                    try {
                        Uri safUri = Uri.parse(uriString);
                        DocumentFile root = DocumentFile.fromTreeUri(context, safUri);
                        if (root != null) {
                            traverseAndCache(root);
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
    
    private void traverseAndCache(DocumentFile dir) {
        if (!dir.isDirectory()) return;
        
        DocumentFile[] files = dir.listFiles();
        if (files == null) return;
        
        String folderName = dir.getName() != null ? dir.getName() : "Unknown";
        YaaccLogger.d(getClass().getName(), "PRELOAD_TRAVERSE: " + folderName + " (" + files.length + " items)");
        
        for (DocumentFile file : files) {
            if (file.isDirectory()) {
                traverseAndCache(file);
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
        return type != null && (type.startsWith("audio/") || type.startsWith("video/"));
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
                count++;
            }
        }
        long elapsed = System.currentTimeMillis() - loadStart;
        YaaccLogger.i(getClass().getName(), "CACHE_LOADED: " + count + " entries in " + elapsed + "ms (cache_size=" + lruCache.size() + ")");
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
