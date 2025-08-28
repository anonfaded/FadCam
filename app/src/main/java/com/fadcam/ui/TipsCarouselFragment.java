package com.fadcam.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class TipsCarouselFragment extends BottomSheetDialogFragment {
    
    private ViewPager2 viewPager;
    private LinearLayout dotsIndicator;
    private ImageView btnPrevious, btnNext, btnClose;
    private String[] tips;
    private int currentPosition = 0;

    public static TipsCarouselFragment newInstance() {
        return new TipsCarouselFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_tips_carousel, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        initViews(view);
        setupTips();
        setupViewPager();
        setupNavigation();
        setupDotsIndicator();
    }

    private void initViews(View view) {
        viewPager = view.findViewById(R.id.tips_viewpager);
        dotsIndicator = view.findViewById(R.id.dots_indicator);
        btnPrevious = view.findViewById(R.id.btn_previous_tip);
        btnNext = view.findViewById(R.id.btn_next_tip);
        btnClose = view.findViewById(R.id.btn_close_tips);
        
        btnClose.setOnClickListener(v -> dismiss());
    }

    private void setupTips() {
        tips = getResources().getStringArray(R.array.tips_widget);
    }

    private void setupViewPager() {
        TipsAdapter adapter = new TipsAdapter(tips);
        viewPager.setAdapter(adapter);
        
        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                currentPosition = position;
                updateDotsIndicator();
                updateNavigationButtons();
            }
        });
    }

    private void setupNavigation() {
        btnPrevious.setOnClickListener(v -> {
            if (currentPosition > 0) {
                viewPager.setCurrentItem(currentPosition - 1, true);
            }
        });

        btnNext.setOnClickListener(v -> {
            if (currentPosition < tips.length - 1) {
                viewPager.setCurrentItem(currentPosition + 1, true);
            }
        });
        
        updateNavigationButtons();
    }

    private void setupDotsIndicator() {
        dotsIndicator.removeAllViews();
        
        for (int i = 0; i < tips.length; i++) {
            View dot = new View(getContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(12, 12);
            params.setMargins(4, 0, 4, 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.dot_inactive);
            dotsIndicator.addView(dot);
        }
        
        updateDotsIndicator();
    }

    private void updateDotsIndicator() {
        for (int i = 0; i < dotsIndicator.getChildCount(); i++) {
            View dot = dotsIndicator.getChildAt(i);
            if (i == currentPosition) {
                dot.setBackgroundResource(R.drawable.dot_active);
            } else {
                dot.setBackgroundResource(R.drawable.dot_inactive);
            }
        }
    }

    private void updateNavigationButtons() {
        btnPrevious.setEnabled(currentPosition > 0);
        btnNext.setEnabled(currentPosition < tips.length - 1);
        
        // Update button appearance for disabled state
        btnPrevious.setAlpha(currentPosition > 0 ? 1.0f : 0.3f);
        btnNext.setAlpha(currentPosition < tips.length - 1 ? 1.0f : 0.3f);
        
        // Update next button label for last page
        TextView nextLabel = getView().findViewById(R.id.btn_next_label);
        if (currentPosition == tips.length - 1) {
            nextLabel.setText(getString(R.string.done));
            btnNext.setOnClickListener(v -> dismiss());
        } else {
            nextLabel.setText(getString(R.string.next));
            btnNext.setOnClickListener(v -> {
                if (currentPosition < tips.length - 1) {
                    viewPager.setCurrentItem(currentPosition + 1, true);
                }
            });
        }
    }

    // Adapter for ViewPager2
    private static class TipsAdapter extends RecyclerView.Adapter<TipsAdapter.TipViewHolder> {
        private final String[] tips;

        public TipsAdapter(String[] tips) {
            this.tips = tips;
        }

        @NonNull
        @Override
        public TipViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tip_card, parent, false);
            return new TipViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull TipViewHolder holder, int position) {
            String fullTip = tips[position];
            
            // Extract counter and content (e.g., "1/11 | Long press preview...")
            String[] parts = fullTip.split(" \\| ", 2);
            if (parts.length == 2) {
                holder.tipCounter.setText(parts[0]);
                holder.tipContent.setText(parts[1]);
            } else {
                holder.tipCounter.setText((position + 1) + "/" + tips.length);
                holder.tipContent.setText(fullTip);
            }
        }

        @Override
        public int getItemCount() {
            return tips.length;
        }

        static class TipViewHolder extends RecyclerView.ViewHolder {
            TextView tipCounter, tipContent;

            public TipViewHolder(@NonNull View itemView) {
                super(itemView);
                tipCounter = itemView.findViewById(R.id.tip_counter);
                tipContent = itemView.findViewById(R.id.tip_content);
            }
        }
    }
}