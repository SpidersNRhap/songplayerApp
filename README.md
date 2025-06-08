# SongPlayer Mobile Application

## Overview
SongPlayer is a mobile application that allows users to access and stream songs hosted on a server. The app fetches a token from the server and uses it to construct URLs for streaming songs.

## Features
- Fetches authentication token from the server.
- Streams songs using the provided URL format.
- User-friendly interface for browsing and playing songs.

## Project Structure
```
songplayer
├── app
│   ├── src
│   │   ├── main
│   │   │   ├── java
│   │   │   │   └── com
│   │   │   │       └── example
│   │   │   │           └── songplayer
│   │   │   │               └── MainActivity.java
│   │   │   └── res
│   │   │       ├── layout
│   │   │       │   └── activity_main.xml
│   │   │       └── values
│   │   │           └── strings.xml
│   │   └── AndroidManifest.xml
├── .env
├── build.gradle
├── settings.gradle
└── README.md
```

## Setup Instructions
1. Clone the repository to your local machine.
2. Create a `.env` file in the root directory with the following variables:
   ```
   PUBLIC_IP=your.public.ip.or.domain
   PORT=3000
   API_KEY=your_super_secret_key
   ```
3. Open the project in Android Studio.
4. Build and run the application on an Android device or emulator.

## Usage
- Launch the application.
- The app will automatically fetch the token and display the available songs.
- Select a song to start streaming.

## Contributing
Feel free to submit issues or pull requests for improvements and bug fixes.