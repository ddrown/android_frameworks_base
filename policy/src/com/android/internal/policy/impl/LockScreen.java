/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.android.internal.widget.DigitalClock;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.SlidingTab;
import com.android.internal.widget.WaveView;
import com.android.internal.widget.multiwaveview.MultiWaveView;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.util.Log;
import android.media.AudioManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.net.URISyntaxException;

/**
 * The screen within {@link LockPatternKeyguardView} that shows general
 * information about the device depending on its state, and how to get
 * past it, as applicable.
 */
class LockScreen extends LinearLayout implements KeyguardScreen {

    private static final int ON_RESUME_PING_DELAY = 500; // delay first ping until the screen is on
    private static final boolean DBG = false;
    private static final boolean DEBUG = DBG;
    private static final String TAG = "LockScreen";
    private static final String ENABLE_MENU_KEY_FILE = "/data/local/enable_menu_key";
    private static final int WAIT_FOR_ANIMATION_TIMEOUT = 0;
    private static final int STAY_ON_WHILE_GRABBED_TIMEOUT = 30000;

    public static final int STOCK_TARGETS = 0;
    public static final int FOUR_TARGETS = 1;
    public static final int FOUR_TARGETS_UNLOCK_RIGHT = 2;
    public static final int SIX_TARGETS = 3;
    public static final int SIX_TARGETS_UNLOCK_RIGHT = 4;
    public static final int EIGHT_TARGETS = 5;
    public static final int EIGHT_TARGETS_UNLOCK_RIGHT = 6;

    public static final int STOCK_LAYOUT = 7;
    public static final int STOCK_LAYOUT_CENTERED_RING = 8;
    public static final int CENTER_LAYOUT = 9;
    public static final int CENTER_LAYOUT_CENTERED_RING = 10;
    public static final int BIG_CLOCK_LAYOUT = 11;
    public static final int BIG_CLOCK_LAYOUT_CENTERED_RING = 12;

    private int mLockscreenTargets = STOCK_TARGETS;
    private int mLockscreenLayout = STOCK_LAYOUT;

    final int mScreenSize = getResources().getConfiguration().screenLayout &
    Configuration.SCREENLAYOUT_SIZE_MASK;

    private boolean mIsScreenLarge = mScreenSize == Configuration.SCREENLAYOUT_SIZE_LARGE ||
    mScreenSize == Configuration.SCREENLAYOUT_SIZE_XLARGE;

    private static final int COLOR_WHITE = 0xFFFFFFFF;

    private LockPatternUtils mLockPatternUtils;
    private KeyguardUpdateMonitor mUpdateMonitor;
    private KeyguardScreenCallback mCallback;
    private SettingsObserver mSettingsObserver;

    // current configuration state of keyboard and display
    private int mKeyboardHidden;
    private int mCreationOrientation;

    private boolean mSilentMode;
    private AudioManager mAudioManager;
    private boolean mEnableMenuKeyInLockScreen;

    private KeyguardStatusViewManager mStatusViewManager;
    private UnlockWidgetCommonMethods mUnlockWidgetMethods;
    private View mUnlockWidget;

    // to allow coloring of lockscreen texts
    private TextView mCarrier;
    private DigitalClock mDigitalClock;

    private interface UnlockWidgetCommonMethods {
        // Update resources based on phone state
        public void updateResources();

        // Get the view associated with this widget
        public View getView();

        // Reset the view
        public void reset(boolean animate);

        // Animate the widget if it supports ping()
        public void ping();
    }

    class SlidingTabMethods implements SlidingTab.OnTriggerListener, UnlockWidgetCommonMethods {
        private final SlidingTab mSlidingTab;

        SlidingTabMethods(SlidingTab slidingTab) {
            mSlidingTab = slidingTab;
        }

        public void updateResources() {
            boolean vibe = mSilentMode
                && (mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE);

            mSlidingTab.setRightTabResources(
                    mSilentMode ? ( vibe ? R.drawable.ic_jog_dial_vibrate_on
                                         : R.drawable.ic_jog_dial_sound_off )
                                : R.drawable.ic_jog_dial_sound_on,
                    mSilentMode ? R.drawable.jog_tab_target_yellow
                                : R.drawable.jog_tab_target_gray,
                    mSilentMode ? R.drawable.jog_tab_bar_right_sound_on
                                : R.drawable.jog_tab_bar_right_sound_off,
                    mSilentMode ? R.drawable.jog_tab_right_sound_on
                                : R.drawable.jog_tab_right_sound_off);
        }

