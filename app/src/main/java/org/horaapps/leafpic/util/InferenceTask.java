package org.horaapps.leafpic.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import org.horaapps.leafpic.ImageNetClasses;
import org.horaapps.leafpic.adapters.MediaAdapter;
import org.horaapps.leafpic.data.Media;
import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;

public class InferenceTask extends AsyncTask<MediaAdapter, Integer, String[]> {
    private MediaAdapter _adapter;

    protected String[] doInBackground(MediaAdapter... adapters) {
        MediaAdapter adapter = adapters[0];
        _adapter = adapter;

        ArrayList<Media> media = adapter.getSelected();
        ArrayList<Bitmap> bitmaps = new ArrayList<>();
        final int dstWidth = 224;
        final int dstHeight = 224;

        for (int i = 0; i < media.size(); i++ ) {
            Media m = media.get(i);

            Bitmap bitmap = Bitmap.createScaledBitmap(
                    BitmapFactory.decodeFile(m.getPath()),
                    dstWidth,
                    dstHeight,
                    false
            );

            bitmaps.add(bitmap);
        }

        Module module = null;

        try {
            module = Module.load(adapter.assetFilePath(adapter.getContext(), "mobilenet.pt"));
        } catch (IOException e) {
            Log.e("LeafPic", "Error reading assets", e);
        }

        // preparing input tensor
        final Tensor inputTensor = bitmapsToFloat32Tensor(bitmaps, dstWidth, dstHeight);

        // inference
        final Tensor outputTensor = module.forward(IValue.from(inputTensor)).toTensor();

        // getting tensor content as java array of floats
        final float[] scores = outputTensor.getDataAsFloatArray();

        // searching for the index with maximum score
        String[] labels = new String[bitmaps.size()];

        final int classesLength = ImageNetClasses.IMAGENET_CLASSES.length;
        for (int j = 0; j < bitmaps.size(); j++) {
            float maxScore = -Float.MAX_VALUE;
            int maxScoreIdx = -1;

            for (int k = j * classesLength;
                 k < (j + 1) * classesLength;
                 k++) {
                if (scores[k] > maxScore) {
                    maxScore = scores[k];
                    maxScoreIdx = k - j * classesLength;
                }
            }

            labels[j] = ImageNetClasses.IMAGENET_CLASSES[maxScoreIdx];
        }

        // pop a toast with the className

        // deselect the true negatives
                /*
                if (className == "goldfish, Carassius auratus") {
                    m.setSelected(false);
                    adapter.notifyItemChanged(i);
                }
                 */

        return labels;
    }

    protected void onProgressUpdate(Integer... progress) {
    }

    protected void onPostExecute(String[] result) {
        for (String label : result) {
            Toast.makeText(_adapter.getContext(), label, Toast.LENGTH_SHORT).show();
        }

    }



    private Tensor bitmapsToFloat32Tensor(
            final ArrayList<Bitmap> bitmaps, // only equally-sized bitmaps
            int width,
            int height) {
        final float[] normMeanRGB = TensorImageUtils.TORCHVISION_NORM_MEAN_RGB;
        final float[] normStdRGB = TensorImageUtils.TORCHVISION_NORM_STD_RGB;

        checkNormMeanArg(normMeanRGB);
        checkNormStdArg(normStdRGB);

        final FloatBuffer floatBuffer = Tensor.allocateFloatBuffer(bitmaps.size() * 3 * width * height);
        for (int i = 0; i < bitmaps.size(); i++) {
            bitmapToFloatBuffer(bitmaps.get(i), 0, 0, width, height, normMeanRGB, normStdRGB, floatBuffer, i * 3 * width * height);
        }
        return Tensor.fromBlob(floatBuffer, new long[] {bitmaps.size(), 3, height, width});
    }

    private void bitmapToFloatBuffer(
            final Bitmap bitmap,
            final int x,
            final int y,
            final int width,
            final int height,
            final float[] normMeanRGB,
            final float[] normStdRGB,
            final FloatBuffer outBuffer,
            final int outBufferOffset) {

        checkOutBufferCapacity(outBuffer, outBufferOffset, width, height);
        checkNormMeanArg(normMeanRGB);
        checkNormStdArg(normStdRGB);

        final int pixelsCount = height * width;
        final int[] pixels = new int[pixelsCount];
        bitmap.getPixels(pixels, 0, width, x, y, width, height);
        final int offset_g = pixelsCount;
        final int offset_b = 2 * pixelsCount;
        for (int i = 0; i < pixelsCount; i++) {
            final int c = pixels[i];
            float r = ((c >> 16) & 0xff) / 255.0f;
            float g = ((c >> 8) & 0xff) / 255.0f;
            float b = ((c) & 0xff) / 255.0f;
            float rF = (r - normMeanRGB[0]) / normStdRGB[0];
            float gF = (g - normMeanRGB[1]) / normStdRGB[1];
            float bF = (b - normMeanRGB[2]) / normStdRGB[2];
            outBuffer.put(outBufferOffset + i, rF);
            outBuffer.put(outBufferOffset + offset_g + i, gF);
            outBuffer.put(outBufferOffset + offset_b + i, bF);
        }
    }

    private void checkOutBufferCapacity(
            FloatBuffer outBuffer, int outBufferOffset, int tensorWidth, int tensorHeight) {
        if (outBufferOffset + 3 * tensorWidth * tensorHeight > outBuffer.capacity()) {
            throw new IllegalStateException("Buffer underflow");
        }
    }

    private void checkNormMeanArg(float[] normMeanRGB) {
        if (normMeanRGB.length != 3) {
            throw new IllegalArgumentException("normMeanRGB length must be 3");
        }
    }

    private static void checkNormStdArg(float[] normStdRGB) {
        if (normStdRGB.length != 3) {
            throw new IllegalArgumentException("normStdRGB length must be 3");
        }
    }
}
