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
import java.lang.reflect.Type;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.myjson.Gson;
import com.google.myjson.reflect.TypeToken;
import com.mobilyzer.Config;
import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.measurements.TracerouteTask.TracerouteDesc;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;

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
	  
	public ArrayList<MeasurementTask> subTasks;
    public SequentialDesc(String key, Date startTime,
        Date endTime, double intervalSec, long count, long priority,
        int contextIntervalSec, Map<String, String> params)
            throws InvalidParameterException {
      super(SequentialTask.TYPE, key, startTime, endTime, intervalSec, count,
        priority, contextIntervalSec, params);
            initializeParams(params);
            if (subTasks==null || subTasks.size()==0){
            	throw new InvalidParameterException("Sequential task must contain at-least one sub task");
            }

    }

    @Override
    protected void initializeParams(Map<String, String> params) {
        if (params == null) {
            return;
        }
        subTasks=new ArrayList<MeasurementTask>();
        String tasksJsonList = null;
        if ((tasksJsonList = params.get("tasks")) != null && tasksJsonList.length() > 0){
        	try {
				JSONArray jsonArray = new JSONArray(tasksJsonList);
				for (int i = 0; i < jsonArray.length(); i++) {
					JSONObject jsonObj = jsonArray.optJSONObject(i);
					if (jsonObj != null &&  MeasurementTask.getMeasurementTypes().contains(jsonObj.get("type"))) {
						MeasurementTask subTask = MeasurementJsonConvertor.makeMeasurementTaskFromJson(jsonObj);
						subTasks.add(subTask);
					}
				}
				
				
			} catch (JSONException e) {
				e.printStackTrace();
			}
        }
    }

    @Override
    public String getType() {
      return SequentialTask.TYPE;
    }  
    
    @Override
    public int describeContents() {
      return 0;
    }
    
    protected SequentialDesc(Parcel in) {
      super(in);
//      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      subTasks = in.readArrayList(MeasurementTask.class.getClassLoader());
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
    
    @Override
    public void writeToParcel(Parcel dest, int flags) {
      super.writeToParcel(dest, flags);
      dest.writeList(subTasks);
    }
    
  }

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
    return SequentialDesc.class;
  }

  public SequentialTask(MeasurementDesc desc) {
	    super(new SequentialDesc(desc.key, desc.startTime, desc.endTime,
	      desc.intervalSec, desc.count, desc.priority, desc.contextIntervalSec,
	      desc.parameters));
	    //we are using executor because it has builtin support for execution timeouts.
	    executor=Executors.newSingleThreadExecutor();
	    long totalduration=0;
	    this.tasks=(List<MeasurementTask>)(((SequentialDesc)getDescription()).subTasks).clone();
	    for(MeasurementTask mt: tasks){
	      totalduration+=mt.getDuration();
	    }
	    this.duration=totalduration;
	    this.stopFlag=false;
	    this.currentTask=null;
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
//    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    // we cannot directly cast Parcelable[] to MeasurementTask[]. Cast them one-by-one
    Parcelable[] tempTasks = in.readParcelableArray(MeasurementTask.class.getClassLoader());
    executor=Executors.newSingleThreadExecutor();
    tasks = new ArrayList<MeasurementTask>();
    long totalduration=0;
    for ( Parcelable pTask : tempTasks ) {
      MeasurementTask mt = (MeasurementTask) pTask;
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
    dest.writeParcelableArray(tasks.toArray(new MeasurementTask[tasks.size()]), flags);
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
/*        
        if(allResults.size()>0 && mt.getType().equals(TracerouteTask.TYPE) && ((TracerouteDesc)(mt.getDescription())).preCondition!=null){
        	String precond=((TracerouteDesc)(mt.getDescription())).preCondition;
        	Logger.d(">>>>>>>>>>>>>>> "+precond);
        	PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
        	String network_type=phoneUtils.getNetwork().replace(" ", "").toLowerCase();
        	
        	float rttThreshold=-1;
        	Type listType = new TypeToken<ArrayList<ArrayList<String>>>() {}.getType();
        	List<ArrayList<String>> precondList = new Gson().fromJson(precond, listType);
        	
        	for(ArrayList<String> condition: precondList){
        		if(condition.size()==3){//WiFi precondition
        			String cond_nettype=condition.get(0);
        			String cond_ssid=condition.get(1);
        			float cond_rtt=Float.parseFloat(condition.get(2));
        			if(cond_nettype.equals(network_type) && cond_ssid.equals(phoneUtils.getWifiSSID())
        					&& cond_rtt>0){
        				Logger.i("Precond matched: "+cond_nettype+" "+cond_ssid);
        				rttThreshold=cond_rtt;
        				break;
        			}
        			
        			
        		}else if(condition.size()==2){//cellular precondition
        			String cond_nettype=condition.get(0);
        			float cond_rtt=Float.parseFloat(condition.get(1));
        			if(cond_nettype.equals(network_type) && cond_rtt>0){
        				Logger.i("Precond matched: "+cond_nettype);
        				rttThreshold=cond_rtt;
        				break;
        			}
        		}
        	}
        	
        	float pingRtt=-1;
        	if(allResults.get(allResults.size()-1).getValues().get("min_rtt_ms")!=null){
        		pingRtt=Float.parseFloat(allResults.get(allResults.size()-1).getValues().get("min_rtt_ms"));
        	}
        	
        	if(rttThreshold==-1 || pingRtt==-1 || rttThreshold>pingRtt){
        		Logger.i("Traceroute diag task is not going to run because: "+rttThreshold+" "+pingRtt);
        		break;
        	}else if(rttThreshold!=-1 && pingRtt!=-1){
        		allResults.get(allResults.size()-1).getValues().put("rtt_thresh", rttThreshold+"");
        	}
        }
        
*/        
        Logger.i("Sub task "+mt.getType()+" is going to run");
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