        /** {@inheritDoc} */
        public void onTrigger(View v, int whichHandle) {
            if (whichHandle == SlidingTab.OnTriggerListener.LEFT_HANDLE) {
                mCallback.goToUnlockScreen();
            } else if (whichHandle == SlidingTab.OnTriggerListener.RIGHT_HANDLE) {
                toggleRingMode();
                mCallback.pokeWakelock();
            }
        }

        /** {@inheritDoc} */
        public void onGrabbedStateChange(View v, int grabbedState) {
            if (grabbedState == SlidingTab.OnTriggerListener.RIGHT_HANDLE) {
                mSilentMode = isSilentMode();
                mSlidingTab.setRightHintText(mSilentMode ? R.string.lockscreen_sound_on_label
                        : R.string.lockscreen_sound_off_label);
            }
            // Don't poke the wake lock when returning to a state where the handle is
            // not grabbed since that can happen when the system (instead of the user)
            // cancels the grab.
            if (grabbedState != SlidingTab.OnTriggerListener.NO_HANDLE) {
                mCallback.pokeWakelock();
            }
        }

        public View getView() {
            return mSlidingTab;
        }

        public void reset(boolean animate) {
            mSlidingTab.reset(animate);
        }

        public void ping() {
        }
    }

    class WaveViewMethods implements WaveView.OnTriggerListener, UnlockWidgetCommonMethods {

        private final WaveView mWaveView;

        WaveViewMethods(WaveView waveView) {
            mWaveView = waveView;
        }
        /** {@inheritDoc} */
        public void onTrigger(View v, int whichHandle) {
            if (whichHandle == WaveView.OnTriggerListener.CENTER_HANDLE) {
                requestUnlockScreen();
            }
        }

        /** {@inheritDoc} */
        public void onGrabbedStateChange(View v, int grabbedState) {
            // Don't poke the wake lock when returning to a state where the handle is
            // not grabbed since that can happen when the system (instead of the user)
            // cancels the grab.
            if (grabbedState == WaveView.OnTriggerListener.CENTER_HANDLE) {
                mCallback.pokeWakelock(STAY_ON_WHILE_GRABBED_TIMEOUT);
            }
        }

        public void updateResources() {
        }

        public View getView() {
            return mWaveView;
        }
        public void reset(boolean animate) {
            mWaveView.reset();
        }
        public void ping() {
        }
    }

    class MultiWaveViewMethods implements MultiWaveView.OnTriggerListener,
            UnlockWidgetCommonMethods {

        private final MultiWaveView mMultiWaveView;
        private boolean mCameraDisabled = (Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.LOCKSCREEN_DISABLE_CAMERA, 0) == 1);

        MultiWaveViewMethods(MultiWaveView multiWaveView) {
            mMultiWaveView = multiWaveView;
        }

