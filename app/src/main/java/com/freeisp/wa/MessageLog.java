package com.freeisp.wa;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Local SQLite log for message tracking on the device.
 */
public class MessageLog extends SQLiteOpenHelper {
    private static final String DB_NAME = "relay_log.db";
    private static final int DB_VERSION = 1;

    private static MessageLog instance;

    public static synchronized MessageLog getInstance(Context context) {
        if (instance == null) {
            instance = new MessageLog(context.getApplicationContext());
        }
        return instance;
    }

    private MessageLog(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE logs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "server_message_id INTEGER," +
                "phone TEXT," +
                "message TEXT," +
                "whatsapp_type TEXT," +
                "status TEXT," +
                "error_message TEXT," +
                "created_at TEXT" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS logs");
        onCreate(db);
    }

    public void log(int serverMessageId, String phone, String message, String whatsappType, String status, String error) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("server_message_id", serverMessageId);
        values.put("phone", phone);
        values.put("message", message);
        values.put("whatsapp_type", whatsappType);
        values.put("status", status);
        values.put("error_message", error);
        values.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        db.insert("logs", null, values);
    }

    public List<LogEntry> getRecentLogs(int limit) {
        List<LogEntry> entries = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT * FROM logs ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(limit)}
        );
        while (cursor.moveToNext()) {
            LogEntry entry = new LogEntry();
            entry.id = cursor.getInt(cursor.getColumnIndexOrThrow("id"));
            entry.serverMessageId = cursor.getInt(cursor.getColumnIndexOrThrow("server_message_id"));
            entry.phone = cursor.getString(cursor.getColumnIndexOrThrow("phone"));
            entry.message = cursor.getString(cursor.getColumnIndexOrThrow("message"));
            entry.whatsappType = cursor.getString(cursor.getColumnIndexOrThrow("whatsapp_type"));
            entry.status = cursor.getString(cursor.getColumnIndexOrThrow("status"));
            entry.errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("error_message"));
            entry.createdAt = cursor.getString(cursor.getColumnIndexOrThrow("created_at"));
            entries.add(entry);
        }
        cursor.close();
        return entries;
    }

    public int getCountByStatus(String status) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM logs WHERE status = ?", new String[]{status});
        int count = 0;
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0);
        }
        cursor.close();
        return count;
    }

    public void clearLogs() {
        getWritableDatabase().delete("logs", null, null);
    }

    public static class LogEntry {
        public int id;
        public int serverMessageId;
        public String phone;
        public String message;
        public String whatsappType;
        public String status;
        public String errorMessage;
        public String createdAt;
    }
}
