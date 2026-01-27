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
        Set<String> selectedUris = MediaPathFilter.getSelectedSafPathes(context);
        List<UriPermission> grantedPermissions = context.getContentResolver().getPersistedUriPermissions();
        
        Set<String> validUris = new HashSet<>();
        Set<String> validSelectedUris = new HashSet<>();
        
        // Check each stored URI
        for (String uriString : storedUris) {
            if (isUriAccessible(context, uriString, grantedPermissions)) {
                validUris.add(uriString);
                if (selectedUris.contains(uriString)) {
                    validSelectedUris.add(uriString);
                }
            } else {
                Log.w(TAG, "Removing inaccessible SAF URI: " + uriString);
            }
        }
        
        // Update stored URIs if any were removed
        if (validUris.size() != storedUris.size()) {
            MediaPathFilter.saveSafPathes(context, validUris);
        }
        if (validSelectedUris.size() != selectedUris.size()) {
            MediaPathFilter.saveSelectedSafPathes(context, validSelectedUris);
        }
        
        // Clean up orphaned permissions
        cleanupOrphanedPermissions(context, validUris, grantedPermissions);
        
        Log.i(TAG, "SAF validation complete. Valid URIs: " + validUris.size() + 
              ", Granted permissions: " + grantedPermissions.size());
    }
    
    private static boolean isUriAccessible(Context context, String uriString, List<UriPermission> grantedPermissions) {
        try {
            Uri uri = Uri.parse(uriString);
            
            // Check if we have persistent permission
            boolean hasPermission = grantedPermissions.stream()
                .anyMatch(perm -> perm.getUri().equals(uri) && perm.isReadPermission());
            
            if (!hasPermission) {
                return false;
            }
            
            // Test actual access
            DocumentFile doc = DocumentFile.fromTreeUri(context, uri);
            return doc != null && doc.exists() && doc.canRead();
            
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
