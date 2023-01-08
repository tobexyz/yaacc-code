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
package org.seamless.util;

/**
 * @author Christian Bauer
 */
public class OS {

    public static boolean checkForLinux() {
        return checkForPresence("os.name", "linux");
    }

    public static boolean checkForHp() {
        return checkForPresence("os.name", "hp");
    }

    public static boolean checkForSolaris() {
        return checkForPresence("os.name", "sun");
    }

    public static boolean checkForWindows() {
        return checkForPresence("os.name", "win");
    }

    public static boolean checkForMac() {
        return checkForPresence("os.name", "mac");
    }

    private static boolean checkForPresence(String key, String value) {
        try {
            String tmp = System.getProperty(key);
            return tmp != null && tmp.trim().toLowerCase().startsWith(value);
        }
        catch (Throwable t) {
            return false;
        }
    }

}
