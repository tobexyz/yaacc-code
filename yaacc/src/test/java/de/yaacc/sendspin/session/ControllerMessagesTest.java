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
package de.yaacc.sendspin.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** The messages of the controller client: what it sends and what it reads of the server, with hostile input. */
public class ControllerMessagesTest {

    private static final String ID = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static JSONObject payload(String json) throws Exception {
        return new JSONObject(json);
    }

    private static void rejects(String json, String what, java.util.concurrent.Callable<?> parse) {
        try {
            parse.call();
            fail(what);
        } catch (ProtocolException expected) {
            // fine
        } catch (Exception e) {
            fail(what + ": " + e);
        }
    }

    // ---------------------------------------------------------------- what the client sends

    @Test
    public void clientInitHasTheIdTheVersionAndTheSuite() throws Exception {
        Envelope e = Envelope.parse(ControllerMessages.clientInit(ID, "Noise_KKpsk2_25519_ChaChaPoly_SHA256").getBytes("UTF-8"));
        assertEquals("client/init", e.type());
        assertEquals(ID, e.payload().getString("client_id"));
        assertEquals(1, e.payload().getInt("version"));
        assertEquals("Noise_KKpsk2_25519_ChaChaPoly_SHA256", e.payload().getString("suite"));
    }

    @Test
    public void theControllerHelloListsTheControllerAndMetadataRolesAndNoPlayer() throws Exception {
        Envelope e = Envelope.parse(ControllerMessages.clientHello("YAACC", "Pixel", "1.2", true).getBytes("UTF-8"));
        assertEquals("client/hello", e.type());
        JSONObject p = e.payload();
        assertEquals("YAACC", p.getString("name"));
        assertEquals(Arrays.asList("controller@v1", "metadata@v1", "artwork@v1"), Arrays.asList(p.getJSONArray("supported_roles").getString(0),
                p.getJSONArray("supported_roles").getString(1), p.getJSONArray("supported_roles").getString(2)));
        assertEquals(3, p.getJSONArray("supported_roles").length());
        assertFalse(p.has("player@v1_support"));
        assertTrue("every client offers the Pairing PSK method", p.getJSONObject("supported_pair_methods").has("pairing_psk"));
        assertTrue(p.getJSONObject("unpaired_access").getBoolean("enabled"));
        assertEquals("Pixel", p.getJSONObject("device_info").getString("product_name"));
        assertFalse(Envelope.parse(ControllerMessages.clientHello("YAACC", null, null, false).getBytes("UTF-8"))
                .payload().getJSONObject("unpaired_access").getBoolean("enabled"));
    }

    @Test
    public void theHelloIsParsedByTheServersParserToo() throws Exception {
        Envelope e = Envelope.parse(ControllerMessages.clientHello("YAACC", "Pixel", "1.2", false).getBytes("UTF-8"));
        SendspinMessages.ClientHello hello = SendspinMessages.parseClientHello(e.payload());
        assertFalse(hello.supportsPlayer());
        assertTrue(hello.supportedRoles.contains("controller@v1"));
        assertTrue(hello.pairMethods.contains("pairing_psk"));
    }

    @Test
    public void commandsCarryOnlyWhatTheirNameNeeds() throws Exception {
        JSONObject c = Envelope.parse(ControllerMessages.commandPlain("pause").getBytes("UTF-8")).payload().getJSONObject("controller");
        assertEquals("pause", c.getString("command"));
        assertEquals(1, c.length());
        assertEquals(40, Envelope.parse(ControllerMessages.commandVolume(40).getBytes("UTF-8")).payload().getJSONObject("controller").getInt("volume"));
        assertTrue(Envelope.parse(ControllerMessages.commandMute(true).getBytes("UTF-8")).payload().getJSONObject("controller").getBoolean("mute"));
        assertEquals(90_000L, Envelope.parse(ControllerMessages.commandSeek(90_000L).getBytes("UTF-8")).payload().getJSONObject("controller").getLong("position_ms"));
        assertEquals(-5_000L, Envelope.parse(ControllerMessages.commandSeekRelative(-5_000L).getBytes("UTF-8")).payload().getJSONObject("controller").getLong("offset_ms"));
        assertEquals("client/command", Envelope.parse(ControllerMessages.commandPlain("next").getBytes("UTF-8")).type());
    }

