# Icons and graphics

Where the icons of YAACC come from and under which license they are used. The license of the source code is in
`LICENSE.txt`. The app shows no logo of another product or company.

## Material Icons and Material Symbols (Google), Apache License 2.0

Most symbols of the user interface are Material Icons or Material Symbols from
<https://github.com/google/material-design-icons> (<https://fonts.google.com/icons>), used under the
Apache License, Version 2.0 (`licenses/Apache-2.0.txt`). Some were resized or recolored.

Files in `yaacc/src/main/res/drawable/`:

- `ic_baseline_*.xml`, `outline_*.xml`, `sharp_*.xml` (the bookmark icons are Material Symbols)
- `ic_exit_to_app_24.xml`, `ic_search_bookmark.xml`
- `ic_pick_device.xml`, `ic_pick_file.xml`, `ic_pick_folder.xml` (smartphone, file and folder symbols)
- `ic_yxc_link.xml`, `ic_yxc_presets.xml`, `ic_yxc_settings.xml` (link, star and tune symbols)

`yaacc/src/main/res/raw/album_art_music_generic.png`, the picture the media server sends as the cover of a music track
whose album has none, is the audio track symbol (`ic_baseline_audiotrack_48.xml`) drawn in gray on a transparent background.

## Android icons (Google)

`yaacc/src/main/res/drawable/button_cancel.png` is a file of the Android Design Icons pack in
`icons/Android_Design_Icons_20120711` (`holo_dark/hdpi/1_navigation_cancel.png`). The notice of the pack
(`icons/Android_Design_Icons_20120711/NOTICE.txt`) says:

> You may use the materials in this file without restriction to develop your apps and to use in your apps.

The resources of the Android Open Source Project are published under the Apache License, Version 2.0
(`licenses/Apache-2.0.txt`); Android icons that are used in the app are used under these terms.

## The YAACC logo

The play button in a ring (`icons/yaacc_icon/`; in the app `yaacc*.png`, `yaacc*.jpg`, `yaacc*.bmp`, the launcher icons
in `mipmap-*`, `tv_banner.png`) belongs to the project since 2014 and has been adapted since (adaptive icon, notification
icon, themed icon, TV banner). It is a work of the project itself and is covered by the license of the app (`LICENSE.txt`).

## Made for this project

`ic_live_audio_stream.xml`, `ic_live_video_stream.xml`, `ic_launcher_monochrome.xml` and `focus_ring.xml`.

## Not in the repository any more

Thirteen small PNG files (`cdtrack`, `device`, `folder`, `laptop` and others) that `LICENSE.txt` once attributed to
carpicon.com, and five player button PNGs and `refresh.png` of the Android Design Icons, were not used by the app and were
removed. They are still in the git history.
