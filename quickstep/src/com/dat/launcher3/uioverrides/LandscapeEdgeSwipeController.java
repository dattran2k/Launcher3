package com.dat.launcher3.uioverrides;

import android.view.MotionEvent;

import com.dat.launcher3.AbstractFloatingView;
import com.dat.launcher3.Launcher;
import com.dat.launcher3.LauncherState;
import com.dat.launcher3.touch.AbstractStateChangeTouchController;
import com.dat.launcher3.touch.SwipeDetector;
import com.dat.launcher3.userevent.nano.LauncherLogProto;
import com.dat.quickstep.RecentsModel;
import com.dat.launcher3.LauncherStateManager;
import com.dat.quickstep.TouchInteractionService;

/**
 * Touch controller for handling edge swipes in landscape/seascape UI
 */
public class LandscapeEdgeSwipeController extends AbstractStateChangeTouchController {

    private static final String TAG = "LandscapeEdgeSwipeCtrl";

    public LandscapeEdgeSwipeController(Launcher l) {
        super(l, SwipeDetector.HORIZONTAL);
    }

    @Override
    protected boolean canInterceptTouch(MotionEvent ev) {
        if (mCurrentAnimation != null) {
            // If we are already animating from a previous state, we can intercept.
            return true;
        }
        if (AbstractFloatingView.getTopOpenView(mLauncher) != null) {
            return false;
        }
        return mLauncher.isInState(LauncherState.NORMAL) && (ev.getEdgeFlags() & TouchInteractionService.EDGE_NAV_BAR) != 0;
    }

    @Override
    protected LauncherState getTargetState(LauncherState fromState, boolean isDragTowardPositive) {
        boolean draggingFromNav = mLauncher.getDeviceProfile().isSeascape() != isDragTowardPositive;
        return draggingFromNav ? LauncherState.OVERVIEW : LauncherState.NORMAL;
    }

    @Override
    protected int getLogContainerTypeForNormalState() {
        return LauncherLogProto.ContainerType.NAVBAR;
    }

    @Override
    protected float getShiftRange() {
        return mLauncher.getDragLayer().getWidth();
    }

    @Override
    protected float initCurrentAnimation(@LauncherStateManager.AnimationComponents int animComponent) {
        float range = getShiftRange();
        long maxAccuracy = (long) (2 * range);
        mCurrentAnimation = mLauncher.getStateManager().createAnimationToNewWorkspace(mToState,
                maxAccuracy, animComponent);
        return (mLauncher.getDeviceProfile().isSeascape() ? 2 : -2) / range;
    }

    @Override
    protected int getDirectionForLog() {
        return mLauncher.getDeviceProfile().isSeascape() ? LauncherLogProto.Action.Direction.RIGHT : LauncherLogProto.Action.Direction.LEFT;
    }

    @Override
    protected void onSwipeInteractionCompleted(LauncherState targetState, int logAction) {
        super.onSwipeInteractionCompleted(targetState, logAction);
        if (mStartState == LauncherState.NORMAL && targetState == LauncherState.OVERVIEW) {
            RecentsModel.getInstance(mLauncher).onOverviewShown(true, TAG);
        }
    }
}
