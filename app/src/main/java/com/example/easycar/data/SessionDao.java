package com.example.easycar.data;
import androidx.room.*;
import java.util.List;
@Dao public interface SessionDao {
    @Query("SELECT id FROM sessions ORDER BY createdAt DESC") List<String> ids();
    /** Null when the session does not exist. */
    @Query("SELECT length(payload) FROM sessions WHERE id = :id") Integer payloadLength(String id);
    @Query("SELECT substr(payload, :offset, :count) FROM sessions WHERE id = :id") String payloadChunk(String id, int offset, int count);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void save(SessionEntity entity);
    @Query("DELETE FROM sessions WHERE id = :id") void delete(String id);

    @Query("SELECT seq FROM frame_chunks WHERE sessionId = :id ORDER BY seq") List<Integer> frameChunkSeqs(String id);
    /** One row per chunk; each row stays well under SQLite's CursorWindow, the whole list need not. */
    @Query("SELECT payload FROM frame_chunks WHERE sessionId = :id ORDER BY seq") List<String> frameChunkPayloads(String id);
    @Query("SELECT MAX(seq) FROM frame_chunks WHERE sessionId = :id") Integer lastFrameChunkSeq(String id);
    /**
     * Chunks that can hold frames in [fromMs, toMs]. Frames are appended in time order, so a chunk covers
     * the time after the previous chunk's lastTimeMs up to its own: from the first chunk ending at or after
     * fromMs through the first chunk ending at or after toMs (or the last chunk).
     */
    @Query("SELECT payload FROM frame_chunks WHERE sessionId = :id AND lastTimeMs >= :fromMs AND seq <= "
            + "IFNULL((SELECT MIN(seq) FROM frame_chunks WHERE sessionId = :id AND lastTimeMs >= :toMs), seq) ORDER BY seq")
    List<String> frameChunkPayloadsBetween(String id, long fromMs, long toMs);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void insertFrameChunk(FrameChunkEntity chunk);
    @Query("DELETE FROM frame_chunks WHERE sessionId = :id") void deleteFrameChunks(String id);
    /** Drops chunks that lie entirely before a session's retained frame window. */
    @Query("DELETE FROM frame_chunks WHERE sessionId = :id AND lastTimeMs < :firstRetainedMs") void deleteFrameChunksBefore(String id, long firstRetainedMs);
}
