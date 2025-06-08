package com.example.songplayer;

import android.graphics.Color;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.*;
import android.view.View;
import android.util.Log;

public class MainActivity extends AppCompatActivity {
    private ImageButton pauseButton;
    private String publicIp, port, apiKey, token;
    private TextView statusText, songTitle;
    private RecyclerView songList;
    private SongAdapter adapter;
    private List<SongNode> currentNodes = new ArrayList<>();
    private Stack<List<SongNode>> navStack = new Stack<>();
    private String currentSongPath = null;
    private List<String> playlistNames = new ArrayList<>();
    private Map<String, List<SongNode>> playlists = new HashMap<>();
    private List<SongNode> currentPlaylist = new ArrayList<>();
    private int currentSongIndex = 0;
    private SeekBar progressBar;
    private Handler progressHandler = new Handler();
    private Runnable progressRunnable;
    private TextView songMeta;
    private RecyclerView playlistList;
    private PlaylistAdapter playlistAdapter;
    private TextView currentTime;
    private TextView totalTime;
    private List<SongNode> allSongNodes = new ArrayList<>();
    private boolean isShuffle = false;
    private boolean isLoop = false;
    private ImageButton shuffleButton;
    private ImageButton loopButton;
    private final Random random = new Random();
    private RecyclerView playlistSongsList;
    private PlaylistSongsAdapter playlistSongsAdapter;
    private String selectedPlaylistName = null;
    private EditText searchBox;
    private List<SongNode> allNodes; // Store the full list for searching

    // Service binding
    private MusicService musicService;
    private boolean serviceBound = false;
    private String pendingSongPath = null;
    private String pendingToken = null;

    // Executor for background tasks
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d("SongPlayerDBG", "Service connected");
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            musicService = binder.getService();
            serviceBound = true;
            // Set playback listener
            musicService.setPlaybackListener(new MusicService.PlaybackListener() {
                @Override
                public void onPlaybackStarted() {
                    runOnUiThread(() -> {
                        updatePauseButtonAndStatus();
                        startProgressUpdater();
                    });
                }

                @Override
                public void onPlaybackPaused() {
                    runOnUiThread(() -> {
                        updatePauseButtonAndStatus();
                    });
                }

                @Override
                public void onTrackChanged(int newIndex, String newsongTitle) {
                    runOnUiThread(() -> {
                        songTitle.setText(extractSongTitle(newsongTitle));
                        progressBar.setProgress(0);
                        progressBar.setMax(musicService.getDuration());

                        // Update metadata and album art for the new song
                        if (currentPlaylist != null && newIndex >= 0 && newIndex < currentPlaylist.size()) {
                            String songPath = currentPlaylist.get(newIndex).path;
                            String streamUrl = getStreamUrl(songPath, token);
                            showSongMetadata(streamUrl);

                            // Extract album art and update notification in background
                            backgroundExecutor.submit(() -> {
                                Bitmap albumArt = loadAlbumArt(streamUrl);
                                if (albumArt != null && musicService != null) {
                                    runOnUiThread(() -> musicService.updateNotificationArt(albumArt));
                                }
                            });
                        }
                    });
                }
            });
            // If there was a pending playSong, call it now
            if (pendingSongPath != null && pendingToken != null) {
                playSong(pendingSongPath, pendingToken);
                pendingSongPath = null;
                pendingToken = null;
            }
            // Sync pause button and status with actual playback state
            updatePauseButtonAndStatus();

