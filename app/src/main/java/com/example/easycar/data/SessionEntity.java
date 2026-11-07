package com.example.easycar.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName = "sessions")
public class SessionEntity {
    @PrimaryKey @NonNull public String id = "";
    public long createdAt;
    @NonNull public String payload = "";
}
