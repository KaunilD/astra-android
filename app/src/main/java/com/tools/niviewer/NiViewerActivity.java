/*****************************************************************************
*                                                                            *
*  OpenNI 2.x Alpha                                                          *
*  Copyright (C) 2012 PrimeSense Ltd.                                        *
*                                                                            *
*  This file is part of OpenNI.                                              *
*                                                                            *
*  Licensed under the Apache License, Version 2.0 (the "License");           *
*  you may not use this file except in compliance with the License.          *
*  You may obtain a copy of the License at                                   *
*                                                                            *
*      http://www.apache.org/licenses/LICENSE-2.0                            *
*                                                                            *
*  Unless required by applicable law or agreed to in writing, software       *
*  distributed under the License is distributed on an "AS IS" BASIS,         *
*  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
*  See the License for the specific language governing permissions and       *
*  limitations under the License.                                            *
*                                                                            *
*****************************************************************************/
package com.tools.niviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.os.AsyncTask;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup.LayoutParams;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.openni.Device;
import org.openni.DeviceInfo;
import org.openni.ImageRegistrationMode;
import org.openni.OpenNI;
import org.openni.Recorder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

public class NiViewerActivity 
		extends AppCompatActivity
		implements OpenNIHelper.DeviceOpenListener, SensorEventListener {
	
	private static final String TAG = "NiViewer";
	private OpenNIHelper mOpenNIHelper;
	private boolean mDeviceOpenPending = false;
	private Device mDevice;
	private Recorder mRecorder;
	private String mRecordingName;
	private String mRecording;
	private LinearLayout mStreamsContainer;
	private int mActiveDeviceID = -1;
	private boolean looping = false;
    private AlarmLoopReciever alarmLoopReciever;
    private SensorManager mSensorManager;
    private Sensor mTempSensor;
    private int packetCreation = 3;

    public int mProgressStatus = 0;
    int ambientTemperature = 0;

    Intent serviceIntent;
    private String mDeviceURI;

    @Override
	protected void onCreate(Bundle savedInstanceState) {
		Log.d(TAG, "onCreate");

		setContentView(R.layout.activity_niviewer);
		mOpenNIHelper = new OpenNIHelper(this);
		OpenNI.setLogAndroidOutput(true);
		OpenNI.setLogMinSeverity(0);
		OpenNI.initialize();
		super.onCreate(savedInstanceState);
		mStreamsContainer = (LinearLayout)findViewById(R.id.streams_container);
		onConfigurationChanged(getResources().getConfiguration());
        mOpenNIHelper.requestDeviceOpen(this);

        IntentFilter iFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        this.registerReceiver(mBroadcastReceiver,iFilter);

        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        mTempSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
    }

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.niviewer_menu, menu);
		return true;
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
            case R.id.loop:
                toggleLooping(item);
                return true;
			case R.id.record:
				toggleRecording(item);
				return true;
			case R.id.device:
				switchDevice();
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	@Override
	protected void onDestroy() {
		Log.d(TAG, "onDestroy");
		super.onDestroy();
		if(this.alarmLoopReciever!=null){
            unregisterReceiver(this.alarmLoopReciever);
        }
        if(this.mBroadcastReceiver!=null){
		    unregisterReceiver(mBroadcastReceiver);
        }


        for (StreamView streamView : getStreamViews()) {
            streamView.stop();
        }

        if (mDevice != null) {
            mDevice.close();
            mDevice = null;
        }

        mSensorManager.unregisterListener(this);

        mOpenNIHelper.shutdown();
		OpenNI.shutdown();
	}
	
	@Override 
	protected void onStart() {
		Log.d(TAG, "onStart");
		super.onStart();
		
		final android.content.Intent intent = getIntent ();

		if (intent != null) {
			final android.net.Uri data = intent.getData ();
			if (data != null) {
				mRecording = data.getEncodedPath ();
				Log.d(TAG, "Will open file " + mRecording);
			}
		}
	}
	
	@Override
	public void onConfigurationChanged(Configuration config) {
		Log.d(TAG, "onConfigurationChanged");

		if (Configuration.ORIENTATION_PORTRAIT == config.orientation) {
			mStreamsContainer.setOrientation(LinearLayout.VERTICAL);
		} else {
			mStreamsContainer.setOrientation(LinearLayout.HORIZONTAL);
		}

		//Re-insert each view to force correct display (forceLayout() doesn't work)
		for (StreamView streamView : getStreamViews()) {
			mStreamsContainer.removeView(streamView);
			setStreamViewLayout(streamView, config);
			mStreamsContainer.addView(streamView);
		}

		super.onConfigurationChanged(config);
	}
	
	private void showAlert(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(message);
		builder.show();
	}
	
	private void showAlertAndExit(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setMessage(message);
		builder.setNeutralButton("OK", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				finish();
			}
		});
		builder.show();
	}

	@Override
	public void onDeviceOpened(UsbDevice aDevice) {
		Log.d(TAG, "Permission granted for device " +aDevice.getDeviceName()+ " "  + aDevice.getProductId() + " " + aDevice.getVendorId());
        mDevice = Device.open();
        mDeviceURI = mDevice.getDeviceInfo().getUri();

        mDeviceOpenPending = false;



		//Find device ID
		List<DeviceInfo> devices = OpenNI.enumerateDevices();
		for(int i=0; i < devices.size(); i++)
		{
			if(devices.get(i).getUri().equals(mDevice.getDeviceInfo().getUri())){
				mActiveDeviceID = i;
				break;
			}
		}


        for (StreamView streamView : getStreamViews()) {
			streamView.setDevice(mDevice);
		}

        mStreamsContainer.requestLayout();
        addStream();
		/*
        alarmLoopReciever = new AlarmLoopReciever(getStreamViews());
		registerReceiver(alarmLoopReciever, new IntentFilter("insylo.loopreciever"));
		*/
    }
	
	private void setStreamViewLayout(StreamView streamView, Configuration config) {
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); 
		if (config.orientation == Configuration.ORIENTATION_PORTRAIT) {
			params.width = LayoutParams.WRAP_CONTENT;
			params.height = 0;
		} else {
			params.width = 0;
			params.height = LayoutParams.WRAP_CONTENT;
		}

		params.weight = 1;
		params.gravity = Gravity.CENTER;
		streamView.setLayoutParams(params);
	}


	private void addStream() {
		StreamView depthStreamView = new StreamView(this, 0);
		setStreamViewLayout(depthStreamView, getResources().getConfiguration());
		depthStreamView.setDevice(mDevice);
		mStreamsContainer.addView(depthStreamView);

		StreamView irStreamView = new StreamView(this, 2);
		setStreamViewLayout(irStreamView, getResources().getConfiguration());
		irStreamView.setDevice(mDevice);
		mStreamsContainer.addView(irStreamView);

		/*
        StreamView colorStreamView = new StreamView(this, 1);
        setStreamViewLayout(colorStreamView, getResources().getConfiguration());
        colorStreamView.setDevice(mDevice);
        mStreamsContainer.addView(colorStreamView);
        */

		mStreamsContainer.requestLayout();
	}

	@SuppressLint("SimpleDateFormat")
	private void toggleRecording(MenuItem item) {
		if (mRecorder == null) {
			mRecordingName = Environment.getExternalStorageDirectory().getPath() +
					"/" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".oni";

			try {
				mRecorder = Recorder.create(mRecordingName);
				for (StreamView streamView : getStreamViews()) {
					mRecorder.addStream(streamView.getStream(), true);
				}
				mRecorder.start();
			} catch (RuntimeException ex) {
				mRecorder = null;
				showAlert("Failed to start recording: " + ex.getMessage());
				return;
			}
			
			item.setTitle("RECORDING");
		} else {
			stopRecording();
			item.setTitle("RECORD");
		}
	}

	/*
    private void toggleLooping(MenuItem item){
        looping = ! looping;
        if(looping){
            item.setTitle("LOOPING");
            Intent intent = new Intent(getBaseContext(), InsyloDataCaptureService.class);
            intent.putExtra("uri", mDeviceURI);
            startService(intent);
        }else{
            Intent intent = new Intent(getBaseContext(), InsyloDataCaptureService.class);
            intent.putExtra("uri", mDeviceURI);

            item.setTitle("LOOP");
            stopService(new Intent(getBaseContext(), InsyloDataCaptureService.class));
        }
    }
	*/

    private void toggleLooping(MenuItem item){

        looping =! looping;

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent;
        PendingIntent pendingIntent;

        intent = new Intent("insylo.loopreciever");
        pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);

        if(looping){
            item.setTitle("LOOPING");
            alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, SystemClock.elapsedRealtime()+3000, 3000, pendingIntent);
        }else{
            item.setTitle("LOOP");
            alarmManager.cancel(pendingIntent);
        }

    }


	private void stopRecording() {
		if (mRecorder != null) {
			mRecorder.stop();
			mRecorder.destroy();
			mRecorder = null;

			mRecordingName = null;
		}
	}
	
	private void switchDevice() {
		List<DeviceInfo> devices = OpenNI.enumerateDevices();
		if (devices.isEmpty()) {
			showAlertAndExit("No OpenNI-compliant device found.");
			return;
		}

		new DeviceSelectDialog().showDialog(devices, mActiveDeviceID, this);
	}

	public void openDevice(String deviceURI) {
		if (mDeviceOpenPending) {
			return;
		}

		stopRecording();

		for (StreamView streamView : getStreamViews()) {
			streamView.stop();
			mStreamsContainer.removeView(streamView);
		}

		if (mDevice != null) {
			mDevice.close();
		}
		
		mDeviceOpenPending = true;
		mOpenNIHelper.requestDeviceOpen(deviceURI, this);
	}

	@Override
	public void onDeviceOpenFailed(UsbDevice device) {
		Log.e(TAG, "Failed to open device " + device.getDeviceName());
		mDeviceOpenPending = false;
		showAlertAndExit("Failed to open device");
	}

	@Override
	protected void onPause() {
		Log.d(TAG, "onPause");

		super.onPause();
		
		// onPause() is called just before the USB permission dialog is opened, in which case, we don't
		// want to shutdown OpenNI
		if (mDeviceOpenPending)
			return;

		stopRecording();

	}

	private List<StreamView> getStreamViews() {
		int count = mStreamsContainer.getChildCount();
		ArrayList<StreamView> list = new ArrayList<StreamView>(count);
		for (int i = 0; i < count; ++i) {
			StreamView view = (StreamView)mStreamsContainer.getChildAt(i);
			list.add(view);
		}
		return list;
	}

    @Override
    public void onSensorChanged(SensorEvent event) {
        this.ambientTemperature = (int) event.values[0];
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    public class AlarmLoopReciever extends BroadcastReceiver{
        List<StreamView> streamViews;

        AlarmLoopReciever(List<StreamView> list){
            this.streamViews = list;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (packetCreation == 3 ){
                List<ByteBuffer> frameData = new ArrayList<>();
                for(StreamView streamView : this.streamViews){
                    frameData.add(streamView.captureFrame());
                }
                List<Integer> meta = new ArrayList<>();
                meta.add(mProgressStatus);
                meta.add(ambientTemperature);

                CreateDataPacket createDataPacket = new CreateDataPacket(frameData.get(0), frameData.get(1), meta);
                createDataPacket.execute();
            }else{
                Toast.makeText(NiViewerActivity.this, "Skipping Capture. Packet Creation in Queue", Toast.LENGTH_SHORT).show();
            }

        }
    }


    private class CreateDataPacket extends AsyncTask{
        private ByteBuffer depthBuffer, irBuffer;
        private List<Integer> meta;

        CreateDataPacket(){

        }

        CreateDataPacket(ByteBuffer depthBuffer, ByteBuffer irBuffer, List<Integer> meta){
            this.depthBuffer = depthBuffer;
            this.irBuffer = irBuffer;
            this.meta = meta;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            packetCreation = 1;

        }

        @Override
        protected Object doInBackground(Object[] objects) {
            packetCreation = 2;
            String dataDirPath = Environment.getExternalStorageDirectory().getPath() + "/" +"insylo_data";
            File dataDir = new File(dataDirPath);

            if (!dataDir.exists()){
                dataDir.mkdir();
            }

            String timeStamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            String depthCSVFileName = dataDir +
                    "/" + timeStamp + "_depth.csv";
            String irCSVFileName = dataDir +
                    "/" + timeStamp + "_ir.csv";
            String metaCSVFileName = dataDir +
                    "/" + timeStamp + "_meta.csv";

            List<String> depthValues = new ArrayList<>();
            while(this.depthBuffer.remaining() > 0){
                depthValues.add(Integer.toString(this.depthBuffer.getShort() & 0xFFFF));
            }

            List<String> irValues = new ArrayList<>();
            while(this.irBuffer.remaining() > 0){
                int red = (int) irBuffer.get() & 0xFF;
                int green = (int) irBuffer.get() & 0xFF;
                int blue = (int) irBuffer.get() & 0xFF;

                irValues.add(
                		Integer.toString((red << 16) | (green << 8) | (blue))
                );
            }


            try {
                FileWriter csvWriter = new FileWriter(depthCSVFileName);
                CSVUtils.writeLine(csvWriter, depthValues);

                csvWriter = new FileWriter(irCSVFileName);
                CSVUtils.writeLine(csvWriter, irValues);

                csvWriter = new FileWriter(metaCSVFileName);
                CSVUtils.writeLine(csvWriter, Arrays.asList(
                        Integer.toString(this.meta.get(0)),
                        Integer.toString(this.meta.get(1)))
                );

                csvWriter.flush();
                csvWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }


            return null;
        }

        @Override
        protected void onPostExecute(Object o) {
            packetCreation = 3;
            super.onPostExecute(o);
        }
    }


    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1);


            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
            // Display the battery level in TextView

            // Calculate the battery charged percentage
            float percentage = level/ (float) scale;
            // Update the progress bar to display current battery charged percentage
            mProgressStatus = (int)((percentage)*100);

        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, mTempSensor, SensorManager.SENSOR_DELAY_NORMAL);

    }

}
