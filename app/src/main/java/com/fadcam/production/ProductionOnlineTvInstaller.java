package com.fadcam.production;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.fadcam.R;
import com.fadcam.StudioActivity;
import com.fadcam.streaming.CloudStreamUploader;
import com.fadcam.streaming.RemoteStreamManager;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Locale;

/** Production Online TV health and media/music scene installer. */
public final class ProductionOnlineTvInstaller {
    private static final String TAG = "ProductionOnlineTv";
    private static final String PREFS = "FadCamProductionOnlineTv";
    private static final String PREF_VIEWER_URL = "public_viewer_url";
    private static final String PREF_HLS_URL = "public_hls_url";
    private static final String TAG_ONLINE_TV = "fadcam_online_tv_card";
    private static final String TAG_MEDIA_SCENE = "fadcam_media_scene";
    private static final long HEALTH_INTERVAL_MS = 1500L;
    private static final long RELAY_STALE_MS = 8000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean registered;

    private ProductionOnlineTvInstaller() {}

    public static synchronized void register(Application application) {
        if (registered || application == null) return;
        registered = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle state) { installWhenCreated(a); }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { if (!(a instanceof StudioActivity)) scheduleOnlineTv(a); }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle state) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    private static void installWhenCreated(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            if (activity instanceof StudioActivity) {
                // Studio must be initialized while the Activity is in CREATED state so
                // ActivityResultRegistry registration happens before STARTED.
                installStudio((StudioActivity) activity);
            } else if ("com.fadcam.MainActivity".equals(activity.getClass().getName())) {
                scheduleOnlineTv(activity);
            }
        } catch (Throwable t) {
            android.util.Log.e(TAG, "optional production feature installation failed", t);
        }
    }

    private static void scheduleOnlineTv(Activity activity) {
        MAIN.postDelayed(() -> {
            if (activity.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
            try { installOnlineTv(activity); } catch (Throwable t) { android.util.Log.e(TAG, "Online TV UI failed", t); }
        }, 250L);
    }

    private static int id(Context context, String name) { return context.getResources().getIdentifier(name, "id", context.getPackageName()); }

    private static TextView label(Context c, String text) { TextView v=new TextView(c); v.setText(text); v.setTextColor(Color.WHITE); v.setTextSize(11f); v.setPadding(0,3,0,3); return v; }

    private static void installOnlineTv(Activity activity) {
        int anchorId=id(activity,"recording_mode_row"); if(anchorId==0)return; View anchor=activity.findViewById(anchorId);
        if(anchor==null||!(anchor.getParent() instanceof ViewGroup))return; ViewGroup parent=(ViewGroup)anchor.getParent(); if(parent.findViewWithTag(TAG_ONLINE_TV)!=null)return;
        LinearLayout card=new LinearLayout(activity); card.setTag(TAG_ONLINE_TV); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(14,14,14,14); card.setBackgroundColor(Color.rgb(20,20,20));
        TextView title=label(activity,"ONLINE TV • SERVER DELIVERY"); title.setTextSize(14f); title.setTextColor(Color.rgb(255,70,70)); title.setTypeface(null,android.graphics.Typeface.BOLD); card.addView(title);
        TextView state=label(activity,"ONLINE TV • CHECKING…"), relay=label(activity,"Relay: checking"), viewers=label(activity,"Viewer count: checking"), upload=label(activity,"Upload health: checking"), viewerUrl=label(activity,"Public viewer URL: not configured"), hlsUrl=label(activity,"Public HLS URL: not configured");
        TextView warning=label(activity,""); warning.setTextColor(Color.rgb(255,60,60)); warning.setTypeface(null,android.graphics.Typeface.BOLD); warning.setVisibility(View.GONE);
        card.addView(state);card.addView(relay);card.addView(viewers);card.addView(upload);card.addView(viewerUrl);card.addView(hlsUrl);card.addView(warning);
        Button configure=new Button(activity);configure.setText("CONFIGURE PUBLIC VIEWER / HLS URLS");configure.setOnClickListener(v->showPublicUrlDialog(activity));card.addView(configure);parent.addView(card,parent.indexOfChild(anchor));
        Runnable updater=new Runnable(){@Override public void run(){if(activity.isFinishing()||(android.os.Build.VERSION.SDK_INT>=17&&activity.isDestroyed()))return;updateOnlineTvCard(activity,state,relay,viewers,upload,viewerUrl,hlsUrl,warning);MAIN.postDelayed(this,HEALTH_INTERVAL_MS);}};int tagId=id(activity,"online_tv_health_tag");if(tagId!=0)card.setTag(tagId,updater);MAIN.post(updater);
    }

    private static void updateOnlineTvCard(Activity activity,TextView state,TextView relay,TextView viewers,TextView upload,TextView viewerUrl,TextView hlsUrl,TextView warning){
        try{JSONObject json=new JSONObject(RemoteStreamManager.getInstance().getStatusJson());boolean recording=json.optBoolean("isRecording",false),streaming=json.optBoolean("streaming",false);long last=json.optLong("lastRelayUploadMs",0L);int cloudViewers=json.optInt("cloudViewers",0);boolean telemetry=json.optBoolean("cloudViewerTelemetryAvailable",false);long now=System.currentTimeMillis();boolean enabled=CloudStreamUploader.getInstance(activity).isEnabled();boolean receiving=enabled&&last>0&&now-last<=RELAY_STALE_MS;boolean localReady=streaming&&recording&&json.optInt("fragmentsBuffered",0)>=2;
            if(receiving){state.setText("ONLINE TV • LIVE / RECEIVING");state.setTextColor(Color.rgb(80,220,110));}else if(enabled&&recording){state.setText("ONLINE TV • SERVER NOT RECEIVING");state.setTextColor(Color.rgb(255,60,60));}else if(localReady){state.setText("ONLINE TV • LOCAL HLS READY");state.setTextColor(Color.rgb(255,190,70));}else{state.setText("ONLINE TV • STANDBY");state.setTextColor(Color.LTGRAY);}
            relay.setText("Relay: "+(enabled?(receiving?"RECEIVING SEGMENTS":"ENABLED / WAITING"):"LOCAL ONLY"));viewers.setText("Viewer count: "+cloudViewers+(telemetry?" • LIVE TELEMETRY":" • TELEMETRY UNAVAILABLE"));
            if(receiving){upload.setText("Upload health: GOOD • last segment "+((now-last)/1000L)+"s ago");upload.setTextColor(Color.rgb(80,220,110));warning.setVisibility(View.GONE);}else if(enabled&&recording){upload.setText("Upload health: FAILED / STALE");upload.setTextColor(Color.rgb(255,60,60));warning.setText("⚠ RED ALERT: SERVER HAS STOPPED RECEIVING SEGMENTS");warning.setVisibility(View.VISIBLE);}else{upload.setText("Upload health: IDLE");upload.setTextColor(Color.LTGRAY);warning.setVisibility(View.GONE);}
            android.content.SharedPreferences p=activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE);viewerUrl.setText("Public viewer URL: "+valueOrMissing(p.getString(PREF_VIEWER_URL,"")));hlsUrl.setText("Public HLS URL: "+valueOrMissing(p.getString(PREF_HLS_URL,"")));
        }catch(Exception e){state.setText("ONLINE TV • STATUS ERROR");state.setTextColor(Color.rgb(255,60,60));warning.setText("⚠ Unable to read streaming health");warning.setVisibility(View.VISIBLE);}
    }
    private static String valueOrMissing(String v){return v==null||v.trim().isEmpty()?"not configured":v.trim();}
    private static void showPublicUrlDialog(Activity a){LinearLayout root=new LinearLayout(a);root.setOrientation(LinearLayout.VERTICAL);int pad=(int)(16*a.getResources().getDisplayMetrics().density);root.setPadding(pad,0,pad,0);EditText viewer=new EditText(a),hls=new EditText(a);viewer.setSingleLine(true);hls.setSingleLine(true);viewer.setHint("https://your-domain.example/tv/channel");hls.setHint("https://your-domain.example/tv/channel/live.m3u8");android.content.SharedPreferences p=a.getSharedPreferences(PREFS,Context.MODE_PRIVATE);viewer.setText(p.getString(PREF_VIEWER_URL,""));hls.setText(p.getString(PREF_HLS_URL,""));root.addView(label(a,"PUBLIC VIEWER URL"));root.addView(viewer);root.addView(label(a,"PUBLIC HLS URL"));root.addView(hls);new AlertDialog.Builder(a).setTitle("ONLINE TV DELIVERY").setMessage("Use the exact public routes exposed by your relay/server. FadCam never guesses server routes.").setView(root).setPositiveButton("SAVE",(d,w)->p.edit().putString(PREF_VIEWER_URL,viewer.getText().toString().trim()).putString(PREF_HLS_URL,hls.getText().toString().trim()).apply()).setNegativeButton("CANCEL",null).show();}

    private static void installStudio(StudioActivity activity){
        View rootView=activity.findViewById(android.R.id.content);if(!(rootView instanceof ViewGroup))return;ViewGroup root=(ViewGroup)rootView;if(root.findViewWithTag(TAG_MEDIA_SCENE)!=null)return;int anchorId=id(activity,"studio_rtmp_status");View anchor=anchorId==0?null:activity.findViewById(anchorId);ViewGroup host=anchor!=null&&anchor.getParent() instanceof ViewGroup?(ViewGroup)anchor.getParent():root;
        LinearLayout card=new LinearLayout(activity);card.setTag(TAG_MEDIA_SCENE);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(10,10,10,10);card.setBackgroundColor(Color.rgb(12,17,24));TextView title=label(activity,"MEDIA / MUSIC SCENE");title.setTextSize(12f);title.setTypeface(null,android.graphics.Typeface.BOLD);card.addView(title);TextView status=label(activity,"SOURCE • CAMERA");card.addView(status);
        FrameLayout playerHost=new FrameLayout(activity);playerHost.setBackgroundColor(Color.BLACK);card.addView(playerHost,new LinearLayout.LayoutParams(-1,260));PlayerView playerView=new PlayerView(activity);playerView.setUseController(true);playerView.setVisibility(View.GONE);playerHost.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        WebView youtube=new WebView(activity);youtube.setVisibility(View.GONE);youtube.setBackgroundColor(Color.BLACK);youtube.getSettings().setJavaScriptEnabled(true);youtube.getSettings().setDomStorageEnabled(true);youtube.getSettings().setMediaPlaybackRequiresUserGesture(true);youtube.getSettings().setAllowFileAccess(false);youtube.getSettings().setAllowContentAccess(false);youtube.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String h=r.getUrl().getHost();return h==null||!(h.equals("youtube.com")||h.endsWith(".youtube.com")||h.equals("youtube-nocookie.com")||h.endsWith(".youtube-nocookie.com"));}});playerHost.addView(youtube,new FrameLayout.LayoutParams(-1,-1));
        ExoPlayer player=new ExoPlayer.Builder(activity).build();playerView.setPlayer(player);
        ActivityResultLauncher<String[]> picker=activity.getActivityResultRegistry().register("fadcam-production-media-"+System.identityHashCode(activity),activity,new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null){try{activity.getContentResolver().takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}playDirect(player,playerView,youtube,uri);status.setText("SOURCE • LOCAL • "+uri.getLastPathSegment());}});
        LinearLayout b1=new LinearLayout(activity);b1.setOrientation(LinearLayout.HORIZONTAL);Button local=button(activity,"LOCAL MEDIA"),online=button(activity,"ONLINE MEDIA"),yt=button(activity,"YOUTUBE");b1.addView(local,weightParams());b1.addView(online,weightParams());b1.addView(yt,weightParams());card.addView(b1);LinearLayout b2=new LinearLayout(activity);b2.setOrientation(LinearLayout.HORIZONTAL);Button take=button(activity,"TAKE TO PROGRAM"),camera=button(activity,"RETURN TO CAMERA"),stop=button(activity,"STOP MEDIA");b2.addView(take,weightParams());b2.addView(camera,weightParams());b2.addView(stop,weightParams());card.addView(b2);
        TextView note=label(activity,"YouTube uses the official embedded player. Ads and playback rules remain controlled by YouTube; FadCam does not suppress, extract or re-stream YouTube media.");note.setTextColor(Color.rgb(170,180,194));card.addView(note);host.addView(card,0);
        local.setOnClickListener(v->picker.launch(new String[]{"video/*","audio/*"}));online.setOnClickListener(v->showOnlineUrlDialog(activity,player,playerView,youtube,status));yt.setOnClickListener(v->showYouTubeDialog(activity,youtube,playerView,status));take.setOnClickListener(v->takeMediaToProgram(activity,player,playerView,youtube,status));camera.setOnClickListener(v->returnToCamera(playerHost,playerView,youtube,status));stop.setOnClickListener(v->{player.stop();youtube.stopLoading();playerView.setVisibility(View.GONE);youtube.setVisibility(View.GONE);status.setText("SOURCE • CAMERA");});
        activity.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){@Override public void onViewAttachedToWindow(View v){}@Override public void onViewDetachedFromWindow(View v){player.release();youtube.destroy();picker.unregister();}});
    }

    private static Button button(Context c,String t){Button b=new Button(c);b.setText(t);return b;}
    private static LinearLayout.LayoutParams weightParams(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,48,1f);p.setMargins(2,2,2,2);return p;}
    private static void takeMediaToProgram(Activity a,ExoPlayer player,PlayerView pv,WebView yt,TextView status){int rid=id(a,"studio_program_snapshot");View image=rid==0?null:a.findViewById(rid);if(image==null||!(image.getParent() instanceof FrameLayout)){Toast.makeText(a,"Program monitor is unavailable",Toast.LENGTH_SHORT).show();return;}FrameLayout program=(FrameLayout)image.getParent();detach(pv);detach(yt);program.addView(pv,new FrameLayout.LayoutParams(-1,-1));program.addView(yt,new FrameLayout.LayoutParams(-1,-1));pv.setVisibility(player.getPlaybackState()!=ExoPlayer.STATE_IDLE?View.VISIBLE:View.GONE);yt.setVisibility(yt.getUrl()!=null?View.VISIBLE:View.GONE);status.setText("SOURCE • MEDIA PROGRAM");}
    private static void returnToCamera(FrameLayout host,PlayerView pv,WebView yt,TextView status){detach(pv);detach(yt);host.addView(pv,new FrameLayout.LayoutParams(-1,-1));host.addView(yt,new FrameLayout.LayoutParams(-1,-1));pv.setVisibility(View.VISIBLE);yt.setVisibility(View.GONE);status.setText("SOURCE • CAMERA");}
    private static void detach(View v){if(v.getParent() instanceof ViewGroup)((ViewGroup)v.getParent()).removeView(v);}
    private static void showOnlineUrlDialog(Activity a,ExoPlayer player,PlayerView pv,WebView yt,TextView status){EditText input=new EditText(a);input.setSingleLine(true);input.setHint("https://example.com/live.m3u8 or music.mp4");new AlertDialog.Builder(a).setTitle("AUTHORIZED ONLINE MEDIA").setMessage("Use an HTTPS media URL that you are authorized to play. HLS (.m3u8), MP4 and MP3 are supported when compatible.").setView(input).setPositiveButton("PLAY",(d,w)->{String url=input.getText().toString().trim();if(!url.toLowerCase(Locale.US).startsWith("https://")){Toast.makeText(a,"Use an HTTPS media URL",Toast.LENGTH_LONG).show();return;}playDirect(player,pv,yt,Uri.parse(url));status.setText("SOURCE • ONLINE • "+url);}).setNegativeButton("CANCEL",null).show();}
    private static void playDirect(ExoPlayer player,PlayerView pv,WebView yt,Uri uri){yt.setVisibility(View.GONE);pv.setVisibility(View.VISIBLE);MediaItem.Builder b=new MediaItem.Builder().setUri(uri);String s=uri.toString().toLowerCase(Locale.US);if(s.contains(".m3u8"))b.setMimeType(MimeTypes.APPLICATION_M3U8);else if(s.contains(".mp3"))b.setMimeType(MimeTypes.AUDIO_MPEG);else if(s.contains(".mp4"))b.setMimeType(MimeTypes.VIDEO_MP4);player.setMediaItem(b.build());player.prepare();player.play();}
    private static void showYouTubeDialog(Activity a,WebView yt,PlayerView pv,TextView status){EditText input=new EditText(a);input.setSingleLine(true);input.setHint("https://www.youtube.com/watch?v=VIDEO_ID");new AlertDialog.Builder(a).setTitle("YOUTUBE OFFICIAL PLAYER").setMessage("YouTube is played only through its official embedded player. FadCam cannot suppress YouTube advertisements or extract/re-stream YouTube media.").setView(input).setPositiveButton("LOAD",(d,w)->{String id=extractYouTubeId(input.getText().toString().trim());if(id==null){Toast.makeText(a,"Invalid YouTube URL",Toast.LENGTH_LONG).show();return;}pv.setVisibility(View.GONE);yt.setVisibility(View.VISIBLE);String embed="https://www.youtube.com/embed/"+id+"?playsinline=1&controls=1&rel=0";yt.loadUrl(embed,Collections.singletonMap("Referer","https://com.fadcam"));status.setText("SOURCE • YOUTUBE • OFFICIAL PLAYER");}).setNegativeButton("CANCEL",null).show();}
    private static String extractYouTubeId(String value){try{Uri u=Uri.parse(value);String host=u.getHost();if(host==null)return null;if(host.equals("youtu.be"))return validId(u.getLastPathSegment());if(host.equals("youtube.com")||host.endsWith(".youtube.com")){String id=validId(u.getQueryParameter("v"));if(id!=null)return id;String p=u.getPath();if(p!=null&&p.startsWith("/embed/"))return validId(p.substring(7));if(p!=null&&p.startsWith("/shorts/"))return validId(p.substring(8));}}catch(Exception ignored){}return null;}
    private static String validId(String v){return v!=null&&v.matches("[A-Za-z0-9_-]{6,20}")?v:null;}
}
