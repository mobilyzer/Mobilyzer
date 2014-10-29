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
package com.mobilyzer;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Calendar;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;

import org.json.JSONException;
import org.json.JSONObject;

import com.mobilyzer.Config;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.UpdateIntent;
import com.mobilyzer.gcm.GCMManager;
import com.mobilyzer.measurements.RRCTask;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.PhoneUtils;
import com.mobilyzer.util.MeasurementJsonConvertor;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.Messenger;
import android.os.Parcelable;
import android.preference.PreferenceManager;

/**
 * 
 * @author Ashkan Nikravesh (ashnik@umich.edu) + others The single scheduler thread that monitors
 *         the task queue, runs tasks at their specified times, and finally retrieves and reports
 *         results once they finish. The API can call the public methods or this Service. This
 *         service works as a remote service and always, we will have a single instance of scheduler
 *         running on a device, although we can have more than one app that binds to this service
 *         and communicate with that.
 */
public class MeasurementScheduler extends Service {

  public enum TaskStatus {
    FINISHED, PAUSED, CANCELLED, SCHEDULED, RUNNING, NOTFOUND
  }

  public enum DataUsageProfile {
    PROFILE1, PROFILE2, PROFILE3, PROFILE4, UNLIMITED, NOTASSIGNED
  }

  private ExecutorService measurementExecutor;
  private BroadcastReceiver broadcastReceiver;
  public boolean isSchedulerStarted = false;


  public Checkin checkin;// TODO: should not be public, quick fix to get gcm working
  private long checkinIntervalSec;
  private long checkinRetryIntervalSec;
  private int checkinRetryCnt;
  private CheckinTask checkinTask;
  private Calendar lastCheckinTime;

  private int batteryThreshold;
  // private int dataLimit;//in Byte
  private DataUsageProfile dataUsageProfile;


  private PhoneUtils phoneUtils;

  private PendingIntent measurementIntentSender;
  private PendingIntent checkinIntentSender;
  private PendingIntent checkinRetryIntentSender;

  private volatile HashMap<String, Date> serverTasks;
  // private volatile HashMap <String, MeasurementTask> currentSchedule;

  private AlarmManager alarmManager;
  private ResourceCapManager resourceCapManager;
  private volatile ConcurrentHashMap<String, TaskStatus> tasksStatus;
  // all the tasks are put in to this queue first, where they ordered based on their start time
  private volatile PriorityBlockingQueue<MeasurementTask> mainQueue;
  // ready queue, all the tasks in this queue are ready to be run. They sorted based on
  // (1) priority (2) end time
  private volatile PriorityBlockingQueue<MeasurementTask> waitingTasksQueue;
  private volatile ConcurrentHashMap<MeasurementTask, Future<MeasurementResult[]>> pendingTasks;
  private volatile Date currentTaskStartTime;
  private volatile MeasurementTask currentTask;

  private volatile ConcurrentHashMap<String, String> idToClientKey;

  private Messenger messenger;

  private GCMManager gcmManager;
  
  
  @Override
  public void onCreate() {
    Logger.d("MeasurementScheduler -> onCreate called");
    PhoneUtils.setGlobalContext(this.getApplicationContext());
    
    

    phoneUtils = PhoneUtils.getPhoneUtils();
    phoneUtils.registerSignalStrengthListener();

    this.measurementExecutor = Executors.newSingleThreadExecutor();
    this.mainQueue =
        new PriorityBlockingQueue<MeasurementTask>(Config.MAX_TASK_QUEUE_SIZE, new TaskComparator());
    this.waitingTasksQueue =
        new PriorityBlockingQueue<MeasurementTask>(Config.MAX_TASK_QUEUE_SIZE,
            new WaitingTasksComparator());
    this.pendingTasks = new ConcurrentHashMap<MeasurementTask, Future<MeasurementResult[]>>();
    this.tasksStatus = new ConcurrentHashMap<String, MeasurementScheduler.TaskStatus>();
    this.alarmManager = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);
    this.resourceCapManager = new ResourceCapManager(Config.DEFAULT_BATTERY_THRESH_PRECENT, this);

    this.serverTasks = new HashMap<String, Date>();
    // this.currentSchedule = new HashMap<String, MeasurementTask>();

    this.idToClientKey = new ConcurrentHashMap<String, String>();

    messenger = new Messenger(new APIRequestHandler(this));

    gcmManager = new GCMManager(this.getApplicationContext());
    
    this.setCurrentTask(null);
    this.setCurrentTaskStartTime(null);


    this.checkin = new Checkin(this);
    this.checkinRetryIntervalSec = Config.MIN_CHECKIN_RETRY_INTERVAL_SEC;
    this.checkinRetryCnt = 0;
    this.checkinTask = new CheckinTask();

    this.batteryThreshold = -1;
    this.checkinIntervalSec = -1;
    this.dataUsageProfile = DataUsageProfile.NOTASSIGNED;


//    loadSchedulerState();//TODO(ASHKAN)
    
    

    // Register activity specific BroadcastReceiver here
    IntentFilter filter = new IntentFilter();
    filter.addAction(UpdateIntent.CHECKIN_ACTION);
    filter.addAction(UpdateIntent.CHECKIN_RETRY_ACTION);
    filter.addAction(UpdateIntent.MEASUREMENT_ACTION);
    filter.addAction(UpdateIntent.MEASUREMENT_PROGRESS_UPDATE_ACTION);
    filter.addAction(UpdateIntent.GCM_MEASUREMENT_ACTION);
    filter.addAction(UpdateIntent.PLT_MEASUREMENT_ACTION);

