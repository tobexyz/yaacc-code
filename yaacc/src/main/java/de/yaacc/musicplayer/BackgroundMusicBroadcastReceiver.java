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
package de.yaacc.musicplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.util.Log;

/**
 * @author Tobias Schoene (openbit)  
 * 
 */
public class BackgroundMusicBroadcastReceiver extends BroadcastReceiver {

	public static String ACTION_PLAY = "de.yaacc.musicplayer.ActionPlay";
	public static String ACTION_STOP = "de.yaacc.musicplayer.ActionStop";
	public static String ACTION_PAUSE = "de.yaacc.musicplayer.ActionPause";
	public static String ACTION_SET_DATA = "de.yaacc.musicplayer.ActionSetData";	
	public static String ACTION_SET_DATA_URI_PARAM = "de.yaacc.musicplayer.ActionSetDataUriParam";
    public static String ACTION_SEEK_TO = "de.yaacc.musicplayer.ActionSeekTo";
    public static String ACTION_SEEK_TO_PARAM = "de.yaacc.musicplayer.ActionSeekToParam";
	
	
	
	private BackgroundMusicService backgroundMusicService;

	/**
	 * 
	 */
	public BackgroundMusicBroadcastReceiver(BackgroundMusicService backgroundMusicService) {
		Log.d(this.getClass().getName(), "Starting Broadcast Receiver..." );
		assert(backgroundMusicService != null);
		this.backgroundMusicService = backgroundMusicService;
		
	}

	/* (non-Javadoc)
	 * @see android.content.BroadcastReceiver#onReceive(android.content.Context, android.content.Intent)
	 */
	@Override
	public void onReceive(Context context, Intent intent) {
		Log.d(this.getClass().getName(), "Received Action: " + intent.getAction());		
		if(backgroundMusicService == null) return;
		Log.d(this.getClass().getName(), "Execute Action on backgroundMusicService: " + backgroundMusicService);
		if(ACTION_PLAY.equals(intent.getAction())){
			backgroundMusicService.play();
		}else if(ACTION_PAUSE.equals(intent.getAction())){
			backgroundMusicService.pause();
		}else if(ACTION_STOP.equals(intent.getAction())){
			backgroundMusicService.stop();
		}else if(ACTION_SET_DATA.equals(intent.getAction())){
			backgroundMusicService.setMusicUri((Uri)intent.getParcelableExtra(ACTION_SET_DATA_URI_PARAM));
		}else if(ACTION_SEEK_TO.equals(intent.getAction())){
            backgroundMusicService.seekTo(intent.getIntExtra(ACTION_SEEK_TO_PARAM,0));
        }
		
			
	}

	public void registerReceiver() {
		Log.d(this.getClass().getName(), "Register Receiver" );		
		IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(ACTION_PLAY);
		intentFilter.addAction(ACTION_PAUSE);		
		intentFilter.addAction(ACTION_STOP);
		
		intentFilter.addAction(ACTION_SET_DATA);
		backgroundMusicService.registerReceiver(this, intentFilter);

		
	}

}
