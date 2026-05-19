package com.fadcam.ui.miniapps;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Size;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.RGBLuminanceSource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QR/Barcode Scanner using CameraX + ZXing.
 * Scans all barcode formats, captures frame on decode, saves JPEG + metadata JSON.
 */
public class QRScannerActivity extends AppCompatActivity {

    private static final int RC_CAMERA = 101;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isScanning = new AtomicBoolean(true);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ProcessCameraProvider cameraProvider;
    private ImageView ivTorch;
    private boolean torchOn;
    private ScanParticleView scanParticles;
    private View resultContainer;
    private TextView rt;
    private TextView ht;
    private View btnCopy;
    private View btnAgain;
    private View btnShare;
    private TextView tvFormat;
    private String lastScannedText;
    private String lastScannedFormat;
    private Date lastScannedTime;
    private androidx.camera.core.Camera camera;
    private ActivityResultLauncher<String> galleryLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scanner);
        ivTorch = findViewById(R.id.qr_torch);
        ImageView ivClose = findViewById(R.id.qr_close);
        scanParticles = findViewById(R.id.qr_scan_line);
        resultContainer = findViewById(R.id.qr_result_container);
        rt = findViewById(R.id.qr_result_text);
        ht = findViewById(R.id.qr_hint_text);
        btnCopy = findViewById(R.id.qr_btn_copy);
        btnAgain = findViewById(R.id.qr_btn_scan_again);
        btnShare = findViewById(R.id.qr_btn_share);
        tvFormat = findViewById(R.id.qr_result_format);

        if (ivClose != null) ivClose.setOnClickListener(v -> finish());
        if (ivTorch != null) ivTorch.setOnClickListener(v -> toggleTorch());

        // Gallery button
        View galleryBtn = findViewById(R.id.qr_gallery);
        if (galleryBtn != null) galleryBtn.setOnClickListener(v -> galleryLauncher.launch("image/*"));

        // Register image picker
        galleryLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                this::onGalleryImagePicked);

        View headerBar = findViewById(R.id.qr_header_bar);
        if (headerBar != null) headerBar.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop() + ins.getSystemWindowInsetTop(),
                    v.getPaddingRight(), v.getPaddingBottom());
            return ins;
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, RC_CAMERA);
        } else startCamera();
    }

    @Override public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] g) {
        super.onRequestPermissionsResult(r, p, g);
        if (r == RC_CAMERA && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else { Toast.makeText(this, getString(R.string.qr_scanner_camera_permission_required), Toast.LENGTH_SHORT).show(); finish(); }
    }

    private void startCamera() {
        FLog.i("QRScanner","startCamera");
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
                startScanLineAnimation();
                FLog.i("QRScanner","started");
            } catch (Exception e) {
                FLog.e("QRScanner","Camera error",e);
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        cameraProvider.unbindAll();
        PreviewView pv = findViewById(R.id.qr_preview);
        Preview preview = new Preview.Builder().setTargetResolution(new Size(1280,720)).build();
        preview.setSurfaceProvider(pv.getSurfaceProvider());
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280,720))
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
        analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
        camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
    }

    private void analyzeFrame(@NonNull ImageProxy image) {
        if (!isScanning.get()) { image.close(); return; }
        Result result = decodeImage(image);
        if (result != null) {
            FLog.i("QRScanner","Detected: "+result.getText()+" ("+result.getBarcodeFormat()+")");
            if (isScanning.compareAndSet(true, false)) {
                lastScannedText = result.getText();
                lastScannedFormat = result.getBarcodeFormat().name();
                lastScannedTime = new Date();
                Bitmap frameBitmap = yuvToBitmap(image);
                image.close();
                final String text = result.getText();
                final String format = result.getBarcodeFormat().name();
                mainHandler.post(() -> showScanResult(text, format));
                final Bitmap bmp = frameBitmap;
                cameraExecutor.execute(() -> saveScan(text, format, bmp));
                return;
            }
        }
        image.close();
    }

    /**
     * Converts a YUV_420_888 ImageProxy frame to a Bitmap by:
     * 1. Converting the 3-plane YUV_420_888 to interleaved NV21
     * 2. Using YuvImage.compressToJpeg() for hardware-accelerated JPEG encoding
     * 3. Decoding the JPEG bytes to a Bitmap
     *
     * YUV_420_888 has separate planes (Y, U, V) but YuvImage expects
     * interleaved NV21 format [Y][VU][VU]... Passing raw plane[0] with
     * ImageFormat.NV21 causes native SIGSEGV in Yuv420SpToJpegEncoder.
     */
    private Bitmap yuvToBitmap(@NonNull ImageProxy image) {
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            byte[] nv21 = yuv420888ToNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), 90, out);
            byte[] jpeg = out.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0 && bitmap != null) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                bitmap = rotated;
            }
            return bitmap;
        } catch (Exception e) {
            FLog.e("QRScanner", "yuvToBitmap failed", e);
            return null;
        }
    }

    /**
     * Converts YUV_420_888 (3 separate planes) to NV21 (interleaved).
     * NV21 layout: [Y data for all pixels][VU VU VU... for each 2x2 block]
     */
    private static byte[] yuv420888ToNv21(@NonNull ImageProxy image) {
        int w = image.getWidth();
        int h = image.getHeight();
        ImageProxy.PlaneProxy[] planes = image.getPlanes();

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        // Rewind — decodeImage() may have consumed buffer positions
        yBuffer.rewind();
        uBuffer.rewind();
        vBuffer.rewind();

        int ySize = w * h;
        byte[] nv21 = new byte[ySize * 3 / 2];

        // Copy Y plane (handle row stride if != width)
        int yRowStride = planes[0].getRowStride();
        if (yRowStride == w) {
            yBuffer.get(nv21, 0, ySize);
        } else {
            int yPos = 0;
            for (int row = 0; row < h; row++) {
                yBuffer.position(row * yRowStride);
                yBuffer.get(nv21, yPos, w);
                yPos += w;
            }
        }

        // Interleave V and U into NV21 tail
        int vuPos = ySize;
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();
        int uvH = h / 2;
        int uvW = w / 2;

        for (int row = 0; row < uvH; row++) {
            for (int col = 0; col < uvW; col++) {
                nv21[vuPos++] = vBuffer.get(row * vRowStride + col * vPixelStride);
                nv21[vuPos++] = uBuffer.get(row * uRowStride + col * uPixelStride);
            }
        }

        return nv21;
    }

    private void showScanResult(String text, String format) {
        // Haptic feedback
        View root = findViewById(android.R.id.content);
        if (root != null) root.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        
        // Play beep
        try {
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            android.content.res.AssetFileDescriptor afd = getAssets().openFd("audio/qrscanner_beep.mp3");
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.prepare();
            mp.start();
            mp.setOnCompletionListener(android.media.MediaPlayer::release);
        } catch (Exception e) { FLog.w("QRScanner", "Beep failed", e); }
        
        // Stop particle animation
        if (scanParticles != null) scanParticles.stopAnimation();
        
        // Auto turn off torch after successful scan
        if (torchOn) {
            mainHandler.postDelayed(this::toggleTorch, 100);
        }
        
        // Set format badge
        if (tvFormat != null) {
            String badge = format.replace("_", " ");
            tvFormat.setText(badge);
        }

        // Set result text
        if (rt != null) {
            rt.setText(text);
        }
        
        // Slide-up animation for result
        if (resultContainer != null) {
            resultContainer.setVisibility(View.VISIBLE);
            resultContainer.setTranslationY(400);
            resultContainer.animate()
                    .translationY(0)
                    .setDuration(450)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        
        // Share button
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(shareIntent, null));
            });
        }

        // Copy button
        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Scanned", text));
                Toast.makeText(this, getString(R.string.qr_scanner_copied), Toast.LENGTH_SHORT).show();
            });
        }
        
        // Scan again button with fade-out
        if (btnAgain != null) {
            btnAgain.setOnClickListener(v -> {
                if (resultContainer != null) {
                    resultContainer.animate().alpha(0).setDuration(300).withEndAction(() -> {
                        resultContainer.setVisibility(View.GONE);
                        resultContainer.setAlpha(1f);
                        isScanning.set(true);
                        startScanLineAnimation();
                    }).start();
                }
            });
        }
    }

    private void onGalleryImagePicked(Uri uri) {
        if (uri == null) return;
        FLog.i("QRScanner", "Gallery image picked: " + uri);
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
            if (bitmap == null) {
                Toast.makeText(this, getString(R.string.qr_scanner_could_not_load), Toast.LENGTH_SHORT).show();
                return;
            }
            Result result = decodeBitmap(bitmap);
            if (result != null) {
                String text = result.getText();
                String format = result.getBarcodeFormat().name();
                FLog.i("QRScanner", "Gallery decoded: " + text + " (" + format + ")");
                lastScannedText = text;
                lastScannedFormat = format;
                lastScannedTime = new Date();
                if (isScanning.compareAndSet(true, false)) {
                    isScanning.set(true); // re-enable since we're not stopping camera scanning
                    showScanResult(text, format);
                    final Bitmap bmp = bitmap;
                    final String t = text, f = format;
                    cameraExecutor.execute(() -> saveScan(t, f, bmp));
                }
            } else {
                Toast.makeText(this, getString(R.string.qr_scanner_no_barcode), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            FLog.e("QRScanner", "Gallery decode error", e);
            Toast.makeText(this, getString(R.string.qr_scanner_failed_process), Toast.LENGTH_SHORT).show();
        }
    }

    /** Decodes barcodes from a Bitmap using ZXing RGBLuminanceSource. */
    @Nullable private Result decodeBitmap(@NonNull Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        RGBLuminanceSource src = new RGBLuminanceSource(w, h, pixels);
        Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Arrays.asList(
            BarcodeFormat.QR_CODE, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13,
            BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
            BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.CODE_128,
            BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417));
        MultiFormatReader reader = new MultiFormatReader(); reader.setHints(hints);
        try {
            return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
        } catch (NotFoundException e) { return null; }
    }

    private void saveScan(String text, String format, Bitmap frameBitmap) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File scanDir = getScanDir(ts);
        FLog.i("QRScanner","saveScan: "+scanDir);
        if (scanDir == null || !scanDir.mkdirs()) return;
        File meta = new File(scanDir, "metadata.json");
        try (FileOutputStream fos = new FileOutputStream(meta)) {
            fos.write(String.format(Locale.US,
                "{\"rawValue\":\"%s\",\"format\":\"%s\",\"timestamp\":\"%s\"}",
                escapeJson(text), format, ts).getBytes());
        } catch (Exception ignored) {}
        if (frameBitmap != null) {
            String fileName = "FadCam_MiniApps_QRScanner_" + ts + ".jpg";
            File jpg = new File(scanDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(jpg)) {
                frameBitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos);
                FLog.i("QRScanner", fileName + " saved: " + jpg.length() + " bytes");
            } catch (Exception ignored) {}
        }
        Intent intent = new Intent(Constants.ACTION_RECORDING_COMPLETE);
        intent.putExtra(Constants.EXTRA_RECORDING_SUCCESS, true);
        intent.putExtra(Constants.EXTRA_RECORDING_URI_STRING, Uri.fromFile(scanDir).toString());
        sendBroadcast(intent);
    }

    @Nullable private Result decodeImage(@NonNull ImageProxy image) {
        int rotation = image.getImageInfo().getRotationDegrees();
        int w = image.getWidth(), h = image.getHeight();
        int yStride = image.getPlanes()[0].getRowStride();
        byte[] packed = packYPlane(image);
        FLog.d("QRScanner", "decode: w=" + w + " h=" + h + " stride=" + yStride
                + " rot=" + rotation + " packed=" + packed.length);

        // Physically rotate luminance to match display orientation — required for
        // 1D barcodes. ZXing PlanarYUVLuminanceSource does NOT handle rotation.
        byte[] rotated = rotateLuminance(packed, w, h, rotation);
        int rw = (rotation == 90 || rotation == 270) ? h : w;
        int rh = (rotation == 90 || rotation == 270) ? w : h;

        PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                rotated, rw, rh, 0, 0, rw, rh, false);
        Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Arrays.asList(
            BarcodeFormat.QR_CODE,BarcodeFormat.EAN_8,BarcodeFormat.EAN_13,BarcodeFormat.UPC_A,BarcodeFormat.UPC_E,
            BarcodeFormat.CODE_39,BarcodeFormat.CODE_93,BarcodeFormat.CODE_128,BarcodeFormat.DATA_MATRIX,BarcodeFormat.AZTEC,BarcodeFormat.PDF_417));
        MultiFormatReader reader = new MultiFormatReader(); reader.setHints(hints);
        try {
            Result r = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
            FLog.i("QRScanner","Decoded: " + r.getBarcodeFormat());
            return r;
        } catch (NotFoundException e) {
            FLog.d("QRScanner","Not found in frame");
            return null;
        }
    }

    /** Physically rotates luminance data to match display orientation.
     *  Based on journeyapps/zxing-android-embedded RawImageData.rotateCameraPreview(). */
    private static byte[] rotateLuminance(byte[] data, int w, int h, int rotation) {
        switch (rotation) {
            case 90: {
                byte[] out = new byte[w * h];
                int i = 0;
                for (int x = 0; x < w; x++)
                    for (int y = h - 1; y >= 0; y--)
                        out[i++] = data[y * w + x];
                return out;
            }
            case 180: {
                byte[] out = new byte[w * h];
                int n = w * h;
                for (int j = 0, i = n - 1; j < n; j++, i--)
                    out[i] = data[j];
                return out;
            }
            case 270: {
                byte[] out = new byte[w * h];
                int i = 0;
                for (int x = w - 1; x >= 0; x--)
                    for (int y = 0; y < h; y++)
                        out[i++] = data[y * w + x];
                return out;
            }
            default:
                return data;
        }
    }

    @Nullable private Result tryDecodeRaw(byte[] data, int w, int h) {
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
            Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Arrays.asList(
                BarcodeFormat.QR_CODE,BarcodeFormat.EAN_8,BarcodeFormat.EAN_13,BarcodeFormat.UPC_A,BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_39,BarcodeFormat.CODE_93,BarcodeFormat.CODE_128,BarcodeFormat.DATA_MATRIX,BarcodeFormat.AZTEC,BarcodeFormat.PDF_417));
            MultiFormatReader reader = new MultiFormatReader(); reader.setHints(hints);
            return reader.decodeWithState(new BinaryBitmap(new GlobalHistogramBinarizer(src)));
        } catch (NotFoundException e) {
            FLog.d("QRScanner", "Raw decode attempt also failed");
            return null;
        }
    }

    private static byte[] packYPlane(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy yPlane = image.getPlanes()[0];
        ByteBuffer buf = yPlane.getBuffer();
        int w = image.getWidth(), h = image.getHeight();
        int stride = yPlane.getRowStride();
        byte[] packed = new byte[w * h];
        if (stride == w) {
            buf.get(packed);
        } else {
            int pos = 0;
            for (int row = 0; row < h; row++) {
                buf.position(row * stride);
                buf.get(packed, pos, w);
                pos += w;
            }
        }
        return packed;
    }

    @Nullable private Result tryDecode(byte[] data, int w, int h, int rotation) {
        try {
            // Swap dimensions if rotation is 90/270 (camera sensor vs display orientation)
            if (rotation == 90 || rotation == 270) { int t = w; w = h; h = t; }
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
            Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Arrays.asList(
                BarcodeFormat.QR_CODE,BarcodeFormat.EAN_8,BarcodeFormat.EAN_13,BarcodeFormat.UPC_A,BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_39,BarcodeFormat.CODE_93,BarcodeFormat.CODE_128,BarcodeFormat.DATA_MATRIX,BarcodeFormat.AZTEC,BarcodeFormat.PDF_417));
            MultiFormatReader reader = new MultiFormatReader(); reader.setHints(hints);
            // GlobalHistogramBinarizer is the correct choice for camera frames
            // with 1D barcodes. HybridBinarizer uses local binarization for images
            // > 40px (1920x1920 qualifies), which creates noise that breaks 1D
            // row scanning. Global histogram creates a clean binary image.
            return reader.decodeWithState(new BinaryBitmap(new GlobalHistogramBinarizer(src)));
        } catch (NotFoundException e) { return null; }
    }

    private File getScanDir(String ts) {
        File ext = getExternalFilesDir(null);
        if (ext == null) return null;
        return new File(new File(new File(ext, Constants.RECORDING_DIRECTORY),
                Constants.RECORDING_SUBDIR_MINIAPPS), Constants.RECORDING_SUBDIR_MINIAPPS_QR + File.separator + ts);
    }

    private String escapeJson(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }

    private void startScanLineAnimation() {
        if (scanParticles != null) scanParticles.startAnimation();
    }

    private void toggleTorch() {
        torchOn = !torchOn;
        if (ivTorch != null) {
            ivTorch.setImageResource(torchOn ? R.drawable.ic_flashlight_on : R.drawable.ic_flashlight_off);
        }
        if (camera != null) {
            camera.getCameraControl().enableTorch(torchOn);
        }
    }



    @Override protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (scanParticles != null) scanParticles.stopAnimation();
    }
}
