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
import java.util.concurrent.Future;
import javax.net.ssl.*;
import android.view.View;
import android.util.Log;
import java.io.File;

public class MainActivity extends AppCompatActivity {
    // UI Components
    private ImageButton pauseButton, shuffleButton, loopButton;
    private TextView songTitle, songMeta, currentTime, totalTime;
    private RecyclerView songList, playlistList, playlistSongsList;
    private EditText searchBox;
    private SeekBar progressBar;

    // Data & State
    private String publicIp, port, apiKey;
    private List<SongNode> currentNodes = new ArrayList<>();
    private List<SongNode> currentPlaylist = new ArrayList<>();
    private List<SongNode> allSongNodes = new ArrayList<>();
    private List<SongNode> allNodes;
    private List<String> playlistNames = new ArrayList<>();
    private Map<String, List<SongNode>> playlists = new HashMap<>();
    private Future<?> currentMetadataTask;
    private int currentSongIndex = 0;
    private String currentSongPath = null;
    private String selectedPlaylistName = null;
    private boolean isShuffle = false, isLoop = false;
    private final Random random = new Random();

    // Adapters
    private SongAdapter adapter;
    private PlaylistAdapter playlistAdapter;
    private PlaylistSongsAdapter playlistSongsAdapter;

    // Service
    private MusicService musicService;
    private boolean serviceBound = false;

    // Background tasks
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private Handler progressHandler = new Handler();
    private Runnable progressRunnable;

