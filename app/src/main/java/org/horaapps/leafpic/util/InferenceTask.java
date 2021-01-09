package org.horaapps.leafpic.util;

import android.content.Context;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.FloatBuffer;
import java.util.ArrayList;

public class InferenceTask extends AsyncTask<Void, Void, String[]> {
    private final WeakReference<MediaAdapter> adapterRef;
    private ArrayList<Media> media;
    private ArrayList<Integer> selectedIndices;

    public InferenceTask(MediaAdapter adapter) {
        this.adapterRef = new WeakReference<>(adapter);
    }

    @Override
    protected void onPreExecute() {
        media = adapterRef.get().getSelected();
        // get indices of selected media
        // relative to all media
        selectedIndices = new ArrayList<>();
        ArrayList<Media> allMedia = adapterRef.get().getMedia();
        for (int i = 0; i < allMedia.size(); i++)
            if (allMedia.get(i).isSelected())
                selectedIndices.add(i);
    }

    protected String[] doInBackground(Void... voids) {
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
            module = Module.load(assetFilePath(adapterRef.get().getContext(), "mobilenet.pt"));
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
        return labels;
    }

    // TODO: add a SwipeRefreshLayout or something for progress indication

    protected void onPostExecute(String[] result) {
        // deselect the true negatives
        for (int i = 0; i < media.size(); i++) {
            if (result[i].contains("grille")) {
                media.get(i).setSelected(false);
                adapterRef.get().notifyItemChanged(selectedIndices.get(i));
            }
        }
        adapterRef.get().invalidateSelectedCount();

        for (String label : result) {
            Toast.makeText(adapterRef.get().getContext(), label, Toast.LENGTH_SHORT).show();
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

    private String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        }
    }
}
