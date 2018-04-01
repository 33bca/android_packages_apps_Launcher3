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

package com.android.quickstep.views;

import static com.android.launcher3.anim.Interpolators.ACCEL;
import static com.android.launcher3.anim.Interpolators.ACCEL_2;
import static com.android.launcher3.anim.Interpolators.FAST_OUT_SLOW_IN;
import static com.android.launcher3.anim.Interpolators.LINEAR;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.Rect;
import android.os.Build;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.SparseBooleanArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;

import com.android.launcher3.BaseActivity;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.PagedView;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.config.FeatureFlags;
import com.android.quickstep.PendingAnimation;
import com.android.quickstep.QuickScrubController;
import com.android.quickstep.RecentsModel;
import com.android.systemui.shared.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.shared.recents.model.RecentsTaskLoader;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.TaskStack;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.WindowManagerWrapper;

import java.util.ArrayList;

/**
 * A list of recent tasks.
 */
@TargetApi(Build.VERSION_CODES.P)
public abstract class RecentsView<T extends BaseActivity>
        extends PagedView implements OnSharedPreferenceChangeListener {

    public static final FloatProperty<RecentsView> CONTENT_ALPHA =
            new FloatProperty<RecentsView>("contentAlpha") {


        @Override
        public void setValue(RecentsView recentsView, float v) {
            recentsView.setContentAlpha(v);
        }

        @Override
        public Float get(RecentsView recentsView) {
            return recentsView.mContentAlpha;
        }
    };

    private static final String PREF_FLIP_RECENTS = "pref_flip_recents";
    private static final int DISMISS_TASK_DURATION = 300;

    private static final Rect sTempStableInsets = new Rect();

    protected final T mActivity;
    private final QuickScrubController mQuickScrubController;
    private final float mFastFlingVelocity;
    private final RecentsModel mModel;

    private final ScrollState mScrollState = new ScrollState();
    // Keeps track of the previously known visible tasks for purposes of loading/unloading task data
    private final SparseBooleanArray mHasVisibleTaskData = new SparseBooleanArray();

    /**
     * TODO: Call reloadIdNeeded in onTaskStackChanged.
     */
    private final TaskStackChangeListener mTaskStackListener = new TaskStackChangeListener() {
        @Override
        public void onTaskSnapshotChanged(int taskId, ThumbnailData snapshot) {
            for (int i = 0; i < getChildCount(); i++) {
                final TaskView taskView = (TaskView) getChildAt(i);
                if (taskView.getTask().key.id == taskId) {
                    taskView.getThumbnail().setThumbnail(taskView.getTask(), snapshot);
                    return;
                }
            }
        }
    };

    private int mLoadPlanId = -1;

    // Only valid until the launcher state changes to NORMAL
    private int mRunningTaskId = -1;

    private boolean mFirstTaskIconScaledDown = false;

    private boolean mOverviewStateEnabled;
    private boolean mTaskStackListenerRegistered;
    private Runnable mNextPageSwitchRunnable;

    private PendingAnimation mPendingAnimation;

    private float mContentAlpha = 1;

    // Keeps track of task views whose visual state should not be reset
    private ArraySet<TaskView> mIgnoreResetTaskViews = new ArraySet<>();

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setPageSpacing(getResources().getDimensionPixelSize(R.dimen.recents_page_spacing));
        enableFreeScroll(true);
        setClipToOutline(true);

        mFastFlingVelocity = getResources()
                .getDimensionPixelSize(R.dimen.recents_fast_fling_velocity);
        mActivity = (T) BaseActivity.fromContext(context);
        mQuickScrubController = new QuickScrubController(mActivity, this);
        mModel = RecentsModel.getInstance(context);

        onSharedPreferenceChanged(Utilities.getPrefs(context), PREF_FLIP_RECENTS);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        if (s.equals(PREF_FLIP_RECENTS)) {
            mIsRtl = Utilities.isRtl(getResources());
            if (sharedPreferences.getBoolean(PREF_FLIP_RECENTS, false)) {
                mIsRtl = !mIsRtl;
            }
            setLayoutDirection(mIsRtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        }
    }

    public boolean isRtl() {
        return mIsRtl;
    }

    public TaskView updateThumbnail(int taskId, ThumbnailData thumbnailData) {
        for (int i = 0; i < getChildCount(); i++) {
            final TaskView taskView = (TaskView) getChildAt(i);
            if (taskView.getTask().key.id == taskId) {
                taskView.onTaskDataLoaded(taskView.getTask(), thumbnailData);
                taskView.setAlpha(1);
                return taskView;
            }
        }
        return null;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateTaskStackListenerState();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateTaskStackListenerState();
        Utilities.getPrefs(getContext()).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        updateTaskStackListenerState();
        Utilities.getPrefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);

        // Clear the task data for the removed child if it was visible
        Task task = ((TaskView) child).getTask();
        if (mHasVisibleTaskData.get(task.key.id)) {
            mHasVisibleTaskData.delete(task.key.id);
            RecentsTaskLoader loader = mModel.getRecentsTaskLoader();
            loader.unloadTaskData(task);
            loader.getHighResThumbnailLoader().onTaskInvisible(task);
        }
    }

    public boolean isTaskViewVisible(TaskView tv) {
        // For now, just check if it's the active task or an adjacent task
        return Math.abs(indexOfChild(tv) - getNextPage()) <= 1;
    }

    public TaskView getTaskView(int taskId) {
        for (int i = 0; i < getChildCount(); i++) {
            TaskView tv = (TaskView) getChildAt(i);
            if (tv.getTask().key.id == taskId) {
                return tv;
            }
        }
        return null;
    }

    public void setOverviewStateEnabled(boolean enabled) {
        mOverviewStateEnabled = enabled;
        updateTaskStackListenerState();
    }

    public void setNextPageSwitchRunnable(Runnable r) {
        mNextPageSwitchRunnable = r;
    }

    @Override
    protected void onPageEndTransition() {
        super.onPageEndTransition();
        if (mNextPageSwitchRunnable != null) {
            mNextPageSwitchRunnable.run();
            mNextPageSwitchRunnable = null;
        }
    }

    private void applyLoadPlan(RecentsTaskLoadPlan loadPlan) {
        if (mPendingAnimation != null) {
            mPendingAnimation.addEndListener((b) -> applyLoadPlan(loadPlan));
            return;
        }
        TaskStack stack = loadPlan != null ? loadPlan.getTaskStack() : null;
        if (stack == null) {
            removeAllViews();
            return;
        }

        int oldChildCount = getChildCount();

        // Ensure there are as many views as there are tasks in the stack (adding and trimming as
        // necessary)
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        final ArrayList<Task> tasks = new ArrayList<>(stack.getTasks());

        final int requiredChildCount = tasks.size();
        for (int i = getChildCount(); i < requiredChildCount; i++) {
            final TaskView taskView = (TaskView) inflater.inflate(R.layout.task, this, false);
            addView(taskView);
        }
        while (getChildCount() > requiredChildCount) {
            final TaskView taskView = (TaskView) getChildAt(getChildCount() - 1);
            removeView(taskView);
        }

        // Unload existing visible task data
        unloadVisibleTaskData();

        // Rebind and reset all task views
        for (int i = requiredChildCount - 1; i >= 0; i--) {
            final int pageIndex = requiredChildCount - i - 1;
            final Task task = tasks.get(i);
            final TaskView taskView = (TaskView) getChildAt(pageIndex);
            taskView.bind(task);
        }
        resetTaskVisuals();
        applyIconScale(false /* animate */);

        if (oldChildCount != getChildCount()) {
            mQuickScrubController.snapToNextTaskIfAvailable();
        }
    }

    public void resetTaskVisuals() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            TaskView taskView = (TaskView) getChildAt(i);
            if (!mIgnoreResetTaskViews.contains(taskView)) {
                taskView.resetVisualProperties();
            }
        }

        updateCurveProperties();
        // Update the set of visible task's data
        loadVisibleTaskData();
    }

    private void updateTaskStackListenerState() {
        boolean registerStackListener = mOverviewStateEnabled && isAttachedToWindow()
                && getWindowVisibility() == VISIBLE;
        if (registerStackListener != mTaskStackListenerRegistered) {
            if (registerStackListener) {
                ActivityManagerWrapper.getInstance()
                        .registerTaskStackListener(mTaskStackListener);
                reloadIfNeeded();
            } else {
                ActivityManagerWrapper.getInstance()
                        .unregisterTaskStackListener(mTaskStackListener);
            }
            mTaskStackListenerRegistered = registerStackListener;
        }
    }

    protected static Rect getPadding(DeviceProfile profile, Context context) {
        WindowManagerWrapper.getInstance().getStableInsets(sTempStableInsets);
        Rect padding = new Rect(profile.workspacePadding);

        float taskWidth = profile.widthPx - sTempStableInsets.left - sTempStableInsets.right;
        float taskHeight = profile.heightPx - sTempStableInsets.top - sTempStableInsets.bottom;

        float overviewHeight, overviewWidth;
        if (profile.isVerticalBarLayout()) {
            float maxPadding = Math.max(padding.left, padding.right);

            // Use the same padding on both sides for symmetry.
            float availableWidth = taskWidth - 2 * maxPadding;
            float availableHeight = profile.availableHeightPx - padding.top - padding.bottom
                    - sTempStableInsets.top;
            float scaledRatio = Math.min(availableWidth / taskWidth, availableHeight / taskHeight);
            overviewHeight = taskHeight * scaledRatio;
            overviewWidth = taskWidth * scaledRatio;

        } else {
            overviewHeight = profile.availableHeightPx - padding.top - padding.bottom
                    - sTempStableInsets.top;
            overviewWidth = taskWidth * overviewHeight / taskHeight;
        }

        padding.bottom = profile.availableHeightPx - padding.top - sTempStableInsets.top
                - Math.round(overviewHeight);
        padding.left = padding.right = (int) ((profile.availableWidthPx - overviewWidth) / 2);
        return padding;
    }

    public static void getPageRect(DeviceProfile grid, Context context, Rect outRect) {
        Rect targetPadding = getPadding(grid, context);
        Rect insets = grid.getInsets();
        outRect.set(
                targetPadding.left + insets.left,
                targetPadding.top + insets.top,
                grid.widthPx - targetPadding.right - insets.right,
                grid.heightPx - targetPadding.bottom - insets.bottom);
        outRect.top += context.getResources()
                .getDimensionPixelSize(R.dimen.task_thumbnail_top_margin);
    }

    @Override
    protected boolean computeScrollHelper() {
        boolean scrolling = super.computeScrollHelper();
        boolean isFlingingFast = false;
        updateCurveProperties();
        if (scrolling || (mTouchState == TOUCH_STATE_SCROLLING)) {
            if (scrolling) {
                // Check if we are flinging quickly to disable high res thumbnail loading
                isFlingingFast = mScroller.getCurrVelocity() > mFastFlingVelocity;
            }

            // After scrolling, update the visible task's data
            loadVisibleTaskData();
        }

        // Update the high res thumbnail loader
        RecentsTaskLoader loader = mModel.getRecentsTaskLoader();
        loader.getHighResThumbnailLoader().setFlingingFast(isFlingingFast);
        return scrolling;
    }

    /**
     * Scales and adjusts translation of adjacent pages as if on a curved carousel.
     */
    public void updateCurveProperties() {
        if (getPageCount() == 0 || getPageAt(0).getMeasuredWidth() == 0) {
            return;
        }
        final int halfPageWidth = getNormalChildWidth() / 2;
        final int screenCenter = mInsets.left + getPaddingLeft() + getScrollX() + halfPageWidth;
        final int halfScreenWidth = getMeasuredWidth() / 2;
        final int pageSpacing = mPageSpacing;

        final int pageCount = getPageCount();
        for (int i = 0; i < pageCount; i++) {
            View page = getPageAt(i);
            float pageCenter = page.getLeft() + page.getTranslationX() + halfPageWidth;
            float distanceFromScreenCenter = screenCenter - pageCenter;
            float distanceToReachEdge = halfScreenWidth + halfPageWidth + pageSpacing;
            mScrollState.linearInterpolation = Math.min(1,
                    Math.abs(distanceFromScreenCenter) / distanceToReachEdge);
            ((PageCallbacks) page).onPageScroll(mScrollState);
        }
    }

    /**
     * Iterates through all thet asks, and loads the associated task data for newly visible tasks,
     * and unloads the associated task data for tasks that are no longer visible.
     */
    public void loadVisibleTaskData() {
        RecentsTaskLoader loader = mModel.getRecentsTaskLoader();
        int centerPageIndex = getPageNearestToCenterOfScreen();
        int lower = Math.max(0, centerPageIndex - 2);
        int upper = Math.min(centerPageIndex + 2, getChildCount() - 1);
        int numChildren = getChildCount();

        // Update the task data for the in/visible children
        for (int i = 0; i < numChildren; i++) {
            TaskView taskView = (TaskView) getChildAt(i);
            Task task = taskView.getTask();
            boolean visible = lower <= i && i <= upper;
            if (visible) {
                if (!mHasVisibleTaskData.get(task.key.id)) {
                    loader.loadTaskData(task);
                    loader.getHighResThumbnailLoader().onTaskVisible(task);
                }
                mHasVisibleTaskData.put(task.key.id, visible);
            } else {
                if (mHasVisibleTaskData.get(task.key.id)) {
                    loader.unloadTaskData(task);
                    loader.getHighResThumbnailLoader().onTaskInvisible(task);
                }
                mHasVisibleTaskData.delete(task.key.id);
            }
        }
    }

    /**
     * Unloads any associated data from the currently visible tasks
     */
    private void unloadVisibleTaskData() {
        RecentsTaskLoader loader = mModel.getRecentsTaskLoader();
        for (int i = 0; i < mHasVisibleTaskData.size(); i++) {
            if (mHasVisibleTaskData.valueAt(i)) {
                TaskView taskView = getTaskView(mHasVisibleTaskData.keyAt(i));
                Task task = taskView.getTask();
                loader.unloadTaskData(task);
                loader.getHighResThumbnailLoader().onTaskInvisible(task);
            }
        }
        mHasVisibleTaskData.clear();
    }


    protected abstract void onAllTasksRemoved();

    public void reset() {
        unloadVisibleTaskData();
        mRunningTaskId = -1;
        setCurrentPage(0);
    }

    /**
     * Reloads the view if anything in recents changed.
     */
    public void reloadIfNeeded() {
        if (!mModel.isLoadPlanValid(mLoadPlanId)) {
            mLoadPlanId = mModel.loadTasks(mRunningTaskId, this::applyLoadPlan);
        }
    }

    /**
     * Ensures that the first task in the view represents {@param task} and reloads the view
     * if needed. This allows the swipe-up gesture to assume that the first tile always
     * corresponds to the correct task.
     * All subsequent calls to reload will keep the task as the first item until {@link #reset()}
     * is called.
     * Also scrolls the view to this task
     */
    public void showTask(int runningTaskId) {
        boolean needsReload = false;
        if (getChildCount() == 0) {
            needsReload = true;
            // Add an empty view for now
            final TaskView taskView = (TaskView) LayoutInflater.from(getContext())
                    .inflate(R.layout.task, this, false);
            addView(taskView, 0);
        }
        mRunningTaskId = runningTaskId;
        setCurrentPage(0);
        if (!needsReload) {
            needsReload = !mModel.isLoadPlanValid(mLoadPlanId);
        }
        if (needsReload) {
            mLoadPlanId = mModel.loadTasks(runningTaskId, this::applyLoadPlan);
        } else {
            loadVisibleTaskData();
        }
        getPageAt(mCurrentPage).setAlpha(0);
    }

    public QuickScrubController getQuickScrubController() {
        return mQuickScrubController;
    }

    public void setFirstTaskIconScaledDown(boolean isScaledDown, boolean animate) {
        if (mFirstTaskIconScaledDown == isScaledDown) {
            return;
        }
        mFirstTaskIconScaledDown = isScaledDown;
        applyIconScale(animate);
    }

    private void applyIconScale(boolean animate) {
        float scale = mFirstTaskIconScaledDown ? 0 : 1;
        TaskView firstTask = (TaskView) getChildAt(0);
        if (firstTask != null) {
            if (animate) {
                firstTask.animateIconToScale(scale);
            } else {
                firstTask.setIconScale(scale);
            }
        }
    }

    public interface PageCallbacks {

        /**
         * Updates the page UI based on scroll params.
         */
        default void onPageScroll(ScrollState scrollState) {};
    }

    public static class ScrollState {

        /**
         * The progress from 0 to 1, where 0 is the center
         * of the screen and 1 is the edge of the screen.
         */
        public float linearInterpolation;
    }

    public void addIgnoreResetTask(TaskView taskView) {
        mIgnoreResetTaskViews.add(taskView);
    }

    public void removeIgnoreResetTask(TaskView taskView) {
        mIgnoreResetTaskViews.remove(taskView);
    }

    public PendingAnimation createTaskDismissAnimation(TaskView taskView, boolean animateTaskView,
            boolean removeTask, long duration) {
        if (FeatureFlags.IS_DOGFOOD_BUILD && mPendingAnimation != null) {
            throw new IllegalStateException("Another pending animation is still running");
        }
        AnimatorSet anim = new AnimatorSet();
        PendingAnimation pendingAnimation = new PendingAnimation(anim);

        int count = getChildCount();
        if (count == 0) {
            return pendingAnimation;
        }

        int[] oldScroll = new int[count];
        getPageScrolls(oldScroll, false, SIMPLE_SCROLL_LOGIC);

        int[] newScroll = new int[count];
        getPageScrolls(newScroll, false, (v) -> v.getVisibility() != GONE && v != taskView);

        int maxScrollDiff = 0;
        int lastPage = mIsRtl ? 0 : count - 1;
        if (getChildAt(lastPage) == taskView) {
            if (count > 1) {
                int secondLastPage = mIsRtl ? 1 : count - 2;
                maxScrollDiff = oldScroll[lastPage] - newScroll[secondLastPage];
            }
        }

        boolean needsCurveUpdates = false;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child == taskView) {
                if (animateTaskView) {
                    addAnim(ObjectAnimator.ofFloat(taskView, ALPHA, 0), duration, ACCEL_2, anim);
                    addAnim(ObjectAnimator.ofFloat(taskView, TRANSLATION_Y, -taskView.getHeight()),
                            duration, LINEAR, anim);
                }
            } else {
                int scrollDiff = newScroll[i] - oldScroll[i] + maxScrollDiff;
                if (scrollDiff != 0) {
                    addAnim(ObjectAnimator.ofFloat(child, TRANSLATION_X, scrollDiff),
                            duration, ACCEL, anim);
                    needsCurveUpdates = true;
                }
            }
        }

        if (needsCurveUpdates) {
            ValueAnimator va = ValueAnimator.ofFloat(0, 1);
            va.addUpdateListener((a) -> updateCurveProperties());
            anim.play(va);
        }

        // Add a tiny bit of translation Z, so that it draws on top of other views
        if (animateTaskView) {
            taskView.setTranslationZ(0.1f);
        }

        mPendingAnimation = pendingAnimation;
        mPendingAnimation.addEndListener((isSuccess) -> {
           if (isSuccess) {
               if (removeTask) {
                   ActivityManagerWrapper.getInstance().removeTask(taskView.getTask().key.id);
               }
               removeView(taskView);
               if (getChildCount() == 0) {
                   onAllTasksRemoved();
               }
           }
           resetTaskVisuals();
           mPendingAnimation = null;
        });
        return pendingAnimation;
    }

    private static void addAnim(ObjectAnimator anim, long duration,
            TimeInterpolator interpolator, AnimatorSet set) {
        anim.setDuration(duration).setInterpolator(interpolator);
        set.play(anim);
    }

    private void snapToPageRelative(int delta) {
        snapToPage((getNextPage() + getPageCount() + delta) % getPageCount());
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible && !isFocused()) {
            // Having focus, even in touch mode, keeps us from losing [Alt+]Tab by preventing
            // switching to keyboard mode.
            requestFocus();
        }
    }

    public void dismissTask(TaskView taskView, boolean animateTaskView, boolean removeTask) {
        PendingAnimation pendingAnim = createTaskDismissAnimation(taskView, animateTaskView,
                removeTask, DISMISS_TASK_DURATION);
        AnimatorPlaybackController controller = AnimatorPlaybackController.wrap(
                pendingAnim.anim, DISMISS_TASK_DURATION);
        controller.dispatchOnStart();
        controller.setEndAction(() -> pendingAnim.finish(true));
        controller.getAnimationPlayer().setInterpolator(FAST_OUT_SLOW_IN);
        controller.start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_TAB:
                    snapToPageRelative(event.isShiftPressed() ? -1 : 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    snapToPageRelative(mIsRtl ? -1 : 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    snapToPageRelative(mIsRtl ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DEL:
                case KeyEvent.KEYCODE_FORWARD_DEL:
                    dismissTask((TaskView) getChildAt(getNextPage()), true /*animateTaskView*/,
                            true /*removeTask*/);
                    return true;
                case KeyEvent.KEYCODE_NUMPAD_DOT:
                    if (event.isAltPressed()) {
                        // Numpad DEL pressed while holding Alt.
                        dismissTask((TaskView) getChildAt(getNextPage()), true /*animateTaskView*/,
                                true /*removeTask*/);
                        return true;
                    }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    public void snapToTaskAfterNext() {
        snapToPageRelative(1);
    }

    public void launchNextTask() {
        final TaskView nextTask = (TaskView) getChildAt(getNextPage());
        nextTask.launchTask(true);
    }

    public void setContentAlpha(float alpha) {
        if (mContentAlpha == alpha) {
            return;
        }
        mContentAlpha = alpha;
        for (int i = getChildCount() - 1; i >= 0; i--) {
            getChildAt(i).setAlpha(alpha);
        }
        setVisibility(alpha > 0 ? VISIBLE : GONE);
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        child.setAlpha(mContentAlpha);
    }
}