    @Test
    public void stateTimeGoodbyeAndAbortAreBuilt() throws Exception {
        assertTrue(Envelope.parse(ControllerMessages.clientState(true).getBytes("UTF-8")).payload().getBoolean("available"));
        assertEquals(77L, Envelope.parse(ControllerMessages.clientTime(77L).getBytes("UTF-8")).payload().getLong("client_transmitted"));
        assertEquals("user_request", Envelope.parse(ControllerMessages.clientGoodbye("user_request").getBytes("UTF-8")).payload().getString("reason"));
        assertEquals("method_not_supported", Envelope.parse(ControllerMessages.pairAbort("method_not_supported").getBytes("UTF-8")).payload().getString("reason"));
    }

    // ---------------------------------------------------------------- what the client reads

    @Test
    public void serverHelloGivesTheName() throws Exception {
        assertEquals("Music Assistant", ControllerMessages.parseServerHello(payload("{\"name\":\"Music Assistant\",\"languages\":[\"de\"]}")));
        rejects("", "no name", () -> ControllerMessages.parseServerHello(payload("{}")));
        char[] big = new char[65];
        Arrays.fill(big, 'x');
        rejects("", "name over 64 bytes", () -> ControllerMessages.parseServerHello(payload("{\"name\":\"" + new String(big) + "\"}")));
    }

    @Test
    public void activationIsParsedWithRolesThatMayBeAbsent() throws Exception {
        ControllerMessages.Activate a = ControllerMessages.parseActivate(payload(
                "{\"activities\":[\"playback\"],\"active_roles\":[\"controller@v1\",\"metadata@v1\"]}"));
        assertEquals(Collections.singleton("playback"), a.activities);
        assertEquals(Arrays.asList("controller@v1", "metadata@v1"), a.activeRoles);
        assertNull("absent roles persist", ControllerMessages.parseActivate(payload("{\"activities\":[]}")).activeRoles);
        ControllerMessages.Activate pairing = ControllerMessages.parseActivate(payload(
                "{\"activities\":[\"pairing\"],\"active_roles\":[],\"pairing\":{\"method\":\"pairing_psk\"}}"));
        assertEquals("pairing_psk", pairing.pairingMethod);
        rejects("", "activities missing", () -> ControllerMessages.parseActivate(payload("{}")));
        rejects("", "activities not an array", () -> ControllerMessages.parseActivate(payload("{\"activities\":\"playback\"}")));
        rejects("", "duplicate activity", () -> ControllerMessages.parseActivate(payload("{\"activities\":[\"playback\",\"playback\"]}")));
        rejects("", "roles not strings", () -> ControllerMessages.parseActivate(payload("{\"activities\":[],\"active_roles\":[1]}")));
    }

