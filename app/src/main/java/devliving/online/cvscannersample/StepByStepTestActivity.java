package devliving.online.cvscannersample;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.RecyclerView.ViewHolder;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Range;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import devliving.online.cvscanner.CVProcessor;
import devliving.online.cvscanner.Document;

/**
 * Created by user on 9/22/16.
 */
public class StepByStepTestActivity extends AppCompatActivity{

    final int REQ_PICK_IMAGE = 1;

    RecyclerView contentView;
    ImageAdapter mAdapter;
    FloatingActionButton fab;

    Mat mData = null;

    BaseLoaderCallback mCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            super.onManagerConnected(status);

            fab.setScaleX(0.1f);
            fab.setScaleY(0.1f);
            fab.setAlpha(0.4f);
            fab.setVisibility(View.VISIBLE);

            fab.animate()
                    .alpha(1)
                    .scaleX(1)
                    .scaleY(1)
                    .start();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_step_by_step);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(intent, REQ_PICK_IMAGE);
            }
        });

        contentView = (RecyclerView) findViewById(R.id.image_list);

        contentView.setLayoutManager(new LinearLayoutManager(this));
        //contentView.setHasFixedSize(true);

        mAdapter = new ImageAdapter();
        contentView.setAdapter(mAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if(!OpenCVLoader.initDebug()){
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, getApplicationContext(), mCallback);
        }
        else mCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        mAdapter.clear();
        if(mData != null){
            mData.release();
            mData = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null){
            try {
                Bitmap image = BitmapFactory.decodeStream(getContentResolver().openInputStream(data.getData()));
                if(mData != null){
                    mData.release();
                    mData = null;
                }
                mData = new Mat();
                Utils.bitmapToMat(image, mData);
                image.recycle();

                startTests();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    HandlerThread imgThread;
    CVTestRunner testRunner;

    void startTests(){
        mAdapter.clear();

        if(imgThread == null || testRunner == null) {
            imgThread = new HandlerThread("Image processor thread");
            imgThread.start();

            testRunner = new CVTestRunner(imgThread.getLooper());
        }

        if(mData != null){
            Message msg = new Message();
            msg.obj = new CVTestMessage(CVCommand.START_DOCUMENT_SCAN_1, mData);
            testRunner.sendMessage(msg);
        }
    }

    void onNextStep(Mat img){
        Bitmap result = Bitmap.createBitmap(img.cols(), img.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(img, result);
        final String path = Utility.saveBitmapJPG(result, "cvsample_" + Calendar.getInstance().getTimeInMillis() + ".jpg");

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mAdapter.add(path);
            }
        });
    }

    enum CVCommand {
        START_BORDER_DETECTION,
        START_DOCUMENT_SCAN_1,
        START_DOCUMENT_SCAN_2;
    }

    class CVTestMessage {
        CVCommand command;
        Mat input;

        public CVTestMessage(CVCommand cmd, Mat input){
            command = cmd;
            this.input = input;
        }
    }

    class CVTestRunner extends Handler{
        final String TAG = "CV-TEST";

        public CVTestRunner(Looper looper){
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final int FIXED_HEIGHT = 800;

            if(msg.obj != null && msg.obj instanceof CVTestMessage){
                CVTestMessage data = (CVTestMessage) msg.obj;

                switch (data.command){
                    case START_BORDER_DETECTION:
                        Log.d(TAG, "*** --> Processing start.");

                        Mat src = data.input.clone();
                        Log.d(TAG, "1 original: " + src.toString());
                        onNextStep(src);
                        Imgproc.GaussianBlur(src, src, new Size(3, 3), 0);
                        Log.d(TAG, "2.1 --> Gaussian blur done\n blur: " + src.toString());
                        onNextStep(src);
                        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY);
                        Log.d(TAG, "2.2 --> Grayscaling done\n gray: " + src.toString());
                        onNextStep(src);

                        Mat sobelX = new Mat();
                        Mat sobelY = new Mat();

                        Imgproc.Sobel(src, sobelX, CvType.CV_32FC1, 2, 0, 5, 1, 0);
                        Log.d(TAG, "3.1 --> Sobel done.\n X: " + sobelX.toString());
                        Imgproc.Sobel(src, sobelY, CvType.CV_32FC1, 0, 2, 5, 1, 0);
                        Log.d(TAG, "3.2 --> Sobel done.\n Y: " + sobelY.toString());

                        Mat sum_img = new Mat();
                        Core.addWeighted(sobelX, 0.5, sobelY, 0.5, 0.5, sum_img);
                        //Core.add(sobelX, sobelY, sum_img);
                        Log.d(TAG, "4 --> Addition done. sum: " + sum_img.toString());

                        sobelX.release();
                        sobelY.release();

                        Mat gray = new Mat();
                        Core.normalize(sum_img, gray, 0, 255, Core.NORM_MINMAX, CvType.CV_8UC1);
                        Log.d(TAG, "5 --> Normalization done. gray: " + gray.toString());
                        onNextStep(gray);
                        sum_img.release();

                        Mat row_proj = new Mat();
                        Mat col_proj = new Mat();
                        Core.reduce(gray, row_proj, 1, Core.REDUCE_AVG, CvType.CV_8UC1);
                        Log.d(TAG, "6.1 --> Reduce done. row: " + row_proj.toString());
                        onNextStep(row_proj);
                        Core.reduce(gray, col_proj, 0, Core.REDUCE_AVG, CvType.CV_8UC1);
                        Log.d(TAG, "6.2 --> Reduce done. col: " + col_proj.toString());
                        onNextStep(col_proj);
                        gray.release();

                        Imgproc.Sobel(row_proj, row_proj, CvType.CV_8UC1, 0, 2);
                        Log.d(TAG, "7.1 --> Sobel done. row: " + row_proj.toString());
                        onNextStep(row_proj);
                        Imgproc.Sobel(col_proj, col_proj, CvType.CV_8UC1, 2, 0);
                        Log.d(TAG, "7.2 --> Sobel done. col: " + col_proj.toString());
                        onNextStep(col_proj);

                        Rect result = new Rect();

                        int half_pos = (int) (row_proj.total()/2);
                        Mat row_sub = new Mat(row_proj, new Range(0, half_pos), new Range(0, 1));
                        Log.d(TAG, "8.1 --> Copy sub matrix done. row: " + row_sub.toString());
                        result.y = (int) Core.minMaxLoc(row_sub).maxLoc.y;
                        Log.d(TAG, "8.2 --> Minmax done. Y: " + result.y);
                        row_sub.release();
                        Mat row_sub2 = new Mat(row_proj, new Range(half_pos, (int) row_proj.total()), new Range(0, 1));
                        Log.d(TAG, "8.3 --> Copy sub matrix done. row: " + row_sub2.toString());
                        result.height = (int) (Core.minMaxLoc(row_sub2).maxLoc.y + half_pos - result.y);
                        Log.d(TAG, "8.4 --> Minmax done. Height: " + result.height);
                        row_sub2.release();

                        half_pos = (int) (col_proj.total()/2);
                        Mat col_sub = new Mat(col_proj, new Range(0, 1), new Range(0, half_pos));
                        Log.d(TAG, "9.1 --> Copy sub matrix done. col: " + col_sub.toString());
                        result.x = (int) Core.minMaxLoc(col_sub).maxLoc.x;
                        Log.d(TAG, "9.2 --> Minmax done. X: " + result.x);
                        col_sub.release();
                        Mat col_sub2 = new Mat(col_proj, new Range(0, 1), new Range(half_pos, (int) col_proj.total()));
                        Log.d(TAG, "9.3 --> Copy sub matrix done. col: " + col_sub2.toString());
                        result.width = (int) (Core.minMaxLoc(col_sub2).maxLoc.x + half_pos - result.x);
                        Log.d(TAG, "9.4 --> Minmax done. Width: " + result.width);
                        col_sub2.release();

                        row_proj.release();
                        col_proj.release();
                        src.release();
                        Imgproc.rectangle(data.input, new Point(result.x, result.y),
                                new Point(result.x + result.width, result.y + result.height), new Scalar(0, 255, 200, 255), 8);
                        onNextStep(data.input);
                        data.input.release();
                        Log.d(TAG, "*** --> Processing done.");
                        break;

                    case START_DOCUMENT_SCAN_1:
                        Mat img = data.input.clone();
                        data.input.release();
                        //find contours
                        double ratio = img.size().height/FIXED_HEIGHT;
                        int width = (int) (img.size().width / ratio);
                        int height = (int) (img.size().height / ratio);
                        Size newSize = new Size(width, height);
                        Mat resizedImg = new Mat(newSize, CvType.CV_8UC4);
                        Imgproc.resize(img, resizedImg, newSize);
                        onNextStep(resizedImg);

                        Imgproc.medianBlur(resizedImg, resizedImg, 5);
                        onNextStep(resizedImg);

                        Mat cannedImg = new Mat(newSize, CvType.CV_8UC1);
                        Imgproc.Canny(resizedImg, cannedImg, 70, 200, 3, true);
                        resizedImg.release();
                        onNextStep(cannedImg);

                        Imgproc.threshold(cannedImg, cannedImg, 70, 255, Imgproc.THRESH_OTSU);
                        onNextStep(cannedImg);

                        Mat dilatedImg = new Mat(newSize, CvType.CV_8UC1);
                        Mat morph = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
                        Imgproc.dilate(cannedImg, dilatedImg, morph, new Point(-1, -1), 2, 1, new Scalar(1));
                        cannedImg.release();
                        morph.release();
                        onNextStep(dilatedImg);

                        ArrayList<MatOfPoint> contours = new ArrayList<>();
                        Mat hierarchy = new Mat();
                        Imgproc.findContours(dilatedImg, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
                        hierarchy.release();

                        Log.d(TAG, "contours found: " + contours.size());

                        Collections.sort(contours, new Comparator<MatOfPoint>() {
                            @Override
                            public int compare(MatOfPoint o1, MatOfPoint o2) {
                                return Double.valueOf(Imgproc.contourArea(o2)).compareTo(Imgproc.contourArea(o1));
                            }
                        });

                        Imgproc.drawContours(dilatedImg, contours, 0, new Scalar(255, 255, 250));
                        onNextStep(dilatedImg);
                        dilatedImg.release();

                        MatOfPoint rectContour = null;
                        Point[] foundPoints = null;

                        for(MatOfPoint contour:contours){
                            MatOfPoint2f mat = new MatOfPoint2f(contour.toArray());
                            double peri = Imgproc.arcLength(mat, true);
                            MatOfPoint2f approx = new MatOfPoint2f();
                            Imgproc.approxPolyDP(mat, approx, 0.02 * peri, true);

                            Point[] points = approx.toArray();
                            Log.d("SCANNER", "approx size " + points.length);

                            if (points.length == 4) {
                                Point[] spoints = CVProcessor.sortPoints(points);

                                if (CVProcessor.insideArea(spoints, newSize)) {
                                    rectContour = contour;
                                    foundPoints = spoints;
                                    break;
                                }
                            }
                        }

                        if(rectContour != null){
                            Point[] scaledPoints = new Point[foundPoints.length];

                            for(int i = 0; i < foundPoints.length; i++){
                                scaledPoints[i] = new Point(foundPoints[i].x * ratio, foundPoints[i].y * ratio);
                            }
                            Log.d("SCANNER", "drawing lines");
                            Imgproc.line(img, scaledPoints[0], scaledPoints[1], new Scalar(250, 20, 20));
                            Imgproc.line(img, scaledPoints[0], scaledPoints[3], new Scalar(250, 20, 20));
                            Imgproc.line(img, scaledPoints[1], scaledPoints[2], new Scalar(250, 20, 20));
                            Imgproc.line(img, scaledPoints[3], scaledPoints[2], new Scalar(250, 20, 20));
                        }

                        onNextStep(img);
                        img.release();
                        break;

                    case START_DOCUMENT_SCAN_2:
                        img = data.input.clone();
                        data.input.release();
                        //find contours
                        ratio = img.size().height/FIXED_HEIGHT;
                        width = (int) (img.size().width / ratio);
                        height = (int) (img.size().height / ratio);
                        newSize = new Size(width, height);
                        resizedImg = new Mat(newSize, CvType.CV_8UC4);
                        Imgproc.resize(img, resizedImg, newSize);
                        onNextStep(resizedImg);

                        Imgproc.medianBlur(resizedImg, resizedImg, 5);
                        onNextStep(resizedImg);

                        Mat grayImg = new Mat();
                        Imgproc.cvtColor(resizedImg, grayImg, Imgproc.COLOR_RGBA2GRAY);
                        resizedImg.release();
                        onNextStep(grayImg);

                        Mat filtered = new Mat();
                        Imgproc.adaptiveThreshold(grayImg, filtered, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                                Imgproc.THRESH_BINARY, 11, 1.2);
                        grayImg.release();
                        onNextStep(filtered);

                        Imgproc.threshold(filtered, filtered, 180, 255, Imgproc.THRESH_OTSU);
                        onNextStep(filtered);

                        cannedImg = new Mat(newSize, CvType.CV_8UC1);
                        Imgproc.Canny(filtered, cannedImg, 70, 200, 3, true);
                        filtered.release();
                        onNextStep(cannedImg);

                        dilatedImg = new Mat(newSize, CvType.CV_8UC1);
                        morph = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
                        Imgproc.dilate(cannedImg, dilatedImg, morph, new Point(-1, -1), 2, 1, new Scalar(1));
                        cannedImg.release();
                        morph.release();
                        onNextStep(dilatedImg);

                        Mat kernel = new Mat();
                        Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
                        Imgproc.morphologyEx(dilatedImg, dilatedImg, Imgproc.MORPH_CLOSE, kernel);
                        kernel.release();
                        onNextStep(dilatedImg);

                        contours = new ArrayList<>();
                        hierarchy = new Mat();
                        Imgproc.findContours(dilatedImg, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
                        hierarchy.release();

                        Log.d(TAG, "contours found: " + contours.size());

                        Collections.sort(contours, new Comparator<MatOfPoint>() {
                            @Override
                            public int compare(MatOfPoint o1, MatOfPoint o2) {
                                return Double.valueOf(Imgproc.contourArea(o2)).compareTo(Imgproc.contourArea(o1));
                            }
                        });

                        Imgproc.drawContours(dilatedImg, contours, 0, new Scalar(255, 255, 250));
                        onNextStep(dilatedImg);
                        dilatedImg.release();

                        rectContour = null;
                        foundPoints = null;

                        for(MatOfPoint contour:contours){
                            MatOfPoint2f mat = new MatOfPoint2f(contour.toArray());
                            double peri = Imgproc.arcLength(mat, true);
                            MatOfPoint2f approx = new MatOfPoint2f();
                            Imgproc.approxPolyDP(mat, approx, 0.02 * peri, true);

                            Point[] points = approx.toArray();
                            Log.d("SCANNER", "approx size " + points.length);

                            if (points.length == 4) {
                                Point[] spoints = CVProcessor.sortPoints(points);

                                if (CVProcessor.insideArea(spoints, newSize)) {
                                    rectContour = contour;
                                    foundPoints = spoints;
                                    break;
                                }
                            }
                        }

                        if(rectContour != null){
                            Point[] scaledPoints = new Point[foundPoints.length];

                            for(int i = 0; i < foundPoints.length; i++){
                                scaledPoints[i] = new Point(foundPoints[i].x * ratio, foundPoints[i].y * ratio);
                            }
                            Log.d("SCANNER", "drawing lines");
                            Imgproc.line(img, scaledPoints[0], scaledPoints[1], new Scalar(200, 200, 140));
                            Imgproc.line(img, scaledPoints[0], scaledPoints[3], new Scalar(200, 200, 140));
                            Imgproc.line(img, scaledPoints[1], scaledPoints[2], new Scalar(200, 200, 140));
                            Imgproc.line(img, scaledPoints[3], scaledPoints[2], new Scalar(200, 200, 140));
                        }

                        onNextStep(img);
                        img.release();
                        break;
                }
            }

        }
    }
}