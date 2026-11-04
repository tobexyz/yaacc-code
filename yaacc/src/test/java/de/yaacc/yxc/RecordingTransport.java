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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory {@link YxcTransport}: answers by path, records what was sent. */
final class RecordingTransport implements YxcTransport {

    static final class Request {
        final String method;
        final String path;
        final String body;

        Request(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }

    final List<Request> requests = new ArrayList<>();
    private final Map<String, String> answers = new HashMap<>();
    private IOException failure;
    private String defaultAnswer = "{\"response_code\":0}";

    RecordingTransport answer(String path, String body) {
        answers.put(path, body);
        return this;
    }

    RecordingTransport answerEverythingWith(String body) {
        defaultAnswer = body;
        return this;
    }

    RecordingTransport failWith(IOException failure) {
        this.failure = failure;
        return this;
    }

    Request only() {
        if (requests.size() != 1) {
            throw new AssertionError("expected one request, got " + requests.size());
        }
        return requests.get(0);
    }

    @Override
    public String get(String path) throws IOException {
        return handle("GET", path, null);
    }

    @Override
    public String post(String path, String jsonBody) throws IOException {
        return handle("POST", path, jsonBody);
    }

    private String handle(String method, String path, String body) throws IOException {
        requests.add(new Request(method, path, body));
        if (failure != null) {
            throw failure;
        }
        String answer = answers.get(path);
        return answer != null ? answer : defaultAnswer;
    }
}
