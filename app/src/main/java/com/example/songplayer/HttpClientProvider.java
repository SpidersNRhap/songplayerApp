package com.example.songplayer;

import okhttp3.OkHttpClient;

public class HttpClientProvider {
    private static OkHttpClient instance;

    public static OkHttpClient getClient() {
        if (instance == null) {
            instance = new OkHttpClient.Builder()
                // Add interceptors, timeouts, etc. here if needed
                .build();
        }
        return instance;
    }
}