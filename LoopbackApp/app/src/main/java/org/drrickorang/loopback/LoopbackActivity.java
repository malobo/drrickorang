/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drrickorang.loopback;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
//import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.TextView;
import android.content.Context;
import android.database.Cursor;
import android.os.Handler;
import android.os.Message;

import java.util.Arrays;
import java.io.File;

import android.os.Build;

public class LoopbackActivity extends Activity {
    /**
     * Member Vars
     */

    public final static String SETTINGS_OBJECT = "org.drrickorang.loopback.SETTINGS_OBJECT";

    private static final int SAVE_TO_WAVE_REQUEST = 42;
    private static final int SETTINGS_ACTIVITY_REQUEST_CODE = 44;
    LoopbackAudioThread audioThread;
    NativeAudioThread nativeAudioThread;
    private WavePlotView mWavePlotView;

    SeekBar  mBarMasterLevel;
    TextView mTextInfo;
    private double [] mWaveData;
    int mSamplingRate;

    private Handler mMessageHandler = new Handler() {
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch(msg.what) {
                case LoopbackAudioThread.FUN_PLUG_AUDIO_THREAD_MESSAGE_REC_STARTED:
                    log("got message java rec complete!!");
                    Toast.makeText(getApplicationContext(), "Java Recording Started",
                            Toast.LENGTH_SHORT).show();
                    refreshState();
                    break;
                case LoopbackAudioThread.FUN_PLUG_AUDIO_THREAD_MESSAGE_REC_COMPLETE:
                    mWaveData = audioThread.getWaveData();
                    mSamplingRate = audioThread.mSamplingRate;
                    log("got message java rec complete!!");
                    refreshPlots();
                    refreshState();
                    Toast.makeText(getApplicationContext(), "Java Recording Completed",
                            Toast.LENGTH_SHORT).show();
                    stopAudioThread();
                    break;
                case NativeAudioThread.FUN_PLUG_NATIVE_AUDIO_THREAD_MESSAGE_REC_STARTED:
                    log("got message native rec complete!!");
                    Toast.makeText(getApplicationContext(), "Native Recording Started",
                            Toast.LENGTH_SHORT).show();
                    refreshState();
                    break;
                case NativeAudioThread.FUN_PLUG_NATIVE_AUDIO_THREAD_MESSAGE_REC_COMPLETE:
                    mWaveData = nativeAudioThread.getWaveData();
                    mSamplingRate = nativeAudioThread.mSamplingRate;
                    log("got message native rec complete!!");
                    refreshPlots();
                    refreshState();
                    Toast.makeText(getApplicationContext(), "Native Recording Completed",
                            Toast.LENGTH_SHORT).show();
                    stopAudioThread();
                    break;
                default:
                    log("Got message:"+msg.what);
                    break;
            }
        }
    };


    // Thread thread;

    /**
     * Called with the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set the layout for this activity. You can find it
        View view = getLayoutInflater().inflate(R.layout.main_activity, null);
        setContentView(view);

        mTextInfo = (TextView) findViewById(R.id.textInfo);
        mBarMasterLevel = (SeekBar) findViewById(R.id.BarMasterLevel);

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        mBarMasterLevel.setMax(maxVolume);

        mBarMasterLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress,boolean fromUser) {

                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                        progress, 0);
                refreshState();
                log("Changed stream volume to: "+progress);
            }
        });
        mWavePlotView = (WavePlotView) findViewById(R.id.viewWavePlot);
        refreshState();
    }

    private void stopAudioThread() {
        log("stopping audio threads");
        if (audioThread != null) {
            audioThread.isRunning = false;
            // isRunning = false;
            try {
                audioThread.finish();
                audioThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            audioThread = null;
        }
        if (nativeAudioThread != null) {
            nativeAudioThread.isRunning = false;
            // isRunning = false;
            try {
                nativeAudioThread.finish();
                nativeAudioThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            nativeAudioThread = null;
        }
    }

    public void onDestroy() {
        stopAudioThread();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        // TODO Auto-generated method stub
        super.onResume();
        //web.loadUrl(stream);
        log("on resume called");

        //restartAudioSystem();
    }

    @Override
    protected void onPause () {
        super.onPause();
        //stop audio system
        stopAudioThread();
    }

    private void restartAudioSystem() {

        log("restart audio system...");

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int sessionId = 0; /* FIXME runtime test for am.generateAudioSessionId() in API 21 */

        int samplingRate = getApp().getSamplingRate();
        int playbackBuffer = getApp().getPlayBufferSizeInBytes();
        int recordBuffer = getApp().getRecordBufferSizeInBytes();

        log(" current sampling rate: " + samplingRate);
        stopAudioThread();

        //select if java or native audio thread
        if (getApp().getAudioThreadType() == LoopbackApplication.AUDIO_THREAD_TYPE_JAVA ) {
            audioThread = new LoopbackAudioThread();
            audioThread.setMessageHandler(mMessageHandler);
            audioThread.mSessionId = sessionId;
            audioThread.setParams(samplingRate, playbackBuffer, recordBuffer);
            audioThread.start();
        } else {
            nativeAudioThread = new NativeAudioThread();
            nativeAudioThread.setMessageHandler(mMessageHandler);
            nativeAudioThread.mSessionId = sessionId;
            nativeAudioThread.setParams(samplingRate, playbackBuffer, recordBuffer);
            nativeAudioThread.start();
        }
        mWavePlotView.setSamplingRate( samplingRate);


        //first refresh
        refreshState();
    }

    /** Called when the user clicks the button */
    public void onButtonTest(View view) {
        restartAudioSystem();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (getApp().getAudioThreadType() == LoopbackApplication.AUDIO_THREAD_TYPE_JAVA ) {
            if (audioThread != null) {
                audioThread.runTest();
            }
        }
        else {
            if (nativeAudioThread != null) {
                nativeAudioThread.runTest();
            }
        }
    }

    /** Called when the user clicks the button */
    public void onButtonSave(View view) {

        //create filename with date
        String date = (String) DateFormat.format("yyyy_MM_dd_kk_mm", System.currentTimeMillis());
        String fileName = "loopback_"+date+".wav";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // browser.
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/wav");

            intent.putExtra(Intent.EXTRA_TITLE,fileName); //suggested filename
            startActivityForResult(intent, SAVE_TO_WAVE_REQUEST);
        }
        else {
            Toast.makeText(getApplicationContext(), "Saving Wave to: "+fileName,
                    Toast.LENGTH_SHORT).show();

            //save to a given uri... local file?
            Uri uri = Uri.parse("file://mnt/sdcard/"+fileName);
            saveToWavefile(uri);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode,
            Intent resultData) {
        log("ActivityResult request: "+requestCode + "  result:" + resultCode);
        if (requestCode == SAVE_TO_WAVE_REQUEST && resultCode == Activity.RESULT_OK) {
            log("got SAVE TO WAV intent back!");
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                saveToWavefile(uri);
            }
        } else if (requestCode == SETTINGS_ACTIVITY_REQUEST_CODE &&
                resultCode == Activity.RESULT_OK) {
            //new settings!
            log("return from settings!");
        }
    }

    /** Called when the user clicks the button */
    public void onButtonZoomOutFull(View view) {

        double fullZoomOut = mWavePlotView.getMaxZoomOut();

        mWavePlotView.setZoom(fullZoomOut);
        mWavePlotView.refreshGraph();
    }

    public void onButtonZoomOut(View view) {

        double zoom = mWavePlotView.getZoom();

        zoom = 2.0 *zoom;
        mWavePlotView.setZoom(zoom);
        mWavePlotView.refreshGraph();
    }

    /** Called when the user clicks the button */
    public void onButtonZoomIn(View view) {

        double zoom = mWavePlotView.getZoom();

        zoom = zoom/2.0;
        mWavePlotView.setZoom(zoom);
        mWavePlotView.refreshGraph();
    }

