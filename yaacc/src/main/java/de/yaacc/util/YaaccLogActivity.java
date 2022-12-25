package de.yaacc.util;

import android.app.Activity;
import android.app.ActionBar;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.os.Build;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import de.yaacc.R;

public class YaaccLogActivity extends Activity {
    public static void showLog(Activity activity) {
        activity.startActivity(new Intent(activity, YaaccLogActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_yaacc_log);


        displayLog();
    }


    /*
  * (non-Javadoc)
  *
  * @see android.app.Activity#onResume()
  */
    @Override
    protected void onResume() {
        super.onResume();
        displayLog();
    }

    private void displayLog() {
        TextView textView = (TextView) findViewById(R.id.yaaccLog_content);

        try {
            Process process = Runtime.getRuntime().exec("logcat -d");
            BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));

            StringBuilder log = new StringBuilder();
            String line = "";
            while ((line = bufferedReader.readLine()) != null) {
                log.insert(0,"\n");
                log.insert(0,line);
            }

            textView.setText(log.toString());
            textView.setTextIsSelectable(true);


        } catch (IOException e) {
            textView.setText("Error while reading log: " + e.getMessage());
        }
    }


}
