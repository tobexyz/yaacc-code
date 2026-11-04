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
package de.yaacc.yxc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The settings of a zone the device lets us change, built <b>only from what the device reports</b>: a function missing
 * from the zone's {@code func_list}, an empty option list or a missing range gives no item. Pure; the sheet shows the items
 * and {@link YxcSettingsWriter} applies a change.
 */
public final class YxcSettings {

    public enum Id {
        POWER, INPUT, SOUND_PROGRAM,
        SLEEP, EQUALIZER_MODE, EQUALIZER_LOW, EQUALIZER_MID, EQUALIZER_HIGH,
        LINK_CONTROL, LINK_AUDIO_DELAY, LINK_AUDIO_QUALITY
    }

    public enum Kind {SWITCH, CHOICE, NUMBER}

    /** The most options one item offers; a device lists a handful, the rest of a huge list is not shown. */
    static final int MAX_OPTIONS = 64;
    /** The most steps the equalizer slider gets. */
    static final int MAX_BAND_STEPS = 1000;
    private static final java.util.regex.Pattern OPTION_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /** The sleep timer values the devices take, in minutes ({@code 0} is off). */
    private static final int[] SLEEP_MINUTES = {0, 30, 60, 90, 120};

    /** @return the sleep timer values in minutes, {@code 0} is off */
    public static List<Integer> sleepMinutes() {
        List<Integer> result = new ArrayList<>();
        for (int minutes : SLEEP_MINUTES) {
            result.add(minutes);
        }
        return result;
    }

    /** One choice: the word the device uses and the label to show. */
    public static final class Option {
        private final String value;
        private final String label;

        Option(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public String getValue() {
            return value;
        }

        public String getLabel() {
            return label;
        }
    }

    /** One setting with its current value. */
    public static final class Item {
        private final Id id;
        private final Kind kind;
        private final boolean on;
        private final List<Option> options;
        private final String current;
        private final int min;
        private final int max;
        private final int step;
        private final int number;
        private final boolean enabled;

        Item(Id id, Kind kind, boolean on, List<Option> options, String current) {
            this(id, kind, on, options, current, 0, 0, 1, 0, true);
        }

        Item(Id id, Kind kind, boolean on, List<Option> options, String current, int min, int max, int step, int number,
             boolean enabled) {
            this.id = id;
            this.kind = kind;
            this.on = on;
            this.options = Collections.unmodifiableList(options);
            this.current = current;
            this.min = min;
            this.max = max;
            this.step = step;
            this.number = number;
            this.enabled = enabled;
        }

        public int getMin() {
            return min;
        }

        public int getMax() {
            return max;
        }

        public int getStep() {
            return step;
        }

        /** @return for a number: the value now set */
        public int getNumber() {
            return number;
        }

        /** @return whether the item can be changed now (the equalizer bands only while the mode is manual) */
        public boolean isEnabled() {
            return enabled;
        }

        /** @return the number of slider steps from the minimum to the maximum (in long arithmetic, at most 1000) */
        public int getSteps() {
            return (int) (((long) max - min) / step);
        }

        /** @return the value at a slider position, in long arithmetic and inside the range */
        public int valueAt(int progress) {
            return (int) Math.max(min, Math.min(max, min + (long) progress * step));
        }

        boolean accepts(int value) {
            return value >= min && value <= max && ((long) value - min) % step == 0;
        }

        public Id getId() {
            return id;
        }

        public Kind getKind() {
            return kind;
        }

        /** @return for a switch: whether it is on */
        public boolean isOn() {
            return on;
        }

        public List<Option> getOptions() {
            return options;
        }

        /** @return for a choice: the value now set, {@code null} if the device did not say */
        public String getCurrent() {
            return current;
        }

        /** @return the label of the current choice, {@code null} when there is none */
        public String getCurrentLabel() {
            for (Option option : options) {
                if (option.value.equals(current)) {
                    return option.label;
                }
            }
            return current == null ? null : readable(current);
        }

        boolean offers(String value) {
            for (Option option : options) {
                if (option.value.equals(value)) {
                    return true;
                }
            }
            return false;
        }
    }

    private YxcSettings() {
    }

    /**
     * @param features the zone's features ({@code getFeatures})
     * @param status   the zone's status now
     * @param names    the device's own names, {@link YxcNameText#empty()} when it gave none
     * @return the items, in the order the sheet shows them
     */
    public static List<Item> build(YxcZoneFeatures features, YxcZoneStatus status, YxcNameText names) {
        List<Item> items = new ArrayList<>();
        if (features == null || status == null) {
            return items;
        }
        YxcNameText known = names == null ? YxcNameText.empty() : names;
        if (features.hasFunction("power")) {
            items.add(new Item(Id.POWER, Kind.SWITCH, status.isPowerOn(), Collections.<Option>emptyList(), null));
        }
        List<Option> inputs = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : features.getInputIds()) {
            // the group input is set by linking and by leaving a group, not by hand
            if (!YxcZoneFeatures.GROUP_INPUT_ID.equalsIgnoreCase(id) && usable(id, seen, inputs.size())) {
                String text = known.inputText(id);
                inputs.add(new Option(id, text != null ? text : readable(id)));
            }
        }
        if (!inputs.isEmpty()) {
            items.add(new Item(Id.INPUT, Kind.CHOICE, false, inputs, status.getInput()));
        }
        if (features.hasFunction("sound_program") && !features.getSoundProgramIds().isEmpty()) {
            List<Option> programs = new ArrayList<>();
            java.util.Set<String> seenPrograms = new java.util.HashSet<>();
            for (String id : features.getSoundProgramIds()) {
                if (usable(id, seenPrograms, programs.size())) {
                    String text = known.soundProgramText(id);
                    programs.add(new Option(id, text != null ? text : readable(id)));
                }
            }
            if (!programs.isEmpty()) {
                items.add(new Item(Id.SOUND_PROGRAM, Kind.CHOICE, false, programs, status.getSoundProgram()));
            }
        }
        addSleep(items, features, status);
        addEqualizer(items, features, status);
        addLink(items, features, status);
        return items;
    }

