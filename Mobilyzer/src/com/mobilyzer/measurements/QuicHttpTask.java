
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
import org.json.JSONException;
import org.json.JSONObject;

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
 * A Callable class that performs download throughput test using QUIC via Google Cronet library
 */
public class QuicHttpTask extends MeasurementTask {

    ////////////////////////////////////////////////
    //// CONSTANTS

    // Type name for internal use
    public static final String TYPE = "quic-http";
    // Human readable name for the task
    public static final String DESCRIPTOR = "QUIC-HTTP";
    // The maximum number of bytes we will read from requested URL. Set to 1Mb.
    public static final long MAX_QUIC_HTTP_RESPONSE_SIZE = 1024 * 1024;
    // The size of the response body we will report to the service.
    // If the response is larger than MAX_BODY_SIZE_TO_UPLOAD bytes, we will
    // only report the first MAX_BODY_SIZE_TO_UPLOAD bytes of the body.
    public static final int MAX_BODY_SIZE_TO_UPLOAD = 1024;
    // The buffer size we use to read from the HTTP response stream
    public static final int READ_BUFFER_SIZE = 1024;
    // Not used by the HTTP protocol. Just in case we do not receive a status line from the response
    public static final int DEFAULT_STATUS_CODE = 0;
    // Option for the Cronet builder to force QUIC on host + port
    private static final String FORCE_QUIC_OPTION = "origin_to_force_quic_on";



    ////////////////////////////////////////////////
    //// FIELDS

    //Track data consumption for this task to avoid exceeding user's limit
    private long dataConsumed;
    private long duration;


    public static final Parcelable.Creator<QuicHttpTask> CREATOR =
            new Parcelable.Creator<QuicHttpTask>() {
                public QuicHttpTask createFromParcel(Parcel in) {
                    return new QuicHttpTask(in);
                }

                public QuicHttpTask[] newArray(int size) {
                    return new QuicHttpTask[size];
                }
            };

    ////////////////////////////////////////////////
    //// CONSTRUCTORS

    public QuicHttpTask(MeasurementDesc desc) {
        super(new QuicDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec,
                desc.count, desc.priority, desc.contextIntervalSec, desc.parameters));
        this.duration = Config.DEFAULT_HTTP_TASK_DURATION;
        this.dataConsumed = 0;
    }

    protected QuicHttpTask(Parcel in) {
        super(in);
        duration = in.readLong();
        dataConsumed = in.readLong();
    }


    ////////////////////////////////////////////////
    //// INNER CLASS

    /**
     * The description of a Quic measurement
     */
    public static class QuicDesc extends MeasurementDesc {

        public static final Parcelable.Creator<QuicDesc> CREATOR =
                new Parcelable.Creator<QuicDesc>() {
                    public QuicDesc createFromParcel(Parcel in) {
                        return new QuicDesc(in);
                    }

                    public QuicDesc[] newArray(int size) {
                        return new QuicDesc[size];
                    }
                };

        public String url;
        private String method;
        private String headers;
        private String body;

        public QuicDesc(String key, Date startTime, Date endTime,
                        double intervalSec, long count, long priority, int contextIntervalSec,
                        Map<String, String> params) throws InvalidParameterException {
            super(QuicHttpTask.TYPE, key, startTime, endTime, intervalSec, count,
                    priority, contextIntervalSec, params);
            if (params == null) {
                throw new InvalidParameterException("Parameters for the measurement are not provided");
            }
            initializeParams(params);
        }

        @Override
        protected void initializeParams(Map<String, String> params) {
            String urlParam = params.get("url");
            if (urlParam == null || urlParam.isEmpty()) {
                throw new InvalidParameterException("URL for quic http task is null");
            }

            url = urlParam.startsWith("https://") ? urlParam : "https://" + urlParam;

            headers = params.get("headers");
            body = params.get("body");
            method = (body == null || body.isEmpty()) ? "GET" : "POST";
        }

        @Override
        public String getType() {
            return QuicHttpTask.TYPE;
        }

        protected QuicDesc(Parcel in) {
            super(in);
            url = in.readString();
            method = in.readString();
            headers = in.readString();
            body = in.readString();
        }

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


    @Override
    public MeasurementTask clone() {
        QuicDesc desc = (QuicDesc) this.measurementDesc;
        QuicDesc newDesc = new QuicDesc(desc.key, desc.startTime, desc.endTime,
                desc.intervalSec, desc.count, desc.priority, desc.contextIntervalSec,
                desc.parameters);
        return new QuicHttpTask(newDesc);
    }

    /**
     * Runs the Quic measurement task. Will acquire power lock to ensure wifi
     * is not turned off
     */
    @Override
    public MeasurementResult[] call() throws MeasurementError {
        TaskProgress taskProgress = TaskProgress.FAILED;
        int statusCode = DEFAULT_STATUS_CODE;
        ByteBuffer body = ByteBuffer.allocate(QuicHttpTask.MAX_BODY_SIZE_TO_UPLOAD);
        String headers = "";
        byte[] readBuffer = new byte[QuicHttpTask.READ_BUFFER_SIZE];
        int totalBodyLen = 0;

        long currentRxTx = Util.getCurrentRxTxBytes();

        QuicDesc task = (QuicDesc) this.measurementDesc;

        HttpURLConnection urlConnection = null;

        try {
            URL url = new URL(task.url);

            /* Build new Cronet engine */
            CronetEngine.Builder cronetBuilder = new CronetEngine.Builder(API.getApplicationContext());
            cronetBuilder.enableQUIC(true);
            int quicPort = url.getPort() == -1 ? 443 : url.getPort();
            cronetBuilder.addQuicHint(url.getHost(), quicPort, quicPort);


            JSONObject quicParams = new JSONObject().put(FORCE_QUIC_OPTION, url.getHost() + ":" + quicPort);
            JSONObject experimentalOptions = new JSONObject().put("QUIC", quicParams);
                cronetBuilder.setExperimentalOptions(experimentalOptions.toString());
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
                        && totalBodyLen <= QuicHttpTask.MAX_QUIC_HTTP_RESPONSE_SIZE) {
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
        }
        catch (JSONException e) {
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
                QuicHttpTask.TYPE, System.currentTimeMillis() * 1000,
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
        return QuicDesc.class;
    }

    @Override
    public String getType() {
        return QuicHttpTask.TYPE;
    }

    @Override
    public String getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String toString() {
        QuicDesc desc = (QuicDesc) measurementDesc;
        return "[QUICHTTP " + desc.method + "]\n  Target: " + desc.url +
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