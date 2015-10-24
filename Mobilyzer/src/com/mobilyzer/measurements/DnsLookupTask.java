/* Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mobilyzer.measurements;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.InvalidClassException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.InvalidParameterException;
import java.util.Date;
import java.util.Map;

import com.mobilyzer.Config;
import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;

import org.xbill.DNS.*;

/**
 * Measures the DNS lookup time
 */
public class DnsLookupTask extends MeasurementTask{
    // Type name for internal use
    public static final String TYPE = "dns_lookup";
    // Human readable name for the task
    public static final String DESCRIPTOR = "DNS lookup";

    //Since it's very hard to calculate the data consumed by this task
    // directly, we use a fixed value.  This is on the high side.
    public static final int AVG_DATA_USAGE_BYTE=2000;

    private long duration;

    /**
     * The description of DNS lookup measurement
     */
    public static class DnsLookupDesc extends MeasurementDesc {
        public String target;
        public String server;
        public String qclass;
        public String qtype;


        public DnsLookupDesc(String key, Date startTime, Date endTime,
                             double intervalSec, long count, long priority,
                             int contextIntervalSec, Map<String, String> params) {
            super(DnsLookupTask.TYPE, key, startTime, endTime, intervalSec, count,
                  priority, contextIntervalSec, params);
            initializeParams(params);
            if (this.target == null || this.target.length() == 0) {
                throw new InvalidParameterException("LookupDnsTask cannot " +
                                                    "be created due to null " +
                                                    "target string");
            }
        }

        /*
         * @see com.google.wireless.speed.speedometer.MeasurementDesc#getType()
         */
        @Override
        public String getType() {
            return DnsLookupTask.TYPE;
        }

        @Override
        protected void initializeParams(Map<String, String> params) {
            if (params == null) {
                return;
            }

            this.server = params.get("server");
            this.target = params.get("target");
            // make the lookup absolute if it isn't already
            if (!this.target.endsWith(".")) {
                this.target = this.target + ".";
            }

            /* we are extending the DNS measurement to allow setting
             * arbitrary query classes and types, but we want to maintain
             * backwards compatibility. Therefore, we are going to default
             * to a standard IPv4 query, qclass IN and qtype A
             */
            if (params.conainsKey("qclass")) {
                this.qclass = params.get("qclass");
            } else {
                this.qclass = "IN";
            }

            if (params.conainsKey("qtype")) {
                this.qtype = params.get("qtype");
            } else {
                this.qtype = "A";
            }

        }

        protected DnsLookupDesc(Parcel in) {
            super(in);
            target = in.readString();
            server = in.readString();
            qclass = in.readString();
            qtype = in.readString();
        }

