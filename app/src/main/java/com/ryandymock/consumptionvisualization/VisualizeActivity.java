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
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
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
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TableRow;
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
import com.ryandymock.consumptionvisualization.net.ObdReading;
import com.ryandymock.consumptionvisualization.net.ObdService;
import com.ryandymock.consumptionvisualization.tool.Tool;
import com.ryandymock.consumptionvisualization.tool.Tool.ToolType;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.Response;
import roboguice.RoboGuice;

public class VisualizeActivity extends Activity implements OnTouchListener {
  private static final String TAG = VisualizeActivity.class.getName();
  static String sVersionName;
  private Controller mController;
  private GLSurfaceView mWorldView;
  private Handler mHandler;
  int milliOffset = 50;

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

  private RelativeLayout mRootLayout;
  private SparseIntArray mColorMap;
  private SparseIntArray mPencilImageMap;
  private SparseIntArray mRigidImageMap;
  private SparseIntArray mWaterImageMap;
  private List<View> mRigidColorPalette;
  private List<View> mWaterColorPalette;

  // The image view of the selected tool
  private ImageView mSelected;
  // The current open palette
  private List<View> mOpenPalette = null;

  private boolean mUsingTool = false;
  private static final int ANIMATION_DURATION = 300;
  private int mDripDelay_ms = 200;

  private PowerManager powerManager;
  private static boolean bluetoothDefaultIsEnable = false;
  private SharedPreferences prefs;

  public Map<String, String> commandResult = new HashMap<String, String>();
  boolean mGpsIsStarted = false;
  private Location mLastLocation;

  static {

    System.loadLibrary("liquidfun_jni");
    System.loadLibrary("liquidfun");
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Set the ToolBar layout
    setContentView(R.layout.tools_layout);
    mRootLayout = (RelativeLayout) findViewById(R.id.root);

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

    powerManager =
            (PowerManager) this.getSystemService(Context.POWER_SERVICE);

    prefs = getSharedPreferences("shared_prefs", MODE_PRIVATE);

    final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
    if (btAdapter != null)
      bluetoothDefaultIsEnable = btAdapter.isEnabled();

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

    // Set the restart button's listener
    findViewById(R.id.button_restart).setOnTouchListener(this);

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
    mWorldView.setOnTouchListener(this);
    // GLSurfaceView#setPreserveEGLContextOnPause() is added in API level 11
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      setPreserveEGLContextOnPause();
    }

    mWorldView.setRenderer(renderer);
    renderer.startSimulation();

    // Set default tool colors
    Tool.getTool(ToolType.PENCIL).setColor(
            getColor(getString(R.string.default_pencil_color), "color"));
    Tool.getTool(ToolType.RIGID).setColor(
            getColor(getString(R.string.default_rigid_color), "color"));
    Tool.getTool(ToolType.WATER).setColor(
            getColor(getString(R.string.default_water_color), "color"));

    // Initialize the first selected tool
    mSelected = (ImageView) findViewById(R.id.water);
    onClickTool(mSelected);

    if (BuildConfig.DEBUG) {
      View fps = findViewById(R.id.fps);
      fps.setVisibility(View.VISIBLE);
      TextView versionView = (TextView) findViewById(R.id.version);
      try {
        sVersionName = "Version "
                + getPackageManager()
                .getPackageInfo(getPackageName(), 0).versionName;
        versionView.setText(sVersionName);
      } catch (NameNotFoundException e) {
        // The name returned by getPackageName() must be found.
      }
    }

    mHandler = new Handler();

    final Runnable repeat = new Runnable() {
      public void run() {
        simulateDrip();
        mHandler.postDelayed(this, mDripDelay_ms);
      }
    };

