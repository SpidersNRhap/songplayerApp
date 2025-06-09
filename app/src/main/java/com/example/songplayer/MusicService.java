package com.example.songplayer;

import android.app.Service;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.app.Notification;

import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.media.MediaMetadataRetriever;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Call;

import java.io.File;


public class MusicService extends Service {
    // Media and playback
    private MediaPlayer mediaPlayer;
    private List<String> playlist;
    private List<SongNode> playlistNodes;
    private int currentIndex = 0;
    private boolean isShuffle = false, isLoop = false;
    private final Random random = new Random();
    private PlaybackListener playbackListener;
    private MediaSessionCompat mediaSession;

    // Notification and album art
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "media_playback_channel";
    private Bitmap currentAlbumArt = null;
    private Bitmap defaultAlbumArt;

    // Networking and config
    private String publicIp, port, apiKey, token;

    // Timeout handling
    private Handler prepareTimeoutHandler;
    private Runnable prepareTimeoutRunnable;

    // Album art cache
    private final HashMap<String, Bitmap> albumArtCache = new HashMap<>();

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public MusicService getService() { return MusicService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        mediaSession = new MediaSessionCompat(getApplicationContext(), "SongPlayerSession");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { playNext(); }
            @Override public void onSkipToPrevious() { playPrevious(); }
        });
        mediaSession.setActive(true);
        defaultAlbumArt = BitmapFactory.decodeResource(getResources(), R.drawable.default_art);
        createNotificationChannel();

        SharedPreferences prefs = getSharedPreferences("music_service_prefs", MODE_PRIVATE);
        currentIndex = prefs.getInt("currentIndex", 0);
        String playlistStr = prefs.getString("playlist", null);
        if (playlistStr != null) {
            playlist = java.util.Arrays.asList(playlistStr.split(";;"));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mediaSession, intent);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stop();
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }


    public void setPlaylist(List<SongNode> nodes, int index, boolean shuffle, boolean loop) {
        this.playlistNodes = nodes;
        this.currentIndex = index;
        this.isShuffle = shuffle;
        this.isLoop = loop;
        playSongWithFreshToken(index, nodes, shuffle, loop);
    }

    public void setShuffle(boolean shuffle) { isShuffle = shuffle; }
    public void setLoop(boolean loop) { isLoop = loop; }
    public boolean isShuffle() { return isShuffle; }
    public boolean isLoop() { return isLoop; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void showMediaNotification(boolean isPlaying, String title) {
        Bitmap albumArt = currentAlbumArt != null ? currentAlbumArt : defaultAlbumArt;
        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playPauseText = isPlaying ? "Pause" : "Play";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(title != null ? title : "Playing")
                .setContentIntent(contentIntent)
                .addAction(new NotificationCompat.Action(
                        android.R.drawable.ic_media_previous, "Previous",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(getApplicationContext(), PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)))
                .addAction(new NotificationCompat.Action(
                        playPauseIcon, playPauseText,
                        MediaButtonReceiver.buildMediaButtonPendingIntent(getApplicationContext(), isPlaying ? PlaybackStateCompat.ACTION_PAUSE : PlaybackStateCompat.ACTION_PLAY)))
                .addAction(new NotificationCompat.Action(
                        android.R.drawable.ic_media_next, "Next",
                        MediaButtonReceiver.buildMediaButtonPendingIntent(getApplicationContext(), PlaybackStateCompat.ACTION_SKIP_TO_NEXT)))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)
                        .setShowCancelButton(false)
                )
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        // Set album art as large icon and background if available
        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
        if (albumArt != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt);
        }
        mediaSession.setMetadata(metaBuilder.build());
        Notification notification = builder.build();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void updateNotification() {
        String title = "Playing";
        if (playlist != null && !playlist.isEmpty()) {
            String url = playlist.get(currentIndex);
            title = extractSongTitle(url);
        }
        showMediaNotification(isPlaying(), title);
    }

    private String extractSongTitle(String url) {
        int q = url.indexOf('?');
        if (q != -1) url = url.substring(0, q);
        int lastSlash = url.lastIndexOf('/');
        String title = (lastSlash != -1 && lastSlash < url.length() - 1) ? url.substring(lastSlash + 1) : url;
        try { title = java.net.URLDecoder.decode(title, "UTF-8"); } catch (Exception ignored) {}
        String[] exts = {".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a"};
        for (String ext : exts) {
            if (title.toLowerCase().endsWith(ext)) {
                title = title.substring(0, title.length() - ext.length());
                break;
            }
        }
        return title;
    }

    private void playCurrent() {
        stop();
        if (playlist == null || playlist.isEmpty()) return;
        String url = playlist.get(currentIndex);
        String title = extractSongTitle(url);
        Bitmap albumArt = getAlbumArt(url);
        currentAlbumArt = albumArt;

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(url);

            // Timeout logic
            if (prepareTimeoutHandler == null) {
                prepareTimeoutHandler = new Handler(getMainLooper());
            }
            if (prepareTimeoutRunnable != null) {
                prepareTimeoutHandler.removeCallbacks(prepareTimeoutRunnable);
            }
            prepareTimeoutRunnable = () -> {
                Log.e("SongPlayerDBG", "MediaPlayer prepareAsync() timeout");
                if (mediaPlayer != null) {
                    mediaPlayer.reset();
                    mediaPlayer.release();
                    mediaPlayer = null;
                }
                if (playbackListener != null) {
                    playbackListener.onPlaybackError(currentIndex, url, -1, -1);
                }
                mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(getAvailableActions())
                    .setState(PlaybackStateCompat.STATE_ERROR, 0, 1.0f)
                    .build());
                updateNotification();
                stopForeground(false);
            };
            prepareTimeoutHandler.postDelayed(prepareTimeoutRunnable, 3000);

            mediaPlayer.setOnPreparedListener(mp -> {
                if (prepareTimeoutHandler != null && prepareTimeoutRunnable != null) {
                    prepareTimeoutHandler.removeCallbacks(prepareTimeoutRunnable);
                }
                mp.start();
                mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(getAvailableActions())
                    .setState(PlaybackStateCompat.STATE_PLAYING, getCurrentPosition(), 1.0f)
                    .build());
                updateNotification();
                if (playbackListener != null) {
                    playbackListener.onTrackChanged(currentIndex, title, albumArt);
                    playbackListener.onPlaybackStarted();
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                int next = getNextIndex();
                if (next != -1) {
                    currentIndex = next;
                    playCurrent();
                } else {
                    stop();
                    mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                        .setActions(getAvailableActions())
                        .setState(PlaybackStateCompat.STATE_PAUSED, getCurrentPosition(), 1.0f)
                        .build());
                    updateNotification();
                    stopForeground(true); // Remove notification
                    stopSelf();           // Stop the service
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                if (prepareTimeoutHandler != null && prepareTimeoutRunnable != null) {
                    prepareTimeoutHandler.removeCallbacks(prepareTimeoutRunnable);
                }
                Log.e("SongPlayerDBG", "MediaPlayer error: what=" + what + ", extra=" + extra);
                if (playbackListener != null) {
                    playbackListener.onPlaybackError(currentIndex, playlist != null ? playlist.get(currentIndex) : null, what, extra);
                }
                stop();
                mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(getAvailableActions())
                    .setState(PlaybackStateCompat.STATE_ERROR, 0, 1.0f)
                    .build());
                updateNotification();
                stopForeground(false);
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            if (prepareTimeoutHandler != null && prepareTimeoutRunnable != null) {
                prepareTimeoutHandler.removeCallbacks(prepareTimeoutRunnable);
            }
            stop();
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(getAvailableActions())
                .setState(PlaybackStateCompat.STATE_ERROR, 0, 1.0f)
                .build());
            updateNotification();
            stopForeground(false);
        }

        // Set metadata with albumArt
        MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
        if (albumArt != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt);
        }
        mediaSession.setMetadata(metaBuilder.build());
    }

    private int getNextIndex() {
        if (playlist == null || playlist.isEmpty()) return -1;
        if (isShuffle) {
            if (playlist.size() == 1) return currentIndex;
            int next;
            do { next = random.nextInt(playlist.size()); } while (next == currentIndex);
            return next;
        } else if (currentIndex < playlist.size() - 1) {
            return currentIndex + 1;
        } else if (isLoop) {
            return 0;
        }
        return -1;
    }

    private int getPreviousIndex() {
        if (playlist == null || playlist.isEmpty()) return -1;
        if (isShuffle) {
            if (playlist.size() == 1) return currentIndex;
            int prev;
            do { prev = random.nextInt(playlist.size()); } while (prev == currentIndex);
            return prev;
        } else if (currentIndex > 0) {
            return currentIndex - 1;
        } else if (isLoop) {
            return playlist.size() - 1;
        }
        return -1;
    }

    public void play() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
            mediaPlayer.start();
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(getAvailableActions())
                .setState(PlaybackStateCompat.STATE_PLAYING, getCurrentPosition(), 1.0f)
                .build());
            updateNotification();
            if (playbackListener != null) playbackListener.onPlaybackStarted();
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(getAvailableActions())
                .setState(PlaybackStateCompat.STATE_PAUSED, getCurrentPosition(), 1.0f)
                .build());
            updateNotification();
            if (playbackListener != null) playbackListener.onPlaybackPaused();
        }
    }

    public void seekTo(int pos) {
        if (mediaPlayer != null) mediaPlayer.seekTo(pos);
    }

    public boolean isPlaying() { return mediaPlayer != null && mediaPlayer.isPlaying(); }
    public int getCurrentPosition() { return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0; }
    public int getDuration() { return mediaPlayer != null ? mediaPlayer.getDuration() : 0; }

    public void stop() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    public int getCurrentIndex() { return currentIndex; }

    public boolean isSamePlaylist(List<String> urls) {
        if (playlist == null || urls == null || playlist.size() != urls.size()) return false;
        for (int i = 0; i < playlist.size(); i++) {
            if (!playlist.get(i).equals(urls.get(i))) return false;
        }
        return true;
    }

    public void setPlaybackListener(PlaybackListener listener) {
        this.playbackListener = listener;
    }

    private long getAvailableActions() {
        return PlaybackStateCompat.ACTION_PLAY |
                PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                PlaybackStateCompat.ACTION_SEEK_TO;
    }

    public void playNext() {
        int next = getNextIndex();
        if (next != -1 && playlistNodes != null) {
            currentIndex = next;
            savePlaybackState();
            playSongWithFreshToken(next, playlistNodes, isShuffle, isLoop);
        }
    }

    public void playPrevious() {
        int prev = getPreviousIndex();
        if (prev != -1 && playlistNodes != null) {
            currentIndex = prev;
            savePlaybackState();
            playSongWithFreshToken(prev, playlistNodes, isShuffle, isLoop);
        }
    }

    public void setPlaylistWithArt(List<String> urls, int index, boolean shuffle, boolean loop, Bitmap albumArt) {
        this.playlist = urls;
        this.currentIndex = index;
        this.isShuffle = shuffle;
        this.isLoop = loop;
        this.currentAlbumArt = albumArt;
        savePlaybackState();
        playCurrent();
    }

    public void updateNotificationArt(Bitmap albumArt) {
        this.currentAlbumArt = albumArt;
        updateNotification();
    }

    private void savePlaybackState() {
        SharedPreferences prefs = getSharedPreferences("music_service_prefs", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("currentIndex", currentIndex);
        if (playlist != null) {
            editor.putString("playlist", android.text.TextUtils.join(";;", playlist));
        }
        editor.apply();
    }

    public void setServerConfig(String publicIp, String port, String apiKey) {
        this.publicIp = publicIp;
        this.port = port;
        this.apiKey = apiKey;
    }

    // Fetch token and play song at index
    public void playSongWithFreshToken(int songIndex, List<SongNode> playlistNodes, boolean shuffle, boolean loop) {
        OkHttpClient client = getUnsafeOkHttpClient();
        String tokenUrl = "https://" + publicIp + ":" + port + "/token?apiKey=" + apiKey;
        Request request = new Request.Builder().url(tokenUrl).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) { }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    try {
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        token = json.get("token").getAsString();
                        List<String> playlistUrls = new ArrayList<>();
                        for (SongNode node : playlistNodes) {
                            String url = getStreamUrl(node.path, token);
                            playlistUrls.add(url);
                        }
                        new Handler(Looper.getMainLooper()).post(() -> {
                            setPlaylistWithArt(playlistUrls, songIndex, shuffle, loop, null);
                        });
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    // Utility to build stream URL
    private String getStreamUrl(String songPath, String token) {
        try {
            int lastSlash = songPath.lastIndexOf('/');
            if (lastSlash != -1) {
                String folder = URLEncoder.encode(songPath.substring(0, lastSlash), "UTF-8").replace("+", "%20");
                String filename = URLEncoder.encode(songPath.substring(lastSlash + 1), "UTF-8").replace("+", "%20");
                return "https://" + publicIp + ":" + port + "/stream/" + folder + "/" + filename + "?token=" + token;
            } else {
                String filename = URLEncoder.encode(songPath, "UTF-8").replace("+", "%20");
                return "https://" + publicIp + ":" + port + "/stream/" + filename + "?token=" + token;
            }
        } catch (Exception e) {
            return "";
        }
    }

    public OkHttpClient getUnsafeOkHttpClient() {
        try {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[]{}; }
                }
            };

            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            javax.net.ssl.SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (javax.net.ssl.X509TrustManager)trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);

            builder.connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
            builder.readTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
            builder.writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS);

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public interface PlaybackListener {
        void onPlaybackStarted();
        void onPlaybackPaused();
        void onTrackChanged(int newIndex, String songTitle, Bitmap albumArt);
        void onPlaybackError(int index, String url, int what, int extra);
    }

    @Override
    public void onDestroy() {
        stop();
        if (mediaSession != null) {
            mediaSession.release();
        }
        albumArtCache.clear();
        stopForeground(true); // <-- This removes the notification
        super.onDestroy();
    }

    private Bitmap getAlbumArt(String url) {
        // Check cache first
        if (albumArtCache.containsKey(url)) {
            return albumArtCache.get(url);
        }
        try {
            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                mmr.setDataSource(url, new HashMap<>());
            } else {
                mmr.setDataSource(url);
            }
            byte[] art = mmr.getEmbeddedPicture();
            mmr.release();
            if (art != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap = BitmapFactory.decodeByteArray(art, 0, art.length, options);
                albumArtCache.put(url, bitmap); // Cache it
                return bitmap;
            }
        } catch (Exception e) {
            Log.e("SongPlayerDBG", "Metadata error for: " + url, e);
        }
        albumArtCache.put(url, null); // Cache miss as null to avoid repeated attempts
        return null;
    }
    private Bitmap getAlbumArtFromRemote(String url) {
        File tempFile = null;
        try {
            // Download the first 128KB (should be enough for metadata)
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=0-131071")
                .build();
            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) return null;

            tempFile = File.createTempFile("meta", ".mp3", getCacheDir());
            try (java.io.InputStream in = response.body().byteStream();
                 java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            }

            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(tempFile.getAbsolutePath());
            byte[] art = mmr.getEmbeddedPicture();
            mmr.release();
            if (art != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                return BitmapFactory.decodeByteArray(art, 0, art.length, options);
            }
        } catch (Exception e) {
            Log.e("SongPlayerDBG", "Metadata error for: " + url, e);
        } finally {
            if (tempFile != null) tempFile.delete();
        }
        return null;
    }
    private SongMetadata getSongMetadataFromRemote(String url) {
        File tempFile = null;
        try {
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=0-131071")
                .build();
            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) return null;

            tempFile = File.createTempFile("meta", ".mp3", getCacheDir());
            try (java.io.InputStream in = response.body().byteStream();
                 java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            }

            android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(tempFile.getAbsolutePath());
            String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            byte[] art = mmr.getEmbeddedPicture();
            mmr.release();
            return new SongMetadata(title, artist, album, art);
        } catch (Exception e) {
            Log.e("SongPlayerDBG", "Metadata error for: " + url, e);
        } finally {
            if (tempFile != null) tempFile.delete();
        }
        return null;
    }

    // Helper class
    public static class SongMetadata {
        public final String title, artist, album;
        public final byte[] art;
        public SongMetadata(String title, String artist, String album, byte[] art) {
            this.title = title; this.artist = artist; this.album = album; this.art = art;
        }
    }
}