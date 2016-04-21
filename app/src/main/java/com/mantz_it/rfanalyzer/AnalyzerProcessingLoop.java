package com.mantz_it.rfanalyzer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * <h1>RF Analyzer - Analyzer Processing Loop</h1>
 *
 * Module:      AnalyzerProcessingLoop.java
 * Description: This Thread will fetch samples from the incoming queue (provided by the scheduler),
 *              do the signal processing (fft) and then forward the result to the AnalyzerSurface at a
 *              fixed rate. It stabilises the rate at which the fft is generated to give the
 *              waterfall display a linear time scale.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2014 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
public class AnalyzerProcessingLoop extends Thread {
	private int fftSize = 0;					// Size of the FFT
	private int frameRate = 10;					// Frames per Second
	private double load = 0;					// Time_for_processing_and_drawing / Time_per_Frame
	private boolean dynamicFrameRate = true;	// Turns on and off the automatic frame rate control
	private boolean stopRequested = true;		// Will stop the thread when set to true
	private float[] mag = null;					// Magnitude of the frequency spectrum

	private static final String LOGTAG = "AnalyzerProcessingLoop";
	private static final int MAX_FRAMERATE = 30;		// Upper limit for the automatic frame rate control
	private static final double LOW_THRESHOLD = 0.65;	// at every load value below this threshold we increase the frame rate
	private static final double HIGH_THRESHOLD = 0.85;	// at every load value above this threshold we decrease the frame rate

	private AnalyzerSurface view;
	private FFT fftBlock = null;
	private ArrayBlockingQueue<SamplePacket> inputQueue = null;		// queue that delivers sample packets
	private ArrayBlockingQueue<SamplePacket> returnQueue = null;	// queue to return unused buffers
	private Context context;

	/**
	 * Constructor. Will initialize the member attributes.
	 *
	 * @param view			reference to the AnalyzerSurface for drawing
	 * @param fftSize		Size of the FFT
	 * @param inputQueue	queue that delivers sample packets
	 * @param returnQueue	queue to return unused buffers
	 */
	public AnalyzerProcessingLoop(AnalyzerSurface view, int fftSize,
				ArrayBlockingQueue<SamplePacket> inputQueue, ArrayBlockingQueue<SamplePacket> returnQueue,Context context) {
		this.view = view;

		// Check if fftSize is a power of 2
		int order = (int)(Math.log(fftSize) / Math.log(2));
		if(fftSize != (1<<order))
			throw new IllegalArgumentException("FFT size must be power of 2");
		this.fftSize = fftSize;

		this.fftBlock = new FFT(fftSize);
		this.mag = new float[fftSize];
		this.inputQueue = inputQueue;
		this.returnQueue = returnQueue;
		this.context = context;
	}

	public int getFrameRate() {
		return frameRate;
	}

	public void setFrameRate(int frameRate) {
		this.frameRate = frameRate;
	}

	public boolean isDynamicFrameRate() {
		return dynamicFrameRate;
	}

	public void setDynamicFrameRate(boolean dynamicFrameRate) {
		this.dynamicFrameRate = dynamicFrameRate;
	}

	public int getFftSize() { return fftSize; }

	/**
	 * Will start the processing loop
	 */
	@Override
	public void start() {
		this.stopRequested = false;
		super.start();
	}

	/**
	 * Will set the stopRequested flag so that the processing loop will terminate
	 */
	public void stopLoop() {
		this.stopRequested = true;
	}

	/**
	 * @return true if loop is running; false if not.
	 */
	public boolean isRunning() {
		return !stopRequested;
	}

