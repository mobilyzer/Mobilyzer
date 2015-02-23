package com.mobilyzer.util;

import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;

import android.app.IntentService;
import android.content.Intent;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

public class ActivityIntentService extends IntentService {
	public static final String ACTION_ACTIVITY_UPDATE = "activity update from ActivityService";
	
	public ActivityIntentService(){
		this("com.mobilyzer.util.ActivityIntentService");
	}
	
	public ActivityIntentService(String name) {
		super(name);
	}

	@Override
	protected void onHandleIntent(Intent intent) {
		if (ActivityRecognitionResult.hasResult(intent)) {
			ActivityRecognitionResult result = ActivityRecognitionResult.extractResult(intent);
			DetectedActivity mostProbableActivity = result.getMostProbableActivity();
			// Get the confidence % (probability)
			int confidence = mostProbableActivity.getConfidence();
			// Get the type.
			int activityType = mostProbableActivity.getType();
			Log.i("xsc","activityType:"+activityType+" confidence:"+confidence);
			/* types:
			* DetectedActivity.IN_VEHICLE
			* DetectedActivity.ON_BICYCLE
			* DetectedActivity.ON_FOOT
			* DetectedActivity.STILL
			* DetectedActivity.UNKNOWN
			* DetectedActivity.TILTING
			*/
			if (confidence >= 50){
				Intent broadcastIntent = new Intent();
				broadcastIntent.setAction(ACTION_ACTIVITY_UPDATE);
				broadcastIntent.putExtra("activityType", activityType);
				broadcastIntent.putExtra("confidence", confidence);
				LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
			}
		}
	}
}
