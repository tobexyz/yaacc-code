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
package org.seamless.xhtml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * @author Christian Bauer
 */
public class Option {

    private String key;
    private String[] values;

    public Option(String key, String[] values) {
        this.key = key;
        this.values = values;
    }

    public static Option[] fromString(String string) {
        if (string == null || string.length() == 0) return new Option[0];

        List<Option> options = new ArrayList();

        try {

            String[] fields = string.split(";");
            for (String field : fields) {
                field = field.trim();
                if (!field.contains(":")) continue;

                String[] keyValues = field.split(":");
                if (keyValues.length != 2) continue;

                String key = keyValues[0].trim();
                String[] values = keyValues[1].split(",");
                List<String> cleanValues = new ArrayList<String>();
                for (String s : values) {
                    String value = s.trim();
                    if (value.length() > 0)
                        cleanValues.add(value);
                }

                options.add(
                        new Option(key, cleanValues.toArray(new String[cleanValues.size()]))
                );
            }

            return options.toArray(new Option[options.size()]);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Can't parse options string: " + string, ex);
        }
    }

    public String getKey() {
        return key;
    }

    public String[] getValues() {
        return values;
    }

    public boolean isTrue() {
        return getValues().length == 1 && getValues()[0].toLowerCase().equals("true");
    }

    public boolean isFalse() {
        return getValues().length == 1 && getValues()[0].toLowerCase().equals("false");
    }

    public String getFirstValue() {
        return getValues()[0];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getKey()).append(": ");
        Iterator<String> it = Arrays.asList(getValues()).iterator();
        while (it.hasNext()) {
            sb.append(it.next());
            if(it.hasNext())sb.append(", ");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Option that = (Option) o;

        if (!key.equals(that.key)) return false;
        if (!Arrays.equals(values, that.values)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = key.hashCode();
        result = 31 * result + Arrays.hashCode(values);
        return result;
    }
}
