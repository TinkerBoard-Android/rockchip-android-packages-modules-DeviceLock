/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.devicelockcontroller;

import android.app.Application;
import android.content.ComponentName;

import androidx.annotation.MainThread;

import com.android.devicelockcontroller.policy.DevicePolicyController;
import com.android.devicelockcontroller.policy.DevicePolicyControllerImpl;
import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.DeviceStateControllerImpl;
import com.android.devicelockcontroller.policy.PolicyObjectsInterface;
import com.android.devicelockcontroller.util.LogUtil;

/**
 * Application class for Device Lock Controller.
 */
public final class DeviceLockControllerApplication extends Application implements
        PolicyObjectsInterface {
    private static final String TAG = "DeviceLockControllerApplication";

    private static final String DEVICE_ADMIN_RECEIVER_CLASS =
            "com.android.devicelockcontroller.receivers.DlcDeviceAdminReceiver";

    private DeviceStateController mStateController;
    private DevicePolicyController mPolicyController;

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtil.i(TAG, "onCreate");
    }

    @Override
    @MainThread
    public DeviceStateController getStateController() {
        if (mStateController == null) {
            mStateController = new DeviceStateControllerImpl(this);
        }

        return mStateController;
    }

    @Override
    @MainThread
    public DevicePolicyController getPolicyController() {
        if (mPolicyController == null) {
            final DeviceStateController stateController = getStateController();
            final ComponentName deviceAdmin =
                    new ComponentName(getPackageName(), DEVICE_ADMIN_RECEIVER_CLASS);
            mPolicyController = new DevicePolicyControllerImpl(this,
                    deviceAdmin, stateController);
        }

        return mPolicyController;
    }
}