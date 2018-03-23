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

package com.android.launcher3;

import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.allapps.AllAppsTransitionController.ALL_APPS_PROGRESS;
import static com.android.systemui.shared.recents.utilities.Utilities.getNextFrameNumber;
import static com.android.systemui.shared.recents.utilities.Utilities.getSurface;
import static com.android.systemui.shared.recents.utilities.Utilities.postAtFrontOfQueueAsynchronously;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.MODE_CLOSING;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.MODE_OPENING;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;

import com.android.launcher3.DeviceProfile.OnDeviceProfileChangeListener;
import com.android.launcher3.InsettableFrameLayout.LayoutParams;
import com.android.launcher3.allapps.AllAppsTransitionController;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.anim.PropertyListBuilder;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.graphics.DrawableFactory;
import com.android.launcher3.shortcuts.DeepShortcutTextView;
import com.android.launcher3.shortcuts.DeepShortcutView;
import com.android.quickstep.RecentsAnimationInterpolator;
import com.android.quickstep.RecentsAnimationInterpolator.TaskWindowBounds;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.shared.recents.model.Task;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.system.ActivityCompat;
import com.android.systemui.shared.system.ActivityOptionsCompat;
import com.android.systemui.shared.system.RemoteAnimationAdapterCompat;
import com.android.systemui.shared.system.RemoteAnimationDefinitionCompat;
import com.android.systemui.shared.system.RemoteAnimationRunnerCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;
import com.android.systemui.shared.system.TransactionCompat;
import com.android.systemui.shared.system.WindowManagerWrapper;

/**
 * Manages the opening and closing app transitions from Launcher.
 */
