package com.example.easycar.data;
import androidx.room.AutoMigration;
import androidx.room.Database;
import androidx.room.RoomDatabase;
// v2 adds frame_chunks only. Existing v1 rows keep their embedded frames and still load;
// AppRepository moves them to chunks when it loads them.
@Database(entities = {SessionEntity.class, FrameChunkEntity.class}, version = 2, exportSchema = true,
        autoMigrations = {@AutoMigration(from = 1, to = 2)})
public abstract class EasyCarDatabase extends RoomDatabase { public abstract SessionDao sessions(); }
