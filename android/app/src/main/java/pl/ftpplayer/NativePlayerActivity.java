package pl.ftpplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.interfaces.IVLCVout;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Embedded SurfaceView playback with touch and native Android TV focus. */
public final class NativePlayerActivity extends Activity implements IVLCVout.OnNewVideoLayoutListener {
    private static final int ACCENT = Color.rgb(34, 211, 238);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService exports = Executors.newSingleThreadExecutor();
    private LibVLC engine;
    private MediaPlayer player;
    private FrameLayout root, video;
    private SurfaceView surface, subtitles;
    private LinearLayout controls;
    private TextView status, time;
    private ProgressBar buffering;
    private SeekBar seek;
    private Button pause, previous, next;
    private TextView name;
    private JSONArray playlist = new JSONArray();
    private int playlistIndex = -1, requestGeneration, rotationMode;
    private long fileSize, bitrate, transferBps;
    private int bufferProfile, cacheMillis = 3500;
    private boolean tv, softwareMode, switched, playbackStarted, tuned, restoring, desiredPaused;
    private int restoredAudio = -2, restoredSubtitle = -2;
    private long audioDelay, subtitleDelay;
    private boolean metadataPending;
    private long nextMetadata;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private SharedPreferences positions;
    private String url, fileId, title;
    private long resumePosition;
    private boolean dragging, resumePending, resumeOnReturn, active;
    private int videoWidth, videoHeight, sarNum = 1, sarDen = 1, fitMode;
    private int windowWidth, windowHeight, appliedFitMode = -1;
    private float speed = 1f;
    private AudioManager audio;
    private boolean resumeAfterFocus;
    private AudioFocusRequest focusRequest;
    private long stallSince, lastBufferRestart;
    private int stallGeneration;
    private int decoderMode, persistentStalls;
    private boolean decoderRetried, audioDisabled, directTried, directActive, resolvingSource;
    private String proxyUrl;
    private long playerStartedAt;
    private String pendingDownloadUrl, pendingDownloadName;
    private final AudioManager.OnAudioFocusChangeListener audioFocus = change -> {
        if (player == null) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocus) {
            resumeAfterFocus = false;
            player.play();
        } else if (change < 0) {
            resumeAfterFocus = change != AudioManager.AUDIOFOCUS_LOSS && player.isPlaying();
            player.pause();
        }
    };

    /** Called by Pyjnius; Activity creation always runs on Android's UI thread. */
    public static void launch(Activity host, String url, String fileId, String title) {
        host.runOnUiThread(() -> host.startActivity(new Intent(host, NativePlayerActivity.class)
                .putExtra("url", url).putExtra("file_id", fileId).putExtra("title", title)));
    }

    public static void launch(Activity host, String url, String fileId, String title,
                              String playlistPath, long size) {
        host.runOnUiThread(() -> host.startActivity(new Intent(host, NativePlayerActivity.class)
                .putExtra("url", url).putExtra("file_id", fileId).putExtra("title", title)
                .putExtra("playlist_path", playlistPath).putExtra("size", size)));
    }

    @Override public void onCreate(Bundle state) {
        setTheme(android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        tv = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
                == Configuration.UI_MODE_TYPE_TELEVISION;
        rotationMode = state == null ? 0 : state.getInt("rotation");
        applyOrientation();
        url = getIntent().getStringExtra("url");
        proxyUrl = url;
        fileId = getIntent().getStringExtra("file_id");
        title = getIntent().getStringExtra("title");
        if (url == null || fileId == null) { finish(); return; }
        positions = getSharedPreferences("playback_positions", MODE_PRIVATE);
        resumePosition = state != null ? state.getLong("position") : positions.getLong(fileId, 0);
        resumePending = resumePosition > 0;
        fileSize = getIntent().getLongExtra("size", 0);
        loadPlaylist();
        decoderMode = getSharedPreferences("player_settings", MODE_PRIVATE).getInt("decoder", 0);
        bufferProfile = getSharedPreferences("player_settings", MODE_PRIVATE).getInt("buffer", 0);
        SharedPreferences metadata = getSharedPreferences("player_bitrates", MODE_PRIVATE);
        bitrate = metadata.getLong(fileId + ":size", -1) == fileSize ? metadata.getLong(fileId, 0) : 0;
        cacheMillis = PlaybackBufferPolicy.initialCache(bitrate, bufferProfile, tv, title);
        if (state != null) {
            fitMode = state.getInt("fit"); speed = state.getFloat("speed", 1f);
            cacheMillis = state.getInt("cache", cacheMillis); tuned = state.getBoolean("tuned");
            softwareMode = state.getBoolean("software"); desiredPaused = state.getBoolean("paused");
            restoredAudio = state.getInt("audio", -2); restoredSubtitle = state.getInt("subtitle", -2);
            audioDelay = state.getLong("audio_delay"); subtitleDelay = state.getLong("subtitle_delay");
            audioDisabled = state.getBoolean("audio_disabled");
            restoring = true;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildUi();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        requestMediaFocus();
        startPlayer(softwareMode || decoderMode == 2);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextColor(Color.WHITE); view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text); button.setContentDescription(text); button.setTextSize(17); button.setAllCaps(false);
        button.setTextColor(Color.WHITE); button.setPadding(dp(4), 0, dp(4), 0);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setFocusable(true); button.setOnClickListener(v -> { click.run(); keepControls(); });
        button.setOnFocusChangeListener((v, focused) -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(focused ? ACCENT : Color.rgb(38, 38, 43)); bg.setCornerRadius(dp(10));
            button.setBackground(bg); button.setTextColor(focused ? Color.BLACK : Color.WHITE);
            if (focused) ui.removeCallbacks(hideControls);
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(38, 38, 43)); bg.setCornerRadius(dp(10)); button.setBackground(bg);
        return button;
    }

    private void requestMediaFocus() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(audioFocus, ui).build();
            audio.requestAudioFocus(focusRequest);
        } else audio.requestAudioFocus(audioFocus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    private Button iconButton(int resource, String description, Runnable click) {
        Button action = button("", click);
        setIcon(action, resource, description);
        return action;
    }

    private void setIcon(Button action, int resource, String description) {
        android.graphics.drawable.Drawable icon = getResources().getDrawable(resource, getTheme()).mutate();
        icon.setTint(Color.WHITE); icon.setBounds(0, 0, dp(28), dp(28));
        action.setText(""); action.setContentDescription(description);
        action.setCompoundDrawables(icon, null, null, null);
        action.setGravity(Gravity.CENTER); action.setPadding(dp(12), 0, dp(8), 0);
    }

    private void buildUi() {
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        video = new FrameLayout(this); video.setClipChildren(true);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        surface = new SurfaceView(this);
        video.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        subtitles = new SurfaceView(this);
        subtitles.setZOrderMediaOverlay(true);
        subtitles.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        video.addView(subtitles, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> resizeVideo());
        video.setFocusable(true); video.setFocusableInTouchMode(true);
        video.setOnClickListener(v -> toggleControls());
        LinearLayout heading = new LinearLayout(this);
        heading.setPadding(dp(18), dp(10), dp(18), dp(10)); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setBackgroundColor(0xbb131316);
        heading.addView(button("Wstecz", this::finish), new LinearLayout.LayoutParams(dp(80), dp(44)));
        name = label(title, 18); name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setPadding(dp(16), 0, dp(16), 0);
        name.setTypeface(null, Typeface.BOLD);
        heading.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
        Button overflow = button("⋮", this::more); overflow.setTextSize(30);
        overflow.setContentDescription("Więcej opcji odtwarzacza");
        heading.addView(overflow, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(heading, new FrameLayout.LayoutParams(-1, dp(64), Gravity.TOP));
        heading.setTag("heading");
        LinearLayout loading = new LinearLayout(this); loading.setGravity(Gravity.CENTER);
        loading.setOrientation(LinearLayout.VERTICAL);
        buffering = new ProgressBar(this);
        loading.addView(buffering, new LinearLayout.LayoutParams(dp(40), dp(40)));
        status = label("Ładowanie filmu…", 16); status.setGravity(Gravity.CENTER);
        loading.addView(status, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(loading, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(8), dp(16), dp(16)); controls.setBackgroundColor(0xdd131316);
        time = label("0:00 / 0:00", 17);
        controls.addView(time, new LinearLayout.LayoutParams(-1, dp(24)));
        seek = new SeekBar(this); seek.setMax(1000);
        if (Build.VERSION.SDK_INT >= 21) {
            android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(ACCENT);
            seek.setProgressTintList(accent); seek.setThumbTintList(accent);
            buffering.setIndeterminateTintList(accent);
        }
        controls.addView(seek, new LinearLayout.LayoutParams(-1, dp(42)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; ui.removeCallbacks(hideControls); }
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (player != null && player.isSeekable()) player.setTime(bar.getProgress() * player.getLength() / 1000);
                keepControls();
            }
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && player != null) time.setText(formatTime(value * player.getLength() / 1000)
                        + " / " + formatTime(player.getLength()));
            }
        });
        pause = iconButton(android.R.drawable.ic_media_pause, "Pauza", this::togglePause);
        previous = iconButton(android.R.drawable.ic_media_previous, "Poprzedni film", () -> switchVideo(-1));
        next = iconButton(android.R.drawable.ic_media_next, "Następny film", () -> switchVideo(1));
        addRow(previous, iconButton(android.R.drawable.ic_media_rew, "Cofnij o 10 sekund", () -> jump(-10000)),
                pause, iconButton(android.R.drawable.ic_media_ff, "Przewiń o 10 sekund", () -> jump(10000)), next);
        updateNavigation();
        root.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root); video.requestFocus();
    }

    private void startPlayer(boolean software) {
        if (player != null) { resumePosition = player.getTime(); resumePending = resumePosition > 0; releasePlayer(); }
        ArrayList<String> options = new ArrayList<>();
        softwareMode = software; playbackStarted = false;
        playerStartedAt = android.os.SystemClock.elapsedRealtime();
        if (!restoring) desiredPaused = false;
        options.add("--network-caching=" + cacheMillis); options.add("--no-video-title-show");
        engine = new LibVLC(this, options); player = new MediaPlayer(engine);
        player.setAudioOutput("android_audiotrack");
        player.setAudioDigitalOutputEnabled(false);
        stallSince = 0; stallGeneration++;
        windowWidth = windowHeight = 0; appliedFitMode = -1;
        videoWidth = videoHeight = 0;
        player.setScale(0);
        IVLCVout output = player.getVLCVout();
        output.setVideoView(surface); output.setSubtitlesView(subtitles); output.attachViews(this);
        player.setEventListener(event -> {
            if (isFinishing()) return;
            switch (event.type) {
                case MediaPlayer.Event.Buffering:
                    boolean wait = event.getBuffering() < 100;
                    buffering.setVisibility(wait ? View.VISIBLE : View.GONE);
                    status.setText(wait ? "Buforowanie " + Math.round(event.getBuffering()) + "%" : "");
                    if (!wait) { stallSince = 0; stallGeneration++; }
                    else if (playbackStarted && !desiredPaused && stallSince == 0
                            && android.os.SystemClock.elapsedRealtime() - lastBufferRestart > 15000) {
                        stallSince = android.os.SystemClock.elapsedRealtime();
                        final int generation = ++stallGeneration;
                        final MediaPlayer stalled = player;
                        ui.postDelayed(() -> {
                            if (generation != stallGeneration || player != stalled || desiredPaused
                                    || isDestroyed() || stallSince == 0) return;
                            persistentStalls++;
                            if (persistentStalls >= 2 && !directTried && !directActive && proxyBase() != null) {
                                lastBufferRestart = android.os.SystemClock.elapsedRealtime();
                                useDirectSource(); return;
                            }
                            int target = PlaybackBufferPolicy.afterStall(cacheMillis, (long) (bitrate * speed), bufferProfile, tv);
                            if (target > cacheMillis + 500) {
                                lastBufferRestart = android.os.SystemClock.elapsedRealtime();
                                tuned = true; restartWithBuffer(target);
                            }
                        }, 1800);
                    }
                    break;
                case MediaPlayer.Event.Playing:
                    playbackStarted = true;
                    setIcon(pause, android.R.drawable.ic_media_pause, "Pauza"); resizeVideo(); applyResume();
                    player.setRate(speed);
                    if (restoring) {
                        restoring = false;
                        restoreAudioTrack();
                        if (restoredSubtitle != -2) player.setSpuTrack(restoredSubtitle);
                        player.setAudioDelay(audioDelay); player.setSpuDelay(subtitleDelay);
                        if (desiredPaused) {
                            MediaPlayer restored = player;
                            // Pierwsza klatka musi trafić do nowej powierzchni po obrocie.
                            ui.postDelayed(() -> {
                                if (player == restored && desiredPaused && !isDestroyed()) restored.pause();
                            }, 350);
                        }
                    }
                    keepControls(); break;
                case MediaPlayer.Event.Paused: setIcon(pause, android.R.drawable.ic_media_play, "Odtwórz"); break;
                case MediaPlayer.Event.EndReached:
                    resumePending = false; resumePosition = 0;
                    positions.edit().remove(fileId).apply(); buffering.setVisibility(View.GONE);
                    status.setText("Koniec filmu"); showControls(); break;
                case MediaPlayer.Event.EncounteredError:
                    buffering.setVisibility(View.GONE); status.setText("Nie udało się odtworzyć filmu");
                    if (!software && !decoderRetried && decoderMode == 0) {
                        decoderRetried = true;
                        ui.post(() -> restartDecoder(true));
                    } else showPlaybackError(software);
                    break;
            }
        });
        Media media = new Media(engine, Uri.parse(url));
        media.setHWDecoderEnabled(!software && decoderMode != 2, false);
        media.addOption(":http-reconnect");
        player.setMedia(media); media.release(); player.play();
        buffering.setVisibility(View.VISIBLE); status.setText("Ładowanie filmu…");
    }

    private void addRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        for (Button action : buttons) {
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, dp(50), action == pause ? 1.25f : 1f);
            layout.setMargins(dp(3), dp(3), dp(3), dp(3)); row.addView(action, layout);
        }
        controls.addView(row);
    }

    private void applyOrientation() {
        int orientation = tv ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : rotationMode == 1 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : rotationMode == 2 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        setRequestedOrientation(orientation);
    }

    private void chooseRotation() {
        if (tv) { toast("Telewizor korzysta z poziomego obrazu"); return; }
        new AlertDialog.Builder(this).setTitle("Obrót ekranu")
                .setSingleChoiceItems(new String[]{"Automatyczny", "Pionowo", "Poziomo"}, rotationMode, (d, which) -> {
                    rotationMode = which; d.dismiss(); applyOrientation();
                }).show();
    }

    private void chooseBuffer() {
        new AlertDialog.Builder(this).setTitle("Bufor sieciowy")
                .setSingleChoiceItems(new String[]{"Automatyczny — według bitrate", "Szybki start", "Większy bufor"},
                        bufferProfile, (d, which) -> {
                    bufferProfile = which;
                    getSharedPreferences("player_settings", MODE_PRIVATE).edit().putInt("buffer", which).apply();
                    d.dismiss(); tuned = false;
                    restartWithBuffer(PlaybackBufferPolicy.cacheMillis((long) (bitrate * speed), which, tv));
                    nextMetadata = 0;
                }).show();
    }

    private void restartWithBuffer(int target) {
        if (player == null || isFinishing() || isDestroyed()) return;
        restoredAudio = audioDisabled ? -1 : Math.max(-2, player.getAudioTrack() < 0 ? -2 : player.getAudioTrack()); restoredSubtitle = player.getSpuTrack();
        audioDelay = player.getAudioDelay(); subtitleDelay = player.getSpuDelay(); restoring = true;
        cacheMillis = target; startPlayer(softwareMode);
    }

    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }

    private String proxyBase() { return null; }

    private JSONObject getJson(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(4000); connection.setReadTimeout(15000);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() + count > 65536) throw new java.io.IOException("Control reply too large");
                output.write(bytes, 0, count);
            }
            return new JSONObject(output.toString("UTF-8"));
        } finally { connection.disconnect(); }
    }

    private void refreshMetadata(long duration) {
        if (duration <= 0 || metadataPending || android.os.SystemClock.elapsedRealtime() < nextMetadata) return;
        nextMetadata = android.os.SystemClock.elapsedRealtime() + 5000;
        final int generation = requestGeneration;
        final String id = fileId, base = proxyBase();
        final String address = url;
        final long size = fileSize;
        final float rate = speed;
        final int profile = bufferProfile;
        metadataPending = true;
        network.execute(() -> {
            long total = 0, measured = 0;
            try {
                if (base != null) {
                    JSONObject status = getJson(base + "/__status?file_id=" + encode(id));
                    total = status.optLong("total"); measured = status.optLong("download_bps");
                } else if ("file".equals(Uri.parse(address).getScheme())) total = size;
                long estimate = PlaybackBufferPolicy.bitrate(total, duration);
                if (estimate > 0 && base != null) {
                    int target = PlaybackBufferPolicy.cacheMillis((long) (estimate * rate), profile, tv);
                    getJson(base + "/__buffer?file_id=" + encode(id) + "&bitrate=" + (long) (estimate * rate)
                            + "&seconds=" + Math.max(8, target / 1000));
                }
                final long value = estimate, throughput = measured;
                ui.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    bitrate = value; transferBps = throughput;
                    if (value > 0) {
                        SharedPreferences metadata = getSharedPreferences("player_bitrates", MODE_PRIVATE);
                        SharedPreferences.Editor edit = metadata.edit();
                        if (metadata.getAll().size() > 400) edit.clear();
                        edit.putLong(id, value).putLong(id + ":size", size).apply();
                    }
                });
            } catch (Exception ignored) {
                // Starszy serwis nie obsługuje metadanych: film nadal się odtwarza.
            } finally { ui.post(() -> metadataPending = false); }
        });
    }

    private void loadPlaylist() {
        try {
            String path = getIntent().getStringExtra("playlist_path");
            String json = getIntent().getStringExtra("playlist");
            if (path != null && !path.isEmpty()) {
                File file = new File(path);
                // Wyłącznie własny cache aplikacji, bez dowolnych ścieżek Intent.
                if (!file.getCanonicalPath().startsWith(getCacheDir().getCanonicalPath() + File.separator)
                        || file.length() > 16 * 1024 * 1024) return;
                try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                    json = output.toString("UTF-8");
                }
            }
            if (json != null) playlist = new JSONArray(json);
            for (int i = 0; i < playlist.length(); i++)
                if (fileId.equals(playlist.getJSONObject(i).optString("id"))) { playlistIndex = i; break; }
        } catch (Exception ignored) { playlist = new JSONArray(); }
    }

    private void updateNavigation() {
        previous.setEnabled(!switched && playlistIndex > 0);
        next.setEnabled(!switched && playlistIndex >= 0 && playlistIndex + 1 < playlist.length());
        previous.setAlpha(previous.isEnabled() ? 1f : 0.45f); next.setAlpha(next.isEnabled() ? 1f : 0.45f);
        name.setText((playlistIndex >= 0 ? (playlistIndex + 1) + "/" + playlist.length() + " · " : "") + title);
    }

    private void switchVideo(int direction) {
        int index = playlistIndex + direction;
        if (switched || playlistIndex < 0 || index < 0 || index >= playlist.length()) return;
        JSONObject item = playlist.optJSONObject(index); if (item == null) return;
        final String base = proxyBase();
        final int generation = ++requestGeneration;
        final boolean playing = player != null && player.isPlaying();
        savePosition(); if (player != null) player.pause();
        switched = true; updateNavigation(); status.setText("Przygotowanie kolejnego filmu…");
        buffering.setVisibility(View.VISIBLE);
        network.execute(() -> {
            try {
                String address = item.optString("url");
                if (address.isEmpty()) {
                    if (base == null) throw new java.io.IOException("No stream proxy");
                    address = getJson(base + "/__register?file_id=" + encode(item.getString("id"))
                            + "&name=" + encode(item.getString("name")) + "&folder_id=" + item.optLong("folder_id")
                            + "&size=" + item.optLong("size")).getString("url");
                }
                final String resolved = address;
                ui.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    releasePlayer(); url = resolved; proxyUrl = resolved; directActive = directTried = decoderRetried = audioDisabled = false; persistentStalls = 0; restoredAudio = -2; fileId = item.optString("id"); title = item.optString("name");
                    fileSize = item.optLong("size"); playlistIndex = index;
                    getIntent().putExtra("url", url).putExtra("file_id", fileId).putExtra("title", title).putExtra("size", fileSize);
                    resumePosition = positions.getLong(fileId, 0); resumePending = resumePosition > 0;
                    SharedPreferences meta = getSharedPreferences("player_bitrates", MODE_PRIVATE);
                    bitrate = meta.getLong(fileId + ":size", -1) == fileSize ? meta.getLong(fileId, 0) : 0;
                    cacheMillis = PlaybackBufferPolicy.initialCache((long) (bitrate * speed), bufferProfile, tv, title);
                    tuned = false; restoring = false; nextMetadata = 0; transferBps = 0;
                    switched = false; updateNavigation(); startPlayer(false); showControls();
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    switched = false; updateNavigation(); buffering.setVisibility(View.GONE);
                    status.setText("Nie udało się przygotować kolejnego filmu");
                    if (playing && player != null) player.play();
                    toast("Nie udało się otworzyć filmu. Spróbuj ponownie.");
                });
            }
        });
    }

    private void restoreAudioTrack() {
        if (player == null || !playbackStarted) return;
        if (audioDisabled) { if (player.getAudioTrack() != -1) player.setAudioTrack(-1); return; }
        MediaPlayer.TrackDescription[] tracks = player.getAudioTracks();
        if (tracks == null) return;
        for (MediaPlayer.TrackDescription track : tracks) {
            if (track.id >= 0 && track.id == restoredAudio) {
                player.setAudioTrack(track.id); restoredAudio = -2; return;
            }
        }
        if (player.getAudioTrack() < 0) {
            for (MediaPlayer.TrackDescription track : tracks) {
                if (track.id >= 0) { player.setAudioTrack(track.id); restoredAudio = -2; break; }
            }
        }
    }

    private void restartDecoder(boolean software) {
        if (player == null || isDestroyed()) return;
        restoredAudio = audioDisabled ? -1 : player.getAudioTrack();
        if (!audioDisabled && restoredAudio < 0) restoredAudio = -2;
        restoredSubtitle = player.getSpuTrack(); audioDelay = player.getAudioDelay(); subtitleDelay = player.getSpuDelay();
        restoring = true; startPlayer(software);
    }

    private void chooseDecoder() {
        new AlertDialog.Builder(this).setTitle("Dekodowanie obrazu")
            .setSingleChoiceItems(new String[]{"Automatyczne z próbą programową", "Sprzętowe", "Programowe — zgodność"}, decoderMode, (d, mode) -> {
                decoderMode = mode; decoderRetried = false;
                getSharedPreferences("player_settings", MODE_PRIVATE).edit().putInt("decoder", mode).apply();
                d.dismiss(); restartDecoder(mode == 2);
            }).show();
    }

    private void chooseConnection() {
        new AlertDialog.Builder(this).setTitle("Sposób połączenia")
            .setItems(new String[]{"Bezpośrednio — pomiń pośredni bufor", "Przez bufor aplikacji"}, (d, mode) -> {
                if (mode == 0) useDirectSource();
                else if (proxyUrl != null) { url = proxyUrl; directActive = false; restartDecoder(softwareMode); }
            }).show();
    }

    private void useDirectSource() {
        final String base = proxyBase(), id = fileId;
        if (base == null || resolvingSource) return;
        final int generation = requestGeneration;
        resolvingSource = true; directTried = true;
        toast("Sprawdzanie połączenia bezpośredniego…");
        network.execute(() -> {
            try {
                final String original = getJson(base + "/__download?file_id=" + encode(id)).getString("url");
                ui.post(() -> {
                    if (generation != requestGeneration || isDestroyed()) return;
                    url = original; directActive = true; restartDecoder(softwareMode);
                    toast("Połączenie bezpośrednie z oryginalnym plikiem");
                });
            } catch (Exception error) { ui.post(() -> toast("Pozostawiono dotychczasowe połączenie")); }
            finally { ui.post(() -> resolvingSource = false); }
        });
    }

    private void showPlaybackError(boolean software) {
        new AlertDialog.Builder(this).setTitle("Odtwarzanie niedostępne")
                .setMessage("Przyczyną może być połączenie lub kodek. Dekodowanie programowe "
                        + "może pomóc, ale przy ciężkich plikach wymaga więcej mocy urządzenia.")
                .setPositiveButton(software ? "Ponów" : "Spróbuj programowo", (d, w) -> restartDecoder(true))
                .setNeutralButton("Odtwarzacz zewnętrzny", (d, w) -> openExternal())
                .setNegativeButton("Wróć", (d, w) -> finish()).show();
    }

    private void applyResume() {
        if (resumePending && player != null && player.isSeekable() && player.getLength() > 0) {
            if (resumePosition < player.getLength() - 5000) player.setTime(resumePosition);
            resumePending = false;
        }
    }

    private void togglePause() {
        if (player == null) return;
        desiredPaused = player.isPlaying();
        if (desiredPaused) player.pause(); else player.play();
        showControls();
    }

    private void jump(long delta) {
        if (player != null && player.isSeekable()) {
            player.setTime(Math.max(0, Math.min(Math.max(0, player.getLength() - 1000), player.getTime() + delta)));
        }
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600,
                seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private final Runnable update = new Runnable() {
        public void run() {
            if (!active || player == null) return;
            long duration = player.getLength(), position = player.getTime();
            if (videoWidth == 0 || videoHeight == 0) resizeVideo();
            applyResume(); restoreAudioTrack(); refreshMetadata(duration);
            if (!softwareMode && !decoderRetried && decoderMode == 0 && playbackStarted
                    && !desiredPaused && player.getVideoTracksCount() > 0 && videoWidth == 0 && player.getTime() > 1000
                    && android.os.SystemClock.elapsedRealtime() - playerStartedAt > 10000) {
                decoderRetried = true; restartDecoder(true); ui.postDelayed(this, 500); return;
            } seek.setEnabled(duration > 0 && player.isSeekable());
            if (!dragging) {
                time.setText(formatTime(position) + " / " + formatTime(duration));
                seek.setProgress(duration > 0 ? (int) (position * 1000 / duration) : 0);
            }
            ui.postDelayed(this, 500);
        }
    };
    private final Runnable hideControls = () -> {
        if (player != null && player.isPlaying() && !dragging && video.hasFocus()) {
            controls.setVisibility(View.GONE); root.findViewWithTag("heading").setVisibility(View.GONE);
        }
    };
    private void keepControls() { ui.removeCallbacks(hideControls); ui.postDelayed(hideControls, 5000); }
    private void showControls() {
        controls.setVisibility(View.VISIBLE); root.findViewWithTag("heading").setVisibility(View.VISIBLE); keepControls();
    }
    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE) {
            controls.setVisibility(View.GONE); root.findViewWithTag("heading").setVisibility(View.GONE); video.requestFocus();
        } else showControls();
    }

    private void chooseAspect() {
        String[] modes = {"Dopasuj — pełny obraz", "Wypełnij — przytnij brzegi", "16:9", "4:3"};
        new AlertDialog.Builder(this).setTitle("Proporcje obrazu")
                .setSingleChoiceItems(modes, fitMode, (dialog, which) -> {
                    fitMode = which; resizeVideo(); dialog.dismiss();
                }).show();
    }

    private void chooseTrack(boolean subtitle) {
        if (player == null) return;
        MediaPlayer.TrackDescription[] tracks = subtitle ? player.getSpuTracks() : player.getAudioTracks();
        if (tracks == null || tracks.length == 0) { toast("Brak dostępnych ścieżek"); return; }
        String[] names = new String[tracks.length]; int selected = -1;
        int current = subtitle ? player.getSpuTrack() : player.getAudioTrack();
        for (int i = 0; i < tracks.length; i++) {
            names[i] = tracks[i].id == -1 ? "Wyłączone" : tracks[i].name;
            if (tracks[i].id == current) selected = i;
        }
        new AlertDialog.Builder(this).setTitle(subtitle ? "Ścieżka napisów" : "Ścieżka dźwiękowa")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    if (player != null) {
                        if (subtitle) player.setSpuTrack(tracks[which].id);
                        else { audioDisabled = tracks[which].id < 0; restoredAudio = tracks[which].id; player.setAudioTrack(tracks[which].id); }
                    }
                    dialog.dismiss();
                }).show();
    }

    private void more() {
        String[] options = {"Proporcje obrazu", "Ścieżka dźwiękowa", "Napisy", "Obrót ekranu", "Bufor sieciowy",
                "Prędkość odtwarzania", "Opóźnienie dźwięku", "Opóźnienie napisów",
                "Zapisz zrzut ekranu", "Informacje", "Dekodowanie obrazu", "Odtwarzacz zewnętrzny"};
        new AlertDialog.Builder(this).setTitle("Opcje odtwarzacza").setItems(options, (d, which) -> {
            switch (which) {
                case 0: chooseAspect(); break;
                case 1: chooseTrack(false); break;
                case 2: chooseTrack(true); break;
                case 3: chooseRotation(); break;
                case 4: chooseBuffer(); break;
                case 5: chooseSpeed(); break;
                case 6: chooseDelay(false); break;
                case 7: chooseDelay(true); break;
                case 8: screenshot(); break;
                case 9: new AlertDialog.Builder(this).setTitle("Odtwarzacz")
                    .setMessage("Wbudowany libVLC 3.6.5\nBufor: " + cacheMillis + " ms\nBitrate: "
                            + (bitrate > 0 ? String.format(Locale.ROOT, "%.1f Mb/s", bitrate / 1_000_000d) : "ustalanie")
                            + "\nTransfer: " + (transferBps > 0 ? String.format(Locale.ROOT, "%.1f Mb/s", transferBps / 1_000_000d) : "brak pomiaru")
                            + "\nPrędkość: " + speed + "×")
                    .setPositiveButton("OK", null).show(); break;
                case 10: chooseDecoder(); break;
                default: openExternal();
            }
        }).show();
    }

    private void downloadCurrent() {
        final String base = proxyBase(), id = fileId, filename = title;
        if (base == null) { toast("Pobierz plik z listy folderu"); return; }
        toast("Przygotowanie pobierania…");
        network.execute(() -> {
            try {
                String source = getJson(base + "/__download?file_id=" + encode(id)).getString("url");
                ui.post(() -> queueDownload(source, filename));
            } catch (Exception error) { ui.post(() -> toast("Nie udało się rozpocząć pobierania")); }
        });
    }

    private void queueDownload(String source, String filename) {
        if (isDestroyed() || isFinishing()) return;
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = source; pendingDownloadName = filename;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 608);
            return;
        }
        try { NativeDownloads.enqueue(this, source, filename); toast("Dodano do pobierania — sprawdź powiadomienia"); }
        catch (Exception error) { toast("Nie udało się rozpocząć pobierania"); }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 608 && pendingDownloadUrl != null) {
            try {
                NativeDownloads.enqueue(this, pendingDownloadUrl, pendingDownloadName);
                toast("Dodano do pobierania — sprawdź powiadomienia");
            } catch (Exception error) { toast("Nie udało się rozpocząć pobierania"); }
            pendingDownloadUrl = pendingDownloadName = null;
        }
    }

    private void chooseSpeed() {
        float[] rates = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        String[] names = {"0,5×", "0,75×", "1×", "1,25×", "1,5×", "2×"};
        new AlertDialog.Builder(this).setTitle("Prędkość").setItems(names, (d, which) -> {
            if (player != null) { speed = rates[which]; player.setRate(speed); }
        }).show();
    }

    private void chooseDelay(boolean subtitle) {
        long current = player == null ? 0 : (subtitle ? player.getSpuDelay() : player.getAudioDelay()) / 1000;
        new AlertDialog.Builder(this).setTitle("Opóźnienie " + (subtitle ? "napisów" : "dźwięku")
                + ": " + current + " ms")
                .setItems(new String[]{"−100 ms", "Wyzeruj", "+100 ms"}, (d, which) -> {
                    if (player == null) return;
                    long delay = which == 1 ? 0 : (current + (which == 0 ? -100 : 100)) * 1000;
                    if (subtitle) player.setSpuDelay(delay); else player.setAudioDelay(delay);
                }).show();
    }

    private void openExternal() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*"));
        } catch (android.content.ActivityNotFoundException missing) { toast("Brak zewnętrznego odtwarzacza"); }
    }

    private void screenshot() {
        if (Build.VERSION.SDK_INT < 26) { toast("Zrzuty wymagają Androida 8 lub nowszego"); return; }
        if (surface.getWidth() <= 0 || surface.getHeight() <= 0) return;
        float scale = Math.min(1f, Math.min(1920f / surface.getWidth(), 1080f / surface.getHeight()));
        int width = Math.max(1, Math.round(surface.getWidth() * scale));
        int height = Math.max(1, Math.round(surface.getHeight() * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        PixelCopy.request(surface, bitmap, result -> {
            if (result != PixelCopy.SUCCESS) { bitmap.recycle(); toast("Zrzut jest chwilowo niedostępny"); return; }
            if (isFinishing() || isDestroyed() || exports.isShutdown()) { bitmap.recycle(); return; }
            exports.execute(() -> saveScreenshot(bitmap));
        }, ui);
    }

    private void saveScreenshot(Bitmap bitmap) {
        Uri inserted = null;
        try {
            String filename = "FTPPlayer-" + System.currentTimeMillis() + ".png";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FTP Player");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                inserted = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (inserted == null) throw new java.io.IOException("No media destination");
                try (OutputStream output = getContentResolver().openOutputStream(inserted)) {
                    if (output == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                        throw new java.io.IOException("Cannot save screenshot");
                }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(inserted, values, null, null);
                ui.post(() -> toast("Zapisano w Pictures/FTP Player"));
            } else {
                File folder = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (folder == null) throw new java.io.IOException("No storage");
                try (OutputStream output = new FileOutputStream(new File(folder, filename))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                }
                ui.post(() -> toast("Zapisano w folderze Pictures aplikacji"));
            }
        } catch (Exception error) {
            if (inserted != null) getContentResolver().delete(inserted, null, null);
            ui.post(() -> toast("Nie udało się zapisać zrzutu"));
        } finally { bitmap.recycle(); }
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    @Override public void onNewVideoLayout(IVLCVout output, int width, int height,
            int visibleWidth, int visibleHeight, int numerator, int denominator) {
        videoWidth = visibleWidth > 0 ? visibleWidth : width;
        videoHeight = visibleHeight > 0 ? visibleHeight : height;
        sarNum = numerator > 0 ? numerator : 1; sarDen = denominator > 0 ? denominator : 1;
        resizeVideo();
    }

    private void resizeVideo() {
        if (player == null || video.getWidth() == 0 || video.getHeight() == 0) return;
        // Nie każdy dekoder dostarcza OnNewVideoLayout (np. część ścieżek WMV).
        if (videoWidth == 0 || videoHeight == 0) {
            IMedia.VideoTrack track = player.getCurrentVideoTrack();
            if (track != null && track.width > 0 && track.height > 0) {
                videoWidth = track.width; videoHeight = track.height;
                sarNum = track.sarNum > 0 ? track.sarNum : 1;
                sarDen = track.sarDen > 0 ? track.sarDen : 1;
            }
        }
        if ((videoWidth == 0 || videoHeight == 0) && fitMode < 2) {
            updateOutputSize(video.getWidth(), video.getHeight());
            return;
        }
        double ratio = fitMode == 2 ? 16.0 / 9 : fitMode == 3 ? 4.0 / 3
                : (double) videoWidth * sarNum / (videoHeight * sarDen);
        double width = video.getWidth(), height = width / ratio;
        boolean scaleToHeight = fitMode == 1 ? height < video.getHeight() : height > video.getHeight();
        if (scaleToHeight) { height = video.getHeight(); width = height * ratio; }
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams((int) Math.round(width),
                (int) Math.round(height), Gravity.CENTER);
        FrameLayout.LayoutParams previous = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (previous.width != layout.width || previous.height != layout.height) {
            surface.setLayoutParams(layout);
            subtitles.setLayoutParams(new FrameLayout.LayoutParams(layout));
        }
        // VLC musi znać wymiary SurfaceView, inaczej letterbox trafia poza nią w pionie.
        updateOutputSize(layout.width, layout.height);
        if (appliedFitMode != fitMode) {
            appliedFitMode = fitMode;
            player.setAspectRatio(fitMode == 2 ? "16:9" : fitMode == 3 ? "4:3" : null);
        }
    }

    private void updateOutputSize(int width, int height) {
        if (windowWidth != width || windowHeight != height) {
            windowWidth = width; windowHeight = height;
            player.getVLCVout().setWindowSize(width, height);
        }
    }

    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); resizeVideo(); }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_MEDIA_NEXT || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            switchVideo(key == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1); return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_SPACE) { togglePause(); return true; }
        if (key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || key == KeyEvent.KEYCODE_MEDIA_REWIND) {
            jump(key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 10000 : -10000); return true;
        }
        if (video.hasFocus() || controls.getVisibility() != View.VISIBLE) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                jump(key == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000); return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) { togglePause(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_MENU) {
                showControls(); pause.requestFocus(); return true;
            }
        }
        return super.onKeyDown(key, event);
    }

    @Override public void onBackPressed() {
        if (controls.getVisibility() == View.VISIBLE && !video.hasFocus()) {
            toggleControls(); return;
        }
        super.onBackPressed();
    }

    private void savePosition() {
        if (player == null) return;
        long position = player.getTime(), length = player.getLength();
        SharedPreferences.Editor edit = positions.edit();
        if (position > 5000 && length > position + 5000) edit.putLong(fileId, position);
        else if (!resumePending) edit.remove(fileId);
        // Historia nie może rosnąć bez końca na małym urządzeniu.
        if (positions.getAll().size() > 200) {
            int remove = positions.getAll().size() - 160;
            for (String key : positions.getAll().keySet()) {
                if (!key.equals(fileId) && remove-- > 0) edit.remove(key);
            }
        }
        edit.apply();
    }

    @Override protected void onResume() {
        super.onResume(); requestMediaFocus(); active = true; ui.removeCallbacks(update); ui.post(update);
        if (resumeOnReturn && player != null) { resumeOnReturn = false; player.play(); }
    }
    @Override protected void onPause() {
        active = false; ui.removeCallbacks(update); ui.removeCallbacks(hideControls);
        if (player != null) { savePosition(); resumeOnReturn = player.isPlaying() && !desiredPaused; player.pause(); }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        if (player != null) {
            out.putLong("position", player.getTime());
            out.putBoolean("paused", !(player.isPlaying() || resumeOnReturn));
            out.putInt("audio", audioDisabled ? -1 : (player.getAudioTrack() < 0 ? -2 : player.getAudioTrack())); out.putInt("subtitle", player.getSpuTrack());
            out.putBoolean("audio_disabled", audioDisabled);
            out.putLong("audio_delay", player.getAudioDelay()); out.putLong("subtitle_delay", player.getSpuDelay());
        }
        out.putInt("fit", fitMode); out.putFloat("speed", speed); out.putInt("rotation", rotationMode);
        out.putInt("cache", cacheMillis); out.putBoolean("tuned", tuned); out.putBoolean("software", softwareMode);
        super.onSaveInstanceState(out);
    }
    private void releasePlayer() {
        if (player != null) {
            player.setEventListener(null); player.stop(); player.getVLCVout().detachViews(); player.release(); player = null;
        }
        if (engine != null) { engine.release(); engine = null; }
    }
    @Override protected void onDestroy() {
        active = false; ui.removeCallbacksAndMessages(null); releasePlayer();
        if (audio != null) {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
            else audio.abandonAudioFocus(audioFocus);
        }
        requestGeneration++; network.shutdownNow(); exports.shutdown(); super.onDestroy();
    }
}
