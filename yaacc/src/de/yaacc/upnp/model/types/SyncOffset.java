package de.yaacc.upnp.model.types;/*
* Copyright (C) 2014 www.yaacc.de
*
* This program is free software; you can redistribute it and/or
* modify it under the terms of the GNU General Public License
* as published by the Free Software Foundation; either version 3
* of the License, or (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program; if not, write to the Free Software
* Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

import android.util.Log;

import org.fourthline.cling.model.types.Datatype;
import org.fourthline.cling.model.types.InvalidValueException;

import java.util.Locale;

/**
 * Representation of the upnp type SyncOffset.
 * Format of the upnp type:
 * duration ::= ['-']'P' time
 * time::= HH ':' MM ':' SS'.' MilliS MicroS NanoS
 * HH ::= 2DIGIT (* 00 - 23 *)
 * MM ::= 2DIGIT (* 00 - 59 *)
 * SS ::= 2DIGIT (* 00 - 59 *)
 * MilliS ::= 3DIGIT
 * MicroS ::= 3DIGIT
 * NanoS  ::= 3DIGIT
 *
 * @author Tobias Schoene (TheOpenBit)
 */
public class SyncOffset implements Datatype{


    private int hour = 0;
    private int minute = 0;
    private int second = 0;
    private int millis = 0;
    private int micros = 0;
    private int nanos = 0;


    private boolean positive = true;

    public SyncOffset(boolean positive, int hour, int minute, int second, int millis, int micros, int nanos) {
        if (!(hour >= 0 && hour <= 23)) {
            throw new IllegalArgumentException("hour must fit interval 0-23, but was: " + hour);
        }
        if (!(minute >= 0 && minute <= 59)) {
            throw new IllegalArgumentException("minute must fit interval 0-60, but was: " + minute);
        }

        if (!(second >= 0 && second <= 59)) {
            throw new IllegalArgumentException("second must fit interval 0-60, but was: " + second);

        }

        if (!(millis >= 0 && millis <= 999)) {
            throw new IllegalArgumentException("millis must fit interval 0-60, but was: " + millis);
        }

        if (!(micros >= 0 && micros <= 999)) {
            throw new IllegalArgumentException("micros must fit interval 0-999, but was: " + micros);
        }

        if (!(nanos >= 0 && nanos <= 999)) {
            throw new IllegalArgumentException("nanos must fit interval 0-999, but was: " + nanos);
        }

        this.positive = positive;
        this.hour = hour;
        this.minute = minute;
        this.second = second;
        this.millis = millis;
        this.micros = micros;
        this.nanos = nanos;


    }

    public SyncOffset(){

    }

    public SyncOffset(long nanos) {
        if(nanos < 0) {
            positive = false;
        }
        hour = (int) (nanos / (60L * 60L * 1000L * 1000L *1000L));
        long rest = nanos % (60L * 60L * 1000L * 1000L *1000L);
        minute = (int) (rest / (60L  * 1000L * 1000L * 1000L));
        rest = rest % (60L  * 1000L * 1000L * 1000L);
        second = (int) (rest / (1000L * 1000L * 1000L));
        rest = rest % (1000L * 1000L * 1000L);
        millis = (int) (rest / (1000L * 1000L));
        rest = rest % (1000L * 1000L);
        micros = (int) (rest / 1000L);
        this.nanos = (int) (rest % 1000L);
    }

