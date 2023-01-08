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
import java.util.Date;

/**
 * @author Christian Bauer
 */
public class DateRange implements Serializable {

    public static enum Preset {

        ALL(new DateRange(null)),
        YEAR_TO_DATE(new DateRange(new Date(getCurrentYear(), 0, 1))),
        MONTH_TO_DATE(new DateRange(new Date(getCurrentYear(), getCurrentMonth(), 1))),
        LAST_MONTH(getMonthOf(new Date(getCurrentYear(), getCurrentMonth() - 1, 1))),
        LAST_YEAR(new DateRange(new Date(getCurrentYear() - 1, 0, 1), new Date(getCurrentYear() - 1, 11, 31)));

        DateRange dateRange;

        Preset(DateRange dateRange) {
            this.dateRange = dateRange;
        }

        public DateRange getDateRange() {
            return dateRange;
        }

    }

    protected Date start;
    protected Date end;

    public DateRange() {
    }

    public DateRange(Date start) {
        this.start = start;
    }

    public DateRange(Date start, Date end) {
        this.start = start;
        this.end = end;
    }

    public DateRange(String startUnixtime, String endUnixtime) throws NumberFormatException {
        if (startUnixtime != null) {
            this.start = new Date(Long.valueOf(startUnixtime));
        }
        if (endUnixtime != null) {
            this.end = new Date(Long.valueOf(endUnixtime));
        }
    }

    public Date getStart() {
        return start;
    }

    public Date getEnd() {
        return end;
    }

    public boolean isStartAfter(Date date) {
        return getStart() != null && getStart().getTime() > date.getTime();
    }

    public Date getOneDayBeforeStart() {
        if (getStart() == null) {
            throw new IllegalStateException("Can't get day before start date because start date is null");
        }
        return new Date(getStart().getTime() - 86400000);
    }

    public static int getCurrentYear() {
        return new Date().getYear();
    }

    public static int getCurrentMonth() {
        return new Date().getMonth();
    }

    public static int getCurrentDayOfMonth() {
        return new Date().getDate();
    }

    public boolean hasStartOrEnd() {
        return getStart() != null || getEnd() != null;
    }

    public static int getDaysInMonth(Date date) {
        int month = date.getMonth();
        int year = date.getYear() + 1900;
        boolean isLeapYear = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        int[] daysInMonth = {31, isLeapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return daysInMonth[month];
    }

    public static DateRange getMonthOf(Date date) {
        return new DateRange(
            new Date(date.getYear(), date.getMonth(), 1),
            new Date(date.getYear(), date.getMonth(), getDaysInMonth(date))
        );
    }

    public boolean isInRange(Date date) {
        return getStart() != null && getStart().getTime() < date.getTime()
            && (getEnd() == null || getEnd().getTime() > date.getTime());
    }

    public boolean isValid() {
        return getStart() != null && (getEnd() == null || getStart().getTime() <= getEnd().getTime());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DateRange dateRange = (DateRange) o;

        if (end != null ? !end.equals(dateRange.end) : dateRange.end != null) return false;
        if (start != null ? !start.equals(dateRange.start) : dateRange.start != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = start != null ? start.hashCode() : 0;
        result = 31 * result + (end != null ? end.hashCode() : 0);
        return result;
    }

    public static DateRange valueOf(String s) {
        if (!s.contains("dr=")) return null;
        String dr = s.substring(s.indexOf("dr=") + 3);
        dr = dr.substring(0, dr.indexOf(";"));
        String[] split = dr.split(",");
        if (split.length != 2) return null;
        try {
            return new DateRange(
                !split[0].equals("0") ? new Date(Long.valueOf(split[0])) : null,
                !split[1].equals("0") ? new Date(Long.valueOf(split[1])) : null
            );
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("dr=");
        sb.append(getStart() != null ? getStart().getTime() : "0");
        sb.append(",");
        sb.append(getEnd() != null ? getEnd().getTime() : "0");
        sb.append(";");
        return sb.toString();
    }
}
