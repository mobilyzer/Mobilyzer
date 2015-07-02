/*
 * Copyright 2012 Google Inc.
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

import android.os.Parcel;
import android.os.Parcelable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InvalidClassException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mobilyzer.Config;
import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.PreemptibleMeasurementTask;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;
import com.mobilyzer.util.Util;


/**
 * A Callable task that handles Traceroute measurements
 */
public class TracerouteTask extends MeasurementTask implements PreemptibleMeasurementTask {
  // Type name for internal use
  public static final String TYPE = "traceroute";
  // Human readable name for the task
  public static final String DESCRIPTOR = "traceroute";
  /*
   * Default payload size of the ICMP packet, plus the 8-byte ICMP header resulting in a total of
   * 64-byte ICMP packet
   */
  public static final int DEFAULT_PING_PACKET_SIZE = 56;
  public static final int DEFAULT_PING_TIMEOUT = 10;
  public static final int DEFAULT_MAX_HOP_CNT = 30;
  public static final int DEFAULT_PARALLEL_PROBE_NUM = 1;
  // Used to compute progress for user
  public static final int EXPECTED_HOP_CNT = 20;
  public static final int DEFAULT_PINGS_PER_HOP = 3;

  private long duration;
  public ArrayList<Double> resultsArray;
  private TaskProgress taskProgress;


  private volatile boolean stopFlag;
  private volatile boolean pauseFlag;
  public ArrayList<HopInfo> hopHosts;// TODO: change it to private
  private long totalRunningTime;
  private int ttl;
  private int maxHopCount;

  // Track data consumption for this task to avoid exceeding user's limit
  private long dataConsumed;

  /**
   * The description of the Traceroute measurement
   */
  public static class TracerouteDesc extends MeasurementDesc {
    // the host name or IP address to use as the target of the traceroute.
    public String target;
    // the packet per ICMP ping in the unit of bytes
    private int packetSizeByte;
    // the number of seconds we wait for a ping response.
    private int pingTimeoutSec;
    // the interval between successive pings in seconds
    private double pingIntervalSec;
    // the number of pings we use for each ttl value
    private int pingsPerHop;
    // the total number of pings will send before we declarethe traceroute fails
    private int maxHopCount;
    // the location of the ping binary. Only used internally
    private String pingExe;
    // TODO, this should be moved into MeasurementDesc if we want to have that for all measurement
    // types
    public String preCondition;

    private int parallelProbeNum;

    public TracerouteDesc(String key, Date startTime, Date endTime, double intervalSec, long count,
        long priority, int contextIntervalSec, Map<String, String> params)
        throws InvalidParameterException {
      super(TracerouteTask.TYPE, key, startTime, endTime, intervalSec, count, priority,
          contextIntervalSec, params);
      initializeParams(params);

      if (target == null || target.length() == 0) {
        throw new InvalidParameterException("Target of traceroute cannot be null");
      }
    }

    @Override
    public String getType() {
      return TracerouteTask.TYPE;
    }

    @Override
    protected void initializeParams(Map<String, String> params) {

      if (params == null) {
        return;
      }

      // HTTP specific parameters according to the design document
      this.target = params.get("target");
      try {
        String val;
        if ((val = params.get("packet_size_byte")) != null && val.length() > 0
            && Integer.parseInt(val) > 0) {
          this.packetSizeByte = Integer.parseInt(val);
        } else {
          this.packetSizeByte = TracerouteTask.DEFAULT_PING_PACKET_SIZE;
        }
        if ((val = params.get("ping_timeout_sec")) != null && val.length() > 0
            && Integer.parseInt(val) > 0) {
          this.pingTimeoutSec = Integer.parseInt(val);
        } else {
          this.pingTimeoutSec = TracerouteTask.DEFAULT_PING_TIMEOUT;
        }
        if ((val = params.get("ping_interval_sec")) != null && val.length() > 0
            && Integer.parseInt(val) > 0) {
          this.pingIntervalSec = Integer.parseInt(val);
        } else {
          this.pingIntervalSec = Config.DEFAULT_INTERVAL_BETWEEN_ICMP_PACKET_SEC;
        }
        if ((val = params.get("pings_per_hop")) != null && val.length() > 0
            && Integer.parseInt(val) > 0) {
          this.pingsPerHop = Integer.parseInt(val);
        } else {
          this.pingsPerHop = TracerouteTask.DEFAULT_PINGS_PER_HOP;
        }
        if ((val = params.get("max_hop_count")) != null && val.length() > 0
            && Integer.parseInt(val) > 0) {
          this.maxHopCount = Integer.parseInt(val);
        } else {
          this.maxHopCount = TracerouteTask.DEFAULT_MAX_HOP_CNT;
        }
        if ((val = params.get("precond")) != null && val.length() > 0) {
          this.preCondition = params.get("precond");
        } else {
          this.preCondition = null;
        }
        if ((val = params.get("parallel_probe_num")) != null && val.length() > 0
            && Integer.parseInt(val) > 0) {
          this.parallelProbeNum = Integer.parseInt(val);
        } else {
          this.parallelProbeNum = TracerouteTask.DEFAULT_PARALLEL_PROBE_NUM;
        }
      } catch (NumberFormatException e) {
        throw new InvalidParameterException("PingTask cannot be created due " + "to invalid params");
      }



    }

