Mobilyzer
=========

Mobilyzer is a network measurment platform, developed for Android applications. Apps can include Mobilyzer to issue various types of measurement experiments from the server and client side.

=============================

##Important: How to Integrate Mobilyzer for Mobile Apps

**Note**   
In the provided version of Cronet library certificate verification is disabled for QUIC and is set up to ignore certificate errors HTTPS, thus communication using CronetHttpTask and QuicHttpTask is not secure.  

##Important: How to Integrate Mobilyzer for Mobile Apps

You need Android Studio to integrate Mobilyzer into your Android apps.  

**Cloning source code:**  
1. Create a **mobilibs** directory next to your app directory.  
2. git clone the ExoPlayer and Mobilyzer repositories to it.  
3. Add the following lines to the **settings.gradle** of your app:  

&nbsp;&nbsp;`include ':mobilibs:Mobilyzer:Mobilyzer'`  
&nbsp;&nbsp;`include ':mobilibs:ExoPlayer:library'`  

**Configuring app project:**  
<ol>
<li>Create **quiclibs** folder next to **src** folder of your app, copy jars from the **quiclibs** folder in Mobilyzer to it and add the following to the dependencies of your app’s **build.gradle** file</li>  

&nbsp;&nbsp;`dependencies {`  
&nbsp;&nbsp;&nbsp;&nbsp;`apk files('quiclibs/base_java.jar')`  
&nbsp;&nbsp;&nbsp;&nbsp;`apk files('quiclibs/jsr_305_javalib.jar')`  
&nbsp;&nbsp;&nbsp;&nbsp;`apk files('quiclibs/net_java.jar')`  
&nbsp;&nbsp;&nbsp;&nbsp;`apk files('quiclibs/url_java.jar')`  
&nbsp;&nbsp;`}`  

<li>Create a **jniLibs** folder under **src/main** of your project and copy the contents of the **jniLibs** folder in Mobilyzer to it, add reference to it to the `android {}` section your app’s **build.gradle** file</li>  

&nbsp;&nbsp;`sourceSets {`  
&nbsp;&nbsp;&nbsp;&nbsp;` main {`  
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;`jniLibs.srcDirs = ['src/main/jniLibs'] `  
&nbsp;&nbsp;&nbsp;&nbsp;`}`  
&nbsp;&nbsp;`}`  

<li>Add Mobilyzer library project as a compile dependency in your app's **build.gradle** file</li>  

&nbsp;&nbsp;`dependencies {`  
&nbsp;&nbsp;&nbsp;&nbsp;`compile project(':mobilibs:Mobilyzer:Mobilyzer')`  
  
&nbsp;&nbsp;&nbsp;&nbsp;`apk files('quiclibs/base_java.jar')`  
&nbsp;&nbsp;&nbsp;&nbsp;`apk files('quiclibs/jsr_305_javalib.jar')`  
&nbsp;&nbsp;&nbsp;&nbsp;`apk files('quiclibs/net_java.jar')`  
&nbsp;&nbsp;&nbsp;&nbsp;`apk files('quiclibs/url_java.jar')`  
&nbsp;&nbsp;`}`
</ol>

