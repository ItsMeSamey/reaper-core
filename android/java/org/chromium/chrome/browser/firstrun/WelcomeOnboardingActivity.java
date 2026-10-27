/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */
package org.chromium.chrome.browser.firstrun;

import static org.chromium.build.NullUtil.assumeNonNull;
import static org.chromium.chrome.browser.set_default_browser.BraveSetDefaultBrowserUtils.isBraveSetAsDefaultBrowser;
import static org.chromium.chrome.browser.set_default_browser.BraveSetDefaultBrowserUtils.setDefaultBrowser;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.app.Activity;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Guideline;
import androidx.viewpager2.widget.ViewPager2;

import org.chromium.base.ActivityState;
import org.chromium.base.ApplicationStatus;
import org.chromium.base.ApplicationStatus.ActivityStateListener;
import org.chromium.base.BravePreferenceKeys;
import org.chromium.brave.browser.customize_menu.CustomizeBraveMenu;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.onboarding.OnboardingPrefManager;
import org.chromium.chrome.browser.preferences.BravePref;
import org.chromium.chrome.browser.preferences.ChromeSharedPreferences;
import org.chromium.chrome.browser.util.PackageUtils;

/**
 * Activity responsible for handling the first-run onboarding experience for new Brave browser
 * installations.
 *
 * <p>This activity extends {@link FirstRunActivityBase} and guides users through a series of
 * onboarding steps:
 *
 * <ul>
 *   <li>Setting Brave as the default browser
 *   <li>Accepting the Terms of Service
 * </ul>
 *
 * <p>The onboarding flow uses animations and clear UI elements to introduce Brave’s key features
 * and privacy-focused approach.
 */