	@Override
	public void run() {
		Log.d(LOGTAG,"Processing loop started. (Thread: " + this.getName() + ")");
		long startTime;		// timestamp when signal processing is started
		long sleepTime;		// time (in ms) to sleep before the next run to meet the frame rate
		long frequency;		// center frequency of the incoming samples
		int sampleRate;		// sample rate of the incoming samples
		FileOutputStream fo = null;
		OutputStreamWriter fw = null;

		try {
			File mydir = context.getDir("RFAnalyzer", Context.MODE_PRIVATE);
			if(!mydir.exists())
			{
				mydir.mkdirs();
			}
			File f = new File(mydir, "a.txt");
			//File f = new File("/analyzer/"+this.getName()+new Date().getTime()+".txt");

			f.createNewFile();
			fo = new FileOutputStream(f,true);
			fw =new OutputStreamWriter(fo);
			fw.write(this.getName() + "\n");
		} catch (IOException e) {
			Log.e(this.getClass().getName(), "file cannot be processed", e);
		}
		while(!stopRequested) {
			// store the current timestamp
			startTime = System.currentTimeMillis();

			// fetch the next samples from the queue:
			SamplePacket samples;
			try {
				samples = inputQueue.poll(1000 / frameRate, TimeUnit.MILLISECONDS);
				if (samples == null) {
					Log.d(LOGTAG, "run: Timeout while waiting on input data. skip.");
					continue;
				}
			} catch (InterruptedException e) {
				Log.e(LOGTAG, "run: Interrupted while polling from input queue. stop.");
				this.stopLoop();
				break;
			}

			frequency = samples.getFrequency();
			sampleRate = samples.getSampleRate();

			// do the signal processing:
			this.doProcessing(samples);

			// return samples to the buffer pool
			returnQueue.offer(samples);

			// Push the results on the surface:
			view.draw(mag, frequency, sampleRate, frameRate, load);
			if(fw!=null){
				//Log.d("mojgan",frequency+"\t"+sampleRate+"\t"+frameRate+"\t"+load+"\n");
				//try {
					//fw.write(Arrays.toString(mag)+"\t");
					//fw.write(frequency+"\t");
					//fw.write(sampleRate+"\t");
					//fw.write(frameRate+"\t");
					//fw.write(load+"\n");
				/////} catch (IOException e) {
				//	e.printStackTrace();
				//}
			}


			// Calculate the remaining time in this frame (according to the frame rate) and sleep
			// for that time:
			sleepTime = (1000/frameRate)-(System.currentTimeMillis() - startTime);
			try {
				if (sleepTime > 0) {
					// load = processing_time / frame_duration
					load = (System.currentTimeMillis() - startTime) / (1000.0 / frameRate);

					// Automatic frame rate control:
					if(dynamicFrameRate && load < LOW_THRESHOLD && frameRate < MAX_FRAMERATE)
						frameRate++;
					if(dynamicFrameRate && load > HIGH_THRESHOLD && frameRate > 1)
						frameRate--;

					//Log.d(LOGTAG,"FrameRate: " + frameRate + ";  Load: " + load + "; Sleep for " + sleepTime + "ms.");
					sleep(sleepTime);
				}
				else {
					// Automatic frame rate control:
					if(dynamicFrameRate && frameRate > 1)
						frameRate--;

					//Log.d(LOGTAG, "Couldn't meet requested frame rate!");
					load = 1;
				}
			} catch (Exception e) {
				Log.e(LOGTAG,"Error while calling sleep()");
			}
		}
		if(fw!=null){
			try {
				fo.flush();
				fw.flush();
				fo.close();
				fw.close();

			} catch (IOException e) {
				Log.e(LOGTAG, "file cannot be closed", e);
			}finally {
				try {
					fw.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}


		}

		this.stopRequested = true;
		Log.d(LOGTAG,"Processing loop stopped. (Thread: " + this.getName() + ")");
	}

	/**
	 * This method will do the signal processing (fft) on the given samples
	 *
	 * @param samples	input samples for the signal processing
	 */
	public void doProcessing(SamplePacket samples) {
		float[] re=samples.re(), im=samples.im();
		// Multiply the samples with a Window function:
		this.fftBlock.applyWindow(re, im);

		// Calculate the fft:
		this.fftBlock.fft(re, im);

		// Calculate the logarithmic magnitude:
		float realPower;
		float imagPower;
		int size = samples.size();
		for (int i = 0; i < size; i++) {
			// We have to flip both sides of the fft to draw it centered on the screen:
			int targetIndex = (i+size/2) % size;

			// Calc the magnitude = log(  re^2 + im^2  )
			// note that we still have to divide re and im by the fft size
			realPower = re[i]/fftSize;
			realPower = realPower * realPower;
			imagPower = im[i]/fftSize;
			imagPower = imagPower * imagPower;
			mag[targetIndex] = (float) (10* Math.log10(Math.sqrt(realPower + imagPower)));
		}
	}
	public static void appendLog(String appendString){
		String header = "# Timestamp, frequency, RSS\n";
		Date d=new Date();
		int day = d.getDate();
		int mon = d.getMonth()+1;
		int yr = d.getYear();
		String logFileName = "/logs/"+mon+"_"+day+"_"+yr+"_trrss.txt";
		File logFile;
		File dir;
		if (isExternalStorageWritable())
		{
			logFile = new File(Environment.getExternalStorageDirectory(), logFileName);
			dir = new File(Environment.getExternalStorageDirectory(), "/logs");
			if (!logFile.exists()){
				try	{
					dir.mkdirs();
					logFile.createNewFile();
					//BufferedWriter for performance, true to set append to file flag
					BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
					buf.append(header);
					buf.newLine();
					buf.close();
				}
				catch (IOException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			try
			{
				//BufferedWriter for performance, true to set append to file flag
				BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
				buf.append(appendString);
				buf.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}}
	}
	public static void appendLog(List<LogRecord> Records)
	{
		String header = "# Timestamp, MAC Address of AP, RSS\n";
		LogRecord writeRecord;
		LogRecord writeLR;
		Date d=new Date();
		int day = d.getDate();
		int mon = d.getMonth()+1;
		int yr = d.getYear();
		String logFileName = "/logs/"+mon+"_"+day+"_"+yr+"_wifirss.txt";
		File logFile;
		File dir;

		if (isExternalStorageWritable())
		{
			logFile = new File(Environment.getExternalStorageDirectory(), logFileName);
			dir = new File(Environment.getExternalStorageDirectory(), "/logs");



			if (!logFile.exists())
			{
				try
				{

					dir.mkdirs();
					logFile.createNewFile();
					//BufferedWriter for performance, true to set append to file flag
					BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
					buf.append(header);
					buf.newLine();
					buf.close();
				}
				catch (IOException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			try
			{
				//BufferedWriter for performance, true to set append to file flag
				BufferedWriter buf = new BufferedWriter(new FileWriter(logFile, true));
				for (int i = 0; i < Records.size(); ++i) {
					writeRecord = Records.get(i);
					buf.append(writeRecord.toString());

				}

				buf.close();
			}
			catch (IOException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}}
	}
	public static boolean isExternalStorageWritable() {
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)) {
			return true;
		}
		return false;
	}
}
