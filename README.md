Mobilyzer
=========

Mobilyzer is a network measurment platform, developed for Android applications. Apps can include Mobilyzer to issue various types of measurement experiments from the server and client side.

=============================

##Important: How to Integrate Mobilyzer for Mobile Apps

You need Eclipse or ADT bundle to integrate Mobilyzer into your Android apps.

1. Clone this project and import it into your Eclipse workspace.
2. Add a reference to the Mobilyzer project library project to your app with this guide (http://developer.android.com/tools/projects/projects-eclipse.html#ReferencingLibraryProject). 
3. You must add "manifestmerger.enabled=true" to your application's project.properties. Forget to enable manifestmerger will cause no scheduler error.
4. Add google play service library project (http://developer.android.com/google/play-services/setup.html).
5. Add all the jar files in libs folder to your build path.

===============================

## Context-Triggered Measurement Framework

One common challenge of crowdsourcing active measurements is that due to the scarce power and data resource in mobile devices, we have to carefully schedule the measurement tasks and get representative data with minimal overhead. If we simply do measurements at fixed rate or at random time, we are very likely to either miss interesting behaviour of the network or issue lots of unnecessary measurements.

One solution to the above challenge is to support “context aware measurement”, which means we can exactly specify when to do measurements and only trigger measurements when the device is in a context that we focus on. For example, we may be interested in the cellular network performance in peak hours and only want measurements in such period. We may also want to do extra diagnose measurements only when the previous measurement indicates potential problems in the network.

This branch implements the context-triggered measurement framework atop Mobilyzer. When creating a new measurement task, the expected triggered condition can be specified. Mobilyzer will conduct the measurement only when the specified condition is met.

### API

To specify the triggered condtion, when creating a new measurement task, a parameter with key "prerequisites" are required. The prerequisite can be expressed as single prerequisites connected with "or" and "and". Each single prerequisite can be expressed as "PrerequisiteName CompareOperator Value".

For example, if we want to do a measurement if the network type is cellular and rssi is less than 30, or the network type is wifi and rssi is less than 20, we can express the prerequisite as "network.type<>wifi & network.cellular.rssi<30 | network.type=wifi & network.wifi.rssi<20".


### Supported context

network.type
network.wifi.rssi
network.wifi.ssid
network.wifi.rssid
network.cellular.rssi
screen.status
call.status
movement.count (the total step count)
movement.status (whether the user is moving or not)
result.type (the type of last measurement)
result.ping.avgrtt
location.coordinate
location.longitude
location.latitude
location.alitude
location.speed
activity.type (the movement activity, i.e. walking, driving etc.)
activity.lasttime (the last update time)
time.time