@TargetApi(Build.VERSION_CODES.O)
@SuppressWarnings("unused")
public class LauncherAppTransitionManagerImpl extends LauncherAppTransitionManager
        implements OnDeviceProfileChangeListener {

    private static final String TAG = "LauncherTransition";
    private static final int REFRESH_RATE_MS = 16;
    private static final int STATUS_BAR_TRANSITION_DURATION = 120;

    private static final String CONTROL_REMOTE_APP_TRANSITION_PERMISSION =
            "android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS";

    private static final int APP_LAUNCH_DURATION = 500;
    // Use a shorter duration for x or y translation to create a curve effect
    private static final int APP_LAUNCH_CURVED_DURATION = 233;
    private static final int RECENTS_LAUNCH_DURATION = 336;
    private static final int LAUNCHER_RESUME_START_DELAY = 100;
    private static final int CLOSING_TRANSITION_DURATION_MS = 350;

    // Progress = 0: All apps is fully pulled up, Progress = 1: All apps is fully pulled down.
    private static final float ALL_APPS_PROGRESS_OFF_SCREEN = 1.3059858f;
    private static final float ALL_APPS_PROGRESS_OVERSHOOT = 0.99581414f;

    private final DragLayer mDragLayer;
    private final Launcher mLauncher;
    private DeviceProfile mDeviceProfile;

    private final float mContentTransY;
    private final float mWorkspaceTransY;
    private final float mRecentsTransX;
    private final float mRecentsTransY;
    private final float mRecentsScale;

    private View mFloatingView;
    private boolean mIsRtl;

    private LauncherTransitionAnimator mCurrentAnimator;

    public LauncherAppTransitionManagerImpl(Context context) {
        mLauncher = Launcher.getLauncher(context);
        mDragLayer = mLauncher.getDragLayer();
        mDeviceProfile = mLauncher.getDeviceProfile();

        mIsRtl = Utilities.isRtl(mLauncher.getResources());

        Resources res = mLauncher.getResources();
        mContentTransY = res.getDimensionPixelSize(R.dimen.content_trans_y);
        mWorkspaceTransY = res.getDimensionPixelSize(R.dimen.workspace_trans_y);
        mRecentsTransX = res.getDimensionPixelSize(R.dimen.recents_adjacent_trans_x);
        mRecentsTransY = res.getDimensionPixelSize(R.dimen.recents_adjacent_trans_y);
        mRecentsScale = res.getFraction(R.fraction.recents_adjacent_scale, 1, 1);

        mLauncher.addOnDeviceProfileChangeListener(this);
        registerRemoteAnimations();
    }

    @Override
    public void onDeviceProfileChanged(DeviceProfile dp) {
        mDeviceProfile = dp;
    }

    private void setCurrentAnimator(LauncherTransitionAnimator animator) {
        if (isAnimating()) {
            mCurrentAnimator.cancel();
        }
        mCurrentAnimator = animator;
    }

    @Override
    public void finishLauncherAnimation() {
        if (isAnimating()) {
            mCurrentAnimator.finishLauncherAnimation();
        }
        mCurrentAnimator = null;
    }

    @Override
    public boolean isAnimating() {
        return mCurrentAnimator != null && mCurrentAnimator.isRunning();
    }

    /**
     * @return ActivityOptions with remote animations that controls how the window of the opening
     *         targets are displayed.
     */
    @Override
    public ActivityOptions getActivityLaunchOptions(Launcher launcher, View v) {
        if (hasControlRemoteAppTransitionPermission()) {
            TaskView taskView = findTaskViewToLaunch(launcher, v);
            try {
                RemoteAnimationRunnerCompat runner = new LauncherAnimationRunner(mLauncher) {
                    @Override
                    public void onAnimationStart(RemoteAnimationTargetCompat[] targets,
                                                 Runnable finishedCallback) {
                        // Post at front of queue ignoring sync barriers to make sure it gets
                        // processed before the next frame.
                        postAtFrontOfQueueAsynchronously(v.getHandler(), () -> {
                            final boolean removeTrackingView;
                            LauncherTransitionAnimator animator = composeRecentsLaunchAnimator(
                                    taskView == null ? v : taskView, targets);
                            if (animator != null) {
                                // We are animating the task view directly, do not remove it after
                                removeTrackingView = false;
                            } else {
                                animator = composeAppLaunchAnimator(v, targets);
                                // A new floating view is created for the animation, remove it after
                                removeTrackingView = true;
                            }

                            setCurrentAnimator(animator);
                            mAnimator = animator.getAnimatorSet();
                            mAnimator.addListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    // Reset launcher to normal state
                                    v.setVisibility(View.VISIBLE);
                                    if (removeTrackingView) {
                                        ((ViewGroup) mDragLayer.getParent()).removeView(
                                                mFloatingView);
                                    }

                                    mDragLayer.setAlpha(1f);
                                    mDragLayer.setTranslationY(0f);

                                    View appsView = mLauncher.getAppsView();
                                    appsView.setAlpha(1f);
                                    appsView.setTranslationY(0f);

                                    finishedCallback.run();
                                }
                            });
                            mAnimator.start();
                            // Because t=0 has the app icon in its original spot, we can skip the
                            // first frame and have the same movement one frame earlier.
                            mAnimator.setCurrentPlayTime(REFRESH_RATE_MS);
                        });
                    }
                };

                int duration = taskView != null ? RECENTS_LAUNCH_DURATION : APP_LAUNCH_DURATION;
                int statusBarTransitionDelay = duration - STATUS_BAR_TRANSITION_DURATION;
                return ActivityOptionsCompat.makeRemoteAnimation(new RemoteAnimationAdapterCompat(
                        runner, duration, statusBarTransitionDelay));
            } catch (NoClassDefFoundError e) {
                // Gracefully fall back to default launch options if the user's platform doesn't
                // have the latest changes.
            }
        }
        return getDefaultActivityLaunchOptions(launcher, v);
    }

    /**
     * Try to find a TaskView that corresponds with the component of the launched view.
     *
     * If this method returns a non-null TaskView, it will be used in composeRecentsLaunchAnimation.
     * Otherwise, we will assume we are using a normal app transition, but it's possible that the
     * opening remote target (which we don't get until onAnimationStart) will resolve to a TaskView.
     */
    private TaskView findTaskViewToLaunch(Launcher launcher, View v) {
        if (v instanceof TaskView) {
            return (TaskView) v;
        }
        if (!launcher.isInState(LauncherState.OVERVIEW)) {
            return null;
        }
        if (v.getTag() instanceof ItemInfo) {
            ItemInfo itemInfo = (ItemInfo) v.getTag();
            ComponentName componentName = itemInfo.getTargetComponent();
            if (componentName != null) {
                RecentsView recentsView = launcher.getOverviewPanel();
                for (int i = 0; i < recentsView.getChildCount(); i++) {
                    TaskView taskView = (TaskView) recentsView.getPageAt(i);
                    if (recentsView.isTaskViewVisible(taskView)) {
                        Task task = taskView.getTask();
                        if (componentName.equals(task.key.getComponent())) {
                            return taskView;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Composes the animations for a launch from the recents list if possible.
     */
    private LauncherTransitionAnimator composeRecentsLaunchAnimator(View v,
            RemoteAnimationTargetCompat[] targets) {
        RecentsView recentsView = mLauncher.getOverviewPanel();
        boolean launcherClosing = launcherIsATargetWithMode(targets, MODE_CLOSING);
        MutableBoolean skipLauncherChanges = new MutableBoolean(!launcherClosing);
        if (v instanceof TaskView) {
            // We already found a task view to launch, so use that for the animation.
            TaskView taskView = (TaskView) v;
            return new LauncherTransitionAnimator(getRecentsLauncherAnimator(recentsView, taskView),
                    getRecentsWindowAnimator(taskView, skipLauncherChanges, targets));
        }

        // It's possible that the launched view can still be resolved to a visible task view, check
        // the task id of the opening task and see if we can find a match.

        // Ensure recents is actually visible
        if (!mLauncher.getStateManager().getState().overviewUi) {
            return null;
        }

        // Resolve the opening task id
        int openingTaskId = -1;
        for (RemoteAnimationTargetCompat target : targets) {
            if (target.mode == MODE_OPENING) {
                openingTaskId = target.taskId;
                break;
            }
        }

        // If there is no opening task id, fall back to the normal app icon launch animation
        if (openingTaskId == -1) {
            return null;
        }

        // If the opening task id is not currently visible in overview, then fall back to normal app
        // icon launch animation
        TaskView taskView = recentsView.getTaskView(openingTaskId);
        if (taskView == null || !recentsView.isTaskViewVisible(taskView)) {
            return null;
        }

        // Found a visible recents task that matches the opening app, lets launch the app from there
        Animator launcherAnim;
        AnimatorListenerAdapter windowAnimEndListener;
        if (launcherClosing) {
            launcherAnim = getRecentsLauncherAnimator(recentsView, taskView);
            windowAnimEndListener = new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    // Make sure recents gets fixed up by resetting task alphas and scales, etc.
                    mLauncher.getStateManager().reapplyState();
                }
            };
        } else {
            AnimatorPlaybackController controller =
                    mLauncher.getStateManager()
                            .createAnimationToNewWorkspace(NORMAL, RECENTS_LAUNCH_DURATION);
            controller.dispatchOnStart();
            launcherAnim = controller.getAnimationPlayer().setDuration(RECENTS_LAUNCH_DURATION);
            windowAnimEndListener = new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mLauncher.getStateManager().goToState(NORMAL, false);
                }
            };
        }

        Animator windowAnim = getRecentsWindowAnimator(taskView, skipLauncherChanges, targets);
        windowAnim.addListener(windowAnimEndListener);
        return new LauncherTransitionAnimator(launcherAnim, windowAnim, skipLauncherChanges);
    }

    /**
     * Animate adjacent tasks off screen while scaling up, and translate hotseat off screen as well.
     *
     * If launching one of the adjacent tasks, parallax the center task and other adjacent task
     * to the right.
     */
    private Animator getRecentsLauncherAnimator(RecentsView recentsView, TaskView v) {
        AnimatorSet launcherAnimator = new AnimatorSet();

        int launchedTaskIndex = recentsView.indexOfChild(v);
        int centerTaskIndex = recentsView.getCurrentPage();
        boolean launchingCenterTask = launchedTaskIndex == centerTaskIndex;
        boolean isRtl = recentsView.isRtl();
        if (launchingCenterTask) {
            if (launchedTaskIndex - 1 >= 0) {
                TaskView adjacentPage1 = (TaskView) recentsView.getPageAt(launchedTaskIndex - 1);
                ObjectAnimator adjacentTask1ScaleAndTranslate =
                        LauncherAnimUtils.ofPropertyValuesHolder(adjacentPage1,
                                new PropertyListBuilder()
                                        .scale(adjacentPage1.getScaleX() * mRecentsScale)
                                        .translationY(mRecentsTransY)
                                        .translationX(isRtl ? mRecentsTransX : -mRecentsTransX)
                                        .build());
                launcherAnimator.play(adjacentTask1ScaleAndTranslate);
            }
            if (launchedTaskIndex + 1 < recentsView.getPageCount()) {
                TaskView adjacentTask2 = (TaskView) recentsView.getPageAt(launchedTaskIndex + 1);
                ObjectAnimator adjacentTask2ScaleAndTranslate =
                        LauncherAnimUtils.ofPropertyValuesHolder(adjacentTask2,
                                new PropertyListBuilder()
                                        .scale(adjacentTask2.getScaleX() * mRecentsScale)
                                        .translationY(mRecentsTransY)
                                        .translationX(isRtl ? -mRecentsTransX : mRecentsTransX)
                                        .build());
                launcherAnimator.play(adjacentTask2ScaleAndTranslate);
            }
        } else {
            // We are launching an adjacent task, so parallax the center and other adjacent task.
            TaskView centerTask = (TaskView) recentsView.getPageAt(centerTaskIndex);
            float translationX = Math.abs(v.getTranslationX());
            ObjectAnimator centerTaskParallaxToRight =
                    LauncherAnimUtils.ofPropertyValuesHolder(centerTask,
                            new PropertyListBuilder()
                                    .scale(v.getScaleX())
                                    .translationX(isRtl ? -translationX : translationX)
                                    .build());
            launcherAnimator.play(centerTaskParallaxToRight);
            int otherAdjacentTaskIndex = centerTaskIndex + (centerTaskIndex - launchedTaskIndex);
            if (otherAdjacentTaskIndex >= 0
                    && otherAdjacentTaskIndex < recentsView.getPageCount()) {
                TaskView otherAdjacentTask = (TaskView) recentsView.getPageAt(
                        otherAdjacentTaskIndex);
                ObjectAnimator otherAdjacentTaskParallaxToRight =
                        LauncherAnimUtils.ofPropertyValuesHolder(otherAdjacentTask,
                                new PropertyListBuilder()
                                        .translationX(otherAdjacentTask.getTranslationX()
                                                + (isRtl ? -translationX : translationX))
                                        .build());
                launcherAnimator.play(otherAdjacentTaskParallaxToRight);
            }
        }

        Animator allAppsSlideOut = ObjectAnimator.ofFloat(mLauncher.getAllAppsController(),
                ALL_APPS_PROGRESS, ALL_APPS_PROGRESS_OFF_SCREEN);
        launcherAnimator.play(allAppsSlideOut);

        Workspace workspace = mLauncher.getWorkspace();
        float[] workspaceScaleAndTranslation = NORMAL
                .getWorkspaceScaleAndTranslation(mLauncher);
        Animator recenterWorkspace = LauncherAnimUtils.ofPropertyValuesHolder(
                workspace, new PropertyListBuilder()
                        .translationX(workspaceScaleAndTranslation[1])
                        .translationY(workspaceScaleAndTranslation[2])
                        .build());
        launcherAnimator.play(recenterWorkspace);
        CellLayout currentWorkspacePage = (CellLayout) workspace.getPageAt(
                workspace.getCurrentPage());

        launcherAnimator.setInterpolator(Interpolators.TOUCH_RESPONSE_INTERPOLATOR);
        launcherAnimator.setDuration(RECENTS_LAUNCH_DURATION);
        return launcherAnimator;
    }

    /**
     * @return Animator that controls the window of the opening targets for the recents launch
     * animation.
     */
    private ValueAnimator getRecentsWindowAnimator(TaskView v, MutableBoolean skipLauncherChanges,
            RemoteAnimationTargetCompat[] targets) {
        Rect taskViewBounds = new Rect();
        mDragLayer.getDescendantRectRelativeToSelf(v, taskViewBounds);

        // TODO: Use the actual target insets instead of the current thumbnail insets in case the
        // device state has changed
        RecentsAnimationInterpolator recentsInterpolator = new RecentsAnimationInterpolator(
                new Rect(0, 0, mDeviceProfile.widthPx, mDeviceProfile.heightPx),
                v.getThumbnail().getInsets(),
                taskViewBounds,
                new Rect(0, v.getThumbnail().getTop(), 0, 0),
                v.getScaleX(),
                v.getTranslationX());

        Rect crop = new Rect();
        Matrix matrix = new Matrix();

        ValueAnimator appAnimator = ValueAnimator.ofFloat(0, 1);
        appAnimator.setDuration(RECENTS_LAUNCH_DURATION);
        appAnimator.setInterpolator(Interpolators.TOUCH_RESPONSE_INTERPOLATOR);
        appAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            boolean isFirstFrame = true;

            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final Surface surface = getSurface(v);
                final long frameNumber = surface != null ? getNextFrameNumber(surface) : -1;
                if (frameNumber == -1) {
                    // Booo, not cool! Our surface got destroyed, so no reason to animate anything.
                    Log.w(TAG, "Failed to animate, surface got destroyed.");
                    return;
                }
                final float percent = animation.getAnimatedFraction();
                TaskWindowBounds tw = recentsInterpolator.interpolate(percent);

                if (!skipLauncherChanges.value) {
                    v.setScaleX(tw.taskScale);
                    v.setScaleY(tw.taskScale);
                    v.setTranslationX(tw.taskX);
                    v.setTranslationY(tw.taskY);
                    // Defer fading out the view until after the app window gets faded in
                    v.setAlpha(getValue(1f, 0f, 75, 75,
                            appAnimator.getDuration() * percent, Interpolators.LINEAR));
                }

                matrix.setScale(tw.winScale, tw.winScale);
                matrix.postTranslate(tw.winX, tw.winY);
                crop.set(tw.winCrop);

                // Fade in the app window.
                float alphaDelay = 0;
                float alphaDuration = 75;
                float alpha = getValue(0f, 1f, alphaDelay, alphaDuration,
                        appAnimator.getDuration() * percent, Interpolators.LINEAR);

                TransactionCompat t = new TransactionCompat();
                for (RemoteAnimationTargetCompat target : targets) {
                    if (target.mode == RemoteAnimationTargetCompat.MODE_OPENING) {
                        t.setAlpha(target.leash, alpha);

                        // TODO: This isn't correct at the beginning of the animation, but better
                        // than nothing.
                        matrix.postTranslate(target.position.x, target.position.y);
                        t.setMatrix(target.leash, matrix);
                        t.setWindowCrop(target.leash, crop);

                        if (!skipLauncherChanges.value) {
                            t.deferTransactionUntil(target.leash, surface, frameNumber);
                        }
                    }
                    if (isFirstFrame) {
                        t.show(target.leash);
                    }
                }
                t.apply();

                matrix.reset();
                isFirstFrame = false;
            }
        });
        return appAnimator;
    }

    /**
     * Composes the animations for a launch from an app icon.
     */
    private LauncherTransitionAnimator composeAppLaunchAnimator(View v,
            RemoteAnimationTargetCompat[] targets) {
        return new LauncherTransitionAnimator(getLauncherAnimators(v, targets),
                getWindowAnimators(v, targets));
    }

    /**
     * @return Animators that control the movements of the Launcher and icon of the opening target.
     */
    private AnimatorSet getLauncherAnimators(View v, RemoteAnimationTargetCompat[] targets) {
        AnimatorSet launcherAnimators = new AnimatorSet();
        launcherAnimators.play(getIconAnimator(v));
        if (launcherIsATargetWithMode(targets, MODE_CLOSING)) {
            launcherAnimators.play(getLauncherContentAnimator(false /* show */));
        }
        return launcherAnimators;
    }

    /**
     * Content is everything on screen except the background and the floating view (if any).
     *
     * @param show If true: Animate the content so that it moves upwards and fades in.
     *             Else: Animate the content so that it moves downwards and fades out.
     */
    private AnimatorSet getLauncherContentAnimator(boolean show) {
        AnimatorSet launcherAnimator = new AnimatorSet();

        float[] alphas = show
                ? new float[] {0, 1}
                : new float[] {1, 0};
        float[] trans = show
                ? new float[] {mContentTransY, 0,}
                : new float[] {0, mContentTransY};

        if (mLauncher.isInState(LauncherState.ALL_APPS) && !mDeviceProfile.isVerticalBarLayout()) {
            // All Apps in portrait mode is full screen, so we only animate AllAppsContainerView.
            View appsView = mLauncher.getAppsView();
            appsView.setAlpha(alphas[0]);
            appsView.setTranslationY(trans[0]);

            ObjectAnimator alpha = ObjectAnimator.ofFloat(appsView, View.ALPHA, alphas);
            alpha.setDuration(217);
            alpha.setInterpolator(Interpolators.LINEAR);
            ObjectAnimator transY = ObjectAnimator.ofFloat(appsView, View.TRANSLATION_Y, trans);
            transY.setInterpolator(Interpolators.AGGRESSIVE_EASE);
            transY.setDuration(350);

            launcherAnimator.play(alpha);
            launcherAnimator.play(transY);
        } else {
            mDragLayer.setAlpha(alphas[0]);
            mDragLayer.setTranslationY(trans[0]);

            ObjectAnimator dragLayerAlpha = ObjectAnimator.ofFloat(mDragLayer, View.ALPHA, alphas);
            dragLayerAlpha.setDuration(217);
            dragLayerAlpha.setInterpolator(Interpolators.LINEAR);
            ObjectAnimator dragLayerTransY = ObjectAnimator.ofFloat(mDragLayer, View.TRANSLATION_Y,
                    trans);
            dragLayerTransY.setInterpolator(Interpolators.AGGRESSIVE_EASE);
            dragLayerTransY.setDuration(350);

            launcherAnimator.play(dragLayerAlpha);
            launcherAnimator.play(dragLayerTransY);
        }
        return launcherAnimator;
    }

    /**
     * @return Animator that controls the icon used to launch the target.
     */
    private AnimatorSet getIconAnimator(View v) {
        final boolean isBubbleTextView = v instanceof BubbleTextView;
        mFloatingView = new View(mLauncher);
        if (isBubbleTextView && v.getTag() instanceof ItemInfoWithIcon ) {
            // Create a copy of the app icon
            mFloatingView.setBackground(
                    DrawableFactory.get(mLauncher).newIcon((ItemInfoWithIcon) v.getTag()));
        }

        // Position the floating view exactly on top of the original
        Rect rect = new Rect();
        final boolean isDeepShortcutTextView = v instanceof DeepShortcutTextView
                && v.getParent() != null && v.getParent() instanceof DeepShortcutView;
        if (isDeepShortcutTextView) {
            // Deep shortcut views have their icon drawn in a sibling view.
            DeepShortcutView view = (DeepShortcutView) v.getParent();
            mDragLayer.getDescendantRectRelativeToSelf(view.getIconView(), rect);
        } else {
            mDragLayer.getDescendantRectRelativeToSelf(v, rect);
        }
        final int viewLocationStart = mIsRtl
                ? mDeviceProfile.widthPx - rect.right
                : rect.left;
        final int viewLocationTop = rect.top;

        float startScale = 1f;
        if (isBubbleTextView && !isDeepShortcutTextView) {
            BubbleTextView btv = (BubbleTextView) v;
            btv.getIconBounds(rect);
            Drawable dr = btv.getIcon();
            if (dr instanceof FastBitmapDrawable) {
                startScale = ((FastBitmapDrawable) dr).getAnimatedScale();
            }
        } else {
            rect.set(0, 0, rect.width(), rect.height());
        }
        LayoutParams lp = new LayoutParams(rect.width(), rect.height());
        lp.ignoreInsets = true;
        lp.setMarginStart(viewLocationStart + rect.left);
        lp.topMargin = viewLocationTop + rect.top;
        mFloatingView.setLayoutParams(lp);

        // Swap the two views in place.
        ((ViewGroup) mDragLayer.getParent()).addView(mFloatingView);
        v.setVisibility(View.INVISIBLE);

        AnimatorSet appIconAnimatorSet = new AnimatorSet();
        // Animate the app icon to the center
        float centerX = mDeviceProfile.widthPx / 2;
        float centerY = mDeviceProfile.heightPx / 2;

        float xPosition = mIsRtl
                ? mDeviceProfile.widthPx - lp.getMarginStart() - rect.width()
                : lp.getMarginStart();
        float dX = centerX - xPosition - (lp.width / 2);
        float dY = centerY - lp.topMargin - (lp.height / 2);

        ObjectAnimator x = ObjectAnimator.ofFloat(mFloatingView, View.TRANSLATION_X, 0f, dX);
        ObjectAnimator y = ObjectAnimator.ofFloat(mFloatingView, View.TRANSLATION_Y, 0f, dY);

        // Adjust the duration to change the "curve" of the app icon to the center.
        boolean isBelowCenterY = lp.topMargin < centerY;
        x.setDuration(isBelowCenterY ? APP_LAUNCH_DURATION : APP_LAUNCH_CURVED_DURATION);
        y.setDuration(isBelowCenterY ? APP_LAUNCH_CURVED_DURATION : APP_LAUNCH_DURATION);
        x.setInterpolator(Interpolators.AGGRESSIVE_EASE);
        y.setInterpolator(Interpolators.AGGRESSIVE_EASE);
        appIconAnimatorSet.play(x);
        appIconAnimatorSet.play(y);

        // Scale the app icon to take up the entire screen. This simplifies the math when
        // animating the app window position / scale.
        float maxScaleX = mDeviceProfile.widthPx / (float) rect.width();
        float maxScaleY = mDeviceProfile.heightPx / (float) rect.height();
        float scale = Math.max(maxScaleX, maxScaleY);
        ObjectAnimator scaleAnim = ObjectAnimator
                .ofFloat(mFloatingView, SCALE_PROPERTY, startScale, scale);
        scaleAnim.setDuration(APP_LAUNCH_DURATION).setInterpolator(Interpolators.EXAGGERATED_EASE);
        appIconAnimatorSet.play(scaleAnim);

        // Fade out the app icon.
        ObjectAnimator alpha = ObjectAnimator.ofFloat(mFloatingView, View.ALPHA, 1f, 0f);
        alpha.setStartDelay(32);
        alpha.setDuration(50);
        alpha.setInterpolator(Interpolators.LINEAR);
        appIconAnimatorSet.play(alpha);

        return appIconAnimatorSet;
    }

    /**
     * @return Animator that controls the window of the opening targets.
     */
    private ValueAnimator getWindowAnimators(View v, RemoteAnimationTargetCompat[] targets) {
        Rect bounds = new Rect();
        boolean isDeepShortcutTextView = v instanceof DeepShortcutTextView
                && v.getParent() != null && v.getParent() instanceof DeepShortcutView;
        if (isDeepShortcutTextView) {
            // Deep shortcut views have their icon drawn in a sibling view.
            DeepShortcutView view = (DeepShortcutView) v.getParent();
            mDragLayer.getDescendantRectRelativeToSelf(view.getIconView(), bounds);
        } else if (v instanceof BubbleTextView) {
            ((BubbleTextView) v).getIconBounds(bounds);
        } else {
            mDragLayer.getDescendantRectRelativeToSelf(v, bounds);
        }
        int[] floatingViewBounds = new int[2];

        Rect crop = new Rect();
        Matrix matrix = new Matrix();

        ValueAnimator appAnimator = ValueAnimator.ofFloat(0, 1);
        appAnimator.setDuration(APP_LAUNCH_DURATION);
        appAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            boolean isFirstFrame = true;

            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final Surface surface = getSurface(mFloatingView);
                final long frameNumber = surface != null ? getNextFrameNumber(surface) : -1;
                if (frameNumber == -1) {
                    // Booo, not cool! Our surface got destroyed, so no reason to animate anything.
                    Log.w(TAG, "Failed to animate, surface got destroyed.");
                    return;
                }
                final float percent = animation.getAnimatedFraction();
                final float easePercent = Interpolators.AGGRESSIVE_EASE.getInterpolation(percent);

                // Calculate app icon size.
                float iconWidth = bounds.width() * mFloatingView.getScaleX();
                float iconHeight = bounds.height() * mFloatingView.getScaleY();

                // Scale the app window to match the icon size.
                float scaleX = iconWidth / mDeviceProfile.widthPx;
                float scaleY = iconHeight / mDeviceProfile.heightPx;
                float scale = Math.min(1f, Math.min(scaleX, scaleY));
                matrix.setScale(scale, scale);

                // Position the scaled window on top of the icon
                int deviceWidth = mDeviceProfile.widthPx;
                int deviceHeight = mDeviceProfile.heightPx;
                float scaledWindowWidth = deviceWidth * scale;
                float scaledWindowHeight = deviceHeight * scale;

                float offsetX = (scaledWindowWidth - iconWidth) / 2;
                float offsetY = (scaledWindowHeight - iconHeight) / 2;
                mFloatingView.getLocationInWindow(floatingViewBounds);
                float transX0 = floatingViewBounds[0] - offsetX;
                float transY0 = floatingViewBounds[1] - offsetY;
                matrix.postTranslate(transX0, transY0);

                // Fade in the app window.
                float alphaDelay = 0;
                float alphaDuration = 60;
                float alpha = getValue(0f, 1f, alphaDelay, alphaDuration,
                        appAnimator.getDuration() * percent, Interpolators.LINEAR);

                // Animate the window crop so that it starts off as a square, and then reveals
                // horizontally.
                float cropHeight = deviceHeight * easePercent + deviceWidth * (1 - easePercent);
                float initialTop = (deviceHeight - deviceWidth) / 2f;
                crop.left = 0;
                crop.top = (int) (initialTop * (1 - easePercent));
                crop.right = deviceWidth;
                crop.bottom = (int) (crop.top + cropHeight);

                TransactionCompat t = new TransactionCompat();
                for (RemoteAnimationTargetCompat target : targets) {
                    if (target.mode == MODE_OPENING) {
                        t.setAlpha(target.leash, alpha);

                        // TODO: This isn't correct at the beginning of the animation, but better
                        // than nothing.
                        matrix.postTranslate(target.position.x, target.position.y);
                        t.setMatrix(target.leash, matrix);
                        t.setWindowCrop(target.leash, crop);
                        t.deferTransactionUntil(target.leash, surface, getNextFrameNumber(surface));
                    }
                    if (isFirstFrame) {
                        t.show(target.leash);
                    }
                }
                t.apply();

                matrix.reset();
                isFirstFrame = false;
            }
        });
        return appAnimator;
    }

    /**
     * Registers remote animations used when closing apps to home screen.
     */
    private void registerRemoteAnimations() {
        if (hasControlRemoteAppTransitionPermission()) {
            try {
                RemoteAnimationDefinitionCompat definition = new RemoteAnimationDefinitionCompat();
                definition.addRemoteAnimation(WindowManagerWrapper.TRANSIT_WALLPAPER_OPEN,
                        new RemoteAnimationAdapterCompat(getWallpaperOpenRunner(),
                                CLOSING_TRANSITION_DURATION_MS, 0 /* statusBarTransitionDelay */));

//      TODO: App controlled transition for unlock to home TRANSIT_KEYGUARD_GOING_AWAY_ON_WALLPAPER

                new ActivityCompat(mLauncher).registerRemoteAnimations(definition);
            } catch (NoClassDefFoundError e) {
                // Gracefully fall back if the user's platform doesn't have the latest changes
            }
        }
    }

    private boolean launcherIsATargetWithMode(RemoteAnimationTargetCompat[] targets, int mode) {
        int launcherTaskId = mLauncher.getTaskId();
        for (RemoteAnimationTargetCompat target : targets) {
            if (target.mode == mode && target.taskId == launcherTaskId) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Runner that plays when user goes to Launcher
     *         ie. pressing home, swiping up from nav bar.
     */
    private RemoteAnimationRunnerCompat getWallpaperOpenRunner() {
        return new LauncherAnimationRunner(mLauncher) {
            @Override
            public void onAnimationStart(RemoteAnimationTargetCompat[] targets,
                                         Runnable finishedCallback) {
                Handler handler = mLauncher.getWindow().getDecorView().getHandler();
                postAtFrontOfQueueAsynchronously(handler, () -> {
                    if ((Utilities.getPrefs(mLauncher)
                            .getBoolean("pref_use_screenshot_for_swipe_up", false)
                            && mLauncher.getStateManager().getState().overviewUi)
                            || !launcherIsATargetWithMode(targets, MODE_OPENING)) {
                        // We use a separate transition for Overview mode. And we can skip the
                        // animation in cases where Launcher is not in the set of opening targets.
                        // This can happen when Launcher is already visible. ie. Closing a dialog.
                        setCurrentAnimator(null);
                        finishedCallback.run();
                        return;
                    }

                    LauncherTransitionAnimator animator = new LauncherTransitionAnimator(
                            getLauncherResumeAnimation(), getClosingWindowAnimators(targets));
                    setCurrentAnimator(animator);
                    mAnimator = animator.getAnimatorSet();
                    mAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            finishedCallback.run();
                        }
                    });
                    mAnimator.start();

                    // Because t=0 has the app icon in its original spot, we can skip the
                    // first frame and have the same movement one frame earlier.
                    mAnimator.setCurrentPlayTime(REFRESH_RATE_MS);
                });
            }
        };
    }

    /**
     * Animator that controls the transformations of the windows the targets that are closing.
     */
    private Animator getClosingWindowAnimators(RemoteAnimationTargetCompat[] targets) {
        Matrix matrix = new Matrix();
        float height = mLauncher.getDeviceProfile().heightPx;
        float width = mLauncher.getDeviceProfile().widthPx;
        float endX = (mLauncher.<RecentsView>getOverviewPanel().isRtl() ? -width : width) * 1.16f;

        ValueAnimator closingAnimator = ValueAnimator.ofFloat(0, 1);
        closingAnimator.setDuration(CLOSING_TRANSITION_DURATION_MS);

        closingAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            boolean isFirstFrame = true;

            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                final float percent = animation.getAnimatedFraction();
                float currentPlayTime = percent * closingAnimator.getDuration();

                float scale = getValue(1f, 0.8f, 0, 267, currentPlayTime,
                        Interpolators.AGGRESSIVE_EASE);

                float dX = getValue(0, endX, 0, 350, currentPlayTime,
                        Interpolators.AGGRESSIVE_EASE_IN_OUT);

                TransactionCompat t = new TransactionCompat();
                for (RemoteAnimationTargetCompat app : targets) {
                    if (app.mode == RemoteAnimationTargetCompat.MODE_CLOSING) {
                        t.setAlpha(app.leash, getValue(1f, 0f, 0, 350, currentPlayTime,
                                Interpolators.APP_CLOSE_ALPHA));
                        matrix.setScale(scale, scale,
                                app.sourceContainerBounds.centerX(),
                                app.sourceContainerBounds.centerY());
                        matrix.postTranslate(dX, 0);
                        matrix.postTranslate(app.position.x, app.position.y);
                        t.setMatrix(app.leash, matrix);
                    }
                    if (isFirstFrame) {
                        int layer = app.mode == RemoteAnimationTargetCompat.MODE_CLOSING
                                ? Integer.MAX_VALUE
                                : app.prefixOrderIndex;
                        t.setLayer(app.leash, layer);
                        t.show(app.leash);
                    }
                }
                t.apply();

                matrix.reset();
                isFirstFrame = false;
            }
        });
        return closingAnimator;
    }

    /**
     * @return Animator that modifies Launcher as a result from {@link #getWallpaperOpenRunner}.
     */
    private AnimatorSet getLauncherResumeAnimation() {
        if (mLauncher.isInState(LauncherState.ALL_APPS)
                || mLauncher.getDeviceProfile().isVerticalBarLayout()) {
            AnimatorSet contentAnimator = getLauncherContentAnimator(true /* show */);
            contentAnimator.setStartDelay(LAUNCHER_RESUME_START_DELAY);
            return contentAnimator;
        } else {
            AnimatorSet workspaceAnimator = new AnimatorSet();
            mLauncher.getWorkspace().setTranslationY(mWorkspaceTransY);
            mLauncher.getWorkspace().setAlpha(0f);
            workspaceAnimator.play(ObjectAnimator.ofFloat(mLauncher.getWorkspace(),
                    View.TRANSLATION_Y, mWorkspaceTransY, 0));
            workspaceAnimator.play(ObjectAnimator.ofFloat(mLauncher.getWorkspace(), View.ALPHA,
                    0, 1f));
            workspaceAnimator.setStartDelay(LAUNCHER_RESUME_START_DELAY);
            workspaceAnimator.setDuration(333);
            workspaceAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);

            // Animate the shelf in two parts: slide in, and overeshoot.
            AllAppsTransitionController allAppsController = mLauncher.getAllAppsController();
            // The shelf will start offscreen
            final float startY = ALL_APPS_PROGRESS_OFF_SCREEN;
            // And will end slightly pulled up, so that there is something to overshoot back to 1f.
            final float slideEnd = ALL_APPS_PROGRESS_OVERSHOOT;

            allAppsController.setProgress(startY);

            Animator allAppsSlideIn =
                    ObjectAnimator.ofFloat(allAppsController, ALL_APPS_PROGRESS, startY, slideEnd);
            allAppsSlideIn.setStartDelay(LAUNCHER_RESUME_START_DELAY);
            allAppsSlideIn.setDuration(317);
            allAppsSlideIn.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);

            Animator allAppsOvershoot =
                    ObjectAnimator.ofFloat(allAppsController, ALL_APPS_PROGRESS, slideEnd, 1f);
            allAppsOvershoot.setDuration(153);
            allAppsOvershoot.setInterpolator(Interpolators.OVERSHOOT_0);

            AnimatorSet resumeLauncherAnimation = new AnimatorSet();
            resumeLauncherAnimation.play(workspaceAnimator);
            resumeLauncherAnimation.playSequentially(allAppsSlideIn, allAppsOvershoot);
            return resumeLauncherAnimation;
        }
    }

    private boolean hasControlRemoteAppTransitionPermission() {
        return mLauncher.checkSelfPermission(CONTROL_REMOTE_APP_TRANSITION_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Helper method that allows us to get interpolated values for embedded
     * animations with a delay and/or different duration.
     */
    private static float getValue(float start, float end, float delay, float duration,
            float currentPlayTime, Interpolator i) {
        float time = Math.max(0, currentPlayTime - delay);
        float newPercent = Math.min(1f, time / duration);
        newPercent = i.getInterpolation(newPercent);
        return end * newPercent + start * (1 - newPercent);
    }
}