/*
    public void onButtonZoomInFull(View view) {

        double minZoom = mWavePlotView.getMinZoomOut();

        mWavePlotView.setZoom(minZoom);
        mWavePlotView.refreshGraph();
    }
*/

    /** Called when the user clicks the button */
    public void onButtonSettings(View view) {
        Intent mySettingsIntent = new Intent (this, SettingsActivity.class);
        //send settings
        startActivityForResult(mySettingsIntent, SETTINGS_ACTIVITY_REQUEST_CODE);
    }

    void refreshPlots() {
        mWavePlotView.setData(mWaveData);
        mWavePlotView.redraw();
    }

    void refreshState() {

        log("refreshState!");

        Button buttonTest = (Button) findViewById(R.id.buttonTest);

        //get current audio level
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        int currentVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        mBarMasterLevel.setProgress(currentVolume);

        log("refreshState 2");

        //get info
        int samplingRate = getApp().getSamplingRate();
        int playbackBuffer = getApp().getPlayBufferSizeInBytes()/getApp().BYTES_PER_FRAME;
        int recordBuffer = getApp().getRecordBufferSizeInBytes()/getApp().BYTES_PER_FRAME;
        StringBuilder s = new StringBuilder(200);
        s.append("SR: " + samplingRate + " Hz");
        int audioThreadType = getApp().getAudioThreadType();
        switch(audioThreadType) {
            case LoopbackApplication.AUDIO_THREAD_TYPE_JAVA:
                s.append(" Play Frames: " + playbackBuffer);
                s.append(" Record Frames: " + recordBuffer);
                s.append(" Audio: JAVA");
            break;
            case LoopbackApplication.AUDIO_THREAD_TYPE_NATIVE:
                s.append(" Frames: " + playbackBuffer);
                s.append(" Audio: NATIVE");
                break;
        }
        mTextInfo.setText(s.toString());
    }

    private static void log(String msg) {
        Log.v("Recorder", msg);
    }

    private LoopbackApplication getApp() {
        return (LoopbackApplication) this.getApplication();
    }

    void saveToWavefile(Uri uri) {

       // double [] data = audioThread.getWaveData();
        if (mWaveData != null && mWaveData.length > 0 ) {
            AudioFileOutput audioFileOutput = new AudioFileOutput(getApplicationContext(), uri,
                    mSamplingRate);
            boolean status = audioFileOutput.writeData(mWaveData);

            if (status) {
                Toast.makeText(getApplicationContext(), "Finished exporting wave File",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(getApplicationContext(), "Something failed saving wave file",
                        Toast.LENGTH_SHORT).show();
            }
        }

    }
}