    @Test
    public void admissionFollowsTheSpecificationsTable() {
        java.util.Set<String> none = Collections.emptySet();
        java.util.Set<String> playback = Collections.singleton("playback");
        java.util.Set<String> pairing = Collections.singleton("pairing");
        java.util.List<String> noRoles = Collections.emptyList();
        java.util.List<String> roles = Collections.singletonList("controller@v1");
        de.yaacc.sendspin.secure.SendspinPsk.Category sn = de.yaacc.sendspin.secure.SendspinPsk.Category.SENTINEL;
        de.yaacc.sendspin.secure.SendspinPsk.Category lt = de.yaacc.sendspin.secure.SendspinPsk.Category.LONG_TERM;
        assertEquals(ControllerMessages.Admission.OK, ControllerMessages.admit(sn, true, none, noRoles, true));
        assertEquals("roles and playback on an unpaired session need unpaired access", ControllerMessages.Admission.OK,
                ControllerMessages.admit(sn, true, playback, roles, true));
        assertEquals("the first rule: unpaired access would make it admissible", ControllerMessages.Admission.PAIRING_REQUIRED,
                ControllerMessages.admit(sn, false, playback, roles, true));
        assertEquals("roles without playback are allowed only on a playback-capable connection",
                ControllerMessages.Admission.PAIRING_REQUIRED, ControllerMessages.admit(sn, false, none, roles, true));
        assertEquals("empty activities and no roles are always fine", ControllerMessages.Admission.OK,
                ControllerMessages.admit(sn, false, none, noRoles, true));
        assertEquals("a long-term session may declare playback whatever the unpaired setting", ControllerMessages.Admission.OK,
                ControllerMessages.admit(lt, false, playback, roles, true));
        assertEquals("pairing is not an activity of a long-term session", ControllerMessages.Admission.UNAUTHORIZED,
                ControllerMessages.admit(lt, true, pairing, noRoles, true));
        assertEquals("a pairing activation: we do not offer a pairing method in this slice",
                ControllerMessages.Admission.METHOD_NOT_SUPPORTED, ControllerMessages.admit(sn, true, pairing, noRoles, false));
        assertEquals("a pairing activation with a method we offer is fine", ControllerMessages.Admission.OK,
                ControllerMessages.admit(sn, true, pairing, noRoles, true));
    }

    @Test
    public void groupUpdateTakesTheIdAsAnOpaqueTextBecauseTheReferenceServerSendsAUuid() throws Exception {
        ControllerMessages.Group g = ControllerMessages.parseGroupUpdate(payload(
                "{\"playback_state\":\"playing\",\"group_id\":\"" + ID + "\",\"group_name\":\"Kitchen\"}"));
        assertTrue(g.playing);
        assertEquals(ID, g.groupId);
        assertEquals("Kitchen", g.groupName);
        assertFalse(ControllerMessages.parseGroupUpdate(payload(
                "{\"playback_state\":\"stopped\",\"group_id\":\"" + ID + "\",\"group_name\":\"\"}")).playing);
        assertEquals("a UUID as the reference server sends it", "275497ee-a9b9-42e4-baf7-33ac2418bfbf", ControllerMessages.parseGroupUpdate(payload(
                "{\"playback_state\":\"stopped\",\"group_id\":\"275497ee-a9b9-42e4-baf7-33ac2418bfbf\",\"group_name\":\"x\"}")).groupId);
        rejects("", "empty id", () -> ControllerMessages.parseGroupUpdate(payload(
                "{\"playback_state\":\"stopped\",\"group_id\":\"\",\"group_name\":\"x\"}")));
        char[] big = new char[65];
        Arrays.fill(big, 'x');
        rejects("", "id over 64 bytes", () -> ControllerMessages.parseGroupUpdate(payload(
                "{\"playback_state\":\"stopped\",\"group_id\":\"" + new String(big) + "\",\"group_name\":\"x\"}")));
        rejects("", "id not a text", () -> ControllerMessages.parseGroupUpdate(payload(
                "{\"playback_state\":\"stopped\",\"group_id\":5,\"group_name\":\"x\"}")));
        rejects("", "unknown state", () -> ControllerMessages.parseGroupUpdate(payload(
                "{\"playback_state\":\"paused\",\"group_id\":\"" + ID + "\",\"group_name\":\"x\"}")));
    }

