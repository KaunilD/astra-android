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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.openni.Device;
import org.openni.OpenNI;
import org.openni.ParamsRegistrationMode;
import org.openni.SensorType;
import org.openni.VideoFrameRef;
import org.openni.VideoMode;
import org.openni.VideoStream;
import org.openni.PixelFormat;
import org.openni.android.OpenNIView;
import org.w3c.dom.Text;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.Sensor;
import android.os.Environment;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.AdapterView.OnItemSelectedListener;

public class StreamView extends RelativeLayout {

	private static final String TAG = "StreamView";
	private static final String LOG_TAG = "StreamView";
	private Thread mMainLoopThread;
	private boolean mShouldRun = true;
	private Device mDevice;
	private VideoStream mStream;
	private Button captureButton;
	private List<SensorType> mDeviceSensors;
	private List<VideoMode> mStreamVideoModes;
	private OpenNIView mFrameView;
	private TextView mStatusLine;
	private TextView sensorTypeTextView, videoModeTextView;

	public ByteBuffer frameData;

	public int sensorType;
    private static SensorType[] SENSORS = { SensorType.DEPTH, SensorType.COLOR, SensorType.IR };
	private static CharSequence[] SENSOR_NAMES = { "Depth", "Color", "IR" };

	public StreamView(Context context, int sensorType) {
		super(context);
		this.sensorType = sensorType;
		initialize(context);
	}

	public StreamView(Context context, AttributeSet attrs) {
		super(context, attrs);
		initialize(context);
	}

	public StreamView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initialize(context);
	}
	
	public VideoStream getStream() {
		return mStream;
	}
	
	private void initialize(Context context) {
		View.inflate(context, R.layout.stream_view, this);
		
		if (!isInEditMode()) {
			captureButton = (Button) findViewById(R.id.capture);
			mFrameView = (OpenNIView) findViewById(R.id.frameView);
			mStatusLine = (TextView) findViewById(R.id.status_line);
            sensorTypeTextView = (TextView) findViewById(R.id.sensorType);
            videoModeTextView = (TextView) findViewById(R.id.videoMode);

            sensorTypeTextView.setText(SENSOR_NAMES[this.sensorType]);


			captureButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    captureFrame();
                }
            });


		}
	}
	
	public void setDevice(Device device) {
		mDevice = device;
		mDeviceSensors = new ArrayList<SensorType>();

		List<CharSequence> sensors = new ArrayList<CharSequence>();

		for (int i = 0; i < SENSORS.length; ++i) {
			if (mDevice.hasSensor(SENSORS[i])) {
				sensors.add(SENSOR_NAMES[i]);
				mDeviceSensors.add(SENSORS[i]);
			}
		}

		SensorType sensor = mDeviceSensors.get(this.sensorType);
		this.mStream = VideoStream.create(this.mDevice, sensor);

        this.videoModeTextView.setText(pixelFormatToName(this.mStream.getVideoMode().getPixelFormat()).toString().toUpperCase());

        if (sensor == SensorType.DEPTH) {
            mFrameView.setBaseColor(Color.YELLOW);
        } else {
            mFrameView.setBaseColor(Color.WHITE);
        }

	}

	public void stop() {
		mShouldRun = false;

		while (mMainLoopThread != null) {
			try {
				mMainLoopThread.join();
				mMainLoopThread = null;
				break;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		if (mStream != null) {
			mStream.stop();
		}

		mFrameView.clear();
		mStatusLine.setText(R.string.waiting_for_frames);
	}

    private CharSequence pixelFormatToName(PixelFormat format) {
        switch (format) {
            case DEPTH_1_MM:    return "1 mm";
            case DEPTH_100_UM:  return "100 um";
            case SHIFT_9_2:     return "9.2";
            case SHIFT_9_3:     return "9.3";
            case RGB888:        return "RGB";
            case GRAY8:         return "Gray8";
            case GRAY16:        return "Gray16";
            case YUV422:		return "YUV422";
            case YUYV:			return "YUYV";
            default:            return "UNKNOWN";
        }
    }
	

    public ByteBuffer captureFrame(){
        mStream.start();

        List<VideoStream> streams = new ArrayList<VideoStream>();
        streams.add(mStream);


        VideoFrameRef frame = null;

        try {
            OpenNI.waitForAnyStream(streams, 1000);
            frame = mStream.readFrame();

            // Request rendering of the current OpenNI frame

            mFrameView.update(frame);
            frameData = frame.getData().order(ByteOrder.LITTLE_ENDIAN);
            frame.release();

            updateLabel(String.format("Frame Index: %,d | Timestamp: %,d", frame.getFrameIndex(), frame.getTimestamp()));

        } catch (TimeoutException e) {
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(TAG, "Failed reading frame: " + e);
        }

        mStream.stop();

        return frameData;
    }
	
	private void showAlert(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
		builder.setMessage(message);
		builder.show();
	}
	
	private void updateLabel(final String message) {
		post(new Runnable() {
			public void run() {
				mStatusLine.setText(message);								
			}
		});
	}
}