    // Service connection
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d("SongPlayerDBG", "Service connected");
            MusicService.LocalBinder binder = (MusicService.LocalBinder) service;
            musicService = binder.getService();
            musicService.setServerConfig(publicIp, port, apiKey);
            serviceBound = true;
            musicService.setTokenListener(() -> runOnUiThread(() -> {
                fetchSongTree();
                fetchPlaylists();
            }));
            musicService.refreshToken();

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
                    runOnUiThread(() -> updatePauseButtonAndStatus());
                }
                @Override
                public void onTrackChanged(int newIndex, String newsongTitle, Bitmap albumArt) {
                    runOnUiThread(() -> {
                        songTitle.setText(extractSongTitle(newsongTitle));
                        progressBar.setProgress(0);
                        progressBar.setMax(musicService.getDuration());
                        if (currentPlaylist != null && newIndex >= 0 && newIndex < currentPlaylist.size()) {
                            String songPath = currentPlaylist.get(newIndex).path;
                            showSongMetadata(buildStreamUrl(songPath));
                        }
                        ImageView backgroundImage = findViewById(R.id.backgroundImage);
                        if (albumArt != null) {
                            backgroundImage.setImageBitmap(albumArt);
                            backgroundImage.setVisibility(View.VISIBLE);
                        } else {
                            backgroundImage.setImageDrawable(null);
                            backgroundImage.setVisibility(View.GONE);
                        }
                    });
                }
                @Override
                public void onPlaybackError(int index, String url, int what, int extra) {
                    runOnUiThread(() -> {
                        Log.e("SongPlayerDBG", "Playback error: what=" + what + ", extra=" + extra);
                        playSongViaService(index);
                    });
                }
            });
            updatePauseButtonAndStatus();
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
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        setContentView(R.layout.activity_main);

        // UI setup
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
        shuffleButton = findViewById(R.id.shuffleButton);
        loopButton = findViewById(R.id.loopButton);
        ImageButton nextButton = findViewById(R.id.nextButton);
        ImageButton prevButton = findViewById(R.id.prevButton);

        // Button listeners
        nextButton.setOnClickListener(v -> { if (isServiceReady()) musicService.playNext(); });
        prevButton.setOnClickListener(v -> { if (isServiceReady()) musicService.playPrevious(); });
        pauseButton.setOnClickListener(v -> {
            if (!isServiceReady()) return;
            if (musicService.isPlaying()) {
                musicService.pause();
                pauseButton.setImageResource(android.R.drawable.ic_media_play);
            } else {
                musicService.play();
                pauseButton.setImageResource(android.R.drawable.ic_media_pause);
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

        // Config
        publicIp = BuildConfig.PUBLIC_IP;
        port = BuildConfig.PORT;
        apiKey = BuildConfig.API_KEY;

        // Adapters
        adapter = new SongAdapter(currentNodes, new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(SongNode song) {
                List<SongNode> siblings = findSiblings(song, allNodes);
                currentPlaylist = siblings;
                currentSongIndex = siblings.indexOf(song);
                currentSongPath = song.path;
                playSongViaService(currentSongIndex);
            }
            @Override
            public void onFolderClick(SongNode folder) {}
        });
        songList.setLayoutManager(new LinearLayoutManager(this));
        songList.setAdapter(adapter);

        playlistSongsAdapter = new PlaylistSongsAdapter(new ArrayList<>(), (position, song) -> {
            currentPlaylist = playlists.get(selectedPlaylistName);
            currentSongIndex = position;
            playSongViaService(position);
        });
        playlistSongsList.setLayoutManager(new LinearLayoutManager(this));
        playlistSongsList.setAdapter(playlistSongsAdapter);

        // Search
        searchBox = findViewById(R.id.searchBox);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                if (playlistSongsList.getVisibility() == View.VISIBLE && currentPlaylist != null) {
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

        // Start service and fetch data
        Intent intent = new Intent(this, MusicService.class);
        startService(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!serviceBound) {
            Intent intent = new Intent(this, MusicService.class);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    @Override
    public void onBackPressed() {
        if (playlistSongsList.getVisibility() == View.VISIBLE) {
            showMainSongList();
        } else {
            super.onBackPressed();
        }
    }

    // --- UI and Playback Helpers ---

    private void updatePauseButtonAndStatus() {
        if (serviceBound && musicService != null && musicService.isPlaying()) {
            pauseButton.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            pauseButton.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private void updateShuffleLoopUI() {
        updateShuffleButton(isShuffle);
        updateLoopButton(isLoop);
    }

    private void updateShuffleButton(boolean isShuffle) {
        shuffleButton.setColorFilter(
            isShuffle ? Color.parseColor("#00E676") : Color.parseColor("#888888"),
            android.graphics.PorterDuff.Mode.SRC_IN
        );
    }

    private void updateLoopButton(boolean isLoop) {
        loopButton.setColorFilter(
            isLoop ? Color.parseColor("#2979FF") : Color.parseColor("#888888"),
            android.graphics.PorterDuff.Mode.SRC_IN
        );
    }

    private void setSongList(List<SongNode> nodes) {
        currentNodes = nodes;
        adapter.setItems(nodes);
    }

    private void showMainSongList() {
        songList.setVisibility(View.VISIBLE);
        playlistSongsList.setVisibility(View.GONE);
    }

    private boolean isServiceReady() {
        if (!serviceBound || musicService == null) {
            ensureServiceBound();
            return false;
        }
        return true;
    }

    private void ensureServiceBound() {
        if (!serviceBound || musicService == null) {
            Intent intent = new Intent(this, MusicService.class);
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void playSongViaService(int songIndex) {
        if (!serviceBound || musicService == null || currentPlaylist == null || currentPlaylist.isEmpty()) return;
        musicService.setPlaylist(currentPlaylist, songIndex, isShuffle, isLoop);
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
                    progressHandler.postDelayed(this, 500);
                } else {
                    pauseButton.setImageResource(android.R.drawable.ic_media_play);
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

    // --- Metadata and Playlist Fetching ---
    private String buildStreamUrl(String songPath) {
        if (!songPath.startsWith("/")) songPath = "/" + songPath;
        return "https://" + publicIp + ":" + port + "/stream" + songPath + "?token=" + (musicService != null ? musicService.getToken() : "");
    }

    private void showSongMetadata(String url) {
        Log.d("SongPlayerDBG", "showSongMetadata called with url: " + url);

        if (currentMetadataTask != null && !currentMetadataTask.isDone()) {
            currentMetadataTask.cancel(true);
            Log.d("SongPlayerDBG", "Cancelled previous metadata task");
        }

        currentMetadataTask = backgroundExecutor.submit(() -> {
            File tempFile = null;
            try {
                OkHttpClient client = getUnsafeOkHttpClient();
                // Try partial download first
                Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=0-1048575")
                    .build();
                Log.d("SongPlayerDBG", "Requesting first 1MB for metadata: " + url);
                Response response = client.newCall(request).execute();
                Log.d("SongPlayerDBG", "Response code: " + response.code());
                Log.d("SongPlayerDBG", "Content-Type: " + response.header("Content-Type"));
                if (response.code() == 403) {
                    Log.d("SongPlayerDBG", "Token is stale or expired during metadata fetch. Fetching a new token.");
                    if (musicService != null) musicService.refreshToken();
                    return;
                }
                if (!response.isSuccessful()) {
                    Log.e("SongPlayerDBG", "Failed to fetch file: HTTP " + response.code());
                    runOnUiThread(() -> songMeta.setText(""));
                    return;
                }
                tempFile = File.createTempFile("meta", ".mp3", getCacheDir());
                try (java.io.InputStream in = response.body().byteStream();
                     java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
                    byte[] buf = new byte[4096];
                    int len;
                    int total = 0;
                    while ((len = in.read(buf)) != -1) {
                        if (Thread.currentThread().isInterrupted()) return;
                        out.write(buf, 0, len);
                        total += len;
                    }
                    Log.d("SongPlayerDBG", "Downloaded " + total + " bytes for metadata extraction");
                }
                Log.d("SongPlayerDBG", "Temp file size: " + tempFile.length());
                if (Thread.currentThread().isInterrupted()) return;

                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                try {
                    mmr.setDataSource(tempFile.getAbsolutePath());
                } catch (RuntimeException e) {
                    Log.w("SongPlayerDBG", "Partial file failed, retrying with full file...");
                    // Try full download
                    tempFile.delete();
                    tempFile = File.createTempFile("meta", ".mp3", getCacheDir());
                    request = new Request.Builder().url(url).build();
                    response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        Log.e("SongPlayerDBG", "Failed to fetch full file: HTTP " + response.code());
                        runOnUiThread(() -> songMeta.setText(""));
                        return;
                    }
                    try (java.io.InputStream in = response.body().byteStream();
                         java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
                        byte[] buf = new byte[4096];
                        int len;
                        int total = 0;
                        while ((len = in.read(buf)) != -1) {
                            if (Thread.currentThread().isInterrupted()) return;
                            out.write(buf, 0, len);
                            total += len;
                        }
                        Log.d("SongPlayerDBG", "Downloaded " + total + " bytes for full metadata extraction");
                    }
                    mmr.setDataSource(tempFile.getAbsolutePath());
                }
                String artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                String album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                String title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                Log.d("SongPlayerDBG", "Extracted metadata - Title: " + title + ", Artist: " + artist + ", Album: " + album);
                String info = "";
                if (artist != null) info += "Artist: " + artist + "\n";
                if (album != null) info += "Album: " + album + "\n";
                if (title != null) info += "Title: " + title + "\n";
                String finalInfo = info;
                runOnUiThread(() -> songMeta.setText(finalInfo));
                mmr.release();
            } catch (Exception e) {
                Log.e("SongPlayerDBG", "Exception in showSongMetadata", e);
                runOnUiThread(() -> songMeta.setText(""));
            } finally {
                if (tempFile != null) {
                    boolean deleted = tempFile.delete();
                    Log.d("SongPlayerDBG", "Temp file deleted: " + deleted);
                }
            }
        });
    }

    private void fetchTokenAndThenSongs() {
        OkHttpClient client = getUnsafeOkHttpClient();
        String tokenUrl = "https://" + publicIp + ":" + port + "/token?apiKey=" + apiKey;
        Request request = new Request.Builder().url(tokenUrl).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String body = response.body().string();
                        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                        // token = json.get("token").getAsString();
                        runOnUiThread(() -> {
                            fetchSongTree();
                            fetchPlaylists();
                        });
                    } catch (Exception e) {}
                }
            }
        });
    }

    private void fetchSongTree() {
        OkHttpClient client = getUnsafeOkHttpClient();
        String url = "https://" + publicIp + ":" + port + "/songs?token=" + (musicService != null ? musicService.getToken() : "");
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {}
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
                    if (musicService != null) musicService.refreshToken();                }
            }
        });
    }

    private void fetchPlaylists() {
        OkHttpClient client = getUnsafeOkHttpClient();
        String url = "https://" + publicIp + ":" + port + "/playlists?token=" + (musicService != null ? musicService.getToken() : "");
        Request request = new Request.Builder().url(url).build();
        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response response) throws IOException {
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
                        } catch (Exception ex) {}
                    });
                }
            }
        });
    }

    private void setupPlaylistAdapter() {
        if (playlistAdapter == null) {
            playlistAdapter = new PlaylistAdapter(
                playlistNames,
                name -> {
                    selectedPlaylistName = name;
                    currentPlaylist = playlists.get(name);
                    currentSongIndex = 0;
                    if (currentPlaylist != null && !currentPlaylist.isEmpty()) {
                        playlistSongsAdapter.setSongs(currentPlaylist);
                        playlistSongsList.setVisibility(View.VISIBLE);
                        songList.setVisibility(View.GONE);
                    } else {
                        playlistSongsAdapter.setSongs(new ArrayList<>());
                        playlistSongsList.setVisibility(View.VISIBLE);
                        songList.setVisibility(View.GONE);
                    }
                },
                name -> {
                    List<SongNode> playlist = playlists.get(name);
                    if (playlist != null && !playlist.isEmpty()) {
                        currentPlaylist = playlist;
                        int startIndex;
                        if (isShuffle) {
                            startIndex = random.nextInt(playlist.size());
                        } else {
                            startIndex = 0;
                        }
                        currentSongIndex = startIndex;
                        selectedPlaylistName = name;
                        playSongViaService(startIndex);
                    }
                }
            );
            playlistList.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
            playlistList.setAdapter(playlistAdapter);
        } else {
            playlistAdapter.notifyDataSetChanged();
        }
    }

    // --- Utility Methods ---

    public static String extractSongTitle(String urlOrName) {
        int q = urlOrName.indexOf('?');
        if (q != -1) urlOrName = urlOrName.substring(0, q);
        int lastSlash = urlOrName.lastIndexOf('/');
        String title = (lastSlash != -1 && lastSlash < urlOrName.length() - 1) ? urlOrName.substring(lastSlash + 1) : urlOrName;
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

    private List<SongNode> findSiblings(SongNode target, List<SongNode> nodes) {
        for (SongNode node : nodes) {
            if (node.children != null && !node.children.isEmpty()) {
                for (SongNode child : node.children) {
                    if (child == target || (child.path != null && child.path.equals(target.path))) {
                        List<SongNode> files = new ArrayList<>();
                        for (SongNode c : node.children) {
                            if (c.children == null || c.children.isEmpty()) files.add(c);
                        }
                        return files;
                    }
                }
                List<SongNode> result = findSiblings(target, node.children);
                if (result != null) return result;
            }
        }
        List<SongNode> files = new ArrayList<>();
        for (SongNode n : nodes) {
            if (n.children == null || n.children.isEmpty()) files.add(n);
        }
        if (files.contains(target)) return files;
        return null;
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
        adapter.setItems(filtered);
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

    // --- Networking ---

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
            builder.connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
            builder.readTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
            builder.writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS);
            return builder.build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}