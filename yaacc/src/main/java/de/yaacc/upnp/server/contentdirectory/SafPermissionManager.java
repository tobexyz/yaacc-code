package de.yaacc.upnp.server.contentdirectory;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SafPermissionManager {
    private static final String TAG = "SafPermissionManager";
    private static final int MAX_PERMISSIONS = 120; // Leave buffer below Android's 128 limit

    public static void validateAndCleanupPermissions(Context context) {
        Set<String> storedUris = MediaPathFilter.getSafPathes(context);
        List<UriPermission> grantedPermissions = context.getContentResolver().getPersistedUriPermissions();
        
        Log.i(TAG, "Checking " + storedUris.size() + " stored SAF URIs, " + 
              grantedPermissions.size() + " total permissions");
        
        // Check each stored URI to see if we still have permission
        for (String uriString : storedUris) {
            try {
                Uri uri = Uri.parse(uriString);
                boolean hasPermission = grantedPermissions.stream()
                    .anyMatch(perm -> perm.getUri().equals(uri) && perm.isReadPermission());
                
                if (!hasPermission) {
                    Log.w(TAG, "Lost permission for SAF URI: " + uriString);
                } else {
                    Log.d(TAG, "Permission OK for SAF URI: " + uriString);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error checking permission for URI: " + uriString, e);
            }
        }
        
        // Clean up orphaned permissions if we're approaching the limit
        if (grantedPermissions.size() >= 110) {
            Set<String> storedUriSet = new HashSet<>(storedUris);
            
            for (UriPermission permission : grantedPermissions) {
                String uriString = permission.getUri().toString();
                if (!storedUriSet.contains(uriString)) {
                    try {
                        context.getContentResolver().releasePersistableUriPermission(
                            permission.getUri(), 
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
                        Log.i(TAG, "Released orphaned permission: " + uriString);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to release permission: " + uriString, e);
                    }
                }
            }
        }
        
        Log.i(TAG, "SAF permission check complete");
    }
    
    private static boolean isUriAccessible(Context context, String uriString, List<UriPermission> grantedPermissions) {
        try {
            Uri uri = Uri.parse(uriString);
            
            // Check if we have persistent permission
            boolean hasPermission = grantedPermissions.stream()
                .anyMatch(perm -> perm.getUri().equals(uri) && perm.isReadPermission());
            
            if (!hasPermission) {
                Log.w(TAG, "No persistent permission found for URI: " + uriString);
                return false;
            }
            
            // Test actual access - be more lenient
            try {
                DocumentFile doc = DocumentFile.fromTreeUri(context, uri);
                boolean accessible = doc != null && doc.exists();
                if (!accessible) {
                    Log.w(TAG, "DocumentFile not accessible for URI: " + uriString);
                }
                return accessible;
            } catch (Exception e) {
                Log.w(TAG, "Error testing DocumentFile access for URI: " + uriString, e);
                // Don't fail just because of access test - permission exists
                return true;
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Error checking URI accessibility: " + uriString, e);
            return false;
        }
    }
    
    private static void cleanupOrphanedPermissions(Context context, Set<String> validUris, List<UriPermission> grantedPermissions) {
        Set<String> validUriSet = new HashSet<>(validUris);
        
        for (UriPermission permission : grantedPermissions) {
            String uriString = permission.getUri().toString();
            if (!validUriSet.contains(uriString)) {
                try {
                    context.getContentResolver().releasePersistableUriPermission(
                        permission.getUri(), 
                        permission.isReadPermission() ? android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION : 0
                    );
                    Log.i(TAG, "Released orphaned permission: " + uriString);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to release permission: " + uriString, e);
                }
            }
        }
    }
    
    public static boolean canAddMorePermissions(Context context) {
        List<UriPermission> grantedPermissions = context.getContentResolver().getPersistedUriPermissions();
        return grantedPermissions.size() < MAX_PERMISSIONS;
    }
    
    public static int getPermissionCount(Context context) {
        return context.getContentResolver().getPersistedUriPermissions().size();
    }
}
