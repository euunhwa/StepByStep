package com.example.easycar.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;

/** Append-only slices of a session's raw frames, so a checkpoint writes only frames not yet stored. */
@Entity(tableName = "frame_chunks", primaryKeys = {"sessionId", "seq"})
public class FrameChunkEntity {
    @NonNull public String sessionId = "";
    public int seq;
    public long lastTimeMs;
    @NonNull public String payload = "";
}
