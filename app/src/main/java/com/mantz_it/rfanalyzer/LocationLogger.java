package com.mantz_it.rfanalyzer;


import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
//import android.support.v4.content.ContextCompat;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.Permissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
//import java.util.logging.Handler;
import android.os.Handler;

import static android.R.attr.readPermission;
import static android.R.attr.type;

/**
 * Created by harsimran on 29/3/17.
 */

public class LocationLogger {
    String aTAG = "LocationListenerclass";
    boolean takereadings = true;

    private class Readings {
        private class Reading {
            public Reading(double lat, double lon, String time) {
                mLat = lat;
                mLon = lon;
                mTime = time;
            }

            public double mLat;
            public double mLon;
            public String mTime;
        }

        private List<Reading> mReadings = new ArrayList<>();

        public void insertReading(double lat, double lon, String time) {
            Reading reading = new Reading(lat, lon, time);
            mReadings.add(reading);
        }

        public void writeReadings() {
            List<Reading> readings = new ArrayList<>(mReadings);
            mReadings.clear();
            try {
                String logFileName = "/logs/" + "GPS_trace.txt";
                File logFile = new File(Environment.getExternalStorageDirectory(), logFileName);
                File dir = new File(Environment.getExternalStorageDirectory(), "/logs");

                if (!logFile.exists()) {
                    dir.mkdir();
                    logFile.createNewFile();
                }
                
                FileOutputStream stream = new FileOutputStream(logFile, true);
                stream.write("lat, long, time\n".getBytes());
                try {
                    for(Reading r : readings) {
                        stream.write((r.mLat + "," + r.mLon + "," + r.mTime + "\n").getBytes());
                    }
                } finally {
                    stream.close();
                }
            } catch (IOException e) {
                Log.e("Exception", "File write failed: " + e.toString());
            }
        }
    }

    private LocationManager locationManager=null;
    private double longitude;
    private double latitude;
    Context context;
    TimerTask t;
    boolean locationManagerSet = false;

    boolean interrupt = true;

     private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            latitude = location.getLatitude();
            longitude = location.getLongitude();
        }

        @Override
        public void onProviderDisabled(String provider) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

    };

    public void startLocationListener(Context context) {
            Log.d(aTAG, "will request location updates");
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, locationListener);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 0,locationListener);

    }

    public void stopLocationListener() {
            locationManager.removeUpdates(locationListener);
    }

    public void stopTakingReadings(){
        this.takereadings = false;
    }

    private Timer mTimer1;
    private TimerTask mTt1;
    private Handler mTimerHandler = new Handler();

    private void stopTimer(){
        if(mTimer1 != null){
            mTimer1.cancel();
            mTimer1.purge();
        }
    }

    final Readings mReadings = new Readings();

    public void putReadings(){

    }
    private void startTimer(){
        mTimer1 = new Timer();
        mTt1 = new TimerTask() {
            public void run() {
                mTimerHandler.post(new Runnable() {
                    public void run(){
                        java.util.Date date = new java.util.Date();
                        Log.d(aTAG, Double.toString(latitude));
                        Log.d(aTAG, Double.toString(longitude));
                        mReadings.insertReading(latitude, longitude, date.toString());
                    }
                });
            }
        };

        mTimer1.schedule(mTt1, 5000, 1000);
    }

    public void runGPSLogger(final Context context){
        locationManager = (LocationManager) context.getSystemService((Context.LOCATION_SERVICE));
        Log.d(aTAG, "trying to start location listener");
        startLocationListener(context);
        Log.d(aTAG, "sucessfully started location listener");
        startTimer();
    }


    public void stopGPSLogger(){
        stopTimer();

        mReadings.writeReadings();
    }
    /*

    public void runGPSLogger(final Context context){
        locationManager  = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        Log.d(aTAG, "trying to start location listener");
        startLocationListener(context);
        Log.d(aTAG, "sucessfully started location listener");
        final Readings mReadings = new Readings();

        Log.d(aTAG, "logging the readings");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                while(takereadings) {
                    //java.util.Date date = new java.util.Date();
                    //Log.d(aTAG, Double.toString(latitude));
                    //Log.d(aTAG, Double.toString(longitude));
                    ///mReadings.insertReading(latitude, longitude, date.toString());

                    //try {
                    //    Thread.sleep(1000);
                    //} catch (InterruptedException e) {
                    //    e.printStackTrace();
                    //}

                    new Handler(Looper.getMainLooper()).postDelayed(

                            new Runnable() {
                                @Override
                                public void run() {
                                    java.util.Date date = new java.util.Date();
                                    Log.d(aTAG, Double.toString(latitude));
                                    Log.d(aTAG, Double.toString(longitude));
                                    mReadings.insertReading(latitude, longitude, date.toString());
                                }
                            },
                            1000
                    );
                }
                mReadings.writeReadings();
                Log.d(aTAG, "Readings written to file");
                stopLocationListener(context);

            }

        }).start();
    }
    */
}