    mHandler.postDelayed(repeat, mDripDelay_ms);


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
  private final Runnable mQueueCommands = new Runnable() {
    public void run() {
      if (service != null && service.isRunning() && service.queueEmpty()) {
        queueCommands();

        double lat = 0;
        double lon = 0;
        double alt = 0;
        final int posLen = 7;
        if (mGpsIsStarted && mLastLocation != null) {
          lat = mLastLocation.getLatitude();
          lon = mLastLocation.getLongitude();
          alt = mLastLocation.getAltitude();

          StringBuilder sb = new StringBuilder();
          sb.append("Lat: ");
          sb.append(String.valueOf(mLastLocation.getLatitude()).substring(0, posLen));
          sb.append(" Lon: ");
          sb.append(String.valueOf(mLastLocation.getLongitude()).substring(0, posLen));
          sb.append(" Alt: ");
          sb.append(String.valueOf(mLastLocation.getAltitude()));
          //gpsStatusTextView.setText(sb.toString());
        }
        if (prefs.getBoolean(ConfigActivity.UPLOAD_DATA_KEY, false)) {
          // Upload the current reading by http
          final String vin = prefs.getString(ConfigActivity.VEHICLE_ID_KEY, "UNDEFINED_VIN");
          Map<String, String> temp = new HashMap<String, String>();
          temp.putAll(commandResult);
          ObdReading reading = new ObdReading(lat, lon, alt, System.currentTimeMillis(), vin, temp);
          new UploadAsyncTask().execute(reading);

        } else if (prefs.getBoolean(ConfigActivity.ENABLE_FULL_LOGGING_KEY, false)) {
          // Write the current reading to CSV
          final String vin = prefs.getString(ConfigActivity.VEHICLE_ID_KEY, "UNDEFINED_VIN");
          Map<String, String> temp = new HashMap<String, String>();
          temp.putAll(commandResult);
          ObdReading reading = new ObdReading(lat, lon, alt, System.currentTimeMillis(), vin, temp);
        }
        commandResult.clear();
      }
      // run again in period defined in preferences
      new Handler().postDelayed(mQueueCommands, ConfigActivity.getObdUpdatePeriod(prefs));
    }
  };

  private PowerManager.WakeLock wakeLock = null;
  private boolean preRequisites = true;

