package com.fadcam.production;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Professional producer switcher using the real production canvas and real source transports. */
public final class ProductionControlRoomDialog extends Dialog {
    public interface Listener {
        void onStateChanged(@NonNull ProductionControlState state, boolean applyToProgram);
        void onCameraSlotChanged(int slot, @NonNull String streamUrl);
        void onCloseRequested();
    }

    private static final int BG=Color.rgb(9,10,11), PANEL=Color.rgb(27,28,30), ALT=Color.rgb(40,42,45);
    private static final int RED=Color.rgb(220,42,48), DARK_RED=Color.rgb(74,19,21), TEXT=Color.WHITE;
    private static final int MUTED=Color.rgb(175,178,183), BLUE=Color.rgb(85,165,245), GREEN=Color.rgb(90,220,120);

    private final Listener listener;
    private ProductionControlState state;
    private final FrameLayout liveCanvas;
    private final ViewGroup originalParent;
    private final ViewGroup.LayoutParams originalLayoutParams;
    private final int originalIndex;
    private FrameLayout liveMonitor;
    private ProductionGuestWebView guestReceiver;
    private ExoPlayer videoPlayer;
    private ProductionAudioDucker videoDucker;
    private boolean canvasAttached;
    private TextView previewValue, previewDetail, tallyValue, transitionValue, durationValue;
    private MaterialButton autoButton, takeButton, liveButton;

    public ProductionControlRoomDialog(@NonNull Context context, @NonNull ProductionControlState initialState,
                                       @Nullable FrameLayout liveCanvas, @Nullable ViewGroup originalParent,
                                       @NonNull Listener listener) {
        super(context);
        this.state=initialState; this.liveCanvas=liveCanvas; this.originalParent=originalParent;
        this.originalLayoutParams=liveCanvas==null?null:liveCanvas.getLayoutParams();
        this.originalIndex=originalParent==null||liveCanvas==null?-1:originalParent.indexOfChild(liveCanvas);
        this.listener=listener;
    }

    @Override protected void onCreate(@Nullable android.os.Bundle saved) {
        super.onCreate(saved); requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(buildContent());
        setCanceledOnTouchOutside(false); attachLiveCanvas(); syncGuestReceiver();
    }

    @Override protected void onStart() {
        super.onStart(); Window w=getWindow();
        if(w!=null){w.setBackgroundDrawableResource(android.R.color.transparent);w.setDimAmount(.78f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);}
    }

