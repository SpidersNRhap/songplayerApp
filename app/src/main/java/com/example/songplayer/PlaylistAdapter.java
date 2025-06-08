package com.example.songplayer;

import android.view.*;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.ViewHolder> {
    private List<String> playlistNames;
    private OnPlaylistClickListener listener;
    private OnPlaylistPlayClickListener playListener;

    public interface OnPlaylistClickListener {
        void onPlaylistClick(String name);
    }
    public interface OnPlaylistPlayClickListener {
        void onPlaylistPlayClick(String name);
    }

    public PlaylistAdapter(List<String> playlistNames, OnPlaylistClickListener listener, OnPlaylistPlayClickListener playListener) {
        this.playlistNames = playlistNames;
        this.listener = listener;
        this.playListener = playListener;
    }

    public void setPlaylists(List<String> newNames) {
        this.playlistNames = newNames;
        notifyDataSetChanged();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_playlist, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        String name = playlistNames.get(position);
        holder.playlistName.setText(name);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPlaylistClick(name);
        });
        holder.playButton.setOnClickListener(v -> {
            if (playListener != null) playListener.onPlaylistPlayClick(name);
        });
    }

    @Override
    public int getItemCount() {
        return playlistNames.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView playlistName;
        ImageButton playButton;
        ViewHolder(View v) {
            super(v);
            playlistName = v.findViewById(R.id.playlistName);
            playButton = v.findViewById(R.id.playlistPlayButton);
        }
    }
}