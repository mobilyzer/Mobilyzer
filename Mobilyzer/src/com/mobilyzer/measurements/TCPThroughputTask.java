//Copyright 2012 RobustNet Lab, University of Michigan. All Rights Reserved.

package com.mobilyzer.measurements;

import java.io.IOException;
import java.io.InvalidClassException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.Random;

import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MLabNS;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @author Haokun Luo
 * 
 * TCP Throughput is a measurement task for cellular network throughput.
 * 1. Uplink: the mobile device continuously sends packets with consistent 
 *    packet size. We send packets for a fixed amount of time, and we sample
 *    each throughput value at a smaller period. We use the median of all the 
 *    sampling result as the final measurement result. The result is calculated 
 *    at the server side, and send back to the device.
 * 2. Downlink: similar methodology as uplink. Only difference is that the
 *    device is receiving packets from the server, and calculate the result
 *    locally. 
 */
public class TCPThroughputTask extends MeasurementTask {
  // default constant here
  public static final String DESCRIPTOR = "TCP Speed Test";
  public static final int PORT_DOWNLINK = 6001;
  public static final int PORT_UPLINK = 6002;
  public static final int PORT_CONFIG = 6003;
  public static final String TYPE = "tcpthroughput";

  // Timing related
  public final int BUFFER_SIZE = 5000;
  public static final long DURATION_IN_SEC = 15;
  public final int KSEC = 1000;
  public static final long SAMPLE_PERIOD_IN_SEC = 1; 
  public static final long SLOW_START_PERIOD_IN_SEC = 5;
  public static final int TCP_TIMEOUT_IN_SEC = 30;
  // largest non-fragment packet size in LTE (uplink)
  public static final int THROUGHPUT_UP_PKT_SIZE_MAX = 1357;
  public static final int THROUGHPUT_UP_PKT_SIZE_MIN = 700;

  // Data related
  private final int KBYTE = 1024;
  private static final int DATA_LIMIT_MB_UP = 5; 
  private static final int DATA_LIMIT_MB_DOWN = 10;
  private boolean DATA_LIMIT_ON = true;
  private boolean DATA_LIMIT_EXCEEDED = false;
  private static final String UPLINK_FINISH_MSG = "*";

  private Context context = null;

  // helper variables 
  private int accumulativeSize = 0;
  private Random randStr = new Random();
  private ArrayList<Double> samplingResults = new ArrayList<Double>();
  //start time of each sampling period
  private long startSampleTime = 0;
  private String serverVersion = "";
  private long taskStartTime = 0;
  private double taskDuration = 0;
  //uplink accumulative data
  private int totalSendSize = 0;
  // downlink accumulative data
  private int totalRevSize = 0;

  private long duration;
  private TaskProgress taskProgress;
  private volatile boolean stopFlag;

  // class constructor
  public TCPThroughputTask(MeasurementDesc desc) {
    super(new TCPThroughputDesc(desc.key, desc.startTime, desc.endTime, 
      desc.intervalSec, desc.count, desc.priority, desc.contextIntervalSec,
      desc.parameters));
    this.taskProgress=TaskProgress.FAILED;
    this.stopFlag=false;
    this.duration=(long)(this.KSEC*
        ((TCPThroughputDesc)measurementDesc).duration_period_sec +
        ((TCPThroughputDesc)measurementDesc).slow_start_period_sec);
    Logger.i("Create new throughput task");
  }


  protected TCPThroughputTask(Parcel in) {
    super(in);
    taskProgress = (TaskProgress)in.readSerializable();
    stopFlag = in.readByte() != 0;
    duration = in.readLong();
  }

