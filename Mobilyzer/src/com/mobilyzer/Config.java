package com.mobilyzer;


/**
 * The system defaults.
 */

public interface Config {
  // Important: keep same with the version_code and version_name in strings.xml
  public static final String version = "3";
  /**
   * Strings migrated from string.xml
   */
  public static final String SERVER_URL = "https://openmobiledata.appspot.com";
  public static final String ANONYMOUS_SERVER_URL = "https://openmobiledata.appspot.com/anonymous";
  public static final String TEST_SERVER_URL = "";
  public static final String DEFAULT_USER = "Anonymous";

  public static final int MAX_TASK_QUEUE_SIZE = 100;

  public static final String USER_AGENT = "Mobilyzer-" + version + " (Linux; Android)";
  public static final String PING_EXECUTABLE = "ping";
  public static final String PING6_EXECUTABLE = "ping6";
  
  public static final String SERVER_TASK_CLIENT_KEY = "LibraryServerTask";
  public static final String CHECKIN_KEY = "MobilyzerCheckin";

  public static final String TASK_STARTED = "TASK_STARTED";
  public static final String TASK_FINISHED = "TASK_FINISHED";
  public static final String TASK_PAUSED = "TASK_PAUSED";
  public static final String TASK_RESUMED = "TASK_RESUMED";
  public static final String TASK_CANCELED = "TASK_CENCELED";
  public static final String TASK_STOPPED = "TASK_STOPPED";
  public static final String TASK_RESCHEDULED = "TASK_RESCHEDULED";


  /** Types for message between API and scheduler**/
  public static final int MSG_SUBMIT_TASK = 1;
  public static final int MSG_RESULT = 2;
  public static final int MSG_CANCEL_TASK = 3;
  public static final int MSG_SET_BATTERY_THRESHOLD = 4;
  public static final int MSG_GET_BATTERY_THRESHOLD = 5;
  public static final int MSG_SET_CHECKIN_INTERVAL = 6;
  public static final int MSG_GET_CHECKIN_INTERVAL = 7;
  public static final int MSG_GET_TASK_STATUS = 8;
  public static final int MSG_SET_DATA_USAGE = 9;
  public static final int MSG_GET_DATA_USAGE = 10;
  public static final int MSG_REGISTER_CLIENTKEY = 11;
  public static final int MSG_UNREGISTER_CLIENTKEY = 12;
  public static final int MSG_SET_AUTH_ACCOUNT = 13;
  public static final int MSG_GET_AUTH_ACCOUNT = 14;

  /** The default battery level if we cannot read it from the system */
  public static final int DEFAULT_BATTERY_LEVEL = 0;
  /** The default maximum battery level if we cannot read it from the system */
  public static final int DEFAULT_BATTERY_SCALE = 100;

  /** Tasks expire in a bit more than two days. Expired tasks will be removed from the scheduler */
  public static final long TASK_EXPIRATION_MSEC = 2 * 24 * 3600 * 1000 + 1800 * 1000;
  /** Default interval in seconds between system measurements of a given measurement type */
  public static final double DEFAULT_SYSTEM_MEASUREMENT_INTERVAL_SEC = 15 * 60;
  /** Default interval in seconds between context collection */
  public static final int DEFAULT_CONTEXT_INTERVAL_SEC = 2;
  public static final int MAX_CONTEXT_INFO_COLLECTIONS_PER_TASK = 120;



  // TODO check these static values
  public static final int DEFAULT_DNS_COUNT_PER_MEASUREMENT = 1;
  public static final int PING_COUNT_PER_MEASUREMENT = 10;
  public static final float PING_FILTER_THRES = (float) 1.4;
  public static final double DEFAULT_INTERVAL_BETWEEN_ICMP_PACKET_SEC = 0.5;


  public static final int TRACEROUTE_TASK_DURATION = 4 * 30 * 500;
  public static final int DEFAULT_DNS_TASK_DURATION = 0;
  public static final int DEFAULT_HTTP_TASK_DURATION = 0;
  public static final int DEFAULT_PING_TASK_DURATION = PING_COUNT_PER_MEASUREMENT * 500;
  public static final int DEFAULT_UDPBURST_DURATION = 30 * 1000;
  public static final int DEFAULT_PARALLEL_TASK_DURATION = 60 * 1000;
  public static final int DEFAULT_TASK_DURATION_TIMEOUT = 60 * 1000;
  public static final int DEFAULT_RRC_TASK_DURATION = 30 * 60 * 1000;
  public static final int MAX_TASK_DURATION = 15 * 60 * 1000;//TODO


  // Keys in SharedPrefernce
  public static final String PREF_KEY_SELECTED_ACCOUNT = "PREF_KEY_SELECTED_ACCOUNT";
  public static final String PREF_KEY_BATTERY_THRESHOLD = "PREF_KEY_BATTERY_THRESHOLD";
  public static final String PREF_KEY_CHECKIN_INTERVAL = "PREF_KEY_CHECKIN_INTERVAL";
  public static final String PREF_KEY_DATA_USAGE_PROFILE = "PREF_KEY_DATA_USAGE_PROFILE";


  public static final int MIN_BATTERY_THRESHOLD = 20;
  public static final int MAX_BATTERY_THRESHOLD = 100;
  public static final int DEFAULT_BATTERY_THRESH_PRECENT = 60;
  
  // The default checkin interval in seconds
  public static final long DEFAULT_CHECKIN_INTERVAL_SEC = 60 * 60L;
  public static final long MIN_CHECKIN_INTERVAL_SEC = 3600L;
  public static final long MAX_CHECKIN_INTERVAL_SEC = 24 * 3600L;
  public static final long MIN_CHECKIN_RETRY_INTERVAL_SEC = 20L;
  public static final long MAX_CHECKIN_RETRY_INTERVAL_SEC = 60L;
  public static final int MAX_CHECKIN_RETRY_COUNT = 3;
  public static final long PAUSE_BETWEEN_CHECKIN_CHANGE_MSEC = 1 * 60 * 1000L;
  
  public static final int DEFAULT_DATA_MONITOR_PERIOD_DAY= 1;
  
  // Reschedule delay for RRC task
  public static final long RESCHEDULE_DELAY = 20*60*1000;
}