    @Test
    public void serverStateCarriesTheControllerAndMetadataObjects() throws Exception {
        ControllerMessages.ServerState s = ControllerMessages.parseServerState(payload("{"
                + "\"controller\":{\"supported_commands\":[\"play\",\"pause\",\"volume\",\"seek\",\"made_up\"],\"volume\":35,\"muted\":false,"
                + "\"repeat\":\"all\",\"shuffle\":true,\"seek_max_ms\":200000},"
                + "\"metadata\":{\"timestamp\":5000000,\"title\":\"Song\",\"artist\":\"Band\",\"album\":\"LP\",\"year\":2024,\"track\":3,"
                + "\"artwork_url\":\"http://x/a.jpg\",\"progress\":{\"track_progress\":1000,\"track_duration\":180000,\"playback_speed\":1000}}}"));
        assertEquals(35, s.controller.volume);
        assertFalse(s.controller.muted);
        assertEquals("all", s.controller.repeat);
        assertTrue(s.controller.shuffle);
        assertEquals(Long.valueOf(200000L), s.controller.seekMaxMs);
        assertTrue(s.controller.supports("seek"));
        assertFalse("a command that is not in the list", s.controller.supports("next"));
        assertEquals("Song", s.metadata.title);
        assertEquals("Band", s.metadata.artist);
        assertNull(s.metadata.albumArtist);
        assertEquals(5_000_000L, s.metadata.timestamp);
        assertEquals(1000L, s.metadata.progress.trackProgressMs);
        assertEquals(180000L, s.metadata.progress.trackDurationMs);
        assertEquals(1000, s.metadata.progress.playbackSpeed);
        assertNull(ControllerMessages.parseServerState(payload("{\"controller\":{\"supported_commands\":[],\"volume\":1,\"muted\":true,"
                + "\"repeat\":\"off\",\"shuffle\":false}}")).metadata);
        assertNull("progress may be omitted", ControllerMessages.parseServerState(payload(
                "{\"metadata\":{\"timestamp\":1,\"title\":\"x\"}}")).metadata.progress);
        assertNull("unknown objects are ignored", ControllerMessages.parseServerState(payload("{\"color\":{\"x\":1}}")).controller);
    }

    @Test
    public void serverStateRejectsWhatBreaksTheTypesAndLimits() {
        rejects("", "volume above 100", () -> ControllerMessages.parseServerState(payload("{\"controller\":{\"supported_commands\":[],"
                + "\"volume\":101,\"muted\":false,\"repeat\":\"off\",\"shuffle\":false}}")));
        rejects("", "unknown repeat", () -> ControllerMessages.parseServerState(payload("{\"controller\":{\"supported_commands\":[],"
                + "\"volume\":1,\"muted\":false,\"repeat\":\"twice\",\"shuffle\":false}}")));
        rejects("", "muted not boolean", () -> ControllerMessages.parseServerState(payload("{\"controller\":{\"supported_commands\":[],"
                + "\"volume\":1,\"muted\":\"no\",\"repeat\":\"off\",\"shuffle\":false}}")));
        rejects("", "commands not an array", () -> ControllerMessages.parseServerState(payload("{\"controller\":{\"supported_commands\":1,"
                + "\"volume\":1,\"muted\":false,\"repeat\":\"off\",\"shuffle\":false}}")));
        char[] long256 = new char[256];
        Arrays.fill(long256, 'x');
        rejects("", "title over 255 bytes", () -> ControllerMessages.parseServerState(payload(
                "{\"metadata\":{\"timestamp\":1,\"title\":\"" + new String(long256) + "\"}}")));
        rejects("", "no timestamp", () -> ControllerMessages.parseServerState(payload("{\"metadata\":{\"title\":\"x\"}}")));
        rejects("", "track below 1", () -> ControllerMessages.parseServerState(payload("{\"metadata\":{\"timestamp\":1,\"track\":0}}")));
        rejects("", "progress without fields", () -> ControllerMessages.parseServerState(payload(
                "{\"metadata\":{\"timestamp\":1,\"progress\":{\"track_progress\":1}}}")));
        rejects("", "negative duration", () -> ControllerMessages.parseServerState(payload(
                "{\"metadata\":{\"timestamp\":1,\"progress\":{\"track_progress\":1,\"track_duration\":-1,\"playback_speed\":1000}}}")));
    }

