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

import org.json.JSONObject;

/** Something that can make a JSON-RPC call: the connection, or a fake in a test. */
public interface SnapcastCaller {

    /**
     * Calls a method and waits for the answer.
     *
     * @param params the parameters, or null for none
     * @return the {@code result} object of the answer
     * @throws SnapcastException when the server answers an error or the call fails
     */
    JSONObject call(String method, JSONObject params) throws SnapcastException;

    /** Turns the result of an answer into the value of the call; see {@link #callOrdered}. */
    interface ResultHandler<T> {
        T handle(JSONObject result) throws SnapcastException;
    }

    /**
     * Calls a method and runs {@code handler} with the result <b>in the order of the lines of the connection</b>: after
     * every notification that arrived before the answer and before every one that arrived behind it. The handler runs
     * on the thread that delivers the notifications, and the call returns its value. Use it for everything that
     * changes the model (a status read, a change of our own), so that an answer can never overwrite a newer
     * notification. The default runs the handler on the calling thread (a fake without notifications needs no more).
     *
     * @throws SnapcastException when the call fails or the handler throws one
     */
    default <T> T callOrdered(String method, JSONObject params, ResultHandler<T> handler) throws SnapcastException {
        return handler.handle(call(method, params));
    }
}