  private ServiceConnection serviceConn = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder binder) {
      log("Service is Bound!  (onServiceConnected callback)");
      Log.d(TAG, className.toString() + " service is bound");
      isServiceBound = true;
      service = ((AbstractGatewayService.AbstractGatewayServiceBinder) binder).getService();
      service.setContext(VisualizeActivity.this);
      Log.d(TAG, "Starting live data");
      log("Starting Live Data");
      try {
        service.startService();
        //if (preRequisites)
        //btStatusTextView.setText(getString(R.string.status_bluetooth_connected));
      } catch (IOException ioe) {
        Log.e(TAG, "Failure Starting live data");
        log("Failure Starting live data");
        //btStatusTextView.setText(getString(R.string.status_bluetooth_error_connecting));
        doUnbindService();
      }
    }

    @Override
    protected Object clone() throws CloneNotSupportedException {
      return super.clone();
    }

    // This method is *only* called when the connection to the service is lost unexpectedly
    // and *not* when the client unbinds (http://developer.android.com/guide/components/bound-services.html)
    // So the isServiceBound attribute should also be set to false when we unbind from the service.
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

  public void stateUpdate(final ObdCommandJob job) {
    final String cmdName = job.getCommand().getName();
    String cmdResult = "";
    Toast.makeText(getApplicationContext(), cmdName, Toast.LENGTH_SHORT).show();
    final String cmdID = LookUpCommand(cmdName);
    if (cmdName.equals("Mass Air Flow")) {
      String result = job.getCommand().getCalculatedResult();
      //mafOutputTextView.setText(result);
      try {
        //mafValue = Float.parseFloat(result);
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

  private boolean gpsInit() {
    return true;
  }

  private void updateTripStatistic(final ObdCommandJob job, final String cmdID) {

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

  private void updateConfig() {
    startActivity(new Intent(this, ConfigActivity.class));
  }

  private void log(String text) {
    //Toast.makeText(this,text,Toast.LENGTH_SHORT).show();
  }

  public boolean onCreateOptionsMenu(Menu menu) {
    menu.add(0, START_LIVE_DATA, 0, getString(R.string.menu_start_live_data));
    menu.add(0, STOP_LIVE_DATA, 0, getString(R.string.menu_stop_live_data));
    menu.add(0, GET_DTC, 0, getString(R.string.menu_get_dtc));
    menu.add(0, TRIPS_LIST, 0, getString(R.string.menu_trip_list));
    menu.add(0, SETTINGS, 0, getString(R.string.menu_settings));
    return true;
  }

  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case START_LIVE_DATA:
        startLiveData();
        return true;
      case STOP_LIVE_DATA:
        stopLiveData();
        return true;
      case SETTINGS:
        updateConfig();
        return true;
      case GET_DTC:
        getTroubleCodes();
        return true;
      case TRIPS_LIST:
        return true;
    }
    return false;
  }

  private void getTroubleCodes() {

  }

  private void startLiveData() {
    Log.d(TAG, "Starting live data..");
    log("startLiveData has been called");

    //tl.removeAllViews(); //start fresh
    doBindService();
    log("doBindService has been called");

    // start command execution
    new Handler().post(mQueueCommands);

    if (prefs.getBoolean(ConfigActivity.ENABLE_GPS_KEY, false))
      gpsStart();
    //else
    //gpsStatusTextView.setText(getString(R.string.status_gps_not_used));

    // screen won't turn off until wakeLock.release()
    wakeLock.acquire();

    if (prefs.getBoolean(ConfigActivity.ENABLE_FULL_LOGGING_KEY, false)) {

      // Create the CSV Logger
      long mils = System.currentTimeMillis();
      SimpleDateFormat sdf = new SimpleDateFormat("_dd_MM_yyyy_HH_mm_ss");

    }
  }

  private void stopLiveData() {
    Log.d(TAG, "Stopping live data..");
    log("Stopping Live Data");

    gpsStop();

    doUnbindService();
    endTrip();

    releaseWakeLockIfHeld();

    final String devemail = prefs.getString(ConfigActivity.DEV_EMAIL_KEY, null);
    if (devemail != null) {
      DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
              ObdGatewayService.saveLogcatToFile(getApplicationContext(), devemail);
              break;

            case DialogInterface.BUTTON_NEGATIVE:
              //No button clicked
              break;
          }
        }
      };
      AlertDialog.Builder builder = new AlertDialog.Builder(this);
      builder.setMessage("Where there issues?\nThen please send us the logs.\nSend Logs?").setPositiveButton("Yes", dialogClickListener)
              .setNegativeButton("No", dialogClickListener).show();
    }


  }

  protected void endTrip() {
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

  private void addTableRow(String id, String key, String val) {

    TableRow tr = new TableRow(this);
    ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.setMargins(TABLE_ROW_MARGIN, TABLE_ROW_MARGIN, TABLE_ROW_MARGIN,
            TABLE_ROW_MARGIN);
    tr.setLayoutParams(params);

    TextView name = new TextView(this);
    name.setGravity(Gravity.RIGHT);
    name.setText(key + ": ");
    TextView value = new TextView(this);
    value.setGravity(Gravity.LEFT);
    value.setText(val);
    value.setTag(id);
    tr.addView(name);
    tr.addView(value);
    //tl.addView(tr, params);
  }

  /**
   *
   */
  private void queueCommands() {
    if (isServiceBound) {
      for (ObdCommand Command : ObdConfig.getCommands()) {
        if (prefs.getBoolean(Command.getName(), true))
          service.queueJob(new ObdCommandJob(Command));
      }
    }
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

  public void onLocationChanged(Location location) {
    mLastLocation = location;
  }

  public void onStatusChanged(String provider, int status, Bundle extras) {
  }

  public void onProviderEnabled(String provider) {
  }

  public void onProviderDisabled(String provider) {
  }

  public void onGpsStatusChanged(int event) {

    switch (event) {
      case GpsStatus.GPS_EVENT_STARTED:
        //gpsStatusTextView.setText(getString(R.string.status_gps_started));
        break;
      case GpsStatus.GPS_EVENT_STOPPED:
        //gpsStatusTextView.setText(getString(R.string.status_gps_stopped));
        break;
      case GpsStatus.GPS_EVENT_FIRST_FIX:
        //gpsStatusTextView.setText(getString(R.string.status_gps_fix));
        break;
      case GpsStatus.GPS_EVENT_SATELLITE_STATUS:
        break;
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

  /**
   * Uploading asynchronous task
   */
  private class UploadAsyncTask extends AsyncTask<ObdReading, Void, Void> {

    @Override
    protected Void doInBackground(ObdReading... readings) {
      Log.d(TAG, "Uploading " + readings.length + " readings..");
      // instantiate reading service client
      final String endpoint = prefs.getString(ConfigActivity.UPLOAD_URL_KEY, "");
      RestAdapter restAdapter = new RestAdapter.Builder()
              .setEndpoint(endpoint)
              .build();
      ObdService service = restAdapter.create(ObdService.class);
      // upload readings
      for (ObdReading reading : readings) {
        try {
          Response response = service.uploadReading(reading);
          assert response.getStatus() == 200;
        } catch (RetrofitError re) {
          Log.e(TAG, re.toString());
        }

      }
      Log.d(TAG, "Done");
      return null;
    }

  }

  @Override
  protected void onPause() {
    super.onPause();
    mController.onPause();
    mWorldView.onPause();
    super.onPause();
    Log.d(TAG, "Pausing..");
    releaseWakeLockIfHeld();
  }

  private void togglePalette(View selectedTool, List<View> palette) {
    // Save the previously opened palette as closePalette() will clear it.
    List<View> prevOpenPalette = mOpenPalette;

    // Always close the palette.
    closePalette();

    // If we are not selecting the same tool with an open color palette,
    // open it.
    if (!(selectedTool.getId() == mSelected.getId() &&
            prevOpenPalette != null)) {
      openPalette(palette);
    }
  }

  private void openPalette(List<View> palette) {
    if (mOpenPalette == null) {
      float d = getResources().getDimension(R.dimen.color_width);
      for (int i = 0; i < palette.size(); i++) {
        Animation slideIn =
                new TranslateAnimation(-d * (i + 1), 0, 0, 0);
        slideIn.setDuration(ANIMATION_DURATION);

        View view = palette.get(i);
        view.setVisibility(View.VISIBLE);
        view.setClickable(true);
        view.startAnimation(slideIn);
      }
    }
    mOpenPalette = palette;
  }

  private void closePalette() {
    if (mOpenPalette != null) {
      float d = getResources().getDimension(R.dimen.color_width);
      for (int i = 0; i < mOpenPalette.size(); i++) {
        View view = mOpenPalette.get(i);
        Animation slideOut =
                new TranslateAnimation(0, -d * (i + 1), 0, 0);
        slideOut.setDuration(ANIMATION_DURATION);
        view.startAnimation(slideOut);
        view.setVisibility(View.GONE);
      }
    }
    mOpenPalette = null;
  }

  private void select(View v, ToolType tool) {
    // Send the new tool over to the Controller
    mController.setTool(tool);
    // Keep track of the ImageView of the tool and highlight it
    mSelected = (ImageView) v;
    View selecting = findViewById(R.id.selecting);
    RelativeLayout.LayoutParams params =
            new RelativeLayout.LayoutParams(selecting.getLayoutParams());

    params.addRule(RelativeLayout.ALIGN_TOP, v.getId());
    selecting.setLayoutParams(params);
    selecting.setVisibility(View.VISIBLE);
  }

  private int getColor(String name, String defType) {
    Resources r = getResources();
    int id = r.getIdentifier(name, defType, getPackageName());
    int color = r.getColor(id);
    // ARGB to ABGR
    int red = (color >> 16) & 0xFF;
    int blue = (color << 16) & 0xFF0000;
    return (color & 0xFF00FF00) | red | blue;
  }

  /**
   * OnTouch event handler.
   */
  @Override
  public boolean onTouch(View v, MotionEvent event) {
    boolean retValue = false;
    switch (v.getId()) {
      case R.id.button_restart:
        retValue = onTouchReset(v, event);
        break;
      case R.id.world:
        retValue = onTouchCanvas(v, event);
        break;
      default:
        break;
    }
    return retValue;
  }

  /**
   * OnTouch handler for OpenGL canvas.
   * Called from OnTouchListener event.
   */
  public boolean onTouchCanvas(View v, MotionEvent event) {
    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        mUsingTool = true;
        if (mSelected.getId() == R.id.rigid) {
          Renderer.getInstance().pauseSimulation();
        }
        break;
      case MotionEvent.ACTION_CANCEL:
      case MotionEvent.ACTION_UP:
        mUsingTool = false;
        if (mSelected.getId() == R.id.rigid) {
          Renderer.getInstance().startSimulation();
        }
        break;
      default:
        break;
    }

    closePalette();
    return mController.onTouch(v, event);
  }

  /**
   * OnTouch handler for reset button.
   * Called from OnTouchListener event.
   */
  public boolean onTouchReset(View v, MotionEvent event) {
    if (!mUsingTool) {
      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          closePalette();
          select(v, null);
          break;
        case MotionEvent.ACTION_UP:
          Renderer.getInstance().reset();
          mController.reset();
          // Could refactor out to a deselect() function, but this is
          // the only place that needs it now.
          View selecting = findViewById(R.id.selecting);
          selecting.setVisibility(View.INVISIBLE);
          break;
        default:
          break;
      }
    }

    return true;
  }

  /**
   * OnClick method for debug view.
   * Called from XML layout.
   */
  public void onClickDebug(View v) {
  }

  /**
   * OnClick method for the color palette.
   * Called from XML layout.
   */
  public void onClickPalette(View v) {
    if (mUsingTool) {
      return;
    }
    int color = mColorMap.get(v.getId());
    mController.setColor(color);
    switch (mSelected.getId()) {
      case R.id.pencil:
        mSelected.setImageResource(
                mPencilImageMap.get(v.getId()));
        break;
      case R.id.rigid:
        mSelected.setImageResource(
                mRigidImageMap.get(v.getId()));
        break;
      case R.id.water:
        mSelected.setImageResource(
                mWaterImageMap.get(v.getId()));
        break;
    }
    // Close the palette on choosing a color
    closePalette();
  }

  /**
   * OnClick method for tools.
   * Called from XML layout.
   */
  public void onClickTool(View v) {
    if (mUsingTool) {
      return;
    }

    ToolType tool = null;

    switch (v.getId()) {
      case R.id.pencil:
        tool = ToolType.PENCIL;
        togglePalette(v, mRigidColorPalette);
        break;
      case R.id.rigid:
        tool = ToolType.RIGID;
        togglePalette(v, mRigidColorPalette);
        break;
      case R.id.water:
        tool = ToolType.WATER;
        togglePalette(v, mWaterColorPalette);
        break;
      case R.id.eraser:
        tool = ToolType.ERASER;
        // Always close palettes for non-drawing tools
        closePalette();
        break;
      case R.id.hand:
        tool = ToolType.MOVE;
        // Always close palettes for non-drawing tools
        closePalette();
        break;
      default:
    }

    // Actually select the view
    select(v, tool);
  }

  public void simulateMotionEvent(float x, float y, int eventType) {
    long downTime = SystemClock.uptimeMillis();
    long eventTime = SystemClock.uptimeMillis() + milliOffset;
    int metaState = 0;
    MotionEvent motionEvent = MotionEvent.obtain(downTime, eventTime, eventType, x, y, metaState);
    mWorldView.dispatchTouchEvent(motionEvent);

  }

  public void simulateDrip() {
    int swipeLength = 30;
    mDripDelay_ms = mDripDelay_ms - 5;
    if (mDripDelay_ms == 40) mDripDelay_ms = 65;
    //Log.d("RCD",String.valueOf(mDripDelay_ms));
    //Log.d("RCD",String.valueOf(System.currentTimeMillis()));

    //ACTION DOWN
    simulateMotionEvent(700f, 0, MotionEvent.ACTION_DOWN);
    simulateMotionEvent(700f, swipeLength / 4, MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f, swipeLength / 2, MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f, swipeLength / 4 * 3, MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f, swipeLength, MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f, swipeLength, MotionEvent.ACTION_UP);

  }

  public void setDripRatefromMAF(Float MAFrate) {
    int waterDropsPerGram = 20;
    float dripsPerSec = waterDropsPerGram * MAFrate;
    if (dripsPerSec > 1000) dripsPerSec = 1000;

    mDripDelay_ms = Math.round(dripsPerSec);
  }


}
