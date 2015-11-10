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

// Java Http Connection imports

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.MalformedURLException;

// Parceling and Base64 imports
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Base64;

// Mobilyzer-specific imports
import com.mobilyzer.Config;
import com.mobilyzer.MeasurementDesc;
import com.mobilyzer.MeasurementResult;
import com.mobilyzer.MeasurementTask;
import com.mobilyzer.MeasurementResult.TaskProgress;
import com.mobilyzer.exceptions.MeasurementError;
import com.mobilyzer.util.Logger;
import com.mobilyzer.util.MeasurementJsonConvertor;
import com.mobilyzer.util.PhoneUtils;
import com.mobilyzer.util.Util;

// other java imports 
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.nio.ByteBuffer;
import java.security.InvalidParameterException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * A Callable task that issues HTTP gets for a list of URLs
 */
public class HttpTask extends MeasurementTask {

    // Type name for internal use
    public static final String TYPE = "http";

    // Human readable name for the task
    public static final String DESCRIPTOR = "HTTP";

    // The maximum number of bytes we will read from any requested URL. Set to 1Mb.
    public static final int MAX_HTTP_RESPONSE_SIZE = 1024 * 1024;

    // The buffer size we use to read from the HTTP response stream
    public static final int READ_BUFFER_SIZE = 1024;

    // Not used by the HTTP protocol. Just in case we do not receive a status line
    // from the response
    public static final int DEFAULT_STATUS_CODE = 0;

    // Track data consumption for this task to avoid exceeding user's limit  
    private long dataConsumed;

    // length of time the task has run
    private long duration;

    // Actual Http Client 
    private HttpURLConnection httpClient = null;

