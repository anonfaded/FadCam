package com.fadcam.ui.picker;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fadcam.R;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

/**
 * NumberInputBottomSheetFragment
 * Lightweight numeric input sheet with min/max validation and helper messaging.
 */
public class NumberInputBottomSheetFragment extends BottomSheetDialogFragment {

    public static final String ARG_TITLE = "title";
    public static final String ARG_MIN = "min";
    public static final String ARG_MAX = "max";
    public static final String ARG_VALUE = "value";
    public static final String ARG_RESULT_KEY = "result_key";
    public static final String ARG_HINT = "hint";
    public static final String ARG_LOW_MSG = "low_msg";
    public static final String ARG_HIGH_MSG = "high_msg";
    public static final String ARG_LOW_THRESHOLD = "low_threshold";
    public static final String ARG_HIGH_THRESHOLD = "high_threshold";
    public static final String ARG_DESCRIPTION = "description"; // static helper/description shown above dynamic hints
    public static final String ARG_DEFAULT_VALUE = "default_value"; // value to reset to
    public static final String ARG_SHOW_RESET = "show_reset"; // boolean
    public static final String RESULT_NUMBER = "number_value";

    public static NumberInputBottomSheetFragment newInstance(String title, int min, int max, int value, String hint,
                                                             int lowThreshold, int highThreshold, String lowMsg, String highMsg, String resultKey){
        NumberInputBottomSheetFragment f = new NumberInputBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putInt(ARG_MIN, min);
        b.putInt(ARG_MAX, max);
        b.putInt(ARG_VALUE, value);
        b.putString(ARG_HINT, hint);
        b.putInt(ARG_LOW_THRESHOLD, lowThreshold);
        b.putInt(ARG_HIGH_THRESHOLD, highThreshold);
        b.putString(ARG_LOW_MSG, lowMsg);
        b.putString(ARG_HIGH_MSG, highMsg);
        b.putString(ARG_RESULT_KEY, resultKey);
        // Defaults: no description/ reset by default unless caller adds via builder variant (future)
        f.setArguments(b);
        return f;
    }

    private int min, max, value, lowTh, highTh, defaultValue; private String title, hint, lowMsg, highMsg, resultKey; private EditText field; private TextView helper; private Button ok; private TextView descriptionView; private String descriptionText; private Button resetButton; private boolean showReset;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.number_input_bottom_sheet, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // -------------- Fix Start for this method(onViewCreated)-----------
        Bundle a = getArguments();
        if(a!=null){
            title = a.getString(ARG_TITLE, "");
            min = a.getInt(ARG_MIN, 0);
            max = a.getInt(ARG_MAX, Integer.MAX_VALUE);
            value = a.getInt(ARG_VALUE, min);
            hint = a.getString(ARG_HINT, "");
            lowTh = a.getInt(ARG_LOW_THRESHOLD, -1);
            highTh = a.getInt(ARG_HIGH_THRESHOLD, -1);
            lowMsg = a.getString(ARG_LOW_MSG, "");
            highMsg = a.getString(ARG_HIGH_MSG, "");
            resultKey = a.getString(ARG_RESULT_KEY, "number_input_result");
            defaultValue = a.getInt(ARG_DEFAULT_VALUE, value);
            showReset = a.getBoolean(ARG_SHOW_RESET, false);
            descriptionText = a.getString(ARG_DESCRIPTION, null);
        }
        TextView titleView = view.findViewById(R.id.number_input_title);
        field = view.findViewById(R.id.number_input_field);
        helper = view.findViewById(R.id.number_input_helper);
        descriptionView = view.findViewById(R.id.number_input_description);
        ok = view.findViewById(R.id.number_input_ok);
        Button cancel = view.findViewById(R.id.number_input_cancel);
        resetButton = view.findViewById(R.id.number_input_reset);
        if(titleView!=null) titleView.setText(title);
        if(field!=null){ field.setHint(hint); field.setText(String.valueOf(value)); field.setSelection(field.getText().length()); }
        if(descriptionView!=null){ if(descriptionText!=null && !descriptionText.isEmpty()){ descriptionView.setText(descriptionText); descriptionView.setVisibility(View.VISIBLE);} else { descriptionView.setVisibility(View.GONE);} }
        if(resetButton!=null){
            if(showReset){
                resetButton.setVisibility(View.VISIBLE);
                resetButton.setOnClickListener(v-> { field.setText(String.valueOf(defaultValue)); });
            } else { resetButton.setVisibility(View.GONE); }
        }
        cancel.setOnClickListener(v-> dismiss());
        ok.setOnClickListener(v->{
            Integer parsed = parseField();
            if(parsed!=null){ Bundle result = new Bundle(); result.putInt(RESULT_NUMBER, parsed); getParentFragmentManager().setFragmentResult(resultKey, result); dismiss(); }
        });
        field.addTextChangedListener(new TextWatcher(){ public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){ validate(); } public void afterTextChanged(Editable e){} });
        validate();
        // -------------- Fix Ended for this method(onViewCreated)-----------
    }

    private Integer parseField(){ try{ return Integer.parseInt(field.getText().toString().trim()); }catch(Exception e){ return null; } }

    private void validate(){
        Integer val = parseField();
    if(val==null){ helper.setText(getString(R.string.universal_enter_number)); helper.setTextColor(getResources().getColor(android.R.color.holo_orange_light, requireContext().getTheme())); ok.setEnabled(false); return; }
    if(val<min){ helper.setText(getString(R.string.universal_min_value, min)); helper.setTextColor(getResources().getColor(android.R.color.holo_red_light, requireContext().getTheme())); ok.setEnabled(false); return; }
    if(val>max){ helper.setText(getString(R.string.universal_max_value, max)); helper.setTextColor(getResources().getColor(android.R.color.holo_red_light, requireContext().getTheme())); ok.setEnabled(false); return; }
    // Threshold hints
    if(lowTh>0 && val<lowTh && !lowMsg.isEmpty()){ helper.setText(lowMsg); helper.setTextColor(getResources().getColor(android.R.color.holo_orange_light, requireContext().getTheme())); }
    else if(highTh>0 && val>highTh && !highMsg.isEmpty()){ helper.setText(highMsg); helper.setTextColor(getResources().getColor(android.R.color.holo_red_light, requireContext().getTheme())); }
    else { helper.setText(getString(R.string.universal_ok)); helper.setTextColor(getResources().getColor(android.R.color.holo_green_light, requireContext().getTheme())); }
        ok.setEnabled(true);
    }
}
