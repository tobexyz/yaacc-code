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
package de.yaacc.yxc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads and recalls the presets of a device. A recall is never sent from the list the screen shows: the list is read again
 * right before, and the entry must still be in it at the same position with the same name (the user may have changed the
 * presets with the manufacturer's app meanwhile). Pure over a {@link YxcClient}; the caller runs it off the main thread.
 */
public final class YxcPresetActions {

    /** What the device lists, per kind. */
    public static final class Lists {
        private final List<YxcPresets.Entry> recent;
        private final List<YxcPresets.Entry> net;
        private final List<YxcPresets.Entry> fm;
        private final List<YxcPresets.Entry> dab;
        private final boolean failed;

        Lists(List<YxcPresets.Entry> recent, List<YxcPresets.Entry> net, List<YxcPresets.Entry> fm,
              List<YxcPresets.Entry> dab, boolean failed) {
            this.recent = recent;
            this.net = net;
            this.fm = fm;
            this.dab = dab;
            this.failed = failed;
        }

        public List<YxcPresets.Entry> recent() {
            return recent;
        }

        public List<YxcPresets.Entry> net() {
            return net;
        }

        public List<YxcPresets.Entry> fm() {
            return fm;
        }

        public List<YxcPresets.Entry> dab() {
            return dab;
        }

        public boolean isEmpty() {
            return recent.isEmpty() && net.isEmpty() && fm.isEmpty() && dab.isEmpty();
        }

        /** True when nothing could be read because the device did not answer. */
        public boolean isFailed() {
            return failed;
        }
    }

    public enum Result {
        DONE, NOT_AVAILABLE, FAILED
    }

    private YxcPresetActions() {
    }

    /** Reads the lists the device says it has ({@code caps}); a list that cannot be read is left out. */
    public static Lists load(YxcClient client, YxcSourceCapabilities caps) {
        if (caps == null) {
            return new Lists(none(), none(), none(), none(), false);
        }
        int asked = 0;
        int failed = 0;
        List<YxcPresets.Entry> recent = none();
        List<YxcPresets.Entry> net = none();
        List<YxcPresets.Entry> fm = none();
        List<YxcPresets.Entry> dab = none();
        if (caps.hasRecent()) {
            asked++;
            try {
                recent = client.getRecentItems();
            } catch (YxcException e) {
                failed++;
            }
        }
        if (caps.getNetPresets() > 0) {
            asked++;
            try {
                net = client.getNetPresets();
            } catch (YxcException e) {
                failed++;
            }
        }
        if (caps.hasFm() && caps.getTunerPresets() > 0) {
            asked++;
            try {
                fm = client.getTunerPresets("fm");
            } catch (YxcException e) {
                failed++;
            }
        }
        if (caps.hasDab() && caps.getTunerPresets() > 0) {
            asked++;
            try {
                dab = client.getTunerPresets("dab");
            } catch (YxcException e) {
                failed++;
            }
        }
        return new Lists(recent, net, fm, dab, asked > 0 && failed == asked);
    }

    /** Recalls the entry if the device still lists it as it was shown. */
    public static Result recall(YxcClient client, YxcPresets.Entry entry) {
        try {
            List<YxcPresets.Entry> fresh;
            switch (entry.getKind()) {
                case NET_PRESET:
                    fresh = client.getNetPresets();
                    break;
                case RECENT:
                    fresh = client.getRecentItems();
                    break;
                case FM_PRESET:
                    fresh = client.getTunerPresets("fm");
                    break;
                case DAB_PRESET:
                    fresh = client.getTunerPresets("dab");
                    break;
                default:
                    return Result.NOT_AVAILABLE;
            }
            if (!fresh.contains(entry)) {
                return Result.NOT_AVAILABLE;
            }
            switch (entry.getKind()) {
                case NET_PRESET:
                    client.recallNetPreset(entry.getNumber());
                    break;
                case RECENT:
                    client.recallRecentItem(entry.getNumber());
                    break;
                default:
                    // the tuner presets are recalled on the tuner input (a recall may not switch the zone to it)
                    if (!YxcNowPlaying.TUNER_INPUT.equalsIgnoreCase(client.getZoneStatus(YxcDeviceState.MAIN_ZONE).getInput())) {
                        client.setInput(YxcDeviceState.MAIN_ZONE, YxcNowPlaying.TUNER_INPUT, "");
                    }
                    client.recallTunerPreset(entry.getKind() == YxcPresets.Kind.FM_PRESET ? "fm" : "dab", entry.getNumber());
                    break;
            }
            return Result.DONE;
        } catch (YxcException | RuntimeException e) {
            return Result.FAILED;
        }
    }

    private static List<YxcPresets.Entry> none() {
        return Collections.unmodifiableList(new ArrayList<YxcPresets.Entry>());
    }
}
