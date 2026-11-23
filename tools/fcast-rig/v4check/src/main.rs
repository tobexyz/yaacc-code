use fcast_protocol::v4::{flat, DeviceInfo, MediaItem, MediaTrack, MediaTrackMetadata, MessageBuilder, PlaybackState};

fn hex(b: &[u8]) -> String { b.iter().map(|x| format!("{:02x}", x)).collect() }

fn item(url: &str) -> MediaItem {
    MediaItem {
        container: "video/mp4".into(),
        source_url: url.into(),
        start_time: Some(12.5),
        volume: Some(0.25),
        speed: Some(1.5),
        headers: None,
        title: Some("Song".into()),
        thumbnail_url: Some("http://h/a.jpg".into()),
        metadata: None,
        extra_metadata: None,
    }
}

fn main() {
    let t = |s: u64| flat::Time::new(s);
    let out = |name: &str, b: &[u8]| println!("{}={}", name, hex(b));
    out("senderIntroduction", &MessageBuilder::new().sender_introduction(&DeviceInfo { display_name: Some("YAACC".into()), app_name: Some("YAACC app".into()), app_version: Some("1.2.3".into()) }));
    out("loadFull", &MessageBuilder::new().load_single(item("http://192.168.0.5:8080/a.mp4")));
    out("loadMinimal", &MessageBuilder::new().load_single(MediaItem { container: "audio/mpeg".into(), source_url: "http://h/a.mp3".into(), start_time: None, volume: None, speed: None, headers: None, title: None, thumbnail_url: None, metadata: None, extra_metadata: None }));
    out("loadQueue", &MessageBuilder::new().load_queue(vec![(item("http://h/0.mp4"), None), (item("http://h/1.mp4"), Some(5.0)), (item("http://h/2.mp4"), None)].into_iter(), Some(1), true));
    out("progress", &MessageBuilder::new().progress_changed(t(1_500_000), t(61_000_000)));
    out("progressZero", &MessageBuilder::new().progress_changed(t(0), t(0)));
    out("volume", &MessageBuilder::new().volume_changed(0.5));
    out("volumeZero", &MessageBuilder::new().volume_changed(0.0));
    out("speed", &MessageBuilder::new().speed_changed(1.25));
    out("stateIdle", &MessageBuilder::new().playback_state_changed(PlaybackState::Idle));
    out("stateBuffering", &MessageBuilder::new().playback_state_changed(PlaybackState::Buffering));
    out("statePlaying", &MessageBuilder::new().playback_state_changed(PlaybackState::Playing));
    out("statePaused", &MessageBuilder::new().playback_state_changed(PlaybackState::Paused));
    out("stateEnded", &MessageBuilder::new().playback_state_changed(PlaybackState::Ended));
    out("stop", &MessageBuilder::new().stop_playback());
    out("error", &MessageBuilder::new().error(Some(7), flat::ErrorKind::UnsupportedFormat));
    out("errorNoNumber", &MessageBuilder::new().error(None, flat::ErrorKind::Internal));
    let protos = ["http", "https"]; let conts = ["mp4", "mkv"]; let vids = ["h264"]; let auds = ["aac", "opus"]; let subs = ["srt", "vtt"]; let hdrs: [&str; 0] = []; let imgs = ["png", "jpeg"];
    out("receiverIntroduction", &MessageBuilder::new().receiver_introduction(&DeviceInfo { display_name: Some("FCast-pc".into()), app_name: Some("FCast Receiver Desktop".into()), app_version: Some("3.0.4".into()) }, protos.into_iter(), conts.into_iter(), vids.into_iter(), auds.into_iter(), subs.into_iter(), hdrs.into_iter(), imgs.into_iter(), true, false, 0.01));
    out("tracks", &MessageBuilder::new().tracks_available(vec![
        MediaTrack { id: 1, title: Some("English".into()), iso_639: "eng".into(), metadata: Some(MediaTrackMetadata::Audio) },
        MediaTrack { id: 2, title: None, iso_639: "deu".into(), metadata: Some(MediaTrackMetadata::Subtitle) },
        MediaTrack { id: 0, title: Some("Main".into()), iso_639: "und".into(), metadata: Some(MediaTrackMetadata::Video) },
        MediaTrack { id: 9, title: Some("x".into()), iso_639: "fra".into(), metadata: None },
    ].into_iter()));
    out("changeTrack", &MessageBuilder::new().change_track(Some(3), flat::MediaTrackType::Audio));
    out("changeTrackOff", &MessageBuilder::new().change_track(None, flat::MediaTrackType::Subtitle));
    out("addSubtitle", &MessageBuilder::new().add_subtitle_source("http://h/a.srt", true, Some("English (external)")));
    out("addSubtitleNoName", &MessageBuilder::new().add_subtitle_source("http://h/b.vtt", false, None));
    out("progressInterval", &MessageBuilder::new().set_progress_update_interval(t(1_000_000)));
}
