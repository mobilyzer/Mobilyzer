/*
 * Copyright 2014 RobustNet Lab, University of Michigan. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.mobilyzer.measurements;

import java.io.InvalidClassException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.UpdateIntent;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;
import com.mobilyzer.util.video.VideoPlayerService;
import com.mobilyzer.util.video.util.DemoUtil;

/**
 * @author laoyao
 * Measure the user-perceived Video QoE metrics by playing YouTube video in the background
 */
public class VideoQoETask extends MeasurementTask {
  // Type name for internal use
  public static final String TYPE = "video";
  // Human readable name for the task
  public static final String DESCRIPTOR = "Video QoE";

  private boolean isSucceed = false;
  private int numFrameDropped;
  private double initialLoadingTime;
  private ArrayList<Double> rebufferTimes = new ArrayList<Double>();
  private ArrayList<String> goodputTimestamps = new ArrayList<String>();
  private ArrayList<Double> goodputValues = new ArrayList<Double>();
  private ArrayList<Long> goodputEstimateValues = new ArrayList<Long>();
  private ArrayList<String> bitrateTimestamps = new ArrayList<String>();
  private ArrayList<Integer> bitrateValues = new ArrayList<Integer>();
  private long bbaSwitchTime;
  private long dataConsumed;
  
  private boolean isResultReceived;
  private long duration;
  /**
   * @author laoyao
   * Parameters for Video QoE measurement
   */
  public static class VideoQoEDesc extends MeasurementDesc {
    // The url to retrieve video
    public String contentURL;
    // The content id for YouTube video
    public String contentId;
    // The ABR algorithm for video playback
    public int contentType;

    public VideoQoEDesc(String key, Date startTime, Date endTime, double intervalSec,
            long count, long priority, int contextIntervalSec, Map<String, String> params) {
        super(VideoQoETask.TYPE, key, startTime, endTime, intervalSec, count, priority,
                contextIntervalSec, params);
        initializeParams(params);
        if (this.contentURL == null) {
            throw new InvalidParameterException("Video QoE task cannot be created"
                    + " due to null video url string");
        }
        if (this.contentType != DemoUtil.TYPE_DASH_VOD && this.contentType != DemoUtil.TYPE_PROGRESSIVE && this.contentType != DemoUtil.TYPE_BBA ) {
          throw new InvalidParameterException("Video QoE task cannot be created"
              + " due to invalid streaming algorithm: " + this.contentType);
        }
    }

    @Override
    public String getType() {
        return VideoQoETask.TYPE;
    }

    @Override
    protected void initializeParams(Map<String, String> params) {
        if (params == null) {
            return;
        }
        String val = null;

        this.contentURL = params.get("content_url");
        this.contentId = params.get("content_id");
        if ((val = params.get("content_type")) != null && Integer.parseInt(val) >= 0) {
          this.contentType = Integer.parseInt(val);
        }

    }

    protected VideoQoEDesc(Parcel in) {
        super(in);
        contentURL = in.readString();
        contentId = in.readString();
        contentType = in.readInt();
    }

    public static final Parcelable.Creator<VideoQoEDesc> CREATOR =
            new Parcelable.Creator<VideoQoEDesc>() {
        public VideoQoEDesc createFromParcel(Parcel in) {
            return new VideoQoEDesc(in);
        }

        public VideoQoEDesc[] newArray(int size) {
            return new VideoQoEDesc[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(this.contentURL);
        dest.writeString(this.contentId);
        dest.writeInt(this.contentType);
    }
  }
  
  /**
   * Constructor for video QoE measuremen task
   * @param desc
   */
  public VideoQoETask(MeasurementDesc desc) {
    super(new VideoQoEDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec,
        desc.count, desc.priority, desc.contextIntervalSec, desc.parameters));
    bbaSwitchTime=-1;
    dataConsumed=0;
  }
  
  protected VideoQoETask(Parcel in) {
    super(in);
  }

