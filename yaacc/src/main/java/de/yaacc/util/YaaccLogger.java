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
package de.yaacc.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

public class YaaccLogger {
    
    private static final int VERBOSE = 2;
    private static final int DEBUG = 3;
    private static final int INFO = 4;
    private static final int WARN = 5;
    private static final int ERROR = 6;
    private static final int FATAL = 7;
    
    private static Context appContext;
    private static int currentLogLevel = ERROR;
    
    public static void initialize(Context context) {
        appContext = context.getApplicationContext();
        updateLogLevel();
    }
    
    public static void updateLogLevel() {
        if (appContext == null) return;
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(appContext);
        String logLevel = preferences.getString("settings_log_level_key", "E");
        currentLogLevel = parseLogLevel(logLevel);
    }
    
    private static int parseLogLevel(String level) {
        switch (level) {
            case "V": return VERBOSE;
            case "D": return DEBUG;
            case "I": return INFO;
            case "W": return WARN;
            case "E": return ERROR;
            case "F": return FATAL;
            default: return ERROR;
        }
    }
    
    public static void v(String tag, String msg) {
        if (currentLogLevel <= VERBOSE) {
            Log.v(tag, msg);
        }
    }
    
    public static void v(String tag, String msg, Throwable tr) {
        if (currentLogLevel <= VERBOSE) {
            Log.v(tag, msg, tr);
        }
    }
    
    public static void d(String tag, String msg) {
        if (currentLogLevel <= DEBUG) {
            Log.d(tag, msg);
        }
    }
    
    public static void d(String tag, String msg, Throwable tr) {
        if (currentLogLevel <= DEBUG) {
            Log.d(tag, msg, tr);
        }
    }
    
    public static void i(String tag, String msg) {
        if (currentLogLevel <= INFO) {
            Log.i(tag, msg);
        }
    }
    
    public static void i(String tag, String msg, Throwable tr) {
        if (currentLogLevel <= INFO) {
            Log.i(tag, msg, tr);
        }
    }
    
    public static void w(String tag, String msg) {
        if (currentLogLevel <= WARN) {
            Log.w(tag, msg);
        }
    }
    
    public static void w(String tag, String msg, Throwable tr) {
        if (currentLogLevel <= WARN) {
            Log.w(tag, msg, tr);
        }
    }
    
    public static void w(String tag, Throwable tr) {
        if (currentLogLevel <= WARN) {
            Log.w(tag, tr);
        }
    }
    
    public static void e(String tag, String msg) {
        if (currentLogLevel <= ERROR) {
            Log.e(tag, msg);
        }
    }
    
    public static void e(String tag, String msg, Throwable tr) {
        if (currentLogLevel <= ERROR) {
            Log.e(tag, msg, tr);
        }
    }
}
