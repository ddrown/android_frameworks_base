/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.INetworkManagementService;
import android.os.ServiceManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Looper;
import android.os.Message;
import android.os.HandlerThread;
import android.net.INetworkManagementEventObserver;
import android.net.LinkProperties;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.telephony.RILConstants;

public class Nat464xlatService {
    private Context mContext;
    private BroadcastReceiver mStateReceiver;
    private InterfaceObserver mInterfaceObserver;
    private StateMachine mNat464xlatStateMachine;
    private HandlerThread mThread;
    private Looper mLooper;
    private String mUpstreamInterface;

    private final INetworkManagementService mNMService;
    private final IConnectivityManager mConnService;

    public static String ACTION_NAT_464XLAT_STATE_CHANGED = "com.android.server.connectivity.nat464xlatservice";
    public static String DATA_STATE = "DATA_STATE";
    public static String DATA_UPSTREAM_INTERFACE = "DATA_UPSTREAM_INTERFACE";
    public static String DATA_CLAT_INTERFACE = "DATA_CLAT_INTERFACE";

    public static String STATE_RUNNING = "running";
    public static String STATE_STOPPING = "stopping";

    private static final String TAG = "Nat464xlatService";
    private static final String CLAT_INTERFACE_NAME = "clat";

    public Nat464xlatService(Context context, INetworkManagementService nmService, IConnectivityManager connService) {
        mContext = context;
        mNMService = nmService;
	mConnService = connService;
	mUpstreamInterface = null;

        mStateReceiver = new StateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(mStateReceiver, filter);

        mInterfaceObserver = new InterfaceObserver();
        try {
            mNMService.registerObserver(mInterfaceObserver);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register InterfaceObserver " + e);
        }

	mThread = new HandlerThread("464xlat");
	mThread.start();
        mLooper = mThread.getLooper();
	mNat464xlatStateMachine = new Nat464xlatStateMachine("464xlat", mLooper);
	mNat464xlatStateMachine.start();
    }

