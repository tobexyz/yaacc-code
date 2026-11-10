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
package de.yaacc.multiroom.control;

/**
 * The limits that keep a hostile or broken server from using the phone's memory or time. They are the numbers of the
 * "Security rules" of the design; every parser and the connection code read them from here.
 */
public final class SnapcastLimits {

    /** Groups taken from a status; further groups are ignored. */
    public static final int MAX_GROUPS = 64;
    /** Clients taken per group. */
    public static final int MAX_CLIENTS_PER_GROUP = 256;
    /** Streams taken from a status. */
    public static final int MAX_STREAMS = 64;
    /** Entries of a stream URI query taken. */
    public static final int MAX_QUERY_ENTRIES = 32;
    /** Characters of any string taken from the server (names, ids, URIs, error texts); longer ones are cut. */
    public static final int MAX_STRING_LENGTH = 1024;
    /** Nesting depth of a JSON line; real answers stay below 10. */
    public static final int MAX_DEPTH = 32;
    /** Structural tokens (opening brackets, commas, colons) of a JSON line. */
    public static final int MAX_TOKENS = 10_000;
    /** Elements of a JSON-RPC batch. */
    public static final int MAX_BATCH = 64;
    /** Bytes of a line from the server. */
    public static final int MAX_LINE_BYTES = 1 << 20;
    /** Bytes of a request we send. */
    public static final int MAX_REQUEST_BYTES = 256 * 1024;
    /** Requests waiting for an answer at the same time. */
    public static final int MAX_PENDING_CALLS = 32;
    /** Notifications waiting for the listeners; further ones are dropped and reported as lost. */
    public static final int MAX_QUEUED_NOTIFICATIONS = 256;
    /** Characters of a JSON-RPC method name. */
    public static final int MAX_METHOD_LENGTH = 64;
    /** The largest latency setting of a client, in milliseconds. */
    public static final int MAX_LATENCY_MS = 10_000;

    private SnapcastLimits() {
    }
}
