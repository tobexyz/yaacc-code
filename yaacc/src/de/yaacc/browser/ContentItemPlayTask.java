package de.yaacc.browser;

import android.content.Intent;
import android.os.AsyncTask;

import de.yaacc.player.PlayableItem;

public class ContentItemPlayTask extends AsyncTask<Integer, Void,Void> {
    public final static int PLAY_CURRENT=0;
    public final static int PLAY_ALL=1;
    private final ContentListClickListener parent;

    public ContentItemPlayTask(ContentListClickListener parent){
        this.parent = parent;
    }
    @Override
    public Void doInBackground(Integer... integers){
        if (integers == null || integers.length != 1){
            return null;
        }
        if (integers[0] == PLAY_CURRENT) {
            parent.playCurrent();
        } else if(integers[0] == PLAY_ALL) {
            parent.playAll();
        }
        return null;
    }
}
