package com.example.songplayer;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.ViewHolder> {
    private List<SongNode> items = new ArrayList<>();
    private OnSongClickListener listener;

    public interface OnSongClickListener {
        void onSongClick(SongNode song);
        void onFolderClick(SongNode folder);
    }

    public SongAdapter(List<SongNode> items, OnSongClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void setItems(List<SongNode> newItems) {
        this.items = newItems;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return getVisibleCount(items, 0);
    }

    // Helper to count only visible (expanded) nodes
    private int getVisibleCount(List<SongNode> nodes, int depth) {
        int count = 0;
        for (SongNode node : nodes) {
            count++;
            if ("folder".equals(node.type) && node.expanded && node.children != null) {
                count += getVisibleCount(node.children, depth + 1);
            }
        }
        return count;
    }

    // Helper to get node at flat position
    private SongNodeDepth getNodeAtPosition(List<SongNode> nodes, int pos, int depth) {
        for (SongNode node : nodes) {
            if (pos == 0) return new SongNodeDepth(node, depth);
            pos--;
            if ("folder".equals(node.type) && node.expanded && node.children != null) {
                int childCount = getVisibleCount(node.children, depth + 1);
                if (pos < childCount) {
                    return getNodeAtPosition(node.children, pos, depth + 1);
                }
                pos -= childCount;
            }
        }
        return null;
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        SongNodeDepth nd = getNodeAtPosition(items, position, 0);
        SongNode node = nd.node;
        int depth = nd.depth;

        String displayName = node.name;
        if (!"folder".equals(node.type)) {
            int dot = displayName.lastIndexOf('.');
            if (dot > 0) {
                displayName = displayName.substring(0, dot);
            }
        }
        holder.title.setText(displayName);
        holder.itemView.setPadding(32 * depth, 0, 0, 0); // Indent

        if ("folder".equals(node.type)) {
            holder.icon.setImageResource(node.expanded ? R.drawable.ic_folder_open : R.drawable.ic_folder_closed);
            holder.itemView.setOnClickListener(v -> {
                node.expanded = !node.expanded;
                notifyDataSetChanged();
                if (listener != null) listener.onFolderClick(node);
            });
        } else {
            holder.icon.setImageResource(R.drawable.ic_song);
            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onSongClick(node);
            });
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false);
        return new ViewHolder(v);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView title;
        ViewHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            title = v.findViewById(R.id.songTitle);
        }
    }

    // Helper class to track depth for indentation
    private static class SongNodeDepth {
        SongNode node;
        int depth;
        SongNodeDepth(SongNode node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }
}