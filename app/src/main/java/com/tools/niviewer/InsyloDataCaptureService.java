package com.tools.niviewer;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import org.openni.Device;
import org.openni.OpenNI;
import org.openni.SensorType;
import org.openni.VideoFrameRef;
import org.openni.VideoStream;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static android.content.ContentValues.TAG;

/**
 * Created by kaunildhruv on 16/04/18.
 */

public class InsyloDataCaptureService extends Service {
    Device device;
    VideoStream videoStream;
    PendingIntent alarmPendingIntent;
    Intent alarmIntent;
    AlarmManager alarmManager;
    AlarmLoopReciever alarmLoopReciever;
    @Override
    public void onCreate() {
        super.onCreate();

    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        device = Device.open();
        videoStream = VideoStream.create(device, SensorType.DEPTH);

        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        alarmIntent = new Intent("insylo.loopreciever");
        alarmPendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);

        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, SystemClock.elapsedRealtime()+3000, 3000, alarmPendingIntent);

        alarmLoopReciever = new AlarmLoopReciever();

        registerReceiver(alarmLoopReciever, new IntentFilter("insylo.loopreciever"));

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(alarmLoopReciever);
        alarmManager.cancel(alarmPendingIntent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void captureFrame(){
        videoStream.start();



        List<VideoStream> streams = new ArrayList<VideoStream>();
        streams.add(videoStream);


        VideoFrameRef frame = null;
        ByteBuffer frameData;
        try {
            OpenNI.waitForAnyStream(streams, 1000);
            frame = videoStream.readFrame();

            // Request rendering of the current OpenNI frame
            frameData = frame.getData().order(ByteOrder.LITTLE_ENDIAN);
            frame.release();

        } catch (TimeoutException e) {
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(TAG, "Failed reading frame: " + e);
        }

        videoStream.stop();
    }



    public class AlarmLoopReciever extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            captureFrame();
        }
    }
}
