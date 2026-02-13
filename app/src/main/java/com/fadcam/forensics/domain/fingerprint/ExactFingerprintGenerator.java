package com.fadcam.forensics.domain.fingerprint;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.util.Log;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;

public final class ExactFingerprintGenerator {

    private static final String TAG = "ExactFingerprintGen";
    private static final int SAMPLE_SIZE = 64 * 1024;

    private ExactFingerprintGenerator() {
    }

    public static String compute(ContentResolver resolver, Uri uri, long sizeHint) {
        if (resolver == null || uri == null) {
            return null;
        }

        try (AssetFileDescriptor afd = resolver.openAssetFileDescriptor(uri, "r")) {
            if (afd == null) {
                return null;
            }

            long length = afd.getLength();
            if (length <= 0) {
                length = sizeHint;
            }

            try (FileInputStream fis = afd.createInputStream(); FileChannel channel = fis.getChannel()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(ByteBuffer.allocate(8).putLong(Math.max(length, 0L)).array());

                long[] offsets = buildOffsets(length);
                byte[] buffer = new byte[SAMPLE_SIZE];

                for (long offset : offsets) {
                    int read = readSample(channel, buffer, offset);
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }

                return toHex(digest.digest());
            }
        } catch (Exception e) {
            Log.w(TAG, "compute failed for " + uri, e);
            return null;
        }
    }

    private static long[] buildOffsets(long length) {
        if (length <= SAMPLE_SIZE) {
            return new long[]{0L};
        }
        long mid = Math.max(0L, (length / 2L) - (SAMPLE_SIZE / 2L));
        long end = Math.max(0L, length - SAMPLE_SIZE);
        return new long[]{0L, mid, end};
    }

    private static int readSample(FileChannel channel, byte[] buffer, long offset) throws Exception {
        channel.position(Math.max(0L, offset));
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.clear();
        int read = channel.read(bb);
        return Math.max(read, 0);
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