        public static final Parcelable.Creator<DnsLookupDesc> CREATOR =
            new Parcelable.Creator<DnsLookupDesc>() {
            public DnsLookupDesc createFromParcel(Parcel in) {
                return new DnsLookupDesc(in);
            }

            public DnsLookupDesc[] newArray(int size) {
                return new DnsLookupDesc[size];
            }
        };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(target);
            dest.writeString(server);
            dest.writeString(qclass);
            dest.writeString(qtype);
        }
    }

    private class DNSWrapper(){
        public boolean isValid;
        public String rawOutput;
        public Message response;
        public int qid;
        public int id;
        public long respTime;

        public DNSWrapper(boolean isValid, byte[] rawOutput, Message response,
                          int qid, int id, long respTime) {
            this.isValid = isValid;
            this.rawOutput = rawOutput.toString();
            this.response = response;
            this.qid = qid;
            this.id = id;
            this.respTime = respTime;
        }
    }


    public DnsLookupTask(MeasurementDesc desc) {
        super(new DnsLookupDesc(desc.key, desc.startTime, desc.endTime,
                                desc.intervalSec, desc.count, desc.priority,
                                desc.contextIntervalSec, desc.parameters));
        this.duration=Config.DEFAULT_DNS_TASK_DURATION;
    }

    protected DnsLookupTask(Parcel in) {
        super(in);
        duration = in.readLong();
    }

    public static final Parcelable.Creator<DnsLookupTask> CREATOR =
        new Parcelable.Creator<DnsLookupTask>() {
        public DnsLookupTask createFromParcel(Parcel in) {
            return new DnsLookupTask(in);
        }

        public DnsLookupTask[] newArray(int size) {
            return new DnsLookupTask[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeLong(duration);
    }
    /**
     * Returns a copy of the DnsLookupTask
     */
    @Override
    public MeasurementTask clone() {
        MeasurementDesc desc = this.measurementDesc;
        DnsLookupDesc newDesc = new DnsLookupDesc(desc.key, desc.startTime, desc.endTime,
                                                  desc.intervalSec, desc.count, desc.priority, desc.contextIntervalSec, desc.parameters);
        return new DnsLookupTask(newDesc);
    }

    public ArrayList<DNSWrapper> measureDNS(String domain, String qtype, String qclass) {
        Record question = null;
        try {
            question = Record.newRecord(Name.fromString(domain),
                                        Type.value(qtype),
                                        DClass.value(qclass));
        } catch (TextParseException e) {
            System.out.println("Error constructing packet");
        }

        Message query = Message.newQuery(question);
        // wait for at most 5 seconds for a response
        long endTime = System.currentTimeMillis() + 5;
        ArrayList<DNSWrapper> responses = sendMeasurement(query, false, endTime);
        return responses;
    }

    private ArrayList<DNSWrapper> sendMeasurement(Message query, boolean useTcp, long endTime) {
        // now that we have a message, put it on the wire and wait for
        // responses
        int qid = query.getHeader().getID();
        byte [] output = query.toWire();
        int udpSize = SimpleResolver.maxUDPSize(query);

        Client client;
        if (useTCP || (output.length > udpSize)) {
            client = new TCPClient(endTime);
            client.connect(this.server);
            useTCP = true;

        } else {
            client = new UDPClient(endTime);
            client.bind(null);
            client.connect(this.server);
        }

        boolean shouldSend = True;
        long startTime, long respTime;
        ArrayList<DNSWrapper> responses = new ArrayList<DNSWrapper>();
        while (System.currentTimeMillis() < endTime) {
            byte [] in;

            if (shouldSend) {
                client.send(output);
                startTime = System.currentTimeMillis();
                shouldSend = false;
            }

            if (useTCP) {
                in = client.recv();
            } else {
                in = client.recv(udpSize);
            }
            respTime = System.currentTimeMillis() - startTime;

            // if we didn't get anything back, then continue. this
            // means we will break out if we are over time
            if (in.length == 0) {
                continue;
            }

            // don't parse the message if it's too short
            if (in.length < Header.LENGTH) {
                DNSWrapper response = new DNSWrapper(false, in, null, qid, -1, respTime);
                responses.add(response);
                continue
            }


            int id = ((in[0] & 0xFF) << 8) + (in[1] & 0xFF);
            Message response = parseMessage(in);
            DNSWrapper wrap = new DNSWrapper(true, in, response, qid, id, respTime);
            responses.add(wrap);

            // if the response was truncated, then requery over TCP
            if (!useTCP && response.getHeader().getFlag(Flags.TC)) {
                client.cleanup();
                client = new TCPClient(endTime);
                client.connect(this.server);
                useTCP = true;
                shouldSend = true;
            }

        }
        return responses;
    }


    @Override
    public MeasurementResult[] call() throws MeasurementError {
        // long t1, t2;
        // long totalTime = 0;
        // InetAddress resultInet = null;
        // int successCnt = 0;
        ArrayList <DNSWrapper> responses;
        for (int i = 0; i < Config.DEFAULT_DNS_COUNT_PER_MEASUREMENT; i++) {
            DnsLookupDesc taskDesc = (DnsLookupDesc) this.measurementDesc;
            Logger.i("Running DNS Lookup for target " + taskDesc.target);
            ArrayList<DNSWrapper> responses = measureDNS(taskDesc.target, taskDesc.qtype, taskDesc.qclass);

                // t1 = System.currentTimeMillis();
                // InetAddress inet = InetAddress.getByName(taskDesc.target);
                // t2 = System.currentTimeMillis();
                // if (inet != null) {
                //     totalTime += (t2 - t1);
                //     resultInet = inet;
                //     successCnt++;
                // }
        }
        if ((responses == null) || (response.size() == 0)){
            throw new MeasurementError("Problems conducting DNS measurement");
        } else {
resultInet != null) {
            Logger.i("Successfully resolved target address");
            PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
            MeasurementResult result = new MeasurementResult(
                                                             phoneUtils.getDeviceInfo().deviceId,
                                                             phoneUtils.getDeviceProperty(this.getKey()),
                                                             DnsLookupTask.TYPE,
                                                             System.currentTimeMillis() * 1000,
                                                             TaskProgress.COMPLETED, this.measurementDesc);

            // now turn the result into an array of hashmaps with the data we care about

            HashMap <String, Object> [] data = extractResults(responses);
            result.addResult("results", data);
            result.addResult("target", this.target);
            result.addResult("qtype", this.qtype);
            result.addResult("qclass", this.qclass);

            Logger.i(MeasurementJsonConvertor.toJsonString(result));
            MeasurementResult[] mrArray= new MeasurementResult[1];
            mrArray[0]=result;
            return mrArray;
        }
    }

    public HashMap<String, Object>[] extractResults(ArrayList<DNSWrapper> responses) {
        ArrayList<HashMap<String, Object>> data = new ArrayList<>;
        for (DNSWrapper wrap : responses) {
            Message resp;
            if (wrap.isValid) {
                resp = wrap.response;
            }
            HashMap<String, Object> item = new HashMap<>;
            item.put("qryId", wrap.qid);
            item.put("respId", wrap.id);
            item.put("payload", wrap.rawOutput);
            item.put("respTime", wrap.respTime);
            item.put("isValid", wrap.isValid);
            item.put("rcode", Rcode.string(resp.header.getRcode()));
            item.put("tc", resp.getHeader().getFlag(Flags.TC));

            // process the question
            Record [] questionRecs = resp.getSectionArray(0);
            if (questionRecs.length == 0) {
                item.put("domain", null);
                item.put("qtype", null);
                item.put("qclass", null);
            } else {
                Record rec = questionRecs[0];
                item.put("domain", rec.name.toString());
                item.put("qtype", Type.string(rec.type));
                item.put("qclass", DClass.string(rec.dclass));
            }

            // now process the answers
            List<HashMap<String, String>> answers = new ArrayList<>;
            Record [] questionRecs = resp.getSectionArray(1);
            for (rec : questionRecs) {
                HashMap<String, String> entry = new HashMap<>;
                entry.put("name", rec.name.toString());
                entry.put("rtype", Type.string(rec.type));
                entry.put("rdata", rec.rrToString());
                answers.add(entry);
            }
            item.put("answers", answers.toArray());
            data.add(item);
        }
        return data.toArray();
    }

    @SuppressWarnings("rawtypes")
    public static Class getDescClass() throws InvalidClassException {
        return DnsLookupDesc.class;
    }

    @Override
    public String getType() {
        return DnsLookupTask.TYPE;
    }

    @Override
    public String getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String toString() {
        DnsLookupDesc desc = (DnsLookupDesc) measurementDesc;
        return "[DNS Lookup]\n  Target: " + desc.target + "\n  Interval (sec): "
            + desc.intervalSec + "\n  Next run: " + desc.startTime;
    }

    @Override
    public boolean stop() {
        //There is nothing we need to do to stop the DNS measurement
        return false;
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

    /**
     * Since it is hard to get the amount of data sent directly,
     * use a fixed value.  The data consumed is usually small, and the fixed
     * value is a conservative estimate.
     *
     * TODO find a better way to get this value
     */
    @Override
    public long getDataConsumed() {
        return AVG_DATA_USAGE_BYTE;
    }

}
