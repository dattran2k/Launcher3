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
package com.dat.launcher3.uioverrides;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.animation.Interpolator;

import com.dat.launcher3.AbstractFloatingView;
import com.dat.launcher3.DeviceProfile;
import com.dat.launcher3.Launcher;
import com.dat.launcher3.LauncherState;
import com.dat.launcher3.allapps.AllAppsTransitionController;
import com.dat.launcher3.anim.AnimatorPlaybackController;
import com.dat.launcher3.anim.AnimatorSetBuilder;
import com.dat.launcher3.anim.Interpolators;
import com.dat.launcher3.touch.AbstractStateChangeTouchController;
import com.dat.launcher3.touch.SwipeDetector;
import com.dat.quickstep.RecentsModel;
import com.dat.quickstep.TouchInteractionService;
import com.dat.quickstep.views.RecentsView;
import com.dat.quickstep.views.TaskView;
import com.dat.launcher3.LauncherStateManager;
import com.dat.launcher3.userevent.nano.LauncherLogProto;

/**
 * Touch controller for handling various state transitions in portrait UI.
 */
public class PortraitStatesTouchController extends AbstractStateChangeTouchController {

    private static final String TAG = "PortraitStatesTouchCtrl";

    /**
     * The progress at which all apps content will be fully visible when swiping up from overview.
     */
    private static final float ALL_APPS_CONTENT_FADE_THRESHOLD = 0.08f;

    /**
     * The progress at which recents will begin fading out when swiping up from overview.
     */
    private static final float RECENTS_FADE_THRESHOLD = 0.88f;

    private InterpolatorWrapper mAllAppsInterpolatorWrapper = new InterpolatorWrapper();

    // If true, we will finish the current animation instantly on second touch.
    private boolean mFinishFastOnSecondTouch;


    public PortraitStatesTouchController(Launcher l) {
        super(l, SwipeDetector.VERTICAL);
    }

    @Override
    protected boolean canInterceptTouch(MotionEvent ev) {
        if (mCurrentAnimation != null) {
            if (mFinishFastOnSecondTouch) {
                // TODO: Animate to finish instead.
                mCurrentAnimation.getAnimationPlayer().end();
            }

            AllAppsTransitionController allAppsController = mLauncher.getAllAppsController();
            if (ev.getY() >= allAppsController.getShiftRange() * allAppsController.getProgress()) {
                // If we are already animating from a previous state, we can intercept as long as
                // the touch is below the current all apps progress (to allow for double swipe).
                return true;
            }
            // Otherwise, make sure everything is settled and don't intercept so they can scroll
            // recents, dismiss a task, etc.
            if (mAtomicAnim != null) {
                mAtomicAnim.end();
            }
            return false;
        }
        if (mLauncher.isInState(LauncherState.ALL_APPS)) {
            // In all-apps only listen if the container cannot scroll itself
            if (!mLauncher.getAppsView().shouldContainerScroll(ev)) {
                return false;
            }
        } else {
            // For all other states, only listen if the event originated below the hotseat height
            DeviceProfile dp = mLauncher.getDeviceProfile();
            int hotseatHeight = dp.hotseatBarSizePx + dp.getInsets().bottom;
            if (ev.getY() < (mLauncher.getDragLayer().getHeight() - hotseatHeight)) {
                return false;
            }
        }
        if (AbstractFloatingView.getTopOpenView(mLauncher) != null) {
            return false;
        }
        return true;
    }

    @Override
    protected LauncherState getTargetState(LauncherState fromState, boolean isDragTowardPositive) {
        if (fromState == LauncherState.ALL_APPS && !isDragTowardPositive) {
            // Should swipe down go to OVERVIEW instead?
            return TouchInteractionService.isConnected() ?
                    mLauncher.getStateManager().getLastState() : LauncherState.NORMAL;
        } else if (fromState == LauncherState.OVERVIEW) {
            return isDragTowardPositive ? LauncherState.ALL_APPS : LauncherState.NORMAL;
        } else if (fromState == LauncherState.NORMAL && isDragTowardPositive) {
            return TouchInteractionService.isConnected() ? LauncherState.OVERVIEW : LauncherState.ALL_APPS;
        }
        return fromState;
    }

    @Override
    protected int getLogContainerTypeForNormalState() {
        return LauncherLogProto.ContainerType.HOTSEAT;
    }

    private AnimatorSetBuilder getNormalToOverviewAnimation() {
        mAllAppsInterpolatorWrapper.baseInterpolator = Interpolators.LINEAR;

        AnimatorSetBuilder builder = new AnimatorSetBuilder();
        builder.setInterpolator(AnimatorSetBuilder.ANIM_VERTICAL_PROGRESS, mAllAppsInterpolatorWrapper);
        return builder;
    }

    public static AnimatorSetBuilder getOverviewToAllAppsAnimation() {
        AnimatorSetBuilder builder = new AnimatorSetBuilder();
        builder.setInterpolator(AnimatorSetBuilder.ANIM_ALL_APPS_FADE, Interpolators.clampToProgress(Interpolators.ACCEL,
                0, ALL_APPS_CONTENT_FADE_THRESHOLD));
        builder.setInterpolator(AnimatorSetBuilder.ANIM_OVERVIEW_FADE, Interpolators.clampToProgress(Interpolators.DEACCEL,
                RECENTS_FADE_THRESHOLD, 1));
        return builder;
    }