    protected TracerouteDesc(Parcel in) {
      super(in);
      target = in.readString();
      packetSizeByte = in.readInt();
      pingTimeoutSec = in.readInt();
      pingIntervalSec = in.readDouble();
      pingsPerHop = in.readInt();
      maxHopCount = in.readInt();
      pingExe = in.readString();
      preCondition = in.readString();
      parallelProbeNum = in.readInt();
    }

    public static final Parcelable.Creator<TracerouteDesc> CREATOR =
        new Parcelable.Creator<TracerouteDesc>() {
          public TracerouteDesc createFromParcel(Parcel in) {
            return new TracerouteDesc(in);
          }

          public TracerouteDesc[] newArray(int size) {
            return new TracerouteDesc[size];
          }
        };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      super.writeToParcel(dest, flags);
      dest.writeString(target);
      dest.writeInt(packetSizeByte);
      dest.writeInt(pingTimeoutSec);
      dest.writeDouble(pingIntervalSec);
      dest.writeInt(pingsPerHop);
      dest.writeInt(maxHopCount);
      dest.writeString(pingExe);
      dest.writeString(preCondition);
      dest.writeInt(parallelProbeNum);
    }
  }

  public TracerouteTask(MeasurementDesc desc) {
    super(new TracerouteDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec, desc.count,
        desc.priority, desc.contextIntervalSec, desc.parameters));
    this.duration = Config.TRACEROUTE_TASK_DURATION;
    this.taskProgress = TaskProgress.FAILED;
    this.stopFlag = false;
    this.pauseFlag = false;
    this.hopHosts = new ArrayList<HopInfo>();
    this.ttl = 1;
    this.maxHopCount = ((TracerouteDesc) this.measurementDesc).maxHopCount;
    this.totalRunningTime = 0;
    this.dataConsumed = 0;
  }

  protected TracerouteTask(Parcel in) {
    super(in);
    duration = in.readLong();
    taskProgress = (TaskProgress) in.readSerializable();
    stopFlag = (in.readByte() != 0);
    pauseFlag = (in.readByte() != 0);
    hopHosts = new ArrayList<HopInfo>();
    ttl = in.readInt();
    maxHopCount = ((TracerouteDesc) this.measurementDesc).maxHopCount;
    totalRunningTime = in.readLong();
    dataConsumed = in.readLong();
  }

  public static final Parcelable.Creator<TracerouteTask> CREATOR =
      new Parcelable.Creator<TracerouteTask>() {
        public TracerouteTask createFromParcel(Parcel in) {
          return new TracerouteTask(in);
        }

        public TracerouteTask[] newArray(int size) {
          return new TracerouteTask[size];
        }
      };

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    super.writeToParcel(dest, flags);
    dest.writeLong(duration);
    dest.writeSerializable(taskProgress);
    dest.writeByte((byte) (stopFlag ? 1 : 0));
    dest.writeByte((byte) (pauseFlag ? 1 : 0));
    dest.writeInt(ttl);
    dest.writeLong(totalRunningTime);
    dest.writeLong(dataConsumed);
  }

  /**
   * Returns a copy of the TracerouteTask
   */
  @Override
  public MeasurementTask clone() {
    MeasurementDesc desc = this.measurementDesc;
    TracerouteDesc newDesc =
        new TracerouteDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec, desc.count,
            desc.priority, desc.contextIntervalSec, desc.parameters);
    return new TracerouteTask(newDesc);
  }

  @Override
  public MeasurementResult[] call() throws MeasurementError {

    TracerouteDesc task = (TracerouteDesc) this.measurementDesc;
    // int maxHopCount = task.maxHopCount;
    // int ttl = 1;
    String hostIp = null;
    String target = task.target;
    taskProgress = TaskProgress.FAILED;
    stopFlag = false;
    pauseFlag = false;



    Logger.d("Starting traceroute on host " + task.target);

    try {
      InetAddress hostInetAddr = InetAddress.getByName(target);
      hostIp = hostInetAddr.getHostAddress();
      // add support for ipv6
      int ipByteLen = hostInetAddr.getAddress().length;
      Logger.i("IP address length is " + ipByteLen);
      Logger.i("IP is " + hostIp);
      task.pingExe = Util.pingExecutableBasedOnIPType(ipByteLen);
      Logger.i("Ping executable is " + task.pingExe);
      if (task.pingExe == null) {
        Logger.e("Ping Executable not found");
        throw new MeasurementError("Ping Executable not found");
      }
    } catch (UnknownHostException e) {
      Logger.e("Cannont resolve host " + target);
      throw new MeasurementError("target " + target + " cannot be resolved");
    }
    MeasurementResult result = null;


    ExecutorService hopExecutorService = Executors.newFixedThreadPool(task.parallelProbeNum);
    CompletionService<HopInfo> taskCompletionService =
        new ExecutorCompletionService<HopInfo>(hopExecutorService);

    /*
     * Current traceroute implementation sends out three ICMP probes per TTL. One ping every 0.2s is
     * the lower bound before some platforms requires root to run ping. We ping once every time to
     * get a rough rtt as we cannot get the exact rtt from the output of the ping command with ttl
     * being set
     */
    boolean[] hopsStatus = new boolean[maxHopCount];
    for (int i = maxHopCount; i > 0; i--) {
      hopsStatus[i - 1] = false;
      String command =
          Util.constructCommand(task.pingExe, "-n", "-t", ttl, "-s", task.packetSizeByte, "-c 1",
              target);
      taskCompletionService.submit(new HopExecutor(task.pingsPerHop, command, hostIp, ttl));
      ttl++;
    }

    for (int tasksHandled = 0; tasksHandled < maxHopCount; tasksHandled++) {


      try {
        Future<HopInfo> hopResult = taskCompletionService.take();
        HopInfo hop = hopResult.get();
        hopHosts.add(hop);
        HashSet<String> hostsAtThisDistance = hop.hosts;
        hopsStatus[hop.ttl - 1] = true;
        for (String ip : hostsAtThisDistance) {
          // If we have reached the final destination hostIp,
          // print it out and clean up
          boolean allHopsAreDone = true;
          if (ip.compareTo(hostIp) == 0) {
            for (int i = 0; i < hop.ttl; i++) {
              if (!hopsStatus[hop.ttl - 1]) {
                allHopsAreDone=false;
              }
            }
            
            if(allHopsAreDone){
              
              hopExecutorService.shutdownNow();
              Logger.i(hop.ttl + ": " + hostIp);
              Logger.i(" Finished! " + target + " reached in " + hop.ttl + " hops");
              taskProgress = TaskProgress.COMPLETED;
              PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
              result =
                  new MeasurementResult(phoneUtils.getDeviceInfo().deviceId,
                      phoneUtils.getDeviceProperty(this.getKey()), TracerouteTask.TYPE,
                      System.currentTimeMillis() * 1000, taskProgress, this.measurementDesc);
              result.addResult("num_hops", hop.ttl);
              for (int i = 0; i < hopHosts.size(); i++) {
                HopInfo hopInfo = hopHosts.get(i);
                int hostIdx = 1;
                for (String host : hopInfo.hosts) {
                  result.addResult("hop_" + hopInfo.ttl + "_addr_" + hostIdx++, host);
                }
                result.addResult("hop_" + hopInfo.ttl + "_rtt_ms", String.format("%.3f", hopInfo.rtt));
                if (hopInfo.hosts.contains(hostIp)){
                  break;
                }
              }
              Logger.i(MeasurementJsonConvertor.toJsonString(result));
              MeasurementResult[] mrArray = new MeasurementResult[1];
              mrArray[0] = result;
              return mrArray;
              
            }

          }
        }

      } catch (InterruptedException e) {
        e.printStackTrace();
      } catch (ExecutionException e) {
        e.printStackTrace();
      }



    }




    Logger.e("cannot perform traceroute to " + task.target);
    throw new MeasurementError("cannot perform traceroute to " + task.target);
  }



  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
    return TracerouteDesc.class;
  }

  @Override
  public String getType() {
    return TracerouteTask.TYPE;
  }

  @Override
  public String getDescriptor() {
    return DESCRIPTOR;
  }

  private void cleanUp(Process proc) {
    if (proc != null) {
      // destroy() closes all open streams
      proc.destroy();
    }
  }

  private HashSet<String> processPingOutput(BufferedReader br, String hostIp) throws IOException {
    HashSet<String> hostsAtThisDistance = new HashSet<String>();
    String line = null;
    while ((line = br.readLine()) != null) {
      if (line.startsWith("From")) {
        String ip = getHostIp(line);
        if (ip != null && ip.compareTo(hostIp) != 0) {
          Logger.d("IP: " + ip);
          hostsAtThisDistance.add(ip);
        }
      } else if (line.contains("time=")) {
        hostsAtThisDistance.add(hostIp);
      }
    }
    return hostsAtThisDistance;
  }

  /*
   * TODO(Wenjie): The current search for valid IPs assumes the IP string is not a proper substring
   * of the space-separated tokens. For more robust searching in case different outputs from ping
   * due to its different versions, we need to refine the search by testing weather any substring of
   * the tokens contains a valid IP
   */
  private String getHostIp(String line) {
    String[] tokens = line.split(" ");
    // In most cases, the second element in the array is the IP
    String tempIp = tokens[1];
    /**
     * In Android 4.3 or above, the second token of the result is like "192.168.1.1:". So we should
     * remove the last ":"
     */
    if (tempIp.endsWith(":")) {
      tempIp = tempIp.substring(0, tempIp.length() - 1);
    }
    if (isValidIpv4Addr(tempIp) || isValidIpv6Addr(tempIp)) {
      return tempIp;
    } else {
      for (int i = 0; i < tokens.length; i++) {
        if (i == 1) {
          // Examined already
          continue;
        } else {
          if (isValidIpv4Addr(tokens[i]) || isValidIpv6Addr(tokens[i])) {
            return tokens[i];
          }
        }
      }
    }

    return null;
  }

  // Tells whether the string is an valid IPv4 address
  private boolean isValidIpv4Addr(String ip) {
    String[] tokens = ip.split("\\.");
    if (tokens.length == 4) {
      for (int i = 0; i < 4; i++) {
        try {
          int val = Integer.parseInt(tokens[i]);
          if (val < 0 || val > 255) {
            return false;
          }
        } catch (NumberFormatException e) {
          Logger.d(ip + " is not a valid IPv4 address");
          return false;
        }
      }
      return true;
    }
    return false;
  }

  // Tells whether the string is an valid IPv6 address
  private boolean isValidIpv6Addr(String ip) {
    int max = Integer.valueOf("FFFF", 16);
    String[] tokens = ip.split("\\:");
    if (tokens.length <= 8) {
      for (int i = 0; i < tokens.length; i++) {
        try {
          // zeros might get grouped
          if (tokens[i].isEmpty())
            continue;
          int val = Integer.parseInt(tokens[i], 16);
          if (val < 0 || val > max) {
            return false;
          }
        } catch (NumberFormatException e) {
          Logger.d(ip + " is not a valid IPv6 address");
          return false;
        }
      }
      return true;
    }
    return false;
  }

  private class HopInfo {
    // The hosts at a given hop distance
    public HashSet<String> hosts;
    // The average RRT for this hop distance
    public double rtt;
    public int ttl;

    protected HopInfo(HashSet<String> hosts, double rtt, int ttl) {
      this.hosts = hosts;
      this.rtt = rtt;
      this.ttl = ttl;
    }
  }

  @Override
  public String toString() {
    TracerouteDesc desc = (TracerouteDesc) measurementDesc;
    return "[Traceroute]\n  Target: " + desc.target + "\n  Interval (sec): " + desc.intervalSec
        + "\n  Next run: " + desc.startTime;
  }



  // Measure the actual ping process execution time
  private class ProcWrapper extends Thread {
    public long duration = 0;
    private final Process process;
    private Integer exitStatus = null;

    private ProcWrapper(Process process) {
      this.process = process;
    }

    public void run() {
      try {
        long startTime = System.currentTimeMillis();
        exitStatus = process.waitFor();
        duration = System.currentTimeMillis() - startTime;
      } catch (InterruptedException e) {
        Logger.e("Traceroute thread gets interrupted");
      }
    }
  }


  class HopExecutor implements Callable {
    private int pingsPerHop;
    private String command;
    private String hostIp;
    private Process pingProc = null;
    private int ttl;

    public HopExecutor(int pingsPerHop, String command, String hostIp, int ttl) {
      this.pingsPerHop = pingsPerHop;
      this.command = command;
      this.ttl = ttl;
      this.hostIp = hostIp;
    }

    @Override
    public HopInfo call() {
      double rtt = 0;
      HashSet<String> hostsAtThisDistance = new HashSet<String>();
      try {

        int effectiveTask = 0;
        ExecutorService executor = Executors.newFixedThreadPool(pingsPerHop);
        ArrayList<Runnable> workers = new ArrayList<Runnable>();
        for (int i = 0; i < pingsPerHop; i++) {
          // Actual packet is 28 bytes larger than the size specified.
          // Three packets are sent in each direction
          // dataConsumed += (task.packetSizeByte + 28) * 2 * 3;//TODO

          pingProc = Runtime.getRuntime().exec(command);

          Runnable worker = new PingExecutor(pingProc, hostIp);
          executor.execute(worker);
          workers.add(worker);
        }

        executor.shutdown();
        try {
          executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          e.printStackTrace();
        }

        for (Runnable w : workers) {
          rtt += ((PingExecutor) w).getRtt();
          if (((PingExecutor) w).getRtt() != 0) {
            effectiveTask++;
            for (String h : ((PingExecutor) w).getHosts()) {
              hostsAtThisDistance.add(h);
            }
          }
        }

        rtt = (effectiveTask != 0) ? (rtt / effectiveTask) : -1;
        if (rtt == -1) {
          String Unreachablehost = "";
          for (int i = 0; i < pingsPerHop; i++) {
            Unreachablehost += "* ";
          }
          hostsAtThisDistance.add(Unreachablehost);
        }


      } catch (SecurityException e) {
        Logger.e("Does not have the permission to run ping on this device");
      } catch (IOException e) {
        Logger.e("The ping program cannot be executed");
        Logger.e(e.getMessage());
      } finally {
        cleanUp(pingProc);
      }

      return new HopInfo(hostsAtThisDistance, rtt, ttl);

    }

  }

  class PingExecutor implements Runnable {
    private Process proc;
    private double rtt;
    private String hostIp;
    private HashSet<String> hosts;

    public PingExecutor(Process proc, String hostIp) {
      this.proc = proc;
      rtt = 0;
      this.hostIp = hostIp;
      hosts = new HashSet<String>();
    }

    public double getRtt() {
      return rtt;
    }

    public HashSet<String> getHosts() {
      return hosts;
    }


    @Override
    public void run() {
      // Wait for process to finish
      // Enforce thread timeout if pingProc doesn't respond
      ProcWrapper procwrapper = new ProcWrapper(proc);
      procwrapper.start();
      try {
        long pingThreadTimeout = 5000;
        procwrapper.join(pingThreadTimeout);
        if (procwrapper.exitStatus == null)
          throw new TimeoutException();
      } catch (InterruptedException ex) {
        procwrapper.interrupt();
        Thread.currentThread().interrupt();
        Logger.e("Traceroute process gets interrupted");
        cleanUp(proc);
        return;
      } catch (TimeoutException e) {
        Logger.e("Traceroute process timeout");
        cleanUp(proc);
        return;
      }
      rtt += procwrapper.duration;


      // Grab the output of the process that runs the ping command
      InputStream is = proc.getInputStream();
      BufferedReader br = new BufferedReader(new InputStreamReader(is));
      /*
       * Process each line of the ping output and extracts the intermediate hops into
       * hostAtThisDistance
       */
      try {
        hosts = processPingOutput(br, hostIp);
      } catch (IOException e) {

        e.printStackTrace();
      }
      cleanUp(proc);
      // try {
      // Thread.sleep((long) (task.pingIntervalSec * 1000));
      // } catch (InterruptedException e) {
      // Logger.i("Sleep interrupted between ping intervals");
      // }

    }


  }

  @Override
  public long getDuration() {
    return this.duration - this.totalRunningTime;
  }

  @Override
  public void setDuration(long newDuration) {
    if (newDuration < 0) {
      this.duration = 0;
    } else {
      this.duration = newDuration;
    }
  }

  @Override
  public boolean pause() {
    pauseFlag = true;
    return true;
  }

  @Override
  public boolean stop() {
    stopFlag = true;
    // cleanUp(pingProc);TODO
    return true;
  }

  @Override
  public long getTotalRunningTime() {
    return this.totalRunningTime;
  }

  @Override
  public void updateTotalRunningTime(long duration) {
    this.totalRunningTime += duration;
  }

  /**
   * Based on counting the number of pings sent
   */
  @Override
  public long getDataConsumed() {
    return dataConsumed;
  }



}
