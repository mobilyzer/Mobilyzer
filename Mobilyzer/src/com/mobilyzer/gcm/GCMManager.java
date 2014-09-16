package com.mobilyzer.gcm;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.mobilyzer.util.Logger;

import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;

public class GCMManager{
  
  public static final String EXTRA_MESSAGE = "message";
  public static final String PROPERTY_REG_ID = "registration_id";
  private static final String PROPERTY_APP_VERSION = "appVersion";
  private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
  
  

  /**
   * Substitute you own sender ID here. This is the project number you got
   * from the API Console"
   */
  String SENDER_ID = "510377927595";

  
  GoogleCloudMessaging gcm;
  AtomicInteger msgId = new AtomicInteger();
  Context context;

  String regid;
  
  public GCMManager(Context context) {
   this.context=context;
   // Check device for Play Services APK. If check succeeds, proceed with GCM registration.
   if (checkPlayServices()) {
       gcm = GoogleCloudMessaging.getInstance(context);
       regid = getRegistrationId();
       Logger.d("GCMManager: regid = "+regid);

       if (regid.isEmpty()) {
           registerInBackground();
       }
   } else {
       Logger.e("GCMManager: No valid Google Play Services APK found.");
   }

  }
    
    /**
     * Check the device to make sure it has the Google Play Services APK. If
     * it doesn't, display a dialog that allows users to download the APK from
     * the Google Play Store or enable it in the device's system settings.
     */
    public boolean checkPlayServices() {
        int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(context);
        if (resultCode != ConnectionResult.SUCCESS) {
//            if (GooglePlayServicesUtil.isUserRecoverableError(resultCode)) {
//                GooglePlayServicesUtil.getErrorDialog(resultCode, context,
//                        PLAY_SERVICES_RESOLUTION_REQUEST).show();
//            } else {
//                Logger.e("GCMManager: This device is not supported.");
//            }
          Logger.e("GCMManager: This device is not supported");
            return false;
        }
        return true;
    }
    
    /**
     * Gets the current registration ID for application on GCM service, if there is one.
     * <p>
     * If result is empty, the app needs to register.
     *
     * @return registration ID, or empty string if there is no existing
     *         registration ID.
     */
    public String getRegistrationId() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String registrationId = prefs.getString(PROPERTY_REG_ID, "");
        if (registrationId.isEmpty()) {
            Logger.e("GCMManager: Registration not found.");
            return "";
        }
        // Check if app was updated; if so, it must clear the registration ID
        // since the existing regID is not guaranteed to work with the new
        // app version.
        int registeredVersion = prefs.getInt(PROPERTY_APP_VERSION, Integer.MIN_VALUE);
        int currentVersion = getAppVersion(context);
        if (registeredVersion != currentVersion) {
            Logger.d("GCMManager: App version changed.");
            return "";
        }
        return registrationId;
    }

    /**
     * Registers the application with GCM servers asynchronously.
     * <p>
     * Stores the registration ID and the app versionCode in the application's
     * shared preferences.
     */
    private void registerInBackground() {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                String msg = "";
                try {
                    if (gcm == null) {
                        gcm = GoogleCloudMessaging.getInstance(context);
                    }
                    regid = gcm.register(SENDER_ID);
                    Logger.d("GCMManager: regid: "+regid);
                    msg = "Device registered, registration ID=" + regid;

                    // Persist the regID - no need to register again.
                    storeRegistrationId(context, regid);
                } catch (IOException ex) {
                    msg = "Error :" + ex.getMessage();
                    // If there is an error, don't just keep trying to register.
                    // Require the user to click a button again, or perform
                    // exponential back-off.
                }
                return msg;
            }

            
        }.execute(null, null, null);
    }
    
    /**
     * Stores the registration ID and the app versionCode in the application's
     * {@code SharedPreferences}.
     *
     * @param context application's context.
     * @param regId registration ID
     */
    private void storeRegistrationId(Context context, String regId) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int appVersion = getAppVersion(context);
        Logger.d("GCMManager: Saving regId on app version " + appVersion);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PROPERTY_REG_ID, regId);
        editor.putInt(PROPERTY_APP_VERSION, appVersion);
        editor.commit();
    }
    /**
     * @return Application's version code from the {@code PackageManager}.
     */
    private static int getAppVersion(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (NameNotFoundException e) {
            // should never happen
            throw new RuntimeException("Could not get package name: " + e);
        }
    }


}
