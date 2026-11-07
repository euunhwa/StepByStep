package com.example.easycar.data;
import androidx.room.Database;
import androidx.room.RoomDatabase;
@Database(entities = {SessionEntity.class}, version = 1, exportSchema = true)
public abstract class EasyCarDatabase extends RoomDatabase { public abstract SessionDao sessions(); }
