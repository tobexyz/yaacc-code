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
/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
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

package de.yaacc.upnp.registry;

import org.fourthline.cling.model.ExpirationDetails;

/**
 * Internal class, required by {@link RegistryImpl}.
 *
 * @author Christian Bauer
 */
class RegistryItem<K, I> {

    private K key;
    private I item;
    private ExpirationDetails expirationDetails = new ExpirationDetails();

    RegistryItem(K key) {
        this.key = key;
    }

    RegistryItem(K key, I item, int maxAgeSeconds) {
        this.key = key;
        this.item = item;
        this.expirationDetails = new ExpirationDetails(maxAgeSeconds);
    }

    public K getKey() {
        return key;
    }

    public I getItem() {
        return item;
    }

    public ExpirationDetails getExpirationDetails() {
        return expirationDetails;
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RegistryItem that = (RegistryItem) o;

        return key.equals(that.key);
    }

    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return "(" + getClass().getSimpleName() + ") " + getExpirationDetails() + " KEY: " + getKey() + " ITEM: " + getItem();
    }
}