    private class StateReceiver extends BroadcastReceiver {
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
		NetworkInfo info = null;
		try {
		    info = mConnService.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
		} catch (RemoteException e) { }
		if((info != null) && info.isConnected()) {
		    mNat464xlatStateMachine.sendMessage(
			Nat464xlatStateMachine.CMD_UPSTREAM_INTERFACE_UP);
		} else {
		    mNat464xlatStateMachine.sendMessage(
			Nat464xlatStateMachine.CMD_UPSTREAM_INTERFACE_DOWN);
		}
            }
        }
    }

    private class InterfaceObserver extends INetworkManagementEventObserver.Stub {
	public InterfaceObserver() {
	}

	public void interfaceStatusChanged(String iface, boolean up) { }

	public void interfaceLinkStateChanged(String iface, boolean up) { }

	public void interfaceAdded(String iface) {
	    if(iface.equals(CLAT_INTERFACE_NAME)) {
		mNat464xlatStateMachine.sendMessage(
		    Nat464xlatStateMachine.CMD_CLAT_INTERFACE_UP);
	    }
	}

	public void interfaceRemoved(String iface) {
	    if(iface.equals(CLAT_INTERFACE_NAME)) {
		mNat464xlatStateMachine.sendMessage(
		    Nat464xlatStateMachine.CMD_CLAT_INTERFACE_DOWN);
	    }
	}

	public void limitReached(String limitName, String iface) {}
    }

    private class Nat464xlatStateMachine extends StateMachine {
	static final int CMD_CLAT_INTERFACE_UP = 1;
	static final int CMD_CLAT_INTERFACE_DOWN = 2;
	static final int CMD_UPSTREAM_INTERFACE_UP = 3;
	static final int CMD_UPSTREAM_INTERFACE_DOWN = 4;

	private State mStartingState;
	private State mRunningState;
	private State mStoppingState;
	private State mStoppedState;

	public Nat464xlatStateMachine(String name, Looper looper) {
	    super(name, looper);

            mStartingState = new StartingState();
            addState(mStartingState);
            mRunningState = new RunningState();
            addState(mRunningState);
            mStoppingState = new StoppingState();
            addState(mStoppingState);
            mStoppedState = new StoppedState();
            addState(mStoppedState);

	    setInitialState(mStoppedState);
	}

        public String toString() {
            String res = "???";
            IState current = getCurrentState();
            if (current == mStartingState) res = "StartingState";
            if (current == mRunningState) res = "RunningState";
            if (current == mStoppingState) res = "StoppingState";
            if (current == mStoppedState) res = "StoppedState";
            return res;
        }

        class StartingState extends State {
            @Override
            public void enter() {
		LinkProperties linkProperties = null;
                String iface = null;
		String protocolType;

		try {
		    linkProperties = mConnService.getLinkProperties(ConnectivityManager.TYPE_MOBILE);
		} catch(RemoteException e) {}
		if (linkProperties == null) {
		    Log.e(TAG, "StartingState: link properties for TYPE_MOBILE returned null");
		    transitionTo(mStoppedState);
		    return;
		}
		protocolType = linkProperties.getProtocolType();
		if((protocolType == null) || !protocolType.equals(RILConstants.SETUP_DATA_PROTOCOL_IPV6)) {
		    Log.d(TAG, "StartingState: link is protocol type="+protocolType+", skipping");
		    transitionTo(mStoppedState);
		    return;
		}
		iface = linkProperties.getInterfaceName();
		Log.d(TAG, "StartingState starting clat, iface="+iface);
		try {
		    mNMService.startClatd(iface);
		} catch(Exception e) {
		    Log.e(TAG, "StartingState failed to start clat: "+e.toString());
		}

		mUpstreamInterface = iface;
	    }
            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "StartingState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
		    case CMD_UPSTREAM_INTERFACE_DOWN:
			transitionTo(mStoppingState);
			break;
		    case CMD_CLAT_INTERFACE_UP:
			transitionTo(mRunningState);
			break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
	    }
	}

        class RunningState extends State {
	    @Override
	    public void enter() {
		Log.e(TAG, "transitioned to RunningState");
		Intent intent = new Intent(ACTION_NAT_464XLAT_STATE_CHANGED);
		intent.putExtra(DATA_STATE, STATE_RUNNING);
		intent.putExtra(DATA_UPSTREAM_INTERFACE, mUpstreamInterface);
		intent.putExtra(DATA_CLAT_INTERFACE, CLAT_INTERFACE_NAME);
		mContext.sendBroadcast(intent);
	    }
            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "RunningState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_UPSTREAM_INTERFACE_UP:
                        Log.e(TAG, "got upstream interface up while clat is running");
                        break;
		    case CMD_UPSTREAM_INTERFACE_DOWN:
			transitionTo(mStoppingState);
			break;
		    case CMD_CLAT_INTERFACE_UP:
			break;
		    case CMD_CLAT_INTERFACE_DOWN:
			transitionTo(mStoppingState);
			break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
	    }
	}

        class StoppingState extends State {
            @Override
	    public void enter() {
		Log.d(TAG, "StoppingState: stopping clat");
		try {
		    mNMService.stopClatd();
		} catch(Exception e) {
		    Log.e(TAG, "StoppingState failed to stop clat: "+e.toString());
		}
		Log.d(TAG, "StoppingState: clat stopped");

		Intent intent = new Intent(ACTION_NAT_464XLAT_STATE_CHANGED);
		intent.putExtra(DATA_STATE, STATE_STOPPING);
		intent.putExtra(DATA_UPSTREAM_INTERFACE, mUpstreamInterface);
		intent.putExtra(DATA_CLAT_INTERFACE, CLAT_INTERFACE_NAME);
		mContext.sendBroadcast(intent);

		mUpstreamInterface = null;
		transitionTo(mStoppedState);
	    }
            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "StoppingState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_UPSTREAM_INTERFACE_UP:
                        Log.e(TAG, "got upstream interface up while stopping clat");
                        break;
		    case CMD_UPSTREAM_INTERFACE_DOWN:
			break;
		    case CMD_CLAT_INTERFACE_UP:
			break;
		    case CMD_CLAT_INTERFACE_DOWN:
			break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
	    }
	}

        class StoppedState extends State {
	    @Override
	    public void enter() {
		 Log.e(TAG, "transitioned to StoppedState");
	    }
            @Override
            public boolean processMessage(Message message) {
                Log.d(TAG, "StoppedState.processMessage what=" + message.what);
                boolean retValue = true;
                switch (message.what) {
                    case CMD_UPSTREAM_INTERFACE_UP:
                        transitionTo(mStartingState);
                        break;
		    case CMD_UPSTREAM_INTERFACE_DOWN:
			break;
		    case CMD_CLAT_INTERFACE_UP:
			Log.e(TAG, "StoppedState, clat interface up?");
			break;
		    case CMD_CLAT_INTERFACE_DOWN:
			break;
                    default:
                        retValue = false;
                        break;
                }
                return retValue;
	    }
	}
    }
}