    public SyncOffset(String offset) {
        if (offset == null || offset.equals("")) {
            return;
        }
        positive = !offset.startsWith("-");
        String toBeParsed = offset;
        if (positive && toBeParsed.startsWith("P")) {
            toBeParsed = toBeParsed.substring(1);
        } else if (toBeParsed.startsWith("-P")) {
            toBeParsed = toBeParsed.substring(2);
        } else {
            Log.w(this.getClass().getName(), "Can't parse offset format: " + offset + " ignoring it");
            return;
        }
        //Minimum requiered format
        if (toBeParsed.matches("^\\d{2}:\\d{2}:\\d{2}.*")) {
            hour = Integer.parseInt(toBeParsed.substring(0, 2));
            if (hour < 0 || hour > 23) {
                hour = 0;
                Log.w(this.getClass().getName(), "Can't parse offset hour format: " + offset + " ignoring it");
            }
            toBeParsed = toBeParsed.substring(3);
            minute = Integer.parseInt(toBeParsed.substring(0, 2));
            if (minute < 0 || minute > 59) {
                minute = 0;
                Log.w(this.getClass().getName(), "Can't parse offset minute format: " + offset + " ignoring it");
            }
            toBeParsed = toBeParsed.substring(3);
            second = Integer.parseInt(toBeParsed.substring(0, 2));
            if (second < 0 || second > 59) {
                second = 0;
                Log.w(this.getClass().getName(), "Can't parse offset second format: " + offset + " ignoring it");
            }
            toBeParsed = toBeParsed.substring(2);
            if (toBeParsed.indexOf('.') > 0 || (toBeParsed.indexOf('.') == -1 && toBeParsed.length() > 0)) {
                Log.w(this.getClass().getName(), "Can't parse offset second format: " + offset + " ignoring it");
                second = 0;
                if (toBeParsed.indexOf('.') > 0) {
                    toBeParsed = toBeParsed.substring(toBeParsed.indexOf('.'));
                }
            }
        } else {
            Log.w(this.getClass().getName(), "Can't parse offset time format: " + offset + " ignoring it");
            return;
        }
        if (toBeParsed.matches("^[.]\\d{3}.*")) {
            millis = Integer.parseInt(toBeParsed.substring(1, 4));
            if (millis < 0 || millis > 999) {
                millis = 0;
                Log.w(this.getClass().getName(), "Can't parse offset millis format: " + offset + " ignoring it");
            }
            toBeParsed = toBeParsed.substring(4);
            if (!toBeParsed.startsWith(" ") && toBeParsed.indexOf(' ') > -1) {
                toBeParsed = toBeParsed.substring(toBeParsed.indexOf(' '));
                millis = 0;
                Log.w(this.getClass().getName(), "Can't parse offset millis format: " + offset + " ignoring it");
            }
            if (toBeParsed.matches("^\\s\\d{3}.*")) {
                micros = Integer.parseInt(toBeParsed.substring(1, 4));
                if (micros < 0 || micros > 999) {
                    micros = 0;
                    Log.w(this.getClass().getName(), "Can't parse offset micros format: " + offset + " ignoring it");
                }
                toBeParsed = toBeParsed.substring(4);
                if (!toBeParsed.startsWith(" ") && toBeParsed.indexOf(' ') > -1) {
                    toBeParsed = toBeParsed.substring(toBeParsed.indexOf(' '));
                    micros = 0;
                    Log.w(this.getClass().getName(), "Can't parse offset micros format: " + offset + " ignoring it");
                }
                if (toBeParsed.matches("^\\s\\d{3}.*")) {
                    nanos = Integer.parseInt(toBeParsed.substring(1, 4));
                    if (nanos < 0 || nanos > 999) {
                        nanos = 0;
                        Log.w(this.getClass().getName(), "Can't parse offset nanos format: " + offset + " ignoring it");
                    }
                    toBeParsed = toBeParsed.substring(4);
                    if (toBeParsed.length() > 0) {
                        nanos = 0;
                        Log.w(this.getClass().getName(), "Can't parse offset nanos format: " + offset + " ignoring it");
                    }
                } else {
                    Log.w(this.getClass().getName(), "Can't parse offset nanos : " + offset + " ignoring it");
                }
            } else {
                Log.w(this.getClass().getName(), "Can't parse offset mircos : " + offset + " ignoring it");
            }
        } else {
            Log.w(this.getClass().getName(), "Can't parse offset sub second format: " + offset + " ignoring it");
            return;
        }

    }

    public java.lang.String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(positive ? "" : "-");
        sb.append("P");
        sb.append(String.format(Locale.ENGLISH,"%02d", hour));
        sb.append(":");
        sb.append(String.format(Locale.ENGLISH,"%02d", minute));
        sb.append(":");
        sb.append(String.format(Locale.ENGLISH,"%02d", second));
        sb.append(".");
        sb.append(String.format(Locale.ENGLISH,"%03d", millis));
        sb.append(" ");
        sb.append(String.format(Locale.ENGLISH,"%03d", micros));
        sb.append(" ");
        sb.append(String.format(Locale.ENGLISH,"%03d", nanos));
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SyncOffset that = (SyncOffset) o;

        if (hour != that.hour) return false;
        if (positive != that.positive) return false;
        if (micros != that.micros) return false;
        if (millis != that.millis) return false;
        if (minute != that.minute) return false;
        if (nanos != that.nanos) return false;
        if (second != that.second) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = hour;
        result = 31 * result + minute;
        result = 31 * result + second;
        result = 31 * result + millis;
        result = 31 * result + micros;
        result = 31 * result + nanos;
        result = 31 * result + (positive ? 1 : 0);
        return result;
    }

    public int getHour() {
        return hour;
    }

    public int getMinute() {
        return minute;
    }

    public int getSecond() {
        return second;
    }

    public int getMillis() {
        return millis;
    }

    public int getMicros() {
        return micros;
    }

    public int getNanos() {
        return nanos;
    }


    public boolean isPositive() {
        return positive;
    }


    public SyncOffset add(SyncOffset syncOffset) {
        return new SyncOffset((isPositive()?1:-1)*toNanos() + (syncOffset.isPositive()?1:-1)*syncOffset.toNanos());
    }

    public long toNanos() {
        return getNanos() +
                getMicros() * 1000L +
                getMillis() * 1000L *1000L +
                getSecond() * 1000L *1000L *1000L +
                getMinute() * 60L * 1000L * 1000L *1000L +
                getHour() * 60L * 60L * 1000L *1000L *1000L;
    }


    @Override
    public boolean isHandlingJavaType(Class aClass) {
        if (aClass == null) {
            return false;
        }
        return aClass.isAssignableFrom(getClass());
    }

    @Override
    public Builtin getBuiltin() {
        return null;
    }

    @Override
    public boolean isValid(Object o) {
        if(o == null || ! (o instanceof SyncOffset)) {
            return false;
        }
        //TODO to be implemented

        return true;
    }

    @Override
    public String getString(Object o) throws InvalidValueException {
        if(o == null || ! (o instanceof SyncOffset)) {
            return "";
        }
        return o.toString();
    }

    @Override
    public Object valueOf(String s) throws InvalidValueException {
        return new SyncOffset(s);
    }

    @Override
    public String getDisplayString() {
        return toString();
    }
}

