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
package com.mobilyzer.util.video;

import com.mobilyzer.UpdateIntent;
import com.mobilyzer.util.video.player.DemoPlayer;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer.AudioTrackInitializationException;
import com.google.android.exoplayer.MediaCodecTrackRenderer.DecoderInitializationException;
import com.google.android.exoplayer.util.VerboseLogUtil;

import android.content.Intent;
import android.media.MediaCodec.CryptoException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Logs player events using {@link Log}.
 */
public class EventLogger implements DemoPlayer.Listener, DemoPlayer.InfoListener,
    DemoPlayer.InternalErrorListener {
  
  public static HashMap<Pair<Integer, Integer>, Integer> Resolution2Bitrate = new HashMap<Pair<Integer, Integer>, Integer>();
  public static HashMap<String, Integer> Id2Bitrate = new HashMap<String, Integer>();
  
  private ArrayList<String> dropFrameTime;
  private ArrayList<Pair<String, Integer>> videoBitrateVarience;
  private ArrayList<Pair<String, Integer>> audioBitrateVarience;
  private double initialLoadingTime;
  private ArrayList<Double> rebufferTime;
  private ArrayList<Pair<String, Double>> videoGoodput;
  private ArrayList<Long> videoGoodputEstimate;
  private ArrayList<Pair<String, Double>> audioGoodput;
  private int previousVideoBitrate;
  private int previousAudioBitrate;
  private long switchToSteadyStateTime;
  private long totalBytesDownloaded;
  
  private double initialLoadingTime_s;
  private int bufferCounter = 0;
  private double bufferTime_s;
  
  private static final String TAG = "EventLogger";
  private static final NumberFormat TIME_FORMAT;
  static {
    TIME_FORMAT = NumberFormat.getInstance(Locale.US);
    TIME_FORMAT.setMinimumFractionDigits(4);
    TIME_FORMAT.setMaximumFractionDigits(4);
  }

  private long sessionStartTimeMs;
  private long[] loadStartTimeMs;

  public EventLogger() {
    loadStartTimeMs = new long[DemoPlayer.RENDERER_COUNT];
    
    this.dropFrameTime = new ArrayList<String>();
    this.videoBitrateVarience = new ArrayList<Pair<String, Integer>>();
    this.audioBitrateVarience = new ArrayList<Pair<String, Integer>>();
    this.rebufferTime = new ArrayList<Double>();
    this.videoGoodput = new ArrayList<Pair<String, Double>>();
    this.videoGoodputEstimate = new ArrayList<Long>();
    this.audioGoodput = new ArrayList<Pair<String, Double>>();
    this.videoBitrateVarience.add(Pair.create("0.00", 0));
    this.previousVideoBitrate = 0;
    this.audioBitrateVarience.add(Pair.create("0.00", 0));
    this.previousAudioBitrate = 0;
    this.switchToSteadyStateTime = -1;
    this.totalBytesDownloaded = 0;
  }
  
  public void startSession() {
    sessionStartTimeMs = SystemClock.elapsedRealtime();
    Log.d(TAG, "start [0]");
  }

  public Intent endSession() {
    Log.d(TAG, "end [" + getSessionTimeString() + "]");
    this.videoBitrateVarience.add(Pair.create(getSessionTimeString(), this.previousVideoBitrate));
    this.audioBitrateVarience.add(Pair.create(getSessionTimeString(), this.previousAudioBitrate));
    return printStatInfo();
  }

  // DemoPlayer.Listener

  @Override
  public void onStateChanged(boolean playWhenReady, int state) {
    Log.d(TAG, "state [" + getSessionTimeString() + ", " + playWhenReady + ", " +
        getStateString(state) + "]");
//    DecimalFormat df = new DecimalFormat("#.##");
    switch(state) {
      case ExoPlayer.STATE_PREPARING:
//        this.bitrateVarience.add(Pair.create("0.00", currentBitrate));
        this.initialLoadingTime_s = Double.parseDouble(getSessionTimeString());
        break;

      case ExoPlayer.STATE_BUFFERING:
        bufferCounter++;
        bufferTime_s = Double.parseDouble(getSessionTimeString());
        break;
      case ExoPlayer.STATE_READY:
        if (bufferCounter == 1) {
          this.initialLoadingTime = Double.parseDouble(String.format("%.2f", Double.parseDouble(getSessionTimeString()) - this.initialLoadingTime_s));
        }
        else {
          this.rebufferTime.add(Double.parseDouble(String.format("%.2f", Double.parseDouble(getSessionTimeString()) - bufferTime_s)));
        }
        break;
      case ExoPlayer.STATE_ENDED:
        this.videoBitrateVarience.add(Pair.create(getSessionTimeString(), this.previousVideoBitrate));
        this.audioBitrateVarience.add(Pair.create(getSessionTimeString(), this.previousAudioBitrate));
//        printStatInfo();
        break;
    }
  }

  private Intent printStatInfo() {
//    Log.e("", "DropFrame #: " + this.dropFrameTime.size());
//    Log.e("", "Bitrate: " + displayBitrate(this.bitrateVarience));
//    Log.e("", "Initial Loading Time: " + this.initialLoadingTime);
//    Log.e("", "Rebuffering: " + this.rebufferTime);
    Log.e("ashkan_video", "" + this.dropFrameTime.size());
    Log.e("ashkan_video", "" + displayGoodPut(this.videoGoodput));
    Log.e("ashkan_video", "" + displayBitrate(this.videoBitrateVarience));
    Log.e("ashkan_video", "" + this.initialLoadingTime);
    Log.e("ashkan_video", "" + this.rebufferTime);
    Log.e("ashkan_video", "" + displayGoodPut(this.audioGoodput));
    Log.e("ashkan_video", "" + displayBitrate(this.audioBitrateVarience));
    Log.e("ashkan_video", "bba switch time " +this.switchToSteadyStateTime );
    Log.e("ashkan_video", "total bytes downloaded " +this.totalBytesDownloaded );
    
    Intent videoQoEResult = new Intent();
    videoQoEResult.setAction(UpdateIntent.VIDEO_MEASUREMENT_ACTION);
    videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_NUM_FRAME_DROPPED, this.dropFrameTime.size());
    videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_INITIAL_LOADING_TIME, this.initialLoadingTime);
    if(this.switchToSteadyStateTime!=-1){
      videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BBA_SWITCH_TIME, this.switchToSteadyStateTime);
    }
    if(this.totalBytesDownloaded!=0){
      videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BYTE_USED, this.totalBytesDownloaded);
    }
    double[] rebufferTimeArray = new double[this.rebufferTime.size()];
    int counter = 0;
    for (Double rebufferSample : this.rebufferTime) {
      rebufferTimeArray[counter] = rebufferSample;
      counter++;
    }
    videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_REBUFFER_TIME, rebufferTimeArray);
    String[] goodputTimestamp = new String[this.videoGoodput.size()];
    double[] goodputValue = new double[this.videoGoodput.size()];
    long[] goodputEstimate = new long[this.videoGoodputEstimate.size()]; 
    counter = 0;
    for (Pair<String, Double> goodputSample : this.videoGoodput) {
      goodputTimestamp[counter] = goodputSample.first;
      goodputValue[counter] = goodputSample.second;
      counter++;
    }
    
    counter=0;
    for (Long estimate : this.videoGoodputEstimate) {
      goodputEstimate[counter] = estimate;
      counter++;
    }
    
    videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_TIMESTAMP, goodputTimestamp);
    videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_VALUE, goodputValue);
    videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_GOODPUT_ESTIMATE_VALUE, goodputEstimate);
    
    String[] bitrateTimestamp = new String[this.videoBitrateVarience.size()];
    int[] bitrateValue = new int[this.videoBitrateVarience.size()];
    counter=0;
    for (Pair<String, Integer> bitrateSample : this.videoBitrateVarience) {
      bitrateTimestamp[counter] = bitrateSample.first;
      bitrateValue[counter] = bitrateSample.second;
      counter++;
    }
    videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_TIMESTAMP, bitrateTimestamp);
    videoQoEResult.putExtra(UpdateIntent.VIDEO_TASK_PAYLOAD_BITRATE_VALUE, bitrateValue);
    
    return videoQoEResult;
  }

  
  private String displayBitrate(ArrayList<Pair<String, Integer>> bitrateVarience2) {
    StringBuilder sBuilder = new StringBuilder();
    for (Pair<String, Integer> pBitrate : bitrateVarience2) {
      sBuilder.append(pBitrate.first + " " + pBitrate.second + "\n");
    }
    return sBuilder.toString();
  }

  private String displayGoodPut(ArrayList<Pair<String, Double>> goodput) {
    StringBuilder sBuilder = new StringBuilder();
    for (Pair<String, Double> pBitrate : goodput) {
      sBuilder.append(pBitrate.first + " " + String.format("%.2f", pBitrate.second) + "\n");
    }
    return sBuilder.toString();
  }
  
  // Error finished, return partial results
  @Override
  public void onError(Exception e) {
    Log.e(TAG, "playerFailed [" + getSessionTimeString() + "]", e);
//    this.videoBitrateVarience.add(Pair.create(getSessionTimeString(), this.previousVideoBitrate));
//    this.audioBitrateVarience.add(Pair.create(getSessionTimeString(), this.previousAudioBitrate));
//    printStatInfo();
  }

  @Override
  public void onVideoSizeChanged(int width, int height) {
//    currentBitrate = Resolution2Bitrate.get(Pair.create(width, height));
    Log.d(TAG, "videoSizeChanged [" + getSessionTimeString() + ", " + width + ", " + height + "]");
//    this.bitrateVarience.add(Pair.create(getSessionTimeString(), currentBitrate));
  }

  // DemoPlayer.InfoListener

  @Override
