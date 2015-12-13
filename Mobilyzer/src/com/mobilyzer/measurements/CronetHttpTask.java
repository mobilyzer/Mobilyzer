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
import android.util.Base64;

import com.mobilyzer.Config;
import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.api.API;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;
import com.mobilyzer.util.Util;

import org.chromium.net.CronetEngine;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.InvalidParameterException;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * A Callable class that performs download throughput test using HTTP via Google Cronet library
 */
public class CronetHttpTask extends MeasurementTask {


    ////////////////////////////////////////////////
    //// CONSTANTS

    // Type name for internal use
    public static final String TYPE = "cronet-http";
    // Human readable name for the task
    public static final String DESCRIPTOR = "CRONET-HTTP";
    // The maximum number of bytes we will read from requested URL. Set to 1Mb.
    public static final long MAX_HTTP_RESPONSE_SIZE = 1024 * 1024;
    // The size of the response body we will report to the service.
    // If the response is larger than MAX_BODY_SIZE_TO_UPLOAD bytes, we will
    // only report the first MAX_BODY_SIZE_TO_UPLOAD bytes of the body.
    public static final int MAX_BODY_SIZE_TO_UPLOAD = 1024;
    // The buffer size we use to read from the HTTP response stream
    public static final int READ_BUFFER_SIZE = 1024;
    // Not used by the HTTP protocol. Just in case we do not receive a status line from the response
    public static final int DEFAULT_STATUS_CODE = 0;


    ////////////////////////////////////////////////
    //// FIELDS

    //Track data consumption for this task to avoid exceeding user's limit
    private long dataConsumed;
    private long duration;
    public static final Parcelable.Creator<CronetHttpTask> CREATOR =
            new Parcelable.Creator<CronetHttpTask>() {
                public CronetHttpTask createFromParcel(Parcel in) {
                    return new CronetHttpTask(in);
                }

                public CronetHttpTask[] newArray(int size) {
                    return new CronetHttpTask[size];
                }
            };

    ////////////////////////////////////////////////
    //// CONSTRUCTORS