        public void updateResources() {
            int resId;
            if (mLockscreenTargets == STOCK_TARGETS) {
                if (mCameraDisabled) {
                    resId = mSilentMode ? R.array.lockscreen_targets_when_silent
                        : R.array.lockscreen_targets_when_soundon;
                } else {
                    resId = R.array.lockscreen_targets_with_camera;
                }
            } else if (mLockscreenTargets == FOUR_TARGETS) {
                if (mCameraDisabled && mIsScreenLarge) {
                    resId = mSilentMode ? R.array.soundon_quad_lockscreen_nophone_targets
                        : R.array.silent_quad_lockscreen_nophone_targets;
                } else if (mCameraDisabled) {
                    resId = mSilentMode ? R.array.soundon_quad_lockscreen_targets
                        : R.array.silent_quad_lockscreen_targets;
                } else if (mIsScreenLarge) {
                    resId = R.array.quad_lockscreen_nophone_targets;
                } else {
                    resId = R.array.quad_lockscreen_targets;
                }
            } else if (mLockscreenTargets == FOUR_TARGETS_UNLOCK_RIGHT) {
                if (mCameraDisabled && mIsScreenLarge) {
                    resId = mSilentMode ? R.array.soundon_quad_unlockright_lockscreen_nophone_targets
                        : R.array.silent_quad_unlockright_lockscreen_nophone_targets;
                } else if (mCameraDisabled) {
                    resId = mSilentMode ? R.array.soundon_quad_unlockright_lockscreen_targets
                        : R.array.silent_quad_unlockright_lockscreen_targets;
                } else if (mIsScreenLarge) {
                    resId = R.array.quad_unlockright_lockscreen_nophone_targets;
                } else {
                    resId = R.array.quad_unlockright_lockscreen_targets;
                }
            } else if (mLockscreenTargets == SIX_TARGETS) {
                if (mCameraDisabled && mIsScreenLarge) {
                    resId = mSilentMode ? R.array.soundon_six_lockscreen_nophone_targets
                        : R.array.silent_six_lockscreen_nophone_targets;
                } else if (mCameraDisabled) {
                    resId = mSilentMode ? R.array.soundon_six_lockscreen_targets
                        : R.array.silent_six_lockscreen_targets;
                } else if (mIsScreenLarge) {
                    resId = R.array.six_lockscreen_nophone_targets;
                } else {
                    resId = R.array.six_lockscreen_targets;
                }
            } else if (mLockscreenTargets == SIX_TARGETS_UNLOCK_RIGHT) {
                if (mCameraDisabled && mIsScreenLarge) {
                    resId = mSilentMode ? R.array.soundon_six_unlockright_lockscreen_nophone_targets
                        : R.array.silent_six_unlockright_lockscreen_nophone_targets;
                } else if (mCameraDisabled) {
                    resId = mSilentMode ? R.array.soundon_six_unlockright_lockscreen_targets
                        : R.array.silent_six_unlockright_lockscreen_targets;
                } else if (mIsScreenLarge) {
                    resId = R.array.six_unlockright_lockscreen_nophone_targets;
                } else {
                    resId = R.array.six_unlockright_lockscreen_targets;
                }
            } else if (mLockscreenTargets == EIGHT_TARGETS) {
                if (mCameraDisabled && mIsScreenLarge) {
                    resId = mSilentMode ? R.array.soundon_eight_lockscreen_nophone_targets
                        : R.array.silent_eight_lockscreen_nophone_targets;
                } else if (mCameraDisabled) {
                    resId = mSilentMode ? R.array.soundon_eight_lockscreen_targets
                        : R.array.silent_eight_lockscreen_targets;
                } else if (mIsScreenLarge) {
                    resId = R.array.eight_lockscreen_nophone_targets;
                } else {
                    resId = R.array.eight_lockscreen_targets;
                }
            } else if (mLockscreenTargets == EIGHT_TARGETS_UNLOCK_RIGHT) {
                if (mCameraDisabled && mIsScreenLarge) {
                    resId = mSilentMode ? R.array.soundon_eight_unlockright_lockscreen_nophone_targets
                        : R.array.silent_eight_unlockright_lockscreen_nophone_targets;
                } else if (mCameraDisabled) {
                    resId = mSilentMode ? R.array.soundon_eight_unlockright_lockscreen_targets
                        : R.array.silent_eight_unlockright_lockscreen_targets;
                } else if (mIsScreenLarge) {
                    resId = R.array.eight_unlockright_lockscreen_nophone_targets;
                } else {
                    resId = R.array.eight_unlockright_lockscreen_targets;
                }
            } else if (mCameraDisabled) {
                // Fall back to showing ring/silence if camera is disabled by DPM...
                resId = mSilentMode ? R.array.lockscreen_targets_when_silent
                    : R.array.lockscreen_targets_when_soundon;
            } else {
                resId = R.array.lockscreen_targets_with_camera;
            }
            mMultiWaveView.setTargetResources(resId);
        }

        public void onGrabbed(View v, int handle) {

        }

        public void onReleased(View v, int handle) {

        }

        public void onTrigger(View v, int target) {
            if (mLockscreenTargets == STOCK_TARGETS) {
                if (target == 0 || target == 1) { // 0 = unlock/portrait, 1 = unlock/landscape
                    mCallback.goToUnlockScreen();
                } else if (target == 2 || target == 3) { // 2 = alt/portrait, 3 = alt/landscape
                    if (mCameraDisabled) {
                        toggleRingMode();
                        mUnlockWidgetMethods.updateResources();
                        mCallback.pokeWakelock();
                    } else {
                        // Start the Camera
                        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);
                        mCallback.goToUnlockScreen();
                    }
                }
            }
            if (mLockscreenTargets == FOUR_TARGETS) {
                if (target == 0) { // upper left Action = Phone/Browser
                    if (mIsScreenLarge) {
                        Intent browserIntent = new Intent(Intent.ACTION_MAIN);
                        browserIntent.setClassName("com.android.browser",
                                                   "com.android.browser.BrowserActivity");
                        browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(browserIntent);
                    } else {
                        Intent phoneIntent = new Intent(Intent.ACTION_MAIN);
                        phoneIntent.setClassName("com.android.contacts",
                                                 "com.android.contacts.activities.DialtactsActivity");
                        phoneIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(phoneIntent);
                    }
                        mCallback.goToUnlockScreen();
                } else if (target == 1) { // top Action == Camera/Ring Toggle
                    if (mCameraDisabled) {
                        toggleRingMode();
                        mUnlockWidgetMethods.updateResources();
                        mCallback.pokeWakelock();
                    } else {
                        // Start the Camera
                        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);
                        mCallback.goToUnlockScreen();
                    }
                } else if (target == 2) { // left Action = Mms
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_SMS_INTENT);