@NullMarked
public class WelcomeOnboardingActivity extends FirstRunActivityBase
        implements OnboardingStepAdapter.OnboardingNavigationListener {

    private static final String KEY_SPLASH_ANIMATION_FINISHED =
            "WelcomeOnboardingActivity.SplashAnimationFinished";
    private static final String KEY_PAGE_INDEX = "WelcomeOnboardingActivity.PageIndex";
    private static final float REDUCED_TENSION_OVERSHOOT_INTERPOLATOR = 1f;
    private static final float BRAVE_SPLASH_SCALE_ANIMATION = 0.4f;
    // Bottom 16.8% of the drawable is transparent.
    private static final float BRAVE_SPLASH_BOTTOM_TRANSPARENT_RATIO = 0.168f;
    private static final int BRAVE_SPLASH_ANIMATION_DURATION_MS = 600;

    private boolean mSplashAnimationFinished;
    private int mRestoredPageIndex;

    private ImageView mBraveSplash;
    @Nullable private FrameLayout mBraveSplashContainer;

    private Guideline mSplashGuideline;
    private ViewPager2 mOnboardingPager;

    private OnboardingStepAdapter mStepAdapter;
    private PageBounceAnimator mPageBounceAnimator;

    private void finalStep() {
        ChromeSharedPreferences.getInstance()
                .writeBoolean(OnboardingPrefManager.SHOULD_SHOW_SEARCH_WIDGET_PROMO, true);
        CustomizeBraveMenu.initDefaultInvisibleItems(getResources());

        FirstRunStatus.setFirstRunFlowComplete(true);

        FirstRunUtils.setEulaAccepted();

        launchPendingIntentAndFinish();
    }

    /**
     * Sends the post-FRE intent first and delays finishing this activity until either another
     * activity is resumed or this activity is already stopped/destroyed.
     *
     * <p>This avoids handoff races where finishing too early can cause a brief re-show/flash of the
     * onboarding activity during task/window transitions (for example around fold/unfold state
     * changes).
     *
     * <p>This mirrors the upstream robust pattern used in FirstRunActivity and is specifically
     * meant to avoid transition glitches (for example on foldable state changes).
     */
    private void launchPendingIntentAndFinish() {
        if (!sendFirstRunCompleteIntent()) {
            finish();
        } else {
            ApplicationStatus.registerStateListenerForAllActivities(
                    new ActivityStateListener() {
                        @Override
                        public void onActivityStateChange(Activity activity, int newState) {
                            boolean shouldFinish;
                            if (activity == WelcomeOnboardingActivity.this) {
                                shouldFinish =
                                        (newState == ActivityState.STOPPED
                                                || newState == ActivityState.DESTROYED);
                            } else {
                                shouldFinish = newState == ActivityState.RESUMED;
                            }
                            if (shouldFinish) {
                                finish();
                                ApplicationStatus.unregisterActivityStateListener(this);
                            }
                        }
                    });
        }
    }

    @Override
    public void triggerLayoutInflation() {
        super.triggerLayoutInflation();
        setContentView(R.layout.activity_welcome_onboarding);

        mSplashGuideline = findViewById(R.id.splash_anchor);
        mOnboardingPager = findViewById(R.id.onboarding_steps_pager);

        mOnboardingPager.setUserInputEnabled(false);
        mPageBounceAnimator = new PageBounceAnimator(mOnboardingPager);
        mStepAdapter = new OnboardingStepAdapter(this);
        mOnboardingPager.setAdapter(mStepAdapter);

        mBraveSplash = findViewById(R.id.brave_splash);
        // Only the wide layout (tablets in any orientation and phones in landscape) declares the
        // splash container, and it is what tells the two splash treatments apart.
        mBraveSplashContainer = findViewById(R.id.brave_splash_container);

        if (PackageUtils.isFirstInstall(this)) {
            ChromeSharedPreferences.getInstance()
                    .writeBoolean(
                            BravePreferenceKeys.BRAVE_TAB_GROUPS_ENABLED_DEFAULT_VALUE, false);
        }
        onInitialLayoutInflationComplete();
    }

    @Override
    public void finishNativeInitialization() {
        super.finishNativeInitialization();

        if (mSplashAnimationFinished) {
            if (mBraveSplashContainer != null) {
                mBraveSplashContainer.setVisibility(View.GONE);
            } else {
                // The logo stays on screen in this layout, so put it straight into its
                // post-animation state instead of replaying the animation.
                restoreBraveSplashEndState();
            }
            mOnboardingPager.setCurrentItem(mRestoredPageIndex, false);
            mOnboardingPager.setVisibility(View.VISIBLE);
        } else {
            playAnimatedVectorDrawable();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_SPLASH_ANIMATION_FINISHED, mSplashAnimationFinished);
        outState.putInt(KEY_PAGE_INDEX, mOnboardingPager.getCurrentItem());
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        mSplashAnimationFinished = state.getBoolean(KEY_SPLASH_ANIMATION_FINISHED, false);
        mRestoredPageIndex = state.getInt(KEY_PAGE_INDEX, 0);
    }

    private void playAnimatedVectorDrawable() {
        final AnimatedVectorDrawable vectorDrawable =
                (AnimatedVectorDrawable) mBraveSplash.getDrawable();
        vectorDrawable.registerAnimationCallback(
                new Animatable2.AnimationCallback() {

                    @Override
                    public void onAnimationStart(Drawable drawable) {
                        super.onAnimationStart(drawable);
                        mBraveSplash.setAlpha(1f);
                    }

                    @Override
                    public void onAnimationEnd(Drawable drawable) {
                        if (isActivityFinishingOrDestroyed()) {
                            return;
                        }
                        if (mBraveSplashContainer != null) {
                            fadeBraveSplashContainer();
                        } else {
                            animateBraveSplash(vectorDrawable);
                        }
                    }
                });
        vectorDrawable.start();
    }

    private void showPagerAfterSplash() {
        mOnboardingPager.setCurrentItem(0, false);
        mOnboardingPager.setVisibility(View.VISIBLE);
        mSplashAnimationFinished = true;
        maybeRequestDefaultBrowser();
    }

    private void fadeBraveSplashContainer() {
        Animator animator =
                AnimatorInflater.loadAnimator(
                        WelcomeOnboardingActivity.this, R.animator.ic_brave_splash_fade_out);
        animator.setTarget(mBraveSplashContainer);
        animator.addListener(
                new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationCancel(@NonNull Animator animation) {
                        /* No-op. */
                    }

                    @Override
                    public void onAnimationEnd(@NonNull Animator animation) {
                        assumeNonNull(mBraveSplashContainer).setVisibility(View.GONE);
                        showPagerAfterSplash();
                    }

                    @Override
                    public void onAnimationRepeat(@NonNull Animator animation) {
                        /* No-op. */
                    }

                    @Override
                    public void onAnimationStart(@NonNull Animator animation) {
                        /* No-op. */
                    }
                });
        animator.start();
    }

    /** Moves the pager anchor right below the visible bottom of the shrunken logo. */
    private void updateSplashGuideline() {
        final View parent = (View) mBraveSplash.getParent();
        final int splashHeight = mBraveSplash.getHeight();
        // Splash visual bottom in parent coordinates (center pivot scaling).
        final float splashBottomPx =
                mBraveSplash.getBottom()
                        + mBraveSplash.getTranslationY()
                        - splashHeight * (1f - mBraveSplash.getScaleY()) / 2f;
        // Discard the transparent band the drawable paints below the logo, so the guideline
        // lands on the visible logo bottom.
        final float logoBottomPx =
                splashBottomPx
                        - splashHeight
                                * mBraveSplash.getScaleY()
                                * BRAVE_SPLASH_BOTTOM_TRANSPARENT_RATIO;

        ConstraintLayout.LayoutParams guidelineLayoutParams =
                (ConstraintLayout.LayoutParams) mSplashGuideline.getLayoutParams();
        // guideBegin is relative to the padded content box, while logoBottomPx already
        // includes the parent top padding.
        guidelineLayoutParams.guideBegin = Math.round(logoBottomPx) - parent.getPaddingTop();
        mSplashGuideline.setLayoutParams(guidelineLayoutParams);
    }

    /**
     * Applies the state {@link #animateBraveSplash} ends on, without animating. Used after a
     * configuration change, where the splash must not play again but the logo has to stay put.
     */
    private void restoreBraveSplashEndState() {
        mBraveSplash
                .getViewTreeObserver()
                .addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                mBraveSplash.getViewTreeObserver().removeOnPreDrawListener(this);

                                final View parent = (View) mBraveSplash.getParent();
                                final int splashHeight = mBraveSplash.getHeight();
                                // Compensate because shrinking around center moves the top edge
                                // down.
                                final float compensation =
                                        (splashHeight * (1f - BRAVE_SPLASH_SCALE_ANIMATION)) / 2f;

                                mBraveSplash.setTranslationY(
                                        parent.getPaddingTop()
                                                - mBraveSplash.getTop()
                                                - compensation);
                                mBraveSplash.setScaleX(BRAVE_SPLASH_SCALE_ANIMATION);
                                mBraveSplash.setScaleY(BRAVE_SPLASH_SCALE_ANIMATION);
                                mBraveSplash.setAlpha(1f);
                                updateSplashGuideline();
                                return false;
                            }
                        });
    }

    private void animateBraveSplash(final AnimatedVectorDrawable vectorDrawable) {
        vectorDrawable.clearAnimationCallbacks();

        final View parent = (View) mBraveSplash.getParent();
        final float deltaY = parent.getPaddingTop() - mBraveSplash.getY();
        final int splashHeight = mBraveSplash.getHeight();
        // Compensate because shrinking around center moves the top edge down.
        final float compensation = (splashHeight * (1f - BRAVE_SPLASH_SCALE_ANIMATION)) / 2f;

        mBraveSplash
                .animate()
                .translationY(deltaY - compensation)
                .scaleX(BRAVE_SPLASH_SCALE_ANIMATION)
                .scaleY(BRAVE_SPLASH_SCALE_ANIMATION)
                .setDuration(BRAVE_SPLASH_ANIMATION_DURATION_MS)
                .setInterpolator(new OvershootInterpolator(REDUCED_TENSION_OVERSHOOT_INTERPOLATOR))
                .setListener(
                        new Animator.AnimatorListener() {
                            @Override
                            public void onAnimationCancel(@NonNull Animator animation) {
                                /* No-op. */
                            }

                            @Override
                            public void onAnimationEnd(@NonNull Animator animation) {
                                updateSplashGuideline();
                                showPagerAfterSplash();
                            }

                            @Override
                            public void onAnimationRepeat(@NonNull Animator animation) {
                                /* No-op. */
                            }

                            @Override
                            public void onAnimationStart(@NonNull Animator animation) {
                                /* No-op. */
                            }
                        })
                .start();
    }

    private void maybeRequestDefaultBrowser() {
        if (isActivityFinishingOrDestroyed()) {
            return;
        }
        if (!isBraveSetAsDefaultBrowser(this)) {
            setDefaultBrowser(this);
        }
    }

    @Override
    public @BackPressResult int handleBackPress() {
        return BackPressResult.SUCCESS;
    }

    @Override
    public void onRequestPageChange(final int position) {
        mPageBounceAnimator.animateToPosition(position);
    }

    @Override
    public void onDismiss() {
        finalStep();
    }

}
