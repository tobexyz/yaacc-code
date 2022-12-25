/*
 *
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

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import de.yaacc.R;

/**
 * An about dialog for yaacc
 * 
 * @author Tobias Schoene (openbit)
 * 
 */
public class AboutActivity extends Activity {
	public static void showAbout(Activity activity) {
		activity.startActivity(new Intent(activity, AboutActivity.class));
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.about);
		try {
			String app_ver = this.getPackageManager().getPackageInfo(
					this.getPackageName(), 0).versionName;
			TextView textView = (TextView) findViewById(R.id.about_descrip);
			CharSequence aboutText = textView.getText();
			StringBuilder aboutTextBuilder = new StringBuilder();
			aboutTextBuilder.append("Yet Another Android Client Controller\nVersion: ").append(app_ver).append("\n\n")
					.append(aboutText);
			textView.setText(aboutTextBuilder.toString());
		} catch (NameNotFoundException e) {
			Log.d(getClass().getName(), "Can't find version", e);
		}

	}
}
