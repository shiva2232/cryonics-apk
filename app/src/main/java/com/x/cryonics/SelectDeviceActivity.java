package com.x.cryonics;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

public class SelectDeviceActivity extends AppCompatActivity implements DeviceAdapter.OnDeviceClickListener {

    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView tvEmpty;

    private FirebaseAuth mAuth;
    private DatabaseReference userDevicesRef;
    private List<Device> deviceList;
    private DeviceAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select_device);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }

        FirebaseApp.initializeApp(this);
        mAuth = FirebaseAuth.getInstance();

        recyclerView = findViewById(R.id.recycler_view_devices);
        progressBar = findViewById(R.id.progress_bar);
        tvEmpty = findViewById(R.id.tv_empty);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        deviceList = new ArrayList<>();
        adapter = new DeviceAdapter(deviceList, this);
        recyclerView.setAdapter(adapter);

        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            Toast.makeText(this, "User not signed in", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Ref: /users/$uid/
        userDevicesRef = FirebaseDatabase.getInstance()
                .getReference("users")
                .child(currentUser.getUid());

        loadDevices();
    }

    private void loadDevices() {
        progressBar.setVisibility(View.VISIBLE);

        userDevicesRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                deviceList.clear();
                if (snapshot.exists()) {
                    for (DataSnapshot deviceSnap : snapshot.getChildren()) {
                        // Skip any non-device keys if needed:
                        // E.g., if `users/$uid/devices` is not separated but direct child nodes are devices
                        Device device = deviceSnap.getValue(Device.class);
                        if (device != null) {
                            // Store deviceId as the key
                            device.setDeviceId(deviceSnap.getKey());
                            deviceList.add(device);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(deviceList.isEmpty() ? View.VISIBLE : View.GONE);
                } else {
                    tvEmpty.setVisibility(View.VISIBLE);
                }
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(SelectDeviceActivity.this, "Error: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDeviceClick(Device device) {
        // Pass the selected deviceId to DeviceCommandsActivity
        Intent intent = new Intent(SelectDeviceActivity.this, DeviceCommandsActivity.class);
        intent.putExtra("deviceId", device.getDeviceId());
        startActivity(intent);
    }
}