    @Test
    public void serverTimeCarriesTheThreeTimestamps() throws Exception {
        long[] t = ControllerMessages.parseServerTime(payload("{\"client_transmitted\":1,\"server_received\":2,\"server_transmitted\":3}"));
        assertEquals(1L, t[0]);
        assertEquals(2L, t[1]);
        assertEquals(3L, t[2]);
        rejects("", "missing", () -> ControllerMessages.parseServerTime(payload("{\"client_transmitted\":1}")));
    }

    @Test
    public void theArtworkStateDeclaresOneAlbumChannelAndTheWantedSize() throws Exception {
        JSONObject p = Envelope.parse(ControllerMessages.clientStateWithArtwork(true, "jpeg", 512, 512).getBytes("UTF-8")).payload();
        assertTrue(p.getBoolean("available"));
        JSONObject channel = p.getJSONObject("artwork").getJSONArray("channels").getJSONObject(0);
        assertEquals("album", channel.getString("source"));
        assertEquals("jpeg", channel.getString("format"));
        assertEquals(512, channel.getInt("width"));
        assertEquals(512, channel.getInt("height"));
        assertEquals(1, p.getJSONObject("artwork").getJSONArray("channels").length());
    }

    @Test
    public void streamStartOfTheArtworkRoleGivesTheChannels() throws Exception {
        java.util.List<ControllerMessages.ArtworkChannel> channels = ControllerMessages.parseStreamStartArtwork(payload(
                "{\"server_transmitted\":5,\"artwork\":{\"channels\":[{\"source\":\"album\",\"format\":\"jpeg\",\"width\":512,\"height\":512},"
                        + "{\"source\":\"none\"}]}}"));
        assertEquals(2, channels.size());
        assertEquals("jpeg", channels.get(0).format);
        assertEquals(512, channels.get(0).width);
        assertFalse(channels.get(1).isActive());
        assertNull("a stream/start without an artwork object is not ours", ControllerMessages.parseStreamStartArtwork(payload(
                "{\"server_transmitted\":5,\"player\":{\"codec\":\"pcm\"}}")));
        rejects("", "no channels", () -> ControllerMessages.parseStreamStartArtwork(payload("{\"artwork\":{\"channels\":[]}}")));
        rejects("", "five channels", () -> ControllerMessages.parseStreamStartArtwork(payload("{\"artwork\":{\"channels\":["
                + "{\"source\":\"none\"},{\"source\":\"none\"},{\"source\":\"none\"},{\"source\":\"none\"},{\"source\":\"none\"}]}}")));
        rejects("", "unknown source", () -> ControllerMessages.parseStreamStartArtwork(payload(
                "{\"artwork\":{\"channels\":[{\"source\":\"band\",\"format\":\"jpeg\",\"width\":1,\"height\":1}]}}")));
        rejects("", "unknown format", () -> ControllerMessages.parseStreamStartArtwork(payload(
                "{\"artwork\":{\"channels\":[{\"source\":\"album\",\"format\":\"gif\",\"width\":1,\"height\":1}]}}")));
        rejects("", "no size", () -> ControllerMessages.parseStreamStartArtwork(payload(
                "{\"artwork\":{\"channels\":[{\"source\":\"album\",\"format\":\"jpeg\"}]}}")));
        rejects("", "a size beyond the limit", () -> ControllerMessages.parseStreamStartArtwork(payload(
                "{\"artwork\":{\"channels\":[{\"source\":\"album\",\"format\":\"jpeg\",\"width\":100000,\"height\":1}]}}")));
    }

    @Test
    public void streamEndNamesTheRolesOrEndsAll() throws Exception {
        assertEquals(Arrays.asList("player", "artwork"), ControllerMessages.parseStreamEndRoles(payload("{\"roles\":[\"player\",\"artwork\"]}")));
        assertNull("omitted: all streams end", ControllerMessages.parseStreamEndRoles(payload("{}")));
        rejects("", "roles not an array", () -> ControllerMessages.parseStreamEndRoles(payload("{\"roles\":\"artwork\"}")));
        rejects("", "a role that is no text", () -> ControllerMessages.parseStreamEndRoles(payload("{\"roles\":[1]}")));
    }
}
