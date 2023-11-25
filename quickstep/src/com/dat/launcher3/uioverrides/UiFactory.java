/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.view.View.VISIBLE;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.os.CancellationSignal;
import android.util.Base64;

import com.dat.launcher3.AbstractFloatingView;
import com.dat.launcher3.DeviceProfile;
import com.dat.launcher3.Launcher;
import com.dat.launcher3.LauncherAppTransitionManagerImpl;
import com.dat.launcher3.LauncherState;
import com.dat.launcher3.LauncherStateManager;
import com.dat.launcher3.Utilities;
import com.dat.launcher3.anim.AnimatorPlaybackController;
import com.dat.launcher3.util.TouchController;
import com.dat.quickstep.OverviewInteractionState;
import com.dat.quickstep.RecentsModel;
import com.dat.quickstep.util.RemoteFadeOutAnimationListener;
import com.dat.quickstep.views.RecentsView;
import com.android.systemui.shared.system.ActivityCompat;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.dat.launcher3.LauncherAnimUtils;
import com.dat.launcher3.allapps.DiscoveryBounce;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.zip.Deflater;

public class UiFactory {

    public static TouchController[] createTouchControllers(Launcher launcher) {
        boolean swipeUpEnabled = OverviewInteractionState.getInstance(launcher)
                .isSwipeUpGestureEnabled();
        if (!swipeUpEnabled) {
            return new TouchController[] {
                    launcher.getDragController(),
                    new OverviewToAllAppsTouchController(launcher),
                    new LauncherTaskViewController(launcher)};
        }
        if (launcher.getDeviceProfile().isVerticalBarLayout()) {
            return new TouchController[] {
                    launcher.getDragController(),
                    new OverviewToAllAppsTouchController(launcher),
                    new LandscapeEdgeSwipeController(launcher),
                    new LauncherTaskViewController(launcher)};
        } else {
            return new TouchController[] {
                    launcher.getDragController(),
                    new PortraitStatesTouchController(launcher),
                    new LauncherTaskViewController(launcher)};
        }
    }

    public static void setOnTouchControllersChangedListener(Context context, Runnable listener) {
        OverviewInteractionState.getInstance(context).setOnSwipeUpSettingChangedListener(listener);
    }

    public static LauncherStateManager.StateHandler[] getStateHandler(Launcher launcher) {
        return new LauncherStateManager.StateHandler[] {launcher.getAllAppsController(), launcher.getWorkspace(),
                new RecentsViewStateController(launcher), new BackButtonAlphaHandler(launcher)};
    }

    /**
     * Sets the back button visibility based on the current state/window focus.
     */
    public static void onLauncherStateOrFocusChanged(Launcher launcher) {
        boolean shouldBackButtonBeHidden = launcher != null
                && launcher.getStateManager().getState().hideBackButton
                && launcher.hasWindowFocus();
        if (shouldBackButtonBeHidden) {
            // Show the back button if there is a floating view visible.
            shouldBackButtonBeHidden = AbstractFloatingView.getTopOpenViewWithType(launcher,
                    AbstractFloatingView.TYPE_ALL & ~AbstractFloatingView.TYPE_HIDE_BACK_BUTTON) == null;
        }
        OverviewInteractionState.getInstance(launcher)
                .setBackButtonAlpha(shouldBackButtonBeHidden ? 0 : 1, true /* animate */);
    }

    public static void resetOverview(Launcher launcher) {
        RecentsView recents = launcher.getOverviewPanel();
        recents.reset();
    }

    public static void onCreate(Launcher launcher) {
        if (!launcher.getSharedPrefs().getBoolean(DiscoveryBounce.HOME_BOUNCE_SEEN, false)) {
            launcher.getStateManager().addStateListener(new LauncherStateManager.StateListener() {
                @Override
                public void onStateSetImmediately(LauncherState state) {
                }

                @Override
                public void onStateTransitionStart(LauncherState toState) {
                }

                @Override
                public void onStateTransitionComplete(LauncherState finalState) {
                    boolean swipeUpEnabled = OverviewInteractionState.getInstance(launcher)
                            .isSwipeUpGestureEnabled();
                    LauncherState prevState = launcher.getStateManager().getLastState();

                    if (((swipeUpEnabled && finalState == LauncherState.OVERVIEW) || (!swipeUpEnabled
                            && finalState == LauncherState.ALL_APPS && prevState == LauncherState.NORMAL))) {
                        launcher.getSharedPrefs().edit().putBoolean(DiscoveryBounce.HOME_BOUNCE_SEEN, true).apply();
                        launcher.getStateManager().removeStateListener(this);
                    }
                }
            });
        }

        if (!launcher.getSharedPrefs().getBoolean(DiscoveryBounce.SHELF_BOUNCE_SEEN, false)) {
            launcher.getStateManager().addStateListener(new LauncherStateManager.StateListener() {
                @Override
                public void onStateSetImmediately(LauncherState state) {
                }

                @Override
                public void onStateTransitionStart(LauncherState toState) {
                }

                @Override
                public void onStateTransitionComplete(LauncherState finalState) {
                    LauncherState prevState = launcher.getStateManager().getLastState();

                    if (finalState == LauncherState.ALL_APPS && prevState == LauncherState.OVERVIEW) {
                        launcher.getSharedPrefs().edit().putBoolean(DiscoveryBounce.SHELF_BOUNCE_SEEN, true).apply();
                        launcher.getStateManager().removeStateListener(this);
                    }
                }
            });
        }
    }