            // Sync shuffle/loop UI with service state
            isShuffle = musicService.isShuffle();
            isLoop = musicService.isLoop();
            updateShuffleLoopUI();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d("SongPlayerDBG", "Service disconnected");
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hide the ActionBar if present
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_main);

        // Start the service so it stays alive for notifications/media keys
        Intent intent = new Intent(this, MusicService.class);
        startService(intent);

       // statusText = findViewById(R.id.statusText);
        songTitle = findViewById(R.id.songTitle);
        songList = findViewById(R.id.songList);
        pauseButton = findViewById(R.id.pauseButton);
        songMeta = findViewById(R.id.songMeta);
        progressBar = findViewById(R.id.progressBar);
        playlistList = findViewById(R.id.playlistList);
        currentTime = findViewById(R.id.currentTime);
        totalTime = findViewById(R.id.totalTime);
        playlistSongsList = findViewById(R.id.playlistSongsList);
        songList.setVisibility(View.VISIBLE);
        playlistSongsList.setVisibility(View.GONE);

        ImageButton nextButton = findViewById(R.id.nextButton);
        ImageButton prevButton = findViewById(R.id.prevButton);

        shuffleButton = findViewById(R.id.shuffleButton);
        loopButton = findViewById(R.id.loopButton);

        nextButton.setOnClickListener(v -> {
            if (!isServiceReady()) return;
            musicService.playNext();
        });
        prevButton.setOnClickListener(v -> {
            if (!isServiceReady()) return;
            musicService.playPrevious();
        });

        pauseButton.setOnClickListener(v -> {
            if (!isServiceReady()) return;
            if (musicService.isPlaying()) {
                musicService.pause();
                pauseButton.setImageResource(android.R.drawable.ic_media_play);
//                statusText.setText("Status: Paused");
            } else {
                musicService.play();
                pauseButton.setImageResource(android.R.drawable.ic_media_pause);
//                statusText.setText("Status: Playing");
                startProgressUpdater();
            }
        });

        shuffleButton.setOnClickListener(v -> {
            if (!isServiceReady()) return;
            isShuffle = !isShuffle;
            musicService.setShuffle(isShuffle);
            updateShuffleLoopUI();
        });

        loopButton.setOnClickListener(v -> {
            if (!isServiceReady()) return;
            isLoop = !isLoop;
            musicService.setLoop(isLoop);
            updateShuffleLoopUI();
        });

        updateShuffleLoopUI();

        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && serviceBound && musicService != null) {
                    musicService.seekTo(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        publicIp = BuildConfig.PUBLIC_IP;
        port = BuildConfig.PORT;
        apiKey = BuildConfig.API_KEY;

        adapter = new SongAdapter(currentNodes, new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(SongNode song) {
                // Find the parent folder's song list
                List<SongNode> siblings = findSiblings(song, allNodes);
                currentPlaylist = siblings;
                currentSongIndex = siblings.indexOf(song);
                currentSongPath = song.path;
                fetchTokenAndPlay(song.path);
            }
            @Override
            public void onFolderClick(SongNode folder) {
                // No-op or expand/collapse logic
            }
        });
        songList.setLayoutManager(new LinearLayoutManager(this));
        songList.setAdapter(adapter);

        playlistSongsAdapter = new PlaylistSongsAdapter(new ArrayList<>(), (position, song) -> {
            currentPlaylist = playlists.get(selectedPlaylistName);
            currentSongIndex = position;
            playSong(song.path, token);
        });
        playlistSongsList.setLayoutManager(new LinearLayoutManager(this));
        playlistSongsList.setAdapter(playlistSongsAdapter);

        searchBox = findViewById(R.id.searchBox);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                if (playlistSongsList.getVisibility() == View.VISIBLE && currentPlaylist != null) {
                    // Filter playlist songs
                    if (query.isEmpty()) {
                        playlistSongsAdapter.setSongs(currentPlaylist);
                        playlistAdapter.setPlaylists(playlistNames);
                    } else {
                        List<SongNode> filtered = new ArrayList<>();
                        for (SongNode node : currentPlaylist) {
                            if (node.name != null && node.name.toLowerCase().contains(query.toLowerCase())) {
                                filtered.add(node);
                            }
                        }
                        playlistSongsAdapter.setSongs(filtered);
                        filterPlaylists(query);
                    }
                } else {
                    // Filter main song directory and playlists
                    if (query.isEmpty()) {
                        adapter.setItems(allNodes);
                        playlistAdapter.setPlaylists(playlistNames);
                    } else {
                        filterPlaylist(query);
                        filterPlaylists(query);
                    }
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchBox.setOnEditorActionListener((v, actionId,  event) -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
            return true;
        });

        fetchTokenAndThenSongs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Ensure service is bound when activity is visible
        if (!serviceBound) {
            Intent intent = new Intent(this, MusicService.class);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        progressHandler.removeCallbacksAndMessages(null);
        backgroundExecutor.shutdownNow();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (playlistSongsList.getVisibility() == View.VISIBLE) {
            showMainSongList(); // Show the main song directory
        } else {
            super.onBackPressed();
        }
    }

    private void updatePauseButtonAndStatus() {
        if (serviceBound && musicService != null && musicService.isPlaying()) {
            pauseButton.setImageResource(android.R.drawable.ic_media_pause);
//            statusText.setText("Status: Playing");
        } else {
            pauseButton.setImageResource(android.R.drawable.ic_media_play);
//            statusText.setText("Status: Paused");
        }
    }

    private void updateShuffleLoopUI() {
        updateShuffleButton(isShuffle);
        updateLoopButton(isLoop);
    }

    private void setSongList(List<SongNode> nodes) {
        currentNodes = nodes;
        adapter.setItems(nodes);
    }

    private void ensureServiceBound() {
        if (!serviceBound || musicService == null) {
            Intent intent = new Intent(this, MusicService.class);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
//            statusText.setText("Reconnecting to music service...");
        }
    }

    private boolean isServiceReady() {
        if (!serviceBound || musicService == null) {
            ensureServiceBound();
//            statusText.setText("Reconnecting to music service...");
            return false;
        }
        return true;
    }

    private void fetchTokenAndPlay(String songPath) {
//        statusText.setText("Fetching token...");
        OkHttpClient client = getUnsafeOkHttpClient();
        String tokenUrl = "https://" + publicIp + ":" + port + "/token?apiKey=" + apiKey;
        Request request = new Request.Builder().url(tokenUrl).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
//                runOnUiThread(() -> statusText.setText("Token error (network): " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    try {
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        token = json.get("token").getAsString();
                        runOnUiThread(() -> playSong(songPath, token));
                    } catch (Exception e) {
//                        runOnUiThread(() -> statusText.setText("Token parse error: " + e.getMessage() + " Body: " + body));
                    }
                } else {
//                    runOnUiThread(() -> statusText.setText("Token error: HTTP " + response.code() + " Body: " + body));
                }
            }
        });
    }

    private void fetchSongTree() {
//        statusText.setText("Loading songs...");
        OkHttpClient client = getUnsafeOkHttpClient();
        String url = "https://" + publicIp + ":" + port + "/songs?token=" + token;
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
//                runOnUiThread(() -> statusText.setText("Failed to load songs (network): " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    Gson gson = new Gson();
                    SongNode[] nodes = gson.fromJson(body, SongNode[].class);
                    runOnUiThread(() -> {
                        allSongNodes = Arrays.asList(nodes);
                        allNodes = allSongNodes; 
                        setSongList(allSongNodes);
                        fetchPlaylists();
                    });
                } else if (response.code() == 403) {
                    Log.d("SongPlayerDBG", "Token is stale or expired. Fetching a new token.");
                    runOnUiThread(() -> fetchTokenAndThenSongs());
                } else {
//                    runOnUiThread(() -> statusText.setText("Failed to load songs: HTTP " + response.code() + " Body: " + body));
                }
            }
        });
    }

    private void fetchTokenAndThenSongs() {
//        statusText.setText("Fetching token...");
        OkHttpClient client = getUnsafeOkHttpClient();
        String tokenUrl = "https://" + publicIp + ":" + port + "/token?apiKey=" + apiKey;
        Request request = new Request.Builder().url(tokenUrl).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {
//                runOnUiThread(() -> statusText.setText("Token error: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String body = response.body().string();
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        token = json.get("token").getAsString();
                        runOnUiThread(() -> {
                            fetchSongTree();
                            fetchPlaylists();
                        });
                    } catch (Exception e) {
//                        runOnUiThread(() -> statusText.setText("Token error: " + e.getMessage()));
                    }
                } else {
//                    runOnUiThread(() -> statusText.setText("Token error: HTTP " + response.code()));
                }
            }
        });
    }

    private void playSong(String songPath, String token) {
        if (!serviceBound || musicService == null) {
            Log.d("SongPlayerDBG", "Service not bound, deferring playSong for: " + songPath);
            pendingSongPath = songPath;
            pendingToken = token;
            return;
        }
        Log.d("SongPlayerDBG", "playSong called: " + songPath);
        try {
            List<String> playlistUrls = new ArrayList<>();
            for (SongNode node : currentPlaylist) {
                String nodePath = node.path;
                int lastSlash = nodePath.lastIndexOf('/');
                String url;
                if (lastSlash != -1) {
                    String folder = URLEncoder.encode(nodePath.substring(0, lastSlash), "UTF-8").replace("+", "%20");
                    String filename = URLEncoder.encode(nodePath.substring(lastSlash + 1), "UTF-8").replace("+", "%20");
                    url = "https://" + publicIp + ":" + port + "/stream/" + folder + "/" + filename + "?token=" + token;
                } else {
                    String filename = URLEncoder.encode(nodePath, "UTF-8").replace("+", "%20");
                    url = "https://" + publicIp + ":" + port + "/stream/" + filename + "?token=" + token;
                }
                playlistUrls.add(url);
            }
            int index = 0;
            for (int i = 0; i < currentPlaylist.size(); i++) {
                if (currentPlaylist.get(i).path.equals(songPath)) {
                    index = i;
                    break;
                }
            }

            // Start playback immediately, don't wait for album art
            musicService.setPlaylistWithArt(playlistUrls, index, isShuffle, isLoop, null);

            // Load album art in the background and update notification when ready
            final int finalIndex = index;
            backgroundExecutor.submit(() -> {
                Bitmap albumArt = loadAlbumArt(playlistUrls.get(finalIndex));
                if (albumArt != null && musicService != null) {
                    runOnUiThread(() -> musicService.updateNotificationArt(albumArt));
                }
            });

            String displayName = currentPlaylist.get(index).name != null ? currentPlaylist.get(index).name : songPath;
            songTitle.setText(extractSongTitle(displayName));
//            statusText.setText("Streaming from: " + playlistUrls.get(index));
            showSongMetadata(playlistUrls.get(index));
            updatePauseButtonAndStatus();
        } catch (Exception e) {
//            statusText.setText("Error streaming song: " + e.getMessage());
        }
    }

    // Utility: Extract song title from URL or filename
    public static String extractSongTitle(String urlOrName) {
        int q = urlOrName.indexOf('?');
        if (q != -1) urlOrName = urlOrName.substring(0, q);
        int lastSlash = urlOrName.lastIndexOf('/');
        String title = (lastSlash != -1 && lastSlash < urlOrName.length() - 1) ? urlOrName.substring(lastSlash + 1) : urlOrName;
        try {
            title = java.net.URLDecoder.decode(title, "UTF-8");
        } catch (Exception ignored) {}
        String[] exts = {".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a"};
        for (String ext : exts) {
            if (title.toLowerCase().endsWith(ext)) {
                title = title.substring(0, title.length() - ext.length());
                break;
            }
        }
        return title;
    }

    // Utility: Load album art from a data source URL
    public static Bitmap loadAlbumArt(String dataSource) {
        Bitmap albumArt = null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(dataSource, new HashMap<>());
            byte[] art = mmr.getEmbeddedPicture();
            if (art != null) {
                albumArt = BitmapFactory.decodeByteArray(art, 0, art.length);
            }
        } catch (Exception ignored) {}
        finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        return albumArt;
    }

    private void showSongMetadata(String streamUrl) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        ImageView backgroundImage = findViewById(R.id.backgroundImage);
        try {
            mmr.setDataSource(streamUrl, new HashMap<>());
            String artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String info = "";
            if (artist != null) info += "Artist: " + artist + "\n";
            if (album != null) info += "Album: " + album + "\n";
            songMeta.setText(info);

            // Set album art as background if available
            byte[] art = mmr.getEmbeddedPicture();
            if (art != null) {
                Bitmap bmp = BitmapFactory.decodeByteArray(art, 0, art.length);
                backgroundImage.setImageBitmap(bmp);
                backgroundImage.setVisibility(View.VISIBLE);
            } else {
                backgroundImage.setImageDrawable(null);
                backgroundImage.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            songMeta.setText("");
            backgroundImage.setImageDrawable(null);
            backgroundImage.setVisibility(View.GONE);
        } finally {
            try {
                mmr.release();
            } catch (IOException e) {}
        }
    }

    private void fetchPlaylists() {
        OkHttpClient client = getUnsafeOkHttpClient();
        String url = "https://" + publicIp + ":" + port + "/playlists?token=" + token;
//        statusText.setText("Fetching playlists...");
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
//                runOnUiThread(() -> statusText.setText("Failed to load playlists: " + e.getMessage()));
            }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) {
                    runOnUiThread(() -> {
                        try {
                            playlists.clear();
                            playlistNames.clear();
                            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                            for (String name : json.keySet()) {
                                playlistNames.add(name);
                                List<SongNode> nodes = new ArrayList<>();
                                for (com.google.gson.JsonElement song : json.getAsJsonArray(name)) {
                                    String songName = song.getAsString();
                                    String fullPath = findSongPathByName(allSongNodes, songName, "");
                                    if (fullPath != null) {
                                        SongNode node = new SongNode();
                                        node.name = songName;
                                        node.path = fullPath;
                                        nodes.add(node);
                                    }
                                }
                                playlists.put(name, nodes);
                            }
                            Collections.sort(playlistNames, String.CASE_INSENSITIVE_ORDER);
                            setupPlaylistAdapter();
//                            statusText.setText("Playlists loaded.");
                        } catch (Exception ex) {
//                            statusText.setText("Error parsing playlists: " + ex.getMessage());
                        }
                    });
                } else {
//                    runOnUiThread(() -> statusText.setText("Failed to load playlists: HTTP " + response.code()));
                }
            }
        });
    }

    private void setupPlaylistAdapter() {
        if (playlistAdapter == null) {
            playlistAdapter = new PlaylistAdapter(
                playlistNames,
                name -> { // OnPlaylistClick
                    selectedPlaylistName = name;
                    currentPlaylist = playlists.get(name);
                    currentSongIndex = 0;
                    if (currentPlaylist != null && !currentPlaylist.isEmpty()) {
                        playlistSongsAdapter.setSongs(currentPlaylist);
                        playlistSongsList.setVisibility(View.VISIBLE);
                        songList.setVisibility(View.GONE);
                    } else {
//                        statusText.setText("Playlist is empty.");
                        playlistSongsAdapter.setSongs(new ArrayList<>());
                        playlistSongsList.setVisibility(View.VISIBLE);
                        songList.setVisibility(View.GONE);
                    }
                },
                name -> { // OnPlaylistPlayClick
                    List<SongNode> playlist = playlists.get(name);
                    if (playlist != null && !playlist.isEmpty()) {
                        currentPlaylist = playlist;
                        currentSongIndex = 0;
                        selectedPlaylistName = name;
                        playSong(playlist.get(0).path, token);
                    } else {
//                        statusText.setText("Playlist is empty.");
                    }
                }
            );
            playlistList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
            playlistList.setAdapter(playlistAdapter);
        } else {
            playlistAdapter.notifyDataSetChanged();
        }
    }

    private void showMainSongList() {
        songList.setVisibility(View.VISIBLE);
        playlistSongsList.setVisibility(View.GONE);
    }

    private void playNextSong() {
        if (serviceBound && musicService != null) {
            musicService.playNext();
        }
    }

    private void playPreviousSong() {
        if (serviceBound && musicService != null) {
            musicService.playPrevious();
        }
    }

    private String findSongPathByName(List<SongNode> nodes, String songName, String currentPath) {
        for (SongNode node : nodes) {
            String nodePath = currentPath.isEmpty() ? node.name : currentPath + "/" + node.name;
            if (node.children != null && !node.children.isEmpty()) {
                String found = findSongPathByName(node.children, songName, nodePath);
                if (found != null) return found;
            } else {
                if (node.name.equals(songName) || nodePath.equals(songName) || nodePath.endsWith("/" + songName)) {
                    return nodePath;
                }
            }
        }
        return null;
    }

    private List<SongNode> findSiblings(SongNode target, List<SongNode> nodes) {
        for (SongNode node : nodes) {
            if (node.children != null && !node.children.isEmpty()) {
                // Check if target is in this folder
                for (SongNode child : node.children) {
                    if (child == target || (child.path != null && child.path.equals(target.path))) {
                        // Return all files (not folders) in this folder
                        List<SongNode> files = new ArrayList<>();
                        for (SongNode c : node.children) {
                            if (c.children == null || c.children.isEmpty()) {
                                files.add(c);
                            }
                        }
                        return files;
                    }
                }
                // Recurse into subfolders
                List<SongNode> result = findSiblings(target, node.children);
                if (result != null) return result;
            }
        }
        // If not found, treat root as the folder
        List<SongNode> files = new ArrayList<>();
        for (SongNode n : nodes) {
            if (n.children == null || n.children.isEmpty()) {
                files.add(n);
            }
        }
        if (files.contains(target)) return files;
        return null;
    }

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
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {}
                        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {}
                        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[]{}; }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager)trustAllCerts[0]);
            builder.hostnameVerifier((hostname, session) -> true);

            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void startProgressUpdater() {
        progressHandler.removeCallbacksAndMessages(null);
        progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (serviceBound && musicService != null && musicService.isPlaying()) {
                    int pos = musicService.getCurrentPosition();
                    int dur = musicService.getDuration();
                    progressBar.setMax(dur);
                    progressBar.setProgress(pos);
                    currentTime.setText(formatTime(pos));
                    totalTime.setText(formatTime(dur));
                    pauseButton.setImageResource(android.R.drawable.ic_media_pause);
//                    statusText.setText("Status: Playing");
                    progressHandler.postDelayed(this, 500);
                } else {
                    pauseButton.setImageResource(android.R.drawable.ic_media_play);
//                    statusText.setText("Status: Paused");
                }
            }
        };
        progressHandler.post(progressRunnable);
    }

    private String formatTime(int millis) {
        int seconds = millis / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void filterPlaylist(String query) {
        if (allNodes == null) return;
        List<SongNode> filtered = new ArrayList<>();
        for (SongNode node : allNodes) {
            if (node.name != null && node.name.toLowerCase().contains(query.toLowerCase())) {
                filtered.add(node);
            } else if (node.children != null) {
                List<SongNode> childFiltered = filterChildren(node.children, query);
                if (!childFiltered.isEmpty()) {
                    SongNode copy = new SongNode(node.name, node.path, childFiltered);
                    filtered.add(copy);
                }
            }
        }
        adapter.setItems(filtered); // Update the song list with filtered results

        expandAllFolders(filtered);
        adapter.setItems(filtered);
    }

    private List<SongNode> filterChildren(List<SongNode> nodes, String query) {
        List<SongNode> filtered = new ArrayList<>();
        for (SongNode node : nodes) {
            if (node.name != null && node.name.toLowerCase().contains(query.toLowerCase())) {
                filtered.add(node);
            } else if (node.children != null) {
                List<SongNode> childFiltered = filterChildren(node.children, query);
                if (!childFiltered.isEmpty()) {
                    SongNode copy = new SongNode(node.name, node.path, childFiltered);
                    filtered.add(copy);
                }
            }
        }
        return filtered;
    }

    private void expandAllFolders(List<SongNode> nodes) {
        for (SongNode node : nodes) {
            if ("folder".equals(node.type)) {
                node.expanded = true;
                if (node.children != null) expandAllFolders(node.children);
            }
        }
    }

    private void filterPlaylists(String query) {
        List<String> filteredNames = new ArrayList<>();
        for (String name : playlistNames) {
            if (name.toLowerCase().contains(query.toLowerCase())) {
                filteredNames.add(name);
            } else {
                List<SongNode> songs = playlists.get(name);
                if (songs != null) {
                    for (SongNode song : songs) {
                        if (song.name != null && song.name.toLowerCase().contains(query.toLowerCase())) {
                            filteredNames.add(name);
                            break;
                        }
                    }
                }
            }
        }
        Collections.sort(filteredNames, String.CASE_INSENSITIVE_ORDER);
        playlistAdapter.setPlaylists(filteredNames);
    }

    private void updateShuffleButton(boolean isShuffle) {
        ImageButton shuffleButton = findViewById(R.id.shuffleButton);
        shuffleButton.setColorFilter(
            isShuffle ? Color.parseColor("#00E676") : Color.parseColor("#888888"),
            android.graphics.PorterDuff.Mode.SRC_IN
        );
    }

    private void updateLoopButton(boolean isLoop) {
        ImageButton loopButton = findViewById(R.id.loopButton);
        loopButton.setColorFilter(
            isLoop ? Color.parseColor("#2979FF") : Color.parseColor("#888888"),
            android.graphics.PorterDuff.Mode.SRC_IN
        );
    }

    private void refreshAndPlayCurrent(int songIndex) {
        // If token is null or empty, fetch a new token first
        if (token == null || token.isEmpty()) {
            fetchTokenAndThenSongs();
            // Optionally, you can defer the playback until the token is refreshed
            // by storing the desired songIndex in a pending variable
            return;
        }
        if (!serviceBound || musicService == null || currentPlaylist == null || currentPlaylist.isEmpty()) return;
        List<String> playlistUrls = new ArrayList<>();
        for (SongNode node : currentPlaylist) {
            String url = getStreamUrl(node.path, token); // always use latest token
            playlistUrls.add(url);
        }
        musicService.setPlaylistWithArt(playlistUrls, songIndex, isShuffle, isLoop, null);

        // Optionally update album art in background as before
        final int finalIndex = songIndex;
        backgroundExecutor.submit(() -> {
            Bitmap albumArt = loadAlbumArt(playlistUrls.get(finalIndex));
            if (albumArt != null && musicService != null) {
                runOnUiThread(() -> musicService.updateNotificationArt(albumArt));
            }
        });
    }
}