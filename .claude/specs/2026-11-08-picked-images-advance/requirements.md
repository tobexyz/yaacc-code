# Images of picked files and folders change to the next image

## Problem
"Play from this device" (a picked folder with images, or several image files) on a receiver: the image show stayed on the first image. The
items of a picked queue had duration -1 ("the receiver's transport state tells when an item ended"), which is right for audio and video but not
for an image: it has no length and no end of its own, so `AbstractPlayer.doPostLoadItem` started no timer for the next item.

## Goal
- Images of a picked queue get the "default duration" setting (milliseconds, what `UpnpClient.getDefaultDuration()` gives and what browsed images
  get), so the player shows each image that long and then the next one; the setting "never" (-1) keeps the image until the user goes on.
- Audio and video keep -1 until their share is bound (duration read from the file); an image keeps its duration when its share is bound.
- For other receivers (shared items). This phone's own image viewer (`LocalImagePlayer`, direct items) does not read the duration: it changes the image by the slideshow time of its own settings and was not affected.

## Non-Goals
- Other player behaviour, the share lifecycle, the settings.

## Constraints
- Plain JVM unit tests; the gate (build, tests, lint) stays green.
