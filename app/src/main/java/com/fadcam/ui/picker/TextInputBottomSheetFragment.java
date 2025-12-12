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
 * TextInputBottomSheetFragment
 * Simple, styled text input bottom sheet consistent with NumberInputBottomSheetFragment.
 */
public class TextInputBottomSheetFragment extends BottomSheetDialogFragment {

    public static final String ARG_TITLE = "title";
    public static final String ARG_VALUE = "value";
    public static final String ARG_HINT = "hint";
    public static final String ARG_DESCRIPTION = "description";
    public static final String ARG_RESULT_KEY = "result_key";
    public static final String RESULT_TEXT = "text_value";

    public static TextInputBottomSheetFragment newInstance(String title, String value, String hint, String description, String resultKey){
        TextInputBottomSheetFragment f = new TextInputBottomSheetFragment();
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putString(ARG_VALUE, value);
        b.putString(ARG_HINT, hint);
        b.putString(ARG_DESCRIPTION, description);
        b.putString(ARG_RESULT_KEY, resultKey);
        f.setArguments(b);
        return f;
    }

    private String title; private String value; private String hint; private String description; private String resultKey;
    private EditText field; private TextView helper; private Button ok;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.text_input_bottom_sheet, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle a = getArguments();
        if(a!=null){
            title = a.getString(ARG_TITLE, "");
            value = a.getString(ARG_VALUE, "");
            hint = a.getString(ARG_HINT, "");
            description = a.getString(ARG_DESCRIPTION, null);
            resultKey = a.getString(ARG_RESULT_KEY, "text_input_result");
        }
        TextView titleView = view.findViewById(R.id.text_input_title);
        field = view.findViewById(R.id.text_input_field);
        helper = view.findViewById(R.id.text_input_description);
        ok = view.findViewById(R.id.text_input_ok);
        Button cancel = view.findViewById(R.id.text_input_cancel);

        if(titleView!=null) titleView.setText(title);
        if(field!=null){
            field.setHint(hint);
            if(value!=null){ field.setText(value); field.setSelection(field.getText().length()); }
        }
        if(helper!=null){ if(description!=null && !description.isEmpty()){ helper.setText(description); helper.setVisibility(View.VISIBLE);} else { helper.setVisibility(View.GONE);} }
        cancel.setOnClickListener(v-> dismiss());
        ok.setOnClickListener(v->{
            String text = field!=null && field.getText()!=null ? field.getText().toString().trim() : "";
            Bundle res = new Bundle();
            res.putString(RESULT_TEXT, text);
            getParentFragmentManager().setFragmentResult(resultKey, res);
            dismiss();
        });
        field.addTextChangedListener(new TextWatcher(){ public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){ validate(); } public void afterTextChanged(Editable e){} });
        validate();
    }

    private void validate(){
        if(ok==null || field==null) return;
        String t = field.getText()!=null ? field.getText().toString().trim() : "";
        // Allow empty to mean "use default" for rename, but disable if whitespace-only
        ok.setEnabled(true);
    }
}
