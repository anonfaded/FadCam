package com.fadcam.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.card.MaterialCardView;

import com.fadcam.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Recording publishing hub.
 *
 * Uses Android's secure media-sharing contract rather than embedding platform
 * credentials. This keeps provider authentication inside the provider app and
 * lets the user finish the final post there. Android recommends ACTION_SEND for
 * sharing media between applications and a content URI with read permission.
 */
public class SocialPublishBottomSheet extends BottomSheetDialogFragment {

    private static final String ARG_URI = "video_uri";
    private static final String ARG_NAME = "video_name";

    private static final String PKG_YOUTUBE = "com.google.android.youtube";
    private static final String PKG_FACEBOOK = "com.facebook.katana";
    private static final String PKG_INSTAGRAM = "com.instagram.android";
    private static final String PKG_TIKTOK = "com.zhiliaoapp.musically";
    private static final String PKG_LINKEDIN = "com.linkedin.android";
    private static final String PKG_X = "com.twitter.android";
    private static final String PKG_REDDIT = "com.reddit.frontpage";
    private static final String PKG_THREADS = "com.instagram.barcelona";
    private static final String PKG_WHATSAPP = "com.whatsapp";
    private static final String PKG_TELEGRAM = "org.telegram.messenger";

    private Uri videoUri;
    private String videoName;

    public static void show(@NonNull FragmentActivity activity, @NonNull Uri uri, @Nullable String name) {
        SocialPublishBottomSheet sheet = new SocialPublishBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_URI, uri.toString());
        args.putString(ARG_NAME, name == null ? "Video" : name);
        sheet.setArguments(args);
        sheet.show(activity.getSupportFragmentManager(), "social_publish_sheet");
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        Bundle args = getArguments();
        if (args == null || args.getString(ARG_URI) == null) {
            dismissAllowingStateLoss();
            return new View(context);
        }

        videoUri = Uri.parse(args.getString(ARG_URI));
        videoName = args.getString(ARG_NAME, "Video");

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        root.setBackgroundColor(ContextCompat.getColor(context, android.R.color.background_dark));

        TextView title = new TextView(context);
        title.setText("Publish recording");
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(ContextCompat.getColor(context, android.R.color.white));
        root.addView(title, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        TextView subtitle = new TextView(context);
        subtitle.setText(videoName + "\nVideo is attached securely. Choose a destination to continue publishing.");
        subtitle.setTextSize(13);
        subtitle.setTextColor(0xFFBDBDBD);
        root.addView(subtitle, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        TextView installedHeader = new TextView(context);
        installedHeader.setText("DESTINATIONS");
        installedHeader.setTextSize(12);
        installedHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        installedHeader.setTextColor(0xFFEF5350);
        root.addView(installedHeader, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));

        for (Destination destination : destinations()) {
            root.addView(createDestinationCard(context, destination));
        }

        MaterialCardView moreCard = createCard(context);
        moreCard.addView(createTextRow(context, "More apps", "Open the Android sharing sheet", "apps"));
        moreCard.setOnClickListener(v -> shareGeneric(context));
        root.addView(moreCard, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));