    broadcastReceiver = new BroadcastReceiver() {

      @Override
      public void onReceive(Context context, Intent intent) {
        Logger.d(intent.getAction() + " RECEIVED");
        if (intent.getAction().equals(UpdateIntent.MEASUREMENT_ACTION)) {
          handleMeasurement();
          
        } else if (intent.getAction().equals(UpdateIntent.GCM_MEASUREMENT_ACTION)) {
          try {
            JSONObject json =
                new JSONObject(intent.getExtras().getString(UpdateIntent.MEASUREMENT_TASK_PAYLOAD));
            Logger.d("MeasurementScheduler -> GCMManager: json task Value is " + json);
            if (json != null && MeasurementTask.getMeasurementTypes().contains(json.get("type"))) {

              try {
                MeasurementTask task = MeasurementJsonConvertor.makeMeasurementTaskFromJson(json);
                task.getDescription().priority = MeasurementTask.GCM_PRIORITY;
                task.getDescription().startTime = new Date(System.currentTimeMillis() - 1000);
                task.getDescription().endTime = new Date(System.currentTimeMillis() + (600 * 1000));
                task.generateTaskID();
                task.getDescription().key = Config.SERVER_TASK_CLIENT_KEY;
                submitTask(task);
              } catch (IllegalArgumentException e) {
                Logger.w("MeasurementScheduler -> GCM : Could not create task from JSON: " + e);
              }
            }

          } catch (JSONException e) {
            Logger
                .e("MeasurementSchedule -> GCMManager : Got exception during converting GCM json to MeasurementTask",
                    e);
          }


        } else if (intent.getAction().equals(UpdateIntent.MEASUREMENT_PROGRESS_UPDATE_ACTION)) {
          String taskid = intent.getStringExtra(UpdateIntent.TASKID_PAYLOAD);
          String taskKey = intent.getStringExtra(UpdateIntent.CLIENTKEY_PAYLOAD);
          int priority =
              intent.getIntExtra(UpdateIntent.TASK_PRIORITY_PAYLOAD,
                  MeasurementTask.INVALID_PRIORITY);

          Logger.e(intent.getStringExtra(UpdateIntent.TASK_STATUS_PAYLOAD) + " " + taskid + " "
              + taskKey);
          if (intent.getStringExtra(UpdateIntent.TASK_STATUS_PAYLOAD).equals(Config.TASK_FINISHED)) {
            tasksStatus.put(taskid, TaskStatus.FINISHED);
            Parcelable[] results = intent.getParcelableArrayExtra(UpdateIntent.RESULT_PAYLOAD);
            if (results != null) {
              sendResultToClient(results, priority, taskKey, taskid);

              for (Object obj : results) {
                try {
                  MeasurementResult result = (MeasurementResult) obj;
                  /**
                   * Nullify the additional parameters in MeasurmentDesc, or the results won't be
                   * accepted by GAE server
                   */
                  result.getMeasurementDesc().parameters = null;
                  String jsonResult = MeasurementJsonConvertor.encodeToJson(result).toString();
                  saveResultToFile(jsonResult);
                } catch (JSONException e) {
                  Logger.e("Error converting results to json format", e);
                }
              }
            }
            handleMeasurement();
          } else if (intent.getStringExtra(UpdateIntent.TASK_STATUS_PAYLOAD).equals(
              Config.TASK_PAUSED)) {
            tasksStatus.put(taskid, TaskStatus.PAUSED);
          } else if (intent.getStringExtra(UpdateIntent.TASK_STATUS_PAYLOAD).equals(
              Config.TASK_STOPPED)) {
            tasksStatus.put(taskid, TaskStatus.SCHEDULED);
          } else if (intent.getStringExtra(UpdateIntent.TASK_STATUS_PAYLOAD).equals(
              Config.TASK_CANCELED)) {
            tasksStatus.put(taskid, TaskStatus.CANCELLED);
            Parcelable[] results = intent.getParcelableArrayExtra(UpdateIntent.RESULT_PAYLOAD);
            if (results != null) {
              sendResultToClient(results, priority, taskKey, taskid);
            }
          } else if (intent.getStringExtra(UpdateIntent.TASK_STATUS_PAYLOAD).equals(
              Config.TASK_STARTED)) {
            tasksStatus.put(taskid, TaskStatus.RUNNING);
          } else if (intent.getStringExtra(UpdateIntent.TASK_STATUS_PAYLOAD).equals(
              Config.TASK_RESUMED)) {
            tasksStatus.put(taskid, TaskStatus.RUNNING);
          }
        } else if (intent.getAction().equals(UpdateIntent.CHECKIN_ACTION)
            || intent.getAction().equals(UpdateIntent.CHECKIN_RETRY_ACTION)) {
          Logger.d("Checkin intent received");
          handleCheckin();
        }
      }
    };
    this.registerReceiver(broadcastReceiver, filter);
  }

  /**
   * Save the results of a task to a file, for later uploading. This way, if the application
   * crashes, is halted, etc. between the task and checkin, no results are lost.
   * 
   * @param result The JSON representation of a result, as a string
   */
  private synchronized void saveResultToFile(String result) {
    try {
      Logger.i("Saving result to file...");
      BufferedOutputStream writer =
          new BufferedOutputStream(openFileOutput("results", Context.MODE_PRIVATE
              | Context.MODE_APPEND));
      result += "\n";
      Logger.d("Measurement size in byte: "+result.length());
      writer.write(result.getBytes());
      writer.close();
    } catch (FileNotFoundException e) {
      Logger.e("saveResultToFile->", e);
    } catch (IOException e) {
      Logger.e("saveResultToFile->", e);
    }
  }

  /*
   * This callback ensures that we always use newest version scheduler on the device
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null) {
      Logger.e("MeasurementScheduler -> onStartCommand. Get start service intent from "
          + intent.getStringExtra(UpdateIntent.CLIENTKEY_PAYLOAD) + " (API Ver "
          + intent.getStringExtra(UpdateIntent.VERSION_PAYLOAD) + ")");
      /**
       * Fetch the version in startService intent and stop itself if the version in the intent is
       * higher than its own.
       */
      int currentAPIVersion = Integer.parseInt(Config.version);
      int newAPIVersion = Integer.parseInt(intent.getStringExtra(UpdateIntent.VERSION_PAYLOAD));

      gcmManager.checkPlayServices();
      if (currentAPIVersion < newAPIVersion) {
        Logger.e("Found scheduler version " + newAPIVersion + ", current version "
            + currentAPIVersion);
        Logger.e("Scheduler " + android.os.Process.myPid() + " stop itself");
        this.stopScheduler();
        return START_STICKY;
      }
    }
    // Start up the thread running the service.
    // Using one single thread for all requests
    if (isSchedulerStarted == false) {
      Logger.i("starting scheduler, load parameters from preference");
      isSchedulerStarted = true;
      loadFromPreference();
    }
    return START_STICKY;
  }

  /**
   * Load setting parameters from shared preference
   */
  private void loadFromPreference() {
    Logger.d("Scheduler loadFromPreference called");
    SharedPreferences prefs =
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    boolean isForced = (PhoneUtils.clientKeySet.size() == 1);

    String currentBatteryThreshold =
        prefs.getString(Config.PREF_KEY_BATTERY_THRESHOLD,
            String.valueOf(Config.DEFAULT_BATTERY_THRESH_PRECENT));
    setBatteryThresh(isForced, Integer.parseInt(currentBatteryThreshold));

    // API will always call startService before binding. So it is
    // safe to set checkin interval here
    String currentCheckinInterval =
        prefs.getString(Config.PREF_KEY_CHECKIN_INTERVAL,
            String.valueOf(Config.DEFAULT_CHECKIN_INTERVAL_SEC));
    setCheckinInterval(false, Long.parseLong(currentCheckinInterval));

    // Fetch the data limit with 250 MB as a default
    String currentProfile =
        prefs.getString(Config.PREF_KEY_DATA_USAGE_PROFILE, DataUsageProfile.PROFILE3.name());
    this.setDataUsageLimit(isForced, DataUsageProfile.valueOf(currentProfile));

    Logger.i("Preference set from SharedPreference: CheckinInterval=" + this.checkinIntervalSec
        + ", Battery Threshold= " + this.batteryThreshold + ", Profile="
        + this.dataUsageProfile.name());
  }

  @Override
  public IBinder onBind(Intent intent) {
    return messenger.getBinder();
  }

  @Override
  public boolean onUnbind(Intent intent) {
    // All clients unbind by calling unbindService(). We can safely stop scheduler
    Logger.e("All Clients unbind. Safe to stop now");
    this.stopScheduler();
    return false;
  }

  @Override
  public void onDestroy() {
    Logger.d("MeasurementScheduler -> onDestroy");
    super.onDestroy();
    cleanUp();
  }

  // return the current running task
  public synchronized MeasurementTask getCurrentTask() {
    return currentTask;
  }

  // set current running task
  public synchronized void setCurrentTask(MeasurementTask newTask) {
    if (newTask == null) {
      currentTask = null;
      Logger.d("Setting Current task -> null");
    } else {
      Logger.d("Setting Current task: " + newTask.getTaskId());
      currentTask = newTask.clone();
    }

  }

  // set current running task start time
  private synchronized void setCurrentTaskStartTime(Date starttime) {
    currentTaskStartTime = starttime;
  }

  // return the current running task (TODO synchronized?)
  private synchronized Date getCurrentTaskStartTime() {
    Date starttime;
    starttime = currentTaskStartTime;
    return starttime;
  }


  private synchronized void handleMeasurement() {
    alarmManager.cancel(measurementIntentSender);
    setCurrentTask(null);
    try {
      Logger.d("MeasurementScheduler -> In handleMeasurement " + mainQueue.size() + " "
          + waitingTasksQueue.size());
      MeasurementTask task = mainQueue.peek();
      // update the waiting queue. It contains all the tasks that are ready
      // to be executed. Here we extract all those ready tasks from main queue
      while (task != null && task.timeFromExecution() <= 0) {
        mainQueue.poll();
        
        if(task.getDescription().getType().equals(RRCTask.TYPE) && phoneUtils.getNetwork().equals(PhoneUtils.NETWORK_WIFI)){
          long updatedStartTime = System.currentTimeMillis() + (long) (10 * 60 * 1000);
          task.getDescription().startTime.setTime(updatedStartTime);
          mainQueue.add(task);
          Logger.i("MeasurementScheduler: handleMeasurement: delaying RRC task on "+phoneUtils.getNetwork());
          task = mainQueue.peek();
          continue;
        }
        Logger.i("MeasurementScheduler: handleMeasurement: "+task.getDescription().key + " " + task.getDescription().type
            + " added to waiting list");
        waitingTasksQueue.add(task);
        task = mainQueue.peek();
      }

      
      if(!phoneUtils.isNetworkAvailable()){
        Logger.i("No connection is available, set an alarm for 5 min");
        measurementIntentSender =
            PendingIntent.getBroadcast(this, 0,
                new UpdateIntent(UpdateIntent.MEASUREMENT_ACTION),
                PendingIntent.FLAG_CANCEL_CURRENT);
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + (5*60*1000),
          measurementIntentSender);
        return;
      }

      if (waitingTasksQueue.size() != 0) {
        Logger.i("waiting list size is " + waitingTasksQueue.size());
        MeasurementTask ready = waitingTasksQueue.poll();
        Logger.i("ready: " + ready.getDescription().getType());

        MeasurementDesc desc = ready.getDescription();
        long newStartTime = desc.startTime.getTime() + (long) desc.intervalSec * 1000;

        if (desc.count == MeasurementTask.INFINITE_COUNT
            && desc.priority != MeasurementTask.USER_PRIORITY) {
          if (serverTasks.containsKey(desc.toString())
              && serverTasks.get(desc.toString()).after(desc.endTime)) {
            ready.getDescription().endTime.setTime(serverTasks.get(desc.toString()).getTime());
          }
        }


        /**
         * Add a clone of the task if it's still valid it does not change the taskID (hashCode)
         */
        if (newStartTime < ready.getDescription().endTime.getTime()
            && (desc.count == MeasurementTask.INFINITE_COUNT || desc.count > 1)) {
          MeasurementTask newTask = ready.clone();
          if (desc.count != MeasurementTask.INFINITE_COUNT) {
            newTask.getDescription().count--;
          }
          newTask.getDescription().startTime.setTime(newStartTime);
          tasksStatus.put(newTask.getTaskId(), TaskStatus.SCHEDULED);
          mainQueue.add(newTask);
        } else {
          if (desc.priority != MeasurementTask.USER_PRIORITY) {
            serverTasks.remove(desc.toString());
          }
        }

        if (ready.getDescription().endTime.before(new Date())) {
          Intent intent = new Intent();
          intent.setAction(UpdateIntent.MEASUREMENT_PROGRESS_UPDATE_ACTION);
          intent.putExtra(UpdateIntent.TASK_STATUS_PAYLOAD, Config.TASK_CANCELED);
          MeasurementResult[] tempResults =
              MeasurementResult.getFailureResult(ready,
                  new CancellationException("Task cancelled!"));
          intent.putExtra(UpdateIntent.RESULT_PAYLOAD, tempResults);
          intent.putExtra(UpdateIntent.TASKID_PAYLOAD, ready.getTaskId());
          intent.putExtra(UpdateIntent.CLIENTKEY_PAYLOAD, ready.getKey());
          MeasurementScheduler.this.sendBroadcast(intent);

          if (desc.priority != MeasurementTask.USER_PRIORITY) {
            serverTasks.remove(desc.toString());
          }


          handleMeasurement();
        } else {
          Logger.d("MeasurementScheduler -> " + ready.getDescription().getType() + " is gonna run");
          Future<MeasurementResult[]> future;
          setCurrentTask(ready);
          setCurrentTaskStartTime(Calendar.getInstance().getTime());
          if (ready.getDescription().priority == MeasurementTask.USER_PRIORITY) {
            // User task can override the power policy. So a different task wrapper is used.
            future = measurementExecutor.submit(new UserMeasurementTask(ready, this));
          } else {
            future =
                measurementExecutor.submit(new ServerMeasurementTask(ready, this,
                    resourceCapManager));
          }

          synchronized (pendingTasks) {
            pendingTasks.put(ready, future);
          }
        }
      } else {// if(task.timeFromExecution()>0){
        MeasurementTask waiting = mainQueue.peek();
        if (waiting != null) {
          long timeFromExecution = task.timeFromExecution();
          measurementIntentSender =
              PendingIntent.getBroadcast(this, 0,
                  new UpdateIntent(UpdateIntent.MEASUREMENT_ACTION),
                  PendingIntent.FLAG_CANCEL_CURRENT);
          alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + timeFromExecution,
              measurementIntentSender);
          setCurrentTask(null);// TODO
          setCurrentTaskStartTime(null);
        }

      }
    } catch (IllegalArgumentException e) {
      // Task creation in clone can create this exception

    } catch (Exception e) {
      // We don't want any unexpected exception to crash the process
    }
  }

  // returns taskId on success submissions
  public synchronized String submitTask(MeasurementTask newTask) {
    // TODO check if scheduler is running...
    // and there is a current running/scheduled task
    String newTaskId = newTask.getTaskId();
    tasksStatus.put(newTaskId, TaskStatus.SCHEDULED);
    idToClientKey.put(newTaskId, newTask.getKey());
    Logger.d("MeasurementScheduler --> submitTask: " + newTask.getDescription().key + " "
        + newTaskId);
    MeasurementTask current;
    if (getCurrentTask() != null) {
      current = getCurrentTask();
      Logger.d("submitTask: current is NOT null");
    } else {
      current = null;
      Logger.d("submitTask: current is null");
    }
    // preemption condition
    if (current != null
        && newTask.getDescription().priority < current.getDescription().priority
        && new Date(current.getDuration() + getCurrentTaskStartTime().getTime()).after(newTask
            .getDescription().endTime)) {
      Logger.d("submitTask: trying to cancel/preempt the task");
      // finding the current instance in pending tasks. we can call
      // pause on that instance only
      if (pendingTasks.containsKey(current)) {
        for (MeasurementTask mt : pendingTasks.keySet()) {
          if (current.equals(mt)) {
            current = mt;
            break;
          }
        }
        Logger.e("Cancelling Current Task");
        if (current instanceof PreemptibleMeasurementTask
            && ((PreemptibleMeasurementTask) current).pause()) {
          pendingTasks.remove(current);
          ((PreemptibleMeasurementTask) current).updateTotalRunningTime(System.currentTimeMillis()
              - getCurrentTaskStartTime().getTime());
          if (newTask.timeFromExecution() <= 0) {
            mainQueue.add(newTask);
            mainQueue.add(current);
            handleMeasurement();
          } else {
            mainQueue.add(newTask);
            mainQueue.add(current);
            long timeFromExecution = newTask.timeFromExecution();
            measurementIntentSender =
                PendingIntent.getBroadcast(this, 0, new UpdateIntent(
                    UpdateIntent.MEASUREMENT_ACTION), PendingIntent.FLAG_CANCEL_CURRENT);
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                + timeFromExecution, measurementIntentSender);
            setCurrentTask(newTask);
            setCurrentTaskStartTime(new Date(System.currentTimeMillis() + timeFromExecution));
          }

        } else if (current.stop()) {
          pendingTasks.remove(current);
          if (newTask.timeFromExecution() <= 0) {
            mainQueue.add(newTask);
            mainQueue.add(current);
            handleMeasurement();
          } else {
            mainQueue.add(newTask);
            mainQueue.add(current);
            long timeFromExecution = newTask.timeFromExecution();
            measurementIntentSender =
                PendingIntent.getBroadcast(this, 0, new UpdateIntent(
                    UpdateIntent.MEASUREMENT_ACTION), PendingIntent.FLAG_CANCEL_CURRENT);
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                + timeFromExecution, measurementIntentSender);
            // setCurrentTask(null);
            setCurrentTask(newTask);
            setCurrentTaskStartTime(new Date(System.currentTimeMillis() + timeFromExecution));
          }
        } else {
          mainQueue.add(newTask);
        }
      } else {
        alarmManager.cancel(measurementIntentSender);
        if (newTask.timeFromExecution() <= 0) {
          mainQueue.add(newTask);
          handleMeasurement();
        } else {
          mainQueue.add(newTask);
          long timeFromExecution = newTask.timeFromExecution();
          measurementIntentSender =
              PendingIntent.getBroadcast(this, 0,
                  new UpdateIntent(UpdateIntent.MEASUREMENT_ACTION),
                  PendingIntent.FLAG_CANCEL_CURRENT);
          alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + timeFromExecution,
              measurementIntentSender);
          // setCurrentTask(null);
          setCurrentTask(newTask);
          setCurrentTaskStartTime(new Date(System.currentTimeMillis() + timeFromExecution));
        }
      }
    } else {
      Logger.d("submitTask: adding to mainqueue");
      mainQueue.add(newTask);
      if (current == null) {
        Logger.d("submitTask: adding to mainqueue, current is null");
        Logger.d("submitTask: calling handleMeasurement");
        alarmManager.cancel(measurementIntentSender);
        handleMeasurement();
      } else {
        Logger.d("submitTask: adding to mainqueue, current is not null: "
            + current.getMeasurementType() + " " + getCurrentTaskStartTime());
        if (pendingTasks.containsKey(current)) {
          Logger.d("submitTask: isDone?" + pendingTasks.get(current).isDone());
          if (pendingTasks.get(current).isDone()) {
            alarmManager.cancel(measurementIntentSender);
            alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3 * 1000,
                measurementIntentSender);
          } else if (getCurrentTaskStartTime() != null) {
            if (!current.getMeasurementType().equals(RRCTask.TYPE)
                && new Date(System.currentTimeMillis() - Config.MAX_TASK_DURATION)
                    .after(getCurrentTaskStartTime())) {
              Logger.d("submitTask: 1");
              pendingTasks.get(current).cancel(true);
              handleMeasurement();

            } else if (current.getMeasurementType().equals(RRCTask.TYPE)
                && new Date(System.currentTimeMillis()
                    - (Config.DEFAULT_RRC_TASK_DURATION + 15 * 60 * 1000))
                    .after(getCurrentTaskStartTime())) {
              Logger.d("submitTask: 2");
              pendingTasks.get(current).cancel(true);
              handleMeasurement();
            } else {
              Logger.d("submitTask: 3");
              alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                  + Config.MAX_TASK_DURATION / 2, measurementIntentSender);
            }
          }


        } else {
          Logger.d("submitTask: not found in pending task");
          handleMeasurement();
        }

      }
    }
    return newTaskId;
  }

  public synchronized boolean cancelTask(String taskId, String clientKey) {

    if (clientKey.equals(Config.SERVER_TASK_CLIENT_KEY)) {
      return false;
    }

    Logger.i("Cancel task " + taskId + " from " + clientKey);
    if (taskId != null && idToClientKey.containsKey(taskId)) {
      if (idToClientKey.get(taskId).equals(clientKey)) {
        boolean found = false;
        for (Object object : mainQueue) {
          MeasurementTask task = (MeasurementTask) object;
          if (task.getTaskId().equals(taskId) && task.getKey().equals(clientKey)) {
            mainQueue.remove(task);
            found = true;
          }
        }

        for (Object object : waitingTasksQueue) {
          MeasurementTask task = (MeasurementTask) object;
          if (task.getTaskId().equals(taskId) && task.getKey().equals(clientKey)) {
            waitingTasksQueue.remove(task);
            found = true;
          }
        }
        MeasurementTask currentMeasurementTask = getCurrentTask();
        if (currentMeasurementTask != null) {
          Logger.i("submitTask: current taskId " + currentMeasurementTask.getTaskId());
          if (currentMeasurementTask.getTaskId().equals(taskId)
              && currentMeasurementTask.getKey().equals(clientKey)) {
            for (MeasurementTask mt : pendingTasks.keySet()) {
              if (currentMeasurementTask.equals(mt)) {
                currentMeasurementTask = mt;
                break;
              }
            }
            pendingTasks.remove(currentMeasurementTask);
            return currentMeasurementTask.stop();
          }
        }

        return found;
      }
    }
    return false;
  }

  private void persistParams(String key, String value) {
    Logger.d("Scheduler persistParams called");
    SharedPreferences prefs =
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    SharedPreferences.Editor editor = prefs.edit();
    editor.putString(key, value);
    editor.commit();
  }

  /**
   * @param newBatteryThresh
   * @return the final value of battery threshold
   */
  // API should ensure that the value is between 0 and 100
  public synchronized int setBatteryThresh(boolean isForced, int newBatteryThresh) {
    Logger.d("Scheduler->setBatteryThresh called");
    int min = Config.MIN_BATTERY_THRESHOLD;
    int max = Config.MAX_BATTERY_THRESHOLD;
    /**
     * If there are multiple apps registered, we gonna be conservative Only allow raising the
     * battery threshold
     */
    if (!isForced && this.batteryThreshold != -1) {
      min = this.batteryThreshold;
    }
    // Check whether out of boundary and same with old value
    if (newBatteryThresh >= min && newBatteryThresh <= max
        && newBatteryThresh != this.batteryThreshold) {
      this.batteryThreshold = newBatteryThresh;
      Logger.i("Setting battery threshold to " + this.batteryThreshold + "%");
      persistParams(Config.PREF_KEY_BATTERY_THRESHOLD, String.valueOf(this.batteryThreshold));
    }
    this.resourceCapManager.setBatteryThresh(this.batteryThreshold);
    return this.batteryThreshold;
  }

  /**
   * If the users have not specified the threshold, it will return the default value
   * 
   * @return
   */
  public synchronized int getBatteryThresh() {
    if (this.batteryThreshold == -1) {
      return Config.DEFAULT_BATTERY_THRESH_PRECENT;
    }
    return this.batteryThreshold;
  }


  /** Set the interval for checkin in seconds */
  public synchronized long setCheckinInterval(boolean isForced, long interval) {
    Logger.d("Scheduler->setCheckinInterval called " + interval);
    long min = Config.MIN_CHECKIN_INTERVAL_SEC;
    long max = Config.MAX_CHECKIN_INTERVAL_SEC;


    if (!isForced && this.checkinIntervalSec != -1) {
      min = this.batteryThreshold;
    }
    // Check whether out of boundary and same with old value
    if (interval >= min && interval <= max && interval != this.checkinIntervalSec) {
      this.checkinIntervalSec = interval;
      Logger.i("Setting checkin interval to " + this.checkinIntervalSec + " seconds");
      persistParams(Config.PREF_KEY_CHECKIN_INTERVAL, String.valueOf(this.checkinIntervalSec));
      // the new checkin schedule will start
      // in PAUSE_BETWEEN_CHECKIN_CHANGE_MSEC seconds
      checkinIntentSender =
          PendingIntent.getBroadcast(this, 0, new UpdateIntent(UpdateIntent.CHECKIN_ACTION),
              PendingIntent.FLAG_CANCEL_CURRENT);
      alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
          + Config.PAUSE_BETWEEN_CHECKIN_CHANGE_MSEC, checkinIntervalSec * 1000,
          checkinIntentSender);
    }
    return this.checkinIntervalSec;
  }

  /** Returns the checkin interval of the scheduler in seconds */
  public synchronized long getCheckinInterval() {
    if (this.checkinIntervalSec == -1) {
      return Config.DEFAULT_CHECKIN_INTERVAL_SEC;
    }
    return this.checkinIntervalSec;
  }

  public synchronized void setDataUsageLimit(boolean isForced, DataUsageProfile profile) {
    Logger.d("Scheduler->setDataUsageLimit called");
    int min = DataUsageProfile.PROFILE1.ordinal();
    int max = DataUsageProfile.UNLIMITED.ordinal();
    if (!isForced && profile != null && profile != DataUsageProfile.NOTASSIGNED) {
      max = this.dataUsageProfile.ordinal();
    }
    if (profile.ordinal() >= min && profile.ordinal() <= max && profile != this.dataUsageProfile) {
      this.dataUsageProfile = profile;
      resourceCapManager.setDataUsageLimit(profile);
      Logger.i("Setting data usage profile to " + this.dataUsageProfile);
      persistParams(Config.PREF_KEY_DATA_USAGE_PROFILE, this.dataUsageProfile.name());
    }
  }

  public synchronized DataUsageProfile getDataUsageProfile() {
    if (this.dataUsageProfile.equals(DataUsageProfile.NOTASSIGNED)) {
      return DataUsageProfile.PROFILE3;
    }
    return this.dataUsageProfile;
  }

  public synchronized void setAuthenticateAccount(String selectedAccount) {
    Logger.d("Scheduler->setAuthenticateAccount called");
    if (selectedAccount != null) {
      // Verify that the account is valid or is anonymous
      String[] accounts = AccountSelector.getAccountList(getApplicationContext());
      for (String account : accounts) {
        if (account.equals(selectedAccount)) {
          Logger.i("Setting authenticate account to " + account);
          persistParams(Config.PREF_KEY_SELECTED_ACCOUNT, account);
          return;
        }
      }
      Logger.e("Error: " + selectedAccount + " doesn't match any account or anonymous");
    }
  }

  /**
   * Get current authenticate account of Mobilyzer
   * 
   * @return account in string if already set by clients(include Anonymous), or null if havn't been
   *         set yet(it will checkin as anonymous user)
   */
  public synchronized String getAuthenticateAccount() {
    Logger.d("Scheduler->getAuthenticateAccount called");
    SharedPreferences prefs =
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    String selectedAccount = prefs.getString(Config.PREF_KEY_SELECTED_ACCOUNT, null);
    Logger.i("Get authenticate account: " + selectedAccount);
    return selectedAccount;
  }

  /**
   * Adjusts the frequency of the task based on the profile passed from the server.
   * 
   * Alternately, disregards the task altogether, if a -1 is passed.
   * 
   * @param task The task to adjust
   * @return false if the task should not be scheduled, based on the profile
   */
  private boolean adjustInterval(MeasurementTask task) {

    Map<String, String> params = task.getDescription().parameters;
    float adjust = 1; // default
    if (params.containsKey("profile_1_freq") && getDataUsageProfile() == DataUsageProfile.PROFILE1) {
      adjust = Float.parseFloat(params.get("profile_1_freq"));
      Logger.i("Task " + task.getDescription().key + " adjusted using profile 1");
    } else if (params.containsKey("profile_2_freq")
        && getDataUsageProfile() == DataUsageProfile.PROFILE2) {
      adjust = Float.parseFloat(params.get("profile_2_freq"));
      Logger.i("Task " + task.getDescription().key + " adjusted using profile 2");
    } else if (params.containsKey("profile_3_freq")
        && getDataUsageProfile() == DataUsageProfile.PROFILE3) {
      adjust = Float.parseFloat(params.get("profile_3_freq"));
      Logger.i("Task " + task.getDescription().key + " adjusted using profile 3");
    } else if (params.containsKey("profile_4_freq")
        && getDataUsageProfile() == DataUsageProfile.PROFILE4) {
      adjust = Float.parseFloat(params.get("profile_4_freq"));
      Logger.i("Task " + task.getDescription().key + " adjusted using profile 4");
    } else if (params.containsKey("profile_unlimited")
        && getDataUsageProfile() == DataUsageProfile.UNLIMITED) {
      adjust = Float.parseFloat(params.get("profile_unlimited"));
      Logger.i("Task " + task.getDescription().key + " adjusted using unlimited profile");
    }
    if (adjust <= 0) {
      Logger.i("Task " + task.getDescription().key + "marked for removal");
      return false;
    }
    // if (!task.getType().equals(RRCTask.TYPE)) {
    // return false;
    // }
    task.getDescription().intervalSec *= adjust;
    Calendar now = Calendar.getInstance();
    now.add(Calendar.SECOND, (int) (task.getDescription().intervalSec / 24));
    task.getDescription().startTime = now.getTime();
    if (task.getDescription().startTime.after(task.getDescription().endTime)) {
      task.getDescription().endTime =
          new Date(task.getDescription().startTime.getTime() + Config.TASK_EXPIRATION_MSEC);
    }
    // Logger.d(task.getDescription().startTime+" "+task.getDescription().endTime);
    return true;

  }

  public TaskStatus getTaskStatus(String taskID) {
    if (tasksStatus.get(taskID) == null) {
      return TaskStatus.NOTFOUND;
    }
    return tasksStatus.get(taskID);
  }

  private class TaskComparator implements Comparator<MeasurementTask> {

    @Override
    public int compare(MeasurementTask task1, MeasurementTask task2) {
      return task1.compareTo(task2);
    }
  }

  private class WaitingTasksComparator implements Comparator<MeasurementTask> {

    @Override
    public int compare(MeasurementTask task1, MeasurementTask task2) {
      Long task1Prority = task1.measurementDesc.priority;
      Long task2Priority = task2.measurementDesc.priority;

      int priorityComparison = task1Prority.compareTo(task2Priority);
      if (priorityComparison == 0 && task1.measurementDesc.endTime != null
          && task2.measurementDesc.endTime != null) {
        return task1.measurementDesc.endTime.compareTo(task2.measurementDesc.endTime);
      } else {
        return priorityComparison;
      }
    }
  }

  /**
   * Send measurement results to the client that submit the task, or broadcast the result of server
   * scheduled task to each connected clients
   * 
   * @param results Measurement result to be sent
   * @param priority Priority for the task. Determine the communication way - Unicast for user task,
   *        Broadcast for server task
   * @param clientKey Client key for the task
   * @param taskId Unique task id for the task
   */
  public void sendResultToClient(Parcelable[] results, int priority, String clientKey, String taskId) {
    Intent intent = new Intent();
    intent.putExtra(UpdateIntent.RESULT_PAYLOAD, results);
    intent.putExtra(UpdateIntent.TASKID_PAYLOAD, taskId);
    if (priority == MeasurementTask.USER_PRIORITY) {
      Logger.d("Sending result to client " + clientKey + ": taskId " + taskId);
      intent.setAction(UpdateIntent.USER_RESULT_ACTION + "." + clientKey);
    } else {
      Logger.d("Broadcasting result: taskId " + taskId);
      intent.setAction(UpdateIntent.SERVER_RESULT_ACTION);
    }
    this.sendBroadcast(intent);
  }

  /**
   * Stop the scheduler
   */
  public synchronized void stopScheduler() {
    this.notifyAll();
    // this.stopForeground(true);
    this.stopSelf();
  }

  private synchronized void cleanUp() {
    Logger.d("Service cleanUp called");
    this.mainQueue.clear();
    this.waitingTasksQueue.clear();

    if (this.currentTask != null) {
      this.currentTask.stop();
    }

    // remove all future tasks
    this.measurementExecutor.shutdown();
    // remove and stop all active tasks
    this.measurementExecutor.shutdownNow();
    this.checkin.shutDown();

    this.unregisterReceiver(broadcastReceiver);
    Logger.d("canceling pending intents");

    if (checkinIntentSender != null) {
      checkinIntentSender.cancel();
      alarmManager.cancel(checkinIntentSender);
    }
    if (checkinRetryIntentSender != null) {
      checkinRetryIntentSender.cancel();
      alarmManager.cancel(checkinRetryIntentSender);
    }
    if (measurementIntentSender != null) {
      measurementIntentSender.cancel();
      alarmManager.cancel(measurementIntentSender);
    }
    this.notifyAll();
    phoneUtils.shutDown();



    Logger.i("Shut down all executors and stopping service");
  }


  private void getTasksFromServer() throws IOException {
    Logger.i("Downloading tasks from the server");
    checkin.getCookie();
    List<MeasurementTask> tasksFromServer = checkin.checkin(resourceCapManager, gcmManager);
    // The new task schedule overrides the old one

    Logger.i("Received " + tasksFromServer.size() + " task(s) from server");

    for (MeasurementTask task : tasksFromServer) {
      task.measurementDesc.key = Config.SERVER_TASK_CLIENT_KEY;
      if (adjustInterval(task)) {
        if (task.getDescription().count == MeasurementTask.INFINITE_COUNT) {
          if (!serverTasks.containsKey(task.getDescription().toString())) {
            this.mainQueue.add(task);
          }
          serverTasks.put(task.getDescription().toString(), task.getDescription().endTime);
        } else {
          this.mainQueue.add(task);
        }
      }
    }
//    saveSchedulerState();//TODO(ASHKAN)
  }

  /**
   * Save the entire current schedule to a file, in JSON format, like how tasks are received from
   * the server.
   * 
   * One item per line.
   */
  private void saveSchedulerState() {

    try {
      BufferedOutputStream writer =
          new BufferedOutputStream(openFileOutput("schedule", Context.MODE_PRIVATE));
      Object[] mainQueueArray = new Object[0];
      Logger.i("Saving schedule to a file...");
      synchronized (mainQueue) {
        mainQueueArray = mainQueue.toArray();
      }

      for (Object entry : mainQueueArray) {
        try {
          JSONObject task =
              MeasurementJsonConvertor.encodeToJson(((MeasurementTask) entry).getDescription());
          String taskstring = task.toString() + "\n";
          writer.write(taskstring.getBytes());
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }

      Object[] waitingTasksArray = new Object[0];
      synchronized (pendingTasks) {
        waitingTasksArray = waitingTasksQueue.toArray();
      }

      for (Object entry : waitingTasksArray) {
        try {
          JSONObject task =
              MeasurementJsonConvertor.encodeToJson(((MeasurementTask) entry).getDescription());
          String taskstring = task.toString() + "\n";
          writer.write(taskstring.getBytes());
        } catch (JSONException e) {
          e.printStackTrace();
        }
      }
      writer.close();
    } catch (FileNotFoundException e) {
      Logger.e("saveSchedulerState->", e);
    } catch (IOException e) {
      Logger.e("saveSchedulerState->", e);
    }

  }

  /**
   * Load the schedule from the schedule file, if it exists.
   * 
   * This is to be run when the app first starts up, so scheduled items are not lost.
   */
  private void loadSchedulerState() {
    // Vector<MeasurementTask> tasksToAdd = new Vector<MeasurementTask>();
    synchronized (mainQueue) {
      try {
        Logger.i("Restoring schedule from disk...");
        FileInputStream inputstream = openFileInput("schedule");
        InputStreamReader streamreader = new InputStreamReader(inputstream);
        BufferedReader bufferedreader = new BufferedReader(streamreader);

        String line;
        while ((line = bufferedreader.readLine()) != null) {
          JSONObject jsonTask;
          try {
            jsonTask = new JSONObject(line);
            MeasurementTask newTask =
                MeasurementJsonConvertor.makeMeasurementTaskFromJson(jsonTask);

            // If the task is scheduled in the past, re-schedule it in the future
            // We assume tasks in the past have run, otherwise we can wind up getting
            // stuck trying to run a large backlog of tasks

            long curtime = System.currentTimeMillis();
            
            if (curtime > newTask.getDescription().endTime.getTime()){
            	continue;
            }
            
            if (curtime > newTask.getDescription().startTime.getTime()) {
              long timediff = curtime - newTask.getDescription().startTime.getTime();

              timediff = (long) (timediff % (newTask.getDescription().intervalSec * 1000));
              Calendar now = Calendar.getInstance();
              now.add(Calendar.SECOND, (int) timediff / 1000);
              newTask.getDescription().startTime.setTime(now.getTimeInMillis());
              Logger.i("Rescheduled task " + newTask.getDescription().key + " at time "
                  + now.getTimeInMillis());
            }

            mainQueue.add(newTask);
          } catch (JSONException e) {
            e.printStackTrace();
          }
        }
        handleMeasurement();
        bufferedreader.close();
        streamreader.close();
        inputstream.close();

      } catch (FileNotFoundException e) {
        e.printStackTrace();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

  }



  private void resetCheckin() {
    // reset counters for checkin
    checkinRetryCnt = 0;
    checkinRetryIntervalSec = Config.MIN_CHECKIN_RETRY_INTERVAL_SEC;
    checkin.initializeAccountSelector();
  }

  private class CheckinTask implements Runnable {
    @Override
    public void run() {
      Logger.i("checking Speedometer service for new tasks");
      lastCheckinTime = Calendar.getInstance();
      PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
      try {
        uploadResults();
        getTasksFromServer();
        // Also reset checkin if we get a success
        resetCheckin();
        // Schedule the new tasks
        if (getCurrentTask() == null) {// TODO check this
          alarmManager.cancel(measurementIntentSender);
          handleMeasurement();
        }
        //
      } catch (Exception e) {
        /*
         * Executor stops all subsequent execution of a periodic task if a raised exception is
         * uncaught. We catch all undeclared exceptions here
         */
        Logger.e("Unexpected exceptions caught", e);
        if (checkinRetryCnt > Config.MAX_CHECKIN_RETRY_COUNT) {
          /*
           * If we have retried more than MAX_CHECKIN_RETRY_COUNT times upon a checkin failure, we
           * will stop retrying and wait until the next checkin period
           */
          resetCheckin();
        } else if (checkinRetryIntervalSec < checkinIntervalSec) {
          Logger.i("Retrying checkin in " + checkinRetryIntervalSec + " seconds");
          /*
           * Use checkinRetryIntentSender so that the periodic checkin schedule will remain intact
           */
          checkinRetryIntentSender =
              PendingIntent.getBroadcast(MeasurementScheduler.this, 0, new UpdateIntent(
                  UpdateIntent.CHECKIN_RETRY_ACTION), PendingIntent.FLAG_CANCEL_CURRENT);
          alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
              + checkinRetryIntervalSec * 1000, checkinRetryIntentSender);
          checkinRetryCnt++;
          checkinRetryIntervalSec =
              Math.min(Config.MAX_CHECKIN_RETRY_INTERVAL_SEC, checkinRetryIntervalSec * 2);
        }
      } finally {
        if (phoneUtils != null) {
          phoneUtils.releaseWakeLock();
        }
      }
    }
  }

  private void uploadResults() {
    Vector<MeasurementResult> finishedTasks = new Vector<MeasurementResult>();
    MeasurementResult[] results;
    Future<MeasurementResult[]> future;
    Logger.d("pendingTasks: "+pendingTasks.size());
    synchronized (this.pendingTasks) {
      try {
        for (MeasurementTask task : this.pendingTasks.keySet()) {
          future = this.pendingTasks.get(task);
          if (future != null) {
            if (future.isDone()) {
              this.pendingTasks.remove(task);
              if (future.isCancelled()) {
                Logger.e("Task execution was canceled");
                results =
                    MeasurementResult.getFailureResult(task, new CancellationException(
                        "Task cancelled"));
                for (MeasurementResult r : results) {
                  finishedTasks.add(r);
                }
              }
            }
          }
        }
      } catch (ConcurrentModificationException e) {
        /*
         * keySet is a synchronized view of the keys. However, changes during iteration will throw
         * ConcurrentModificationException. Since we have synchronized all changes to pendingTasks
         * this should not happen.
         */
        Logger.e("Pending tasks is changed during measurement upload");
      }
    }
    Logger.i("A total of " + finishedTasks.size() + " from pendingTasks is uploaded");
    Logger.i("A total of " + this.pendingTasks.size() + " is in pendingTasks");

    try {
      for (MeasurementResult r : finishedTasks) {
        r.getMeasurementDesc().parameters = null;
      }
      this.checkin.uploadMeasurementResult(finishedTasks, resourceCapManager);
    } catch (IOException e) {
      Logger.e("Error when uploading message");
    }
  }


  /** Returns the last checkin time */
  public synchronized Date getLastCheckinTime() {
    if (lastCheckinTime != null) {
      return lastCheckinTime.getTime();
    } else {
      return null;
    }
  }

  /** Returns the next (expected) checkin time */
  public synchronized Date getNextCheckinTime() {
    if (lastCheckinTime != null) {
      Calendar nextCheckinTime = (Calendar) lastCheckinTime.clone();
      nextCheckinTime.add(Calendar.SECOND, (int) getCheckinInterval());
      return nextCheckinTime.getTime();
    } else {
      return null;
    }
  }

  /**
   * Perform a checkin operation.
   */
  public void handleCheckin() {
    // if ( PhoneUtils.getPhoneUtils().isCharging() == false
    // && PhoneUtils.getPhoneUtils().getCurrentBatteryLevel() < getBatteryThresh()) {
    if (!resourceCapManager.canScheduleExperiment()) {
      Logger.e("Checkin skipped - below battery threshold " + getBatteryThresh());
      return;
    }
    /*
     * The CPU can go back to sleep immediately after onReceive() returns. Acquire the wake lock for
     * the new thread here and release the lock when the thread finishes
     */
    PhoneUtils.getPhoneUtils().acquireWakeLock();
    new Thread(checkinTask).start();
  }
}
