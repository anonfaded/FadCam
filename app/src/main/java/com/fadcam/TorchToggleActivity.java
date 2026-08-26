package com.fadcam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.services.TorchService;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.streaming.RemoteStreamManager;
import com.fadcam.utils.ServiceUtils;

import java.util.ArrayList;
import java.util.Random;

/** Existing exported torch router plus the FadCam app-to-app guest camera entry point. */
public class TorchToggleActivity extends Activity {
    private static final String TAG = "TorchToggleActivity";
    private static final int GUEST_PERMISSION_REQUEST = 8121;
    private boolean guestMode;
    private WebView guestWebView;
    private int guestSlot = -1;
    private String guestToken = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (isGuestInvite(getIntent())) { guestMode = true; readGuestInvite(getIntent()); showGuestCamera(); return; }
        runTorchToggle();
    }
    private boolean isGuestInvite(Intent intent) { return intent != null && intent.getData() != null && "fadcam".equalsIgnoreCase(intent.getData().getScheme()) && "guest-camera".equalsIgnoreCase(intent.getData().getHost()); }
    private void readGuestInvite(Intent intent) { try { guestSlot=Integer.parseInt(String.valueOf(intent.getData().getQueryParameter("slot"))); guestToken=intent.getData().getQueryParameter("token"); if(guestToken==null)guestToken=""; } catch(Exception ignored){guestSlot=-1;guestToken="";} }
    private void showGuestCamera(){
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        if(guestSlot<1||guestSlot>6||guestToken.isEmpty()){finish();return;}
        com.fadcam.production.ProductionCameraSlot slot=com.fadcam.production.ProductionCameraSlotManager.get(this,guestSlot);
        if(!guestToken.equals(slot.getInviteToken())||slot.isInviteConsumed()){Toast.makeText(this,"This FadCam camera invitation is no longer active",Toast.LENGTH_LONG).show();finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        TextView header=new TextView(this);header.setText("FADCAM • CAMERA "+guestSlot+"\nGuest camera • one-use studio invite");header.setTextColor(Color.WHITE);header.setTextSize(15);header.setPadding(18,18,18,18);header.setBackgroundColor(Color.rgb(55,14,18));root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        guestWebView=new WebView(this);guestWebView.setBackgroundColor(Color.BLACK);root.addView(guestWebView,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        if(hasGuestPermissions())loadGuestPublisher();else ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO},GUEST_PERMISSION_REQUEST);
    }
    private boolean hasGuestPermissions(){return ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED&&ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private void loadGuestPublisher(){
        WebSettings s=guestWebView.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMediaPlaybackRequiresUserGesture(false);s.setAllowContentAccess(true);s.setDatabaseEnabled(true);guestWebView.setWebViewClient(new WebViewClient());
        guestWebView.setWebChromeClient(new WebChromeClient(){@Override public void onPermissionRequest(final PermissionRequest request){runOnUiThread(()->{if(request.getOrigin()==null||!"vdo.ninja".equalsIgnoreCase(request.getOrigin().getHost())){request.deny();return;}ArrayList<String> allowed=new ArrayList<>();for(String r:request.getResources())if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)||PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r))allowed.add(r);if(allowed.isEmpty())request.deny();else request.grant(allowed.toArray(new String[0]));});}});
        guestWebView.loadUrl(com.fadcam.production.ProductionCameraSlotManager.vdoPushLink(this,guestSlot));
    }
    private void runTorchToggle(){try{SharedPreferencesManager sp=SharedPreferencesManager.getInstance(this);Intent intent;RemoteStreamManager stream=RemoteStreamManager.getInstance();if(stream.isStreamingEnabled()){boolean dual=ServiceUtils.isServiceRunning(this,DualCameraRecordingService.class)||(sp.getCameraSelection()!=null&&sp.getCameraSelection().isDual());intent=new Intent(this,dual?DualCameraRecordingService.class:RecordingService.class);intent.setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);}else{intent=new Intent(this,TorchService.class);intent.setAction(Constants.INTENT_ACTION_TOGGLE_TORCH);}if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O)startForegroundService(intent);else startService(intent);}catch(Exception e){FLog.e(TAG,"Error toggling torch",e);showTorchErrorToast();}finally{moveTaskToBack(true);finish();}}
    private void showTorchErrorToast(){String[] m=getResources().getStringArray(R.array.torch_error_messages);Toast.makeText(this,m[new Random().nextInt(m.length)],Toast.LENGTH_LONG).show();}
    @Override protected void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==GUEST_PERMISSION_REQUEST&&hasGuestPermissions())loadGuestPublisher();else if(requestCode==GUEST_PERMISSION_REQUEST)finish();}
    @Override protected void onStop(){super.onStop();if(!guestMode)finish();}
    @Override protected void onPause(){super.onPause();if(!guestMode)moveTaskToBack(true);}
    @Override protected void onDestroy(){if(guestWebView!=null){guestWebView.stopLoading();guestWebView.destroy();}super.onDestroy();}
}
