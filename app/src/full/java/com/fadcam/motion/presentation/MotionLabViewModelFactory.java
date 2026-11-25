package com.fadcam.motion.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.fadcam.motion.data.MotionSettingsRepository;

public class MotionLabViewModelFactory implements ViewModelProvider.Factory {

    private final MotionSettingsRepository repository;

    public MotionLabViewModelFactory(MotionSettingsRepository repository) {
        this.repository = repository;
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(MotionLabViewModel.class)) {
            return (T) new MotionLabViewModel(repository);
        }
        throw new IllegalArgumentException("Unknown ViewModel class: " + modelClass.getName());
    }
}
