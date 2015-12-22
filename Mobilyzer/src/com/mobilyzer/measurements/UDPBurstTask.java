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

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

import com.mobilyzer.Config;
import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MLabNS;
import com.mobilyzer.util.PhoneUtils;

/**
 * 
 * UDPBurstTask provides two types of measurements, Burst Up and Burst Down, described next.
 * 
 * 1. UDPBurst Up: the device sends sends a burst of UDPBurstCount UDP packets and waits for a
 * response from the server that includes the number of packets that the server received
 * 
 * 2. UDPBurst Down: the device sends a request to a remote server on a UDP port and the server
 * responds by sending a burst of UDPBurstCount packets. The size of each packet is packetSizeByte
 */
public class UDPBurstTask extends MeasurementTask {
  public static final String TYPE = "udp_burst";
  public static final String DESCRIPTOR = "UDP Burst";

  private static final int DEFAULT_PORT = 31341;

  /**
   * Min packet size = (int type) + (int burstCount) + (int packetNum) + (int intervalNum) + (long
   * timestamp) + (int packetSize) + (int seq) + (int udpInterval) = 36
   */
  private static final int MIN_PACKETSIZE = 36;
  // Leave enough margin for min MTU in the link and IP options
  private static final int MAX_PACKETSIZE = 500;
  private static final int DEFAULT_UDP_PACKET_SIZE = 100;
  /**
   * Default number of packets to be sent
   */
  private static final int DEFAULT_UDP_BURST = 16;
  private static final int MAX_BURSTCOUNT = 100;
  /**
   * TODO(Hongyi): Interval between packets in millisecond level seems too long for regular UDP
   * transmission. Microsecond level may be better. Need Discussion
   */
  private static final int DEFAULT_UDP_INTERVAL = 1;
  private static final int MAX_INTERVAL = 1;

  // TODO(Hongyi): choose a proper timeout period
  private static final int RCV_UP_TIMEOUT = 2000; // round-trip delay, in msec.
  private static final int RCV_DOWN_TIMEOUT = 1000; // one-way delay, in msec

  private static final int PKT_ERROR = 1;
  private static final int PKT_RESPONSE = 2;
  private static final int PKT_DATA = 3;
  private static final int PKT_REQUEST = 4;

  private String targetIp = null;
  private Context context = null;

  private static int seq = 1;
  private long duration;
  private TaskProgress taskProgress;
  private volatile boolean stopFlag;
  
  // Track data consumption for this task to avoid exceeding user's limit
  private long dataConsumed;


  /**
   * Encode UDP specific parameters, along with common parameters inherited from MeasurementDesc
   * 
   */
  public static class UDPBurstDesc extends MeasurementDesc {
    // Declare static parameters specific to SampleMeasurement here

    public int packetSizeByte = UDPBurstTask.DEFAULT_UDP_PACKET_SIZE;
    public int udpBurstCount = UDPBurstTask.DEFAULT_UDP_BURST;
    public int dstPort = UDPBurstTask.DEFAULT_PORT;
    public String target = null;
    public boolean dirUp = false;
    public int udpInterval = UDPBurstTask.DEFAULT_UDP_INTERVAL;

    public UDPBurstDesc(String key, Date startTime, Date endTime, double intervalSec, long count,
        long priority, int contextIntervalSec, Map<String, String> params)
        throws InvalidParameterException {
      super(UDPBurstTask.TYPE, key, startTime, endTime, intervalSec, count, priority,
          contextIntervalSec, params);
      initializeParams(params);
      if (this.target == null || this.target.length() == 0) {
        throw new InvalidParameterException("UDPBurstTask null target");
      }
    }

