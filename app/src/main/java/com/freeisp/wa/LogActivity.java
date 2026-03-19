package com.freeisp.wa;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LogActivity extends AppCompatActivity {

    private LogAdapter adapter;
    private MessageLog messageLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        messageLog = MessageLog.getInstance(this);

        RecyclerView recycler = findViewById(R.id.recyclerLogs);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogAdapter();
        recycler.setAdapter(adapter);

        findViewById(R.id.btnClearLogs).setOnClickListener(v -> {
            messageLog.clearLogs();
            adapter.setLogs(new ArrayList<>());
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show();
        });

        loadLogs();
    }

    private void loadLogs() {
        List<MessageLog.LogEntry> logs = messageLog.getRecentLogs(200);
        adapter.setLogs(logs);
    }

    // ---- RecyclerView Adapter ----
    static class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {
        private List<MessageLog.LogEntry> logs = new ArrayList<>();

        void setLogs(List<MessageLog.LogEntry> logs) {
            this.logs = logs;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_log, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            MessageLog.LogEntry entry = logs.get(position);

            holder.tvStatus.setText(entry.status.toUpperCase());
            holder.tvTime.setText(entry.createdAt);
            holder.tvPhone.setText(entry.phone.isEmpty() ? "#" + entry.serverMessageId : entry.phone);

            String msgText = entry.message;
            if (entry.errorMessage != null && !entry.errorMessage.isEmpty()) {
                msgText = "Error: " + entry.errorMessage;
            } else if (msgText == null || msgText.isEmpty()) {
                msgText = "Status update";
            }
            holder.tvMessage.setText(msgText);

            // Color the status badge
            int color;
            switch (entry.status) {
                case "delivered":
                    color = 0xFF25D366;
                    break;
                case "failed":
                    color = 0xFFF44336;
                    break;
                case "sending":
                    color = 0xFFFF9800;
                    break;
                default:
                    color = 0xFF999999;
                    break;
            }
            holder.tvStatus.setBackgroundColor(color);
        }

        @Override
        public int getItemCount() {
            return logs.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvStatus, tvTime, tvPhone, tvMessage;

            ViewHolder(View v) {
                super(v);
                tvStatus = v.findViewById(R.id.tvLogStatus);
                tvTime = v.findViewById(R.id.tvLogTime);
                tvPhone = v.findViewById(R.id.tvLogPhone);
                tvMessage = v.findViewById(R.id.tvLogMessage);
            }
        }
    }
}
