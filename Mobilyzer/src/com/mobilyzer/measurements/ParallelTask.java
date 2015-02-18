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

import android.os.Parcel;
import android.os.Parcelable;

import com.mobilyzer.Config;
import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;


/**
 * 
 * @author Ashkan Nikravesh (ashnik@umich.edu)
 * 
 * Parallel Task is a measurement task that can execute more than one measurement task 
 * in parallel using a thread pool.
 */
public class ParallelTask extends MeasurementTask{

  private long duration;
  private List<MeasurementTask> tasks;

  private ExecutorService executor;

  // Type name for internal use
  public static final String TYPE = "parallel";
  // Human readable name for the task
  public static final String DESCRIPTOR = "parallel";
  
  private long dataConsumed;


  public static class ParallelDesc extends MeasurementDesc {     

    public ParallelDesc(String key, Date startTime,
        Date endTime, double intervalSec, long count, long priority,
        int contextIntervalSec, Map<String, String> params)
            throws InvalidParameterException {
      super(ParallelTask.TYPE, key, startTime, endTime, intervalSec, count,
        priority, contextIntervalSec, params);  
      //      initializeParams(params);

    }

    @Override
    protected void initializeParams(Map<String, String> params) {
    }

    @Override
    public String getType() {
      return ParallelTask.TYPE;
    }   
    
    protected ParallelDesc(Parcel in) {
      super(in);      
    }

    public static final Parcelable.Creator<ParallelDesc> CREATOR =
        new Parcelable.Creator<ParallelDesc>() {
      public ParallelDesc createFromParcel(Parcel in) {
        return new ParallelDesc(in);
      }

      public ParallelDesc[] newArray(int size) {
        return new ParallelDesc[size];
      }
    };
  }

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
    return ParallelDesc.class;
  }



  public ParallelTask(MeasurementDesc desc,  ArrayList<MeasurementTask> tasks) {
    super(new ParallelDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec,
      desc.count, desc.priority, desc.contextIntervalSec, desc.parameters));
    this.tasks=(List<MeasurementTask>) tasks.clone();
    long maxduration=0;
    for(MeasurementTask mt: tasks){
      if(mt.getDuration()>maxduration){
        maxduration=mt.getDuration();
      }
    }
    this.duration=maxduration;
    this.dataConsumed=0;

  }
  
  protected ParallelTask(Parcel in) {
    super(in);
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    // we cannot directly cast Parcelable[] to MeasurementTask[]. Cast them one-by-one
    Parcelable[] tempTasks = in.readParcelableArray(loader);
    tasks = new ArrayList<MeasurementTask>();
    long maxDuration = 0;
    for ( Parcelable pTask : tempTasks ) {
      MeasurementTask mt = (MeasurementTask) pTask;
      tasks.add(mt);
      if (mt.getDuration() > maxDuration) {
        maxDuration = mt.getDuration();
      }
    }
    this.duration = maxDuration;
    this. dataConsumed = 0;
  }

  public static final Parcelable.Creator<ParallelTask> CREATOR
      = new Parcelable.Creator<ParallelTask>() {
    public ParallelTask createFromParcel(Parcel in) {
      return new ParallelTask(in);
    }

    public ParallelTask[] newArray(int size) {
      return new ParallelTask[size];
    }
  };

  @Override
  public int describeContents() {
    return super.describeContents();
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    super.writeToParcel(dest, flags);
    dest.writeParcelableArray(tasks.toArray(new MeasurementTask[tasks.size()]), flags);
  }
  
  @Override
  public String getDescriptor() {
    return DESCRIPTOR;
  }

  @Override
  public MeasurementResult[] call() throws MeasurementError {
    long timeout=duration;
    executor=Executors.newFixedThreadPool(this.tasks.size());

    if(timeout==0){
      timeout=Config.DEFAULT_PARALLEL_TASK_DURATION;
    }else{
      //this is the longest time a task can run before it is forcibly killed
      timeout*=2;
    }
    ArrayList<MeasurementResult> allResults=new ArrayList<MeasurementResult>();
    List<Future<MeasurementResult[]>> futures;
    try {
      futures=executor.invokeAll(this.tasks,timeout,TimeUnit.MILLISECONDS);
      for(Future<MeasurementResult[]> f: futures){
        MeasurementResult[] r=f.get();
        for(int i=0;i<r.length;i++){
          allResults.add(r[i]);
        }
      }

    } catch (InterruptedException e) {
      Logger.e("Parallel task " + this.getTaskId()+" got interrupted");
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
    return ParallelTask.TYPE;
  }

  @Override
  public MeasurementTask clone() {
    MeasurementDesc desc = this.measurementDesc;
    ParallelDesc newDesc = new ParallelDesc(desc.key, desc.startTime, desc.endTime, 
      desc.intervalSec, desc.count, desc.priority, desc.contextIntervalSec, desc.parameters);
    ArrayList<MeasurementTask> newTaskList=new ArrayList<MeasurementTask>();
    for(MeasurementTask mt: tasks){
      newTaskList.add(mt.clone());
    }
    return new ParallelTask(newDesc,newTaskList);
  }

  @Override
  public boolean stop() {
    return false;
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
    return dataConsumed;
  }
}
