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

/**
 * Where a source image goes in an artwork channel (spec, "Artwork messages"): scaled to fit within the declared width and height,
 * keeping its aspect ratio, centred, the rest padded with black, never cropped. Pure arithmetic; the bitmap work is the encoder's.
 */
public final class ArtworkLayout {
    public final int width;
    public final int height;
    public final int x;
    public final int y;

    private ArtworkLayout(int width, int height, int x, int y) {
        this.width = width;
        this.height = height;
        this.x = x;
        this.y = y;
    }

    /** @return the size of the scaled image and its top left corner inside the {@code targetWidth} by {@code targetHeight} channel */
    public static ArtworkLayout fit(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        double scale = Math.min(targetWidth / (double) Math.max(1, sourceWidth), targetHeight / (double) Math.max(1, sourceHeight));
        int w = Math.min(targetWidth, Math.max(1, (int) Math.round(sourceWidth * scale)));
        int h = Math.min(targetHeight, Math.max(1, (int) Math.round(sourceHeight * scale)));
        return new ArtworkLayout(w, h, (targetWidth - w) / 2, (targetHeight - h) / 2);
    }
}
