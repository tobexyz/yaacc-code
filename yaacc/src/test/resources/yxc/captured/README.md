# Captured answers of three real devices (scrubbed)

Recorded read-only on 2026-11-02 from three devices that speak the YXC API (devices a, b, c), then scrubbed with
`.claude/specs/2026-11-02-issue307-musiccast/capture/scrub.py`: MAC / IP addresses, device and system ids, serial numbers, UUIDs, names of
rooms and groups and model names were replaced by neutral values. Everything else is as the devices answered.

| Prefix | What it is |
|---|---|
| `a_*` | a small device with a tuner (older firmware generation, `distribution.client_max` 9); `a_distInfo_server.json` is the group info while it was the **server** of a group with one client, `a_status_linked_server.json` the zone status then |
| `b_*` | a device with the `mc_surround` block in its distribution info (`b_distInfo_none.json`) |
| `c_*` | a device that was linked as a **client** (`c_status_linked_client*.json`: input `mc_link`, `distribution_enable` false) and unlinked (`c_*_unlinked`, `c_distInfo_none.json`, role word `none`); `c_upnpDescription.xml` is its UPnP device description (shows the YXC control URL tag) |

Facts these files show (verified on the devices): the role of an unlinked device is `none`; a server's group info has `status` ("working") and a client list with `ip_address` and `data_type`;
`getFeatures` has a `distribution` block (`version`, `compatible_client`, `client_max` 9 or 19, `server_zone_list`, optionally `mc_surround`).
A second capture of the same two devices linked (2026-11-02 evening): `a_distInfo_server_pair.json` (server) and `c_distInfo_client.json` (client) carry the SAME `group_id`;
the client reports `"role": "client"`, a generated group name and a `client_list` that contains **the client itself** (not the server): a client's answer does not name its server, so a client
is matched to its server by the group id plus the server's client list. `c_nameText_main.json` is `system/getNameText?id=main` (the name of the zone: the room name; works with the zone id as parameter).
`a_event_netusb_play_time.json` is a real UDP event (2026-11-02): while the device played a network source it pushed one datagram per second, unicast to the port named in the registration header, of the form
`{"netusb":{"play_time":<seconds>},"device_id":"<device id>"}` (the device id is replaced here by the one of `a_deviceInfo.json`).
Further real events of the same device (`a_event_volume.json`, `a_event_mute_on.json`, `a_event_volume_and_mute.json`, `a_event_main_and_netusb.json`, captured while the owner moved the volume slider and the mute button in the manufacturer's app):
the zone block carries only what changed (`{"main":{"volume":29}}`, `{"main":{"mute":true}}`, both together, or together with a `netusb` block in one datagram).
More real events: `a_event_dist_info_updated.json` (a group change, here an unlink; the device sent it twice about 200 ms apart), `a_event_track_change.json`, `a_event_play_info_updated.json` and `a_event_signal_info_updated.json` (a track change makes a burst of five datagrams within 1.5 s: signal info, play info, play queue, `play_time` back to 0).
`a_event_pause_play.json` (pause and play look the same: `signal_info_updated` + `play_info_updated`, without a queue flag; the state is only in `getPlayInfo`), `a_event_power_standby.json` (power off: `"power":"standby"` alone, nothing else around it), `a_event_power_on.json`, `a_event_play_time_and_dist_info_updated.json` (a `play_time` second and a group change in one datagram) and `a_event_wake_up.json` (what the device sends first when it powers on: `account_updated` and the play flags; it comes right before the power event, the play flags come again 2.5–5 s after it).
Not yet captured: events for input changes, the events of a client, what a client of a surround / stereo-pair group reports.
