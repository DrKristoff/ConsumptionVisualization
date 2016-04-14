/**
 * Copyright (c) 2014 Google, Inc. All rights reserved.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ryandymock.consumptionvisualization;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v7.app.AlertDialog;
import android.support.v7.appcompat.BuildConfig;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.pires.obd.commands.ObdCommand;
import com.github.pires.obd.enums.AvailableCommandNames;
import com.ryandymock.consumptionvisualization.activity.ConfigActivity;
import com.ryandymock.consumptionvisualization.config.ObdConfig;
import com.ryandymock.consumptionvisualization.io.AbstractGatewayService;
import com.ryandymock.consumptionvisualization.io.MockObdGatewayService;
import com.ryandymock.consumptionvisualization.io.ObdCommandJob;
import com.ryandymock.consumptionvisualization.io.ObdGatewayService;
import com.ryandymock.consumptionvisualization.tool.Tool;
import com.ryandymock.consumptionvisualization.tool.Tool.ToolType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import roboguice.RoboGuice;

public class VisualizeActivity extends Activity {
  private static final String TAG = VisualizeActivity.class.getName();
  static String sVersionName;
  private Controller mController;
  private GLSurfaceView mWorldView;
  private Handler mHandler, mQueueHandler;
  private Runnable mRepeat;
  int milliOffset = 50;

  // Mapping from ImageView to actual RGB values
  private SparseIntArray mColorMap;
  // Mapping from ImageView of color palette to the images for Tools
  private SparseIntArray mPencilImageMap;
  private SparseIntArray mRigidImageMap;
  private SparseIntArray mWaterImageMap;
  // List of ImageView of color palettes
  private List<View> mRigidColorPalette;
  private List<View> mWaterColorPalette;

  private float mWaterDropsPerS = 0;

  // The image view of the selected tool
  private ImageView mSelected;
  // The current open palette
  private List<View> mOpenPalette = null;

    private static final int NO_BLUETOOTH_ID = 0;
  private static final int BLUETOOTH_DISABLED = 1;
  private static final int START_LIVE_DATA = 2;
  private static final int STOP_LIVE_DATA = 3;
  private static final int SETTINGS = 4;
  private static final int GET_DTC = 5;
  private static final int TABLE_ROW_MARGIN = 7;
  private static final int NO_ORIENTATION_SENSOR = 8;
  private static final int NO_GPS_SUPPORT = 9;
  private static final int TRIPS_LIST = 10;
  private static final int SAVE_TRIP_NOT_AVAILABLE = 11;
  private static final int REQUEST_ENABLE_BT = 1234;

  private static final float MASS_AIR_FLOW_RATIO = 14.7f;

  private TextView liveMAFTextView;

  private boolean mUsingTool = false;
  private int mDripDelay_ms = 99999;

  private PowerManager powerManager;
  private static boolean bluetoothDefaultIsEnable = false;
  private SharedPreferences prefs;

  public Map<String, String> commandResult = new HashMap<String, String>();

  private OnTouchListener mTouchListener;
  private View.OnClickListener mClickListener;

  static {


  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    System.loadLibrary("liquidfun_jni");
    System.loadLibrary("liquidfun");

    // Set the ToolBar layout
    setContentView(R.layout.tools_layout);

    liveMAFTextView = (TextView) findViewById(R.id.textViewMAF);

    powerManager =
            (PowerManager) this.getSystemService(Context.POWER_SERVICE);

    prefs = getSharedPreferences("shared_prefs", MODE_PRIVATE);

    final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
    if (btAdapter != null)
      bluetoothDefaultIsEnable = btAdapter.isEnabled();

    mColorMap = new SparseIntArray();
    mPencilImageMap = new SparseIntArray();
    mRigidImageMap = new SparseIntArray();
    mWaterImageMap = new SparseIntArray();
    mRigidColorPalette = new ArrayList<View>();
    mWaterColorPalette = new ArrayList<View>();
    String pencilPrefix = "pencil_";
    String rigidPrefix = "rigid_";
    String waterPrefix = "water_";
    String rigidColorPrefix = "color_rigid_";
    String waterColorPrefix = "color_water_";

    Resources r = getResources();
    // Look up all the different colors
    for (int i = 1; i <= r.getInteger(R.integer.num_colors); ++i) {
      // Get color palette for rigid/pencil tools
      // 1) Add color RGB values to mColorMap
      // 2) Add appropriate images for tool
      // 3) Add the color palette view to the color palette list
      int viewId = r.getIdentifier(
              rigidColorPrefix + i, "id", getPackageName());
      mColorMap.append(
              viewId, getColor(rigidColorPrefix + i, "color"));
      mPencilImageMap.append(
              viewId, r.getIdentifier(pencilPrefix + i,
                      "drawable",
                      getPackageName()));
      mRigidImageMap.append(
              viewId, r.getIdentifier(rigidPrefix + i,
                      "drawable",
                      getPackageName()));
      mRigidColorPalette.add(findViewById(viewId));

      // Get color palette for water tool
      // 1) Add color RGB values to mColorMap
      // 2) Add appropriate images for tool
      // 3) Add the color palette view to the color palette list
      viewId = r.getIdentifier(
              waterColorPrefix + i, "id", getPackageName());
      mColorMap.append(
              viewId, getColor(waterColorPrefix + i, "color"));
      mWaterImageMap.append(
              viewId, r.getIdentifier(waterPrefix + i,
                      "drawable",
                      getPackageName()));
      mWaterColorPalette.add(findViewById(viewId));
    }

    // Add the ending piece to both palettes
    int paletteEndViewId = r.getIdentifier(
            rigidColorPrefix + "end", "id", getPackageName());
    mRigidColorPalette.add(findViewById(paletteEndViewId));
    paletteEndViewId = r.getIdentifier(
            waterColorPrefix + "end", "id", getPackageName());
    mWaterColorPalette.add(findViewById(paletteEndViewId));

    Renderer renderer = Renderer.getInstance();
    Renderer.getInstance().init(this);
    mController = new Controller(this);

    // Set up the OpenGL WorldView
    mWorldView = (GLSurfaceView) findViewById(R.id.world);
    mWorldView.setEGLContextClientVersion(2);
    mWorldView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    mWorldView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
    if (BuildConfig.DEBUG) {
      mWorldView.setDebugFlags(
              GLSurfaceView.DEBUG_LOG_GL_CALLS |
                      GLSurfaceView.DEBUG_CHECK_GL_ERROR);
    }
    //
  setPreserveEGLContextOnPause();


    mWorldView.setRenderer(renderer);
    renderer.startSimulation();

    // Set default tool colors
    Tool.getTool(ToolType.PENCIL).setColor(
            getColor(getString(R.string.default_pencil_color), "color"));
    Tool.getTool(ToolType.RIGID).setColor(
            getColor(getString(R.string.default_rigid_color), "color"));
    Tool.getTool(ToolType.WATER).setColor(
            getColor(getString(R.string.default_water_color), "color"));

    mController.setTool(ToolType.WATER);

    mHandler = new Handler();
    mQueueHandler = new Handler();

    mRepeat = new Runnable() {
      public void run() {
        simulateDrip();
        //if(simulationRunning){
        Log.d("RCD",String.valueOf(mDripDelay_ms));
          mHandler.postDelayed(this, mDripDelay_ms);
        //}

      }
    };

    mTouchListener = new OnTouchListener(){

      @Override
      public boolean onTouch(View v, MotionEvent event) {
        if(v.getId()==R.id.world){
          return onTouchCanvas(v, event);
        }
        return false;
      }
    };

    mClickListener = new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        switch (v.getId()) {
          case R.id.button_restart:
            Renderer.getInstance().reset();
            mController.reset();
            break;
          case R.id.settings:
            startActivity(new Intent(getApplicationContext(), ConfigActivity.class));
            break;
          case R.id.beginSimulationImageView:
            startLiveData();
            break;
          case R.id.stopSimulationImageView:
            stopLiveData();
            break;
          case R.id.world:
            break;
          default:
            break;
        }


      }
    };

    findViewById(R.id.button_restart).setOnClickListener(mClickListener);
    findViewById(R.id.beginSimulationImageView).setOnClickListener(mClickListener);
    findViewById(R.id.stopSimulationImageView).setOnClickListener(mClickListener);
    findViewById(R.id.settings).setOnClickListener(mClickListener);

    //mWorldView.setOnTouchListener(mTouchListener);

  }



  static {
    RoboGuice.setUseAnnotationDatabases(false);
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  private void setPreserveEGLContextOnPause() {
    mWorldView.setPreserveEGLContextOnPause(true);
  }

  private boolean isServiceBound;
  private AbstractGatewayService service;

  private PowerManager.WakeLock wakeLock = null;
  private boolean preRequisites = true;

  private int getColor(String name, String defType) {
    Resources r = getResources();
    int id = r.getIdentifier(name, defType, getPackageName());
    int color = r.getColor(id);
    // ARGB to ABGR
    int red = (color >> 16) & 0xFF;
    int blue = (color << 16) & 0xFF0000;
    return (color & 0xFF00FF00) | red | blue;
  }

  private ServiceConnection serviceConn = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder binder) {
      log("Service is Bound!  (onServiceConnected callback)");
      Log.d(TAG, className.toString() + " service is bound");
      isServiceBound = true;
      service = ((AbstractGatewayService.AbstractGatewayServiceBinder) binder).getService();
      service.setContext(VisualizeActivity.this);
      renderSimulation(true);
      Log.d(TAG, "Starting live data");

      try {
        service.startService();
      } catch (IOException ioe) {
        Log.e(TAG, "Failure Starting live data");
        doUnbindService();
        Toast.makeText(getApplicationContext(),"Please Select a Bluetooth Device",Toast.LENGTH_SHORT).show();
        renderSimulation(false);
      }
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
      return super.clone();
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
      Log.d(TAG, className.toString() + " service is unbound");
      isServiceBound = false;
    }
  };

  public static String LookUpCommand(String txt) {
    for (AvailableCommandNames item : AvailableCommandNames.values()) {
      if (item.getValue().equals(txt)) return item.name();
    }
    return txt;
  }

  private void queueCommands() {
    if (isServiceBound) {
      for (ObdCommand Command : ObdConfig.getCommands()) {
        service.queueJob(new ObdCommandJob(Command));
      }
    }
  }

  private final Runnable mQueueCommands = new Runnable() {
    public void run() {
      Log.d("RCD","mQueueCommands");
      if (service != null && service.isRunning() && service.queueEmpty()) {
        queueCommands();
      }
      // run again in period defined in preferences
      mQueueHandler.postDelayed(mQueueCommands, ConfigActivity.getObdUpdatePeriod(prefs));
      //mQueueHandler.postDelayed(mQueueCommands, 1000);
    }
  };

  public void stateUpdate(final ObdCommandJob job) {
    final String cmdName = job.getCommand().getName();
    String cmdResult = "";
    Log.d("RCD",cmdName);
    //Toast.makeText(getApplicationContext(), cmdName, Toast.LENGTH_SHORT).show();
    final String cmdID = LookUpCommand(cmdName);
    if (cmdName.equals("Mass Air Flow")) {
      String result = job.getCommand().getCalculatedResult();
      try {
        Float floatResult = Float.parseFloat(result);
        setDripRatefromMAF(floatResult);

      } catch (Exception e) {

      }
    }

    if (job.getState().equals(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR)) {
      cmdResult = job.getCommand().getResult();
      if (cmdResult != null) {
        //obdStatusTextView.setText(cmdResult.toLowerCase());
      }
    } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.NOT_SUPPORTED)) {
      cmdResult = getString(R.string.status_obd_no_support);
    } else {
      cmdResult = job.getCommand().getFormattedResult();
      //obdStatusTextView.setText(getString(R.string.status_obd_data));
    }
    commandResult.put(cmdID, cmdResult);
    //updateTripStatistic(job, cmdID);
  }

  @Override
  protected void onStart() {
    super.onStart();
    Log.d(TAG, "Entered onStart...");
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    releaseWakeLockIfHeld();
    if (isServiceBound) {
      doUnbindService();
    }


    final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
    if (btAdapter != null && btAdapter.isEnabled() && !bluetoothDefaultIsEnable)
      btAdapter.disable();
  }


  /**
   * If lock is held, release. Lock will be held when the service is running.
   */
  private void releaseWakeLockIfHeld() {
    if (wakeLock.isHeld())
      wakeLock.release();
  }

  protected void onResume() {
    super.onResume();
    Log.d(TAG, "Resuming..");
    mController.onResume();
    mWorldView.onResume();
    Renderer.getInstance().totalFrames = -10000;
    wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,
            "ObdReader");

    // get Bluetooth device
    final BluetoothAdapter btAdapter = BluetoothAdapter
            .getDefaultAdapter();

    preRequisites = btAdapter != null && btAdapter.isEnabled();
    if (!preRequisites && prefs.getBoolean(ConfigActivity.ENABLE_BT_KEY, false)) {
      preRequisites = btAdapter != null && btAdapter.enable();
    }

    if (!preRequisites) {
      showDialog(BLUETOOTH_DISABLED);
      //btStatusTextView.setText(getString(R.string.status_bluetooth_disabled));
    } else {
      //btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
    }
  }

  private void log(String text) {
    //Toast.makeText(this,text,Toast.LENGTH_SHORT).show();
  }


  private void startLiveData() {
    Log.d(TAG, "Starting live data..");
    Toast.makeText(getApplicationContext(),"Attempting connection to Bluetooth Device",Toast.LENGTH_SHORT).show();
    renderSimulation(true);

    //tl.removeAllViews(); //start fresh
    doBindService();
    log("doBindService has been called");

        // screen won't turn off until wakeLock.release()
    wakeLock.acquire();


    }

  private void stopLiveData() {
    Log.d(TAG, "Stopping live data..");
    Toast.makeText(getApplicationContext(),"Pausing Simulation",Toast.LENGTH_SHORT).show();

    Renderer.getInstance().pauseSimulation();
    doUnbindService();

    releaseWakeLockIfHeld();

  }

  protected Dialog onCreateDialog(int id) {
    AlertDialog.Builder build = new AlertDialog.Builder(this);
    switch (id) {
      case NO_BLUETOOTH_ID:
        build.setMessage(getString(R.string.text_no_bluetooth_id));
        return build.create();
      case BLUETOOTH_DISABLED:
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        return build.create();
      case NO_ORIENTATION_SENSOR:
        build.setMessage(getString(R.string.text_no_orientation_sensor));
        return build.create();
      case NO_GPS_SUPPORT:
        build.setMessage(getString(R.string.text_no_gps_support));
        return build.create();
      case SAVE_TRIP_NOT_AVAILABLE:
        build.setMessage(getString(R.string.text_save_trip_not_available));
        return build.create();
    }
    return null;
  }

  public boolean onPrepareOptionsMenu(Menu menu) {
    MenuItem startItem = menu.findItem(START_LIVE_DATA);
    MenuItem stopItem = menu.findItem(STOP_LIVE_DATA);
    MenuItem settingsItem = menu.findItem(SETTINGS);
    MenuItem getDTCItem = menu.findItem(GET_DTC);

    if (service != null && service.isRunning()) {
      getDTCItem.setEnabled(false);
      startItem.setEnabled(false);
      stopItem.setEnabled(true);
      settingsItem.setEnabled(false);
    } else {
      getDTCItem.setEnabled(true);
      stopItem.setEnabled(false);
      startItem.setEnabled(true);
      settingsItem.setEnabled(true);
    }

    return true;
  }

  private void doBindService() {
    if (!isServiceBound) {
      Log.d(TAG, "Binding OBD service..");
      if (preRequisites) {
        //btStatusTextView.setText(getString(R.string.status_bluetooth_connecting));
        Intent serviceIntent = new Intent(this, ObdGatewayService.class);
        Boolean isBound = bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
        log(String.valueOf(isBound));
      } else {
        //btStatusTextView.setText(getString(R.string.status_bluetooth_disabled));
        Intent serviceIntent = new Intent(this, MockObdGatewayService.class);
        bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
      }
    }
  }

  private void doUnbindService() {
    if (isServiceBound) {
      if (service.isRunning()) {
        service.stopService();
        //if (preRequisites)
        //btStatusTextView.setText(getString(R.string.status_bluetooth_ok));
      }
      Log.d(TAG, "Unbinding OBD service..");
      unbindService(serviceConn);
      isServiceBound = false;
      //obdStatusTextView.setText(getString(R.string.status_obd_disconnected));
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode == REQUEST_ENABLE_BT) {
      if (resultCode == Activity.RESULT_OK) {
        //btStatusTextView.setText(getString(R.string.status_bluetooth_connected));
      } else {
        Toast.makeText(this, R.string.text_bluetooth_disabled, Toast.LENGTH_LONG).show();
      }
    }
    super.onActivityResult(requestCode, resultCode, data);
  }

  private synchronized void gpsStart() {
  }

  private synchronized void gpsStop() {

  }


  @Override
  protected void onPause() {
    super.onPause();
    mController.onPause();
    mWorldView.onPause();
    super.onPause();
    Log.d(TAG, "Pausing...");
    releaseWakeLockIfHeld();
  }

  /**
   * OnTouch handler for OpenGL canvas.
   * Called from OnTouchListener event.
   */
  public boolean onTouchCanvas(View v, MotionEvent event) {
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        mUsingTool = true;

        break;
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_UP:
        mUsingTool = false;

        break;
      default:
        break;
    }

    return mController.onTouch(v, event);
  }

  /**
   * OnClick method for debug view.
   * Called from XML layout.
   */
  public void onClickDebug(View v) {
  }



  /**
   * OnClick method for tools.
   * Called from XML layout.
   */
  public void onClickTool(View v) {

  }

  public void simulateMotionEvent(float x, float y, int eventType) {
    long downTime = SystemClock.uptimeMillis();
    long eventTime = SystemClock.uptimeMillis() + milliOffset;
    int metaState = 0;
    MotionEvent motionEvent = MotionEvent.obtain(downTime, eventTime, eventType, x, y, metaState);

    onTouchCanvas(mWorldView, motionEvent);

  }

  public void simulateDrip() {
    int swipeLength = 30;

    //this injects a pseudo event into Liquid fun Paint methods
    simulateMotionEvent(700f, 0, MotionEvent.ACTION_DOWN);
    simulateMotionEvent(700f, swipeLength / 4, MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f, swipeLength / 2, MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f, swipeLength / 4 * 3, MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f, swipeLength, MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f, swipeLength, MotionEvent.ACTION_UP);

  }

  public void setDripRatefromMAF(Float MAFrate) {
    if(MAFrate <= 0){
      mDripDelay_ms = 99999;
      mWaterDropsPerS = 0;
      updateDripRate();
      return;
    }
    Float fuelRate = MAFrate / MASS_AIR_FLOW_RATIO;
    int waterDropsPerGram = 20;
    mWaterDropsPerS = waterDropsPerGram * fuelRate;
    if (mWaterDropsPerS > 1000) mWaterDropsPerS = 1000;
    mDripDelay_ms = Math.round(1000/mWaterDropsPerS);
    updateDripRate();
  }

  public void renderSimulation(boolean run){
    if(run){
      log("Simulation now Rendering");
      Renderer.getInstance().startSimulation();
      updateDripRate();
      mQueueHandler.postDelayed(mQueueCommands,ConfigActivity.getObdUpdatePeriod(prefs));
    }
    else {
      Renderer.getInstance().startSimulation();
      mHandler.removeCallbacks(mRepeat);
      mQueueHandler.removeCallbacks(mQueueCommands);
    }
  }

  public void updateDripRate(){
    mHandler.removeCallbacks(mRepeat);
    mHandler.postDelayed(mRepeat,mDripDelay_ms);

    liveMAFTextView.setText(String.valueOf(mWaterDropsPerS) + " drops per second");

  }

}