    /**
     * Create a new Http task from a measurement description
     */
    public HttpTask(MeasurementDesc desc) {
        super(new HttpDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec,
                desc.count, desc.priority, desc.contextIntervalSec, desc.parameters));
        this.duration = Config.DEFAULT_HTTP_TASK_DURATION;
        this.dataConsumed = 0;
    }

    /**
     * Create a new HttpTask from a Parcel
     */
    protected HttpTask(Parcel in) {
        super(in);
        duration = in.readLong();
        dataConsumed = in.readLong();
    }

    /**
     * A creator that generates instances of HttpTasks from a Parcel
     */
    public static final Parcelable.Creator<HttpTask> CREATOR =
            new Parcelable.Creator<HttpTask>() {
                public HttpTask createFromParcel(Parcel in) {
                    return new HttpTask(in);
                }

                public HttpTask[] newArray(int size) {
                    return new HttpTask[size];
                }
            };

    /**
     * Output a HttpTask to a Parcel
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeLong(duration);
        dest.writeLong(dataConsumed);
    }

    /**
     * Class defining the description of a Http  measurement
     * Note that the class is static - it behaves like a top-level class
     * but is put here for packaging convienience. No extra .java files..Woo!
     */
    public static class HttpDesc extends MeasurementDesc {
        public String url;
        public String method;
        public String headers;
        // TODO more fields may be needed

        public HttpDesc(String key, Date startTime, Date endTime,
                        double intervalSec, long count, long priority, int contextIntervalSec,
                        Map<String, String> params) throws InvalidParameterException {
            super(HttpTask.TYPE, key, startTime, endTime, intervalSec, count,
                    priority, contextIntervalSec, params);
            initializeParams(params);
            if (url == null || url.length() == 0) {
                throw new InvalidParameterException("Url for http task is null");
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
	    else {
		this.method = this.method.toUpperCase(Locale.ENGLISH);
	    }

            this.headers = params.get("headers");
        }

        @Override
        public String getType() {
            return HttpTask.TYPE;
        }

        protected HttpDesc(Parcel in) {
            super(in);
            url = in.readString();
            method = in.readString();
            headers = in.readString();
        }

        public static final Parcelable.Creator<HttpDesc> CREATOR =
                new Parcelable.Creator<HttpDesc>() {
                    public HttpDesc createFromParcel(Parcel in) {
                        return new HttpDesc(in);
                    }

                    public HttpDesc[] newArray(int size) {
                        return new HttpDesc[size];
                    }
                };

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(url);
            dest.writeString(method);
            dest.writeString(headers);
        }
    }

    /**
     * Returns a copy of the HttpTask
     */
    @Override
    public MeasurementTask clone() {
        MeasurementDesc desc = this.measurementDesc;
        HttpDesc newDesc = new HttpDesc(desc.key, desc.startTime, desc.endTime,
                desc.intervalSec, desc.count,
                desc.priority, desc.contextIntervalSec,
                desc.parameters);
        return new HttpTask(newDesc);
    }

    /**
     * gets the class description from an instance of the measurement task
     */
    @SuppressWarnings("rawtypes")
    public static Class getDescClass() throws InvalidClassException {
        return HttpDesc.class;
    }

    /**
     * gets the type string of the class
     */
    @Override
    public String getType() {
        return HttpTask.TYPE;
    }

    /**
     * Gets human-readable string descriptor of the task
     */
    @Override
    public String getDescriptor() {
        return DESCRIPTOR;
    }

    /**
     * Returns a string representation of the task
     */
    @Override
    public String toString() {
        // get measurementDesc from superclass
        HttpDesc desc = (HttpDesc) measurementDesc;
        return "Http Task [HTTP " + desc.method + "]\n  Target: " + desc.url +
                "\n  Headers: " + desc.headers +
                "\n  Interval (sec): " + desc.intervalSec + "\n  Next run: " +
                desc.startTime;
    }

    /**
     * TODO Unsure why this exists...
     */
    @Override
    public boolean stop() {
        return false;
    }

    /**
     * gets the duration of the task
     */
    @Override
    public long getDuration() {
        return this.duration;
    }

    /**
     * sets the duration of the task
     */
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

    /**
     * Runs the task. This is where all the magic happens.
     */
    @Override
    public MeasurementResult[] call() throws MeasurementError {

        int statusCode = HttpTask.DEFAULT_STATUS_CODE;
        String errorMsg = "";
        long duration = 0;
        long preTaskRxTx = Util.getCurrentRxTxBytes();
        InputStream in = null;
        String header = "";
        ByteBuffer body = null;

        try {
            // get the task description from the superclass field
            HttpDesc desc = (HttpDesc) this.measurementDesc;

            // get the URL
            URL url = new URL(desc.url);

            // instantiate the HttpURLConnection
            httpClient = (HttpURLConnection) url.openConnection();
            httpClient.setRequestMethod(desc.method);
            httpClient.setUseCaches(false);
            if (desc.headers != null && desc.headers.trim().length() > 0) {
                for (String headerLine : desc.headers.replaceAll("\\r", "").split("\\n")) {
                    String tokens[] = headerLine.trim().split(":");
                    if (tokens.length == 2) {
                        httpClient.setRequestProperty(tokens[0], tokens[1]);
                    } else {
                        throw new MeasurementError("Invalid header line: " + headerLine);
                    }
                }
            }

            // track time elapsed
            long startTime = System.currentTimeMillis();

            // get headers
            statusCode = httpClient.getResponseCode();
            int contentLength = httpClient.getContentLength();
            Map<String, List<String>> headers = httpClient.getHeaderFields();
            if (headers != null && !headers.isEmpty()) {
                StringBuilder h = new StringBuilder();
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
		    if (entry.getValue() != null && entry.getKey() != null) {
			h.append(entry.getKey().trim() + ":" + TextUtils.join(", ", entry.getValue()).trim() + "\n");
		    }
		    else if (entry.getKey() != null) {
			h.append(entry.getKey().trim() + ":\n");
		    }
                }
                header = h.toString();
            }


            // get body
            in = httpClient.getInputStream();
            int totalBodyLen = 0;
            if (in != null) {
                byte[] readBuffer = new byte[READ_BUFFER_SIZE];
                body = ByteBuffer.allocate(MAX_HTTP_RESPONSE_SIZE);
                int readLen;

                while ((readLen = in.read(readBuffer)) > 0 &&
                        totalBodyLen < MAX_HTTP_RESPONSE_SIZE) {
                    totalBodyLen += readLen;
                    if (body.remaining() > 0) {
                        int putLen = body.remaining() < readLen ? body.remaining() : readLen;
                        body.put(readBuffer, 0, putLen);
                    }
                }
            }

            // finish elapsed time
            duration = System.currentTimeMillis() - startTime;

            // generate the measurement result
            // set task progress to completed always beacuse we are interested in
            // all results even if status code isn't 200.
            PhoneUtils phoneUtils = PhoneUtils.getPhoneUtils();
            MeasurementResult result = new MeasurementResult(
                    phoneUtils.getDeviceInfo().deviceId,
                    phoneUtils.getDeviceProperty(this.getKey()),
                    HttpTask.TYPE, System.currentTimeMillis() * 1000,
                    TaskProgress.COMPLETED, this.measurementDesc);

            result.addResult("status_code", statusCode);
            result.addResult("content_length", contentLength);
            dataConsumed += (Util.getCurrentRxTxBytes() - preTaskRxTx);
            result.addResult("time_ms", duration);
            result.addResult("headers_len", header.length());
            result.addResult("body_len", totalBodyLen);
            result.addResult("headers", header); // will be empty string if no headers
            if (totalBodyLen > 0) {
                result.addResult("body", Base64.encodeToString(body.array(),
                        Base64.DEFAULT));
            }

            // return result!
            Logger.i(MeasurementJsonConvertor.toJsonString(result));
            MeasurementResult[] mrArray = new MeasurementResult[1];
            mrArray[0] = result;
            return mrArray;

        } catch (MalformedURLException e) {
            errorMsg += e.getMessage() + "\n";
            Logger.e(e.getMessage());
        } catch (IOException e) {
            errorMsg += e.getMessage() + "\n";
            Logger.e(e.getMessage());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Logger.e("Fails to close the input stream from the HTTP response");
                }
            }
            if (httpClient != null) {
                httpClient.disconnect();
            }
        }
        //this throw is only triggered if the return wasn't hit in the try block
        throw new MeasurementError("Cannot get result from HTTP measurement because "
                + errorMsg);
    }
}


    
    