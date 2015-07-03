Mobilyzer
=========

Mobilyzer is a network measurment platform, developed for Android applications. Apps can include Mobilyzer to issue various types of measurement experiments from the server and client side.

=============================

##Important: How to Integrate Mobilyzer for Mobile Apps

You need Eclipse or ADT bundle to integrate Mobilyzer into your Android apps.

1. Clone this project and import it into your Eclipse workspace.
2. Add a reference to the Mobilyzer project library project to your app with this guide (http://developer.android.com/tools/projects/projects-eclipse.html#ReferencingLibraryProject). 
3. You must add "manifestmerger.enabled=true" to your application's project.properties. Forget to enable manifestmerger will cause no scheduler error.
4. Add  google play service library project (http://developer.android.com/google/play-services/setup.html).
5. Add ExoPlayerLib (https://github.com/mobilyzer/ExoPlayer.git).
6. Add all the jar files in libs folder to your build path.