  public static final Parcelable.Creator<TCPThroughputTask> CREATOR =
      new Parcelable.Creator<TCPThroughputTask>() {
    public TCPThroughputTask createFromParcel(Parcel in) {
      return new TCPThroughputTask(in);
    }

    public TCPThroughputTask[] newArray(int size) {
      return new TCPThroughputTask[size];
    }
  };

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    super.writeToParcel(dest, flags);
    dest.writeSerializable(taskProgress);
    dest.writeByte((byte) (stopFlag ? 1 : 0));
    dest.writeLong(duration);
  }
  /**
   * There are seven parameters specifically for this experiment:
   * 1. data_limit_mb_up: uplink cellular network data limit
   * 2. data_limit_mb_down: downlink cellular network data limit
   * 3. duration_period_sec : downlink maximum experiment duration period
   * 4. pkt_size_up_bytes: the size each packet in the uplink
   * 5. sample_period_sec : the small interval to calculate current throughput result
   * 6. slow_start_period_sec : waiting period to avoid TCP slow start
   * 7. tcp_timeout_sec: TCP connection timeout
   */

  public static class TCPThroughputDesc extends MeasurementDesc {
    // declared parameters
    public double  data_limit_mb_up =
        TCPThroughputTask.DATA_LIMIT_MB_UP;
    public double  data_limit_mb_down =
        TCPThroughputTask.DATA_LIMIT_MB_DOWN;
    public boolean dir_up = false;
    public double  duration_period_sec =
        TCPThroughputTask.DURATION_IN_SEC;
    public int     pkt_size_up_bytes =
        TCPThroughputTask.THROUGHPUT_UP_PKT_SIZE_MAX;
    public double  sample_period_sec =
        TCPThroughputTask.SAMPLE_PERIOD_IN_SEC;
    public double  slow_start_period_sec =
        TCPThroughputTask.SLOW_START_PERIOD_IN_SEC;
    public String  target = null;
    public double  tcp_timeout_sec = TCPThroughputTask.TCP_TIMEOUT_IN_SEC;

    public TCPThroughputDesc(String key, Date startTime,
        Date endTime, double intervalSec, long count, 
        long priority, int contextIntervalSec, Map<String, String> params) 
        throws InvalidParameterException {
      super(TCPThroughputTask.TYPE, key, startTime, endTime, intervalSec, count,
        priority, contextIntervalSec, params);
      initializeParams(params);
      if (this.target == null || this.target.length() == 0) {
        throw new InvalidParameterException("TCPThroughputTask null target");
      }
    }

    protected TCPThroughputDesc(Parcel in) {
      super(in);
      data_limit_mb_up = in.readDouble();
      data_limit_mb_down = in.readDouble();
      dir_up = in.readByte() != 0;
      duration_period_sec = in.readDouble();
      pkt_size_up_bytes = in.readInt();
      sample_period_sec = in.readDouble();
      slow_start_period_sec = in.readDouble();
      target = in.readString();
      tcp_timeout_sec = in.readDouble();
    }

    public static final Parcelable.Creator<TCPThroughputDesc> CREATOR
    = new Parcelable.Creator<TCPThroughputDesc>() {
      public TCPThroughputDesc createFromParcel(Parcel in) {
        return new TCPThroughputDesc(in);
      }

      public TCPThroughputDesc[] newArray(int size) {
        return new TCPThroughputDesc[size];
      }
    };

    @Override
    public int describeContents() {
      return super.describeContents();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      super.writeToParcel(dest, flags);
      dest.writeDouble(data_limit_mb_up);
      dest.writeDouble(data_limit_mb_down);
      dest.writeByte((byte) (dir_up ? 1 : 0));
      dest.writeDouble(duration_period_sec);
      dest.writeInt(pkt_size_up_bytes);
      dest.writeDouble(sample_period_sec);
      dest.writeDouble(slow_start_period_sec);
      dest.writeString(target);
      dest.writeDouble(tcp_timeout_sec);
    }

    @Override
    protected void initializeParams(Map<String, String> params) {
      if (params == null) {
        return;
      }
      if ( (target = params.get("target")) == null ) {
        target = MLabNS.TARGET;
      }

      try {
        String readVal = null;
        if ((readVal = params.get("data_limit_mb_down")) != null &&
            readVal.length() > 0 && Integer.parseInt(readVal) > 0) {
          this.data_limit_mb_down = Double.parseDouble(readVal);
          if (this.data_limit_mb_down > TCPThroughputTask.DATA_LIMIT_MB_DOWN) {
            this.data_limit_mb_down = TCPThroughputTask.DATA_LIMIT_MB_DOWN;
          }
        }

        if ((readVal = params.get("data_limit_mb_up")) != null &&
            readVal.length() > 0 && Integer.parseInt(readVal) > 0) {
          this.data_limit_mb_up = Double.parseDouble(readVal);
          if (this.data_limit_mb_up > TCPThroughputTask.DATA_LIMIT_MB_UP) {
            this.data_limit_mb_up = TCPThroughputTask.DATA_LIMIT_MB_UP;
          }
        }
        if ((readVal = params.get("duration_period_sec")) != null &&
            readVal.length() > 0 && Integer.parseInt(readVal) > 0) {
          this.duration_period_sec = Double.parseDouble(readVal);
          if (this.duration_period_sec > TCPThroughputTask.DURATION_IN_SEC) {
            this.duration_period_sec = TCPThroughputTask.DURATION_IN_SEC;
          }
        }
        if ((readVal = params.get("pkt_size_up_bytes")) != null &&
            readVal.length() > 0 && Integer.parseInt(readVal) > 0) {
          this.pkt_size_up_bytes = Integer.parseInt(readVal);
          if (this.pkt_size_up_bytes > TCPThroughputTask.THROUGHPUT_UP_PKT_SIZE_MAX) {
            this.pkt_size_up_bytes = TCPThroughputTask.THROUGHPUT_UP_PKT_SIZE_MAX;
          }
          if (this.pkt_size_up_bytes < TCPThroughputTask.THROUGHPUT_UP_PKT_SIZE_MIN) {
            this.pkt_size_up_bytes = TCPThroughputTask.THROUGHPUT_UP_PKT_SIZE_MIN;
          }
        }
        if ((readVal = params.get("sample_period_sec")) != null &&
            readVal.length() > 0 && Integer.parseInt(readVal) > 0) {
          this.sample_period_sec = Double.parseDouble(readVal);
          if (this.sample_period_sec > TCPThroughputTask.DURATION_IN_SEC/2) {
            this.sample_period_sec = TCPThroughputTask.DURATION_IN_SEC/2;
          }
        }
        if ((readVal = params.get("slow_start_period_sec")) != null
            && readVal.length() > 0 && Integer.parseInt(readVal) > 0) {
          this.slow_start_period_sec = Double.parseDouble(readVal);
          if (this.slow_start_period_sec > TCPThroughputTask.DURATION_IN_SEC/2) {
            this.slow_start_period_sec = TCPThroughputTask.DURATION_IN_SEC/2;
          }
        }
        if ((readVal = params.get("tcp_timeout_sec")) != null &&
            readVal.length() > 0 && Integer.parseInt(readVal) > 0) {
          this.tcp_timeout_sec = Integer.parseInt(readVal)*1000;
          if (this.tcp_timeout_sec > TCPThroughputTask.TCP_TIMEOUT_IN_SEC) {
            this.tcp_timeout_sec = TCPThroughputTask.TCP_TIMEOUT_IN_SEC;
          }
        }
      } catch  (NumberFormatException e) {
        throw new InvalidParameterException("TCP Throughput Task invalid parameters.");
      }

      String dir = null;
      if ((dir = params.get("dir_up")) != null && dir.length() > 0) {
        if (dir.compareTo("Up") == 0 || dir.compareTo("true") == 0) {
          this.dir_up = true;
        }
      }
    }

    @Override
    public String getType() {
      return TCPThroughputTask.TYPE;
    }

    /**
     * Find the median value from a TCPThroughput JSON result string (already sorted)
     * Suppose N is the number of results. If N is odd, we pick the result with index
     * (N-1)/2. If N is even, we take the mean value between index N/2 and N/2-1
     * 
     * @return -1 fail to create result
     * @return median value result
     */
    public double calMedianSpeedFromTCPThroughputOutput(String outputInJSON) {
      if (outputInJSON == null || 
          outputInJSON.equals("") ||
          outputInJSON.equals("[]") ||
          outputInJSON.charAt(0) != '[' || 
          outputInJSON.charAt(outputInJSON.length()-1) != ']') {
        return -1;
      }

      String[] splitResult = outputInJSON.substring(1,
        outputInJSON.length()-1).split(",");
      int resultLen = splitResult.length;
      if (resultLen <= 0)
        return 0.0;
      double result = 0.0;
      if (resultLen % 2 == 0) {
        result = (Double.parseDouble(splitResult[resultLen / 2]) +
            Double.parseDouble(splitResult[resultLen / 2 - 1])) / 2;
      } else {
        result = Double.parseDouble(splitResult[(resultLen - 1) / 2]);
      }
      return result;
    }
  }

  /**
   * Make a deep cloning of the task
   */
  @Override
  public MeasurementTask clone() {
    MeasurementDesc desc = this.measurementDesc;
    TCPThroughputDesc newDesc = new TCPThroughputDesc(
      desc.key, desc.startTime, 
      desc.endTime, desc.intervalSec, desc.count, desc.priority,
      desc.contextIntervalSec, desc.parameters);
    return new TCPThroughputTask(newDesc);
  }

  @Override
  public String getType() {
    return TCPThroughputTask.TYPE;
  }

  @Override
  public String getDescriptor() {
    return TCPThroughputTask.DESCRIPTOR;
  }

  /** 
   * This will be printed to the device log console. Make sure it's well
   * structured and human readable
   */
  @Override
  public String toString() {
    TCPThroughputDesc desc = (TCPThroughputDesc) measurementDesc;
    String resp;

    if (desc.dir_up) {
      resp = "[TCP Uplink]\n";
    } else {
      resp = "[TCP Downlink]\n";
    }

    resp += " Target: " + desc.target + "\n  Interval (sec): " + 
        desc.intervalSec + "\n  Next run: " + desc.startTime;

    return resp;
  }

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
    return TCPThroughputDesc.class;
  }


  @Override
  public MeasurementResult[] call() throws MeasurementError {
    this.taskProgress=TaskProgress.FAILED;
    TCPThroughputDesc desc = (TCPThroughputDesc)measurementDesc;

    // Apply MLabNS lookup to fetch FQDN
    if (!desc.target.equals(MLabNS.TARGET)) {
      Logger.i("Not using MLab server!");
      throw new InvalidParameterException("Unknown target " + desc.target +
          " for TCPThroughput");
    }

    try {
      ArrayList<String> mlabResult = MLabNS.Lookup(context, "mobiperf");
      if (mlabResult.size() == 1) {
        desc.target = mlabResult.get(0);
      } else {
        throw new MeasurementError("Invalid MLabNS result");
      }
    } catch (InvalidParameterException e) {
      throw new MeasurementError(e.getMessage());
    }
    Logger.i("Setting target to: " + desc.target);

    PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();

    // reset the data limit if the phone is under Wifi
    if (phoneUtils.getNetwork().equals(phoneUtils.NETWORK_WIFI)) {
      Logger.i("Detect Wifi network");
      this.DATA_LIMIT_ON = false;
    }

    Logger.i("Running TCPThroughput on " + desc.target);
    try {
      // fetch server information
      if (!acquireServerConfig()) {
        throw new MeasurementError("Fail to acquire server configuration");
      }
      Logger.i("Server version is " + this.serverVersion);
      if (desc.dir_up == true) {
        uplink();
        if(stopFlag){
          throw new MeasurementError("Cancelled");
        }
        Logger.i("Uplink measurement result is:");
      }
      else {
        this.taskStartTime = System.currentTimeMillis();
        downlink();
        if(stopFlag){
          throw new MeasurementError("Cancelled");
        }
        Logger.i("Downlink measurement result is:");
      }
      this.taskProgress=TaskProgress.COMPLETED;
    } catch (MeasurementError e) {
      throw e;
    } catch (IOException e) {
      Logger.e("Error close the socket for " + desc.type);
      throw new MeasurementError("Error close the socket for " + desc.type);
    } catch (InterruptedException e) {
      Logger.e("Interrupted captured");
      throw new MeasurementError("Task gets interrrupted");
    }

    MeasurementResult result = new MeasurementResult(
      phoneUtils.getDeviceInfo().deviceId,
      phoneUtils.getDeviceProperty(this.getKey()), TCPThroughputTask.TYPE,
      System.currentTimeMillis() * 1000, taskProgress,
      this.measurementDesc);
    // TODO (Haokun): add more results if necessary
    result.addResult("tcp_speed_results", this.samplingResults);
    result.addResult("data_limit_exceeded", this.DATA_LIMIT_EXCEEDED);
    result.addResult("duration", this.taskDuration);
    result.addResult("server_version", this.serverVersion);
    Logger.i(MeasurementJsonConvertor.toJsonString(result));
    MeasurementResult[] mrArray= new MeasurementResult[1];
    mrArray[0]=result;
    return mrArray;
  }

  /*****************************************************************
   * Core measurement functions definitions
   *****************************************************************
   * acquire server configuration information
   * 1) m-lab slice version
   * 
   * @return: true -- successful acquire data from M-Lab slice
   * @return: false -- failure to acquire data from M-Lab slice
   */
  private boolean acquireServerConfig() throws MeasurementError, IOException,
  InterruptedException {
    Socket tcpSocket = null;
    InputStream iStream = null;
    boolean result = false;
    try {
      tcpSocket = new Socket();
      buildUpSocket(tcpSocket, ((TCPThroughputDesc)measurementDesc).target, 
        TCPThroughputTask.PORT_CONFIG);
      iStream = tcpSocket.getInputStream();
    } catch (IOException e) {
      throw new MeasurementError("Error open uplink socket at " + 
          ((TCPThroughputDesc)measurementDesc).target + 
          " with port " +
          TCPThroughputTask.PORT_CONFIG); 
    }

    try {
      // read from server side configuration
      byte [] resultMsg = new byte[this.BUFFER_SIZE];
      int resultMsgLen = iStream.read(resultMsg, 0, resultMsg.length);
      if (resultMsgLen > 0) {
        // TODO (Haokun): Maybe switch to JSON for multiple acquired data 
        //               currently use one double number
        this.serverVersion = new String(resultMsg).substring(0, resultMsgLen);
        result = true;
      }
    } catch (IOException e) {
      throw new MeasurementError("Error to acquire configuration from " +
          ((TCPThroughputDesc)measurementDesc).target);
    } finally {
      iStream.close();
      tcpSocket.close();
      Logger.i("Close server Config socket");
    }
    return result;
  }

  /* Uplink measurement task
   * @throws IOException 
   * @throws InterruptedException 
   */
  private void uplink() throws MeasurementError, IOException, InterruptedException {
    Logger.i("Start uplink task on " + ((TCPThroughputDesc)measurementDesc).target);
    Socket tcpSocket = null;
    InputStream iStream = null;
    OutputStream oStream = null;

    try {
      tcpSocket = new Socket();
      buildUpSocket(tcpSocket, ((TCPThroughputDesc)measurementDesc).target, 
        TCPThroughputTask.PORT_UPLINK);
      oStream = tcpSocket.getOutputStream();
      iStream = tcpSocket.getInputStream();
    } catch (IOException e){
      e.printStackTrace();
      throw new MeasurementError("Error open uplink socket at " + 
          ((TCPThroughputDesc)measurementDesc).target + 
          " with port " +
          TCPThroughputTask.PORT_UPLINK);
    }

    long startTime = System.currentTimeMillis();
    long endTime = startTime;
    int  data_limit_byte_up =
        (int)(((TCPThroughputDesc)measurementDesc).data_limit_mb_up
        *this.KBYTE*this.KBYTE);
    byte[] uplinkBuffer =
        new byte[((TCPThroughputDesc)measurementDesc).pkt_size_up_bytes];
    this.genRandomByteArray(uplinkBuffer);
    try {

      long totalDuration = (long)(this.KSEC*
          ((TCPThroughputDesc)measurementDesc).duration_period_sec +
          ((TCPThroughputDesc)measurementDesc).slow_start_period_sec);
      do {

        if(stopFlag){
          throw new MeasurementError("Cancelled");
        }

        oStream.write(uplinkBuffer, 0, uplinkBuffer.length);
        oStream.flush();
        endTime = System.currentTimeMillis();

        this.totalSendSize += ((TCPThroughputDesc)measurementDesc).pkt_size_up_bytes;
        if (this.DATA_LIMIT_ON &&
            this.totalSendSize >= data_limit_byte_up) {
          Logger.i("Detect uplink exceeding limitation " +
              (double)((TCPThroughputDesc)measurementDesc).data_limit_mb_up + " MB");
          this.DATA_LIMIT_EXCEEDED = true;
          break;
        }

        // propagate every quarter

      } while ((endTime - startTime) < totalDuration);

      // convert into seconds
      this.taskDuration = (double)(endTime - startTime) / 1000.0;
      Logger.i("Uplink total data comsumption is " + 
          (double)this.totalSendSize/(1024*1024) + " MB");
      // send last message with special content
      uplinkBuffer = TCPThroughputTask.UPLINK_FINISH_MSG.getBytes();
      oStream.write(uplinkBuffer, 0, uplinkBuffer.length);
      oStream.flush();
      // read from server side results
      byte [] resultMsg = new byte[this.BUFFER_SIZE];
      int resultMsgLen = iStream.read(resultMsg, 0, resultMsg.length);
      if (resultMsgLen > 0) {
        String resultMsgStr = new String(resultMsg).substring(0, resultMsgLen);
        // Sample result string is "1111.11#2222.22#3333.33";
        Logger.i("Uplink result from server is " + resultMsgStr);
        String [] tps_result_str = resultMsgStr.split("#");
        double sampleResult;
        for (int i = 0; i < tps_result_str.length; i++) {
          sampleResult = Double.valueOf(tps_result_str[i]);
          this.samplingResults = this.insertWithOrder(this.samplingResults,
            sampleResult);
        }
      }
      Logger.i("Total number of sampling result is " + this.samplingResults.size());

    } catch (OutOfMemoryError e) {
      throw new MeasurementError("Detect out of memory during Uplink task.");
    } catch (IOException e) {
      throw new MeasurementError("Error to send/receive data to " +
          ((TCPThroughputDesc)measurementDesc).target);
    } finally {
      iStream.close();
      oStream.close();
      tcpSocket.close();
      Logger.i("Close uplink socket");
    }
  }

  /**
   * Downlink measurement task
   */
  private void downlink() throws MeasurementError, IOException {
    Logger.i("Start downlink task on " +
        ((TCPThroughputDesc)measurementDesc).target);
    Socket tcpSocket = null;
    InputStream iStream = null;
    try {
      tcpSocket = new Socket();
      buildUpSocket(tcpSocket, ((TCPThroughputDesc)measurementDesc).target,
        TCPThroughputTask.PORT_DOWNLINK);
      iStream = tcpSocket.getInputStream();
    } catch (IOException i) {
      Logger.e("Downlink socket opening error" + i.getCause().toString());
      throw new MeasurementError("Error to open downlink socket at " +
          ((TCPThroughputDesc)measurementDesc).target +
          " with port " + 
          TCPThroughputTask.PORT_DOWNLINK);
    }
    try {
      int read_bytes = 0;

      int data_limit_byte_down = (int)(this.KBYTE*this.KBYTE*
          ((TCPThroughputDesc)measurementDesc).data_limit_mb_down);
      byte[] buffer = new byte[this.BUFFER_SIZE];
      long totalDuration = (long)(this.KSEC*
          ((TCPThroughputDesc)measurementDesc).duration_period_sec + 
          ((TCPThroughputDesc)measurementDesc).slow_start_period_sec);
      do {

        if(stopFlag){
          throw new MeasurementError("Cancelled");
        }

        read_bytes = iStream.read(buffer, 0, buffer.length);
        updateSize(read_bytes);

        this.totalRevSize += read_bytes;
        if (this.DATA_LIMIT_ON &&
            this.totalRevSize >= data_limit_byte_down) {
          Logger.i("Detect downlink data limitation exceed with " +
              ((TCPThroughputDesc)measurementDesc).data_limit_mb_down + " MB");
          this.DATA_LIMIT_EXCEEDED = true;
          break;
        }


      } while (read_bytes >= 0);

      // convert milliseconds to seconds
      this.taskDuration = (System.currentTimeMillis() - 
          (double) this.taskStartTime) / 1000.0;
      Logger.i("Total download data is " +
          (double)this.totalRevSize/(1024*1024) + " MB");
      Logger.i("Total number of sampling result is " +
          this.samplingResults.size());

    } catch (OutOfMemoryError e) {
      throw new MeasurementError("Detect out of memory at Downlink task.");
    } catch  (IOException e) {
      throw new MeasurementError("Error to receive data from " +
          ((TCPThroughputDesc)measurementDesc).target);
    } finally {
      iStream.close();
      tcpSocket.close();
      Logger.i("Close downlink socket");
    }
  }

  /*****************************************************************
   * Helper functions
   *****************************************************************
   * update the total received packet size
   * @param time period increment
   */
  private void updateSize(int delta) {
    double gtime = System.currentTimeMillis() - this.taskStartTime;
    //ignore slow start
    if (gtime<((TCPThroughputDesc)measurementDesc).slow_start_period_sec*this.KSEC)
      return;
    if (this.startSampleTime == 0) {
      this.startSampleTime = System.currentTimeMillis();
      this.accumulativeSize = 0;
    }
    this.accumulativeSize += delta;
    double time = System.currentTimeMillis() - this.startSampleTime;
    if (time < ((TCPThroughputDesc)measurementDesc).sample_period_sec*this.KSEC) {
      return;
    } else {
      double throughput = (double)this.accumulativeSize * 8.0 / time;
      this.samplingResults = this.insertWithOrder(this.samplingResults, throughput);
      this.accumulativeSize = 0;
      this.startSampleTime = System.currentTimeMillis();
    }  
  }

  private void buildUpSocket(Socket tcpSocket, String hostname, int portNum)
      throws IOException {
    TCPThroughputDesc desc = (TCPThroughputDesc) measurementDesc;
    SocketAddress remoteAddr = new InetSocketAddress(hostname, portNum);
    tcpSocket.connect(remoteAddr, (int)desc.tcp_timeout_sec*this.KSEC);
    tcpSocket.setSoTimeout((int)desc.tcp_timeout_sec*this.KSEC);
    tcpSocket.setTcpNoDelay(true);
  }

  private void genRandomByteArray(byte[] byteArray) {
    for (int i = 0; i < byteArray.length; i++) {
      byteArray[i] = (byte)('a' + randStr.nextInt(26));
    }
  }

  // insert element with ascending order, i.e. insertion sort
  private ArrayList<Double> insertWithOrder(ArrayList<Double> array, double item) {
    int i;
    for (i = 0; i < array.size(); i++ ) {
      if (item < array.get(i)) {
        break;
      } 
    }
    array.add(i,item);
    return array;
  }

  @Override
  public long getDuration() {
    return this.duration;
  }

  @Override
  public void setDuration(long newDuration) {
    if(newDuration<0){
      this.duration=0;
    }else{
      this.duration=newDuration;
    }
  }

  @Override
  public boolean stop() {
    stopFlag=true;
    return true;
  }
  
  /**
   * Based on the measured total data sent and received, the same returned as
   * a measurement result
   */
  @Override
  public long getDataConsumed() {
    return totalSendSize + totalRevSize;
  }
}