        TextView note = new TextView(context);
        note.setText("Publishing is completed in the selected platform. FadCam never stores or asks for your social-network passwords.");
        note.setTextSize(11);
        note.setTextColor(0xFF888888);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        return root;
    }

    private MaterialCardView createDestinationCard(Context context, Destination destination) {
        MaterialCardView card = createCard(context);
        boolean installed = isInstalled(context, destination.packageName);
        String state = installed ? "Installed • ready" : "Not installed • use web/chooser";
        card.addView(createTextRow(context, destination.name, state, destination.glyph));
        card.setAlpha(installed ? 1f : 0.82f);
        card.setOnClickListener(v -> publishTo(context, destination));
        return card;
    }

    private MaterialCardView createCard(Context context) {
        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(dp(14));
        card.setCardBackgroundColor(0xFF1B1B1B);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(0xFF333333);
        card.setClickable(true);
        card.setFocusable(true);
        return card;
    }

    private View createTextRow(Context context, String name, String state, String glyph) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));

        TextView icon = new TextView(context);
        icon.setText(glyph);
        icon.setTextSize(12);
        icon.setGravity(Gravity.CENTER);
        icon.setTextColor(0xFFFFFFFF);
        icon.setBackgroundColor(0xFF2A2A2A);
        row.addView(icon, lp(dp(42), dp(42)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, 0, 0);
        TextView primary = new TextView(context);
        primary.setText(name);
        primary.setTextSize(16);
        primary.setTypeface(null, android.graphics.Typeface.BOLD);
        primary.setTextColor(0xFFFFFFFF);
        labels.addView(primary, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(25)));
        TextView secondary = new TextView(context);
        secondary.setText(state);
        secondary.setTextSize(11);
        secondary.setTextColor(0xFF9E9E9E);
        labels.addView(secondary, lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(22)));
        row.addView(labels, lp(0, dp(58), 1f));

        TextView arrow = new TextView(context);
        arrow.setText("›");
        arrow.setTextSize(26);
        arrow.setTextColor(0xFFEF5350);
        row.addView(arrow, lp(dp(30), dp(58)));
        return row;
    }

    private List<Destination> destinations() {
        List<Destination> list = new ArrayList<>();
        list.add(new Destination("YouTube", PKG_YOUTUBE, "YT"));
        list.add(new Destination("Facebook", PKG_FACEBOOK, "FB"));
        list.add(new Destination("Instagram", PKG_INSTAGRAM, "IG"));
        list.add(new Destination("TikTok", PKG_TIKTOK, "TT"));
        list.add(new Destination("LinkedIn", PKG_LINKEDIN, "in"));
        list.add(new Destination("X", PKG_X, "X"));
        list.add(new Destination("Reddit", PKG_REDDIT, "R"));
        list.add(new Destination("Threads", PKG_THREADS, "@"));
        list.add(new Destination("WhatsApp", PKG_WHATSAPP, "WA"));
        list.add(new Destination("Telegram", PKG_TELEGRAM, "TG"));
        return list;
    }

    private void publishTo(Context context, Destination destination) {
        Uri shareUri = getShareableUri(context, videoUri);
        if (shareUri == null) {
            Toast.makeText(context, "Video could not be prepared for sharing", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_STREAM, shareUri);
        intent.putExtra(Intent.EXTRA_TITLE, videoName);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.setPackage(destination.packageName);

        try {
            context.startActivity(intent);
            dismissAllowingStateLoss();
        } catch (ActivityNotFoundException notInstalled) {
            // Some providers intentionally expose only part of their publishing flow to
            // third-party intents. In that case let the user continue with the standard
            // Android Sharesheet rather than failing the recording action.
            Intent chooser = new Intent(Intent.ACTION_SEND);
            chooser.setType("video/*");
            chooser.putExtra(Intent.EXTRA_STREAM, shareUri);
            chooser.putExtra(Intent.EXTRA_TITLE, videoName);
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                context.startActivity(Intent.createChooser(chooser, "Publish " + videoName));
            } catch (Exception e) {
                Toast.makeText(context, "No compatible sharing app is available", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Could not open " + destination.name, Toast.LENGTH_LONG).show();
        }
    }

    private void shareGeneric(Context context) {
        Uri shareUri = getShareableUri(context, videoUri);
        if (shareUri == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/*");
        send.putExtra(Intent.EXTRA_STREAM, shareUri);
        send.putExtra(Intent.EXTRA_TITLE, videoName);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(send, "Publish " + videoName));
    }

    private Uri getShareableUri(Context context, Uri uri) {
        if (uri == null) return null;
        if ("content".equals(uri.getScheme())) return uri;
        if ("file".equals(uri.getScheme())) {
            try {
                File file = new File(uri.getPath());
                return FileProvider.getUriForFile(context,
                        context.getApplicationContext().getPackageName() + ".provider", file);
            } catch (Exception ignored) {
                return uri;
            }
        }
        return uri;
    }

    private boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams lp(int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }

    private static final class Destination {
        final String name;
        final String packageName;
        final String glyph;
        Destination(String name, String packageName, String glyph) {
            this.name = name;
            this.packageName = packageName;
            this.glyph = glyph;
        }
    }
}
