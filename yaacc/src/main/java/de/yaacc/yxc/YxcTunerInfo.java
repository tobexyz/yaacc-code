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

import java.util.Objects;

import org.json.JSONObject;

/**
 * Answer of {@code tuner/getPlayInfo}: what the tuner of the device plays. The answer is there at any time, also while
 * another input is selected (then it holds old values), so it only means something while the zone input is the tuner.
 * Frequencies are in kHz (87550 is 87.55 MHz). Texts are untrusted and padded with blanks.
 */
public final class YxcTunerInfo {
    public static final String BAND_FM = "fm";
    public static final String BAND_DAB = "dab";

    private final String band;
    private final boolean autoScan;
    private final Integer fmFrequencyKhz;
    private final Integer fmPreset;
    private final Boolean fmTuned;
    private final String rdsService;
    private final String rdsTextA;
    private final String rdsTextB;
    private final Integer dabPreset;
    private final String dabServiceLabel;
    private final String dabDls;

    private YxcTunerInfo(JSONObject json) {
        band = YxcJson.string(json, "band");
        autoScan = YxcJson.flag(json, "auto_scan");
        JSONObject fm = YxcJson.object(json, "fm");
        fmFrequencyKhz = fm == null ? null : YxcJson.integer(fm, "freq");
        fmPreset = fm == null ? null : YxcJson.integer(fm, "preset");
        fmTuned = fm == null ? null : YxcJson.bool(fm, "tuned");
        JSONObject rds = YxcJson.object(json, "rds");
        rdsService = rds == null ? null : YxcJson.string(rds, "program_service");
        rdsTextA = rds == null ? null : YxcJson.string(rds, "radio_text_a");
        rdsTextB = rds == null ? null : YxcJson.string(rds, "radio_text_b");
        JSONObject dab = YxcJson.object(json, "dab");
        dabPreset = dab == null ? null : YxcJson.integer(dab, "preset");
        dabServiceLabel = dab == null ? null : YxcJson.string(dab, "service_label");
        dabDls = dab == null ? null : YxcJson.string(dab, "dls");
    }

    static YxcTunerInfo fromJson(JSONObject json) {
        return new YxcTunerInfo(json);
    }

    /** @return {@code fm} or {@code dab} (the words of the device), {@code null} when not given */
    public String getBand() {
        return band;
    }

    public boolean isDab() {
        return BAND_DAB.equalsIgnoreCase(band);
    }

    public boolean isFm() {
        return BAND_FM.equalsIgnoreCase(band);
    }

    /** @return true while the tuner seeks a station by itself ({@code auto_scan}) */
    public boolean isScanning() {
        return autoScan;
    }

    /** @return the FM frequency in kHz, {@code null} when not given */
    public Integer getFmFrequencyKhz() {
        return fmFrequencyKhz;
    }

    /** @return the FM preset number (1-based), 0 or {@code null} when the station is not a preset */
    public Integer getFmPreset() {
        return fmPreset;
    }

    public Boolean getFmTuned() {
        return fmTuned;
    }

    public String getRdsService() {
        return rdsService;
    }

    public String getRdsTextA() {
        return rdsTextA;
    }

    public String getRdsTextB() {
        return rdsTextB;
    }

    public Integer getDabPreset() {
        return dabPreset;
    }

    /** @return the name of the DAB service, padded with blanks by the device */
    public String getDabServiceLabel() {
        return dabServiceLabel;
    }

    /** @return the dynamic label (the text a DAB service sends along), may be empty */
    public String getDabDls() {
        return dabDls;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof YxcTunerInfo)) {
            return false;
        }
        YxcTunerInfo other = (YxcTunerInfo) o;
        return autoScan == other.autoScan && Objects.equals(band, other.band)
                && Objects.equals(fmFrequencyKhz, other.fmFrequencyKhz) && Objects.equals(fmPreset, other.fmPreset)
                && Objects.equals(fmTuned, other.fmTuned) && Objects.equals(rdsService, other.rdsService)
                && Objects.equals(rdsTextA, other.rdsTextA) && Objects.equals(rdsTextB, other.rdsTextB)
                && Objects.equals(dabPreset, other.dabPreset) && Objects.equals(dabServiceLabel, other.dabServiceLabel)
                && Objects.equals(dabDls, other.dabDls);
    }

    @Override
    public int hashCode() {
        return Objects.hash(band, autoScan, fmFrequencyKhz, fmPreset, fmTuned, rdsService, rdsTextA, rdsTextB, dabPreset,
                dabServiceLabel, dabDls);
    }

    /** Station names and texts are not part of a log line. */
    @Override
    public String toString() {
        return "YxcTunerInfo{band=" + band + ", scanning=" + autoScan + "}";
    }
}
