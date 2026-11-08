# Video galleries (#318)

## Problem Statement
The media server of the app lists the images of the phone as "All images" and "By bucket name" (the directories, the galleries of
the phone), but the videos only as one flat list. Users with videos from the camera (DCIM), a video editor (CapCut), downloads
or movie folders cannot browse them by directory.

## Goals
- The folder "Videos" of the server offers "All videos" and "Galleries", like "Images" does.
- A gallery is a MediaStore bucket (`BUCKET_ID`, `BUCKET_DISPLAY_NAME`): one directory, the name shown is the name of the directory.
- Paging, sorting (`dc:title`) and the child counts are right, for the videos and for the images (the image galleries paged by rows and counted wrongly; the YAACC app showed one gallery where Home Assistant showed nine).
- The setting "media paths" filters the list of the galleries and the content of a gallery alike.
- All texts in the seven languages.
- The media paths apply to every answer of the server (metadata of items, galleries, albums, artists, genres, thumbnails, covers), a media path is matched literally (`%`, `_`, `\` are no wildcards), and a deselected SAF folder is no longer described, listed or served (owner's decisions after the security review).

## Non-Goals
- A tree of nested directories (a gallery is one directory; nested ones are separate galleries, as for images).
- Other changes to the image browsers than the ones above (the gallery list, the media paths, the paging of the Images folder).
- Videos from the selected SAF folders (they have their own folder view).

## Success Metrics
- Browse of "Videos" gives two containers; "Galleries" gives one container per bucket with the right count of videos; a gallery gives its
  videos; "All videos" gives all of them; every video has one id wherever it is listed and plays as before.
- Tests: ids, routing, grouping/naming/paging of the galleries, the browsers against a fake MediaStore cursor, the strings.

## Constraints
- Existing ids stay valid: `VIDEOS_FOLDER` (400999) and the video item ids (`VIDEO_PREFIX` 410999 + MediaStore id).
- Plain JVM unit tests only; the build gate `assembleDebug testDebugUnitTest lintDebug` stays green, lint 0.
