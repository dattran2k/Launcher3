/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.dat.quickstep;

import static com.dat.quickstep.WindowTransformSwipeHandler.MAX_SWIPE_DURATION;
import static com.dat.quickstep.WindowTransformSwipeHandler.MIN_OVERSHOOT_DURATION;

import android.animation.ValueAnimator;
import android.view.animation.Interpolator;

import com.dat.launcher3.Launcher;
import com.dat.launcher3.LauncherAnimUtils;
import com.dat.launcher3.LauncherStateManager;
import com.dat.launcher3.R;
import com.dat.launcher3.Utilities;
import com.dat.launcher3.allapps.AllAppsTransitionController;
import com.dat.launcher3.allapps.DiscoveryBounce;
import com.dat.launcher3.anim.AnimatorPlaybackController;
import com.dat.launcher3.anim.AnimatorSetBuilder;
import com.dat.launcher3.anim.Interpolators;
import com.dat.launcher3.uioverrides.PortraitStatesTouchController;
import com.dat.launcher3.util.FlingBlockCheck;
import com.dat.quickstep.util.RemoteAnimationTargetSet;
import com.dat.quickstep.views.RecentsView;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;
import com.dat.launcher3.LauncherState;
import com.dat.launcher3.userevent.nano.LauncherLogProto;

/**
 * Utility class to handle long swipe from an app.
 * This assumes the presence of Launcher activity as long swipe is not supported on the
 * fallback activity.
 */
public class LongSwipeHelper {

    private static final float SWIPE_DURATION_MULTIPLIER =
            Math.min(1 / LauncherAnimUtils.MIN_PROGRESS_TO_ALL_APPS, 1 / (1 - LauncherAnimUtils.MIN_PROGRESS_TO_ALL_APPS));

    private final Launcher mLauncher;
    private final RemoteAnimationTargetSet mTargetSet;

    private float mMaxSwipeDistance = 1;
    private AnimatorPlaybackController mAnimator;
    private FlingBlockCheck mFlingBlockCheck = new FlingBlockCheck();

    LongSwipeHelper(Launcher launcher, RemoteAnimationTargetSet targetSet) {
        mLauncher = launcher;
        mTargetSet = targetSet;
        init();
    }

    private void init() {
        mFlingBlockCheck.blockFling();

        // Init animations
        AllAppsTransitionController controller = mLauncher.getAllAppsController();
        // TODO: Scale it down so that we can reach all-apps in screen space
        mMaxSwipeDistance = Math.max(1, controller.getProgress() * controller.getShiftRange());

        AnimatorSetBuilder builder = PortraitStatesTouchController.getOverviewToAllAppsAnimation();
        mAnimator = mLauncher.getStateManager().createAnimationToNewWorkspace(LauncherState.ALL_APPS, builder,
                Math.round(2 * mMaxSwipeDistance), null, LauncherStateManager.ANIM_ALL);
        mAnimator.dispatchOnStart();
    }

    public void onMove(float displacement) {
        mAnimator.setPlayFraction(displacement / mMaxSwipeDistance);
        mFlingBlockCheck.onEvent();
    }

    public void destroy() {
        // TODO: We can probably also show the task view

        mLauncher.getStateManager().goToState(LauncherState.OVERVIEW, false);
    }

    public void end(float velocity, boolean isFling, Runnable callback) {
        float velocityPxPerMs = velocity / 1000;
        long duration = MAX_SWIPE_DURATION;
        Interpolator interpolator = Interpolators.DEACCEL;

        final float currentFraction = mAnimator.getProgressFraction();
        final boolean toAllApps;
        float endProgress;

        boolean blockedFling = isFling && mFlingBlockCheck.isBlocked();
        if (blockedFling) {
            isFling = false;
        }

        if (!isFling) {
            toAllApps = currentFraction > LauncherAnimUtils.MIN_PROGRESS_TO_ALL_APPS;
            endProgress = toAllApps ? 1 : 0;

            long expectedDuration = Math.abs(Math.round((endProgress - currentFraction)
                    * MAX_SWIPE_DURATION * SWIPE_DURATION_MULTIPLIER));
            duration = Math.min(MAX_SWIPE_DURATION, expectedDuration);

            if (blockedFling && !toAllApps) {
                Interpolators.OvershootParams overshoot = new Interpolators.OvershootParams(currentFraction,
                        currentFraction, endProgress, velocityPxPerMs, (int) mMaxSwipeDistance);
                duration = (overshoot.duration + duration);
                duration = Utilities.boundToRange(duration, MIN_OVERSHOOT_DURATION,
                        MAX_SWIPE_DURATION);
                interpolator = overshoot.interpolator;
                endProgress = overshoot.end;
            }
        } else {
            toAllApps = velocity < 0;
            endProgress = toAllApps ? 1 : 0;

            float minFlingVelocity = mLauncher.getResources()
                    .getDimension(R.dimen.quickstep_fling_min_velocity);
            if (Math.abs(velocity) > minFlingVelocity && mMaxSwipeDistance > 0) {
                float distanceToTravel = (endProgress - currentFraction) * mMaxSwipeDistance;

                // we want the page's snap velocity to approximately match the velocity at
                // which the user flings, so we scale the duration by a value near to the
                // derivative of the scroll interpolator at zero, ie. 2.
                long baseDuration = Math.round(Math.abs(distanceToTravel / velocityPxPerMs));
                duration = Math.min(MAX_SWIPE_DURATION, 2 * baseDuration);
            }
        }

        final boolean finalIsFling = isFling;
        mAnimator.setEndAction(() -> onSwipeAnimationComplete(toAllApps, finalIsFling, callback));

        ValueAnimator animator = mAnimator.getAnimationPlayer();
        animator.setDuration(duration).setInterpolator(interpolator);
        animator.setFloatValues(currentFraction, endProgress);
        animator.start();
    }

    private void onSwipeAnimationComplete(boolean toAllApps, boolean isFling, Runnable callback) {
        mLauncher.getStateManager().goToState(toAllApps ? LauncherState.ALL_APPS : LauncherState.OVERVIEW, false);
        if (!toAllApps) {
            DiscoveryBounce.showForOverviewIfNeeded(mLauncher);
            mLauncher.<RecentsView>getOverviewPanel().setSwipeDownShouldLaunchApp(true);
        }

        mLauncher.getUserEventDispatcher().logStateChangeAction(
                isFling ? LauncherLogProto.Action.Touch.FLING : LauncherLogProto.Action.Touch.SWIPE, LauncherLogProto.Action.Direction.UP,
                LauncherLogProto.ContainerType.NAVBAR, LauncherLogProto.ContainerType.APP,
                toAllApps ? LauncherLogProto.ContainerType.ALLAPPS : LauncherLogProto.ContainerType.TASKSWITCHER,
                0);

        callback.run();
    }

    public float getTargetAlpha(RemoteAnimationTargetCompat app, Float expectedAlpha) {
        if (!(app.isNotInRecents
                || app.activityType == RemoteAnimationTargetCompat.ACTIVITY_TYPE_HOME)) {
            return 0;
        }
        return expectedAlpha;
    }
}
