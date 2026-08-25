package com.fadcam.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.ServiceStartPolicy;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Functional home for every feature that previously displayed "Coming Soon". */
public class ProductionToolsFragment extends Fragment implements SensorEventListener {
    private static final String ARG_FEATURE = "feature";
    private static final int REQ_AUDIO = 4101;
    private static final int REQ_LOCATION = 4102;

    private SensorManager sensors;
    private Sensor accelerometer, magnetometer, gyroscope, stepCounter;
    private final float[] accel = new float[3], mag = new float[3];
    private boolean haveAccel, haveMag;
    private TextView liveValue;
    private AudioRecord audioRecord;
    private ExecutorService audioExecutor;
    private volatile boolean audioRunning;
    private boolean audioArmed;
    private float noiseThreshold = 65f;
    private BroadcastReceiver batteryReceiver;
    private boolean thermalGuardArmed;
    private float thermalLimitC = 45f;
    private LocationManager locationManager;
    private Location lastLocation;
    private LocationListener locationListener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static ProductionToolsFragment newInstance(String feature) {
        ProductionToolsFragment f = new ProductionToolsFragment();
        Bundle b = new Bundle();
        b.putString(ARG_FEATURE, feature);
        f.setArguments(b);
        return f;
    }

    private String feature() {
        return getArguments() == null ? "sensor_dashboard" : getArguments().getString(ARG_FEATURE, "sensor_dashboard");
    }

