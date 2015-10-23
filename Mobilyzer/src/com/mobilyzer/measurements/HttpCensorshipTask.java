package com.mobilyzer.measurements;

// Java Http Connection imports
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.MalformedURLException;

// Parceling and Base64 imports
import android.os.Parcel;
import android.os.Parceable;
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
import java.util.Map;

/** 
 * A Callable task that issues HTTP gets for a list of URLs
 */
public class HttpCensorshipTask extends MeasurementTask {

    // Type name for internal use
    public static final String TYPE = "httpCensorship";

    // Human readable name for the task
    public static final String DESCRIPTOR = "HTTPCensorship";

    // The maximum number of bytes we will read from any requested URL. Set to 1Mb.
    public static final long MAX_HTTP_RESPONSE_SIZE = 1024 * 1024;

    // The size of the response body we will report to the service.
    // If the response is larger than MAX_BODY_SIZE_TO_UPLOAD bytes, we will 
    // only report the first MAX_BODY_SIZE_TO_UPLOAD bytes of the body.
    public static final int MAX_BODY_SIZE_TO_UPLOAD = 1024;

    // The buffer size we use to read from the HTTP response stream
    public static final int READ_BUFFER_SIZE = 1024;

    // Not used by the HTTP protocol. Just in case we do not receive a status line
    // from the response
    public static final int DEFAULT_STATUS_CODE = 0;

    // Track data consumption for this task to avoid exceeding user's limit  
    private long dataConsumed;

    // what is this for???
    private long duration;
   
    /** 
     * Create a new HttpCensorship task from a measurement description 
     */
    public HttpTask(MeasurementDesc desc) {
	super(new HttpCensorshipDesc(desc.key, desc.startTime, desc.endTime, desc.intervalSec,
				     desc.count, desc.priority, desc.contextIntervalSec, desc.parameters));
	this.duration=Config.DEFAULT_HTTP_TASK_DURATION;
	this.dataConsumed = 0;
    }
  
    /** 
     * Create a new HttpCensorshipTask from a Parcel 
     */
    protected HttpCensorshipTask(Parcel in) {
	super(in);
	duration = in.readLong();
	dataConsumed = in.readLong();
    }
    
    /**
     * A creator that generates instances of HttpCensorshipTasks from a Parcel
     */
    public static final Parcelable.Creator<HttpCensorshipTask> CREATOR =
	new Parcelable.Creator<HttpCensorshipTask>() {
	public HttpCenshorshipTask createFromParcel(Parcel in) {
	    return new HttpCensorshipTask(in);
      }
	
      public HttpCensorshipTask[] newArray(int size) {
	  return new HttpCensorshipTask[size];
      }
    };

    /** 
     * Output a HttpCensorshipTask to a Parcel 
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
	super.writeToParcel(dest, flags);
	dest.writeLong(duration);
	dest.writeLong(dataConsumed);
    }
    
    /**
     * Class defining the description of a Http censorship measurement
     *   Note that the class is static - it behaves like a top-level class
     *   but is put here for packaging convienience. No extra .java files..Woo! 
     */
    public static class HttpCensorshipDesc extends MeasurementDesc {
	public String url;
	public String method;
	// TODO more fields may be needed
	
	public HttpCensorshipDesc(String key, Date startTime, Date endTime,
				  double intervalSec, long count, long priority, int contextIntervalSec,
				  Map<String, String> params) throws InvalidParameterException {
	    super(HttpCensorshipTask.TYPE, key, startTime, endTime, intervalSec, count,
		  priority,contextIntervalSec, params);
	    initializeParams(params);
	    if (url == null || url.length() == 0) {
		throw new InvalidParameterException("Url for http censorship task is null");
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
		this.method = "get";
	    }
	}
	
	@Override
	public String getType() {
	    return HttpCensorshipTask.TYPE;
	}
	
	protected HttpCensorshipDesc(Parcel in) {
	    super(in);
	    url = in.readString();
	    method = in.readString();
	}
	
	public static final Parcelable.Creator<HttpDesc> CREATOR =
	    new Parcelable.Creator<HttpDesc>() {
	    public HttpCensorshipDesc createFromParcel(Parcel in) {
		return new HttpCensorshipDesc(in);
	    }
	    
	    public HttpCensorshipDesc[] newArray(int size) {
		return new HttpCensorshipDesc[size];
	    }
	};

	@Override
	public void writeToParcel(Parcel dest, int flags) {
	    super.writeToParcel(dest, flags);
	    dest.writeString(url);
	    dest.writeString(method);
	}
    }
    
    /**
     * Returns a copy of the HttpCensorshipTask
     */
    @Override
    public MeasurementTask clone() {
	MeasurementDesc desc = this.measurementDesc;
	HttpCensorshipDesc newDesc = new HttpCensorshipDesc(desc.key, desc.startTime, desc.endTime, 
							    desc.intervalSec, desc.count, desc.priority, 
							    desc.contextIntervalSec, desc.parameters);
	return new HttpTask(newDesc);
    }

    /** 
     * gets the class description from an instance of the measurement task
     */
    @SuppressWarnings("rawtypes")
    public static Class getDescClass() throws InvalidClassException {
	return HttpCensorshipDesc.class;
    }

    /** 
     * gets the type string of the class
     */
    @Override
    public String getType() {
	return HttpCensorshipTask.TYPE;
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
	HttpCensorshipDesc desc = (HttpCensorshipDesc) measurementDesc; 
	return "Censorship Task [HTTP " + desc.method + "]\n  Target: " + desc.url +
	    "\n  Interval (sec): " + desc.intervalSec + "\n  Next run: " +
	    desc.startTime;
    }	

    /** 
     * TODO Unsure why this exists...doesn't look too important though
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
	if(newDuration<0){
	    this.duration=0;
	}else{
	    this.duration=newDuration;
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
	
    }
}


    
    