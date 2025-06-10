# SongPlayer Mobile Application

## Overview
Personal android app to act as the client to my https music streaming server found at [bocchibot/songserver](https://github.com/SpidersNRhap/bocchibot).

## Features
- Secure token-based authentication with your music server
- Stream songs directly from your server
- Browse folders and playlists
- Shuffle and loop playback modes
- Displays song metadata and album art
- Persistent playback state across app restarts
- Modern Android UI

## Project Structure
```
songplayer
├── app
│   ├── src
│   │   ├── main
│   │   │   ├── java/com/example/songplayer/
│   │   │   │   ├── MainActivity.java
│   │   │   │   ├── MusicService.java
│   │   │   │   ├── HttpClientProvider.java
│   │   │   │   └── ... (other adapters and models)
│   │   │   └── res/
│   │   │       ├── layout/
│   │   │       └── values/
│   │   └── AndroidManifest.xml
├── .env                # Not committed, holds secrets
├── .gitignore
├── build.gradle
├── settings.gradle
└── README.md
```
``

**`.env` file format**
   ```
   PUBLIC_IP=server.domain
   PORT=3000
   API_KEY=your_super_secret_key
   ```
