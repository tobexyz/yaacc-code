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

/**
 * all Yaacc-NotificationIDs.
 * @author Tobias Schoene (openbit)  
 *
 */
public enum NotificationId {
	LOCAL_BACKGROUND_MUSIC_PLAYER(1),
	AVTRANSPORT_PLAYER(2),
	LOCAL_IMAGE_PLAYER(3),
	MULTI_CONTENT_PLAYER(4),
	UPNP_SERVER(5),
	LOCAL_THIRD_PARTIE_MUSIC_PLAYER(6),
    FILE_DOWNLOADER(7);

	
	private int id=0;
    

	/**
	 * @param id
	 */
	private NotificationId(int id) {
		this.id = id;
	}
	
	public int getId(){
		return id;
	}
	
}
