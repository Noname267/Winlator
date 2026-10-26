package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;

import java.io.File;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;
    private Bitmap artworkBitmap;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    public synchronized void show(int textResId) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        if (isShowing()) return;
        close();
        if (dialog == null) create();

        if (textResId == R.string.starting_up) configureLaunchScreen(textResId);
        else configureStandardPreloader(textResId);

        dialog.show();

        if (textResId == R.string.starting_up) {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        }
    }

    private void configureLaunchScreen(int textResId) {
        View root = dialog.findViewById(R.id.PreloaderRoot);
        View launchScrim = dialog.findViewById(R.id.LaunchScrim);
        View launchInfo = dialog.findViewById(R.id.LaunchInfo);
        View loadingPanel = dialog.findViewById(R.id.LoadingPanel);
        View launchProgress = dialog.findViewById(R.id.LaunchProgress);
        ImageView artworkView = dialog.findViewById(R.id.LaunchArtwork);
        TextView launchTitle = dialog.findViewById(R.id.LaunchTitle);
        TextView launchStatus = dialog.findViewById(R.id.LaunchStatus);

        root.setBackgroundColor(0xFF000000);
        loadingPanel.setVisibility(View.GONE);
        launchInfo.setVisibility(View.VISIBLE);
        launchProgress.setVisibility(View.VISIBLE);
        launchTitle.setText(resolveLaunchTitle());
        launchStatus.setText(textResId);

        releaseArtwork();
        File artwork = findLaunchArtwork();
        if (artwork != null) artworkBitmap = decodeArtwork(artwork);

        boolean hasArtwork = artworkBitmap != null;
        if (hasArtwork) {
            artworkView.setImageBitmap(artworkBitmap);
            artworkView.setVisibility(View.VISIBLE);
        }
        else {
            artworkView.setImageDrawable(null);
            artworkView.setVisibility(View.GONE);
        }
        launchScrim.setVisibility(hasArtwork ? View.VISIBLE : View.GONE);
    }

    private void configureStandardPreloader(int textResId) {
        View root = dialog.findViewById(R.id.PreloaderRoot);
        View launchScrim = dialog.findViewById(R.id.LaunchScrim);
        View launchInfo = dialog.findViewById(R.id.LaunchInfo);
        View loadingPanel = dialog.findViewById(R.id.LoadingPanel);
        View launchProgress = dialog.findViewById(R.id.LaunchProgress);
        ImageView artworkView = dialog.findViewById(R.id.LaunchArtwork);

        releaseArtwork();
        artworkView.setImageDrawable(null);
        artworkView.setVisibility(View.GONE);
        launchScrim.setVisibility(View.GONE);
        launchInfo.setVisibility(View.GONE);
        launchProgress.setVisibility(View.GONE);
        loadingPanel.setVisibility(View.VISIBLE);
        root.setBackgroundColor(0);
        ((TextView)dialog.findViewById(R.id.TextView)).setText(textResId);
    }

    private String resolveLaunchTitle() {
        Intent intent = activity.getIntent();
        String title = intent != null ? intent.getStringExtra("shortcut_name") : null;
        String shortcutPath = intent != null ? intent.getStringExtra("shortcut_path") : null;

        if (TextUtils.isEmpty(title) && !TextUtils.isEmpty(shortcutPath)) {
            title = FileUtils.getBasename(shortcutPath);
        }

        if (TextUtils.isEmpty(title) && intent != null) {
            int containerId = intent.getIntExtra("container_id", 0);
            if (containerId > 0) {
                try {
                    Container container = new ContainerManager(activity).getContainerById(containerId);
                    if (container != null && !TextUtils.isEmpty(container.getName())) title = container.getName();
                }
                catch (Exception e) {}
            }
        }

        return TextUtils.isEmpty(title) ? activity.getString(R.string.app_name) : title;
    }

    private File findLaunchArtwork() {
        Intent intent = activity.getIntent();
        String shortcutPath = intent != null ? intent.getStringExtra("shortcut_path") : null;
        if (TextUtils.isEmpty(shortcutPath)) return null;
        String baseName = FileUtils.getBasename(shortcutPath);
        if (TextUtils.isEmpty(baseName)) return null;

        File root = Environment.getExternalStorageDirectory();
        File userArtwork = new File(new File(root, "Winlator/icons"), baseName + ".user.png");
        if (isUsableImageFile(userArtwork)) return userArtwork;
        File cover = new File(new File(root, "Winlator/covers"), baseName + ".png");
        if (isUsableImageFile(cover)) return cover;
        return null;
    }

    private boolean isUsableImageFile(File file) {
        return file != null && file.isFile() && file.length() > 0;
    }

    private Bitmap decodeArtwork(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int targetWidth = Math.max(1, activity.getResources().getDisplayMetrics().widthPixels);
            int targetHeight = Math.max(1, activity.getResources().getDisplayMetrics().heightPixels);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
                sample *= 2;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        }
        catch (Throwable e) {
            return null;
        }
    }

    private void releaseArtwork() {
        if (artworkBitmap != null) {
            try {
                if (!artworkBitmap.isRecycled()) artworkBitmap.recycle();
            }
            catch (Exception e) {}
            artworkBitmap = null;
        }
    }

    public void showOnUiThread(final int textResId) {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        activity.runOnUiThread(() -> show(textResId));
    }

    public synchronized void close() {
        try {
            if (dialog != null) {
                ImageView artworkView = dialog.findViewById(R.id.LaunchArtwork);
                if (artworkView != null) artworkView.setImageDrawable(null);
                dialog.dismiss();
            }
        }
        catch (Exception e) {}
        releaseArtwork();
        AppUtils.hideKeyboard(activity);
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
