package com.example.easycar.data;
import androidx.room.*;
import java.util.List;
@Dao public interface SessionDao {
    @Query("SELECT id FROM sessions ORDER BY createdAt DESC") List<String> ids();
    @Query("SELECT length(payload) FROM sessions WHERE id = :id") int payloadLength(String id);
    @Query("SELECT substr(payload, :offset, :count) FROM sessions WHERE id = :id") String payloadChunk(String id, int offset, int count);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void save(SessionEntity entity);
    @Query("DELETE FROM sessions WHERE id = :id") void delete(String id);
}
