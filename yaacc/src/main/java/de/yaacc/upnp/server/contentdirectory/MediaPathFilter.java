package de.yaacc.upnp.server.contentdirectory;

import android.content.Context;
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
}
