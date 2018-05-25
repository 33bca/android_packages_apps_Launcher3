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

package com.android.quickstep.views;

import static com.android.launcher3.userevent.nano.LauncherLogProto.Action.Touch.TAP;
import static com.android.launcher3.userevent.nano.LauncherLogProto.ControlType.CLEAR_ALL_BUTTON;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.view.Gravity;
import android.view.MotionEvent;

import com.android.launcher3.InsettableFrameLayout;
import com.android.launcher3.R;

public class RecentsViewContainer extends InsettableFrameLayout {
    public static final FloatProperty<RecentsViewContainer> CONTENT_ALPHA =
            new FloatProperty<RecentsViewContainer>("contentAlpha") {
                @Override
                public void setValue(RecentsViewContainer view, float v) {
                    view.setContentAlpha(v);
                }

                @Override
                public Float get(RecentsViewContainer view) {
                    return view.mRecentsView.getContentAlpha();
                }
            };

    private final Rect mTempRect = new Rect();

    private RecentsView mRecentsView;
    private ClearAllButton mClearAllButton;

    public RecentsViewContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mClearAllButton = findViewById(R.id.clear_all_button);
        mClearAllButton.setOnClickListener((v) -> {
            mRecentsView.mActivity.getUserEventDispatcher()
                    .logActionOnControl(TAP, CLEAR_ALL_BUTTON);
            mRecentsView.dismissAllTasks();
        });

        mRecentsView = findViewById(R.id.overview_panel);
        final InsettableFrameLayout.LayoutParams params =
                (InsettableFrameLayout.LayoutParams) mClearAllButton.getLayoutParams();
        params.gravity = Gravity.TOP | (RecentsView.FLIP_RECENTS ? Gravity.START : Gravity.END);
        mClearAllButton.setLayoutParams(params);
        mClearAllButton.forceHasOverlappingRendering(false);

        mRecentsView.setClearAllButton(mClearAllButton);
        mClearAllButton.setRecentsView(mRecentsView);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        mRecentsView.getTaskSize(mTempRect);

        mClearAllButton.setTranslationX(
                (mRecentsView.isRtl() ? 1 : -1) *
                        (getResources().getDimension(R.dimen.clear_all_container_width)
                                - mClearAllButton.getMeasuredWidth()) / 2);
        mClearAllButton.setTranslationY(
                mTempRect.top + (mTempRect.height() - mClearAllButton.getMeasuredHeight()) / 2
                        - mClearAllButton.getTop());
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        super.onTouchEvent(ev);
        // Do not let touch escape to siblings below this view. This prevents scrolling of the
        // workspace while in Recents.
        return true;
    }

    public void setContentAlpha(float alpha) {
        if (alpha == mRecentsView.getContentAlpha()) {
            return;
        }
        mRecentsView.setContentAlpha(alpha);
        setVisibility(alpha > 0 ? VISIBLE : GONE);
    }
}