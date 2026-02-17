package de.yaacc.upnp.server.contentdirectory;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import de.yaacc.R;

public class MediaPathFilter {

    public static String makeLikeClause(String column, int len) {
        StringBuilder sb = new StringBuilder();
        sb.append(column);
        sb.append(" like ?");
        for (int i = 1; i < len; i++) {
            sb.append(" or ");
            sb.append(column);
            sb.append(" like ? ");
        }
        return sb.toString();
    }

    public static List<String> getMediaPathesForLikeClause(Context context) {
        return getMediaPathes(context).stream().map(it -> "%" + it + "%").collect(Collectors.toList());
    }

    public static Set<String> getMediaPathes(Context context) {
        boolean filterActive = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.settings_local_server_media_filter_chkbx), true);
        if (!filterActive) {
            Set<String> result = new HashSet<>();
            result.add("");
            return result;
        }
        return getMediaPathesRaw(context);
    }

    public static Set<String> getMediaPathesRaw(Context context) {
        Set<String> paths = PreferenceManager.getDefaultSharedPreferences(context).getStringSet(context.getString(R.string.settings_media_paths_pref_key), new HashSet<>());
        if (paths == null) {
            return new HashSet<>();
        }
        return new HashSet<>(paths);
    }

    public static void saveMediaPaths(Context context, Set<String> newPaths) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(context.getString(R.string.settings_media_paths_pref_key), newPaths);
        editor.apply();
    }

    public static void resetMediaPaths(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(context.getString(R.string.settings_media_paths_pref_key), new HashSet<>());
        editor.apply();
    }

    public static Set<String> getSafPathes(Context context) {
        Set<String> paths = PreferenceManager.getDefaultSharedPreferences(context).getStringSet(context.getString(R.string.settings_saf_tree_uris_pref_key), new HashSet<>());
        if (paths == null) {
            return new HashSet<>();
        }
        return new HashSet<>(paths);
    }

    public static void saveSafPathes(Context context, Set<String> newPathes) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(context.getString(R.string.settings_saf_tree_uris_pref_key), newPathes);
        editor.apply();
        
        // Notify service to restart SAF preloading
        Intent intent = new Intent("de.yaacc.SAF_PATHS_CHANGED");
        context.sendBroadcast(intent);
    }


    public static void resetSafPathes(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(context.getString(R.string.settings_saf_tree_uris_pref_key), new HashSet<>());
        editor.apply();
    }

    public static Set<String> getSelectedSafPathes(Context context) {
        Set<String> paths = PreferenceManager.getDefaultSharedPreferences(context).getStringSet(context.getString(R.string.settings_saf_tree_uris_selected_pref_key), new HashSet<>());
        if (paths == null) {
            return new HashSet<>();
        }
        return new HashSet<>(paths);
    }

    public static void saveSelectedSafPathes(Context context, Set<String> newPaths) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(context.getString(R.string.settings_saf_tree_uris_selected_pref_key), newPaths);
        editor.apply();
    }


    public static void resetSelectedSafPathes(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(context.getString(R.string.settings_saf_tree_uris_selected_pref_key), new HashSet<>());
        editor.apply();
    }
}