    /**
     * There are three UDP specific parameters:
     * 
     * 1. "direction": "up" if this is an uplink measurement. or "down" otherwise 2. "packet_burst":
     * how many packets should a up/down burst have 3. "packet_size_byte": the size of each packet
     * in bytes
     */
    @Override
    protected void initializeParams(Map<String, String> params) {
      if (params == null) {
        return;
      }

      if ((target = params.get("target")) == null) {
        this.target = MLabNS.TARGET;
      }

      try {
        String val = null;

        if ((val = params.get("dst_port")) != null && val.length() > 0 && Integer.parseInt(val) > 0) {
          this.dstPort = Integer.parseInt(val);
        }
        if ((val = params.get("packet_size_byte")) != null && val.length() > 0
            && Integer.parseInt(val) > 0) {
          this.packetSizeByte = Integer.parseInt(val);
          if (this.packetSizeByte < MIN_PACKETSIZE) {
            this.packetSizeByte = MIN_PACKETSIZE;
          }
          if (this.packetSizeByte > MAX_PACKETSIZE) {
            this.packetSizeByte = MAX_PACKETSIZE;
          }
        }
        if ((val = params.get("packet_burst")) != null && val.length() > 0
            && Integer.parseInt(val) > 0) {
          this.udpBurstCount = Integer.parseInt(val);
          if (this.udpBurstCount > MAX_BURSTCOUNT) {
            this.udpBurstCount = MAX_BURSTCOUNT;
          }
        }
        if ((val = params.get("udp_interval")) != null && val.length() > 0
            && Integer.parseInt(val) >= 0) {
          this.udpInterval = Integer.parseInt(val);
          if (this.udpInterval > MAX_INTERVAL) {
            this.udpInterval = MAX_INTERVAL;
          }
        }
      } catch (NumberFormatException e) {
        throw new InvalidParameterException("UDPTask invalid params");
      }

      String dir = null;
      if ((dir = params.get("direction")) != null && dir.length() > 0) {
        if (dir.compareToIgnoreCase("Up") == 0) {
          this.dirUp = true;
        }
      }
    }

    @Override
    public String getType() {
      return UDPBurstTask.TYPE;
    }

    protected UDPBurstDesc(Parcel in) {
      super(in);
      packetSizeByte = in.readInt();
      udpBurstCount = in.readInt();
      dstPort = in.readInt();
      target = in.readString();
      dirUp = in.readByte() != 0;
      udpInterval = in.readInt();
    }

