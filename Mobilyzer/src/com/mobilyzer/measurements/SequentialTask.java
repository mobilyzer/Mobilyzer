/*
 * Copyright 2013 RobustNet Lab, University of Michigan. All Rights Reserved.
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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mobilyzer.Config;
import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 
 * @author Ashkan Nikravesh (ashnik@umich.edu)
 * 
 * Sequential Task is a measurement task that can execute more than one measurement task 
 * in series.
 */
public class SequentialTask extends MeasurementTask{
  private List<MeasurementTask> tasks;

  private ExecutorService executor;

  // Type name for internal use
  public static final String TYPE = "sequential";
  // Human readable name for the task
  public static final String DESCRIPTOR = "sequential";
  private volatile boolean stopFlag;
  private long duration;
  private volatile MeasurementTask currentTask;

  public static class SequentialDesc extends MeasurementDesc {     

    public SequentialDesc(String key, Date startTime,
        Date endTime, double intervalSec, long count, long priority,
        int contextIntervalSec, Map<String, String> params)
            throws InvalidParameterException {
      super(SequentialTask.TYPE, key, startTime, endTime, intervalSec, count,
        priority, contextIntervalSec, params);  
      //      initializeParams(params);

    }

    @Override
    protected void initializeParams(Map<String, String> params) {
    }

    @Override
    public String getType() {
      return SequentialTask.TYPE;
    }  
    
    protected SequentialDesc(Parcel in) {
      super(in);
    }

    public static final Parcelable.Creator<SequentialDesc> CREATOR
    = new Parcelable.Creator<SequentialDesc>() {
      public SequentialDesc createFromParcel(Parcel in) {
        return new SequentialDesc(in);
      }

      public SequentialDesc[] newArray(int size) {
        return new SequentialDesc[size];
      }
    };
  }

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
    return SequentialDesc.class;
  }



  public SequentialTask(MeasurementDesc desc, ArrayList<MeasurementTask> tasks) {
    super(new SequentialDesc(desc.key, desc.startTime, desc.endTime,
      desc.intervalSec, desc.count, desc.priority, desc.contextIntervalSec,
      desc.parameters));
    this.tasks=(List<MeasurementTask>) tasks.clone();
    //we are using executor because it has builtin support for execution timeouts.
    executor=Executors.newSingleThreadExecutor();
    long totalduration=0;
    for(MeasurementTask mt: tasks){
      totalduration+=mt.getDuration();
    }
    this.duration=totalduration;
    this.stopFlag=false;
    this.currentTask=null;
  }
  
  protected SequentialTask(Parcel in) {
    super(in);
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    MeasurementTask[] tempTasks = (MeasurementTask[])in.readParcelableArray(loader);
    executor=Executors.newSingleThreadExecutor();
    tasks = new ArrayList<MeasurementTask>();
    long totalduration=0;
    for ( MeasurementTask mt : tempTasks ) {
      tasks.add(mt);
      totalduration+=mt.getDuration();
    }
    this.duration = totalduration;
    this.stopFlag=false;
    this.currentTask=null;
  }

  public static final Parcelable.Creator<SequentialTask> CREATOR
  = new Parcelable.Creator<SequentialTask>() {
    public SequentialTask createFromParcel(Parcel in) {
      return new SequentialTask(in);
    }

    public SequentialTask[] newArray(int size) {
      return new SequentialTask[size];
    }
  };

  @Override
  public int describeContents() {
    return super.describeContents();
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    super.writeToParcel(dest, flags);
    dest.writeParcelableArray((MeasurementTask[])tasks.toArray(), flags);
  }
  

  @Override
  public String getDescriptor() {
    return DESCRIPTOR;
  }

  @Override
  public MeasurementResult[] call() throws MeasurementError {

    ArrayList<MeasurementResult> allResults=new ArrayList<MeasurementResult>();
    try {
      //      futures=executor.invokeAll(this.tasks,timeout,TimeUnit.MILLISECONDS);
      for(MeasurementTask mt: tasks){
        if(stopFlag){
          throw new MeasurementError("Cancelled");
        }
        Future<MeasurementResult[]> f=executor.submit(mt);
        currentTask=mt;
        MeasurementResult[] results;
        //specifying timeout for each task based on its duration
        try {
          results = f.get( mt.getDuration()==0 ?
              Config.DEFAULT_TASK_DURATION_TIMEOUT * 2 : mt.getDuration() * 2,
              TimeUnit.MILLISECONDS);
          for(int i=0;i<results.length;i++){
            allResults.add(results[i]);
          }
        } catch (TimeoutException e) {
          if(mt.stop()){
            f.cancel(true);
          }
        }
      }

    } catch (InterruptedException e) {
      Logger.e("Sequential task " + this.getTaskId() + " got interrupted!");
    }catch (ExecutionException e) {
      throw new MeasurementError("Execution error: " + e.getCause());
    }
    finally{
      executor.shutdown();
    }
    return (MeasurementResult[])allResults.toArray(
      new MeasurementResult[allResults.size()]);
  }

  @Override
  public String getType() {
    return SequentialTask.TYPE;
  }

  @Override
  public MeasurementTask clone() {
    MeasurementDesc desc = this.measurementDesc;
    SequentialDesc newDesc = new SequentialDesc(desc.key, desc.startTime, desc.endTime, 
      desc.intervalSec, desc.count, desc.priority, desc.contextIntervalSec, desc.parameters);
    ArrayList<MeasurementTask> newTaskList=new ArrayList<MeasurementTask>();
    for(MeasurementTask mt: tasks){
      newTaskList.add(mt.clone());
    }
    return new SequentialTask(newDesc,newTaskList);
  }

  @Override
  public boolean stop() {
    if(currentTask.stop()){
      stopFlag=true;
      executor.shutdown();
      return true;
    }else{
      return false;
    }
    
  }

  @Override
  public long getDuration() {
    return duration;
  }

  @Override
  public void setDuration(long newDuration) {
    if(newDuration<0){
      this.duration=0;
    }else{
      this.duration=newDuration;
    }
  }

  public MeasurementTask[] getTasks() {
    return tasks.toArray(new MeasurementTask[tasks.size()]);
  }
  
  //TODO
  @Override
  public long getDataConsumed() {
    return 0;
  }

}