//  public void onBandwidthSample(int elapsedMs, long bytes, long bitrateEstimate) {
  public void onBandwidthSample(String label, long startTime, long endTime, int elapsedMs, long bytes, long bitrateEstimate) {
    Log.d(TAG, label + " bandwidth [" + startTime + ", " + endTime + ", " + getSessionTimeString() + ", " + bytes +
        ", " + getTimeString(elapsedMs) + ", " + bitrateEstimate + ", " + bytes * 8 / elapsedMs + "kbps]");
    if (label.equals("video")) {
      this.videoGoodput.add(Pair.create(getSessionTimeString(), (double)bytes * 8000 / elapsedMs));
      this.videoGoodputEstimate.add(bitrateEstimate);
    }
    else if (label.equals("audio")) {
      this.audioGoodput.add(Pair.create(getSessionTimeString(), (double)bytes * 8000 / elapsedMs));
    }
//    this.goodput.add(Pair.create(getSessionTimeString(), (double)bytes * 8000 / elapsedMs));
  }

  @Override
  public void onDroppedFrames(int count, long elapsed) {
    Log.d(TAG, "droppedFrames [" + getSessionTimeString() + ", " + count + "]");
    this.dropFrameTime.add(getSessionTimeString());
  }

  @Override
  public void onLoadStarted(int sourceId, String formatId, int trigger, boolean isInitialization,
      int mediaStartTimeMs, int mediaEndTimeMs, long length) {
    loadStartTimeMs[sourceId] = SystemClock.elapsedRealtime();
    if (VerboseLogUtil.isTagEnabled(TAG)) {
      Log.v(TAG, "loadStart [" + getSessionTimeString() + ", " + sourceId
          + ", " + mediaStartTimeMs + ", " + mediaEndTimeMs + "]");
    }
  }

  @Override
  public void onLoadCompleted(int sourceId, long bytesLoaded) {
    if (VerboseLogUtil.isTagEnabled(TAG)) {
      long downloadTime = SystemClock.elapsedRealtime() - loadStartTimeMs[sourceId];
      Log.v(TAG, "loadEnd [" + getSessionTimeString() + ", " + sourceId + ", " +
          downloadTime + "]");
    }
  }

  @Override
  public void onVideoFormatEnabled(String formatId, int trigger, int mediaTimeMs) {
    int currentBitrate = Id2Bitrate.get(formatId);
    Log.d(TAG, "videoFormat [" + getSessionTimeString() + ", " + formatId + ", " +
        Integer.toString(trigger) + ", " + currentBitrate / 1000 + "kbps" + "]");
    this.videoBitrateVarience.add(Pair.create(getSessionTimeString(), this.previousVideoBitrate));
    this.videoBitrateVarience.add(Pair.create(getSessionTimeString(), currentBitrate));
    this.previousVideoBitrate = currentBitrate;
  }

  @Override
  public void onAudioFormatEnabled(String formatId, int trigger, int mediaTimeMs) {
    int currentBitrate = Id2Bitrate.get(formatId);
    Log.d(TAG, "audioFormat [" + getSessionTimeString() + ", " + formatId + ", " +
        Integer.toString(trigger) + ", " + currentBitrate / 1000 + "kbps" + "]");
    this.audioBitrateVarience.add(Pair.create(getSessionTimeString(), this.previousAudioBitrate));
    this.audioBitrateVarience.add(Pair.create(getSessionTimeString(), currentBitrate));
    this.previousAudioBitrate = currentBitrate;
  }

  // DemoPlayer.InternalErrorListener

  @Override
  public void onUpstreamError(int sourceId, IOException e) {
    printInternalError("upstreamError", e);
  }

  @Override
  public void onConsumptionError(int sourceId, IOException e) {
    printInternalError("consumptionError", e);
  }

  @Override
  public void onRendererInitializationError(Exception e) {
    printInternalError("rendererInitError", e);
  }

  @Override
  public void onDrmSessionManagerError(Exception e) {
    printInternalError("drmSessionManagerError", e);
  }

  @Override
  public void onDecoderInitializationError(DecoderInitializationException e) {
    printInternalError("decoderInitializationError", e);
  }

  @Override
  public void onAudioTrackInitializationError(AudioTrackInitializationException e) {
    printInternalError("audioTrackInitializationError", e);
  }

  @Override
  public void onCryptoError(CryptoException e) {
    printInternalError("cryptoError", e);
  }

  private void printInternalError(String type, Exception e) {
    Log.e(TAG, "internalError [" + getSessionTimeString() + ", " + type + "]", e);
  }

  private String getStateString(int state) {
    switch (state) {
      case ExoPlayer.STATE_BUFFERING:
        return "B";
      case ExoPlayer.STATE_ENDED:
        return "E";
      case ExoPlayer.STATE_IDLE:
        return "I";
      case ExoPlayer.STATE_PREPARING:
        return "P";
      case ExoPlayer.STATE_READY:
        return "R";
      default:
        return "?";
    }
  }

  private String getSessionTimeString() {
    return getTimeString(SystemClock.elapsedRealtime() - sessionStartTimeMs);
//    return getTimeString(System.currentTimeMillis());
  }

  private String getTimeString(long timeMs) {
    return TIME_FORMAT.format((timeMs) / 1000f);
  }

  @Override
  public void onSwitchToSteadyState(long elapsedMs) {
    this.switchToSteadyStateTime=elapsedMs;
  }

  @Override
  public void onAllChunksDownloaded(long totalBytes) {
    this.totalBytesDownloaded=totalBytes;
  }

}