                    if(intentUri == null) {
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory(Intent.CATEGORY_LAUNCHER);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        intent.setClassName("com.android.mms", "com.android.mms.ui.ConversationList");
                        mContext.startActivity(intent);
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 3) { // left Action = Hidden Unlock
                    mCallback.goToUnlockScreen();
                }
            }
            if (mLockscreenTargets == FOUR_TARGETS_UNLOCK_RIGHT) {
                if (target == 2) { // upper right Action = Phone/Browser
                    if (mIsScreenLarge) {
                        Intent browserIntent = new Intent(Intent.ACTION_MAIN);
                        browserIntent.setClassName("com.android.browser",
                                                   "com.android.browser.BrowserActivity");
                        browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(browserIntent);
                    } else {
                        Intent phoneIntent = new Intent(Intent.ACTION_MAIN);
                        phoneIntent.setClassName("com.android.contacts",
                                                 "com.android.contacts.activities.DialtactsActivity");
                        phoneIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(phoneIntent);
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 3) { // left Action == Camera/Ring Toggle
                    if (mCameraDisabled) {
                        toggleRingMode();
                        mUnlockWidgetMethods.updateResources();
                        mCallback.pokeWakelock();
                    } else {
                        // Start the Camera
                        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);
                        mCallback.goToUnlockScreen();
                    }
                } else if (target == 1) { // upper left Action = Mms
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_SMS_INTENT);

                    if(intentUri == null) {
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory(Intent.CATEGORY_LAUNCHER);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        intent.setClassName("com.android.mms", "com.android.mms.ui.ConversationList");
                        mContext.startActivity(intent);
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 0) { // right Action = Hidden Unlock
                    mCallback.goToUnlockScreen();
                }
            }
            if (mLockscreenTargets == SIX_TARGETS) {
                if (target == 5) { // lower left Action = Empty
                } else if (target == 0) { // right Action = Phone/Browser
                    if (mIsScreenLarge) {
                        Intent browserIntent = new Intent(Intent.ACTION_MAIN);
                        browserIntent.setClassName("com.android.browser",
                                                   "com.android.browser.BrowserActivity");
                        browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(browserIntent);
                    } else {
                        Intent phoneIntent = new Intent(Intent.ACTION_MAIN);
                        phoneIntent.setClassName("com.android.contacts",
                                                 "com.android.contacts.activities.DialtactsActivity");
                        phoneIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(phoneIntent);
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 3) { // upper left Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_UPPER_LEFT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 2) { // top Action == Camera/Ring Toggle
                    if (mCameraDisabled) {
                        toggleRingMode();
                        mUnlockWidgetMethods.updateResources();
                        mCallback.pokeWakelock();
                    } else {
                        // Start the Camera
                        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);
                        mCallback.goToUnlockScreen();
                    }
                } else if (target == 1) { // upper right Action = Unlock
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_UPPER_RIGHT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 4) { // left Action = Mms
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_SMS_INTENT);

                    if(intentUri == null) {
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory(Intent.CATEGORY_LAUNCHER);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        intent.setClassName("com.android.mms", "com.android.mms.ui.ConversationList");
                        mContext.startActivity(intent);
                    } else {
                        Intent mmsIntent;
                        try {
                            mmsIntent = Intent.parseUri(intentUri, 0);
                            mmsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(mmsIntent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 7) { // lower right Action = Empty
                } else if (target == 6) { // bottom Action = Hidden Unlock
                    mCallback.goToUnlockScreen();
                }
            }
            if (mLockscreenTargets == SIX_TARGETS_UNLOCK_RIGHT) {
                if (target == 5) { // lower left Action = Empty
                } else if (target == 6) { // bottom Action = Phone/Browser
                    if (mIsScreenLarge) {
                        Intent browserIntent = new Intent(Intent.ACTION_MAIN);
                        browserIntent.setClassName("com.android.browser",
                                                   "com.android.browser.BrowserActivity");
                        browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(browserIntent);
                    } else {
                        Intent phoneIntent = new Intent(Intent.ACTION_MAIN);
                        phoneIntent.setClassName("com.android.contacts",
                                                 "com.android.contacts.activities.DialtactsActivity");
                        phoneIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(phoneIntent);
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 3) { // upper left Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_UPPER_LEFT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 4) { // left Action == Camera/Ring Toggle
                    if (mCameraDisabled) {
                        toggleRingMode();
                        mUnlockWidgetMethods.updateResources();
                        mCallback.pokeWakelock();
                    } else {
                        // Start the Camera
                        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);
                        mCallback.goToUnlockScreen();
                    }
                } else if (target == 1) { // upper right Action = Unlock
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_UPPER_RIGHT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 2) { // left Action = Mms
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_SMS_INTENT);

                    if(intentUri == null) {
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory(Intent.CATEGORY_LAUNCHER);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        intent.setClassName("com.android.mms", "com.android.mms.ui.ConversationList");
                        mContext.startActivity(intent);
                    } else {
                        Intent mmsIntent;
                        try {
                            mmsIntent = Intent.parseUri(intentUri, 0);
                            mmsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(mmsIntent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 7) { // lower right Action = Empty
                } else if (target == 0) { // right Action = Unlock
                            mCallback.goToUnlockScreen();
                }
            }
            if (mLockscreenTargets == EIGHT_TARGETS) {
                if (target == 5) { // lower left Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_LOWER_LEFT_INTENT);

                    if(intentUri == null) {
                        mCallback.goToUnlockScreen();
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 0) { // upper left Action = Phone/Browser
                    if (mIsScreenLarge) {
                        Intent browserIntent = new Intent(Intent.ACTION_MAIN);
                        browserIntent.setClassName("com.android.browser",
                                                   "com.android.browser.BrowserActivity");
                        browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(browserIntent);
                    } else {
                        Intent phoneIntent = new Intent(Intent.ACTION_MAIN);
                        phoneIntent.setClassName("com.android.contacts",
                                                 "com.android.contacts.activities.DialtactsActivity");
                        phoneIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(phoneIntent);
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 3) { // upper left Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_UPPER_LEFT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 2) { // top Action == Camera/Ring Toggle
                    if (mCameraDisabled) {
                        toggleRingMode();
                        mUnlockWidgetMethods.updateResources();
                        mCallback.pokeWakelock();
                    } else {
                        // Start the Camera
                        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);
                        mCallback.goToUnlockScreen();
                    }
                } else if (target == 1) { // upper right Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_UPPER_RIGHT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 4) { // left Action = Mms
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_SMS_INTENT);

                    if(intentUri == null) {
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory(Intent.CATEGORY_LAUNCHER);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        intent.setClassName("com.android.mms", "com.android.mms.ui.ConversationList");
                        mContext.startActivity(intent);
                    } else {
                        Intent mmsIntent;
                        try {
                            mmsIntent = Intent.parseUri(intentUri, 0);
                            mmsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(mmsIntent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 7) { // lower right Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_LOWER_RIGHT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 6) { // bottom Action = Hidden Unlock
                    mCallback.goToUnlockScreen();
                }
            }
            if (mLockscreenTargets == EIGHT_TARGETS_UNLOCK_RIGHT) {
                if (target == 5) { // lower left Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_LOWER_LEFT_INTENT);

                    if(intentUri == null) {
                        mCallback.goToUnlockScreen();
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 6) { // bottom Action = Phone/Browser
                    if (mIsScreenLarge) {
                                Intent browserIntent = new Intent(Intent.ACTION_MAIN);
                        browserIntent.setClassName("com.android.browser",
                                                   "com.android.browser.BrowserActivity");
                        browserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(browserIntent);
                    } else {
                        Intent phoneIntent = new Intent(Intent.ACTION_MAIN);
                        phoneIntent.setClassName("com.android.contacts",
                                                 "com.android.contacts.activities.DialtactsActivity");
                        phoneIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(phoneIntent);
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 3) { // upper left Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_UPPER_LEFT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 4) { // left Action == Camera/Ring Toggle
                    if (mCameraDisabled) {
                        toggleRingMode();
                        mUnlockWidgetMethods.updateResources();
                        mCallback.pokeWakelock();
                    } else {
                        // Start the Camera
                        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);
                        mCallback.goToUnlockScreen();
                    }
                } else if (target == 1) { // upper right Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_UPPER_RIGHT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 2) { // left Action = Mms
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_SMS_INTENT);

                    if(intentUri == null) {
                        Intent intent = new Intent(Intent.ACTION_MAIN);
                        intent.addCategory(Intent.CATEGORY_LAUNCHER);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                        intent.setClassName("com.android.mms", "com.android.mms.ui.ConversationList");
                        mContext.startActivity(intent);
                    } else {
                        Intent mmsIntent;
                        try {
                            mmsIntent = Intent.parseUri(intentUri, 0);
                            mmsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(mmsIntent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 7) { // lower right Action = Custom
                    String intentUri = Settings.System.getString(mContext.getContentResolver(), Settings.System.LOCKSCREEN_CUSTOM_LOWER_RIGHT_INTENT);

                    if(intentUri == null) {
                    } else {
                        Intent intent;
                        try {
                            intent = Intent.parseUri(intentUri, 0);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                            mContext.startActivity(intent);
                        } catch (URISyntaxException e) {
                        }
                    }
                    mCallback.goToUnlockScreen();
                } else if (target == 0) { // right Action = Hidden Unlock
                    mCallback.goToUnlockScreen();
                }
            }
        }

        public void onGrabbedStateChange(View v, int handle) {
            // Don't poke the wake lock when returning to a state where the handle is
            // not grabbed since that can happen when the system (instead of the user)
            // cancels the grab.
            if (handle != MultiWaveView.OnTriggerListener.NO_HANDLE) {
                mCallback.pokeWakelock();
            }
        }

        public View getView() {
            return mMultiWaveView;
        }

        public void reset(boolean animate) {
            mMultiWaveView.reset(animate);
        }

        public void ping() {
            mMultiWaveView.ping();
        }
    }

    private void requestUnlockScreen() {
        // Delay hiding lock screen long enough for animation to finish
        postDelayed(new Runnable() {
            public void run() {
                mCallback.goToUnlockScreen();
            }
        }, WAIT_FOR_ANIMATION_TIMEOUT);
    }

    private void toggleRingMode() {
        // toggle silent mode
        mSilentMode = !mSilentMode;
        if (mSilentMode) {
            final boolean vibe = (Settings.System.getInt(
                mContext.getContentResolver(),
                Settings.System.VIBRATE_IN_SILENT, 1) == 1);

            mAudioManager.setRingerMode(vibe
                ? AudioManager.RINGER_MODE_VIBRATE
                : AudioManager.RINGER_MODE_SILENT);
        } else {
            mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        }
    }

    /**
     * In general, we enable unlocking the insecure key guard with the menu key. However, there are
     * some cases where we wish to disable it, notably when the menu button placement or technology
     * is prone to false positives.
     *
     * @return true if the menu key should be enabled
     */
    private boolean shouldEnableMenuKey() {
        final Resources res = getResources();
        final boolean configDisabled = res.getBoolean(R.bool.config_disableMenuKeyInLockScreen);
        final boolean isTestHarness = ActivityManager.isRunningInTestHarness();
        final boolean fileOverride = (new File(ENABLE_MENU_KEY_FILE)).exists();
        final boolean menuOverride = Settings.System.getInt(getContext().getContentResolver(), Settings.System.MENU_UNLOCK_SCREEN, 0) == 1;
        return !configDisabled || isTestHarness || fileOverride || menuOverride;
    }

    /**
     * @param context Used to setup the view.
     * @param configuration The current configuration. Used to use when selecting layout, etc.
     * @param lockPatternUtils Used to know the state of the lock pattern settings.
     * @param updateMonitor Used to register for updates on various keyguard related
     *    state, and query the initial state at setup.
     * @param callback Used to communicate back to the host keyguard view.
     */
    LockScreen(Context context, Configuration configuration, LockPatternUtils lockPatternUtils,
            KeyguardUpdateMonitor updateMonitor,
            KeyguardScreenCallback callback) {
        super(context);
        mLockPatternUtils = lockPatternUtils;
        mUpdateMonitor = updateMonitor;
        mCallback = callback;

        mEnableMenuKeyInLockScreen = shouldEnableMenuKey();

        mCreationOrientation = configuration.orientation;

        mKeyboardHidden = configuration.hardKeyboardHidden;

        mSettingsObserver = new SettingsObserver(new Handler());
        mSettingsObserver.observe();

        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.v(TAG, "***** CREATING LOCK SCREEN", new RuntimeException());
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + " res orient=" + context.getResources().getConfiguration().orientation);
        }

        final LayoutInflater inflater = LayoutInflater.from(context);
        if (DBG) Log.v(TAG, "Creation orientation = " + mCreationOrientation);

        boolean landscape = mCreationOrientation == Configuration.ORIENTATION_LANDSCAPE;
        
        switch (mLockscreenTargets) {
            case STOCK_TARGETS:
            case FOUR_TARGETS_UNLOCK_RIGHT:
            case SIX_TARGETS_UNLOCK_RIGHT:
            case EIGHT_TARGETS_UNLOCK_RIGHT:
                if (landscape)
                    inflater.inflate(R.layout.keyguard_screen_tab_unlock_land, this,
                                     true);
                else
                    if (mIsScreenLarge) {
                        inflater.inflate(R.layout.keyguard_screen_tab_unlock, this,
                                         true);
                    } else if (mLockscreenLayout == STOCK_LAYOUT) {
                        inflater.inflate(R.layout.keyguard_screen_tab_unlock, this,
                                         true);
                    } else if (mLockscreenLayout == STOCK_LAYOUT_CENTERED_RING) {
                        inflater.inflate(R.layout.keyguard_screen_tab_center_unlock_right, this,
                                         true);
                    } else if (mLockscreenLayout == CENTER_LAYOUT) {
                        inflater.inflate(R.layout.keyguard_center_screen_tab_unlock, this,
                                         true);
                    } else if (mLockscreenLayout == CENTER_LAYOUT_CENTERED_RING) {
                        inflater.inflate(R.layout.keyguard_center_screen_tab_center_unlock_right, this,
                                         true);
                    } else if (mLockscreenLayout == BIG_CLOCK_LAYOUT) {
                        inflater.inflate(R.layout.keyguard_screen_tab_bigclock_unlock_right, this,
                                         true);
                    } else if (mLockscreenLayout == BIG_CLOCK_LAYOUT_CENTERED_RING) {
                        inflater.inflate(R.layout.keyguard_screen_tab_bigclock_center_unlock_right, this,
                                         true);
                    }
                break;
            case FOUR_TARGETS:
            case SIX_TARGETS:
            case EIGHT_TARGETS:
                if (landscape)
                    inflater.inflate(R.layout.keyguard_screen_tab_unlock_land, this,
                                     true);
                else
                    if (mIsScreenLarge) {
                        inflater.inflate(R.layout.keyguard_screen_tab_unlock, this,
                                         true);
                    } else if (mLockscreenLayout == STOCK_LAYOUT) {
                        inflater.inflate(R.layout.keyguard_screen_tab_unlock_down, this,
                                         true);
                    } else if (mLockscreenLayout == STOCK_LAYOUT_CENTERED_RING) {
                        inflater.inflate(R.layout.keyguard_screen_tab_center_unlock_down, this,
                                         true);
                    } else if (mLockscreenLayout == CENTER_LAYOUT) {
                        inflater.inflate(R.layout.keyguard_center_screen_tab_unlock_down, this,
                                         true);
                    } else if (mLockscreenLayout == CENTER_LAYOUT_CENTERED_RING) {
                        inflater.inflate(R.layout.keyguard_center_screen_tab_center_unlock_down, this,
                                         true);
                    } else if (mLockscreenLayout == BIG_CLOCK_LAYOUT) {
                        inflater.inflate(R.layout.keyguard_screen_tab_bigclock_unlock_down, this,
                                         true);
                    } else if (mLockscreenLayout == BIG_CLOCK_LAYOUT_CENTERED_RING) {
                        inflater.inflate(R.layout.keyguard_screen_tab_bigclock_center_unlock_down, this,
                                         true);
                    }
                break;
        }

        mStatusViewManager = new KeyguardStatusViewManager(this, mUpdateMonitor, mLockPatternUtils,
                mCallback, false);

        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mSilentMode = isSilentMode();

        mDigitalClock = (DigitalClock) findViewById(R.id.time);
        mCarrier = (TextView) findViewById(R.id.carrier);

        mUnlockWidget = findViewById(R.id.unlock_widget);
        if (mUnlockWidget instanceof SlidingTab) {
            SlidingTab slidingTabView = (SlidingTab) mUnlockWidget;
            slidingTabView.setHoldAfterTrigger(true, false);
            slidingTabView.setLeftHintText(R.string.lockscreen_unlock_label);
            slidingTabView.setLeftTabResources(
                    R.drawable.ic_jog_dial_unlock,
                    R.drawable.jog_tab_target_green,
                    R.drawable.jog_tab_bar_left_unlock,
                    R.drawable.jog_tab_left_unlock);
            SlidingTabMethods slidingTabMethods = new SlidingTabMethods(slidingTabView);
            slidingTabView.setOnTriggerListener(slidingTabMethods);
            mUnlockWidgetMethods = slidingTabMethods;
        } else if (mUnlockWidget instanceof WaveView) {
            WaveView waveView = (WaveView) mUnlockWidget;
            WaveViewMethods waveViewMethods = new WaveViewMethods(waveView);
            waveView.setOnTriggerListener(waveViewMethods);
            mUnlockWidgetMethods = waveViewMethods;
        } else if (mUnlockWidget instanceof MultiWaveView) {
            MultiWaveView multiWaveView = (MultiWaveView) mUnlockWidget;
            MultiWaveViewMethods multiWaveViewMethods = new MultiWaveViewMethods(multiWaveView);
            multiWaveView.setOnTriggerListener(multiWaveViewMethods);
            mUnlockWidgetMethods = multiWaveViewMethods;
        } else {
            throw new IllegalStateException("Unrecognized unlock widget: " + mUnlockWidget);
        }

        // Update widget with initial ring state
        mUnlockWidgetMethods.updateResources();
        // Update the settings everytime we draw lockscreen
        updateSettings();

        if (DBG) Log.v(TAG, "*** LockScreen accel is "
                + (mUnlockWidget.isHardwareAccelerated() ? "on":"off"));
    }

    private boolean isSilentMode() {
        return mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && mEnableMenuKeyInLockScreen) {
            mCallback.goToUnlockScreen();
        }
        return false;	  	
    }

    void updateConfiguration() {
        Configuration newConfig = getResources().getConfiguration();
        if (newConfig.orientation != mCreationOrientation) {
            mCallback.recreateMe(newConfig);
        } else if (newConfig.hardKeyboardHidden != mKeyboardHidden) {
            mKeyboardHidden = newConfig.hardKeyboardHidden;
            final boolean isKeyboardOpen = mKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
            if (mUpdateMonitor.isKeyguardBypassEnabled() && isKeyboardOpen) {
                mCallback.goToUnlockScreen();
            }
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.v(TAG, "***** LOCK ATTACHED TO WINDOW");
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + ", new config=" + getResources().getConfiguration());
        }
        updateConfiguration();
        updateSettings();
    }

    /** {@inheritDoc} */
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (LockPatternKeyguardView.DEBUG_CONFIGURATION) {
            Log.w(TAG, "***** LOCK CONFIG CHANGING", new RuntimeException());
            Log.v(TAG, "Cur orient=" + mCreationOrientation
                    + ", new config=" + newConfig);
        }
        updateConfiguration();
    }

    /** {@inheritDoc} */
    public boolean needsInput() {
        return false;
    }

    /** {@inheritDoc} */
    public void onPause() {
        mStatusViewManager.onPause();
        mUnlockWidgetMethods.reset(false);
    }

    private final Runnable mOnResumePing = new Runnable() {
        public void run() {
            mUnlockWidgetMethods.ping();
        }
    };

    /** {@inheritDoc} */
    public void onResume() {
        mStatusViewManager.onResume();
        postDelayed(mOnResumePing, ON_RESUME_PING_DELAY);
        // update the settings when we resume
        if (DEBUG) Log.d(TAG, "We are resuming and want to update settings");
        updateSettings();
    }

    /** {@inheritDoc} */
    public void cleanUp() {
        mUpdateMonitor.removeCallback(this); // this must be first
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
        mSettingsObserver = null;
        mLockPatternUtils = null;
        mUpdateMonitor = null;
        mCallback = null;
    }

    /** {@inheritDoc} */
    public void onRingerModeChanged(int state) {
        boolean silent = AudioManager.RINGER_MODE_NORMAL != state;
        if (silent != mSilentMode) {
            mSilentMode = silent;
            mUnlockWidgetMethods.updateResources();
        }
    }

    public void onPhoneStateChanged(String newState) {
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.LOCKSCREEN_TARGETS),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.LOCKSCREEN_LAYOUT),
                    false, this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.LOCKSCREEN_CUSTOM_TEXT_COLOR),
                    false, this);
            updateSettings();
        }

        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    private void updateSettings() {
        if (DEBUG) Log.d(TAG, "Settings for lockscreen have changed lets update");
        ContentResolver resolver = mContext.getContentResolver();

        mLockscreenTargets = Settings.System.getInt(resolver,
        Settings.System.LOCKSCREEN_TARGETS, STOCK_TARGETS);

        mLockscreenLayout = Settings.System.getInt(resolver,
        Settings.System.LOCKSCREEN_LAYOUT, STOCK_LAYOUT);

        int mLockscreenColor = Settings.System.getInt(resolver,
                Settings.System.LOCKSCREEN_CUSTOM_TEXT_COLOR, COLOR_WHITE);

        // digital clock first (see @link com.android.internal.widget.DigitalClock.updateTime())
        try {
            mDigitalClock.updateTime();
        } catch (NullPointerException npe) {
            if (DEBUG) Log.d(TAG, "date update time failed: NullPointerException");
        }

        // then the rest (see @link com.android.internal.policy.impl.KeyguardStatusViewManager.updateColors())
        try {
            mStatusViewManager.updateColors();
        } catch (NullPointerException npe) {
            if (DEBUG) Log.d(TAG, "KeyguardStatusViewManager.updateColors() failed: NullPointerException");
        }
    }
}
