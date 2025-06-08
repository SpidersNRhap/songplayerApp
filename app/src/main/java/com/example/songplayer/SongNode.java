package com.example.songplayer;

import java.util.List;

public class SongNode {
    public String name;
    public String path;
    public String type; // "file" or "folder"
    public List<SongNode> children;
    public boolean expanded = false; // For UI purposes, to track if the node is expanded

    public SongNode() {}

    public SongNode(String name, String path, String type, List<SongNode> children) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.children = children;
        this.expanded = false;
    }

    // Convenience constructor for search/filter (assume type "folder" if children != null)
    public SongNode(String name, String path, List<SongNode> children) {
        this.name = name;
        this.path = path;
        this.type = (children != null) ? "folder" : "file";
        this.children = children;
        this.expanded = false; // Default to not expanded
    }
}