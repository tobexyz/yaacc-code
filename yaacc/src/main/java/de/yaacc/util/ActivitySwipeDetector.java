/*
 * Copyright (C) 2013 www.yaacc.de 
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
package de.yaacc.util;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;

public class ActivitySwipeDetector implements OnTouchListener {

	static final String logTag = "ActivitySwipeDetector";
	private SwipeReceiver swipeReceiver;
	static final int MIN_DISTANCE = 100;
	private float downX, downY, upX, upY;

	public ActivitySwipeDetector(SwipeReceiver swipeReceiver) {
		this.swipeReceiver = swipeReceiver;
	}

	private void onRightToLeftSwipe() {
		Log.i(logTag, "RightToLeftSwipe!");
		swipeReceiver.onRightToLeftSwipe();
	}

	private void onLeftToRightSwipe() {
		Log.i(logTag, "LeftToRightSwipe!");
		swipeReceiver.onLeftToRightSwipe();
	}

	private void onTopToBottomSwipe() {
		Log.i(logTag, "onTopToBottomSwipe!");
		swipeReceiver.onTopToBottomSwipe();
	}

	private void onBottomToTopSwipe() {
		Log.i(logTag, "onBottomToTopSwipe!");
		swipeReceiver.onBottomToTopSwipe();
	}

	private void endOnTouchProcessing(View v, MotionEvent event) {
		Log.i(logTag, "endOnTouchProcessing!");
		swipeReceiver.endOnTouchProcessing(v, event);

	}

	private void beginOnTouchProcessing(View v, MotionEvent event) {
		Log.i(logTag, "beginOnTouchProcessing!");
		swipeReceiver.beginOnTouchProcessing(v, event);

	}




	@Override
	public boolean onTouch(View v, MotionEvent event) {
		beginOnTouchProcessing(v, event);
		try {
			switch (event.getAction()) {
			case MotionEvent.ACTION_DOWN: {
				downX = event.getX();
				downY = event.getY();
				return true;
			}
			case MotionEvent.ACTION_UP: {
				upX = event.getX();
				upY = event.getY();

				float deltaX = downX - upX;
				float deltaY = downY - upY;

				// swipe horizontal?
				if (Math.abs(deltaX) > MIN_DISTANCE) {
					// left or right
					if (deltaX < 0) {
						this.onLeftToRightSwipe();
						return true;
					}
					if (deltaX > 0) {
						this.onRightToLeftSwipe();
						return true;
					}
				} else {
					Log.i(logTag, "Swipe was only " + Math.abs(deltaX)
							+ " long, need at least " + MIN_DISTANCE);
					return false; // We don't consume the event
				}

				// swipe vertical?
				if (Math.abs(deltaY) > MIN_DISTANCE) {
					// top or down
					if (deltaY < 0) {
						this.onTopToBottomSwipe();
						return true;
					}
					if (deltaY > 0) {
						this.onBottomToTopSwipe();
						return true;
					}
				} else {
					Log.i(logTag, "Swipe was only " + Math.abs(deltaX)
							+ " long, need at least " + MIN_DISTANCE);
					return false; // We don't consume the event
				}

				return true;
			}

			}

			return false;

		} finally {
			endOnTouchProcessing(v, event);
		}
	}


}