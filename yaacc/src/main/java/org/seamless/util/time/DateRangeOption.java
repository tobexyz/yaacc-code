 /*
 * Copyright (C) 2012 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.seamless.util.time;

import java.io.Serializable;

/**
 * @author Christian Bauer
 */
public enum DateRangeOption implements Serializable {

    ALL("All dates", DateRange.Preset.ALL.getDateRange()),
    MONTH_TO_DATE("Month to date", DateRange.Preset.MONTH_TO_DATE.getDateRange()),
    YEAR_TO_DATE("Year to date", DateRange.Preset.YEAR_TO_DATE.getDateRange()),
    LAST_MONTH("Last month", DateRange.Preset.LAST_MONTH.getDateRange()),
    LAST_YEAR("Last year", DateRange.Preset.LAST_YEAR.getDateRange()),
    CUSTOM("Custom dates", null);

    String label;
    DateRange dateRange;

    DateRangeOption(String label, DateRange dateRange) {
        this.label = label;
        this.dateRange = dateRange;
    }

    public String getLabel() {
        return label;
    }

    public DateRange getDateRange() {
        return dateRange;
    }
}