    private static void addSleep(List<Item> items, YxcZoneFeatures features, YxcZoneStatus status) {
        if (!features.hasFunction("sleep")) {
            return;
        }
        List<Option> options = new ArrayList<>();
        for (int minutes : sleepMinutes()) {
            options.add(new Option(String.valueOf(minutes), String.valueOf(minutes))); // the sheet words them ("Off", "30 min")
        }
        Integer now = status.getSleep();
        items.add(new Item(Id.SLEEP, Kind.CHOICE, false, options, now == null ? null : String.valueOf(now)));
    }

    private static void addEqualizer(List<Item> items, YxcZoneFeatures features, YxcZoneStatus status) {
        if (!features.hasFunction("equalizer")) {
            return;
        }
        YxcEqualizer equalizer = status.getEqualizer();
        List<String> modes = features.getEqualizerModes();
        // a mode row only where there is something to choose
        if (modes.size() > 1) {
            List<Option> options = new ArrayList<>();
            java.util.Set<String> seenModes = new java.util.HashSet<>();
            for (String mode : modes) {
                if (usable(mode, seenModes, options.size())) {
                    options.add(new Option(mode, readable(mode)));
                }
            }
            if (options.size() > 1) {
                items.add(new Item(Id.EQUALIZER_MODE, Kind.CHOICE, false, options, equalizer == null ? null : equalizer.getMode()));
            }
        }
        YxcRange range = features.getRange("equalizer");
        if (range == null || equalizer == null || range.getStep() <= 0 || range.getMax() < range.getMin()
                || ((long) range.getMax() - range.getMin()) / range.getStep() > MAX_BAND_STEPS) {
            return; // no range, or one that would make a slider of millions of steps
        }
        // the bands can be set while the mode is the manual one (or the device names no mode)
        boolean manual = equalizer.getMode() == null || "manual".equalsIgnoreCase(equalizer.getMode());
        addBand(items, Id.EQUALIZER_LOW, equalizer.getLow(), range, manual);
        addBand(items, Id.EQUALIZER_MID, equalizer.getMid(), range, manual);
        addBand(items, Id.EQUALIZER_HIGH, equalizer.getHigh(), range, manual);
    }

    private static void addBand(List<Item> items, Id id, Integer value, YxcRange range, boolean enabled) {
        if (value != null) {
            items.add(new Item(id, Kind.NUMBER, false, Collections.<Option>emptyList(), null, range.getMin(), range.getMax(),
                    range.getStep(), Math.max(range.getMin(), Math.min(range.getMax(), value)), enabled));
        }
    }

    private static void addLink(List<Item> items, YxcZoneFeatures features, YxcZoneStatus status) {
        addChoice(items, Id.LINK_CONTROL, features.hasFunction("link_control"), features.getLinkControlOptions(),
                status.getLinkControl());
        addChoice(items, Id.LINK_AUDIO_DELAY, features.hasFunction("link_audio_delay"), features.getLinkAudioDelayOptions(),
                status.getLinkAudioDelay());
        addChoice(items, Id.LINK_AUDIO_QUALITY, features.hasFunction("link_audio_quality"), features.getLinkAudioQualityOptions(),
                status.getLinkAudioQuality());
    }

    private static void addChoice(List<Item> items, Id id, boolean listed, List<String> values, String current) {
        if (!listed || values.isEmpty()) {
            return;
        }
        List<Option> options = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String value : values) {
            if (usable(value, seen, options.size())) {
                options.add(new Option(value, readable(value)));
            }
        }
        if (!options.isEmpty()) {
            items.add(new Item(id, Kind.CHOICE, false, options, current));
        }
    }

    /**
     * Whether an option id from the device is taken: the shape the device's calls accept (the client refuses anything
     * else anyway), not a duplicate, and the list is not already full.
     */
    private static boolean usable(String id, java.util.Set<String> seen, int taken) {
        return id != null && taken < MAX_OPTIONS && OPTION_ID.matcher(id).matches() && seen.add(id);
    }

    /** An id of the device made readable: {@code net_radio} becomes {@code Net radio}. */
    static String readable(String id) {
        String text = YxcNames.clean(id);
        if (text == null) {
            return "";
        }
        String spaced = text.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** @return the item with this id, {@code null} if the device does not offer it */
    public static Item find(List<Item> items, Id id) {
        for (Item item : items) {
            if (item.id == id) {
                return item;
            }
        }
        return null;
    }
}