    private View buildContent(){
        ScrollView scroll=new ScrollView(getContext()); scroll.setFillViewport(true);
        LinearLayout root=column(BG); root.setTag("fadcam-production-root"); root.setPadding(dp(10),dp(10),dp(10),dp(18));
        LinearLayout header=row(); TextView title=text("PRODUCTION CONTROL ROOM",17,TEXT); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(46),1));
        liveButton=button(ProductionStreamingController.isLive(getContext())?"ON AIR":"LIVE",true); liveButton.setOnClickListener(v->toggleLive());
        header.addView(liveButton,new LinearLayout.LayoutParams(dp(78),dp(46)));
        MaterialButton cockpit=button("⋮",true); cockpit.setOnClickListener(v->new ProductionCockpitDialog(getContext()).show()); header.addView(cockpit,new LinearLayout.LayoutParams(dp(50),dp(46)));
        TextView live=text("● LIVE CONTROL",10,RED); live.setGravity(Gravity.CENTER); header.addView(live,new LinearLayout.LayoutParams(dp(92),dp(46)));
        MaterialButton close=button("×",true); close.setOnClickListener(v->{listener.onCloseRequested();dismiss();}); header.addView(close,new LinearLayout.LayoutParams(dp(46),dp(46))); root.addView(header);

        LinearLayout status=row(); status.setPadding(dp(8),dp(5),dp(8),dp(5)); status.setBackground(round(PANEL,12));
        tallyValue=text("ON AIR • "+programLabel(),11,TEXT); status.addView(tallyValue,new LinearLayout.LayoutParams(0,dp(36),1));
        TextView ready=text(ProductionStreamingController.isLive(getContext())?"LIVE":"READY",10,ProductionStreamingController.isLive(getContext())?RED:GREEN); ready.setGravity(Gravity.CENTER); status.addView(ready,new LinearLayout.LayoutParams(dp(58),dp(36))); root.addView(status,new LinearLayout.LayoutParams(-1,dp(46)));

        root.addView(section("PROGRAM / PREVIEW"));
        liveMonitor=new FrameLayout(getContext()); liveMonitor.setContentDescription("Live Program monitor"); liveMonitor.setBackground(round(Color.BLACK,10));
        root.addView(liveMonitor,new LinearLayout.LayoutParams(-1,dp(220)));
        LinearLayout labels=row(); labels.addView(monitorLabel("PROGRAM • TALLY",true),new LinearLayout.LayoutParams(0,dp(70),1)); labels.addView(space(dp(8)),new LinearLayout.LayoutParams(dp(8),1)); labels.addView(monitorLabel("PREVIEW • READY",false),new LinearLayout.LayoutParams(0,dp(70),1)); root.addView(labels);

        root.addView(section("MULTIVIEW • CAMERA 1–6 + VIDEO")); root.addView(buildMultiview());
        root.addView(section("SCENE BANK • PREVIEW ONLY")); root.addView(buildSceneBank());

        LinearLayout sw=row(); sw.setPadding(0,dp(7),0,dp(4)); MaterialButton cut=button("CUT • INSTANT",true); cut.setOnClickListener(v->cutNow()); sw.addView(cut,new LinearLayout.LayoutParams(0,dp(54),1)); sw.addView(space(dp(7)),new LinearLayout.LayoutParams(dp(7),1));
        autoButton=button("AUTO",true); autoButton.setOnClickListener(v->autoTake()); sw.addView(autoButton,new LinearLayout.LayoutParams(0,dp(54),1)); sw.addView(space(dp(7)),new LinearLayout.LayoutParams(dp(7),1));
        takeButton=button("TAKE",true); takeButton.setOnClickListener(v->takeNow()); sw.addView(takeButton,new LinearLayout.LayoutParams(0,dp(54),1)); root.addView(sw);

        LinearLayout tr=row(); transitionValue=text("TRANSITION • "+state.getTransition().getTitle(),9,TEXT); tr.addView(transitionValue,new LinearLayout.LayoutParams(0,dp(38),1));
        for(ProductionControlState.Transition t:ProductionControlState.Transition.values()){MaterialButton b=button(t.getTitle().toUpperCase(Locale.US),t==state.getTransition());b.setTextSize(8);b.setOnClickListener(v->{state=state.withTransition(t);state.save(getContext());transitionValue.setText("TRANSITION • "+t.getTitle());});tr.addView(b,new LinearLayout.LayoutParams(dp(72),dp(38)));} root.addView(tr);
        LinearLayout dur=row(); durationValue=text("AUTO DURATION • "+state.getTransitionDurationMs()+" ms",9,MUTED);dur.addView(durationValue,new LinearLayout.LayoutParams(dp(132),dp(40)));SeekBar bar=new SeekBar(getContext());bar.setMax(2900);bar.setProgress(Math.max(0,state.getTransitionDurationMs()-100));bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean u){int ms=ProductionControlState.clampDuration(p+100);state=state.withDuration(ms);durationValue.setText("AUTO DURATION • "+ms+" ms");}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){state.save(getContext());}});dur.addView(bar,new LinearLayout.LayoutParams(0,dp(40),1));root.addView(dur);
        TextView hint=text("Preview never changes Program. Camera invites are unique and one-use. VIDEO loads real storage media and is ready for CUT/TAKE/AUTO.",8,MUTED);hint.setPadding(dp(6),dp(7),dp(6),0);root.addView(hint);
        scroll.addView(root); return scroll;
    }

    private TextView monitorLabel(String label,boolean program){
        LinearLayout card=column();card.setPadding(dp(9),dp(7),dp(9),dp(7));card.setBackground(round(program?DARK_RED:ALT,12));card.addView(text(label,8,program?RED:BLUE));
        TextView value=text(program?programLabel():previewLabel(),15,TEXT);value.setTypeface(Typeface.DEFAULT,Typeface.BOLD);if(program)tallyValue=value;else previewValue=value;card.addView(value,new LinearLayout.LayoutParams(-1,0,1));
        if(!program){previewDetail=text(previewDetailLabel(),8,MUTED);card.addView(previewDetail);}return card;
    }

    private GridLayout buildMultiview(){
        GridLayout grid=new GridLayout(getContext());grid.setColumnCount(3);grid.setRowCount(3);
        for(int i=1;i<=6;i++){final int slot=i;ProductionCameraSlot c=ProductionCameraSlotManager.get(getContext(),slot);TextView tile=cameraTile(c);GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=0;lp.height=dp(68);lp.columnSpec=GridLayout.spec((i-1)%3,1f);lp.rowSpec=GridLayout.spec((i-1)/3,1f);lp.setMargins(dp(3),dp(3),dp(3),dp(3));grid.addView(tile,lp);}
        TextView video=sourceTile("VIDEO\nLOAD FROM STORAGE",false);video.setOnClickListener(v->showVideoPicker());addGrid(grid,video,0,2);
        TextView program=sourceTile("PROGRAM",true);addGrid(grid,program,1,2);TextView preview=sourceTile("PREVIEW",false);addGrid(grid,preview,2,2);return grid;
    }

    private void addGrid(GridLayout g,View v,int col,int row){GridLayout.LayoutParams lp=new GridLayout.LayoutParams();lp.width=0;lp.height=dp(68);lp.columnSpec=GridLayout.spec(col,1f);lp.rowSpec=GridLayout.spec(row,1f);lp.setMargins(dp(3),dp(3),dp(3),dp(3));g.addView(v,lp);}

    private TextView cameraTile(@NonNull ProductionCameraSlot c){String name=c.getGuestName().isEmpty()?"VISITOR "+c.getSlot():c.getGuestName();TextView t=text("CAMERA "+c.getSlot()+"\n"+name+"\n"+c.getStatus().name(),8,c.getStatus()==ProductionCameraSlot.Status.CONNECTED?GREEN:TEXT);t.setGravity(Gravity.CENTER);t.setBackground(round(c.getSlot()==state.getPreviewCameraSlot()&&state.getPreviewScene()==ProductionScene.CAMERA?DARK_RED:PANEL,8));t.setContentDescription("Camera "+c.getSlot()+" guest invite");t.setOnClickListener(v->showCameraInvite(c.getSlot()));return t;}
    private TextView sourceTile(String name,boolean program){TextView t=text(name,9,TEXT);t.setGravity(Gravity.CENTER);t.setBackground(round(program?DARK_RED:PANEL,8));return t;}

    private View buildSceneBank(){HorizontalScrollView scroll=new HorizontalScrollView(getContext());scroll.setHorizontalScrollBarEnabled(false);LinearLayout bank=row();for(ProductionScene s:ProductionScene.values()){MaterialButton b=button(s.getTitle().toUpperCase(Locale.US),s==state.getPreviewScene());b.setTextSize(8);b.setOnClickListener(v->{state=state.withPreview(s);state.save(getContext());refreshValues();});bank.addView(b,new LinearLayout.LayoutParams(dp(110),dp(54)));}scroll.addView(bank);return scroll;}

    private void showCameraInvite(int slot){ProductionCameraSlot c=ProductionCameraSlotManager.createInvite(getContext(),slot,"");String link=ProductionCameraSlotManager.inviteLink(getContext(),slot);String fallback=ProductionCameraSlotManager.vdoPushLink(getContext(),slot);EditText name=new EditText(getContext());name.setHint("Guest / visitor name (optional)");name.setText(c.getGuestName());TextView linkView=text(link,8,MUTED);linkView.setPadding(0,dp(8),0,dp(8));LinearLayout box=column();box.addView(name);box.addView(linkView);new MaterialAlertDialogBuilder(getContext()).setTitle("CAMERA "+slot+" • GUEST INVITE").setMessage("One-use FadCam app link. If the guest has FadCam installed it opens Camera "+slot+" directly. Browser fallback is also supplied.").setView(box).setPositiveButton("SHARE",(d,w)->shareInvite(slot,name.getText().toString(),link,fallback)).setNeutralButton("USE AS PREVIEW",(d,w)->{ProductionCameraSlotManager.markInvited(getContext(),slot,name.getText().toString());state=state.withPreviewCameraSlot(slot);state.save(getContext());listener.onCameraSlotChanged(slot,"");refreshValues();}).setNegativeButton("CLOSE",null).show();}

    private void shareInvite(int slot,String guest,String link,String fallback){ProductionCameraSlotManager.markInvited(getContext(),slot,guest);ClipboardManager cb=(ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);if(cb!=null)cb.setPrimaryClip(ClipData.newPlainText("FadCam Camera "+slot,link));Intent share=new Intent(Intent.ACTION_SEND);share.setType("text/plain");share.putExtra(Intent.EXTRA_TEXT,"Join FadCam Production Camera "+slot+"\n\n"+link+"\n\nBrowser fallback:\n"+fallback);try{getContext().startActivity(Intent.createChooser(share,"Invite Camera "+slot));}catch(Exception ignored){Toast.makeText(getContext(),"Guest link copied",Toast.LENGTH_SHORT).show();}}

    private void showVideoPicker(){Uri collection=MediaStore.Video.Media.EXTERNAL_CONTENT_URI;String[] p={MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.DURATION};List<Uri> uris=new ArrayList<>();List<String> labels=new ArrayList<>();try(Cursor c=getContext().getContentResolver().query(collection,p,null,null,MediaStore.Video.Media.DATE_ADDED+" DESC")){if(c!=null){int n=0;while(c.moveToNext()&&n++<30){long id=c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media._ID));String name=c.getString(c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME));long ms=c.getLong(c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION));uris.add(Uri.withAppendedPath(collection,String.valueOf(id)));labels.add(name+" • "+formatDuration(ms));}}}catch(Exception e){Toast.makeText(getContext(),"Unable to read device videos",Toast.LENGTH_LONG).show();return;}if(uris.isEmpty()){Toast.makeText(getContext(),"No videos found in storage",Toast.LENGTH_SHORT).show();return;}new MaterialAlertDialogBuilder(getContext()).setTitle("LOAD VIDEO • STORAGE").setItems(labels.toArray(new String[0]),(d,w)->loadVideo(uris.get(w))).setNegativeButton("CANCEL",null).show();}

    private void loadVideo(@NonNull Uri uri){PlayerView pv=findPlayerView(liveCanvas);if(pv==null){Toast.makeText(getContext(),"Production player is unavailable",Toast.LENGTH_LONG).show();return;}if(videoPlayer!=null)videoPlayer.release();videoPlayer=new ExoPlayer.Builder(getContext()).build();videoPlayer.setMediaItem(MediaItem.fromUri(uri));videoPlayer.prepare();videoPlayer.setPlayWhenReady(true);pv.setPlayer(videoPlayer);if(videoDucker==null)videoDucker=new ProductionAudioDucker();videoDucker.start();videoDucker.attach(videoPlayer);state=state.withPreview(ProductionScene.VIDEO);state.save(getContext());refreshValues();Toast.makeText(getContext(),"VIDEO LOADED • READY FOR TAKE",Toast.LENGTH_SHORT).show();}
    private PlayerView findPlayerView(View v){if(v instanceof PlayerView)return(PlayerView)v;if(!(v instanceof ViewGroup))return null;ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){PlayerView p=findPlayerView(g.getChildAt(i));if(p!=null)return p;}return null;}
    private String formatDuration(long ms){long s=Math.max(0,ms/1000);return String.format(Locale.US,"%02d:%02d",s/60,s%60);}

    private void syncGuestReceiver(){if(liveCanvas==null)return;boolean needs=state.getProgramScene()!=ProductionScene.VIDEO;if(!needs){if(guestReceiver!=null)guestReceiver.hide();return;}if(guestReceiver==null)guestReceiver=ProductionGuestWebView.obtain(getContext(),liveCanvas);guestReceiver.applyScene(state.getProgramScene());guestReceiver.loadSlot(state.getProgramCameraSlot());}
    private void toggleLive(){try{if(ProductionStreamingController.isLive(getContext()))ProductionStreamingController.stop(getContext());else ProductionStreamingController.start(getContext());liveButton.setText(ProductionStreamingController.isLive(getContext())?"ON AIR":"LIVE");}catch(Exception e){Toast.makeText(getContext(),e.getMessage(),Toast.LENGTH_LONG).show();}}
    private void cutNow(){state=state.take().withTransition(ProductionControlState.Transition.CUT);state.save(getContext());syncGuestReceiver();listener.onStateChanged(state,true);refreshValues();}
    private void takeNow(){state=state.take();state.save(getContext());syncGuestReceiver();listener.onStateChanged(state,true);refreshValues();}
    private void autoTake(){if(autoButton!=null)autoButton.setEnabled(false);if(takeButton!=null)takeButton.setEnabled(false);if(state.getTransition()==ProductionControlState.Transition.CUT){takeNow();reenable();return;}getWindow().getDecorView().postDelayed(()->{if(isShowing())takeNow();reenable();},state.getTransitionDurationMs());}
    private void reenable(){if(autoButton!=null)autoButton.setEnabled(true);if(takeButton!=null)takeButton.setEnabled(true);}

    private String programLabel(){return state.getProgramScene()==ProductionScene.CAMERA?"Camera "+state.getProgramCameraSlot():state.getProgramScene().getTitle();}
    private String previewLabel(){return state.getPreviewScene()==ProductionScene.CAMERA?"Camera "+state.getPreviewCameraSlot():state.getPreviewScene().getTitle();}
    private String previewDetailLabel(){ProductionCameraSlot c=ProductionCameraSlotManager.get(getContext(),state.getPreviewCameraSlot());return state.getPreviewScene()==ProductionScene.CAMERA?c.getStatus().name()+" • "+(c.getGuestName().isEmpty()?"visitor slot":c.getGuestName()):state.getPreviewScene().getDescription();}
    private void refreshValues(){if(previewValue!=null)previewValue.setText(previewLabel());if(previewDetail!=null)previewDetail.setText(previewDetailLabel());if(tallyValue!=null)tallyValue.setText("ON AIR • "+programLabel());}

    private void attachLiveCanvas(){if(liveCanvas==null||originalParent==null||liveCanvas.getParent()!=originalParent)return;View root=getWindow().getDecorView().findViewWithTag("fadcam-production-root");if(!(root instanceof ViewGroup))return;originalParent.removeView(liveCanvas);((ViewGroup)root).addView(liveCanvas,2,new LinearLayout.LayoutParams(-1,dp(220)));canvasAttached=true;}
    @Override public void dismiss(){if(guestReceiver!=null)guestReceiver.hide();restoreLiveCanvas();super.dismiss();}
    private void restoreLiveCanvas(){if(!canvasAttached||liveCanvas==null||originalParent==null)return;if(liveCanvas.getParent() instanceof ViewGroup)((ViewGroup)liveCanvas.getParent()).removeView(liveCanvas);int index=originalIndex<0?originalParent.getChildCount():Math.min(originalIndex,originalParent.getChildCount());originalParent.addView(liveCanvas,index,originalLayoutParams);canvasAttached=false;}

    private LinearLayout row(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private LinearLayout column(){return column(Color.TRANSPARENT);}
    private LinearLayout column(int bg){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.VERTICAL);if(bg!=Color.TRANSPARENT)v.setBackgroundColor(bg);return v;}
    private TextView text(String s,float size,int color){TextView v=new TextView(getContext());v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
    private MaterialButton button(String s,boolean filled){MaterialButton b=new MaterialButton(getContext());b.setText(s);b.setTextColor(TEXT);b.setTextSize(10);b.setMinHeight(0);b.setAllCaps(false);b.setBackground(round(filled?RED:ALT,12));return b;}
    private TextView section(String s){TextView v=text(s,10,MUTED);v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(dp(4),dp(8),dp(4),dp(4));return v;}
    private View space(int w){View v=new View(getContext());v.setLayoutParams(new LinearLayout.LayoutParams(w,1));return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int n){return Math.round(n*getContext().getResources().getDisplayMetrics().density);}
}