    private AnimatorSetBuilder getAllAppsToOverviewAnimation() {
        AnimatorSetBuilder builder = new AnimatorSetBuilder();
        builder.setInterpolator(AnimatorSetBuilder.ANIM_ALL_APPS_FADE, Interpolators.clampToProgress(Interpolators.DEACCEL,
                1 - ALL_APPS_CONTENT_FADE_THRESHOLD, 1));
        builder.setInterpolator(AnimatorSetBuilder.ANIM_OVERVIEW_FADE, Interpolators.clampToProgress(Interpolators.ACCEL,
                0f, 1 - RECENTS_FADE_THRESHOLD));
        return builder;
    }

    @Override
    protected AnimatorSetBuilder getAnimatorSetBuilderForStates(LauncherState fromState,
            LauncherState toState) {
        AnimatorSetBuilder builder = new AnimatorSetBuilder();
        if (fromState == LauncherState.NORMAL && toState == LauncherState.OVERVIEW) {
            builder = getNormalToOverviewAnimation();
        } else if (fromState == LauncherState.OVERVIEW && toState == LauncherState.ALL_APPS) {
            builder = getOverviewToAllAppsAnimation();
        } else if (fromState == LauncherState.ALL_APPS && toState == LauncherState.OVERVIEW) {
            builder = getAllAppsToOverviewAnimation();
        }
        return builder;
    }

    @Override
    protected float initCurrentAnimation(@LauncherStateManager.AnimationComponents int animComponents) {
        float range = getShiftRange();
        long maxAccuracy = (long) (2 * range);

        float startVerticalShift = mFromState.getVerticalProgress(mLauncher) * range;
        float endVerticalShift = mToState.getVerticalProgress(mLauncher) * range;

        float totalShift = endVerticalShift - startVerticalShift;

        final AnimatorSetBuilder builder = totalShift == 0 ? new AnimatorSetBuilder()
                : getAnimatorSetBuilderForStates(mFromState, mToState);

        cancelPendingAnim();

        RecentsView recentsView = mLauncher.getOverviewPanel();
        TaskView taskView = recentsView.getTaskViewAt(recentsView.getNextPage());
        if (recentsView.shouldSwipeDownLaunchApp() && mFromState == LauncherState.OVERVIEW && mToState == LauncherState.NORMAL
                && taskView != null) {
            // Reset the state manager, when changing the interaction mode
            mLauncher.getStateManager().goToState(LauncherState.OVERVIEW, false /* animate */);
            mPendingAnimation = recentsView.createTaskLauncherAnimation(taskView, maxAccuracy);
            mPendingAnimation.anim.setInterpolator(Interpolators.ZOOM_IN);

            Runnable onCancelRunnable = () -> {
                cancelPendingAnim();
                clearState();
            };
            mCurrentAnimation = AnimatorPlaybackController.wrap(mPendingAnimation.anim, maxAccuracy,
                    onCancelRunnable);
            mLauncher.getStateManager().setCurrentUserControlledAnimation(mCurrentAnimation);
        } else {
            mCurrentAnimation = mLauncher.getStateManager()
                    .createAnimationToNewWorkspace(mToState, builder, maxAccuracy, this::clearState,
                            animComponents);
        }

        if (totalShift == 0) {
            totalShift = Math.signum(mFromState.ordinal - mToState.ordinal)
                    * OverviewState.getDefaultSwipeHeight(mLauncher);
        }
        return 1 / totalShift;
    }

    private void cancelPendingAnim() {
        if (mPendingAnimation != null) {
            mPendingAnimation.finish(false, LauncherLogProto.Action.Touch.SWIPE);
            mPendingAnimation = null;
        }
    }

    @Override
    protected void updateSwipeCompleteAnimation(ValueAnimator animator, long expectedDuration,
            LauncherState targetState, float velocity, boolean isFling) {
        super.updateSwipeCompleteAnimation(animator, expectedDuration, targetState,
                velocity, isFling);
        handleFirstSwipeToOverview(animator, expectedDuration, targetState, velocity, isFling);
    }

    private void handleFirstSwipeToOverview(final ValueAnimator animator,
            final long expectedDuration, final LauncherState targetState, final float velocity,
            final boolean isFling) {
        if (mFromState == LauncherState.NORMAL && mToState == LauncherState.OVERVIEW && targetState == LauncherState.OVERVIEW) {
            mFinishFastOnSecondTouch = true;
            if (isFling && expectedDuration != 0) {
                // Update all apps interpolator to add a bit of overshoot starting from currFraction
                final float currFraction = mCurrentAnimation.getProgressFraction();
                mAllAppsInterpolatorWrapper.baseInterpolator = Interpolators.clampToProgress(
                        Interpolators.overshootInterpolatorForVelocity(velocity), currFraction, 1);
                animator.setDuration(Math.min(expectedDuration, ATOMIC_DURATION))
                        .setInterpolator(Interpolators.LINEAR);
            }
        } else {
            mFinishFastOnSecondTouch = false;
        }
    }

    @Override
    protected void onSwipeInteractionCompleted(LauncherState targetState, int logAction) {
        super.onSwipeInteractionCompleted(targetState, logAction);
        if (mStartState == LauncherState.NORMAL && targetState == LauncherState.OVERVIEW) {
            RecentsModel.getInstance(mLauncher).onOverviewShown(true, TAG);
        }
    }

    private static class InterpolatorWrapper implements Interpolator {

        public TimeInterpolator baseInterpolator = Interpolators.LINEAR;

        @Override
        public float getInterpolation(float v) {
            return baseInterpolator.getInterpolation(v);
        }
    }
}
