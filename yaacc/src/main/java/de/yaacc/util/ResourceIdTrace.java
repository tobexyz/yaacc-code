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

import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * A debugging aid for the system message {@code Invalid resource ID 0x00000000} (a native log line without a Java stack). It is on
 * <b>only at the log level V</b> (the log level setting of the app); at every other level {@link #of} returns the resources unchanged
 * and nothing is wrapped. At level V the activities hand out a {@link Resources} that logs, at verbose level and with a stack, every
 * call that asks for a resource, a name, a package or a type of the id {@code 0}: the stack names the code (or the system code on
 * behalf of a view) that did it. At most {@value #MAX_REPORTS} reports are written per run, so a loop does not flood the log.
 *
 * <p>The wrapper shares the assets and the configuration of the resources it wraps and is made again when the configuration changes.
 */
public final class ResourceIdTrace {

    static final int MAX_REPORTS = 40;
    private static final String TAG = ResourceIdTrace.class.getName();

    private static final WeakHashMap<Resources, Traced> WRAPPED = new WeakHashMap<>();
    private static int reports;
    /** Where a report goes; the tests replace it. */
    static BiConsumer<String, Throwable> sink = (message, stack) -> YaaccLogger.v(TAG, message, stack);

    private ResourceIdTrace() {
    }

    /** @return the resources that report the id 0 (at log level V), else the given resources */
    @SuppressWarnings("deprecation")
    public static synchronized Resources of(Resources base) {
        if (base == null || !YaaccLogger.isVerbose() || base instanceof Traced) {
            return base;
        }
        Traced known = WRAPPED.get(base);
        if (known != null && known.matches(base)) {
            return known;
        }
        Traced wrapper = new Traced(base);
        WRAPPED.put(base, wrapper);
        return wrapper;
    }

    /** Reports a call with the id 0 (nothing for another id). */
    static synchronized void note(String method, int id) {
        if (id != 0 || reports >= MAX_REPORTS) {
            return;
        }
        reports++;
        sink.accept("resource id 0x00000000 asked for in Resources." + method + (reports == MAX_REPORTS ? " (last report of this run)" : ""),
                new Throwable("who asked for resource id 0"));
    }

    /** For the tests: starts counting again. */
    static synchronized void reset() {
        reports = 0;
    }

    @SuppressWarnings("deprecation")
    private static final class Traced extends Resources {
        private final android.content.res.Configuration configuration;

        Traced(Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            this.configuration = new android.content.res.Configuration(base.getConfiguration());
        }

        boolean matches(Resources base) {
            return configuration.equals(base.getConfiguration());
        }

        @Override
        public String getResourceName(int id) throws NotFoundException {
            note("getResourceName", id);
            return super.getResourceName(id);
        }

        @Override
        public String getResourceEntryName(int id) throws NotFoundException {
            note("getResourceEntryName", id);
            return super.getResourceEntryName(id);
        }

        @Override
        public String getResourcePackageName(int id) throws NotFoundException {
            note("getResourcePackageName", id);
            return super.getResourcePackageName(id);
        }

        @Override
        public String getResourceTypeName(int id) throws NotFoundException {
            note("getResourceTypeName", id);
            return super.getResourceTypeName(id);
        }

        @Override
        public void getValue(int id, TypedValue outValue, boolean resolveRefs) throws NotFoundException {
            note("getValue", id);
            super.getValue(id, outValue, resolveRefs);
        }

        @Override
        public CharSequence getText(int id) throws NotFoundException {
            note("getText", id);
            return super.getText(id);
        }

        @Override
        public String getString(int id) throws NotFoundException {
            note("getString", id);
            return super.getString(id);
        }

        @Override
        public Drawable getDrawable(int id, Theme theme) throws NotFoundException {
            note("getDrawable", id);
            return super.getDrawable(id, theme);
        }

        @Override
        public int getColor(int id, Theme theme) throws NotFoundException {
            note("getColor", id);
            return super.getColor(id, theme);
        }
    }
}
