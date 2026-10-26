package com.winlator.cmod;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ShortcutSettingsDialog;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.FileUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ShortcutsFragment extends Fragment {
    private static final String TAG = "ShortcutsFragment";
    private static final String PREF_GRID_VIEW = "shortcuts_grid_view";
    private static final int MENU_TOGGLE_VIEW = 7001;
    private static final float GRID_COLUMN_WIDTH_DP = 150f;
    private static final int MIN_GRID_COLUMNS = 2;
    private static final int MAX_ICON_WIDTH = 600;
    private static final int MAX_ICON_HEIGHT = 900;

    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private SharedPreferences preferences;
    private boolean isGridView = true;
    private DividerItemDecoration dividerItemDecoration;
    private Shortcut shortcutForIconUpdate;
    private ActivityResultLauncher<String> iconPickerLauncher;
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> pendingExtractions = new HashSet<>();
    private int loadGeneration = 0;
    private static final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(
            Math.max(4 * 1024, (int) (Runtime.getRuntime().maxMemory() / 1024 / 8))) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        iconPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && shortcutForIconUpdate != null) updateShortcutIcon(uri, shortcutForIconUpdate);
        });
    }

    @Override
    public void onDestroy() {
        imageExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (recyclerView != null && isGridView) recyclerView.post(this::applyGridSpanCount);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        loadShortcutsList();
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.shortcuts);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.shortcuts_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);

        preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        isGridView = preferences.getBoolean(PREF_GRID_VIEW, true);

        recyclerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (isGridView && (right - left) != (oldRight - oldLeft)) recyclerView.post(this::applyGridSpanCount);
        });

        updateLayoutManager();
        return frameLayout;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        MenuItem item = menu.add(0, MENU_TOGGLE_VIEW, 0, isGridView ? "List View" : "Grid View");
        item.setIcon(isGridView ? android.R.drawable.ic_menu_agenda : android.R.drawable.ic_menu_gallery);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_TOGGLE_VIEW) {
            isGridView = !isGridView;
            preferences.edit().putBoolean(PREF_GRID_VIEW, isGridView).apply();
            updateLayoutManager();
            getActivity().invalidateOptionsMenu();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int calculateSpanCount() {
        float density = getResources().getDisplayMetrics().density;
        int width = recyclerView.getWidth();
        float widthDp = width > 0 ? width / density : getResources().getConfiguration().screenWidthDp;
        return Math.max(MIN_GRID_COLUMNS, (int) (widthDp / GRID_COLUMN_WIDTH_DP));
    }

    private void applyGridSpanCount() {
        if (recyclerView == null || !isGridView) return;
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager instanceof GridLayoutManager) {
            int span = calculateSpanCount();
            GridLayoutManager gridLayoutManager = (GridLayoutManager) layoutManager;
            if (gridLayoutManager.getSpanCount() != span) gridLayoutManager.setSpanCount(span);
        }
    }

    private void updateLayoutManager() {
        if (isGridView) {
            recyclerView.setLayoutManager(new GridLayoutManager(getContext(), calculateSpanCount()));
            if (dividerItemDecoration != null) {
                recyclerView.removeItemDecoration(dividerItemDecoration);
                dividerItemDecoration = null;
            }
            int pad = (int) (4 * getResources().getDisplayMetrics().density);
            recyclerView.setPadding(pad, pad, pad, pad);
            recyclerView.setClipToPadding(false);
            recyclerView.post(this::applyGridSpanCount);
        } else {
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            recyclerView.setPadding(0, 0, 0, 0);
            if (dividerItemDecoration == null) {
                dividerItemDecoration = new DividerItemDecoration(getContext(), DividerItemDecoration.VERTICAL);
                recyclerView.addItemDecoration(dividerItemDecoration);
            }
        }
        if (recyclerView.getAdapter() != null) recyclerView.setAdapter(recyclerView.getAdapter());
    }

    private static ArrayList<Shortcut> preloadedShortcuts;
    private static boolean preloadedGrid;
    private static long preloadedAt;
    private static final ExecutorService PRELOAD_EXECUTOR = Executors.newSingleThreadExecutor();

    public static void preload(Context context, ContainerManager manager, Runnable onReady) {
        final Context appContext = context.getApplicationContext();
        final boolean grid = PreferenceManager.getDefaultSharedPreferences(appContext).getBoolean(PREF_GRID_VIEW, true);
        PRELOAD_EXECUTOR.execute(() -> {
            final ArrayList<Shortcut> shortcuts = loadAndWarm(appContext, manager, grid);
            new Handler(Looper.getMainLooper()).post(() -> {
                synchronized (ShortcutsFragment.class) {
                    preloadedShortcuts = shortcuts;
                    preloadedGrid = grid;
                    preloadedAt = System.currentTimeMillis();
                }
                onReady.run();
            });
        });
    }

    private static synchronized ArrayList<Shortcut> takePreloaded(boolean grid) {
        ArrayList<Shortcut> result = preloadedShortcuts;
        boolean usable = result != null && preloadedGrid == grid && System.currentTimeMillis() - preloadedAt < 10000;
        preloadedShortcuts = null;
        return usable ? result : null;
    }

    private static ArrayList<Shortcut> loadAndWarm(Context context, ContainerManager manager, boolean grid) {
        ArrayList<Shortcut> loaded;
        try {
            loaded = manager.loadShortcuts();
            loaded.removeIf(shortcut -> shortcut == null || shortcut.file == null || shortcut.file.getName().isEmpty());
        }
        catch (Exception e) {
            Log.e(TAG, "Failed to load shortcuts", e);
            loaded = new ArrayList<>();
        }
        final ArrayList<Shortcut> shortcuts = loaded;

        File iconsDir = getImagesDir(context, false);
        File autoDir = getImagesDir(context, grid);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Shortcut shortcut : shortcuts) {
            String baseName = FileUtils.getBasename(shortcut.file.getPath());
            File userIcon = new File(iconsDir, baseName + ".user.png");
            final File shown = userIcon.exists() ? userIcon : new File(autoDir, baseName + ".png");
            if (shown.exists() && bitmapCache.get(cacheKey(shown)) == null) {
                tasks.add(() -> {
                    if (bitmapCache.size() > bitmapCache.maxSize() * 0.9) return null;
                    Bitmap bitmap = decodeSampled(shown, MAX_ICON_WIDTH, MAX_ICON_HEIGHT);
                    if (bitmap != null) bitmapCache.put(cacheKey(shown), bitmap);
                    return null;
                });
            }
        }

        if (!tasks.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.invokeAll(tasks, 200, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {}
            finally {
                pool.shutdownNow();
            }
        }
        return shortcuts;
    }

    public void loadShortcutsList() {
        final int generation = ++loadGeneration;
        final boolean grid = isGridView;

        final ArrayList<Shortcut> ready = takePreloaded(grid);
        if (ready != null) {
            emptyTextView.setVisibility(ready.isEmpty() ? View.VISIBLE : View.GONE);
            recyclerView.setAdapter(new ShortcutsAdapter(ready));
            return;
        }

        final Context appContext = getContext().getApplicationContext();
        if (recyclerView.getAdapter() == null) emptyTextView.setVisibility(View.GONE);

        imageExecutor.execute(() -> {
            final ArrayList<Shortcut> shortcuts = loadAndWarm(appContext, manager, grid);
            mainHandler.post(() -> {
                if (!isAdded() || recyclerView == null || generation != loadGeneration) return;
                emptyTextView.setVisibility(shortcuts.isEmpty() ? View.VISIBLE : View.GONE);
                recyclerView.setAdapter(new ShortcutsAdapter(shortcuts));
            });
        });
    }

    private File getImagesDir(boolean isCover) {
        return getImagesDir(getContext(), isCover);
    }

    private static File getImagesDir(Context context, boolean isCover) {
        File dir = new File(Environment.getExternalStorageDirectory(), isCover ? "Winlator/covers" : "Winlator/icons");
        if ((dir.isDirectory() || dir.mkdirs()) && dir.canWrite()) {
            File nomedia = new File(dir, ".nomedia");
            if (!nomedia.exists()) {
                try { nomedia.createNewFile(); } catch (IOException e) {}
            }
            return dir;
        }
        File fallback = new File(context.getFilesDir(), isCover ? "shortcut_images/covers" : "shortcut_images/icons");
        fallback.mkdirs();
        return fallback;
    }

    private File getUserIconFile(Shortcut shortcut) {
        return new File(getImagesDir(false), FileUtils.getBasename(shortcut.file.getPath()) + ".user.png");
    }

    private static Bitmap decodeSampled(File file, int maxW, int maxH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH);
        return BitmapFactory.decodeFile(file.getPath(), options);
    }

    private static int computeSampleSize(int width, int height, int maxW, int maxH) {
        int sample = 1;
        while ((width / (sample * 2)) >= maxW || (height / (sample * 2)) >= maxH) sample *= 2;
        return sample;
    }

    private void updateShortcutIcon(Uri sourceUri, Shortcut shortcut) {
        final Context context = getContext();
        final File destFile = getUserIconFile(shortcut);
        imageExecutor.execute(() -> {
            boolean success = false;
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream is = context.getContentResolver().openInputStream(sourceUri)) {
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = is.read(chunk)) > 0) buffer.write(chunk, 0, read);
                }
                byte[] data = buffer.toByteArray();

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MAX_ICON_WIDTH, MAX_ICON_HEIGHT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);

                if (bitmap != null) {
                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                    bitmap.recycle();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to save icon", e);
            }

            final boolean ok = success;
            mainHandler.post(() -> {
                if (getContext() == null) return;
                Toast.makeText(getContext(), ok ? "Icon updated!" : "Error saving icon", Toast.LENGTH_SHORT).show();
                if (ok && recyclerView != null && recyclerView.getAdapter() != null) {
                    recyclerView.getAdapter().notifyDataSetChanged();
                }
            });
        });
    }

    private void resetShortcutIcon(Shortcut shortcut) {
        File userIcon = getUserIconFile(shortcut);
        if (userIcon.exists() && userIcon.delete()) {
            Toast.makeText(getContext(), "Icon reset", Toast.LENGTH_SHORT).show();
            if (recyclerView.getAdapter() != null) recyclerView.getAdapter().notifyDataSetChanged();
        }
    }

    private File resolveExeFile(Shortcut item) {
        if (item.path == null || item.path.isEmpty()) return null;

        String path = item.path.replace("\\", "/").trim();
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() > 1) path = path.substring(1, path.length() - 1);

        if (path.startsWith("/")) {
            File f = new File(path);
            if (f.exists()) return f;
        }

        if (path.length() >= 3 && path.charAt(1) == ':' && path.charAt(2) == '/') {
            String drive = path.substring(0, 1).toLowerCase();
            String relative = path.substring(3);

            if (item.container != null) {
                for (String[] entry : item.container.drivesIterator()) {
                    if (entry == null || entry.length < 2 || entry[0] == null || entry[1] == null) continue;
                    if (entry[0].replace(":", "").trim().equalsIgnoreCase(drive)) {
                        File f = new File(entry[1], relative);
                        if (f.exists()) return f;
                    }
                }
            }

            switch (drive) {
                case "c": {
                    File root = item.container != null ? item.container.getRootDir() : null;
                    if (root != null) {
                        File f = new File(root, ".wine/drive_c/" + relative);
                        if (f.exists()) return f;
                    }
                    break;
                }
                case "d": {
                    File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relative);
                    if (f.exists()) return f;
                    f = new File(Environment.getExternalStorageDirectory(), relative);
                    if (f.exists()) return f;
                    break;
                }
                case "z": {
                    File f = new File("/" + relative);
                    if (f.exists()) return f;
                    break;
                }
            }
        }
        return null;
    }

    private static String cacheKey(File file) {
        return file.getPath() + "@" + file.lastModified();
    }

    private void showFileInto(ImageView imageView, File file, boolean grid, Shortcut item, String tag, boolean custom) {
        String key = cacheKey(file);
        Bitmap cached = bitmapCache.get(key);
        if (cached != null) {
            imageView.setScaleType(grid ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            imageView.setImageBitmap(cached);
            return;
        }
        imageView.setImageDrawable(null);
        imageExecutor.execute(() -> {
            Bitmap bitmap = decodeSampled(file, MAX_ICON_WIDTH, MAX_ICON_HEIGHT);
            if (bitmap == null) {
                mainHandler.post(() -> {
                    if (tag.equals(imageView.getTag())) showFallback(imageView, item, grid);
                });
                return;
            }
            bitmapCache.put(key, bitmap);
            mainHandler.post(() -> {
                if (tag.equals(imageView.getTag())) {
                    imageView.setScaleType(grid ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
                    imageView.setImageBitmap(bitmap);
                    imageView.setAlpha(0f);
                    imageView.animate().alpha(1f).setDuration(150).start();
                }
            });
        });
    }

    private void showFallback(ImageView imageView, Shortcut item, boolean grid) {
        imageView.setScaleType(grid ? ImageView.ScaleType.CENTER_INSIDE : ImageView.ScaleType.FIT_CENTER);
        if (item.icon != null) imageView.setImageBitmap(item.icon);
        else imageView.setImageResource(R.drawable.icon_wine);
    }

    private void bindShortcutImage(ImageView imageView, Shortcut item, boolean grid) {
        String baseName = FileUtils.getBasename(item.file.getPath());
        String tag = item.file.getPath() + (grid ? "#grid" : "#list");
        imageView.animate().cancel();
        imageView.setAlpha(1f);
        imageView.setTag(tag);

        File userIcon = new File(getImagesDir(false), baseName + ".user.png");
        File autoFile = new File(getImagesDir(grid), baseName + ".png");

        if (userIcon.exists()) {
            showFileInto(imageView, userIcon, grid, item, tag, true);
            return;
        }
        if (autoFile.exists()) {
            showFileInto(imageView, autoFile, grid, item, tag, false);
            return;
        }

        showFallback(imageView, item, grid);

        File exeFile = resolveExeFile(item);
        if (exeFile == null || !pendingExtractions.add(autoFile.getPath())) return;
        ExeIconExtractor.extractAsync(exeFile, autoFile, grid, () -> mainHandler.post(() -> {
            pendingExtractions.remove(autoFile.getPath());
            if (autoFile.exists() && tag.equals(imageView.getTag())) showFileInto(imageView, autoFile, grid, item, tag, false);
        }));
    }

    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final View menuButton;
            private final ImageView imageView;
            private final TextView title;
            private final TextView subtitle;
            private final View innerArea;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.subtitle = view.findViewById(R.id.TVSubtitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
                this.innerArea = view.findViewById(R.id.LLInnerArea);
            }
        }

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @Override
        public int getItemViewType(int position) {
            return isGridView ? 1 : 0;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layoutId = viewType == 1 ? R.layout.shortcut_grid_item : R.layout.shortcut_list_item;
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            if (holder.menuButton != null) holder.menuButton.setOnClickListener(null);
            holder.innerArea.setOnClickListener(null);
            holder.innerArea.setOnLongClickListener(null);
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Shortcut item = data.get(position);
            holder.title.setText(item.name);
            holder.subtitle.setText(item.container.getName());
            holder.innerArea.setOnClickListener((v) -> runFromShortcut(item));

            if (isGridView) {
                if (holder.menuButton != null) holder.menuButton.setVisibility(View.GONE);
                holder.innerArea.setOnLongClickListener(v -> {
                    showListItemMenu(holder.title, item);
                    return true;
                });
                try {
                    androidx.constraintlayout.widget.ConstraintLayout.LayoutParams params =
                            (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) holder.imageView.getLayoutParams();
                    params.dimensionRatio = "3:4";
                    holder.imageView.setLayoutParams(params);
                } catch (ClassCastException ignored) {}
            } else {
                holder.innerArea.setOnLongClickListener(null);
                if (holder.menuButton != null) {
                    holder.menuButton.setVisibility(View.VISIBLE);
                    holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, item));
                }
            }
            bindShortcutImage(holder.imageView, item, isGridView);
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, final Shortcut shortcut) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.shortcut_popup_menu);
            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.shortcut_settings) {
                    (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                }
                else if (itemId == R.id.shortcut_change_icon) {
                    shortcutForIconUpdate = shortcut;
                    iconPickerLauncher.launch("image/*");
                }
                else if (itemId == R.id.shortcut_reset_icon) {
                    resetShortcutIcon(shortcut);
                }
                else if (itemId == R.id.shortcut_remove) {
                    ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_shortcut, () -> {
                        boolean fileDeleted = shortcut.file.delete();
                        File lnkFile = new File(shortcut.file.getPath().substring(0, shortcut.file.getPath().lastIndexOf(".")) + ".lnk");
                        if (lnkFile.exists()) lnkFile.delete();

                        if (fileDeleted) {
                            disableShortcutOnScreen(requireContext(), shortcut);
                            loadShortcutsList();
                            Toast.makeText(context, "Shortcut removed successfully.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, "Failed to remove the shortcut. Please try again.", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
                else if (itemId == R.id.shortcut_clone_to_container) {
                    ContainerManager containerManager = new ContainerManager(context);
                    ArrayList<Container> containers = containerManager.getContainers();
                    showContainerSelectionDialog(containers, new OnContainerSelectedListener() {
                        @Override
                        public void onContainerSelected(Container selectedContainer) {
                            if (shortcut.cloneToContainer(selectedContainer)) {
                                Toast.makeText(context, "Shortcut cloned successfully.", Toast.LENGTH_SHORT).show();
                                loadShortcutsList();
                            } else {
                                Toast.makeText(context, "Failed to clone shortcut.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
                else if (itemId == R.id.shortcut_add_to_home_screen) {
                    if (shortcut.getExtra("uuid").equals("")) shortcut.genUUID();
                    addShortcutToScreen(shortcut);
                }
                else if (itemId == R.id.shortcut_export) {
                    exportShortcut(shortcut);
                }
                else if (itemId == R.id.shortcut_properties) {
                    showShortcutProperties(shortcut);
                }
                return true;
            });
            listItemMenu.show();
        }

        public interface OnContainerSelectedListener {
            void onContainerSelected(Container container);
        }

        private void showContainerSelectionDialog(ArrayList<Container> containers, OnContainerSelectedListener listener) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("Select a container");
            String[] containerNames = new String[containers.size()];
            for (int i = 0; i < containers.size(); i++) containerNames[i] = containers.get(i).getName();
            builder.setItems(containerNames, (dialog, which) -> listener.onContainerSelected(containers.get(which)));
            builder.show();
        }

        private void runFromShortcut(Shortcut shortcut) {
            Activity activity = getActivity();

            if (!XrActivity.isEnabled(getContext())) {
                Intent intent = new Intent(activity, XServerDisplayActivity.class);
                intent.putExtra("container_id", shortcut.container.id);
                intent.putExtra("shortcut_path", shortcut.file.getPath());
                intent.putExtra("shortcut_name", shortcut.name);
                String disableXinputValue = shortcut.getExtra("disableXinput", "0");
                intent.putExtra("disableXinput", disableXinputValue);
                activity.startActivity(intent);
            }
            else XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
        }

        private void exportShortcut(Shortcut shortcut) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            String uriString = sharedPreferences.getString("shortcuts_export_path_uri", null);

            File shortcutsDir;

            if (uriString != null) {
                Uri folderUri = Uri.parse(uriString);
                DocumentFile pickedDir = DocumentFile.fromTreeUri(getContext(), folderUri);

                if (pickedDir == null || !pickedDir.canWrite()) {
                    Toast.makeText(getContext(), "Cannot write to the selected folder", Toast.LENGTH_SHORT).show();
                    return;
                }

                shortcutsDir = new File(FileUtils.getFilePathFromUri(getContext(), folderUri));
            } else {
                shortcutsDir = new File(SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH);
            }

            if (!shortcutsDir.exists() && !shortcutsDir.mkdirs()) {
                Toast.makeText(getContext(), "Failed to create default directory", Toast.LENGTH_SHORT).show();
                return;
            }

            File exportFile = new File(shortcutsDir, shortcut.file.getName());
            boolean fileExists = exportFile.exists();
            boolean containerIdFound = false;

            try {
                List<String> lines = new ArrayList<>();

                try (BufferedReader reader = new BufferedReader(new FileReader(shortcut.file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("container_id:")) {
                            lines.add("container_id:" + shortcut.container.id);
                            containerIdFound = true;
                        } else {
                            lines.add(line);
                        }
                    }
                }

                if (!containerIdFound) lines.add("container_id:" + shortcut.container.id);

                try (FileWriter writer = new FileWriter(exportFile, false)) {
                    for (String line : lines) writer.write(line + "\n");
                    writer.flush();
                }

                Log.d(TAG, "Shortcut exported successfully to " + exportFile.getPath());

                String message = fileExists
                        ? "Shortcut Updated at " + exportFile.getPath()
                        : "Shortcut Exported to " + exportFile.getPath();
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();

            } catch (IOException e) {
                Log.e(TAG, "Failed to export shortcut", e);
                Toast.makeText(getContext(), "Failed to export shortcut", Toast.LENGTH_LONG).show();
            }
        }

        private void showShortcutProperties(Shortcut shortcut) {
            SharedPreferences playtimePrefs = getContext().getSharedPreferences("playtime_stats", Context.MODE_PRIVATE);

            String playtimeKey = shortcut.name + "_playtime";
            String playCountKey = shortcut.name + "_play_count";

            long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0);
            int playCount = playtimePrefs.getInt(playCountKey, 0);

            long seconds = (totalPlaytime / 1000) % 60;
            long minutes = (totalPlaytime / (1000 * 60)) % 60;
            long hours = (totalPlaytime / (1000 * 60 * 60)) % 24;
            long days = (totalPlaytime / (1000 * 60 * 60 * 24));

            String playtimeFormatted = String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);

            ContentDialog dialog = new ContentDialog(getContext(), R.layout.shortcut_properties_dialog);
            dialog.setTitle("Properties");

            TextView playCountTextView = dialog.findViewById(R.id.play_count);
            TextView playtimeTextView = dialog.findViewById(R.id.playtime);

            playCountTextView.setText("Number of times played: " + playCount);
            playtimeTextView.setText("Playtime: " + playtimeFormatted);

            Button resetPropertiesButton = dialog.findViewById(R.id.reset_properties);
            resetPropertiesButton.setOnClickListener(v -> {
                playtimePrefs.edit().remove(playtimeKey).remove(playCountKey).apply();
                Toast.makeText(getContext(), "Properties reset successfully.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            dialog.show();
        }
    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);

        return new ShortcutInfo.Builder(getActivity(), uuid)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    private void addShortcutToScreen(Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
            String baseName = FileUtils.getBasename(shortcut.file.getPath());
            File iconDir = getImagesDir(false);
            File userIcon = new File(iconDir, baseName + ".user.png");
            File autoIcon = new File(iconDir, baseName + ".png");

            Bitmap bitmap = null;
            if (userIcon.exists()) bitmap = decodeSampled(userIcon, 256, 256);
            if (bitmap == null && autoIcon.exists()) bitmap = decodeSampled(autoIcon, 256, 256);
            if (bitmap == null) bitmap = shortcut.icon;
            if (bitmap == null) bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.icon_wine);

            shortcutManager.requestPinShortcut(buildScreenShortCut(shortcut.name, shortcut.name, shortcut.container.id,
                    shortcut.file.getPath(), Icon.createWithBitmap(bitmap), shortcut.getExtra("uuid")), null);
        }
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
        try {
            shortcutManager.disableShortcuts(Collections.singletonList(shortcut.getExtra("uuid")),
                    context.getString(R.string.shortcut_not_available));
        } catch (Exception e) {}
    }

    public void updateShortcutOnScreen(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        try {
            for (ShortcutInfo shortcutInfo : shortcutManager.getPinnedShortcuts()) {
                if (shortcutInfo.getId().equals(uuid)) {
                    shortcutManager.updateShortcuts(Collections.singletonList(
                            buildScreenShortCut(shortLabel, longLabel, containerId, shortcutPath, icon, uuid)));
                    break;
                }
            }
        } catch (Exception e) {}
    }
}
