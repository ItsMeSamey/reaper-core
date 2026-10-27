/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */
package org.chromium.chrome.browser.firstrun;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.recyclerview.widget.RecyclerView;

import com.airbnb.lottie.LottieAnimationView;

import org.chromium.brave.browser.firstrun.R;
import org.chromium.build.annotations.NullMarked;

/**
 * RecyclerView adapter for the onboarding ViewPager2, inflating one layout per step and delegating
 * navigation requests to the host via {@link OnboardingNavigationListener}.
 */
@NullMarked
public class OnboardingStepAdapter
        extends RecyclerView.Adapter<OnboardingStepAdapter.OnboardingBaseViewHolder> {

    /**
     * Listener used by onboarding step views to request a forward page change in the host.
     *
     * @see WelcomeOnboardingActivity
     */
    public interface OnboardingNavigationListener {
        void onRequestPageChange(final int position);

        void onDismiss();

    }

    private static final int[] STEPS = {
        R.layout.block_interruptions, R.layout.make_brave_better
    };

    private static final int BLOCK_INTERRUPTIONS_POSITION = 0;
    private static final int READY_TO_BROWSE_POSITION = 1;

    private final OnboardingNavigationListener mListener;

    public OnboardingStepAdapter(final OnboardingNavigationListener listener) {
        mListener = listener;
    }

    @Override
    public OnboardingBaseViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        View view = inflater.inflate(STEPS[viewType], parent, false);
        return switch (viewType) {
            case BLOCK_INTERRUPTIONS_POSITION -> new BlockInterruptionsViewHolder(view, mListener);
            case READY_TO_BROWSE_POSITION ->
                    new ReadyToBrowseViewHolder(view, mListener);
            default ->
                    throw new IllegalStateException(
                            String.format("View not found for view type %d.", viewType));
        };
    }

    @Override
    public void onBindViewHolder(OnboardingBaseViewHolder holder, int position) {
        // This page has no persisted controls to bind.
    }

    @Override
    public void onViewRecycled(OnboardingBaseViewHolder holder) {
        holder.viewRecycled();
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return STEPS.length;
    }

    public abstract static class OnboardingBaseViewHolder extends RecyclerView.ViewHolder {

        public OnboardingBaseViewHolder(View itemView) {
            super(itemView);
            itemView.setClipToOutline(true);
        }

        public abstract void viewRecycled();
    }

    private static final class BlockInterruptionsViewHolder extends OnboardingBaseViewHolder {
        final Button mContinue;
        final LottieAnimationView mBlockInterruptions;

        private BlockInterruptionsViewHolder(
                final View itemView, final OnboardingNavigationListener listener) {
            super(itemView);
            mContinue = itemView.findViewById(R.id.onboarding_continue);
            mContinue.setClipToOutline(true);
            mContinue.setOnClickListener(
                    view -> listener.onRequestPageChange(READY_TO_BROWSE_POSITION));

            mBlockInterruptions = itemView.findViewById(R.id.onboarding_illustration);
        }

        @Override
        public void viewRecycled() {
            mBlockInterruptions.cancelAnimation();
        }
    }

    private static final class ReadyToBrowseViewHolder extends OnboardingBaseViewHolder {
        private ReadyToBrowseViewHolder(
                final View itemView, final OnboardingNavigationListener listener) {
            super(itemView);
            Button startBrowsing = itemView.findViewById(R.id.onboarding_start_browsing);
            startBrowsing.setClipToOutline(true);
            startBrowsing.setOnClickListener(view -> listener.onDismiss());
        }

        @Override
        public void viewRecycled() {}
    }
}