  public static final Parcelable.Creator<VideoQoETask> CREATOR =
      new Parcelable.Creator<VideoQoETask>() {
    public VideoQoETask createFromParcel(Parcel in) {
      return new VideoQoETask(in);
    }

    public VideoQoETask[] newArray(int size) {
      return new VideoQoETask[size];
    }
  };

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    super.writeToParcel(dest, flags);
  }

  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#clone()
   */
  @Override
  public MeasurementTask clone() {
    MeasurementDesc desc = this.measurementDesc;
    VideoQoEDesc newDesc =
            new VideoQoEDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec, desc.count,
                    desc.priority, desc.contextIntervalSec, desc.parameters);
    return new VideoQoETask(newDesc);
  }
  
  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#call()
   */
  @Override
  public MeasurementResult[] call() throws MeasurementError {
    Logger.d("Video QoE: measurement started");
    
    MeasurementResult[] mrArray = new MeasurementResult[1];
    VideoQoEDesc taskDesc = (VideoQoEDesc) this.measurementDesc;

    Intent videoIntent = new Intent(PhoneUtils.getGlobalContext(), VideoPlayerService.class);
    videoIntent.setData(Uri.parse(taskDesc.contentURL));
    videoIntent.putExtra(DemoUtil.CONTENT_ID_EXTRA, taskDesc.contentId);
    videoIntent.putExtra(DemoUtil.CONTENT_TYPE_EXTRA, taskDesc.contentType);
    PhoneUtils.getGlobalContext().startService(videoIntent);


    IntentFilter filter = new IntentFilter();
    filter.addAction(UpdateIntent.VIDEO_MEASUREMENT_ACTION);
    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          Logger.d("Video QoE: result received");
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_IS_SUCCEED)){
            isSucceed = intent.getBooleanExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_IS_SUCCEED, false);
            Logger.d("Is succeed: " + isSucceed);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_NUM_FRAME_DROPPED)){
            numFrameDropped = intent.getIntExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_NUM_FRAME_DROPPED, 0);
            Logger.d("Num frame dropped: " + numFrameDropped);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_INITIAL_LOADING_TIME)){
            initialLoadingTime = intent.getDoubleExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_INITIAL_LOADING_TIME, 0.0);
            Logger.d("Initial Loading Time: " + initialLoadingTime);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_REBUFFER_TIME)) {
            double[] rebufferTimeArray = intent.getDoubleArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_REBUFFER_TIME);
            for (double rebuffer : rebufferTimeArray) {
              rebufferTimes.add(rebuffer);
            }
            Logger.d("Rebuffer Times: " + rebufferTimes);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_TIMESTAMP)) {
            String[] goodputTimestampArray = intent.getStringArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_TIMESTAMP);
            goodputTimestamps = new ArrayList<String>(Arrays.asList(goodputTimestampArray));
            Logger.d("Goodput Timestamps: " + goodputTimestamps);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_VALUE)) {
            double[] goodputValueArray = intent.getDoubleArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_VALUE);
            for (double goodput : goodputValueArray) {
              goodputValues.add(goodput);
            }
            Logger.d("Goodput Values: " + goodputValues);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_ESTIMATE_VALUE)) {
            long[] goodputEstimateValueArray = intent.getLongArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_ESTIMATE_VALUE);
            for (long estiamte : goodputEstimateValueArray) {
              goodputEstimateValues.add(estiamte);
            }
            Logger.d("Goodput Estimated Values: " + goodputValues);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_TIMESTAMP)) {
            String[] bitrateTimestampArray = intent.getStringArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_TIMESTAMP);
            bitrateTimestamps = new ArrayList<String>(Arrays.asList(bitrateTimestampArray));
            Logger.d("Bitrate Timestamps: " + bitrateTimestamps);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_VALUE)) {
            int[] bitrateValueArray = intent.getIntArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_VALUE);
            for (int bitrate : bitrateValueArray) {
              bitrateValues.add(bitrate);
            }
            Logger.d("Bitrate Values: " + bitrateValues);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BBA_SWITCH_TIME)){
            bbaSwitchTime=intent.getLongExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BBA_SWITCH_TIME, -1);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BYTE_USED)){
            dataConsumed=intent.getLongExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BYTE_USED, 0);
            Logger.d("Data consumed: " + dataConsumed);
          }
          isResultReceived = true;
        }
    };
    PhoneUtils.getGlobalContext().registerReceiver(broadcastReceiver, filter);



    for(int i=0;i<60*5;i++){
        if(isDone()){
            break;
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    Logger.e("Video QoE: result ready? " + this.isResultReceived);
    PhoneUtils.getGlobalContext().unregisterReceiver(broadcastReceiver);
    
    if(isDone()){
        Logger.i("Video QoE: Successfully measured QoE data");
        PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
        MeasurementResult result = new MeasurementResult(
                phoneUtils.getDeviceInfo().deviceId,
                phoneUtils.getDeviceProperty(this.getKey()),
                VideoQoETask.TYPE, System.currentTimeMillis() * 1000,
                TaskProgress.COMPLETED, this.measurementDesc);
//        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_IS_SUCCEED, isSucceed);
        result.addResult("video_num_frame_dropped", this.numFrameDropped);
        result.addResult("video_initial_loading_time", this.initialLoadingTime);
        result.addResult("video_rebuffer_times", this.rebufferTimes);
        result.addResult("video_goodput_times", this.goodputTimestamps);
        result.addResult("video_goodput_values", this.goodputValues);
        result.addResult("video_goodput_estimate_values", this.goodputEstimateValues);
        result.addResult("video_bitrate_times", this.bitrateTimestamps);
        result.addResult("video_bitrate_values", this.bitrateValues);
        if(this.bbaSwitchTime!=-1){
          result.addResult("video_bba_switch_time", this.bbaSwitchTime);
        }

        Logger.i(MeasurementJsonConvertor.toJsonString(result));
        mrArray[0]=result;
    }else{
        Logger.i("Video QoE: Video measurement not finished");
        PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
        MeasurementResult result = new MeasurementResult(
                phoneUtils.getDeviceInfo().deviceId,
                phoneUtils.getDeviceProperty(this.getKey()),
                VideoQoETask.TYPE, System.currentTimeMillis() * 1000,
                TaskProgress.FAILED, this.measurementDesc);
//        result.addResult("error", "measurement timeout");
        Logger.i(MeasurementJsonConvertor.toJsonString(result));
        mrArray[0]=result;
    }

//    PhoneUtils.getGlobalContext().stopService(new Intent(PhoneUtils.getGlobalContext(), PLTExecutorService.class));
    return mrArray;
  }
  
  private boolean isDone() {
    return isResultReceived;
  }

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
      return VideoQoEDesc.class;
  }
  
  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#getDescriptor()
   */
  @Override
  public String getDescriptor() {
    return VideoQoETask.DESCRIPTOR;
  }


  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#getType()
   */
  @Override
  public String getType() {
    return VideoQoETask.TYPE;
  }


  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#stop()
   */
  @Override
  public boolean stop() {
    // There is nothing we need to do to stop the video measurement
    return false;
  }

  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#getDuration()
   */
  @Override
  public long getDuration() {
    return this.duration;
  }

  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#setDuration(long)
   */
  @Override
  public void setDuration(long newDuration) {
    if (newDuration < 0) {
      this.duration = 0;
    } else {
      this.duration = newDuration;
    }
  }

  /* (non-Javadoc)
   * @see com.mobilyzer.MeasurementTask#getDataConsumed()
   */
  @Override
  public long getDataConsumed() {
    return dataConsumed;
  }

}
