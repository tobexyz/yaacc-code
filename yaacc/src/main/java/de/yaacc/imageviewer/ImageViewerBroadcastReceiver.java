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
package de.yaacc.imageviewer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * @author Tobias Schoene (openbit)  
 * 
 */
public class ImageViewerBroadcastReceiver extends BroadcastReceiver {

	public static String ACTION_PLAY = "de.yaacc.imageviewer.ActionPlay";
	public static String ACTION_STOP = "de.yaacc.imageviewer.ActionStop";
	public static String ACTION_PAUSE = "de.yaacc.imageviewer.ActionPause";
	public static String ACTION_NEXT = "de.yaacc.imageviewer.ActionNext";
	public static String ACTION_PREVIOUS = "de.yaacc.imageviewer.ActionPrevious";
	public static String ACTION_EXIT = "de.yaacc.imageviewer.ActionExit";
	
	
	private ImageViewerActivity imageViewer;

    public ImageViewerBroadcastReceiver() {
    }

    /**
	 * 
	 */
	public ImageViewerBroadcastReceiver(ImageViewerActivity imageViewer) {
		Log.d(this.getClass().getName(), "Starting Broadcast Receiver..." );
		assert(imageViewer != null);
		this.imageViewer = imageViewer;
		
	}

	/* (non-Javadoc)
	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		Log.d(this.getClass().getName(), "Received Action: " + intent.getAction());		
		if(imageViewer == null) return;
		Log.d(this.getClass().getName(), "Execute Action on imageViewer: " + imageViewer);
		if(ACTION_PLAY.equals(intent.getAction())){
			imageViewer.play();
		}else if(ACTION_PAUSE.equals(intent.getAction())){
			imageViewer.pause();
		}else if(ACTION_STOP.equals(intent.getAction())){
			imageViewer.stop();
		}else if(ACTION_PREVIOUS.equals(intent.getAction())){
			imageViewer.previous();
		}else if(ACTION_NEXT.equals(intent.getAction())){
			imageViewer.next();
		}else if(ACTION_EXIT.equals(intent.getAction())){
			imageViewer.finish();
		}
		
			
	}

	public void registerReceiver() {
		Log.d(this.getClass().getName(), "Register Receiver" );		
		IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(ACTION_PLAY);
		intentFilter.addAction(ACTION_PAUSE);
		intentFilter.addAction(ACTION_NEXT);
		intentFilter.addAction(ACTION_PREVIOUS);
		intentFilter.addAction(ACTION_STOP);		
		imageViewer.registerReceiver(this, intentFilter);

		
	}

}
