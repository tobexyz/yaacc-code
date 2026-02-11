package de.yaacc.player;

import android.content.ComponentName;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

import de.yaacc.R;
import de.yaacc.util.YaaccLogger;

public class MediaSessionTestActivity extends AppCompatActivity {
    private MediaController mediaController;
    private ListenableFuture<MediaController> controllerFuture;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_session_test);
        
        statusText = findViewById(R.id.statusText);
        Button btnConnect = findViewById(R.id.btnConnect);
        Button btnPlay = findViewById(R.id.btnPlay);
        Button btnPause = findViewById(R.id.btnPause);
        Button btnStop = findViewById(R.id.btnStop);
        
        btnConnect.setOnClickListener(v -> connectToService());
        btnPlay.setOnClickListener(v -> play());
        btnPause.setOnClickListener(v -> pause());
        btnStop.setOnClickListener(v -> stop());
        
        updateStatus("Ready to connect");
    }

    private void connectToService() {
        SessionToken sessionToken = new SessionToken(
            this,
            new ComponentName(this, YaaccMediaSessionService.class)
        );
        
        controllerFuture = new MediaController.Builder(this, sessionToken).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                mediaController = controllerFuture.get();
                updateStatus("Connected to MediaSessionService");
                YaaccLogger.d(getClass().getName(), "MediaController connected");
            } catch (ExecutionException | InterruptedException e) {
                updateStatus("Connection failed: " + e.getMessage());
                YaaccLogger.e(getClass().getName(), "Failed to connect", e);
            }
        }, this.getMainExecutor());
    }

    private void play() {
        if (mediaController == null) {
            updateStatus("Not connected");
            return;
        }
        
        // Test with a sample media item
        MediaItem mediaItem = MediaItem.fromUri("https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3");
        mediaController.setMediaItem(mediaItem);
        mediaController.prepare();
        mediaController.play();
        updateStatus("Playing test audio via ExoPlayer");
    }

    private void pause() {
        if (mediaController != null) {
            mediaController.pause();
            updateStatus("Paused");
        }
    }

    private void stop() {
        if (mediaController != null) {
            mediaController.stop();
            updateStatus("Stopped");
        }
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> statusText.setText(status));
        YaaccLogger.d(getClass().getName(), status);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaController != null) {
            MediaController.releaseFuture(controllerFuture);
        }
    }
}