    @Nullable @Override public View onCreateView(@NonNull android.view.LayoutInflater inflater, @Nullable android.view.ViewGroup container, @Nullable Bundle state) {
        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(30));
        scroll.addView(root);
        root.addView(text(titleFor(feature()), 22, Color.WHITE), lp(-1, -2));
        root.addView(text("Production tool • live controls • settings are saved", 13, 0xFFBDBDBD), lp(-1, -2));
        root.addView(space(10));
        Button back = button("‹  Back to Settings");
        back.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());
        root.addView(back, lp(-1, -2));
        root.addView(space(12));
        buildFeature(root, feature());
        return scroll;
    }

    private void buildFeature(LinearLayout root, String f) {
        switch (f) {
            case "thermal_guardian": buildThermal(root); break;
            case "audio_vision": buildAudioVision(root); break;
            case "scheduled_recording": buildSchedule(root); break;
            case "profiles": buildProfiles(root); break;
            case "sound_meter": buildSoundMeter(root, false); break;
            case "sensor_dashboard": buildSensors(root); break;
            case "speedometer": buildSpeedometer(root); break;
            case "clinometer": buildClinometer(root); break;
            case "compass": buildCompass(root); break;
            case "pedometer": buildPedometer(root); break;
            case "metal_detector": buildMetal(root); break;
            case "parking_marker": buildParking(root); break;
            case "qr_generator": buildQr(root); break;
            default: buildSensors(root);
        }
    }

    private void buildThermal(LinearLayout root) {
        liveValue = metric(root, "Battery temperature: reading…");
        root.addView(text("Thermal Guardian monitors battery temperature and can stop recording before heat becomes unsafe. Android exposes battery temperature rather than a universal internal CPU temperature.", 14, 0xFFCCCCCC), lp(-1, -2));
        SeekBar bar = new SeekBar(requireContext());
        bar.setMax(70); bar.setProgress(45); root.addView(bar, lp(-1, -2));
        TextView limit = text("Stop recording at: 45°C", 13, 0xFFDDDDDD); root.addView(limit, lp(-1, -2));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar s,int p,boolean f){ thermalLimitC=Math.max(35,p); limit.setText(String.format(Locale.US,"Stop recording at: %.0f°C",thermalLimitC)); } public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){} });
        Button arm = button("Enable Thermal Guard"); root.addView(arm, lp(-1,-2));
        arm.setOnClickListener(v -> { thermalGuardArmed=!thermalGuardArmed; arm.setText(thermalGuardArmed?"Thermal Guard ON":"Enable Thermal Guard"); });
        batteryReceiver = new BroadcastReceiver(){ @Override public void onReceive(Context c, Intent i){ int t=i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE); if(t==Integer.MIN_VALUE)return; float celsius=t/10f; if(liveValue!=null)liveValue.setText(String.format(Locale.US,"Battery temperature: %.1f°C",celsius)); if(thermalGuardArmed&&celsius>=thermalLimitC) stopRecordingService(); }};
        requireContext().registerReceiver(batteryReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void buildAudioVision(LinearLayout root) {
        buildSoundMeter(root, true);
        root.addView(text("When armed, a sustained sound above the threshold starts the FadCam recording service. Stop it from the main recording controls.",13,0xFFBDBDBD),lp(-1,-2));
    }

    private void buildSoundMeter(LinearLayout root, boolean vision) {
        liveValue=metric(root,"Sound level: — dB");
        root.addView(text("Microphone level meter",14,0xFFBDBDBD),lp(-1,-2));
        SeekBar bar=new SeekBar(requireContext()); bar.setMax(100); bar.setProgress((int)noiseThreshold); root.addView(bar,lp(-1,-2));
        TextView threshold=text(String.format(Locale.US,"Trigger threshold: %.0f dB",noiseThreshold),13,0xFFDDDDDD); root.addView(threshold,lp(-1,-2));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean f){noiseThreshold=Math.max(20,p);threshold.setText(String.format(Locale.US,"Trigger threshold: %.0f dB",noiseThreshold));}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
        Button start=button(vision?"Arm Audio Vision":"Start Sound Meter");root.addView(start,lp(-1,-2));
        start.setOnClickListener(v->{ if(ContextCompat.checkSelfPermission(requireContext(),Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_AUDIO);return;} audioArmed=vision; if(audioRunning){audioRunning=false;start.setText(vision?"Arm Audio Vision":"Start Sound Meter");}else{startAudio();start.setText(vision?"Audio Vision Armed — Stop":"Stop Sound Meter");} });
    }

    private void startAudio(){ if(audioRunning)return; audioRunning=true; int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT); if(min<1024)min=4096; final int buffer=min*2; audioExecutor=Executors.newSingleThreadExecutor(); audioExecutor.execute(()->{try{audioRecord=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,buffer);audioRecord.startRecording();short[] data=new short[min];long loudSince=0;while(audioRunning){int n=audioRecord.read(data,0,data.length);if(n<=0)continue;double sum=0;for(int i=0;i<n;i++)sum+=(double)data[i]*data[i];double rms=Math.sqrt(sum/n);final float db=(float)(20*Math.log10(Math.max(rms,1)/32768.0)+90);ui.post(()->{if(liveValue!=null)liveValue.setText(String.format(Locale.US,"Sound level: %.1f dB",db));});if(audioArmed&&db>=noiseThreshold){if(loudSince==0)loudSince=System.currentTimeMillis();if(System.currentTimeMillis()-loudSince>=700){startRecordingService();audioArmed=false;}}else loudSince=0;}}catch(Exception e){ui.post(()->{if(getContext()!=null)android.widget.Toast.makeText(requireContext(),"Microphone unavailable: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show();});}finally{if(audioRecord!=null){try{audioRecord.stop();}catch(Exception ignored){}audioRecord.release();audioRecord=null;}}});}

    private void buildSchedule(LinearLayout root){
        root.addView(text("Create a one-time recording alarm. The receiver starts the recording service and automatically stops it after the selected duration.",14,0xFFCCCCCC),lp(-1,-2));
        EditText date=edit("Start time (yyyy-MM-dd HH:mm)"); root.addView(date,lp(-1,-2));
        EditText mins=edit("Duration in minutes (default 30)"); mins.setInputType(InputType.TYPE_CLASS_NUMBER);root.addView(mins,lp(-1,-2));
        Button schedule=button("Schedule Recording");root.addView(schedule,lp(-1,-2));
        TextView pending=metric(root,"Schedules are stored locally on this device.");
        schedule.setOnClickListener(v->{try{SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.US);long when=fmt.parse(date.getText().toString().trim()).getTime();if(when<=System.currentTimeMillis())throw new IllegalArgumentException("Choose a future time");String value=mins.getText().toString().trim();long duration=1000L*60L*Math.max(1,Long.parseLong(value.isEmpty()?"30":value));ProductionScheduleReceiver.schedule(requireContext(),when,duration);pending.setText("Scheduled for "+date.getText());android.widget.Toast.makeText(requireContext(),"Recording scheduled",android.widget.Toast.LENGTH_SHORT).show();}catch(Exception e){android.widget.Toast.makeText(requireContext(),"Invalid schedule: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}});
    }

    private void buildProfiles(LinearLayout root){
        EditText name=edit("Profile name");root.addView(name,lp(-1,-2));
        Spinner quality=new Spinner(requireContext());quality.setAdapter(new ArrayAdapter<>(requireContext(),android.R.layout.simple_spinner_dropdown_item,new String[]{"FHD • 30fps","FHD • 60fps","HD • 30fps","SD • 30fps"}));root.addView(quality,lp(-1,-2));
        Spinner camera=new Spinner(requireContext());camera.setAdapter(new ArrayAdapter<>(requireContext(),android.R.layout.simple_spinner_dropdown_item,new String[]{"Back camera","Front camera"}));root.addView(camera,lp(-1,-2));
        Button save=button("Save Profile");root.addView(save,lp(-1,-2));
        LinearLayout list=new LinearLayout(requireContext());list.setOrientation(LinearLayout.VERTICAL);root.addView(list,lp(-1,-2));
        SharedPreferences p=prefs();
        Runnable refresh=()->{list.removeAllViews();for(Map.Entry<String,?> e:p.getAll().entrySet()){if(!e.getKey().startsWith("production_profile_"))continue;String[] vals=String.valueOf(e.getValue()).split("\\|",-1);LinearLayout row=new LinearLayout(requireContext());row.setGravity(Gravity.CENTER_VERTICAL);TextView t=text(e.getKey().substring("production_profile_".length())+" • "+(vals.length>0?vals[0]:""),14,0xFFFFFFFF);row.addView(t,new LinearLayout.LayoutParams(0,-2,1));Button load=button("Load");row.addView(load,new LinearLayout.LayoutParams(dp(90),-2));Button del=button("Delete");row.addView(del,new LinearLayout.LayoutParams(dp(90),-2));load.setOnClickListener(v->{if(vals.length>0){int q=indexOf(quality,vals[0]);if(q>=0)quality.setSelection(q);}if(vals.length>1){int c=indexOf(camera,vals[1]);if(c>=0)camera.setSelection(c);}name.setText(e.getKey().substring("production_profile_".length()));});del.setOnClickListener(v->{p.edit().remove(e.getKey()).apply();refresh.run();});list.addView(row);}};
        save.setOnClickListener(v->{String n=name.getText().toString().trim();if(n.isEmpty()){android.widget.Toast.makeText(requireContext(),"Enter a profile name",android.widget.Toast.LENGTH_SHORT).show();return;}p.edit().putString("production_profile_"+n,quality.getSelectedItem().toString()+"|"+camera.getSelectedItem().toString()).apply();refresh.run();android.widget.Toast.makeText(requireContext(),"Profile saved",android.widget.Toast.LENGTH_SHORT).show();});refresh.run();
    }

    private void buildSensors(LinearLayout root){
        liveValue=metric(root,"Waiting for sensor data…");sensors=(SensorManager)requireContext().getSystemService(Context.SENSOR_SERVICE);accelerometer=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);magnetometer=sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);gyroscope=sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);stepCounter=sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        root.addView(text("This dashboard reads available hardware in real time. Missing sensors are reported instead of faking values.",14,0xFFCCCCCC),lp(-1,-2));
        Button refresh=button("Refresh sensor availability");root.addView(refresh,lp(-1,-2));refresh.setOnClickListener(v->showSensorSummary());showSensorSummary();
    }
    private void showSensorSummary(){if(liveValue==null||sensors==null)return;StringBuilder b=new StringBuilder();b.append("Accelerometer: ").append(accelerometer!=null?"available":"not available").append("\nMagnetometer: ").append(magnetometer!=null?"available":"not available").append("\nGyroscope: ").append(gyroscope!=null?"available":"not available").append("\nStep counter: ").append(stepCounter!=null?"available":"not available");liveValue.setText(b.toString());}

    private void buildSpeedometer(LinearLayout root){liveValue=metric(root,"Speed: 0.0 km/h");requestLocation();}
    private void buildCompass(LinearLayout root){liveValue=metric(root,"Heading: —°");registerSensors();}
    private void buildClinometer(LinearLayout root){liveValue=metric(root,"Tilt: —°");registerSensors();}
    private void buildPedometer(LinearLayout root){liveValue=metric(root,"Steps: —");registerSensors();}
    private void buildMetal(LinearLayout root){liveValue=metric(root,"Magnetic field: — µT");registerSensors();}

    private void registerSensors(){if(sensors==null)sensors=(SensorManager)requireContext().getSystemService(Context.SENSOR_SERVICE);if(accelerometer==null)accelerometer=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);if(magnetometer==null)magnetometer=sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);if(gyroscope==null)gyroscope=sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);if(stepCounter==null)stepCounter=sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);for(Sensor s:new Sensor[]{accelerometer,magnetometer,gyroscope,stepCounter})if(s!=null)sensors.registerListener(this,s,SensorManager.SENSOR_DELAY_UI);}

    private void buildParking(LinearLayout root){liveValue=metric(root,"No parking location saved");requestLocation();Button save=button("Save Current Parking Spot");root.addView(save,lp(-1,-2));Button navigate=button("Navigate to Saved Spot");root.addView(navigate,lp(-1,-2));save.setOnClickListener(v->{if(lastLocation==null){android.widget.Toast.makeText(requireContext(),"Waiting for GPS fix",android.widget.Toast.LENGTH_SHORT).show();return;}prefs().edit().putString("parking_lat",Double.toString(lastLocation.getLatitude())).putString("parking_lon",Double.toString(lastLocation.getLongitude())).apply();liveValue.setText(String.format(Locale.US,"Saved: %.6f, %.6f",lastLocation.getLatitude(),lastLocation.getLongitude()));});navigate.setOnClickListener(v->{String la=prefs().getString("parking_lat",null),lo=prefs().getString("parking_lon",null);if(la==null||lo==null){android.widget.Toast.makeText(requireContext(),"No saved parking spot",android.widget.Toast.LENGTH_SHORT).show();return;}Intent i=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("geo:"+la+","+lo+"?q="+la+","+lo+"(Parking)"));startActivity(i);});}

    private void buildQr(LinearLayout root){EditText input=edit("Text, URL, contact or production link");root.addView(input,lp(-1,-2));Button gen=button("Generate QR Code");root.addView(gen,lp(-1,-2));ImageView image=new ImageView(requireContext());image.setAdjustViewBounds(true);root.addView(image,lp(-1,dp(280)));gen.setOnClickListener(v->{String value=input.getText().toString().trim();if(value.isEmpty()){android.widget.Toast.makeText(requireContext(),"Enter content first",android.widget.Toast.LENGTH_SHORT).show();return;}try{BitMatrix m=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,700,700);Bitmap b=Bitmap.createBitmap(700,700,Bitmap.Config.ARGB_8888);for(int x=0;x<700;x++)for(int y=0;y<700;y++)b.setPixel(x,y,m.get(x,y)?Color.BLACK:Color.WHITE);image.setImageBitmap(b);}catch(Exception e){android.widget.Toast.makeText(requireContext(),"QR generation failed",android.widget.Toast.LENGTH_SHORT).show();}});}

    private void requestLocation(){if(ContextCompat.checkSelfPermission(requireContext(),Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_LOCATION);return;}locationManager=(LocationManager)requireContext().getSystemService(Context.LOCATION_SERVICE);locationListener=new LocationListener(){@Override public void onLocationChanged(@NonNull Location l){lastLocation=l;if("speedometer".equals(feature())&&liveValue!=null)liveValue.setText(String.format(Locale.US,"Speed: %.1f km/h",l.getSpeed()*3.6));if("parking_marker".equals(feature())&&liveValue!=null)liveValue.setText(String.format(Locale.US,"GPS: %.6f, %.6f",l.getLatitude(),l.getLongitude()));}};try{locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,500,0.5f,locationListener);Location l=locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);if(l!=null)lastLocation=l;}catch(SecurityException ignored){}}

    @Override public void onSensorChanged(SensorEvent e){if(liveValue==null)return;if(e.sensor.getType()==Sensor.TYPE_ACCELEROMETER){System.arraycopy(e.values,0,accel,0,3);haveAccel=true;if("clinometer".equals(feature())){double tilt=Math.toDegrees(Math.atan2(accel[0],Math.sqrt(accel[1]*accel[1]+accel[2]*accel[2])));liveValue.setText(String.format(Locale.US,"Tilt: %.1f°",tilt));}}else if(e.sensor.getType()==Sensor.TYPE_MAGNETIC_FIELD){System.arraycopy(e.values,0,mag,0,3);haveMag=true;if("metal_detector".equals(feature())){double u=Math.sqrt(e.values[0]*e.values[0]+e.values[1]*e.values[1]+e.values[2]*e.values[2]);liveValue.setText(String.format(Locale.US,"Magnetic field: %.1f µT",u));}}else if(e.sensor.getType()==Sensor.TYPE_STEP_COUNTER&&"pedometer".equals(feature())){liveValue.setText(String.format(Locale.US,"Steps since device boot: %.0f",e.values[0]));}else if(e.sensor.getType()==Sensor.TYPE_GYROSCOPE&&"sensor_dashboard".equals(feature())){liveValue.setText(String.format(Locale.US,"Gyro: %.2f, %.2f, %.2f rad/s",e.values[0],e.values[1],e.values[2]));}if("compass".equals(feature())&&haveAccel&&haveMag){float[] R=new float[9],I=new float[9];if(SensorManager.getRotationMatrix(R,I,accel,mag)){float[] o=new float[3];SensorManager.getOrientation(R,o);float deg=(float)Math.toDegrees(o[0]);if(deg<0)deg+=360;liveValue.setText(String.format(Locale.US,"Heading: %.0f°",deg));}}}
    @Override public void onAccuracyChanged(Sensor s,int a){}

    private void startRecordingService(){try{Intent i=new Intent(requireContext(),RecordingService.class);i.setAction(Constants.INTENT_ACTION_START_RECORDING);ServiceStartPolicy.startRecordingAction(requireContext(),i);android.widget.Toast.makeText(requireContext(),"FadCam recording started",android.widget.Toast.LENGTH_SHORT).show();}catch(Exception e){android.widget.Toast.makeText(requireContext(),"Could not start recording: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}
    private void stopRecordingService(){try{Intent i=new Intent(requireContext(),RecordingService.class);i.setAction(Constants.INTENT_ACTION_STOP_RECORDING);ServiceStartPolicy.startRecordingAction(requireContext(),i);}catch(Exception ignored){}}

    private SharedPreferences prefs(){return requireContext().getSharedPreferences("production_tools",Context.MODE_PRIVATE);}
    private int indexOf(Spinner s,String value){for(int i=0;i<s.getCount();i++)if(value.equals(String.valueOf(s.getItemAtPosition(i))))return i;return -1;}
    private String titleFor(String f){String s=f.replace('_',' ');return s.substring(0,1).toUpperCase(Locale.US)+s.substring(1);}
    private TextView metric(LinearLayout root,String value){TextView t=text(value,20,Color.WHITE);t.setGravity(Gravity.CENTER);t.setPadding(dp(12),dp(20),dp(12),dp(20));t.setBackgroundColor(0xFF171717);root.addView(t,lp(-1,-2));return t;}
    private TextView text(String s,int size,int color){TextView t=new TextView(requireContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(4,4,4,4);return t;}
    private EditText edit(String hint){EditText e=new EditText(requireContext());e.setHint(hint);e.setTextColor(Color.WHITE);e.setHintTextColor(0xFF888888);e.setSingleLine(true);e.setPadding(dp(12),dp(10),dp(12),dp(10));e.setBackgroundColor(0xFF171717);return e;}
    private Button button(String s){Button b=new Button(requireContext());b.setText(s);return b;}
    private View space(int h){return new View(requireContext()){protected void onMeasure(int w,int hs){setMeasuredDimension(1,dp(h));}};}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}

    @Override public void onDestroyView(){super.onDestroyView();if(sensors!=null)sensors.unregisterListener(this);if(locationManager!=null&&locationListener!=null)try{locationManager.removeUpdates(locationListener);}catch(Exception ignored){}if(audioRunning){audioRunning=false;if(audioExecutor!=null)audioExecutor.shutdownNow();}if(batteryReceiver!=null)try{requireContext().unregisterReceiver(batteryReceiver);}catch(Exception ignored){}}
}
