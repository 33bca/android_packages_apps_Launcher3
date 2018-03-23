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

package com.android.launcher3.uioverrides;

import static com.android.launcher3.LauncherState.NORMAL;

import android.view.View;
import android.view.View.AccessibilityDelegate;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherStateManager.StateHandler;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.dragndrop.DragLayer;
import com.android.launcher3.util.TouchController;
import com.android.quickstep.OverviewInteractionState;
import com.android.quickstep.RecentsModel;
import com.android.quickstep.views.RecentsView;

public class UiFactory {

    public static TouchController[] createTouchControllers(Launcher launcher) {
        if (FeatureFlags.ENABLE_TWO_SWIPE_TARGETS) {
            return new TouchController[] {
                    new EdgeSwipeController(launcher),
                    new TwoStepSwipeController(launcher),
                    new OverviewSwipeController(launcher)};
        } else {
            return new TouchController[] {
                    new TwoStepSwipeController(launcher),
                    new OverviewSwipeController(launcher)};
        }
    }

    public static AccessibilityDelegate newPageIndicatorAccessibilityDelegate() {
        return null;
    }

    public static StateHandler[] getStateHandler(Launcher launcher) {
        return new StateHandler[] {
                launcher.getAllAppsController(), launcher.getWorkspace(),
                new RecentsViewStateController(launcher)};
    }

    public static void onLauncherStateOrFocusChanged(Launcher launcher) {
        boolean shouldBackButtonBeVisible = launcher == null
                || !launcher.isInState(NORMAL)
                || !launcher.hasWindowFocus();
        if (!shouldBackButtonBeVisible) {
            // Show the back button if there is a floating view visible.
            DragLayer dragLayer = launcher.getDragLayer();
            for (int i = dragLayer.getChildCount() - 1; i >= 0; i--) {
                View child = dragLayer.getChildAt(i);
                if (child instanceof AbstractFloatingView) {
                    shouldBackButtonBeVisible = true;
                    break;
                }
            }
        }
        OverviewInteractionState.setBackButtonVisible(launcher, shouldBackButtonBeVisible);
    }

    public static void resetOverview(Launcher launcher) {
        RecentsView recents = launcher.getOverviewPanel();
        recents.reset();
    }

    public static void onStart(Launcher launcher) {
        RecentsModel model = RecentsModel.getInstance(launcher);
        if (model != null) {
            model.onStart();
        }
    }

    public static void onTrimMemory(Launcher launcher, int level) {
        RecentsModel model = RecentsModel.getInstance(launcher);
        if (model != null) {
            model.onTrimMemory(level);
        }
    }
}
