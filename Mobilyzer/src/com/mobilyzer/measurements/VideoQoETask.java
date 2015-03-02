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

import java.io.IOException;
import java.io.InvalidClassException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
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
  public static int counter = 0;
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
  private ArrayList<String> bitrateTimestamps = new ArrayList<String>();
  private ArrayList<Integer> bitrateValues = new ArrayList<Integer>();
  private ArrayList<String> bufferLoadTimestamps = new ArrayList<String>();
  private ArrayList<Long> bufferLoadValues = new ArrayList<Long>();
  private long bbaSwitchTime;
  private long dataConsumed;
  
  private boolean isResultReceived;
  private long duration;
  
  private long startTimeFilter;
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
    // The percentage of energy saving on radio energy consumption requested by user
    public String energySaving;
    // The buffer size in # of buffer blocks
    public int bufferSegments;

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
        this.energySaving = params.get("energy_saving");
        if (this.energySaving != null && Integer.parseInt(this.energySaving) >= 100) {
          this.energySaving = "100";
        }
        if ((val = params.get("buffer_segments")) != null) {
          int num = Integer.parseInt(val);
          if (num >= 0 && num <= 200) {
            this.bufferSegments = num;
          }
        }
    }

    protected VideoQoEDesc(Parcel in) {
        super(in);
        contentURL = in.readString();
        contentId = in.readString();
        contentType = in.readInt();
        energySaving = in.readString();
        bufferSegments = in.readInt();
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
        dest.writeString(this.energySaving);
        dest.writeInt(this.bufferSegments);
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
//    this.startTimeFilter = System.currentTimeMillis() + VideoQoETask.counter;
    this.startTimeFilter = VideoQoETask.counter;
    VideoQoETask.counter++;
    
  }
  
  protected VideoQoETask(Parcel in) {
    super(in);
//    this.bbaSwitchTime = in.readLong();
//    this.dataConsumed = in.readLong();
//    this.startTimeFilter = in.readLong();
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
//    dest.writeLong(this.bbaSwitchTime);
//    dest.writeLong(this.dataConsumed);
//    dest.writeLong(this.startTimeFilter);
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


    Process process = null;
    try {
//      process = new ProcessBuilder()
//          .command("su", "-c", "/system/bin/tcpdump", "-s", "0", "-w", "hongyi_buffer_1.pcap")
//          .start();
      
      process = Runtime.getRuntime().exec("su -c /system/bin/tcpdump -s 0 -w /sdcard/Mobiperf/hongyi_pcap_" + System.currentTimeMillis() + ".pcap");
      Thread.sleep(5000);
    } catch (IOException e1) {
      Logger.e("Tcpdump start failed!");
      return mrArray;
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
    
    Intent videoIntent = new Intent(PhoneUtils.getGlobalContext(), VideoPlayerService.class);
    videoIntent.setData(Uri.parse(taskDesc.contentURL));
    videoIntent.putExtra(DemoUtil.CONTENT_ID_EXTRA, taskDesc.contentId);
    videoIntent.putExtra(DemoUtil.CONTENT_TYPE_EXTRA, taskDesc.contentType);
    videoIntent.putExtra(DemoUtil.START_TIME_FILTER, this.startTimeFilter);
    videoIntent.putExtra(DemoUtil.ENERGY_SAVING_EXTRA, 1 - (Double.valueOf(taskDesc.energySaving) / 100));
    videoIntent.putExtra(DemoUtil.BUFFER_SEGMENTS_EXTRA, taskDesc.bufferSegments);
    PhoneUtils.getGlobalContext().startService(videoIntent);


    IntentFilter filter = new IntentFilter();
    filter.addAction(UpdateIntent.VIDEO_MEASUREMENT_ACTION + this.startTimeFilter);
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
            Logger.d("Goodput Valuess: " + goodputValues);
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
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BUFFER_TIMESTAMP)) {
            String[] bufferTimestampArray = intent.getStringArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BUFFER_TIMESTAMP);
            bufferLoadTimestamps = new ArrayList<String>(Arrays.asList(bufferTimestampArray));
            Logger.d("Buffer Timestamps: " + bufferLoadTimestamps);
          }
          if (intent.hasExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BUFFER_LOAD)) {
            long[] bufferLoadArray = intent.getLongArrayExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BUFFER_LOAD);
            for (long bufferLoad : bufferLoadArray) {
              bufferLoadValues.add(bufferLoad);
            }
            Logger.d("Buffer Load Values: " + bufferLoadValues);
          }
          isResultReceived = true;
        }
    };
    PhoneUtils.getGlobalContext().registerReceiver(broadcastReceiver, filter);


    int timeElapsed = 0;
    for(timeElapsed = 0; timeElapsed< 60 * 5;timeElapsed++){
        if(isDone()){
            break;
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    if (!isDone()) {
      PhoneUtils.getGlobalContext().stopService(videoIntent);
      try {
        Thread.sleep(2000);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    Logger.e("Time elapsed: " + timeElapsed);
    Logger.e("Video QoE: result ready? " + this.isResultReceived);
    PhoneUtils.getGlobalContext().unregisterReceiver(broadcastReceiver);

    if (process != null) {
      Logger.e("pcap data captured!");
      process.destroy();
    }
    
    if(this.isResultReceived){
        Logger.i("Video QoE: Successfully measured QoE data");
        PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
        MeasurementResult result = new MeasurementResult(
                phoneUtils.getDeviceInfo().deviceId,
                phoneUtils.getDeviceProperty(this.getKey()),
                VideoQoETask.TYPE, System.currentTimeMillis() * 1000,
                TaskProgress.COMPLETED, this.measurementDesc);
//        result.addResult(UpdateIntent.VIDEO_TASK_PAYLOAD_IS_SUCCEED, isSucceed);
        result.addResult("time_elapsed", timeElapsed);
        result.addResult("video_num_frame_dropped", ((double)this.numFrameDropped) / timeElapsed);
        result.addResult("video_initial_loading_time", this.initialLoadingTime);
        double rebufferTime = sum(this.rebufferTimes);
        result.addResult("video_rebuffer_times", rebufferTime / (timeElapsed + rebufferTime));
        result.addResult("video_rebuffer_times_detail", getDelimedStr(this.rebufferTimes, ","));
        
        result.addResult("video_goodput_times", getDelimedStr(this.goodputTimestamps, ","));
        result.addResult("video_goodput_values", getDelimedStr(this.goodputValues, ","));
        result.addResult("video_bitrate_times", getDelimedStr(this.bitrateTimestamps, ","));
        result.addResult("video_bitrate_values", getDelimedStr(this.bitrateValues, ","));
        QoEMetrics metrics = calcAvgBitrate(this.bitrateTimestamps, this.bitrateValues);
        result.addResult("video_average_bitrate", metrics.averageBitrate);
        result.addResult("video_num_bitrate_change", metrics.numBitrateChange);
        result.addResult("video_bufferload_times", getDelimedStr(this.bufferLoadTimestamps, ","));
        result.addResult("video_bufferload_values", getDelimedStr(this.bufferLoadValues, ","));
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

    if (!isDone()) {
      PhoneUtils.getGlobalContext().stopService(new Intent(PhoneUtils.getGlobalContext(), VideoPlayerService.class));
    }
    return mrArray;
  }
  
  private double sum(ArrayList<Double> rebufferTimes2) {
    double result = 0.0;
    for (double d : rebufferTimes2) {
      result += d;
    }
    return result;
  }

  private String getDelimedStr(List<?> list, String delim) {
    StringBuilder sb = new StringBuilder();
    for (Object obj : list) {
      if (sb.length() > 0) sb.append(delim);
      sb.append(obj);
    }
    return sb.toString();
  }

  class QoEMetrics {
    public double averageBitrate;
    public int numBitrateChange;
    public QoEMetrics() {
      this(-1.0, 0);
    }
    public QoEMetrics(double averageBitrate, int numBitrateChange) {
      this.averageBitrate = averageBitrate;
      this.numBitrateChange = numBitrateChange;
    }
  }
  
  private QoEMetrics calcAvgBitrate(ArrayList<String> timestamp, ArrayList<Integer> bitrate) {
    if (timestamp.size() != bitrate.size()) {
      Logger.e("timestamp = " + timestamp.size() + ", bitrate = " + bitrate.size());
      return new QoEMetrics();
    }
    int length = timestamp.size();
    String previousTimestamp = null;
    double dataAmount = 0.0;
    double timeAmount = 0.0;
    int numBitrateChange = 0;
    for (int i = 0; i < length; i++) {
      String currentTimestamp = timestamp.get(i);
      int currentBitrate = bitrate.get(i);
      if (currentBitrate != 0) {
        if (!previousTimestamp.equals(currentTimestamp)) {
          double time = Double.parseDouble(currentTimestamp)
              - Double.parseDouble(previousTimestamp);
          double data = currentBitrate * time;
          Logger.i("Time: " + time + ", Data: " + data);
          dataAmount += data;
          timeAmount += time;
          numBitrateChange++;
        }
      }
      previousTimestamp = currentTimestamp;
    }
    if (timeAmount > 1e-6) {
      double avgBitrate = dataAmount / timeAmount;
      Logger.e("Average bitrate: " + avgBitrate);
      return new QoEMetrics(avgBitrate, numBitrateChange);
    }
    else {
      Logger.e("Time is 0");
      return new QoEMetrics();
    }
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