    public static final Parcelable.Creator<UDPBurstDesc> CREATOR =
        new Parcelable.Creator<UDPBurstDesc>() {
          public UDPBurstDesc createFromParcel(Parcel in) {
            return new UDPBurstDesc(in);
          }

          public UDPBurstDesc[] newArray(int size) {
            return new UDPBurstDesc[size];
          }
        };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      super.writeToParcel(dest, flags);
      dest.writeInt(packetSizeByte);
      dest.writeInt(udpBurstCount);
      dest.writeInt(dstPort);
      dest.writeString(target);
      dest.writeByte((byte) (dirUp ? 1 : 0));
      dest.writeInt(udpInterval);
    }

  }

  @SuppressWarnings("rawtypes")
  public static Class getDescClass() throws InvalidClassException {
    return UDPBurstDesc.class;
  }

  public UDPBurstTask(MeasurementDesc desc) {
    super(new UDPBurstDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec, desc.count,
        desc.priority, desc.contextIntervalSec, desc.parameters));
    this.taskProgress = TaskProgress.FAILED;
    this.stopFlag = false;
    this.duration = Config.DEFAULT_UDPBURST_DURATION;
    this.dataConsumed = 0;
  }

  protected UDPBurstTask(Parcel in) {
    super(in);
    taskProgress = (TaskProgress) in.readSerializable();
    stopFlag = in.readByte() != 0;
    duration = in.readLong();
    dataConsumed = in.readLong();
  }

  public static final Parcelable.Creator<UDPBurstTask> CREATOR =
      new Parcelable.Creator<UDPBurstTask>() {
        public UDPBurstTask createFromParcel(Parcel in) {
          return new UDPBurstTask(in);
        }

        public UDPBurstTask[] newArray(int size) {
          return new UDPBurstTask[size];
        }
      };

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    super.writeToParcel(dest, flags);
    dest.writeSerializable(taskProgress);
    dest.writeByte((byte) (stopFlag ? 1 : 0));
    dest.writeLong(duration);
    dest.writeLong(dataConsumed);
  }

  /**
   * Make a deep cloning of the task
   */
  @Override
  public MeasurementTask clone() {
    MeasurementDesc desc = this.measurementDesc;
    UDPBurstDesc newDesc =
        new UDPBurstDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec, desc.count,
            desc.priority, desc.contextIntervalSec, desc.parameters);
    return new UDPBurstTask(newDesc);
  }

  /**
   * Opens a datagram (UDP) socket
   * 
   * @return a datagram socket used for sending/receiving
   * @throws MeasurementError if an error occurs
   */
  private DatagramSocket openSocket() throws MeasurementError {
    DatagramSocket sock = null;

    // Open datagram socket
    try {
      sock = new DatagramSocket();
    } catch (SocketException e) {
      throw new MeasurementError("Socket creation failed");
    }

    return sock;
  }

  /**
   * @author Hongyi Yao (hyyao@umich.edu) This class encapsulates the results of UDP burst
   *         measurement
   */
  private class UDPResult {
    public int packetCount;
    public double outOfOrderRatio;
    public long jitter;

    public UDPResult() {
      packetCount = 0;
      outOfOrderRatio = 0.0;
      jitter = 0L;
    }
  }

  /**
   * @author Hongyi Yao (hyyao@umich.edu) This class calculates the out-of-order ratio and delay
   *         jitter in the array of received UDP packets
   */
  private class MetricCalculator {
    private int maxPacketNum;
    private ArrayList<Long> offsetedDelayList;
    private int packetCount;
    private int outOfOrderCount;

    public MetricCalculator(int burstSize) {
      maxPacketNum = -1;
      offsetedDelayList = new ArrayList<Long>();
      packetCount = 0;
      outOfOrderCount = 0;
    }

    /**
     * Out-of-order packets is defined as arriving packets with sequence numbers smaller than their
     * predecessors.
     * 
     * @param packetNum: packet number in burst sequence
     * @param timestamp: estimated one-way delay(contains clock offset)
     */
    public void addPacket(int packetNum, long timestamp) {
      if (packetNum > maxPacketNum) {
        maxPacketNum = packetNum;
      } else {
        outOfOrderCount++;
      }
      offsetedDelayList.add(System.currentTimeMillis() - timestamp);
      packetCount++;
    }

    /**
     * Out-of-order ratio is defined as the ratio between the number of out-of-order packets and the
     * total number of packets.
     * 
     * @return the inversion number of the current UDP burst
     */
    public double calculateOutOfOrderRatio() {
      if (packetCount != 0) {
        return (double) outOfOrderCount / packetCount;
      } else {
        return 0.0;
      }
    }

    /**
     * Calculate jitter as the standard deviation of one-way delays[RFC3393] We can assume the clock
     * offset between server and client is constant in a short period(several milliseconds) since
     * typical oscillators have no more than 100ppm of frequency error , then it will be cancelled
     * out during the calculation process
     * 
     * @return the jitter of UDP burst
     */
    public long calculateJitter() {
      if (packetCount > 1) {
        double offsetedDelay_mean = 0;
        for (long offsetedDelay : offsetedDelayList) {
          offsetedDelay_mean += (double) offsetedDelay / packetCount;
        }

        double jitter = 0;
        for (long offsetedDelay : offsetedDelayList) {
          jitter += ((double) offsetedDelay - offsetedDelay_mean)
              * ((double) offsetedDelay - offsetedDelay_mean) / (packetCount - 1);
        }
        jitter = Math.sqrt(jitter);

        return (long) jitter;
      } else {
        return 0;
      }
    }
  }
  /**
   * @author Hongyi Yao (hyyao@umich.edu) A helper structure for packing and unpacking network
   *         message
   */
  private class UDPPacket {
    public int type;
    public int burstCount;
    public int packetNum;
    public int outOfOrderNum;
    // Data packet: local timestamp
    // Response packet: jitter
    public long timestamp;
    public int packetSize;
    public int seq;
    public int udpInterval;

    /**
     * Create an empty structure
     * @param cliId corresponding client identifier
     */
    public UDPPacket() {}

    /**
     * Unpack received message and fill the structure
     * 
     * @param cliId corresponding client identifier
     * @param rawdata network message
     * @throws MeasurementError stream reader failed
     */
    public UDPPacket(byte[] rawdata) throws MeasurementError {
      ByteArrayInputStream byteIn = new ByteArrayInputStream(rawdata);
      DataInputStream dataIn = new DataInputStream(byteIn);

      try {
        type = dataIn.readInt();
        burstCount = dataIn.readInt();
        packetNum = dataIn.readInt();
        outOfOrderNum = dataIn.readInt();
        timestamp = dataIn.readLong();
        packetSize = dataIn.readInt();
        seq = dataIn.readInt();
        udpInterval = dataIn.readInt();
      } catch (IOException e) {
        throw new MeasurementError("Fetch payload failed! " + e.getMessage());
      }

      try {
        byteIn.close();
      } catch (IOException e) {
        throw new MeasurementError("Error closing inputstream!");
      }
    }

    /**
     * Pack the structure to the network message
     * 
     * @return the network message in byte[]
     * @throws MeasurementError stream writer failed
     */
    public byte[] getByteArray() throws MeasurementError {

      ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
      DataOutputStream dataOut = new DataOutputStream(byteOut);

      try {
        dataOut.writeInt(type);
        dataOut.writeInt(burstCount);
        dataOut.writeInt(packetNum);
        dataOut.writeInt(outOfOrderNum);
        dataOut.writeLong(timestamp);
        dataOut.writeInt(packetSize);
        dataOut.writeInt(seq);
        dataOut.writeInt(udpInterval);
      } catch (IOException e) {
        throw new MeasurementError("Create rawpacket failed! " + e.getMessage());
      }

      byte[] rawPacket = byteOut.toByteArray();

      try {
        byteOut.close();
      } catch (IOException e) {
        throw new MeasurementError("Error closing outputstream!");
      }
      return rawPacket;
    }

  }

  /**
   * Opens a Datagram socket to the server included in the UDPDesc and sends a burst of
   * UDPBurstCount packets, each of size packetSizeByte.
   * 
   * @return a Datagram socket that can be used to receive the server's response
   * 
   * @throws MeasurementError if an error occurred.
   */
  private DatagramSocket sendUpBurst() throws MeasurementError {
    UDPBurstDesc desc = (UDPBurstDesc) measurementDesc;
    InetAddress addr = null;
    // Resolve the server's name
    try {
      addr = InetAddress.getByName(desc.target);
      dataConsumed+=DnsLookupTask.AVG_DATA_USAGE_BYTE;
      targetIp = addr.getHostAddress();
    } catch (UnknownHostException e) {
      throw new MeasurementError("Unknown host " + desc.target);
    }

    DatagramSocket sock = null;
    sock = openSocket();

    UDPPacket dataPacket = new UDPPacket();
    // Send burst
    for (int i = 0; i < desc.udpBurstCount; i++) {
      if (stopFlag) {
        throw new MeasurementError("Cancelled");
      }

      dataPacket.type = UDPBurstTask.PKT_DATA;
      dataPacket.burstCount = desc.udpBurstCount;
      dataPacket.packetNum = i;
      dataPacket.timestamp = System.currentTimeMillis();
      dataPacket.packetSize = desc.packetSizeByte;
      dataPacket.seq = seq;
      // Flatten UDP packet
      byte[] data = dataPacket.getByteArray();

      DatagramPacket packet = new DatagramPacket(data, data.length, addr, desc.dstPort);

      try {
        sock.send(packet);
        dataConsumed+=packet.getLength();
      } catch (IOException e) {
        sock.close();
        throw new MeasurementError("Error sending " + desc.target);
      }
      Logger.i("Sent packet pnum:" + i + " to " + desc.target + ": " + targetIp);
      // Sleep udpInterval millisecond
      try {
        Thread.sleep(desc.udpInterval);
        Logger.i("UDP Burst sleep " + desc.udpInterval + "ms");
      } catch (InterruptedException e) {
        Logger.e("UDPBurst -> sendUpBurst got interrupted");
        return null;
      }
    } // for()
    return sock;
  }

  /**
   * Receive a response from the server after the burst of uplink packets was sent, parse it, and
   * return the number of packets the server received.
   * 
   * @param sock the socket used to receive the server's response
   * @return the number of packets the server received
   * 
   * @throws MeasurementError if an error or a timeout occurs
   */
  private UDPResult recvUpResponse(DatagramSocket sock) throws MeasurementError {
    UDPBurstDesc desc = (UDPBurstDesc) measurementDesc;

    UDPResult udpResult = new UDPResult();
    // Receive response
    Logger.i("Waiting for UDP response from " + desc.target + ": " + targetIp);

    byte buffer[] = new byte[UDPBurstTask.MIN_PACKETSIZE];
    DatagramPacket recvpacket = new DatagramPacket(buffer, buffer.length);

    if (stopFlag) {
      throw new MeasurementError("Cancelled");
    }

    try {
      sock.setSoTimeout(RCV_UP_TIMEOUT);
      sock.receive(recvpacket);
    } catch (SocketException e1) {
      sock.close();
      throw new MeasurementError("Timed out reading from " + desc.target);
    } catch (IOException e) {
      sock.close();
      throw new MeasurementError("Error reading from " + desc.target);
    }
    // Reconstruct UDP packet from flattened network data
    UDPPacket responsePacket = new UDPPacket(recvpacket.getData());
    dataConsumed+=recvpacket.getLength();

    if (responsePacket.type == PKT_RESPONSE) {
      // Received seq number must be same with client seq
      if (responsePacket.seq != seq) {
        Logger.e("Error: Server send response packet with different seq, old " + seq + " => new "
            + responsePacket.seq);
      }

      Logger.i("Recv UDP resp from " + desc.target + " type:" + responsePacket.type + " burst:"
          + responsePacket.burstCount + " pktnum:" + responsePacket.packetNum
          + " out_of_order_num: " + responsePacket.outOfOrderNum + " jitter: "
          + responsePacket.timestamp);

      udpResult.packetCount = responsePacket.packetNum;
      udpResult.outOfOrderRatio = (double) responsePacket.outOfOrderNum / responsePacket.packetNum;
      udpResult.jitter = responsePacket.timestamp;
      return udpResult;
    } else {
      throw new MeasurementError("Error: not a response packet! seq: " + seq);
    }
  }

  /**
   * Opens a datagram socket to the server in the UDPDesc and requests the server to send a burst of
   * UDPBurstCount packets, each of packetSizeByte bytes.
   * 
   * @return the datagram socket used to receive the server's burst
   * @throws MeasurementError if an error occurs
   */
  private DatagramSocket sendDownRequest() throws MeasurementError {
    UDPBurstDesc desc = (UDPBurstDesc) measurementDesc;
    DatagramPacket packet;
    InetAddress addr = null;

    if (stopFlag) {
      throw new MeasurementError("Cancelled");
    }

    // Resolve the server's name
    try {
      addr = InetAddress.getByName(desc.target);
      targetIp = addr.getHostAddress();
    } catch (UnknownHostException e) {
      throw new MeasurementError("Unknown host " + desc.target);
    }

    DatagramSocket sock = null;
    sock = openSocket();

    Logger.i("Requesting UDP burst:" + desc.udpBurstCount + " pktsize: " + desc.packetSizeByte
        + " to " + desc.target + ": " + targetIp);

    UDPPacket requestPacket = new UDPPacket();
    requestPacket.type = PKT_REQUEST;
    requestPacket.burstCount = desc.udpBurstCount;
    requestPacket.packetSize = desc.packetSizeByte;
    requestPacket.seq = seq;
    requestPacket.udpInterval = desc.udpInterval;
    // Flatten UDP packet
    byte[] data = requestPacket.getByteArray();
    packet = new DatagramPacket(data, data.length, addr, desc.dstPort);


    try {
      dataConsumed += packet.getLength();
      sock.send(packet);
    } catch (IOException e) {
      sock.close();
      throw new MeasurementError("Error closing Output Stream to:" + desc.target);
    }
    return sock;
  }

  /**
   * Receives a burst from the remote server and counts the number of packets that were received.
   * 
   * @param sock the datagram socket that can be used to receive the server's burst
   * 
   * @return the number of packets received from the server
   * @throws MeasurementError if an error occurs
   */
  private UDPResult recvDownResponse(DatagramSocket sock) throws MeasurementError {
    int pktRecv = 0;
    UDPBurstDesc desc = (UDPBurstDesc) measurementDesc;

    // Receive response
    Logger.i("Waiting for UDP burst from " + desc.target);
    // Reconstruct UDP packet from flattened network data
    byte buffer[] = new byte[desc.packetSizeByte];
    DatagramPacket recvpacket = new DatagramPacket(buffer, buffer.length);
    MetricCalculator metricCalculator = new MetricCalculator(desc.udpBurstCount);
    for (int i = 0; i < desc.udpBurstCount; i++) {
      if (stopFlag) {
        throw new MeasurementError("Cancelled");
      }
      try {
        sock.setSoTimeout(RCV_DOWN_TIMEOUT);
        sock.receive(recvpacket);
      } catch (IOException e) {
        Logger.e("Timeout at round " + i);
        break;
      }

      UDPPacket dataPacket = new UDPPacket(recvpacket.getData());
      dataConsumed+=recvpacket.getLength();
      if (dataPacket.type == UDPBurstTask.PKT_DATA) {
        // Received seq number must be same with client seq
        if (dataPacket.seq != seq) {
          String err =
              "Server send data packets with different seq, old " + seq + " => new "
                  + dataPacket.seq;
          Logger.e(err);
          throw new MeasurementError(err);
        }

        Logger.i("Recv UDP response from " + desc.target + " type:" + dataPacket.type + " burst:"
            + dataPacket.burstCount + " pktnum:" + dataPacket.packetNum + " timestamp:"
            + dataPacket.timestamp);

        pktRecv++;
        metricCalculator.addPacket(dataPacket.packetNum, dataPacket.timestamp);
      } else {
        throw new MeasurementError("Error closing input stream from " + desc.target);
      }
    } // for()

    UDPResult udpResult = new UDPResult();
    udpResult.packetCount = pktRecv;
    udpResult.outOfOrderRatio = metricCalculator.calculateOutOfOrderRatio();
    udpResult.jitter = metricCalculator.calculateJitter();
    return udpResult;
  }

  /**
   * Depending on the type of measurement, indicated by desc.Up, perform an uplink/downlink
   * measurement
   * 
   * @return the measurement's results
   * @throws MeasurementError if an error occurs
   */
  @Override
  public MeasurementResult[] call() throws MeasurementError {
    DatagramSocket socket = null;
    float response = 0.0F;
    UDPResult udpResult;
    int pktrecv = 0;
    this.taskProgress = TaskProgress.FAILED;

    UDPBurstDesc desc = (UDPBurstDesc) measurementDesc;

    if (!desc.target.equals(MLabNS.TARGET)) {
      throw new InvalidParameterException("Unknown target " + desc.target + " for UDPBurstTask");
    }

    ArrayList<String> mlabNSResult = MLabNS.Lookup(context, "mobiperf");
    if (mlabNSResult.size() == 1) {
      desc.target = mlabNSResult.get(0);
    } else {
      throw new InvalidParameterException("Invalid MLabNS query result" + " for UDPBurstTask");
    }
    Logger.i("Setting target to: " + desc.target);

    PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();

    Logger.i("Running UDPBurstTask on " + desc.target);
    try {
      if (desc.dirUp == true) {
        socket = sendUpBurst();
        udpResult = recvUpResponse(socket);
        if (stopFlag) {
          throw new MeasurementError("Cancelled");
        }
        pktrecv = udpResult.packetCount;
        response = pktrecv / (float) desc.udpBurstCount;
        this.taskProgress = TaskProgress.COMPLETED;
      } else {
        socket = sendDownRequest();
        udpResult = recvDownResponse(socket);
        if (stopFlag) {
          throw new MeasurementError("Cancelled");
        }
        pktrecv = udpResult.packetCount;
        response = pktrecv / (float) desc.udpBurstCount;
        this.taskProgress = TaskProgress.COMPLETED;
      }
    } catch (MeasurementError e) {
      throw e;
    } finally {
      // Update the sequence number to be used by the next burst.
      // Hongyi: we should update seq number no matter the measurement is
      // succeeded or not. It ensures previous last UDP burst's packets
      // will not affect the current one.
      seq++;
      if (socket != null) {
        socket.close();
      }
    }

    MeasurementResult result =
        new MeasurementResult(phoneUtils.getDeviceInfo().deviceId,
          phoneUtils.getDeviceProperty(this.getKey()),
          UDPBurstTask.TYPE, System.currentTimeMillis() * 1000,
          this.taskProgress, this.measurementDesc);


    result.addResult("target_ip", targetIp);
    result.addResult("loss_ratio", 1.0 - response);
    result.addResult("out_of_order_ratio", udpResult.outOfOrderRatio);
    result.addResult("jitter", udpResult.jitter);
    MeasurementResult[] mrArray = new MeasurementResult[1];
    mrArray[0] = result;
    return mrArray;

  }

  @Override
  public String getType() {
    return UDPBurstTask.TYPE;
  }

  /**
   * Returns a brief human-readable descriptor of the task.
   */
  @Override
  public String getDescriptor() {
    return UDPBurstTask.DESCRIPTOR;
  }

  /**
   * This will be printed to the device log console. Make sure it's well structured and human
   * readable
   */
  @Override
  public String toString() {
    UDPBurstDesc desc = (UDPBurstDesc) measurementDesc;
    String resp;

    if (desc.dirUp) {
      resp = "[UDPUp]\n";
    } else {
      resp = "[UDPDown]\n";
    }

    resp +=
        " Target: " + desc.target + "\n  Interval (sec): " + desc.intervalSec + "\n  Next run: "
            + desc.startTime;

    return resp;
  }

  @Override
  public long getDuration() {
    return this.duration;
  }

  @Override
  public void setDuration(long newDuration) {
    if (newDuration < 0) {
      this.duration = 0;
    } else {
      this.duration = newDuration;
    }

  }

  /**
   * Stop the measurement, even when it is running. Should release all acquired resource in this
   * function. There should not be side effect if the measurement has not started or is already
   * finished.
   */
  @Override
  public boolean stop() {
    stopFlag = true;
    return true;
  }
  
  /**
   * Based on a direct accounting of UDP packet sizes.
   */
  @Override
  public long getDataConsumed() {
    return dataConsumed;
  }
}
