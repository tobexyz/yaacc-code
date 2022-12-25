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
package de.yaacc.player;

import java.net.URI;
import java.util.List;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import org.fourthline.cling.support.model.DIDLObject;

import de.yaacc.R;
import de.yaacc.upnp.UpnpClient;
import de.yaacc.util.NotificationId;

/**
 * A Player for local music playing
 * 
 * @author Tobias Schoene (openbit)
 * 
 */
public class LocalThirdPartieMusicPlayer extends AbstractPlayer {

	private int musicAppPid=0; 
	
	/**
	 * @param upnpClient
	 * @param name playerName
	 * 
	 */
	public LocalThirdPartieMusicPlayer(UpnpClient upnpClient, String name) {		
		this(upnpClient);
		setName(name);
	}
	
	
	/**
	 * @param upnpClient
	 */
	public LocalThirdPartieMusicPlayer(UpnpClient upnpClient) {
		super(upnpClient);

	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * de.yaacc.player.AbstractPlayer#stopItem(de.yaacc.player.PlayableItem)
	 */
	@Override
	protected void stopItem(PlayableItem playableItem) {
		if(musicAppPid != 0){
			Process.killProcess(musicAppPid);
		}	
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * de.yaacc.player.AbstractPlayer#loadItem(de.yaacc.player.PlayableItem)
	 */
	@Override
	protected Object loadItem(PlayableItem playableItem) {
		Uri uri = playableItem.getUri();
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		intent.setDataAndType(uri, playableItem.getMimeType());
		return intent;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * de.yaacc.player.AbstractPlayer#startItem(de.yaacc.player.PlayableItem,
	 * java.lang.Object)
	 */
	@Override
	protected void startItem(PlayableItem playableItem, Object loadedItem) {		
		if (loadedItem instanceof Intent) {
			
			Intent intent = (Intent) loadedItem;
			try {				
				getContext().startActivity(intent);
				discoverMusicActivityPid();
				

			} catch (ActivityNotFoundException anfe) {
				Resources res = getContext().getResources();
				String text = String.format(
						res.getString(R.string.error_no_activity_found),
						intent.getType());
				Toast toast = Toast.makeText(getContext(), text,
						Toast.LENGTH_LONG);
				toast.show();
			}
		} else {
			Log.d(getClass().getName(),
					"Hey thats stange loaded item isn't an intent");
		}
	}

	private void discoverMusicActivityPid() {
		
		ActivityManager activityManager = (ActivityManager) getContext()
				.getSystemService(Context.ACTIVITY_SERVICE);
		List<RunningTaskInfo> services = activityManager
				.getRunningTasks(Integer.MAX_VALUE);
		List<RunningAppProcessInfo> apps = activityManager.getRunningAppProcesses();
		String packageName = services.get(0).topActivity.getPackageName(); //fist Task is the last started task		
		for (int i = 0; i < apps.size(); i++) {
		    if(apps.get(i).processName .equals(packageName)){
		    	musicAppPid = apps.get(i).pid;
		    	Log.d(getClass().getName(), "Found music activity process: " + apps.get(i).processName + " PID: " + musicAppPid);
		    }
			
		}
	}

	/* (non-Javadoc)
	 * @see de.yaacc.player.AbstractPlayer#onDestroy()
	 */
	@Override
	public void onDestroy() {		
		super.onDestroy();
		if(musicAppPid != 0){
			Process.killProcess(musicAppPid);
		}
	}

    @Override
    public URI getAlbumArt() {
        return null;
    }

    /*
     * (non-Javadoc)
     * @see de.yaacc.player.AbstractPlayer#getNotificationIntent()
     */
	@Override
	public PendingIntent getNotificationIntent(){
		Intent notificationIntent = new Intent(getContext(),
			    ThirdPartieMusicPlayerActivity.class);
			PendingIntent contentIntent = PendingIntent.getActivity(getContext(), 0,
			    notificationIntent, 0);
			return contentIntent;
	}
	
	/*
	 * (non-Javadoc)
	 * @see de.yaacc.player.AbstractPlayer#getNotificationId()
	 */
	@Override
	protected int getNotificationId() {
		 
		return NotificationId.LOCAL_THIRD_PARTIE_MUSIC_PLAYER.getId();
	}

    @Override
    public void seekTo(long millisecondsFromStart){
        Resources res = getContext().getResources();
        String text = String.format(
                res.getString(R.string.not_yet_implemented));
        Toast toast = Toast.makeText(getContext(), text,
                Toast.LENGTH_LONG);
        toast.show();
    }

}
