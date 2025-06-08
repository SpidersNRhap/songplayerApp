package com.example.songplayer;

import com.example.songplayer.SongNode;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class PlaylistSongsAdapter extends RecyclerView.Adapter<PlaylistSongsAdapter.ViewHolder> {
    private List<SongNode> songs;
    private final OnSongClickListener listener;

    public interface OnSongClickListener {
        void onSongClick(int position, SongNode song);
    }

    public PlaylistSongsAdapter(List<SongNode> songs, OnSongClickListener listener) {
        this.songs = songs;
        this.listener = listener;
    }

    public void setSongs(List<SongNode> songs) {
        this.songs = songs;
        notifyDataSetChanged();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist_song, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        SongNode song = songs.get(position);
        String displayName = song.name != null ? song.name : song.path;
        int dot = displayName.lastIndexOf('.');
        if (dot > 0) {
            displayName = displayName.substring(0, dot);
        }
        holder.textView.setText(displayName);
        holder.itemView.setOnClickListener(v -> listener.onSongClick(position, song));
    }

    @Override
    public int getItemCount() {
        return songs != null ? songs.size() : 0;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        ViewHolder(View v) {
            super(v);
            textView = v.findViewById(R.id.songName); // Use your custom TextView ID
        }
    }
}