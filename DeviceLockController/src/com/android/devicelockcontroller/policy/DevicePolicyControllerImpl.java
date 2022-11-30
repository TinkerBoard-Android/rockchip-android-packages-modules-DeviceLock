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

package com.android.devicelockcontroller.policy;

import static com.android.devicelockcontroller.policy.PolicyHandler.SUCCESS;

import android.app.ActivityOptions;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemProperties;
import android.os.UserManager;

import androidx.annotation.Nullable;

import com.android.devicelockcontroller.common.DeviceLockConstants;
import com.android.devicelockcontroller.policy.DeviceStateController.DeviceState;
import com.android.devicelockcontroller.setup.SetupParameters;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Class that listens to state changes and applies the corresponding policies.
 */
public final class DevicePolicyControllerImpl
        implements DevicePolicyController, DeviceStateController.StateListener {
    private static final String TAG = "DevicePolicyControllerImpl";

    private final List<PolicyHandler> mPolicyList = new ArrayList<>();
    private final Context mContext;
    private final DevicePolicyManager mDpm;
    private final LockTaskModePolicyHandler mLockTaskHandler;
    private final DeviceStateController mStateController;

    /**
     * Create a new policy controller.
     *
     * @param context The context used by this policy controller.
     * @param componentName Admin component name.
     * @param stateController State controller.
     */
    public DevicePolicyControllerImpl(
            Context context, ComponentName componentName, DeviceStateController stateController) {
        this(context, componentName, stateController,
             context.getSystemService(DevicePolicyManager.class));
    }

    @VisibleForTesting
    DevicePolicyControllerImpl(Context context, ComponentName componentName,
            DeviceStateController stateController, DevicePolicyManager dpm) {
        mContext = context;
        mDpm = dpm;
        mStateController = stateController;
        mLockTaskHandler = new LockTaskModePolicyHandler(context, componentName, dpm);

        final boolean isDebug = SystemProperties.getInt("ro.debuggable", 0) == 1;
        mPolicyList.add(new UserRestrictionsPolicyHandler(context, componentName, dpm,
                context.getSystemService(UserManager.class), isDebug));
        mPolicyList.add(mLockTaskHandler);
        mPolicyList.add(new KioskAppPolicyHandler(context, componentName, dpm));
        stateController.addCallback(this);
    }

    @Override
    public boolean launchActivityInLockedMode() {
        final Intent launchIntent = getLockedActivity();

        if (launchIntent == null) {
            return false;
        }

        final ComponentName activity = launchIntent.getComponent();
        if (activity == null || !mLockTaskHandler.setPreferredActivityForHome(activity)) {
            LogUtil.w(TAG, "Failed to set preferred activity");
            return false;
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        LogUtil.i(TAG, String.format(Locale.US, "Launching activity: %s", activity));
        mContext.startActivity(
                launchIntent, ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle());

        return true;
    }

    @Override
    public boolean wipeData() {
        LogUtil.i(TAG, "Wiping device");

        try {
            mDpm.wipeData(DevicePolicyManager.WIPE_SILENTLY
                    | DevicePolicyManager.WIPE_RESET_PROTECTION_DATA);
        } catch (SecurityException e) {
            LogUtil.e(TAG, "Cannot wipe device", e);

            return false;
        }

        return true;
    }

    @Override
    public void onStateChanged(@DeviceState int newState) {
        LogUtil.d(TAG, String.format(Locale.US, "onStateChanged (%d)", newState));

        for (int i = 0, policyLen = mPolicyList.size(); i < policyLen; i++) {
            PolicyHandler policy = mPolicyList.get(i);
            if (newState == DeviceState.SETUP_IN_PROGRESS) {
                final String kioskPackage = SetupParameters.getKioskPackage(mContext);
                if (kioskPackage == null) {
                    throw new NullPointerException("SetupParameters must be present before"
                            + " finalization.");
                }
                policy.setSetupParametersValid();
            }

            try {
                LogUtil.d(TAG, String.format(Locale.US, "setPolicyForState (%s)", policy));
                if (SUCCESS != policy.setPolicyForState(newState)) {
                    LogUtil.e(TAG, String.format(Locale.US, "Failed to set %s policy", policy));
                }
            } catch (SecurityException e) {
                LogUtil.e(TAG, "Exception when setting policy", e);
            }
        }
    }

    @Nullable
    private Intent getLockedActivity() {
        @DeviceState int state = mStateController.getState();

        switch (state) {
            case DeviceState.SETUP_IN_PROGRESS:
            case DeviceState.SETUP_SUCCEEDED:
            case DeviceState.SETUP_FAILED:
                return new Intent()
                    .setComponent(ComponentName
                            .unflattenFromString(DeviceLockConstants.SETUP_FAILED_ACTIVITY));
            case DeviceState.KIOSK_SETUP:
                return getKioskSetupActivityIntent();
            case DeviceState.LOCKED:
                return getLockScreenActivityIntent();
            case DeviceState.UNLOCKED:
            case DeviceState.CLEARED:
            case DeviceState.UNPROVISIONED:
                LogUtil.w(TAG, String.format(Locale.US, "%d is not a locked state", state));
                return null;
            default:
                LogUtil.w(TAG, String.format(Locale.US, "%d is an invalid state", state));
                return null;
        }
    }

    @Nullable
    private Intent getLockScreenActivityIntent() {
        final PackageManager packageManager = mContext.getPackageManager();
        final String kioskPackage = SetupParameters.getKioskPackage(mContext);
        if (kioskPackage == null) {
            LogUtil.e(TAG, "Missing kiosk package parameter");
            return null;
        }

        final Intent homeIntent =
                new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setPackage(kioskPackage);
        final ResolveInfo resolvedInfo =
                packageManager.resolveActivity(
                        homeIntent,
                        PackageManager.MATCH_DEFAULT_ONLY);
        if (resolvedInfo != null && resolvedInfo.activityInfo != null) {
            return homeIntent.setComponent(
                    new ComponentName(kioskPackage, resolvedInfo.activityInfo.name));
        }
        // Kiosk app does not have an activity to handle the default home intent. Fall back to the
        // launch activity.
        // Note that in this case, Kiosk App can't be effectively set as the default home activity.
        final Intent launchIntent = packageManager.getLaunchIntentForPackage(kioskPackage);
        if (launchIntent == null) {
            LogUtil.e(TAG, String.format(Locale.US, "Failed to get launch intent for %s",
                        kioskPackage));
            return null;
        }

        return launchIntent;
    }

    @Nullable
    private Intent getKioskSetupActivityIntent() {
        final String setupActivity = SetupParameters.getKioskSetupActivity(mContext);

        if (setupActivity == null) {
            LogUtil.e(TAG, "Failed to get setup Activity");
            return null;
        }

        return new Intent().setComponent(ComponentName.unflattenFromString(setupActivity));
    }
}