    public static void onStart(Context context) {
        RecentsModel model = RecentsModel.getInstance(context);
        if (model != null) {
            model.onStart();
        }
    }

    public static void onEnterAnimationComplete(Context context) {
        // After the transition to home, enable the high-res thumbnail loader if it wasn't enabled
        // as a part of quickstep/scrub, so that high-res thumbnails can load the next time we
        // enter overview
        RecentsModel.getInstance(context).getRecentsTaskLoader()
                .getHighResThumbnailLoader().setVisible(true);
    }

    public static void onLauncherStateOrResumeChanged(Launcher launcher) {
        LauncherState state = launcher.getStateManager().getState();
        DeviceProfile profile = launcher.getDeviceProfile();
        WindowManagerWrapper.getInstance().setShelfHeight(
                (state == LauncherState.NORMAL || state == LauncherState.OVERVIEW) && launcher.isUserActive()
                        && !profile.isVerticalBarLayout(),
                profile.hotseatBarSizePx);

        if (state == LauncherState.NORMAL) {
            launcher.<RecentsView>getOverviewPanel().setSwipeDownShouldLaunchApp(false);
        }
    }

    public static void onTrimMemory(Context context, int level) {
        RecentsModel model = RecentsModel.getInstance(context);
        if (model != null) {
            model.onTrimMemory(level);
        }
    }

    public static void useFadeOutAnimationForLauncherStart(Launcher launcher,
            CancellationSignal cancellationSignal) {
        LauncherAppTransitionManagerImpl appTransitionManager =
                (LauncherAppTransitionManagerImpl) launcher.getAppTransitionManager();
        appTransitionManager.setRemoteAnimationProvider((targets) -> {

            // On the first call clear the reference.
            cancellationSignal.cancel();

            ValueAnimator fadeAnimation = ValueAnimator.ofFloat(1, 0);
            fadeAnimation.addUpdateListener(new RemoteFadeOutAnimationListener(targets));
            AnimatorSet anim = new AnimatorSet();
            anim.play(fadeAnimation);
            return anim;
        }, cancellationSignal);
    }

    public static boolean dumpActivity(Activity activity, PrintWriter writer) {
        if (!Utilities.IS_DEBUG_DEVICE) {
            return false;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!(new ActivityCompat(activity).encodeViewHierarchy(out))) {
            return false;
        }

        Deflater deflater = new Deflater();
        deflater.setInput(out.toByteArray());
        deflater.finish();

        out.reset();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer); // returns the generated code... index
            out.write(buffer, 0, count);
        }

        writer.println("--encoded-view-dump-v0--");
        writer.println(Base64.encodeToString(
                out.toByteArray(), Base64.NO_WRAP | Base64.NO_PADDING));
        return true;
    }

    public static void prepareToShowOverview(Launcher launcher) {
        RecentsView overview = launcher.getOverviewPanel();
        if (overview.getVisibility() != VISIBLE || overview.getContentAlpha() == 0) {
            LauncherAnimUtils.SCALE_PROPERTY.set(overview, 1.33f);
        }
    }

    private static class LauncherTaskViewController extends TaskViewTouchController<Launcher> {

        public LauncherTaskViewController(Launcher activity) {
            super(activity);
        }

        @Override
        protected boolean isRecentsInteractive() {
            return mActivity.isInState(LauncherState.OVERVIEW);
        }

        @Override
        protected void onUserControlledAnimationCreated(AnimatorPlaybackController animController) {
            mActivity.getStateManager().setCurrentUserControlledAnimation(animController);
        }
    }
}