    public CronetHttpTask(MeasurementDesc desc) {
        super(new CronetHttpDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec,
                desc.count, desc.priority, desc.contextIntervalSec, desc.parameters));
        this.duration = Config.DEFAULT_HTTP_TASK_DURATION;
        this.dataConsumed = 0;
    }

    protected CronetHttpTask(Parcel in) {
        super(in);
        duration = in.readLong();
        dataConsumed = in.readLong();
    }


    ////////////////////////////////////////////////
    //// INNER CLASS

    /**
     * The description of a HTTP measurement via Cronet
     */
    public static class CronetHttpDesc extends MeasurementDesc {
        public String url;
        private String method;
        private String headers;
        private String body;

        public CronetHttpDesc(String key, Date startTime, Date endTime,
                        double intervalSec, long count, long priority, int contextIntervalSec,
                        Map<String, String> params) throws InvalidParameterException {
            super(CronetHttpTask.TYPE, key, startTime, endTime, intervalSec, count,
                    priority, contextIntervalSec, params);
            initializeParams(params);
            if (this.url == null || this.url.length() == 0) {
                throw new InvalidParameterException("URL for http task is null");
            }
        }

        @Override
        protected void initializeParams(Map<String, String> params) {

            if (params == null) {
                return;
            }

            this.url = params.get("url");
            if (!this.url.startsWith("http://") && !this.url.startsWith("https://")) {
                this.url = "http://" + this.url;
            }

            this.method = params.get("method");
            if (this.method == null || this.method.isEmpty()) {
                this.method = "GET";
            }
            this.headers = params.get("headers");
            this.body = params.get("body");
        }

        @Override
        public String getType() {
            return CronetHttpTask.TYPE;
        }


        protected CronetHttpDesc(Parcel in) {
            super(in);
            url = in.readString();
            method = in.readString();
            headers = in.readString();
            body = in.readString();
        }

        public static final Parcelable.Creator<CronetHttpDesc> CREATOR =
                new Parcelable.Creator<CronetHttpDesc>() {
                    public CronetHttpDesc createFromParcel(Parcel in) {
                        return new CronetHttpDesc(in);
                    }

                    public CronetHttpDesc[] newArray(int size) {
                        return new CronetHttpDesc[size];
                    }
                };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(url);
            dest.writeString(method);
            dest.writeString(headers);
            dest.writeString(body);
        }
    }

    ////////////////////////////////////////////////
    //// METHODS

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeLong(duration);
        dest.writeLong(dataConsumed);
    }


    /**
     * Returns a copy of the CronetHttpTask
     */
    @Override
    public MeasurementTask clone() {
        MeasurementDesc desc = this.measurementDesc;
        CronetHttpDesc newDesc = new CronetHttpDesc(desc.key, desc.startTime, desc.endTime,
                desc.intervalSec, desc.count, desc.priority, desc.contextIntervalSec,
                desc.parameters);
        return new CronetHttpTask(newDesc);
    }

    /**
     * Runs the HTTP measurement task. Will acquire power lock to ensure wifi
     * is not turned off
     */
    @Override
    public MeasurementResult[] call() throws MeasurementError {
        TaskProgress taskProgress = TaskProgress.FAILED;
        int statusCode = DEFAULT_STATUS_CODE;
        ByteBuffer body = ByteBuffer.allocate(CronetHttpTask.MAX_BODY_SIZE_TO_UPLOAD);
        String headers = "";
        byte[] readBuffer = new byte[CronetHttpTask.READ_BUFFER_SIZE];
        int totalBodyLen = 0;

        long currentRxTx = Util.getCurrentRxTxBytes();

        CronetHttpDesc task = (CronetHttpDesc) this.measurementDesc;

        HttpURLConnection urlConnection = null;

        try {
            URL url = new URL(task.url);
            CronetEngine.Builder cronetBuilder = new CronetEngine.Builder(API.getApplicationContext());
            CronetEngine cronetEngine = cronetBuilder.build();

            urlConnection = (HttpURLConnection) cronetEngine.openConnection(url);
            urlConnection.setRequestMethod(task.method);
            boolean doOutput = false;

            /* Add request headers */
            if (task.headers != null && task.headers.trim().length() > 0) {
                doOutput = true;
                for (String headerLine : task.headers.split("\r\n")) {
                    String tokens[] = headerLine.split(":");
                    if (tokens.length == 2) {
                        urlConnection.setRequestProperty(tokens[0], tokens[1]);
                    } else {
                        throw new MeasurementError("Incorrect header line: " + headerLine);
                    }
                }
            }

            /* Do not follow redirects */
            urlConnection.setInstanceFollowRedirects(false);
            /* If we do the output */
            if (!(task.body == null || task.body.isEmpty())) {
                doOutput = true;
            }
            urlConnection.setDoOutput(doOutput);

            /* Start measuring */
            long startTime = System.currentTimeMillis();


            /* Write body */
            if (!(task.body == null || task.body.isEmpty())) {
                OutputStream outputStream = urlConnection.getOutputStream();
                outputStream.write(task.body.getBytes());
                outputStream.flush();
                outputStream.close();
            }

            /* Get response code */
            statusCode = urlConnection.getResponseCode();

            /* Get response body */
            if (statusCode == HttpURLConnection.HTTP_OK) {
                int readLen;
                InputStream inputStream = new BufferedInputStream(urlConnection.getInputStream());
                while ((readLen = inputStream.read(readBuffer)) > 0
                        && totalBodyLen <= CronetHttpTask.MAX_HTTP_RESPONSE_SIZE) {
                    totalBodyLen += readLen;
                    // Fill in the body to report up to MAX_BODY_SIZE
                    if (body.remaining() > 0) {
                        int putLen = body.remaining() < readLen ? body.remaining() : readLen;
                        body.put(readBuffer, 0, putLen);
                    }
                }

            }

             /* Stop measuring */
            duration = System.currentTimeMillis() - startTime;

            /* Set task result */
            taskProgress = statusCode == HttpURLConnection.HTTP_OK ?
                    TaskProgress.COMPLETED : TaskProgress.FAILED;

            /* Process headers */
            Map<String, List<String>> responseHeaders = urlConnection.getHeaderFields();
            if (!responseHeaders.isEmpty()) {
                headers = "";
                for (String headerKey : responseHeaders.keySet()) {
                    List<String> headerValues = responseHeaders.get(headerKey);
                    headers += headerKey + ": ";
                    boolean first = true;
                    for (String headerValue : headerValues) {
                        if (!first) {
                            headers += ", ";
                        }
                        headers += headerValue;
                        first = false;
                    }
                    headers += "\r\n";
                }
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }


        dataConsumed += (Util.getCurrentRxTxBytes() - currentRxTx);

        /* Prepare result */
        PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
        MeasurementResult result = new MeasurementResult(
                phoneUtils.getDeviceInfo().deviceId,
                phoneUtils.getDeviceProperty(this.getKey()),
                CronetHttpTask.TYPE, System.currentTimeMillis() * 1000,
                taskProgress, this.measurementDesc);

        result.addResult("code", statusCode);

        if (taskProgress == TaskProgress.COMPLETED) {
            result.addResult("time_ms", duration);
            result.addResult("headers_len", headers.length());
            result.addResult("body_len", totalBodyLen);
            result.addResult("headers", headers);
            result.addResult("body", Base64.encodeToString(body.array(), Base64.DEFAULT));
        }

        Logger.i(MeasurementJsonConvertor.toJsonString(result));
        MeasurementResult[] mrArray = new MeasurementResult[1];
        mrArray[0] = result;
        return mrArray;
    }

    @SuppressWarnings("rawtypes")
    public static Class getDescClass() throws InvalidClassException {
        return CronetHttpDesc.class;
    }

    @Override
    public String getType() {
        return CronetHttpTask.TYPE;
    }

    @Override
    public String getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String toString() {
        CronetHttpDesc desc = (CronetHttpDesc) measurementDesc;
        return "[HTTP " + desc.method + "]\n  Target: " + desc.url +
                "\n  Interval (sec): " + desc.intervalSec + "\n  Next run: " +
                desc.startTime;
    }

    @Override
    public boolean stop() {
        return false;
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
     * Data used so far by the task.
     */
    @Override
    public long getDataConsumed() {
        return dataConsumed;
    }
}
