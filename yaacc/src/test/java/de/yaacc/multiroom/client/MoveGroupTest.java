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
package de.yaacc.multiroom.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.yaacc.multiroom.control.SnapcastServerStatus;
import de.yaacc.multiroom.control.SnapcastTransport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * {@link ServerControl#moveGroup}: the request that changes the server for everybody in the group is sent only when the
 * picture the owner confirmed is still true, and every way it can go wrong is a result, never an exception and never a
 * call that changes anything. The server is a script behind an in-memory transport.
 */
public class MoveGroupTest {

    private static final String PHONE = "yaacc-0123456789abcdef";
    private static final String SHOWN_GROUP = "5cb59c23-1111-2222-3333-444455556666";
    private static final String STREAM = "YAACC spike";

    private static JSONObject fixture() throws Exception {
        String text = new String(Files.readAllBytes(Paths.get("src/test/resources/snapcast/status_phone_alone_on_idle_stream.json")),
                StandardCharsets.UTF_8);
        return new JSONObject(text).getJSONObject("result");
    }

    /** What the scripted server does with the two calls. */
    private static final class Script {
        JSONObject status;
        /** null: answer; otherwise the JSON-RPC error object to answer with. */
        JSONObject statusError;
        JSONObject setStreamError;
        boolean statusSilent;
        boolean setStreamSilent;
        boolean refuseConnect;
        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
        final List<JSONObject> setStreamParams = Collections.synchronizedList(new ArrayList<JSONObject>());

        String answer(String line) throws Exception {
            JSONObject request = new JSONObject(line);
            String method = request.getString("method");
            requests.add(method);
            Object id = request.get("id");
            JSONObject reply = new JSONObject().put("id", id).put("jsonrpc", "2.0");
            if ("Server.GetStatus".equals(method)) {
                if (statusSilent) {
                    return null;
                }
                if (statusError != null) {
                    return reply.put("error", statusError).toString();
                }
                return reply.put("result", status).toString();
            }
            if ("Group.SetStream".equals(method)) {
                setStreamParams.add(request.getJSONObject("params"));
                if (setStreamSilent) {
                    return null;
                }
                if (setStreamError != null) {
                    return reply.put("error", setStreamError).toString();
                }
                return reply.put("result", new JSONObject().put("stream_id", request.getJSONObject("params").getString("stream_id"))).toString();
            }
            return reply.put("error", new JSONObject().put("code", -32601).put("message", "Method not found")).toString();
        }
    }

    /** A connection whose server end is the script: a request line written is answered at once. */
    private static SnapcastTransport transport(Script script) {
        return connectTimeout -> {
            if (script.refuseConnect) {
                throw new IOException("connection refused");
            }
            final LinkedBlockingQueue<byte[]> toClient = new LinkedBlockingQueue<>();
            final boolean[] closed = new boolean[1];
            final int[] timeout = {0};
            return new SnapcastTransport.Connection() {
                @Override
                public InputStream getInputStream() {
                    return new InputStream() {
                        private byte[] current = new byte[0];
                        private int at;

                        @Override
                        public int read() throws IOException {
                            while (at >= current.length) {
                                if (closed[0]) {
                                    return -1;
                                }
                                try {
                                    byte[] next = toClient.poll(timeout[0] > 0 ? timeout[0] : 50, TimeUnit.MILLISECONDS);
                                    if (next != null) {
                                        current = next;
                                        at = 0;
                                    } else if (timeout[0] > 0) {
                                        throw new java.net.SocketTimeoutException("read timed out");
                                    }
                                } catch (InterruptedException e) {
                                    throw new IOException(e);
                                }
                            }
                            return current[at++] & 0xff;
                        }

                        /** Like a socket: what is there, as soon as there is something (not waiting for the array to fill). */
                        @Override
                        public int read(byte[] buffer, int offset, int length) throws IOException {
                            if (length == 0) {
                                return 0;
                            }
                            int first = read();
                            if (first < 0) {
                                return -1;
                            }
                            buffer[offset] = (byte) first;
                            int n = 1;
                            while (n < length && at < current.length) {
                                buffer[offset + n++] = current[at++];
                            }
                            return n;
                        }
                    };
                }

                @Override
                public OutputStream getOutputStream() {
                    return new OutputStream() {
                        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

                        @Override
                        public void write(int b) throws IOException {
                            if (b != '\n') {
                                line.write(b);
                                return;
                            }
                            String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
                            line.reset();
                            try {
                                String reply = script.answer(text);
                                if (reply != null) {
                                    toClient.add((reply + "\n").getBytes(StandardCharsets.UTF_8));
                                }
                            } catch (Exception e) {
                                throw new IOException(e);
                            }
                        }
                    };
                }

                @Override
                public void setReadTimeout(int millis) {
                    timeout[0] = millis;
                }

                @Override
                public InetAddress getPeerAddress() {
                    return null;
                }

                @Override
                public void close() {
                    closed[0] = true;
                }
            };
        };
    }

    private static Script moving() throws Exception {
        Script script = new Script();
        script.status = fixture();
        return script;
    }

    private static ServerControl.MoveResult move(Script script) {
        return ServerControl.moveGroup(transport(script), PHONE, SHOWN_GROUP, STREAM, 600);
    }

    private static JSONArray groups(JSONObject status) throws Exception {
        return status.getJSONObject("server").getJSONArray("groups");
    }

    @Test
    public void theGroupThatWasShownIsMovedAfterAFreshLook() throws Exception {
        Script script = moving();
        assertEquals(ServerControl.MoveResult.MOVED, move(script));
        assertEquals("the status is read first, then the move is made", java.util.Arrays.asList("Server.GetStatus", "Group.SetStream"),
                script.requests);
        assertEquals(SHOWN_GROUP, script.setStreamParams.get(0).getString("id"));
        assertEquals(STREAM, script.setStreamParams.get(0).getString("stream_id"));
    }

    @Test
    public void aClientThatIsInAnotherGroupNowChangesNothing() throws Exception {
        Script script = moving();
        JSONArray groups = groups(script.status);
        // the phone left its group and joined group-0 meanwhile
        JSONObject own = groups.getJSONObject(0);
        JSONObject phone = own.getJSONArray("clients").getJSONObject(0);
        own.put("clients", new JSONArray());
        groups.getJSONObject(1).getJSONArray("clients").put(phone);
        assertEquals(ServerControl.MoveResult.GROUP_CHANGED, move(script));
        assertEquals(Collections.singletonList("Server.GetStatus"), script.requests);
    }

    @Test
    public void aClientThatTheServerDoesNotListAnyMoreChangesNothing() throws Exception {
        Script script = moving();
        groups(script.status).getJSONObject(0).put("clients", new JSONArray());
        assertEquals(ServerControl.MoveResult.GROUP_CHANGED, move(script));
        assertEquals(Collections.singletonList("Server.GetStatus"), script.requests);
    }

    @Test
    public void aGroupThatWasDissolvedAndFormedAnewHasAnotherIdAndIsNotMoved() throws Exception {
        Script script = moving();
        groups(script.status).getJSONObject(0).put("id", "a-new-group-id");
        assertEquals(ServerControl.MoveResult.GROUP_CHANGED, move(script));
        assertEquals(Collections.singletonList("Server.GetStatus"), script.requests);
    }

    @Test
    public void aStreamThatIsGoneIsNotSentAndFails() throws Exception {
        Script script = moving();
        JSONArray streams = script.status.getJSONObject("server").getJSONArray("streams");
        JSONArray kept = new JSONArray();
        for (int i = 0; i < streams.length(); i++) {
            if (!STREAM.equals(streams.getJSONObject(i).getString("id"))) {
                kept.put(streams.getJSONObject(i));
            }
        }
        script.status.getJSONObject("server").put("streams", kept);
        assertEquals(ServerControl.MoveResult.FAILED, move(script));
        assertEquals(Collections.singletonList("Server.GetStatus"), script.requests);
    }

    @Test
    public void anErrorAnswerToTheMoveFails() throws Exception {
        Script script = moving();
        script.setStreamError = new JSONObject().put("code", -32602).put("message", "Stream not found");
        assertEquals(ServerControl.MoveResult.FAILED, move(script));
        assertEquals(2, script.requests.size());
    }

    @Test
    public void anErrorAnswerToTheStatusFailsWithoutAMove() throws Exception {
        Script script = moving();
        script.statusError = new JSONObject().put("code", -32603).put("message", "Internal error");
        assertEquals(ServerControl.MoveResult.FAILED, move(script));
        assertEquals(Collections.singletonList("Server.GetStatus"), script.requests);
    }

    @Test
    public void aServerThatDoesNotAnswerTheStatusFailsWithoutAMove() throws Exception {
        Script script = moving();
        script.statusSilent = true;
        long start = System.nanoTime();
        assertEquals(ServerControl.MoveResult.FAILED, move(script));
        assertTrue("timed out in time", (System.nanoTime() - start) / 1_000_000L < 5_000);
        assertEquals(Collections.singletonList("Server.GetStatus"), script.requests);
    }

    @Test
    public void aServerThatDoesNotAnswerTheMoveFails() throws Exception {
        Script script = moving();
        script.setStreamSilent = true;
        assertEquals(ServerControl.MoveResult.FAILED, move(script));
        assertEquals(2, script.requests.size());
    }

    @Test
    public void aServerThatCanNotBeReachedFails() throws Exception {
        Script script = moving();
        script.refuseConnect = true;
        assertEquals(ServerControl.MoveResult.FAILED, move(script));
        assertTrue(script.requests.isEmpty());
    }

    @Test
    public void missingArgumentsAreRefusedBeforeAnythingIsSent() throws Exception {
        Script script = moving();
        SnapcastTransport t = transport(script);
        assertEquals(ServerControl.MoveResult.FAILED, ServerControl.moveGroup(t, null, SHOWN_GROUP, STREAM, 600));
        assertEquals(ServerControl.MoveResult.FAILED, ServerControl.moveGroup(t, PHONE, null, STREAM, 600));
        assertEquals(ServerControl.MoveResult.FAILED, ServerControl.moveGroup(t, PHONE, "", STREAM, 600));
        assertEquals(ServerControl.MoveResult.FAILED, ServerControl.moveGroup(t, PHONE, SHOWN_GROUP, null, 600));
        assertEquals(ServerControl.MoveResult.FAILED, ServerControl.moveGroup(t, PHONE, SHOWN_GROUP, "", 600));
        assertEquals(ServerControl.MoveResult.FAILED, ServerControl.moveGroup((SnapcastTransport) null, PHONE, SHOWN_GROUP, STREAM, 600));
        assertEquals(ServerControl.MoveResult.FAILED, ServerControl.moveGroup((InetAddress) null, PHONE, SHOWN_GROUP, STREAM));
        assertTrue(script.requests.isEmpty());
    }

    // ---- the decision itself

    private static SnapcastServerStatus status(JSONObject result) throws Exception {
        return SnapcastServerStatus.fromResult(result);
    }

    @Test
    public void theDecisionIsMoveOnlyForTheShownGroupAndAnExistingStream() throws Exception {
        SnapcastServerStatus status = status(fixture());
        assertEquals(MoveDecision.Verdict.MOVE, MoveDecision.decide(status, PHONE, SHOWN_GROUP, STREAM));
        assertEquals(MoveDecision.Verdict.MOVE, MoveDecision.decide(status, PHONE, SHOWN_GROUP, "Default"));
        assertEquals(MoveDecision.Verdict.GROUP_CHANGED, MoveDecision.decide(status, PHONE, "group-0", STREAM));
        assertEquals(MoveDecision.Verdict.GROUP_CHANGED, MoveDecision.decide(status, "nobody", SHOWN_GROUP, STREAM));
        assertEquals(MoveDecision.Verdict.STREAM_GONE, MoveDecision.decide(status, PHONE, SHOWN_GROUP, "no such stream"));
        assertEquals(MoveDecision.Verdict.GROUP_CHANGED, MoveDecision.decide(null, PHONE, SHOWN_GROUP, STREAM));
        assertEquals(MoveDecision.Verdict.GROUP_CHANGED, MoveDecision.decide(status, null, SHOWN_GROUP, STREAM));
        assertEquals(MoveDecision.Verdict.GROUP_CHANGED, MoveDecision.decide(status, PHONE, null, STREAM));
        assertEquals(MoveDecision.Verdict.GROUP_CHANGED, MoveDecision.decide(status, PHONE, SHOWN_GROUP, null));
    }
}
