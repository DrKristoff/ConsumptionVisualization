/**
* Copyright (c) 2014 Google, Inc. All rights reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/

package com.ryandymock.consumptionvisualization;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v7.appcompat.BuildConfig;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ryandymock.consumptionvisualization.tool.Tool;
import com.ryandymock.consumptionvisualization.tool.Tool.ToolType;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * VisualizeActivity for Splashpaint
 * Implements the Android UI layout.
 */
public class VisualizeActivity extends Activity implements OnTouchListener {
    static String sVersionName;
    private Controller mController;
    private GLSurfaceView mWorldView;
  private Handler mHandler;
  int milliOffset = 50;

    private RelativeLayout mRootLayout;

    // Mapping from ImageView to actual RGB values
    private SparseIntArray mColorMap;
    // Mapping from ImageView of color palette to the images for Tools
    private SparseIntArray mPencilImageMap;
    private SparseIntArray mRigidImageMap;
    private SparseIntArray mWaterImageMap;
    // List of ImageView of color palettes
    private List<View> mRigidColorPalette;
    private List<View> mWaterColorPalette;

    // The image view of the selected tool
    private ImageView mSelected;
    // The current open palette
    private List<View> mOpenPalette = null;

    private boolean mUsingTool = false;
    private static final int ANIMATION_DURATION = 300;
    private int mDripDelay_ms = 200;

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

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void setPreserveEGLContextOnPause() {
        mWorldView.setPreserveEGLContextOnPause(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mController.onResume();
        mWorldView.onResume();
        Renderer.getInstance().totalFrames = -10000;

    }

    @Override
    protected void onPause() {
        super.onPause();
        mController.onPause();
        mWorldView.onPause();
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

  public void simulateMotionEvent(float x, float y, int eventType){
    long downTime = SystemClock.uptimeMillis();
    long eventTime = SystemClock.uptimeMillis() + milliOffset;
    int metaState = 0;
    MotionEvent motionEvent = MotionEvent.obtain(downTime,eventTime,eventType,x,y,metaState);
    mWorldView.dispatchTouchEvent(motionEvent);

  }
  public void simulateDrip(){
    int swipeLength = 30;
    mDripDelay_ms = mDripDelay_ms - 5;
    if(mDripDelay_ms == 40) mDripDelay_ms = 65;
    //Log.d("RCD",String.valueOf(mDripDelay_ms));
    //Log.d("RCD",String.valueOf(System.currentTimeMillis()));

    //ACTION DOWN
    simulateMotionEvent(700f,0,MotionEvent.ACTION_DOWN);
    simulateMotionEvent(700f,swipeLength/4,MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f,swipeLength/2,MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f,swipeLength/4*3,MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f,swipeLength,MotionEvent.ACTION_MOVE);
    simulateMotionEvent(700f,swipeLength,MotionEvent.ACTION_UP);

  }

  public void setDripRatefromMAF(Float MAFrate){
    int waterDropsPerGram = 20;
    float dripsPerSec = waterDropsPerGram * MAFrate;
    if(dripsPerSec > 1000) dripsPerSec = 1000;

    mDripDelay_ms = Math.round(dripsPerSec);
  }


}
